"""مركز الإشعارات: الإنشاء من حدث حقيقي، العدّاد، المقروء، الحذف، العزل بين المستخدمين، وعدم التكرار."""
import asyncio

from starlette.testclient import TestClient

from harith.security import create_user
from harith.web import create_app
from test_acceptance import client


def login(app, username, password):
    c = TestClient(create_app(app, manage_lifecycle=False))
    assert c.post("/api/login", json={"username": username, "password": password}).status_code == 200
    c.headers["X-Harith"] = "1"
    return c


def test_notice_center_unread_read_delete(app, user):
    c = client(app)
    uid = user["id"]
    for i in range(3):
        asyncio.run(app.notify(uid, f"إشعار {i}", dedupe=f"t:{i}"))
    asyncio.run(app.notify(uid, "إشعار 0", dedupe="t:0"))  # إعادة المحاولة بنفس المفتاح لا تكرر
    s = c.get("/api/notifications/summary").json()
    assert s["unread"] == 3 and [x["content"] for x in s["items"]] == ["إشعار 2", "إشعار 1", "إشعار 0"]
    first = s["items"][-1]["id"]
    r = c.post("/api/notifications/read", json={"ids": [first]}).json()
    assert r["unread"] == 2
    s = c.get("/api/notifications/summary").json()
    assert [x["read"] for x in s["items"]] == [False, False, True]
    assert c.post("/api/notifications/read", json={"all": True}).json()["unread"] == 0
    assert c.delete(f"/api/notifications/{first}").status_code == 200
    assert len(c.get("/api/notifications/summary").json()["items"]) == 2
    assert c.delete(f"/api/notifications/{first}").status_code == 404
    assert c.post("/api/notifications/read", json={}).status_code == 400


def test_first_fetch_returns_newest_not_oldest(app, user):
    c = client(app)
    for i in range(60):
        asyncio.run(app.notify(user["id"], f"n{i}"))
    rows = c.get("/api/notifications").json()
    assert len(rows) == 50 and rows[-1]["content"] == "n59" and rows[0]["content"] == "n10"
    last = rows[-1]["id"]
    assert c.get(f"/api/notifications?after={last}").json() == []
    asyncio.run(app.notify(user["id"], "جديد"))
    assert [x["content"] for x in c.get(f"/api/notifications?after={last}").json()] == ["جديد"]


def test_notices_are_private_per_user(app, user):
    create_user(app.db, "other", "password456")
    other = app.db.one("SELECT id FROM users WHERE username='other'")["id"]
    asyncio.run(app.notify(user["id"], "سري للمدير"))
    c2 = login(app, "other", "password456")
    assert c2.get("/api/notifications").json() == []
    assert c2.get("/api/notifications/summary").json()["unread"] == 0
    mine = client(app).get("/api/notifications").json()[-1]["id"]
    assert c2.delete(f"/api/notifications/{mine}").status_code == 404
    c2.post("/api/notifications/read", json={"ids": [mine]})
    assert client(app).get("/api/notifications/summary").json()["unread"] == 1
    assert other


def test_role_change_notifies_the_user(app, user):
    c = client(app)
    uid = c.post("/api/users", json={"username": "sara", "password": "Sara-pass-2026"}).json()["id"]
    assert c.post("/api/users", json={"username": "sara", "password": "Sara-pass-2026"}).status_code == 409
    c.patch(f"/api/users/{uid}", json={"role": "admin"})
    job = app.db.one("SELECT * FROM jobs WHERE user_id=? AND kind='notify'", (uid,))
    assert job and "مدير" in job["payload"]
    asyncio.run(app.scheduler.run_due())
    assert app.db.one("SELECT status FROM jobs WHERE id=?", (job["id"],))["status"] == "done"
    notes = [m["content"] for m in app.db.all("SELECT content FROM messages WHERE user_id=? AND role='notice'", (uid,))]
    assert notes == ["🔑 تغيّرت صلاحيتك في رفيق إلى مدير"]
    asyncio.run(app.scheduler.run_due())  # تشغيل ثانٍ لا يكرر الإشعار
    assert app.db.one("SELECT COUNT(*) n FROM messages WHERE user_id=? AND role='notice'", (uid,))["n"] == 1


def test_reminder_fires_once_and_lands_in_the_inbox(app, user):
    from datetime import timedelta
    from harith.db import iso, utcnow
    c = client(app)
    rid = app.reminders.create(user["id"], "اتصل بالمحاسب", iso(utcnow() - timedelta(seconds=1)))
    asyncio.run(app.scheduler.run_due())
    asyncio.run(app.scheduler.run_due())
    items = c.get("/api/notifications/summary").json()["items"]
    assert [(x["kind"], x["content"]) for x in items] == [("reminder", "🔔 تذكير: اتصل بالمحاسب")]
    assert app.db.one("SELECT status FROM reminders WHERE id=?", (rid,))["status"] == "sent"
