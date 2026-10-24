"""إشعارات فورية لمتصفحات الويب عبر Web Push (معيار RFC 8291/8292) — تشمل آيفون iOS 16.4+ بعد إضافة رفيق للشاشة الرئيسية.

التصميم (مطابق لقناة FCM):
- الرسالة «إيقاظ» فقط بلا أي محتوى من المستخدم؛ عامل الخدمة يعرض إشعارًا عامًّا ويجلب المحتوى داخل التطبيق عبر HTTPS.
- الاشتراك يُربط بالمستخدم الذي سجّله بجلسة موثّقة (webpush_subs.user_id) — الربط يتم من الجهاز نفسه فقط.
- نقطة النهاية قدرة وصول: لا تنتقل بين المستخدمين إلا بإثبات ملكية مفاتيحها (p256dh + auth).
- وجهات الإرسال مقيّدة بخدمات دفع معروفة (allowlist) وعناوين IP عامة فقط، وتُفحص عند التسجيل وقبل كل إرسال.
- مفاتيح VAPID تبقى على الخادم فقط (ملف .env)؛ العام يُسلَّم للمستخدمين الموثّقين عبر /api/push/config.
- فشل الإرسال لا يغيّر سلوك الإشعارات: يبقى الجرس والجلب الدوري في اللوحة احتياطيًا.
- لا تُسجَّل نقاط النهاية في السجلات أو رسائل الخطأ أو الحالة.
"""
from __future__ import annotations

import asyncio
import base64
import ipaddress
import json
import logging
import socket
import time
from urllib.parse import urlsplit

import httpx

log = logging.getLogger("rafiq.webpush")

MAX_SUBS_PER_USER = 10
MAX_FAILS = 5            # فشل متتالٍ قبل حذف الاشتراك
STALE_DAYS = 45          # ما لم يتجدد الاشتراك خلال المدة يُحذف
JWT_EXP = 12 * 3600      # صلاحية توقيع VAPID (الحد الأقصى المقبول 24 ساعة)
KEY_RE_LEN = (20, 4096)  # حدود طول مفاتيح الاشتراك المقبولة
PUSH_PORT = 443          # خدمات الدفع الشرعية تعمل على 443 فقط
SEND_TIMEOUT = 5         # ثوانٍ لكل محاولة — لا نؤخر قنوات الإشعار الأخرى

# خدمات دفع معروفة للمتصفحات الشائعة. يضيف المشغّل خدمات موثوقة أخرى بمتغير البيئة
# VAPID_ALLOWED_HOSTS (قيم مفصولة بفواصل — إضافة صريحة فقط؛ القائمة المدمجة لا تُلغى أبدًا).
# مرجع القائمة: pushpad/known-push-services ووثائق Apple لإشعارات الويب.
DEFAULT_PUSH_HOSTS = (
    "web.push.apple.com",                 # آيفون/آيباد/ماك (Safari)
    ".push.apple.com",
    "fcm.googleapis.com",                 # كروم وأندرويد وسامسونج
    "android.googleapis.com",
    "updates.push.services.mozilla.com",  # فايرفوكس
    ".push.services.mozilla.com",
    ".notify.windows.com",                # إيدج (WNS)
)
_CGNAT = ipaddress.ip_network("100.64.0.0/10")


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _blocked_ip(ip: str) -> bool:
    """أي وجهة غير عامة بالكامل: خاصة، loopback، link-local، محجوزة، multicast، غير محددة، CGNAT."""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return True
    return (not a.is_global) or a in _CGNAT


async def _resolves_public(host: str) -> bool:
    """حلّ DNS حديث: كل العناوين الناتجة يجب أن تكون عامة. يُستدعى قبل كل إرسال فعلي
    فيُكشف أي تغيير لاحق يوجّه المضيف إلى عنوان داخلي."""
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, PUSH_PORT, type=socket.SOCK_STREAM)
    except (OSError, UnicodeError):
        return False
    addrs = {i[4][0] for i in infos}
    return bool(addrs) and not any(_blocked_ip(a) for a in addrs)


class SubscriptionConflict(Exception):
    """نقطة النهاية مسجلة لمستخدم آخر ولم يُثبت الطالب ملكية مفاتيحها."""


class WebPush:
    """قناة إشعارات: ترسل «إيقاظًا» لكل اشتراكات المتصفح المرتبطة بالمستخدم."""

    name = "webpush"

    def __init__(self, app, http: httpx.AsyncClient | None = None):
        self.app = app
        self.s = app.s
        self.http = http or httpx.AsyncClient(timeout=SEND_TIMEOUT, follow_redirects=False)
        self._key = None
        self._checked = None      # نتيجة فحص الإعداد (مخزنة): سبب التعطيل أو ""
        self._jwts: dict[str, tuple[str, float]] = {}   # aud -> (jwt, exp)
        self._last_error = ""
        self._tasks: set[asyncio.Task] = set()          # إرسالات خلفية جارية — تُنتظر عند الإيقاف

    # ——— الإعداد
    def _config_error(self) -> str:
        """سبب تعطيل القناة أو سلسلة فارغة. يتحقق من تطابق المفتاح العام مع الخاص ومن صحة المعرّف."""
        if self._checked is not None:
            return self._checked
        err = ""
        try:
            if not (self.s.vapid_private_key and self.s.vapid_public_key):
                err = "مفاتيح VAPID غير مضبوطة"
            else:
                derived = self._public_from_private()
                if derived != self.s.vapid_public_key:
                    err = "المفتاح العام لا يطابق الخاص"
                elif not self._valid_subject():
                    err = "معرّف VAPID_SUBJECT غير صالح (يلزم mailto: أو رابط https حقيقي)"
        except Exception:  # noqa: BLE001
            err = "مفتاح VAPID الخاص غير صالح"
        self._checked = err
        if err:
            self._last_error = err
        return err

    def _valid_subject(self) -> bool:
        sub = (self.s.vapid_subject or "").strip()
        if sub.startswith("mailto:"):
            return len(sub) > len("mailto:") and "@" in sub
        if sub.startswith("https://"):
            return bool(urlsplit(sub).hostname)
        return False

    def enabled(self) -> bool:
        return not self._config_error()

    def client_config(self) -> dict:
        """المفتاح العام (غير سري بطبيعته) يُسلَّم للمستخدمين الموثّقين فقط."""
        if not self.enabled():
            return {"enabled": False}
        return {"enabled": True, "vapid_public_key": self.s.vapid_public_key}

    def status(self) -> str:
        err = self._config_error()
        if err:
            return "معطّلة: " + err
        return "خطأ: " + self._last_error if self._last_error else "جاهز"

    def _priv_key(self):
        if self._key is None:
            from cryptography.hazmat.primitives.asymmetric import ec
            raw = _b64d(self.s.vapid_private_key)
            if len(raw) != 32:
                raise ValueError("مفتاح VAPID الخاص يجب أن يكون 32 بايت (P-256)")
            self._key = ec.derive_private_key(int.from_bytes(raw, "big"), ec.SECP256R1())
        return self._key

    def _public_from_private(self) -> str:
        from cryptography.hazmat.primitives import serialization
        return _b64(self._priv_key().public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint))

    # ——— توقيع VAPID (ES256 عبر مكتبة cryptography المصانة — لا تشفير مكتوب يدويًا)
    def _jwt(self, aud: str) -> str:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
        now = int(time.time())
        cached = self._jwts.get(aud)
        if cached and now < cached[1] - 300:
            return cached[0]
        head = _b64(json.dumps({"typ": "JWT", "alg": "ES256"}).encode())
        claim = _b64(json.dumps({
            "aud": aud,
            "exp": now + JWT_EXP,
            "sub": (self.s.vapid_subject or "").strip(),
        }).encode())
        der = self._priv_key().sign(f"{head}.{claim}".encode(), ec.ECDSA(hashes.SHA256()))
        r, s = decode_dss_signature(der)
        jwt = f"{head}.{claim}.{_b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"
        self._jwts[aud] = (jwt, now + JWT_EXP)
        return jwt

    # ——— فحص وجهة آمنة (يُستدعى عند التسجيل وقبل كل إرسال فعلي)
    def _push_host_allowed(self, host: str) -> bool:
        # تطابق دقيق للإدخالات بلا نقطة بادئة؛ إدخالات «.example.com» تطابق المضيف نفسه ونطاقاته الفرعية فقط
        host = (host or "").lower()
        allowed = list(DEFAULT_PUSH_HOSTS)
        raw = (getattr(self.s, "vapid_allowed_hosts", "") or "").strip()
        allowed += [h.strip().lower() for h in raw.split(",") if h.strip()]
        for h in allowed:
            if h.startswith("."):
                base = h[1:]
                if host == base or host.endswith("." + base):
                    return True
            elif host == h:
                return True
        return False

    async def check_endpoint(self, endpoint: str) -> str:
        """يعيد سبب الرفض أو سلسلة فارغة: https فقط، منفذ 443 فقط، بلا userinfo ولا IP حرفي
        ولا نقطة ختامية، مضيف ضمن خدمات الدفع المعروفة، وDNS يحلّ لعناوين عامة حصرًا."""
        parts = urlsplit(endpoint or "")
        if parts.scheme != "https" or not parts.hostname:
            return "عنوان الاشتراك يجب أن يكون https"
        if parts.username or parts.password:
            return "عنوان الاشتراك غير صالح"
        host = parts.hostname   # urlsplit يطبّع الحالة الصغيرة — لا التفاف بالتبديل بين الأحرف
        if host.endswith("."):
            return "عنوان الاشتراك غير صالح"
        try:
            ipaddress.ip_address(host)
            return "عنوان الاشتراك غير صالح"     # لا نقبل IP حرفيًا
        except ValueError:
            pass
        try:
            if (parts.port or PUSH_PORT) != PUSH_PORT:
                return "منفذ الاشتراك غير مسموح"
        except ValueError:
            return "منفذ الاشتراك غير مسموح"
        if not self._push_host_allowed(host):
            return "خدمة الدفع غير معروفة"
        if not await _resolves_public(host):
            return "عنوان خدمة الدفع غير صالح"
        return ""

    # ——— الاشتراكات
    async def register(self, uid: int, endpoint: str, p256dh: str, auth: str, label: str = "") -> None:
        why = await self.check_endpoint(endpoint)
        if why:
            raise ValueError(why)
        for k in (p256dh, auth):
            if not k or not (KEY_RE_LEN[0] <= len(k) <= KEY_RE_LEN[1]):
                raise ValueError("مفاتيح الاشتراك غير صالحة")
            try:
                _b64d(k)
            except Exception:  # noqa: BLE001
                raise ValueError("مفاتيح الاشتراك غير صالحة") from None
        from .db import now_iso
        db = self.app.db
        t = now_iso()
        existing = db.one("SELECT user_id, p256dh, auth FROM webpush_subs WHERE endpoint=?", (endpoint,))
        if existing and existing["user_id"] != uid:
            # نقطة النهاية قدرة وصول: لا تنتقل لمستخدم آخر إلا بإثبات ملكية مفاتيحها الحالية
            if p256dh != existing["p256dh"] or auth != existing["auth"]:
                raise SubscriptionConflict("الاشتراك مسجّل لمستخدم آخر")
            db.execute("UPDATE webpush_subs SET user_id=?, label=?, updated_at=?, fail_count=0 WHERE endpoint=?",
                       (uid, label[:60], t, endpoint))
        elif existing:
            db.execute("UPDATE webpush_subs SET p256dh=?, auth=?, label=?, updated_at=?, fail_count=0 "
                       "WHERE endpoint=?", (p256dh, auth, label[:60], t, endpoint))
        else:
            db.insert("INSERT INTO webpush_subs(user_id,endpoint,p256dh,auth,label,created_at,updated_at) "
                      "VALUES(?,?,?,?,?,?,?)", (uid, endpoint, p256dh, auth, label[:60], t, t))
        rows = db.all("SELECT id FROM webpush_subs WHERE user_id=? ORDER BY updated_at DESC", (uid,))
        for r in rows[MAX_SUBS_PER_USER:]:
            db.execute("DELETE FROM webpush_subs WHERE id=?", (r["id"],))

    def unregister(self, uid: int, endpoint: str) -> bool:
        return bool(self.app.db.execute("DELETE FROM webpush_subs WHERE user_id=? AND endpoint=?",
                                        (uid, endpoint)).rowcount)

    # ——— الإرسال (قناة إشعارات): رسالة إيقاظ فارغة؛ المحتوى يُقرأ داخل التطبيق
    async def _send_one(self, endpoint: str) -> str:
        """يعيد: ok | gone | fail. محاولة واحدة، وإعادة واحدة فقط عند 429/5xx — بحد أقصى ~10 ثوانٍ."""
        aud = f"{urlsplit(endpoint).scheme}://{urlsplit(endpoint).netloc}"
        headers = {
            "Authorization": f"vapid t={await asyncio.to_thread(self._jwt, aud)}, k={self.s.vapid_public_key}",
            "TTL": "3600",
            "Urgency": "normal",
        }
        for attempt in range(2):
            try:
                r = await self.http.post(endpoint, content=b"", headers=headers)
            except Exception as e:  # noqa: BLE001
                self._last_error = type(e).__name__       # لا تفاصيل — قد تحمل العنوان
                if attempt == 0:
                    await asyncio.sleep(1)
                continue
            if r.status_code in (200, 201, 202):
                self._last_error = ""
                return "ok"
            if r.status_code in (404, 410):
                return "gone"
            if r.status_code in (401, 403):
                self._last_error = f"رفض المصادقة {r.status_code}"
                return "fail"
            if r.status_code == 429 or r.status_code >= 500:
                if attempt == 0:
                    await asyncio.sleep(1)
                continue
            self._last_error = f"webpush {r.status_code}"
            return "fail"
        return "fail"

    async def _deliver(self, row) -> str:
        # إعادة فحص الوجهة مباشرة قبل الإرسال: يكشف أي تغيير DNS لاحق نحو عنوان داخلي
        if await self.check_endpoint(row["endpoint"]):
            return "fail"
        return await self._send_one(row["endpoint"])

    async def send(self, uid: int, text: str, buttons=None) -> str | None:
        """جدولة الإرسال في مهمة خلفية متعقَّبة والعودة فورًا — notify لا ينتظر الشبكة أبدًا،
        ولا تتأخر قنوات الإشعار الأخرى. تحديثات قاعدة البيانات داخل المهمة.
        يعيد "queued" عند الجدولة (قُبِل للإرسال — لم يُسلَّم بعد)، وNone فقط عندما
        القناة معطّلة أو لا اشتراك للمستخدم (غير مرتبط بهذه القناة)."""
        if not self.enabled():
            return None
        if not self.app.db.one("SELECT 1 FROM webpush_subs WHERE user_id=? LIMIT 1", (uid,)):
            return None
        task = asyncio.create_task(self._deliver_all(uid))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return "queued"

    async def _deliver_all(self, uid: int) -> None:
        from .db import now_iso
        try:
            self.prune()
            rows = self.app.db.all("SELECT id, endpoint FROM webpush_subs WHERE user_id=?", (uid,))
            if not rows:
                return
            # إرسال متزامن لكل اشتراكات المستخدم
            results = await asyncio.gather(*(self._deliver(r) for r in rows))
            any_ok = False
            for r, res in zip(rows, results):
                if res == "ok":
                    any_ok = True
                    self.app.db.execute("UPDATE webpush_subs SET last_ok_at=?, fail_count=0 WHERE id=?",
                                        (now_iso(), r["id"]))
                elif res == "gone":
                    self.app.db.execute("DELETE FROM webpush_subs WHERE id=?", (r["id"],))
                else:
                    self.app.db.execute("UPDATE webpush_subs SET fail_count=fail_count+1 WHERE id=?",
                                        (r["id"],))
            if not any_ok:
                log.warning("webpush: no subscription accepted for user %s (%s)", uid, self._last_error)
        except Exception:  # noqa: BLE001
            log.exception("webpush: background delivery failed for user %s", uid)

    async def pending(self) -> None:
        """انتظار الإرسالات الخلفية الجارية (للاختبارات والإيقاف النظيف)."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    def prune(self) -> int:
        """حذف الاشتراكات الميتة: فشلت مرارًا أو لم تتجدد منذ مدة طويلة."""
        from datetime import timedelta
        from .db import iso, utcnow
        cutoff = iso(utcnow() - timedelta(days=STALE_DAYS))
        n = len(self.app.db.all("SELECT id FROM webpush_subs WHERE fail_count>=? OR updated_at<?",
                                (MAX_FAILS, cutoff)))
        if n:
            self.app.db.execute("DELETE FROM webpush_subs WHERE fail_count>=? OR updated_at<?",
                                (MAX_FAILS, cutoff))
        return n

    async def stop(self) -> None:
        await self.pending()
        await self.http.aclose()
