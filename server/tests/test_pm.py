"""إدارة المشاريع: القوالب، اللوحة، المراحل، الاعتماديات، الوقت، العبء، الصحة."""
import asyncio
from datetime import date, timedelta

from harith.pm import board_data, health_data, timeline_data, workload_data
from harith.toolkit import Ctx

from test_acceptance import client


def ex(app, user, _tool, **a):
    return asyncio.run(app.tools.execute(Ctx(app, user, "test"), _tool, a))


def test_template_builds_phases_tasks_dates_and_dependencies(app, user):
    r = ex(app, user, "project_from_template", template="construction", name="فيلا الياسمين", start="2026-11-01",
           owner="م. أحمد")
    assert r.ok and r.data["milestones"] == 7 and r.data["tasks"] == 21
    pid = r.data["id"]
    tl = timeline_data(app.db, user["id"], pid, user["timezone"])
    assert tl["milestones"][0]["title"] == "التصميم والتراخيص" and tl["milestones"][0]["start"] == "2026-11-01"
    assert tl["project"]["end"] > "2027-01-01"
    # أول مهمة في كل مرحلة تنتظر آخر مهمة في المرحلة السابقة
    deps = app.db.all("SELECT depends_on FROM tasks WHERE project_id=? AND depends_on IS NOT NULL", (pid,))
    assert len(deps) == 6
    assert all(t["assignee"] == "م. أحمد" for t in app.db.all("SELECT assignee FROM tasks WHERE project_id=?", (pid,)))


def test_board_moves_and_dependency_guard(app, user):
    pid = ex(app, user, "project_from_template", template="general", name="مشروع تجربة").data["id"]
    b = board_data(app.db, user["id"], pid, user["timezone"])
    assert [c["id"] for c in b["columns"]] == ["todo", "doing", "waiting", "done"]
    todo = b["columns"][0]["tasks"]
    blocked = next(t for t in todo if t["depends_on"])
    assert blocked["blocked_by"]
    r = ex(app, user, "task_move", task=blocked["id"], column="doing")
    assert not r.ok and "تنتظر" in r.text
    first = next(t for t in todo if not t["depends_on"])
    assert ex(app, user, "task_move", task=first["id"], column="waiting").ok
    assert ex(app, user, "task_move", task=first["id"], column="done").ok
    b = board_data(app.db, user["id"], pid, user["timezone"])
    assert any(t["id"] == first["id"] for t in b["columns"][3]["tasks"])
    assert app.db.one("SELECT status FROM tasks WHERE id=?", (first["id"],))["status"] == "done"


def test_dependency_cycle_rejected(app, user):
    a = ex(app, user, "add_task", title="أ").data["id"]
    b = ex(app, user, "add_task", title="ب", depends_on=a).data["id"]
    r = ex(app, user, "update_task", id=a, depends_on=b)
    assert not r.ok and "حلقة" in r.text


def test_task_fields_time_and_workload(app, user):
    ex(app, user, "create_project", name="موقع الشركة", start="2026-10-01", end="2026-12-31", owner="مهند")
    t = ex(app, user, "add_task", title="تصميم الصفحة الرئيسية", project="موقع الشركة", assignee="سارة",
           tags="تصميم، عاجل", estimate_hours=3, start="2026-10-15T09:00", due="2026-10-16T17:00").data
    assert t["assignee"] == "سارة" and t["tags"] == ["تصميم", "عاجل"] and t["estimate_min"] == 180 and t["start"]
    r = ex(app, user, "log_time", task=t["id"], hours=4, note="مراجعات العميل")
    assert r.ok and "تجاوز التقدير" in r.text
    w = workload_data(app.db, user["id"], user["timezone"])
    assert w[0]["assignee"] == "سارة" and w[0]["spent_hours"] == 4.0


def test_health_flags_late_milestone_and_overdue(app, user):
    start = (date.today() - timedelta(days=200)).isoformat()
    pid = ex(app, user, "project_from_template", template="software", name="تطبيق متأخر", start=start).data["id"]
    h = health_data(app.db, user["id"], pid, user["timezone"])
    assert h["level"] == "red" and h["late_milestones"] and h["overdue"] > 0
    fresh = ex(app, user, "project_from_template", template="event", name="فعالية قادمة",
               start=(date.today() + timedelta(days=10)).isoformat()).data["id"]
    assert health_data(app.db, user["id"], fresh, user["timezone"])["level"] == "green"


def test_web_pm_endpoints(app, user):
    c = client(app)
    tpls = c.get("/api/pm/templates").json()
    assert {t["id"] for t in tpls} >= {"construction", "software", "event", "marketing", "general"}
    r = c.post("/api/tool/project_from_template", json={"template": "marketing", "name": "حملة رمضان"}).json()
    assert r["ok"]
    pid = r["data"]["id"]
    assert len(c.get(f"/api/projects/{pid}/board").json()["columns"]) == 4
    assert c.get(f"/api/projects/{pid}/timeline").json()["milestones"]
    assert "health" in c.get(f"/api/projects/{pid}/health").json()
    lst = c.get("/api/projects").json()
    assert lst[0]["health"] in ("green", "amber", "red") and "progress" in lst[0]
    tid = c.get(f"/api/projects/{pid}/board").json()["columns"][0]["tasks"][0]["id"]
    assert c.patch(f"/api/tasks/{tid}", json={"column": "doing", "assignee": "خالد"}).json()["ok"]
    assert c.patch(f"/api/projects/{pid}", json={"owner": "مهند", "status": "paused"}).json()["ok"]
    assert c.get("/api/workload").json()
