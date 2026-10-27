"""«المتابعة بحساب قوقل»: حالة دخول موقعة، مطابقة الحساب بالبريد، إنشاء حساب برمز دعوة فقط،
ربط البريد خطوة مدمجة اختيارية، ورمز وصول للجلسة مستقل عن صلاحية رمز البريد."""
import asyncio

import httpx

from harith import team
from test_gmail_link import _configure


class _FakeLoginHTTP:
    """تبادل رمز قوقل + معلومات المستخدم — scope قابل للضبط لكل اختبار."""
    scope = "openid email profile https://mail.google.com/"
    email = "User@Example.com"
    name = "مستخدم تجريبي"

    def __init__(self, *a, **k):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def post(self, url, data=None, headers=None):
        return httpx.Response(200, json={"access_token": "at1", "refresh_token": "rt1",
                                         "expires_in": 3600, "scope": type(self).scope})

    async def get(self, url, headers=None):
        return httpx.Response(200, json={"email": type(self).email, "verified_email": True,
                                         "name": type(self).name})


def _patch(monkeypatch, scope="openid email profile https://mail.google.com/", email="User@Example.com"):
    _FakeLoginHTTP.scope = scope
    _FakeLoginHTTP.email = email
    monkeypatch.setattr("harith.gmail_link.httpx.AsyncClient", _FakeLoginHTTP)


def test_login_state_roundtrip_and_tamper(app):
    _configure(app)
    st = app.gmail.make_login_state("INV-123")
    assert app.gmail.is_login_state(st)
    assert app.gmail.check_login_state(st) == "INV-123"
    assert app.gmail.check_login_state(st[:-2] + "xx") is None
    assert app.gmail.check_login_state("garbage") is None
    # حالة ربط البريد العادية ليست حالة دخول ولا تختلط بها
    link_st = app.gmail.make_state(1)
    assert not app.gmail.is_login_state(link_st)
    # بلا دعوة: تُحفظ سلسلة فارغة
    assert app.gmail.check_login_state(app.gmail.make_login_state("")) == ""
    url = app.gmail.start_login_url("INV-123", "https://srv.example/oauth/gmail/callback")
    assert "openid" in url and "access_type=offline" in url and "cid" in url


def test_login_existing_user_by_gmail_link(app, user, monkeypatch):
    """بريده مربوط سابقًا: يدخل بلا دعوة، يتجدد رمز بريده، ويصدر رمز وصول مستقل."""
    _configure(app)
    _patch(monkeypatch, email="User@Example.com")
    # اربط بريده أولًا بالمسار العادي
    asyncio.run(app.gmail.finish("c0", app.gmail.make_state(user["id"]), "https://srv.example/cb"))
    st = app.gmail.make_login_state("")
    ok, res = asyncio.run(app.gmail.finish_login("c1", st, "https://srv.example/cb"))
    assert ok and res.get("code"), res
    rec = app.db.get_setting(0, f"glogin:{res['code']}")
    assert rec and rec["email"] == "user@example.com"
    assert rec["mail_linked"] and not rec["created"] and rec["token"].startswith("hrt_")
    # رمز الوصول يعمل ويخص المستخدم نفسه
    from harith.security import user_for_api_token
    assert user_for_api_token(app.db, rec["token"])["id"] == user["id"]
    # بريده سُجّل على حسابه (موثّق من قوقل)
    u = app.db.one("SELECT * FROM users WHERE id=?", (user["id"],))
    assert u["email"] == "user@example.com" and u["email_verified"] == 1


def test_login_new_user_requires_invite(app, monkeypatch):
    _configure(app)
    _patch(monkeypatch, email="newbie@example.com")
    st = app.gmail.make_login_state("")
    ok, msg = asyncio.run(app.gmail.finish_login("c1", st, "https://srv.example/cb"))
    assert not ok and "دعوة" in msg
    assert not app.db.one("SELECT 1 x FROM users WHERE email='newbie@example.com'")
    assert not app.db.one("SELECT 1 x FROM api_tokens")


def test_login_new_user_with_signup_invite(app, user, monkeypatch):
    _configure(app)
    _patch(monkeypatch, email="newbie@example.com")
    inv = team.new_signup_invite(app.db, user["id"])
    st = app.gmail.make_login_state(inv["code"])
    ok, res = asyncio.run(app.gmail.finish_login("c1", st, "https://srv.example/cb"))
    assert ok, res
    rec = app.db.get_setting(0, f"glogin:{res['code']}")
    assert rec["created"] and rec["mail_linked"]
    u = app.db.one("SELECT * FROM users WHERE email='newbie@example.com'")
    assert u and u["email_verified"] == 1 and u["email_source"] == "google"
    # الدعوة استُهلكت ومراقبة بريده جُدولت
    assert app.db.one("SELECT uses FROM signup_invites WHERE code=?", (inv["code"],))["uses"] == 1
    assert app.db.one("SELECT 1 x FROM gmail_links WHERE user_id=?", (u["id"],))
    assert app.db.one("SELECT 1 x FROM jobs WHERE kind='mailwatch' AND user_id=? AND status='pending'", (u["id"],))
    # رمز البريد مشفر كالعادة
    row = app.db.one("SELECT refresh_token_enc FROM gmail_links WHERE user_id=?", (u["id"],))
    assert "rt1" not in row["refresh_token_enc"]


def test_login_succeeds_without_mail_scope(app, user, monkeypatch):
    """ألغى خانة صلاحية البريد في صفحة قوقل: الدخول ينجح وحده ولا يُربط بريده (تدهور رشيق)."""
    _configure(app)
    _patch(monkeypatch, scope="openid email profile", email="nomail@example.com")
    inv = team.new_signup_invite(app.db, user["id"])
    st = app.gmail.make_login_state(inv["code"])
    ok, res = asyncio.run(app.gmail.finish_login("c1", st, "https://srv.example/cb"))
    assert ok, res
    rec = app.db.get_setting(0, f"glogin:{res['code']}")
    assert rec["created"] and not rec["mail_linked"]
    u = app.db.one("SELECT * FROM users WHERE email='nomail@example.com'")
    assert u and not app.db.one("SELECT 1 x FROM gmail_links WHERE user_id=?", (u["id"],))


def test_login_rejects_unverified_email(app, user, monkeypatch):
    _configure(app)
    _patch(monkeypatch, email="sus@example.com")
    _FakeLoginHTTP.verified = False
    orig_get = _FakeLoginHTTP.get
    async def get_unverified(self, url, headers=None):
        return httpx.Response(200, json={"email": "sus@example.com", "verified_email": False})
    _FakeLoginHTTP.get = get_unverified
    try:
        st = app.gmail.make_login_state("")
        ok, msg = asyncio.run(app.gmail.finish_login("c1", st, "https://srv.example/cb"))
        assert not ok and "موثّق" in msg
    finally:
        _FakeLoginHTTP.get = orig_get


def test_login_redeem_record_single_use_shape(app, user, monkeypatch):
    """سجل الاستبدال يحمل طابعًا زمنيًا (صلاحية ١٠ دقائق يفرضها المسار العام) ويُحذف عند القراءة من الواجهة."""
    _configure(app)
    _patch(monkeypatch, email="User@Example.com")
    asyncio.run(app.gmail.finish("c0", app.gmail.make_state(user["id"]), "https://srv.example/cb"))
    ok, res = asyncio.run(app.gmail.finish_login("c1", app.gmail.make_login_state(""), "https://srv.example/cb"))
    assert ok
    rec = app.db.get_setting(0, f"glogin:{res['code']}")
    assert rec["ts"] > 0
