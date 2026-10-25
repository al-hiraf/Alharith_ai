"""إشعارات متصفحات الويب (Web Push): SSRF/allowlist/DNS، إثبات الملكية بين المستخدمين،
تطابق مفاتيح VAPID، صحة المعرّف، حدود إعادة المحاولة، وتوليد المفاتيح. مستخدمون وهميون فقط."""
import asyncio
import base64
import os
import socket
import stat
import subprocess
import sys
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from harith.webpush import WebPush, SubscriptionConflict
from test_acceptance import client
from test_notifications import login
from harith.security import create_user

b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def run(coro):
    return asyncio.run(coro)


def _pair():
    k = ec.generate_private_key(ec.SECP256R1())
    priv = b64(k.private_numbers().private_value.to_bytes(32, "big"))
    pub = b64(k.public_key().public_bytes(serialization.Encoding.X962,
                                         serialization.PublicFormat.UncompressedPoint))
    return priv, pub


VALID = "https://web.push.apple.com/QFixtureToken"
K65 = b64(b"k" * 65)
A16 = b64(b"a" * 16)


@pytest.fixture
def wp(app, monkeypatch):
    priv, pub = _pair()
    app.s.vapid_private_key, app.s.vapid_public_key = priv, pub
    app.s.vapid_subject = "https://rafiq01.duckdns.org"
    app.s.vapid_allowed_hosts = ""
    sent = []
    plan = {"codes": [201]}

    def handler(req: httpx.Request) -> httpx.Response:
        sent.append(str(req.url))
        return httpx.Response(plan["codes"].pop(0) if len(plan["codes"]) > 1 else plan["codes"][0])

    # DNS وهمي: كل المضيفات تحلّ لعنوان عام — بلا شبكة حقيقية في الاختبارات
    monkeypatch.setattr(socket, "getaddrinfo",
                        lambda h, p, *a, **kw: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", p))])
    w = WebPush(app, http=httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=5))
    w._sent, w._plan = sent, plan
    return w


def uid_of(app, username):
    return app.db.one("SELECT id FROM users WHERE username=?", (username,))["id"]


# ——— SSRF: رفض الوجهات الخطرة عند التسجيل
def test_register_rejects_malicious_endpoints(wp):
    bad = ["http://web.push.apple.com/x", "https://localhost/", "https://127.0.0.1/x",
           "https://169.254.169.254/latest", "https://10.0.0.5/x", "https://[::1]/x",
           "https://user:pass@web.push.apple.com/x", "https://web.push.apple.com:8443/x",
           "https://evil-web.push.apple.com.evil.com/x", "https://web.push.apple.com./x",
           "https://192.168.1.1/x", "https://100.64.1.1/x"]
    for ep in bad:
        with pytest.raises(ValueError):
            run(wp.register(1, ep, K65, A16))
    assert run(wp.check_endpoint("https://WEB.PUSH.APPLE.COM/x")) == ""   # الحالة تُطبَّع بأمان


def test_register_rejects_private_dns(wp, monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo",
                        lambda h, p, *a, **kw: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", p))])
    with pytest.raises(ValueError):
        run(wp.register(1, VALID, K65, A16))


def test_send_revalidates_dns(wp, app, monkeypatch):
    run(wp.register(1, VALID, K65, A16))
    monkeypatch.setattr(socket, "getaddrinfo",
                        lambda h, p, *a, **kw: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.1.2.3", p))])

    async def go():
        assert await wp.send(1, "x") == "queued"  # جدولة فورية؛ الحظر يحدث داخل مهمة الخلفية
        await wp.pending()
    run(go())
    assert app.db.one("SELECT fail_count FROM webpush_subs WHERE user_id=1")["fail_count"] == 1
    assert wp._sent == []


def test_allowlist_accepts_known_push_services(wp):
    for good in ["https://web.push.apple.com/a", "https://fcm.googleapis.com/fcm/send/x",
                 "https://updates.push.services.mozilla.com/wpush/v2/x",
                 "https://db3.notify.windows.com/w/?token=x", "https://android.googleapis.com/x"]:
        assert run(wp.check_endpoint(good)) == "", good
    for bad in ["https://push.apple.com.evil.com/x", "https://notfcm.googleapis.com.evil.com/x"]:
        assert run(wp.check_endpoint(bad)) != "", bad


# ——— إعادة المحاولة: محاولة واحدة + إعادة واحدة عند 429/5xx فقط
def test_retry_only_once_on_5xx(wp):
    wp._plan["codes"] = [500, 201]
    run(wp.register(1, VALID, K65, A16))

    async def go():
        await wp.send(1, "x")
        await wp.pending()
    run(go())
    assert len(wp._sent) == 2


def test_no_retry_on_403(wp):
    wp._plan["codes"] = [403]
    run(wp.register(1, VALID, K65, A16))

    async def go():
        await wp.send(1, "x")
        await wp.pending()
    run(go())
    assert len(wp._sent) == 1


def test_gone_subscription_deleted(wp, app):
    wp._plan["codes"] = [410]
    run(wp.register(1, VALID, K65, A16))

    async def go():
        await wp.send(1, "x")
        await wp.pending()
    run(go())
    assert app.db.all("SELECT id FROM webpush_subs WHERE user_id=1") == []


def test_send_returns_immediately_with_hanging_endpoint(wp):
    import time as _t

    async def hanging(req: httpx.Request) -> httpx.Response:
        await asyncio.sleep(30)
        return httpx.Response(201)
    wp.http = httpx.AsyncClient(transport=httpx.MockTransport(hanging), timeout=5)
    run(wp.register(1, VALID, K65, A16))

    async def go():
        start = _t.monotonic()
        res = await wp.send(1, "x")
        assert _t.monotonic() - start < 0.5
        assert res == "queued"
        for t in list(wp._tasks):
            t.cancel()
        await wp.pending()
    run(go())


# ——— عقد «queued» عبر Core.notify الفعلي (اختبارات ريجرسشن)
def _wire(wp, app):
    app.webpush = wp
    if wp not in app.channels:
        app.channels.append(wp)


def test_notify_reports_queued_not_true(wp, app, user):
    _wire(wp, app)
    run(wp.register(user["id"], VALID, K65, A16))
    r = run(app.notify(user["id"], "اختبار", dedupe="q:t1"))
    assert r["webpush"] == "queued" and r["webpush"] is not True
    run(wp.pending())


def test_notify_twice_same_dedupe_sends_one_push(wp, app, user):
    _wire(wp, app)
    run(wp.register(user["id"], VALID, K65, A16))

    async def go():
        await app.notify(user["id"], "اختبار", dedupe="q:t2")
        await app.notify(user["id"], "اختبار", dedupe="q:t2")   # إعادة: لا إيقاظ ثانٍ
        await wp.pending()
    run(go())
    assert len(wp._sent) == 1


def test_notify_not_delayed_by_hanging_push(wp, app, user):
    import time as _t

    async def hanging(req: httpx.Request) -> httpx.Response:
        await asyncio.sleep(30)
        return httpx.Response(201)
    wp.http = httpx.AsyncClient(transport=httpx.MockTransport(hanging), timeout=5)
    _wire(wp, app)
    run(wp.register(user["id"], VALID, K65, A16))

    async def go():
        start = _t.monotonic()
        r = await app.notify(user["id"], "اختبار", dedupe="q:t3")
        assert _t.monotonic() - start < 0.5
        assert r["webpush"] == "queued"
        for t in list(wp._tasks):
            t.cancel()
        await wp.stop()          # الإيقاف يصرّف المهام المتعقَّبة (بعد الإلغاء) بلا تعليق
    run(go())


def test_failed_push_keeps_notice_in_inbox(wp, app, user):
    wp._plan["codes"] = [403]
    _wire(wp, app)
    run(wp.register(user["id"], VALID, K65, A16))

    async def go():
        await app.notify(user["id"], "اختبار مهم", dedupe="q:t4")
        await wp.pending()
    run(go())
    row = app.db.one("SELECT fail_count FROM webpush_subs WHERE user_id=?", (user["id"],))
    assert row["fail_count"] == 1
    n = app.db.one("SELECT content FROM messages WHERE user_id=? AND role='notice' ORDER BY id DESC",
                   (user["id"],))
    assert n["content"] == "اختبار مهم"


# ——— الملكية: لا انتقال بين المستخدمين بلا إثبات مفاتيح
def test_cross_user_move_requires_key_proof(wp, app):
    create_user(app.db, "fixture-owner2", "password123", role="user")
    run(wp.register(1, VALID, K65, A16, "A"))
    with pytest.raises(SubscriptionConflict):
        run(wp.register(2, VALID, b64(b"z" * 65), b64(b"w" * 16)))
    row = app.db.one("SELECT user_id, label FROM webpush_subs WHERE endpoint=?", (VALID,))
    assert row["user_id"] == 1 and row["label"] == "A"
    run(wp.register(2, VALID, K65, A16, "B"))
    row = app.db.one("SELECT user_id FROM webpush_subs WHERE endpoint=?", (VALID,))
    assert row["user_id"] == 2
    assert app.db.all("SELECT id FROM webpush_subs WHERE user_id=1") == []
    run(wp.register(2, VALID, K65, A16, "B2"))
    assert app.db.one("SELECT label FROM webpush_subs WHERE endpoint=?", (VALID,))["label"] == "B2"


# ——— المفاتيح والمعرّف
def test_keypair_mismatch_disables(app):
    priv, _ = _pair()
    _, wrong_pub = _pair()
    app.s.vapid_private_key, app.s.vapid_public_key = priv, wrong_pub
    app.s.vapid_subject = "https://rafiq01.duckdns.org"
    w = WebPush(app)
    assert not w.enabled() and "لا يطابق" in w.status()


def test_subject_validation(app):
    priv, pub = _pair()
    app.s.vapid_private_key, app.s.vapid_public_key = priv, pub
    for bad in ["", "rafiq.local", "http://rafiq01.duckdns.org"]:
        app.s.vapid_subject = bad
        assert not WebPush(app).enabled(), bad
    for good in ["mailto:ops@example.com", "https://rafiq01.duckdns.org"]:
        app.s.vapid_subject = good
        assert WebPush(app).enabled(), good


def test_webpush_keys_command(tmp_path):
    out = tmp_path / "vapid.env"
    root = Path(__file__).resolve().parent.parent
    env = {**os.environ, "PYTHONPATH": str(root) + os.pathsep + os.environ.get("PYTHONPATH", "")}
    r = subprocess.run([sys.executable, "-m", "harith", "webpush-keys", str(out)],
                       capture_output=True, text=True, env=env, cwd=root)
    assert r.returncode == 0, r.stderr
    assert stat.S_IMODE(os.stat(out).st_mode) == 0o600
    content = out.read_text()
    priv = [l.split("=", 1)[1] for l in content.splitlines() if l.startswith("VAPID_PRIVATE_KEY=")][0]
    assert priv not in (r.stdout + r.stderr)   # الخاص لا يُطبع أبدًا


# ——— تكامل API: التسجيل عبر المسار الموثّق + رفض 409 عبر HTTP
def test_api_register_and_conflict(wp, app, user):
    app.webpush = wp
    c = client(app)
    r = c.post("/api/push/webpush", json={"endpoint": VALID, "keys": {"p256dh": K65, "auth": A16}, "label": "web"})
    assert r.status_code == 200 and r.json()["ok"]
    assert app.db.one("SELECT user_id FROM webpush_subs WHERE endpoint=?", (VALID,))["user_id"] == user["id"]
    create_user(app.db, "fixture2", "password123", role="user")
    c2 = login(app, "fixture2", "password123")
    r = c2.post("/api/push/webpush", json={"endpoint": VALID, "keys": {"p256dh": b64(b"z" * 65), "auth": b64(b"w" * 16)}})
    assert r.status_code == 409
    r = c2.post("/api/push/webpush", json={"endpoint": VALID, "keys": {"p256dh": K65, "auth": A16}})
    assert r.status_code == 200
    uid2 = uid_of(app, "fixture2")
    assert app.db.one("SELECT user_id FROM webpush_subs WHERE endpoint=?", (VALID,))["user_id"] == uid2
    r = c2.request("DELETE", "/api/push/webpush", json={"endpoint": VALID})
    assert r.status_code == 200 and app.db.all("SELECT id FROM webpush_subs") == []


def test_register_rejected_when_channel_disabled(wp, app, user):
    app.s.vapid_subject = ""            # قناة معطّلة بإعداد ناقص
    app.webpush = WebPush(app)
    c = client(app)
    r = c.post("/api/push/webpush", json={"endpoint": VALID, "keys": {"p256dh": K65, "auth": A16}})
    assert r.status_code == 404
    assert app.db.all("SELECT id FROM webpush_subs") == []


def test_push_config_exposes_webpush(wp, app, user):
    app.webpush = wp
    c = client(app)
    j = c.get("/api/push/config").json()
    assert j["webpush"]["enabled"] and j["webpush"]["vapid_public_key"]
