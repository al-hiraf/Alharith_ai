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
    for path, needle in (("/download", "نزّل رفيق"), ("/privacy", "سياسة الخصوصية"), ("/terms", "شروط الاستخدام")):
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
    assert r.status_code == 429 and "حد" in r.json()["error"]
