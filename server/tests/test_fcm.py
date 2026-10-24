"""الإشعارات الفورية (FCM): إيقاظ بلا محتوى، عزل بين المستخدمين، تنظيف الرموز الميتة، إعادة المحاولة، وعدم التأثير على الإشعارات."""
import asyncio
import json

import httpx
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from harith.fcm import FcmPush
from harith.security import create_user
from test_acceptance import client
from test_notifications import login

TOK_A = "a" * 30 + ":APA91bExampleTokenA"
TOK_B = "b" * 30 + ":APA91bExampleTokenB"


@pytest.fixture
def fcm(app, tmp_path):
    key = rsa.generate_private_key(65537, 2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption()).decode()
    f = tmp_path / "sa.json"
    f.write_text(json.dumps({"type": "service_account", "project_id": "proj-1", "private_key": pem,
                             "client_email": "sa@proj-1.iam.gserviceaccount.com"}))
    app.s.fcm_credentials_file = str(f)
    app.s.fcm_app_id, app.s.fcm_api_key, app.s.fcm_sender_id = "1:1:android:x", "key", "1"
    sent = []
    plan = {"fcm": [200]}

    def handler(req: httpx.Request) -> httpx.Response:
        if "oauth" in str(req.url):
            return httpx.Response(200, json={"access_token": "tok", "expires_in": 3600})
        body = json.loads(req.content)
        sent.append((str(req.url), req.headers.get("authorization"), body))
        code = plan["fcm"].pop(0) if len(plan["fcm"]) > 1 else plan["fcm"][0]
        if code == 404:
            return httpx.Response(404, json={"error": {"status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}})
        return httpx.Response(code, json={})

    app.s.fcm_token_uri = "https://oauth.test/token"
    p = FcmPush(app, http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    app.push = p
    app.channels.append(p)
    return p, sent, plan


def test_push_requires_login_and_config_hidden_when_off(app, user):
    c = client(app)
    c.cookies.clear()
    assert c.get("/api/push/config").status_code == 401
    assert c.post("/api/push/register", json={"token": TOK_A}).status_code in (401, 403)


def test_register_validates_and_isolates_users(app, user, fcm):
    p, sent, _ = fcm
    c = client(app)
    assert c.get("/api/push/config").json()["enabled"] is True
    assert c.post("/api/push/register", json={"token": "short"}).status_code == 400
    assert c.post("/api/push/register", json={"token": TOK_A, "label": "Redmi"}).json()["ok"]
    create_user(app.db, "sara", "password456")
    other = app.db.one("SELECT * FROM users WHERE username='sara'")
    asyncio.run(app.notify(other["id"], "سري لسارة"))
    assert sent == []            # سارة بلا رمز، ورمز مهند لا يستلم شيئًا لها
    asyncio.run(app.notify(user["id"], "خاص بمهند"))
    assert len(sent) == 1
    url, auth, body = sent[0]
    assert url.endswith("/v1/projects/proj-1/messages:send") and auth == "Bearer tok"
    m = body["message"]
    assert m["token"] == TOK_A and m["data"] == {"t": "sync"} and "notification" not in m
    assert "مهند" not in json.dumps(body, ensure_ascii=False)       # لا نص من المستخدم في الرسالة
    assert m["android"]["priority"] == "HIGH" and m["android"]["collapse_key"]


def test_token_moves_to_latest_authenticated_user(app, user, fcm):
    p, sent, _ = fcm
    create_user(app.db, "sara", "password456")
    sara = app.db.one("SELECT * FROM users WHERE username='sara'")
    p.register(user["id"], TOK_A)
    p.register(sara["id"], TOK_A)
    rows = app.db.all("SELECT user_id FROM push_tokens WHERE token=?", (TOK_A,))
    assert [r["user_id"] for r in rows] == [sara["id"]]
    assert p.unregister(user["id"], TOK_A) is False and p.unregister(sara["id"], TOK_A) is True


def test_dead_token_removed_and_failure_never_breaks_notify(app, user, fcm):
    p, sent, plan = fcm
    p.register(user["id"], TOK_A)
    plan["fcm"] = [404]
    out = asyncio.run(app.notify(user["id"], "x"))
    assert out["inbox"] is True and "failed" not in out
    assert app.db.one("SELECT 1 FROM push_tokens WHERE token=?", (TOK_A,)) is None
    p.register(user["id"], TOK_B)
    plan["fcm"] = [400]
    out = asyncio.run(app.notify(user["id"], "y"))
    assert out["inbox"] is True and "failed" not in out
    assert app.db.one("SELECT fail_count FROM push_tokens WHERE token=?", (TOK_B,))["fail_count"] == 1


def test_retry_on_server_error_then_success_and_no_duplicate_inbox(app, user, fcm, monkeypatch):
    p, sent, plan = fcm

    async def fast(_): return None
    monkeypatch.setattr(asyncio, "sleep", fast)
    p.register(user["id"], TOK_A)
    plan["fcm"] = [503, 200]
    asyncio.run(app.notify(user["id"], "z", dedupe="k1"))
    asyncio.run(app.notify(user["id"], "z", dedupe="k1"))
    assert len(sent) == 2  # 503 ثم نجاح؛ والإعادة بنفس المفتاح لا ترسل مجددًا
    assert app.db.one("SELECT COUNT(*) c FROM messages WHERE role='notice'")["c"] == 1


def test_unconfigured_channel_is_silent(app, user, tmp_path):
    p = FcmPush(app)
    assert p.client_config() == {"enabled": False}
    assert asyncio.run(p.send(user["id"], "x")) is None


def test_rs256_signature_verifies_with_standard_library():
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from harith.fcm import rs256_sign
    for fmt in (serialization.PrivateFormat.PKCS8, serialization.PrivateFormat.TraditionalOpenSSL):
        k = rsa.generate_private_key(65537, 2048)
        pem = k.private_bytes(serialization.Encoding.PEM, fmt, serialization.NoEncryption()).decode()
        k.public_key().verify(rs256_sign(b"abc.def", {"type": "service_account", "client_email": "a@b.iam", "private_key": pem}), b"abc.def", padding.PKCS1v15(), hashes.SHA256())


def test_logout_unregister_stops_wakes_and_old_account_gets_nothing(app, user, fcm):
    p, sent, _ = fcm
    c = client(app)
    c.post("/api/push/register", json={"token": TOK_A})
    assert c.request("DELETE", "/api/push/register", json={"token": TOK_A}).json()["ok"] is True
    asyncio.run(app.notify(user["id"], "بعد الخروج"))
    assert sent == []


def test_failed_and_stale_tokens_pruned(app, user, fcm):
    from harith.fcm import MAX_FAILS
    p, sent, plan = fcm
    p.register(user["id"], TOK_A)
    p.register(user["id"], TOK_B)
    app.db.execute("UPDATE push_tokens SET fail_count=? WHERE token=?", (MAX_FAILS, TOK_A))
    app.db.execute("UPDATE push_tokens SET updated_at='2000-01-01T00:00:00Z' WHERE token=?", (TOK_B,))
    assert p.prune() == 2
    assert app.db.all("SELECT id FROM push_tokens") == []
    # رمز سليم لا يُحذف
    p.register(user["id"], TOK_A)
    assert p.prune() == 0


def test_missing_cryptography_disables_push_without_breaking_notify(app, user, fcm, monkeypatch):
    import builtins
    p, sent, _ = fcm
    p.register(user["id"], TOK_A)
    real = builtins.__import__

    def fake(name, *a, **k):
        if name.startswith("cryptography"):
            raise ImportError("no cryptography")
        return real(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", fake)
    out = asyncio.run(app.notify(user["id"], "بلا مكتبة"))
    monkeypatch.setattr(builtins, "__import__", real)
    assert out["inbox"] is True and sent == []
    assert "cryptography" in p._last_error
