"""إشعارات فورية عبر Firebase Cloud Messaging (HTTP v1).

التصميم:
- الرسالة «إيقاظ» فقط (data: t=sync) بلا أي نص من المستخدم؛ التطبيق يجلب المحتوى من الخادم بمفتاحه.
  فلو وصل الرمز لجهاز خاطئ فلا يتسرب شيء.
- الرمز مربوط بالمستخدم الذي سجّله بجلسة موثّقة (push_tokens.user_id).
- فشل الإرسال لا يغيّر سلوك الإشعارات: يبقى الجلب الدوري في التطبيق (WorkManager) احتياطيًا.
- المفتاح (حساب الخدمة) ملف على الخادم فقط، لا يصل لأي تطبيق.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
import time
from pathlib import Path
from typing import TYPE_CHECKING

import httpx

from .security import redact

if TYPE_CHECKING:
    from .core import Harith

log = logging.getLogger("rafiq.fcm")
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
TOKEN_RE = re.compile(r"^[A-Za-z0-9:_\-.]{20,4096}$")
MAX_TOKENS_PER_USER = 10
GONE = ("UNREGISTERED", "INVALID_ARGUMENT", "NOT_FOUND")


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


# ——— توقيع RS256 بلا مكتبات خارجية (يعمل على Termux بلا مترجم). مفتاح حساب الخدمة بصيغة PKCS#8 PEM.
def _der(buf: bytes, pos: int = 0) -> tuple[int, bytes, int]:
    tag = buf[pos]
    ln = buf[pos + 1]
    pos += 2
    if ln & 0x80:
        k = ln & 0x7F
        ln = int.from_bytes(buf[pos:pos + k], "big")
        pos += k
    return tag, buf[pos:pos + ln], pos + ln


def _rsa_private(pem: str) -> tuple[int, int]:
    body = "".join(l for l in pem.strip().splitlines() if not l.startswith("-----"))
    der = base64.b64decode(body)
    _, seq, _ = _der(der)                 # PrivateKeyInfo
    pos = 0
    items = []
    while pos < len(seq):
        t, v, pos = _der(seq, pos)
        items.append((t, v))
    if items[2][0] == 0x04:               # PKCS#8: privateKey OCTET STRING يحوي RSAPrivateKey
        _, rsa, _ = _der(items[2][1])
    else:                                 # PKCS#1 مباشرة
        rsa = seq
    pos = 0
    ints = []
    while pos < len(rsa) and len(ints) < 4:
        t, v, pos = _der(rsa, pos)
        ints.append(int.from_bytes(v, "big"))
    return ints[1], ints[3]               # n, d (الترتيب: version, n, e, d)


_SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def rs256_sign(data: bytes, pem: str) -> bytes:
    import hashlib
    n, d = _rsa_private(pem)
    k = (n.bit_length() + 7) // 8
    t = _SHA256_PREFIX + hashlib.sha256(data).digest()
    em = b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t
    return pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")


class FcmPush:
    name = "push"

    def __init__(self, app: "Harith", http: httpx.AsyncClient | None = None):
        self.app = app
        self.s = app.s
        self.http = http or httpx.AsyncClient(timeout=15)
        self._creds: dict | None = None
        self._tok = ""
        self._tok_exp = 0.0
        self._lock = asyncio.Lock()
        self._last_error = ""

    # ——— الإعداد
    def load_creds(self) -> dict | None:
        if self._creds is None:
            try:
                d = json.loads(Path(self.s.fcm_credentials_file).read_text("utf-8"))
                if d.get("type") != "service_account" or not d.get("private_key") or not d.get("client_email"):
                    raise ValueError("ملف حساب الخدمة غير صالح")
                self._creds = d
            except Exception as e:  # noqa: BLE001
                self._last_error = f"تعذر قراءة حساب الخدمة: {type(e).__name__}"
                return None
        return self._creds

    @property
    def project_id(self) -> str:
        c = self.load_creds()
        return (c or {}).get("project_id", "")

    def client_config(self) -> dict:
        """إعداد التطبيق (غير سري بطبيعته) يُسلَّم للمستخدمين الموثّقين فقط."""
        s = self.s
        ok = bool(self.project_id and s.fcm_app_id and s.fcm_api_key and s.fcm_sender_id)
        if not ok:
            return {"enabled": False}
        return {"enabled": True, "project_id": self.project_id, "app_id": s.fcm_app_id,
                "api_key": s.fcm_api_key, "sender_id": s.fcm_sender_id}

    def status(self) -> str:
        if not self.client_config().get("enabled"):
            return self._last_error or "غير مُعد"
        return "خطأ: " + self._last_error if self._last_error else "جاهز"

    # ——— OAuth بحساب الخدمة (RS256)
    def _jwt(self, creds: dict) -> str:
        now = int(time.time())
        head = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claim = _b64(json.dumps({"iss": creds["client_email"], "scope": SCOPE, "aud": self.s.fcm_token_uri,
                                 "iat": now, "exp": now + 3600}).encode())
        sig = rs256_sign(f"{head}.{claim}".encode(), creds["private_key"])
        return f"{head}.{claim}.{_b64(sig)}"

    async def access_token(self, force: bool = False) -> str:
        async with self._lock:
            if self._tok and not force and time.time() < self._tok_exp - 120:
                return self._tok
            creds = self.load_creds()
            if not creds:
                raise RuntimeError(self._last_error or "no credentials")
            assertion = await asyncio.to_thread(self._jwt, creds)
            r = await self.http.post(self.s.fcm_token_uri, data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion})
            if r.status_code != 200:
                raise RuntimeError(f"oauth {r.status_code}")
            j = r.json()
            self._tok = j["access_token"]
            self._tok_exp = time.time() + int(j.get("expires_in", 3600))
            return self._tok

    # ——— الرموز
    def register(self, uid: int, token: str, label: str = "") -> None:
        if not TOKEN_RE.match(token or ""):
            raise ValueError("رمز غير صالح")
        from .db import now_iso
        db = self.app.db
        t = now_iso()
        # الرمز يخص جهازًا واحدًا: إن سجّله مستخدم آخر من قبل يُنقل لمن سجّل الآن بجلسة موثّقة
        n = db.execute("UPDATE push_tokens SET user_id=?, label=?, updated_at=?, fail_count=0 WHERE token=?",
                       (uid, label[:60], t, token)).rowcount
        if not n:
            db.insert("INSERT INTO push_tokens(user_id,token,label,created_at,updated_at) VALUES(?,?,?,?,?)",
                      (uid, token, label[:60], t, t))
        rows = db.all("SELECT id FROM push_tokens WHERE user_id=? ORDER BY updated_at DESC", (uid,))
        for r in rows[MAX_TOKENS_PER_USER:]:
            db.execute("DELETE FROM push_tokens WHERE id=?", (r["id"],))

    def unregister(self, uid: int, token: str) -> bool:
        return bool(self.app.db.execute("DELETE FROM push_tokens WHERE user_id=? AND token=?", (uid, token)).rowcount)

    # ——— الإرسال (قناة إشعارات)
    async def _send_one(self, token: str) -> str:
        """يعيد: ok | gone | retry | fail"""
        url = f"{self.s.fcm_api_base}/v1/projects/{self.project_id}/messages:send"
        body = {"message": {"token": token, "data": {"t": "sync"},
                            "android": {"priority": "HIGH", "collapse_key": "rafiq-sync", "ttl": "3600s"}}}
        for attempt in range(3):
            try:
                at = await self.access_token(force=attempt == 1)
                r = await self.http.post(url, json=body, headers={"Authorization": f"Bearer {at}"})
            except Exception as e:  # noqa: BLE001
                self._last_error = redact(f"{type(e).__name__}: {e}")[:120]
                await asyncio.sleep(1 + attempt)
                continue
            if r.status_code == 200:
                self._last_error = ""
                return "ok"
            code = ""
            try:
                code = (r.json().get("error") or {}).get("status", "")
                for d in (r.json().get("error") or {}).get("details", []) or []:
                    code = d.get("errorCode", code) or code
            except Exception:  # noqa: BLE001
                pass
            if r.status_code in (404, 400) and code in GONE:
                return "gone"
            if r.status_code in (401, 403):
                self._last_error = f"رفض المصادقة {r.status_code}"
                continue
            if r.status_code == 429 or r.status_code >= 500:
                await asyncio.sleep(1 + attempt)
                continue
            self._last_error = f"FCM {r.status_code} {code}"[:120]
            return "fail"
        return "retry"

    async def send(self, uid: int, text: str, buttons=None) -> bool | None:
        if not self.client_config().get("enabled"):
            return None
        rows = self.app.db.all("SELECT id, token FROM push_tokens WHERE user_id=?", (uid,))
        if not rows:
            return None
        from .db import now_iso
        any_ok = False
        for r in rows:
            res = await self._send_one(r["token"])
            if res == "ok":
                any_ok = True
                self.app.db.execute("UPDATE push_tokens SET last_ok_at=?, fail_count=0 WHERE id=?", (now_iso(), r["id"]))
            elif res == "gone":
                self.app.db.execute("DELETE FROM push_tokens WHERE id=?", (r["id"],))
            else:
                self.app.db.execute("UPDATE push_tokens SET fail_count=fail_count+1 WHERE id=?", (r["id"],))
        if not any_ok:
            log.warning("fcm: no token accepted for user %s (%s)", uid, self._last_error)
        # أي نتيجة غير النجاح لا تُعامل كفشل إشعار: الجلب الدوري في التطبيق هو الاحتياطي
        return True if any_ok else None

    async def stop(self) -> None:
        await self.http.aclose()
