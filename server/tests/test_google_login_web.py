"""مسارا «المتابعة بحساب قوقل» العامان: بدء الموافقة واستبدال رمز النتيجة (مرة واحدة، ١٠ دقائق)."""
import time

from starlette.testclient import TestClient

from harith.web import create_app
from test_gmail_link import _configure


def _client(app):
    c = TestClient(create_app(app, manage_lifecycle=False))
    c.headers["X-Harith"] = "1"
    return c


def test_start_requires_config(app):
    c = _client(app)
    r = c.get("/api/public/google/start")
    assert r.status_code == 503 and "غير مفعّل" in r.json()["error"]


def test_start_returns_consent_url(app):
    _configure(app)
    c = _client(app)
    r = c.get("/api/public/google/start?invite=ABC-123")
    assert r.status_code == 200
    url = r.json()["url"]
    assert url.startswith("https://accounts.google.com/o/oauth2/v2/auth?")
    assert "openid" in url and "https%3A%2F%2Fmail.google.com%2F" in url


def test_redeem_single_use_and_expiry(app):
    c = _client(app)
    app.db.set_setting(0, "glogin:tok1", {"token": "hrt_x", "name": "أ", "email": "a@b.c",
                                          "mail_linked": True, "created": False, "ts": time.time()})
    r = c.post("/api/public/google/redeem", json={"code": "tok1"})
    assert r.status_code == 200
    body = r.json()
    assert body["token"] == "hrt_x" and body["mail_linked"] and body["server_url"]
    # مرة واحدة فقط
    assert c.post("/api/public/google/redeem", json={"code": "tok1"}).status_code == 404
    # رمز مجهول
    assert c.post("/api/public/google/redeem", json={"code": "nope"}).status_code == 404
    # منتهي (> ١٠ دقائق)
    app.db.set_setting(0, "glogin:tok2", {"token": "hrt_y", "ts": time.time() - 700})
    assert c.post("/api/public/google/redeem", json={"code": "tok2"}).status_code == 410
    assert not app.db.get_setting(0, "glogin:tok2")
