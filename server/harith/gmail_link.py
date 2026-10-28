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
import re
import secrets
import time
from datetime import timedelta
from typing import TYPE_CHECKING
from urllib.parse import urlencode

import httpx

from .db import now_iso, utcnow
from .db import iso as db_iso
from .security import create_user, new_api_token

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
            # صفحة موافقة قوقل تعرض الصلاحيات كخانات اختيار قابلة للإلغاء: بلا صلاحية البريد
            # يفشل IMAP لاحقًا بخطأ مصادقة غامض — نتحقق هنا ونرشد فورًا
            if "https://mail.google.com/" not in tok.get("scope", "").split():
                return False, ("لم تُمنح صلاحية الوصول إلى البريد. في صفحة موافقة قوقل تظهر الصلاحيات "
                               "كخانات اختيار — أعد المحاولة وتأكد من تفعيل خانة «قراءة بريدك وإدارته وإرساله»")
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
        # سجّل البريد على الحساب (موثّق من قوقل): يفيد «المتابعة بحساب قوقل» في مطابقة الحساب لاحقًا
        self.app.db.execute("UPDATE users SET email=?, email_source='google', email_verified=1"
                            " WHERE id=? AND email=''", (email, uid))
        # إعادة الربط تُعيد تسليح إشعار الانتهاء لدورة الصلاحية القادمة
        self.app.db.execute("DELETE FROM processed_keys WHERE key LIKE ?", (f"gmail:expired:{uid}:%",))
        # أول دورة مراقبة فورًا: البذرة تسجّل الموجود دون اقتراحات ثم تبدأ الاقتراحات بالجديد
        self.app.scheduler.enqueue(uid, "mailwatch", {"uid": uid}, now,
                                   dedupe_key=f"mailwatch:first:{uid}", max_attempts=2)
        return True, email
    # ——— «المتابعة بحساب قوقل»: دخول (أو إنشاء حساب برمز دعوة) + ربط بريد في مسار واحد.
    # جلسة الدخول رمز وصول عادي مستقل تمامًا عن صلاحية رمز البريد (انتهاء بريد قوقل
    # التجريبي بعد ~٧ أيام لا يُخرج المستخدم من التطبيق)، وربط البريد خطوة مدمجة تفشل وحدها بأمان.
    # الدخول بقوقل يطلب المعرفة فقط (openid/email/profile — غير حساسة، لا تحتاج تحقق قوقل):
    # صلاحية البريد الكاملة تُطلب تزايدنيًا فقط عند «ربط البريد» الصريح (start_url)،
    # فينجح دخول كل مشترك بلا حجب وضع الاختبار ولا تحذير «تطبيق غير موثّق».
    LOGIN_SCOPE = "openid email profile"

    def make_login_state(self, invite: str) -> str:
        invite = re.sub(r"[^A-Za-z0-9-]", "", invite or "")[:64]
        exp = int((utcnow() + timedelta(minutes=STATE_TTL_MIN)).timestamp())
        body = f"L|{invite}|{exp}|{secrets.token_urlsafe(8)}"
        sig = hmac.new(self._state_key(), body.encode(), hashlib.sha256).hexdigest()[:24]
        return base64.urlsafe_b64encode(f"{body}|{sig}".encode()).decode()

    def is_login_state(self, state: str) -> bool:
        try:
            return base64.urlsafe_b64decode((state or "").encode()).decode().startswith("L|")
        except Exception:
            return False

    def check_login_state(self, state: str) -> str | None:
        """يرجع رمز الدعوة المحمول في الحالة ('' = بلا دعوة)، أو None إن كانت فاسدة/منتهية."""
        try:
            raw = base64.urlsafe_b64decode(state.encode()).decode()
            if not raw.startswith("L|"):
                return None
            _l, invite, exp_s, _rnd, sig = raw.rsplit("|", 4)
            body = f"L|{invite}|{exp_s}|{_rnd}"
            good = hmac.new(self._state_key(), body.encode(), hashlib.sha256).hexdigest()[:24]
            if not hmac.compare_digest(sig, good) or int(exp_s) < time.time():
                return None
            return invite
        except Exception:
            return None

    def start_login_url(self, invite: str, redirect: str) -> str:
        q = urlencode({
            "client_id": self.app.s.google_client_id,
            "redirect_uri": redirect,
            "response_type": "code",
            "scope": self.LOGIN_SCOPE,
            "access_type": "offline",
            "prompt": "consent",
            "include_granted_scopes": "false",
            "state": self.make_login_state(invite),
        })
        return f"{AUTH_URL}?{q}"

    def _user_by_email(self, email: str) -> dict | None:
        db = self.app.db
        u = db.one("SELECT * FROM users WHERE lower(email)=?", (email,))
        if u:
            return u
        row = db.one("SELECT user_id FROM gmail_links WHERE email=?", (email,))
        if row:
            return db.one("SELECT * FROM users WHERE id=?", (row["user_id"],))
        return db.one("SELECT * FROM users WHERE lower(username)=?", (email,))

    def _create_google_user(self, email: str, name: str, invite: str) -> tuple[bool, str]:
        """ينشئ حسابًا جديدًا لبريد موثّق — برمز دعوة صالح فقط (مشروع أو تسجيل عام)."""
        from . import team
        db = self.app.db
        inv = team.peek_invite(db, invite) if invite else None
        signup = None if inv else (team.peek_signup(db, invite) if invite else None)
        if not inv and not signup:
            return False, ("هذا البريد لا يملك حسابًا بعد — تحتاج رابط دعوة صالحًا: "
                           "الصقه في التطبيق ثم أعد المحاولة")
        base = re.sub(r"[^a-z0-9_.-]", "", email.split("@")[0].lower())[:24] or "user"
        if len(base) < 3:
            base = base + "user"
        username = base
        n = 1
        while db.one("SELECT id FROM users WHERE username=?", (username,)):
            n += 1
            username = f"{base}{n}"[:32]
        uid = create_user(db, username, secrets.token_urlsafe(18), role="user",
                          display_name=(name or base)[:60], tz=self.app.s.timezone)
        db.execute("UPDATE users SET email=?, email_source='google', email_verified=1 WHERE id=?",
                   (email, uid))
        if inv:
            team.use_invite(db, inv["code"])
            if team.add_member(db, inv["project_id"], uid, inv["created_by"]):
                team.log(db, inv["project_id"], uid, name or username, "انضم إلى الفريق بحساب قوقل")
                team.notify_later(self.app, inv["created_by"],
                                  f"👥 انضم {name or username} إلى فريق مشروع «{inv['project']}» بحساب قوقل.")
        else:
            team.use_signup(db, signup["code"])
            team.notify_later(self.app, signup["created_by"],
                              f"🎉 مجرّب جديد في رفيق بحساب قوقل: {name or username}")
        db.log_event("info", "signup", f"إنشاء حساب {username} عبر دخول قوقل")
        return True, ""

    async def finish_login(self, code: str, state: str, redirect: str) -> tuple[bool, dict | str]:
        """يُكمل دخول قوقل: يجد/ينشئ المستخدم بالبريد الموثّق، يربط بريده إن مُنحت صلاحيته،
        ويخزّن رمز استبدال لمرة واحدة يستبدله التطبيق برمز وصول عادي مستقل عن صلاحية البريد."""
        invite = self.check_login_state(state)
        if invite is None:
            return False, "انتهت صلاحية محاولة الدخول — أعد المحاولة من التطبيق"
        async with httpx.AsyncClient(timeout=20) as c:
            t = await c.post(TOKEN_URL, data={"client_id": self.app.s.google_client_id,
                                              "client_secret": self.app.s.google_client_secret,
                                              "code": code, "grant_type": "authorization_code",
                                              "redirect_uri": redirect})
            if t.status_code != 200:
                return False, "رفضت قوقل إكمال الدخول — أعد المحاولة من التطبيق"
            tok = t.json()
            if not tok.get("access_token"):
                return False, "رفضت قوقل إكمال الدخول — أعد المحاولة من التطبيق"
            u = await c.get(USERINFO_URL, headers={"Authorization": f"Bearer {tok['access_token']}"})
            if u.status_code != 200:
                return False, "تعذّر معرفة بريدك من قوقل — أعد المحاولة"
            info = u.json()
        email = str(info.get("email") or "").strip().lower()
        if not email or info.get("verified_email") is False:
            return False, "لم يصلني بريد موثّق من قوقل — أعد المحاولة بحساب موثّق"
        db = self.app.db
        usr = self._user_by_email(email)
        created = False
        if not usr:
            ok, msg = self._create_google_user(email, str(info.get("name") or ""), invite)
            if not ok:
                return False, msg
            usr = self._user_by_email(email)
            created = True
        if not usr or usr["disabled"]:
            return False, "هذا الحساب موقوف — راسل صاحب الخادم"
        if not usr.get("email"):
            db.execute("UPDATE users SET email=?, email_source='google', email_verified=1"
                       " WHERE id=? AND email=''", (email, usr["id"]))
        # ربط البريد خطوة مدمجة لكنها اختيارية: بلا صلاحية البريد ينجح الدخول وحده
        mail_linked = False
        if "https://mail.google.com/" in str(tok.get("scope", "")).split() and tok.get("refresh_token"):
            now = now_iso()
            db.execute(
                "INSERT INTO gmail_links(user_id,email,refresh_token_enc,access_token_enc,access_expires_at,scope,"
                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) "
                "ON CONFLICT(user_id) DO UPDATE SET email=excluded.email, refresh_token_enc=excluded.refresh_token_enc,"
                " access_token_enc=excluded.access_token_enc, access_expires_at=excluded.access_expires_at,"
                " scope=excluded.scope, updated_at=excluded.updated_at, status='ok'",
                (usr["id"], email, self._enc(tok["refresh_token"]), self._enc(tok.get("access_token", "")),
                 db_iso(utcnow() + timedelta(seconds=int(tok.get("expires_in", 3600)))),
                 tok.get("scope", ""), now, now))
            db.execute("DELETE FROM processed_keys WHERE key LIKE ?", (f"gmail:expired:{usr['id']}:%",))
            self.app.scheduler.enqueue(usr["id"], "mailwatch", {"uid": usr["id"]}, now,
                                       dedupe_key=f"mailwatch:first:{usr['id']}", max_attempts=2)
            mail_linked = True
        api_token = new_api_token(db, usr["id"], "دخول قوقل")
        redeem = secrets.token_urlsafe(16)
        db.set_setting(0, f"glogin:{redeem}", {
            "token": api_token, "name": usr.get("display_name") or usr["username"],
            "username": usr["username"],
            "email": email, "mail_linked": mail_linked, "created": created, "ts": time.time(),
        })
        db.log_event("info", "google_login",
                     f"{'إنشاء حساب' if created else 'دخول'} عبر قوقل: {email} (بريد مربوط: {mail_linked})")
        return True, {"code": redeem}


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
