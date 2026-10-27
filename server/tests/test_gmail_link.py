"""ربط بريد قوقل لكل مستخدم: حالة موقعة، تبادل رموز وهمي، تخزين مشفر، تجديد تلقائي،
مراقبة وإرسال من بريد المستخدم نفسه — ولا تغيير على سلوك البيانات العامة."""
import asyncio
import json

import httpx
import pytest
from cryptography.fernet import Fernet

from harith.gmail_link import xoauth2_string
from test_mailwatch import _FakeIMAP, _setup, _raw


def _configure(app):
    app.s.google_client_id = "cid"
    app.s.google_client_secret = "csecret"
    app.s.token_enc_key = Fernet.generate_key().decode()


class _FakeHTTP:
    """يبدل httpx.AsyncClient داخل gmail_link: تبادل الرمز ومعلومات المستخدم وتحديث الرمز."""
    def __init__(self, *a, **k):
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def post(self, url, data=None, headers=None):
        self.calls.append(("post", url, dict(data)))
        if data.get("grant_type") == "authorization_code":
            return httpx.Response(200, json={"access_token": "at1", "refresh_token": "rt1",
                                             "expires_in": 3600, "scope": "mail email"})
        return httpx.Response(200, json={"access_token": "at2", "expires_in": 3600})

    async def get(self, url, headers=None):
        return httpx.Response(200, json={"email": "User@Example.com"})


def test_state_signed_and_tamper_rejected(app, user):
    _configure(app)
    st = app.gmail.make_state(user["id"])
    assert app.gmail.check_state(st) == user["id"]
    assert app.gmail.check_state(st[:-2] + "xx") is None
    assert app.gmail.check_state("garbage") is None
    url = app.gmail.start_url(user["id"], "https://srv.example/oauth/gmail/callback")
    assert "access_type=offline" in url and "prompt=consent" in url and "cid" in url


def test_finish_stores_encrypted_and_enqueues_mailwatch(app, user, monkeypatch):
    _configure(app)
    monkeypatch.setattr("harith.gmail_link.httpx.AsyncClient", _FakeHTTP)
    st = app.gmail.make_state(user["id"])
    ok, msg = asyncio.run(app.gmail.finish("code-1", st, "https://srv.example/cb"))
    assert ok and msg == "user@example.com", (ok, msg)
    row = app.db.one("SELECT * FROM gmail_links WHERE user_id=?", (user["id"],))
    assert row and "rt1" not in row["refresh_token_enc"] and "at1" not in row["access_token_enc"]  # مشفرة
    assert app.gmail.linked(user["id"])["email"] == "user@example.com"
    assert app.db.one("SELECT 1 x FROM jobs WHERE kind='mailwatch' AND user_id=? AND status='pending'",
                      (user["id"],))  # أول دورة مراقبة تُجدول فورًا
    # رمز الوصول الصالح يُفك ويُعاد دون تجديد
    email, tok = asyncio.run(app.gmail.access_token(user["id"]))
    assert (email, tok) == ("user@example.com", "at1")


def test_access_token_refreshes_when_expired(app, user, monkeypatch):
    _configure(app)
    monkeypatch.setattr("harith.gmail_link.httpx.AsyncClient", _FakeHTTP)
    st = app.gmail.make_state(user["id"])
    asyncio.run(app.gmail.finish("code-1", st, "https://srv.example/cb"))
    app.db.execute("UPDATE gmail_links SET access_expires_at='2020-01-01T00:00:00Z' WHERE user_id=?", (user["id"],))
    email, tok = asyncio.run(app.gmail.access_token(user["id"]))
    assert tok == "at2"
    row = app.db.one("SELECT * FROM gmail_links WHERE user_id=?", (user["id"],))
    assert "at2" not in row["access_token_enc"]  # المجدد أيضًا مشفر


def test_unlinked_user_has_no_token(app, user):
    _configure(app)
    assert asyncio.run(app.gmail.access_token(user["id"])) is None


class _FakeIMAPOAuth(_FakeIMAP):
    """يضيف authenticate() لمسار XOAUTH2."""
    authed = None

    def authenticate(self, mech, fn):
        assert mech == "XOAUTH2"
        _FakeIMAPOAuth.authed = fn(None)


def test_mailwatch_uses_linked_gmail_and_sends_from_it(app, user, monkeypatch):
    """مستخدم مربوط: المراقبة عبر XOAUTH2 على imap.gmail.com، والموافقة ترسل من بريده هو."""
    _configure(app)
    sent = _setup(app, monkeypatch, user)  # بيانات IMAP عامة موجودة لكن الربط يتقدم عليها
    monkeypatch.setattr("harith.mailwatch.imaplib.IMAP4_SSL", _FakeIMAPOAuth)
    st = app.gmail.make_state(user["id"])
    monkeypatch.setattr("harith.gmail_link.httpx.AsyncClient", _FakeHTTP)
    asyncio.run(app.gmail.finish("code-1", st, "https://srv.example/cb"))
    job = {"id": 1, "user_id": user["id"]}
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))  # بذرة
    assert _FakeIMAPOAuth.authed == xoauth2_string("user@example.com", "at1")
    _FakeIMAP.uids.append("501")
    _FakeIMAP.raws["501"] = _raw("عميل <client@example.com>", "سؤال", "ما السعر؟", "<m501@x>")
    r = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "1 اقتراح" in r, r
    # الموافقة ترسل عبر مسار OAuth من بريده هو
    captured = {}
    class FakeSMTP:
        def __init__(self, host, port, timeout=30):
            captured["host"] = host
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def ehlo(self): pass
        def auth(self, mech, fn):
            captured["auth"] = fn(None)
        def send_message(self, msg):
            captured["from"] = msg["From"]; captured["to"] = msg["To"]
            return {}
    monkeypatch.setattr("harith.tools.smtplib.SMTP_SSL", FakeSMTP)
    aid = app.db.one("SELECT id FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))["id"]
    ok = asyncio.run(app.decide_approval(user["id"], aid, True))
    assert ok["ok"], ok
    assert captured["host"] == "smtp.gmail.com"
    assert captured["from"] == "user@example.com" and captured["to"] == "client@example.com"
    assert captured["auth"] == xoauth2_string("user@example.com", "at1").decode()
    # فك الربط يوقف مساره
    app.gmail.unlink(user["id"])
    assert app.gmail.linked(user["id"]) is None


def test_ensure_recurring_covers_linked_users(app, user):
    app.db.execute(
        "INSERT INTO gmail_links(user_id,email,refresh_token_enc,created_at,updated_at) VALUES(?,?,?,?,?)",
        (user["id"], "u@example.com", "enc", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))
    app.s.mailwatch_users = ""
    app.scheduler.ensure_recurring()
    rows = app.db.all("SELECT user_id FROM jobs WHERE kind='mailwatch' AND status='pending'")
    assert [r["user_id"] for r in rows] == [user["id"]]
