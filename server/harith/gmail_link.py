"""ربط بريد قوقل لكل مستخدم عبر OAuth — طبقة إضافية بالكامل.

يتيح لأي مستخدم ربط بريده من شاشة الإعدادات في التطبيق (موافقة قوقل في المتصفح)،
فتقرأ مراقبة البريد رسائله من حسابه هو وتُرسل الردود من بريده هو بعد موافقته.
رمز التحديث يُخزَّن مشفرًا (Fernet بمفتاح TOKEN_ENC_KEY) ولا يظهر في السجلات ولا في أي رد.
بلا إعداد GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET تبقى الميزة معطلة بأمان ولا يتغير أي سلوك قائم.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from datetime import timedelta
from typing import TYPE_CHECKING
from urllib.parse import urlencode

import httpx

from .db import now_iso, utcnow
from .db import iso as db_iso

if TYPE_CHECKING:
    from .core import Harith

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
SCOPE = "https://mail.google.com/ email"  # بريد كامل عبر IMAP/SMTP + معرفة العنوان فقط
STATE_TTL_MIN = 15


def xoauth2_string(email: str, token: str) -> bytes:
    """سلسلة مصادقة XOAUTH2 المشتركة بين IMAP وSMTP."""
    return f"user={email}\x01auth=Bearer {token}\x01\x01".encode()


class GmailLink:
    def __init__(self, app: "Harith"):
        self.app = app

    def configured(self) -> bool:
        return bool(self.app.s.google_client_id and self.app.s.google_client_secret
                    and self.app.s.token_enc_key)

    # ——— التشفير
    def _fernet(self):
        from cryptography.fernet import Fernet
        key = self.app.s.token_enc_key
        if not key:
            raise RuntimeError("TOKEN_ENC_KEY غير مضبوط في .env")
        return Fernet(key.encode())

    def _enc(self, v: str) -> str:
        return self._fernet().encrypt(v.encode()).decode()

    def _dec(self, v: str) -> str:
        return self._fernet().decrypt(v.encode()).decode()

    # ——— حالة OAuth الموقعة (لا تخزن شيئًا — HMAC بسرّي العميل والتشفير)
    def _state_key(self) -> bytes:
        return (self.app.s.google_client_secret + "|" + self.app.s.token_enc_key).encode()

    def make_state(self, uid: int) -> str:
        exp = int((utcnow() + timedelta(minutes=STATE_TTL_MIN)).timestamp())
        body = f"{uid}:{exp}:{secrets.token_urlsafe(8)}"
        sig = hmac.new(self._state_key(), body.encode(), hashlib.sha256).hexdigest()[:24]
        return base64.urlsafe_b64encode(f"{body}:{sig}".encode()).decode()

    def check_state(self, state: str) -> int | None:
        try:
            raw = base64.urlsafe_b64decode(state.encode()).decode()
            uid_s, exp_s, rnd, sig = raw.rsplit(":", 3)
            body = f"{uid_s}:{exp_s}:{rnd}"
            good = hmac.new(self._state_key(), body.encode(), hashlib.sha256).hexdigest()[:24]
            if not hmac.compare_digest(good, sig) or int(exp_s) < int(utcnow().timestamp()):
                return None
            return int(uid_s)
        except Exception:  # noqa: BLE001 — أي تشويه = رفض
            return None

    def start_url(self, uid: int, redirect: str) -> str:
        q = urlencode({"client_id": self.app.s.google_client_id, "redirect_uri": redirect,
                       "response_type": "code", "scope": SCOPE, "access_type": "offline",
                       "prompt": "consent", "state": self.make_state(uid)})
        return f"{AUTH_URL}?{q}"

    # ——— إتمام الربط
    async def finish(self, code: str, state: str, redirect: str) -> tuple[bool, str]:
        """يبدل الرمز برموز الوصول ويخزنها مشفرة. يرجع (نجح؟, البريد أو سبب الفشل)."""
        uid = self.check_state(state)
        if not uid:
            return False, "رابط الربط منتهي أو غير صالح — أعد المحاولة من التطبيق"
        if not self.app.db.one("SELECT 1 x FROM users WHERE id=? AND disabled=0", (uid,)):
            return False, "الحساب غير موجود"
        async with httpx.AsyncClient(timeout=20) as c:
            t = await c.post(TOKEN_URL, data={"client_id": self.app.s.google_client_id,
                                              "client_secret": self.app.s.google_client_secret,
                                              "code": code, "grant_type": "authorization_code",
                                              "redirect_uri": redirect})
            if t.status_code != 200:
                return False, "رفضت قوقل رمز الربط — أعد المحاولة من التطبيق"
            tok = t.json()
            if not tok.get("refresh_token"):
                return False, "لم تمنح قوقل رمزًا دائمًا — أعد المحاولة ووافق على صلاحية البريد"
            u = await c.get(USERINFO_URL, headers={"Authorization": f"Bearer {tok['access_token']}"})
            email = (u.json().get("email") or "").strip().lower() if u.status_code == 200 else ""
            if not email:
                return False, "تعذّر معرفة عنوان بريدك من قوقل"
        now = now_iso()
        self.app.db.execute(
            "INSERT INTO gmail_links(user_id,email,refresh_token_enc,access_token_enc,access_expires_at,scope,"
            "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) "
            "ON CONFLICT(user_id) DO UPDATE SET email=excluded.email, refresh_token_enc=excluded.refresh_token_enc,"
            " access_token_enc=excluded.access_token_enc, access_expires_at=excluded.access_expires_at,"
            " scope=excluded.scope, updated_at=excluded.updated_at, status='ok'",
            (uid, email, self._enc(tok["refresh_token"]), self._enc(tok.get("access_token", "")),
             db_iso(utcnow() + timedelta(seconds=int(tok.get("expires_in", 3600)))),
             tok.get("scope", ""), now, now))
        # إعادة الربط تُعيد تسليح إشعار الانتهاء لدورة الصلاحية القادمة
        self.app.db.execute("DELETE FROM processed_keys WHERE key LIKE ?", (f"gmail:expired:{uid}:%",))
        # أول دورة مراقبة فورًا: البذرة تسجّل الموجود دون اقتراحات ثم تبدأ الاقتراحات بالجديد
        self.app.scheduler.enqueue(uid, "mailwatch", {"uid": uid}, now,
                                   dedupe_key=f"mailwatch:first:{uid}", max_attempts=2)
        return True, email

    def linked(self, uid: int) -> dict | None:
        return self.app.db.one("SELECT user_id,email,status,created_at FROM gmail_links WHERE user_id=?", (uid,))

    def unlink(self, uid: int) -> None:
        self.app.db.execute("DELETE FROM gmail_links WHERE user_id=?", (uid,))

    async def access_token(self, uid: int) -> tuple[str, str] | None:
        """(البريد, رمز وصول صالح) للمستخدم المربوط، مع تجديد تلقائي عند الانتهاء."""
        row = self.app.db.one("SELECT * FROM gmail_links WHERE user_id=?", (uid,))
        if not row:
            return None
        if row["access_token_enc"] and row["access_expires_at"] \
                and row["access_expires_at"] > db_iso(utcnow() + timedelta(minutes=2)):
            return row["email"], self._dec(row["access_token_enc"])
        async with httpx.AsyncClient(timeout=20) as c:
            t = await c.post(TOKEN_URL, data={"client_id": self.app.s.google_client_id,
                                              "client_secret": self.app.s.google_client_secret,
                                              "refresh_token": self._dec(row["refresh_token_enc"]),
                                              "grant_type": "refresh_token"})
            if t.status_code != 200 or not t.json().get("access_token"):
                # invalid_grant = انتهاء صلاحية الوضع التجريبي (~7 أيام) أو سحب المستخدم للصلاحية:
                # نعلّم الربط منتهيًا ونُشعره مرة واحدة. الأعطال العابرة (5xx) لا تغيّر الحالة.
                err = ""
                try:
                    err = t.json().get("error", "")
                except Exception:  # noqa: BLE001
                    pass
                if err == "invalid_grant" and row["status"] != "expired":
                    self.app.db.execute("UPDATE gmail_links SET status='expired', updated_at=? WHERE user_id=?",
                                        (now_iso(), uid))
                    await self.app.notify(
                        uid,
                        "⚠️ انتهت صلاحية ربط بريد قوقل (الوضع التجريبي يجدده قوقل كل 7 أيام تقريبًا).\n"
                        "أعد الربط من الإعدادات ← بريد قوقل لتستمر اقتراحات الردود.",
                        dedupe=f"gmail:expired:{uid}")
                return None
            tok = t.json()
        self.app.db.execute(
            "UPDATE gmail_links SET access_token_enc=?, access_expires_at=?, updated_at=? WHERE user_id=?",
            (self._enc(tok["access_token"]),
             db_iso(utcnow() + timedelta(seconds=int(tok.get("expires_in", 3600)))), now_iso(), uid))
        return row["email"], tok["access_token"]
