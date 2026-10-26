"""جاهزية ١٠٠ مجرّب: دعوات التسجيل، قفل حد الصرف، حذف الحساب، حدود الطلبات، وصفحات الموقع."""
from starlette.testclient import TestClient

from harith.web import create_app

from test_acceptance import client
from test_team import bearer


def anon(app):
    return TestClient(create_app(app, manage_lifecycle=False))


def signup(app, code, username="tester1", password="tester-pass-1"):
    r = anon(app).post("/api/public/join", json={"code": code, "name": "مجرّب", "username": username,
                                                 "password": password, "app": True})
    return r


def test_signup_invite_creates_independent_account(app, user):
    admin = client(app)
    inv = admin.post("/api/admin/invites", json={"days": 7, "max_uses": 2, "note": "الدفعة الأولى"}).json()
    assert len(inv["code"]) == 8 and inv["link"].endswith("/join/" + inv["code"])
    peek = anon(app).get(f"/api/public/invite/{inv['code']}").json()
    assert peek["signup"] is True and not peek["project"]
    r = signup(app, inv["code"])
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["token"].startswith("hrt_") and d["project"] == ""
    t = bearer(app, d["token"])
    # حساب مستقل: يضيف مهامه ولا يرى مهام المدير ولا مشاريعه
    admin.post("/api/tool/add_task", json={"title": "مهمة المدير السرية"})
    t.post("/api/tool/add_task", json={"title": "مهمة المجرّب"})
    titles = [x["title"] for x in app.db.all("SELECT title FROM tasks WHERE user_id=(SELECT id FROM users WHERE username='tester1')")]
    assert titles == ["مهمة المجرّب"]
    assert t.get("/api/projects").json() == []
    # الحد الأقصى للاستخدام
    assert signup(app, inv["code"], "tester2").status_code == 200
    assert signup(app, inv["code"], "tester3").status_code == 403
    # غير المدير لا يصدر دعوات
    assert t.post("/api/admin/invites", json={}).status_code == 403
    lst = admin.get("/api/admin/invites").json()["invites"]
    assert lst[0]["uses"] == 2 and lst[0]["active"] is False


def test_cost_limit_locked_for_users(app, user):
    admin = client(app)
    inv = admin.post("/api/admin/invites", json={}).json()
    t = bearer(app, signup(app, inv["code"]).json()["token"])
    uid = app.db.one("SELECT id FROM users WHERE username='tester1'")["id"]
    t.put("/api/settings", json={"daily_cost_limit_usd": 100})
    assert float(app.db.get_setting(uid, "daily_cost_limit_usd", 0)) == 2.0  # لا يتجاوز الحد الافتراضي
    t.put("/api/settings", json={"daily_cost_limit_usd": 0.5})
    assert float(app.db.get_setting(uid, "daily_cost_limit_usd", 0)) == 0.5  # التخفيض مسموح
    assert admin.patch(f"/api/users/{uid}", json={"daily_limit_usd": 4}).status_code == 200
    t.put("/api/settings", json={"daily_cost_limit_usd": 10})
    assert float(app.db.get_setting(uid, "daily_cost_limit_usd", 0)) == 4.0


def test_account_delete_removes_everything(app, user):
    admin = client(app)
    inv = admin.post("/api/admin/invites", json={}).json()
    tok = signup(app, inv["code"]).json()["token"]
    t = bearer(app, tok)
    t.post("/api/tool/add_task", json={"title": "مهمة للحذف"})
    assert t.post("/api/account/delete", json={"password": "wrong"}).status_code == 403
    r = t.post("/api/account/delete", json={"password": "tester-pass-1"})
    assert r.status_code == 200, r.text
    assert app.db.one("SELECT 1 FROM users WHERE username='tester1'") is None
    assert app.db.one("SELECT COUNT(*) n FROM tasks WHERE title='مهمة للحذف'")["n"] == 0
    assert t.get("/api/settings").status_code == 401  # المفتاح لم يعد يعمل
    # المدير الوحيد لا يحذف نفسه
    assert admin.post("/api/account/delete", json={"password": "password123"}).status_code == 400


def test_rate_limit_on_chat(app, user):
    c = client(app)
    codes = [c.post("/api/chat", json={"text": "مرحبا"}).status_code for _ in range(23)]
    assert 429 in codes and codes[0] != 429


def test_public_pages(app, user):
    a = anon(app)
    for path, needle in (("/download", "افتح الدعوة في رفيق"), ("/privacy", "سياسة الخصوصية"), ("/terms", "شروط الاستخدام")):
        r = a.get(path)
        assert r.status_code == 200 and needle in r.text and "{{CONTACT}}" not in r.text
    sw = a.get("/sw.js")
    assert sw.status_code == 200 and sw.headers["service-worker-allowed"] == "/"
    assert a.get("/static/manifest.json").json()["display"] == "standalone"


def test_move_to_new_server_with_setup_token(app, user, tmp_path, monkeypatch):
    from harith.config import load_settings
    from harith.core import Harith
    from harith.scheduler import run_backup
    client(app).post("/api/tool/add_task", json={"title": "مهمة من الجوال"})
    backup = run_backup(app).read_bytes()
    s2 = load_settings(env_file=str(tmp_path / "none2.env"), data_dir=tmp_path / "new", ai_provider="fake",
                       telegram_bot_token="", gemini_api_key="", admin_password="")
    new = Harith(s2)
    try:
        monkeypatch.setenv("RAFIQ_SETUP_TOKEN", "secret-setup-1")
        a = anon(new)
        assert a.post("/api/setup/restore", content=backup).status_code == 403  # بلا رمز الإعداد
        assert a.post("/api/setup", json={"password": "x" * 10}, headers={"X-Harith": "1"}).status_code == 403
        r = a.post("/api/setup/restore", content=backup, headers={"X-Setup-Token": "secret-setup-1"})
        assert r.status_code == 200, r.text and r.json()["admins"] == ["mohand"]
        assert new.db.one("SELECT 1 FROM tasks WHERE title='مهمة من الجوال'")
        assert a.post("/api/login", json={"username": "mohand", "password": "password123"}).status_code == 200
        # بعد الاستيراد لا يُسمح باستيراد ثانٍ
        assert a.post("/api/setup/restore", content=backup, headers={"X-Setup-Token": "secret-setup-1"}).status_code == 403
        assert a.post("/api/setup/restore", content=b"not a db", headers={"X-Setup-Token": "secret-setup-1"}).status_code == 403
    finally:
        new.db.close()


def test_server_ai_proxy_with_daily_limit(app, user):
    admin = client(app)
    inv = admin.post("/api/admin/invites", json={}).json()
    t = bearer(app, signup(app, inv["code"]).json()["token"])
    st = t.get("/api/ai/status").json()
    assert st["available"] is True and st["limit"] == 2.0
    r = t.post("/v1/chat/completions", json={"model": "x", "messages": [{"role": "user", "content": "مرحبا"}]})
    assert r.status_code == 200 and r.json()["choices"][0]["message"]["content"] == "تم"
    # بلا مفتاح وصول: مرفوض
    assert anon(app).post("/v1/chat/completions", json={"messages": []}).status_code == 401
    # عند تجاوز الحد اليومي
    uid = app.db.one("SELECT id FROM users WHERE username='tester1'")["id"]
    from harith.db import now_iso
    app.db.execute("INSERT INTO usage(user_id,provider,model,input_tokens,output_tokens,cost_usd,created_at) VALUES(?,?,?,?,?,?,?)",
                   (uid, "x", "x", 1, 1, 5.0, now_iso()))
    r = t.post("/v1/chat/completions", json={"messages": [{"role": "user", "content": "مرحبا"}]})
    assert r.status_code == 429 and "حد" in r.json()["error"]["message"]


def test_public_url_from_https_proxy_headers(app, user):
    admin = client(app)
    h = {"X-Forwarded-Proto": "https", "X-Forwarded-Host": "rafiq.example.xyz"}
    inv = admin.post("/api/admin/invites", json={}, headers=h).json()
    assert inv["link"].startswith("https://rafiq.example.xyz/join/") and inv["local_only"] is False
    plain = admin.get("/api/admin/invites").json()
    assert plain["local_only"] is True  # بلا HTTPS ولا رابط مضبوط


def test_open_signup_from_app_uses_server_key(app, user, monkeypatch):
    a = anon(app)
    info = a.get("/api/public/info").json()
    assert info["provisioning"] is True and info["mode"] == "server"
    r = a.post("/api/public/register", json={"device_id": "dev-1234567890", "name": "سارة"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["mode"] == "server" and d["token"].startswith("hrt_")
    t = bearer(app, d["token"])
    res = t.post("/v1/chat/completions", json={"messages": [{"role": "user", "content": "هلا"}]})
    assert res.status_code == 200 and res.json()["choices"][0]["message"]["content"]
    # نفس الجهاز بعد إعادة التثبيت: نفس الحساب
    d2 = a.post("/api/public/register", json={"device_id": "dev-1234567890", "name": "سارة"}).json()
    assert app.db.one("SELECT COUNT(*) n FROM users WHERE username LIKE 'app_%'")["n"] == 1 and d2["token"] != d["token"]
    # المستخدم لا يرى بيانات غيره ولا يصير مديرًا
    assert t.get("/api/admin/invites").status_code == 403
    # الحد الأقصى للمستخدمين
    monkeypatch.setenv("RAFIQ_MAX_USERS", "1")
    assert a.post("/api/public/register", json={"device_id": "dev-other-999999", "name": "x"}).status_code == 403
    # إيقاف التسجيل
    monkeypatch.setenv("RAFIQ_OPEN_SIGNUP", "0")
    assert a.get("/api/public/info").json()["provisioning"] is False
    assert a.post("/api/public/register", json={"device_id": "dev-third-99999", "name": "y"}).status_code == 503


def test_admin_app_users_page_lists_every_open_signup(app, user):
    a = anon(app)
    for i in range(5):
        assert a.post("/api/public/register", json={"device_id": f"dev-list-{i:08d}", "name": f"مشترك {i}"}).status_code == 200
    d = client(app).get("/api/devices").json()
    names = sorted(x["display_name"] for x in d["app_users"])
    assert names == [f"مشترك {i}" for i in range(5)]
    assert d["app_max"] >= 5 and d["open_signup"] is True
    assert all("cost_today" in x and "limit" in x for x in d["app_users"])


def test_open_signup_keeps_name_up_to_date(app, user):
    a = anon(app)
    a.post("/api/public/register", json={"device_id": "dev-name-0000001", "name": "خالد"})
    a.post("/api/public/register", json={"device_id": "dev-name-0000001", "name": "خالد العتيبي"})
    a.post("/api/public/register", json={"device_id": "dev-name-0000001"})  # بلا اسم: لا يمسح الاسم
    names = [x["display_name"] for x in client(app).get("/api/devices").json()["app_users"]]
    assert names == ["خالد العتيبي"]


def test_open_signup_requires_a_name_for_new_accounts(app, user):
    a = anon(app)
    assert a.post("/api/public/register", json={"device_id": "dev-noname-00001"}).status_code == 400
    assert a.post("/api/public/register", json={"device_id": "dev-noname-00001", "name": "   "}).status_code == 400
    assert app.db.one("SELECT COUNT(*) n FROM users WHERE username LIKE 'app_%'")["n"] == 0
    assert a.post("/api/public/register", json={"device_id": "dev-noname-00001", "name": "نورة"}).status_code == 200
    a.post("/api/public/register", json={"device_id": "dev-noname-00001", "name": "  "})  # مسافات لا تمسح الاسم
    assert [x["display_name"] for x in client(app).get("/api/devices").json()["app_users"]] == ["نورة"]


def test_ai_proxy_turns_provider_errors_into_clear_messages(app, user, monkeypatch):
    import httpx
    d = anon(app).post("/api/public/register", json={"device_id": "dev-proxy-000001", "name": "سعد"}).json()
    t = bearer(app, d["token"])
    monkeypatch.setattr(app.s, "ai_provider", "custom")
    monkeypatch.setattr(app.s, "custom_base_url", "https://example.invalid/v1")
    replies = {}

    async def fake_post(url, **kw):
        return httpx.Response(replies["code"], json=replies["body"])
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    msg = {"messages": [{"role": "user", "content": "هلا"}]}
    replies.update(code=429, body=[{"error": {"code": 429, "message": "quota", "status": "RESOURCE_EXHAUSTED"}}])
    r = t.post("/v1/chat/completions", json=msg)
    assert r.status_code == 429 and "مزدحمة" in r.text
    replies.update(code=400, body=[{"error": {"code": 400, "message": "bad"}}])
    assert t.post("/v1/chat/completions", json=msg).status_code == 502
    replies.update(code=200, body={"choices": [{"message": {"role": "assistant", "content": "أبشر"}}], "usage": {}})
    r = t.post("/v1/chat/completions", json=msg)
    assert r.status_code == 200 and r.json()["choices"][0]["message"]["content"] == "أبشر"


def _proxy_setup(app, monkeypatch, dev):
    d = anon(app).post("/api/public/register", json={"device_id": dev, "name": "سعد"}).json()
    monkeypatch.setattr(app.s, "ai_provider", "custom")
    monkeypatch.setattr(app.s, "custom_base_url", "https://example.invalid/v1")
    return bearer(app, d["token"])


def test_ai_proxy_typed_errors_retry_after_and_bounded_retry(app, user, monkeypatch):
    import httpx
    t = _proxy_setup(app, monkeypatch, "dev-proxy-typed001")
    calls = []
    plan = {"codes": []}

    async def fake_post(url, **kw):
        calls.append(url)
        code = plan["codes"].pop(0) if plan["codes"] else 200
        if code == 200:
            return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {}})
        return httpx.Response(code, json=plan.get("body") or {"error": {"code": code, "message": "busy"}})
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    monkeypatch.setattr("harith.web.asyncio.sleep", _fast_sleep)
    msg = {"messages": [{"role": "user", "content": "هلا"}]}
    # ضغط مؤقت ثم نجاح عند الإعادة الواحدة
    plan["codes"] = [429, 200]
    r = t.post("/v1/chat/completions", json=msg)
    assert r.status_code == 200 and len(calls) == 2
    # ضغط مستمر: إعادة واحدة فقط ثم خطأ مصنّف مع Retry-After
    calls.clear(); plan["codes"] = [429, 429]
    r = t.post("/v1/chat/completions", json=msg)
    assert r.status_code == 429 and len(calls) == 2
    assert r.json()["error"]["code"] == "upstream_busy" and r.headers.get("retry-after")
    # حصة يومية: بلا إعادة، وبرمز مختلف
    calls.clear(); plan["codes"] = [429]
    plan["body"] = {"error": {"code": 429, "message": "Quota exceeded: GenerateRequestsPerDayPerProjectPerModel-FreeTier"}}
    r = t.post("/v1/chat/completions", json=msg)
    assert r.status_code == 429 and len(calls) == 1 and r.json()["error"]["code"] == "upstream_daily"


async def _fast_sleep(*a, **k):
    return None


def test_ai_proxy_daily_cap_is_typed_and_not_confused(app, user, monkeypatch):
    t = _proxy_setup(app, monkeypatch, "dev-proxy-typed002")
    monkeypatch.setattr(app.s, "daily_cost_limit_usd", 0.0)
    r = t.post("/v1/chat/completions", json={"messages": [{"role": "user", "content": "x"}]})
    assert r.status_code == 429 and r.json()["error"]["code"] == "daily_cap"


def _proxy_env(app, monkeypatch, dev):
    import httpx
    t = _proxy_setup(app, monkeypatch, dev)
    st = {"calls": [], "sleeps": [], "script": []}

    async def fake_post(url, **kw):
        st["calls"].append(url)
        code, headers, body = st["script"].pop(0) if st["script"] else (200, {}, None)
        if code == 200:
            return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {}})
        return httpx.Response(code, headers=headers, json=body or {"error": {"code": code, "message": "busy"}})

    async def rec_sleep(s, *a, **k):
        st["sleeps"].append(s)
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    monkeypatch.setattr("harith.web.asyncio.sleep", rec_sleep)
    return t, st


MSG = {"messages": [{"role": "user", "content": "هلا"}]}


def test_retry_after_30s_is_not_retried_early_and_is_propagated(app, user, monkeypatch):
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-ra30-0001")
    st["script"] = [(429, {"Retry-After": "30"}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 429 and len(st["calls"]) == 1 and not st["sleeps"]
    assert r.headers["retry-after"] == "30" and r.json()["error"]["code"] == "upstream_busy"


def test_short_retry_after_is_honored_exactly(app, user, monkeypatch):
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-ra2s-0001")
    st["script"] = [(429, {"Retry-After": "2"}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 200 and len(st["calls"]) == 2 and st["sleeps"] == [2.0]


def test_no_retry_after_header_waits_one_second_once(app, user, monkeypatch):
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-ra0-00001")
    st["script"] = [(429, {}, None), (200, {}, None)]
    assert t.post("/v1/chat/completions", json=MSG).status_code == 200
    assert st["sleeps"] == [1.0] and len(st["calls"]) == 2


def test_http_date_retry_after_future_is_not_retried_early(app, user, monkeypatch):
    from datetime import datetime, timedelta, timezone
    from email.utils import format_datetime
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-radate-001")
    when = format_datetime(datetime.now(timezone.utc) + timedelta(seconds=90), usegmt=True)
    st["script"] = [(429, {"Retry-After": when}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 429 and len(st["calls"]) == 1 and not st["sleeps"]
    assert 60 <= int(r.headers["retry-after"]) <= 91


def test_unparseable_retry_after_skips_inline_retry(app, user, monkeypatch):
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-rabad-0001")
    st["script"] = [(429, {"Retry-After": "soon-ish"}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 429 and len(st["calls"]) == 1 and not st["sleeps"]
    assert r.headers["retry-after"] == "20"


def test_daily_quota_not_retried_and_fallback_needs_explicit_flag(app, user, monkeypatch):
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-fb-000001")
    monkeypatch.setattr(app.s, "ai_fallback_provider", "openrouter")
    monkeypatch.setattr(app.s, "openrouter_api_key", "k-test")
    monkeypatch.setattr(app.s, "ai_fallback_model", "vendor/m:free")
    body = {"error": {"code": 429, "message": "Quota exceeded GenerateRequestsPerDayPerProjectPerModel-FreeTier"}}
    st["script"] = [(429, {}, body), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)       # بلا AI_PROXY_FALLBACK: لا احتياطي
    assert r.status_code == 429 and len(st["calls"]) == 1 and r.json()["error"]["code"] == "upstream_daily"
    st["calls"].clear(); st["script"] = [(429, {}, body), (200, {}, None)]
    monkeypatch.setattr(app.s, "ai_proxy_fallback", True)
    r = t.post("/v1/chat/completions", json=MSG)       # بالموافقة الصريحة: ينتقل للاحتياطي مرة واحدة
    assert r.status_code == 200 and len(st["calls"]) == 2 and "openrouter" in st["calls"][1]


def _kf_env(app, monkeypatch, dev):
    t, st = _proxy_env(app, monkeypatch, dev)
    monkeypatch.setattr(app.s, "ai_provider", "gemini")
    monkeypatch.setattr(app.s, "gemini_api_key", "primary-k")
    monkeypatch.setattr(app.s, "gemini_api_key_backup", "backup-k")
    return t, st


def test_key_failover_is_outage_only_never_on_429(app, user, monkeypatch):
    t, st = _kf_env(app, monkeypatch, "dev-proxy-key2-0001")
    monkeypatch.setattr(app.s, "ai_proxy_key_failover", True)
    st["script"] = [(429, {"Retry-After": "30"}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 429 and len(st["calls"]) == 1 and r.headers["retry-after"] == "30"
    st["calls"].clear(); st["script"] = [(429, {"Retry-After": "2"}, None), (429, {"Retry-After": "2"}, None), (200, {}, None)]
    r = t.post("/v1/chat/completions", json=MSG)       # 429: إعادة المزوّد المحترمة فقط، بلا مفتاح ثانٍ
    assert r.status_code == 429 and len(st["calls"]) == 2


def test_key_failover_on_5xx_sequential_without_sleep_and_off_by_default(app, user, monkeypatch):
    import httpx
    t, st = _kf_env(app, monkeypatch, "dev-proxy-key3-0001")
    auths = []

    async def fake_post(url, **kw):
        auths.append(kw["headers"]["Authorization"])
        if len(auths) == 1:
            return httpx.Response(503, json={"error": {"code": 503}})
        return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {}})
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    t.post("/v1/chat/completions", json=MSG)           # الميزة مغلقة: لا مفتاح ثانٍ
    assert "Bearer backup-k" not in auths
    auths.clear(); st["sleeps"].clear()
    monkeypatch.setattr(app.s, "ai_proxy_key_failover", True)
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 200 and auths == ["Bearer primary-k", "Bearer backup-k"] and not st["sleeps"]


def test_openrouter_fallback_is_free_model_only(app, user, monkeypatch):
    import json as _j
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-orfree-001")
    monkeypatch.setattr(app.s, "ai_fallback_provider", "openrouter")
    monkeypatch.setattr(app.s, "openrouter_api_key", "k-test")
    monkeypatch.setattr(app.s, "ai_proxy_fallback", True)
    models = []
    provs = []
    import httpx

    async def fake_post(url, **kw):
        models.append((("openrouter" in url), kw["json"]["model"]))
        if "openrouter" in url:
            provs.append(kw["json"].get("provider"))
        if not models[-1][0]:
            return httpx.Response(503, json={"error": {"code": 503}})
        return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {}})
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    t.post("/v1/chat/completions", json=MSG)            # بلا موديل صريح: لا احتياطي (الافتراضي مدفوع)
    assert not any(o for o, _ in models)
    models.clear()
    monkeypatch.setattr(app.s, "ai_fallback_model", "openai/gpt-4o-mini")   # مدفوع: مرفوض
    t.post("/v1/chat/completions", json=MSG)
    assert not any(o for o, _ in models)
    models.clear()
    monkeypatch.setattr(app.s, "ai_fallback_model", "vendor/some-model:free")
    r = t.post("/v1/chat/completions", json=MSG)
    assert r.status_code == 200 and (True, "vendor/some-model:free") in models
    assert provs == [{"data_collection": "deny", "zdr": True, "allow_fallbacks": False}]


def test_free_fallback_usage_records_actual_model_and_zero_cost(app, user, monkeypatch):
    import httpx
    t, st = _proxy_env(app, monkeypatch, "dev-proxy-orcost-001")
    monkeypatch.setattr(app.s, "ai_fallback_provider", "openrouter")
    monkeypatch.setattr(app.s, "openrouter_api_key", "k-test")
    monkeypatch.setattr(app.s, "ai_proxy_fallback", True)
    monkeypatch.setattr(app.s, "ai_fallback_model", "vendor/some-model:free")

    async def fake_post(url, **kw):
        if "openrouter" not in url:
            return httpx.Response(503, json={"error": {"code": 503}})
        return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}],
                                         "usage": {"prompt_tokens": 2_000_000, "completion_tokens": 1_000_000}})
    monkeypatch.setattr(app.ai.http, "post", fake_post)
    for _ in range(3):
        assert t.post("/v1/chat/completions", json=MSG).status_code == 200      # لا يصل سقف اليومي بسبب كلفة وهمية
    rows = app.db.all("SELECT provider, model, cost_usd FROM usage ORDER BY id DESC LIMIT 3")
    assert len(rows) == 3 and all(r["provider"] == "openrouter" and r["model"] == "vendor/some-model:free" and r["cost_usd"] == 0 for r in rows)
    from harith.ai import estimate_cost
    assert estimate_cost("x/y:free", 10**7, 10**7) == 0.0 and estimate_cost("openai/gpt-4o-mini", 10**6, 0) > 0


def test_server_starts_without_optional_static_sites(app):
    # مجلد موقع إضافي غير موجود (مثل hikaya_site) لا يمنع تشغيل الخادم
    from starlette.testclient import TestClient
    from harith.web import create_app
    c = TestClient(create_app(app, manage_lifecycle=False))
    assert c.get("/api/health").status_code == 200
    # حكاية انتقل لاستضافة مستقلة: /hikaya تحويلة علنية دائمة، لا 404 ولا 401
    r = c.get("/hikaya/lessons/index.json", follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"] == "https://hikaya01.duckdns.org/lessons/index.json"
    r2 = c.get("/hikaya", follow_redirects=False)
    assert r2.status_code == 307 and r2.headers["location"] == "https://hikaya01.duckdns.org/"
