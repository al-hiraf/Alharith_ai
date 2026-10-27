"""الفريق: الدعوة والانضمام، الصلاحيات (يرى كل المشروع ويعدّل مهامه فقط)، الإسناد والإشعارات، السجل."""
from starlette.testclient import TestClient

from harith.security import create_user
from harith.web import create_app

from test_acceptance import client


def bearer(app, token):
    c = TestClient(create_app(app, manage_lifecycle=False))
    c.headers["Authorization"] = f"Bearer {token}"
    return c


def notify_jobs(app, uid):
    return [j for j in app.db.all("SELECT * FROM jobs WHERE kind='notify' AND user_id=?", (uid,))]


def setup_project(app):
    owner = client(app)
    pid = owner.post("/api/tool/project_from_template", json={"template": "general", "name": "أجوان", "budget": "100000"}).json()["data"]["id"]
    inv = owner.post(f"/api/projects/{pid}/invite").json()
    assert len(inv["code"]) == 8 and inv["link"].endswith("/join/" + inv["code"])
    anon = TestClient(create_app(app, manage_lifecycle=False))
    assert anon.get(f"/api/public/invite/{inv['code']}").json()["project"] == "أجوان"
    r = anon.post("/api/public/join", json={"code": inv["code"].lower(), "name": "خالد", "username": "khalid",
                                            "password": "khalid-pass-1", "app": True, "device_name": "جوال خالد"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["token"].startswith("hrt_") and d["project"] == "أجوان"
    assert d["username"] == "khalid" and "email" in d  # يحتاجهما التطبيق لحارس خلط الحسابات عند الدخول
    kid = app.db.one("SELECT id FROM users WHERE username='khalid'")["id"]
    return owner, bearer(app, d["token"]), pid, kid


def test_join_and_member_view_without_finance(app, user):
    owner, k, pid, kid = setup_project(app)
    lst = k.get("/api/projects").json()
    mine = next(p for p in lst if p["id"] == pid)
    assert mine["role"] == "member" and mine["owner_name"] == "مهند" and "health" in mine
    b = k.get(f"/api/projects/{pid}/board").json()
    assert b["role"] == "member" and b["me"] == kid and len(b["columns"]) == 4
    assert k.get(f"/api/projects/{pid}/timeline").json()["milestones"]
    h = k.get(f"/api/projects/{pid}/health").json()
    assert h["health"]["budget_percent"] is None and h["role"] == "member"
    g = k.get(f"/api/projects/{pid}").json()
    assert g["role"] == "member" and g["entries"] == [] and "budget_minor" not in g["project"]
    # العضو لا يعدّل المشروع ولا يدعو
    assert k.patch(f"/api/projects/{pid}", json={"name": "x"}).status_code == 404
    assert k.post(f"/api/projects/{pid}/invite").status_code == 403
    # صاحب المشروع أُشعر بالانضمام، والفريق يظهر
    assert any("انضم خالد" in j["payload"] for j in notify_jobs(app, user["id"]))
    team = owner.get(f"/api/projects/{pid}/team").json()
    assert [m["role"] for m in team["members"]] == ["owner", "member"]
    assert any("انضم" in a["action"] for a in team["activity"])


def test_assign_move_log_permissions_and_notifications(app, user):
    owner, k, pid, kid = setup_project(app)
    todo = owner.get(f"/api/projects/{pid}/board").json()["columns"][0]["tasks"]
    free = next(t for t in todo if not t["depends_on"])
    other = next(t for t in todo if t["id"] != free["id"])
    # الإسناد لحساب العضو ← إشعار له
    r = owner.patch(f"/api/tasks/{free['id']}", json={"assignee_user": kid}).json()
    assert r["ok"] and r["task"]["assignee_user_id"] == kid and r["task"]["assignee"] == "خالد"
    assert any("أُسندت إليك" in j["payload"] for j in notify_jobs(app, kid))
    # الإسناد بالاسم المكتوب يربط الحساب تلقائيًا
    owner.patch(f"/api/tasks/{other['id']}", json={"assignee": "خالد"})
    assert app.db.one("SELECT assignee_user_id FROM tasks WHERE id=?", (other["id"],))["assignee_user_id"] == kid
    owner.patch(f"/api/tasks/{other['id']}", json={"assignee": "سالم"})
    assert app.db.one("SELECT assignee_user_id FROM tasks WHERE id=?", (other["id"],))["assignee_user_id"] is None
    # مهامي في الفريق
    assert [t["id"] for t in k.get("/api/team/my-tasks").json()] == [free["id"]]
    # لا يعدّل مهمة غيره
    assert k.post(f"/api/team/tasks/{other['id']}/move", json={"column": "doing"}).status_code == 403
    # يعدّل مهمته ← صاحب المشروع يُشعر عند الإنجاز
    assert k.post(f"/api/team/tasks/{free['id']}/move", json={"column": "doing"}).json()["ok"]
    assert k.post(f"/api/team/tasks/{free['id']}/log", json={"minutes": 45}).json()["ok"]
    assert k.post(f"/api/team/tasks/{free['id']}/move", json={"column": "done"}).json()["ok"]
    assert app.db.one("SELECT status, spent_min FROM tasks WHERE id=?", (free["id"],)) == {"status": "done", "spent_min": 45}
    assert any("خالد أنهى" in j["payload"] for j in notify_jobs(app, user["id"]))
    acts = [a["action"] for a in owner.get(f"/api/projects/{pid}/team").json()["activity"]]
    actors = {a["actor"] for a in owner.get(f"/api/projects/{pid}/team").json()["activity"]}
    assert any("سجّل 45 دقيقة" in a for a in acts) and any("مكتملة" in a for a in acts) and "خالد" in actors
    # الإسناد لغير عضو مرفوض
    create_user(app.db, "stranger", "stranger-pass", display_name="غريب")
    sid = app.db.one("SELECT id FROM users WHERE username='stranger'")["id"]
    assert not owner.patch(f"/api/tasks/{other['id']}", json={"assignee_user": sid}).json().get("ok", False)


def test_outsider_blocked_and_removal(app, user):
    owner, k, pid, kid = setup_project(app)
    create_user(app.db, "outsider", "outsider-pass", display_name="غريب")
    o = TestClient(create_app(app, manage_lifecycle=False))
    o.post("/api/login", json={"username": "outsider", "password": "outsider-pass"})
    assert o.get(f"/api/projects/{pid}/board").status_code == 404
    assert all(p["id"] != pid for p in o.get("/api/projects").json())
    # إضافة مستخدم موجود بالاسم
    assert owner.post(f"/api/projects/{pid}/members", json={"username": "outsider"}).json()["ok"]
    assert o.get(f"/api/projects/{pid}/board").status_code == 200
    # الإزالة تسحب الوصول
    assert owner.delete(f"/api/projects/{pid}/members/{kid}").json()["ok"]
    assert k.get(f"/api/projects/{pid}/board").status_code == 404


def test_bad_invites(app, user):
    owner, k, pid, kid = setup_project(app)
    anon = TestClient(create_app(app, manage_lifecycle=False))
    assert anon.post("/api/public/join", json={"code": "AAAAAAAA", "username": "x1x", "password": "12345678"}).status_code == 403
    code = owner.post(f"/api/projects/{pid}/invite").json()["code"]
    # اسم مستخدم موجود بكلمة مرور خاطئة
    assert anon.post("/api/public/join", json={"code": code, "username": "khalid", "password": "wrong-pass"}).status_code == 409
    # حساب موجود بكلمة مروره الصحيحة: ينضم دون إنشاء حساب جديد
    r = anon.post("/api/public/join", json={"code": code, "username": "khalid", "password": "khalid-pass-1"})
    assert r.status_code == 200 and app.db.one("SELECT COUNT(*) n FROM users WHERE username='khalid'")["n"] == 1
    # صفحة /join تعرض صفحة التحميل (تحميل التطبيق ثم الانضمام، أو الانضمام من المتصفح)
    jp = anon.get("/join/" + code)
    assert jp.status_code == 200 and "download.js" in jp.text
