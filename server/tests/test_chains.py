"""السلاسل المتكررة لا تنقطع: فشل قناة الإشعار، أو تعطّل موقع المواقيت، أو ضياع المهمة القادمة."""
import asyncio
from datetime import timedelta

from harith.db import iso, parse_iso, utcnow


def _pending_jobs(app, kind, uid=None):
    q = "SELECT * FROM jobs WHERE kind=? AND status='pending'" + (" AND user_id=?" if uid else "")
    return app.db.all(q, (kind, uid) if uid else (kind,))


def test_recurring_reminder_survives_push_failure(app, user, monkeypatch):
    rid = app.reminders.create(user["id"], "صلاة الفجر", iso(utcnow() - timedelta(seconds=2)), recur="daily")
    real = app.notify

    async def failing_notify(uid, text, **kw):
        await real(uid, text, dedupe=kw.get("dedupe"), kind=kw.get("kind", "notice"))  # الصندوق يُحفظ
        return {"inbox": True, "fcm": False, "failed": "fcm: UNAVAILABLE"}
    monkeypatch.setattr(app, "notify", failing_notify)
    asyncio.run(app.scheduler.run_due())
    r = app.db.one("SELECT * FROM reminders WHERE id=?", (rid,))
    assert r["status"] == "pending" and parse_iso(r["due_at"]) > utcnow()  # انتقل للغد رغم فشل الـ push
    assert "UNAVAILABLE" in (r["last_error"] or "")
    nxt = [j for j in _pending_jobs(app, "reminder") if f'"id": {rid}' in j["payload"]]
    assert len(nxt) == 1 and parse_iso(nxt[0]["run_at"]) > utcnow()
    assert app.db.one("SELECT COUNT(*) n FROM messages WHERE user_id=? AND role='notice'", (user["id"],))["n"] == 1


def test_recurring_reminder_survives_notify_exception(app, user, monkeypatch):
    rid = app.reminders.create(user["id"], "منبه العمل", iso(utcnow() - timedelta(seconds=2)), recur="daily_except_friday")

    async def boom(*a, **kw):
        raise RuntimeError("network down")
    monkeypatch.setattr(app, "notify", boom)
    asyncio.run(app.scheduler.run_due())
    assert [j for j in _pending_jobs(app, "reminder") if f'"id": {rid}' in j["payload"]]


def test_repair_relinks_orphaned_recurring_reminder(app, user):
    rid = app.reminders.create(user["id"], "منبه الجمعة", iso(utcnow() - timedelta(days=3)), recur="weekly")
    app.db.execute("DELETE FROM jobs WHERE kind='reminder'")  # سلسلة مقطوعة كما حدث بالعيب القديم
    assert app.scheduler.repair_chains() == 1
    r = app.db.one("SELECT due_at FROM reminders WHERE id=?", (rid,))
    assert parse_iso(r["due_at"]) > utcnow()
    assert app.scheduler.repair_chains() == 0  # لا تكرار


def test_prayer_cycle_survives_api_outage(app, user, monkeypatch):
    from harith import prayer
    app.db.set_setting(user["id"], "prayer_alerts", True)
    app.db.set_setting(user["id"], "prayer_city", "Riyadh")

    async def down(*a, **kw):
        return None
    monkeypatch.setattr(prayer, "timings_for", down)
    job = {"user_id": user["id"], "id": 1, "run_at": iso(utcnow())}
    try:
        asyncio.run(prayer.prayer_day(job, {}, app))
    except Exception:
        pass  # لا مواقيت محفوظة: إعادة محاولة — لكن الغد مجدول مسبقًا
    assert _pending_jobs(app, "prayer_day", user["id"])
    # مع مواقيت محفوظة من يوم سابق: تُستخدم بدل الفشل
    app.db.execute("INSERT INTO prayer_cache(city,day,timings,fetched_at) VALUES('Riyadh','2026-01-01',?,?)",
                   ('{"Fajr":"23:58","Dhuhr":"23:58","Asr":"23:58","Maghrib":"23:58","Isha":"23:59"}', iso(utcnow())))
    out = asyncio.run(prayer.prayer_day(job, {}, app))
    assert "المحفوظة" in out


def test_repair_restarts_prayer_cycle(app, user):
    app.db.set_setting(user["id"], "prayer_alerts", True)
    app.db.execute("DELETE FROM jobs WHERE kind IN ('prayer','prayer_day')")
    assert app.scheduler.repair_chains() >= 1
    assert _pending_jobs(app, "prayer_day", user["id"])


def test_user_settings_for_dhikr_mail_and_alerts(app, user):
    from test_acceptance import client
    c = client(app)
    s = c.get("/api/settings").json()["settings"]
    assert s["dhikr_enabled"] is True and s["mailwatch_enabled"] is True and s["prayer_alerts"] is False
    assert c.put("/api/settings", json={"dhikr_enabled": False, "dhikr_times": "05:30,21:00",
                                        "mailwatch_enabled": False, "mailwatch_interval_min": 1,
                                        "biz_alerts_enabled": False}).status_code == 200
    s = c.get("/api/settings").json()["settings"]
    assert (s["dhikr_enabled"], s["dhikr_times"], s["mailwatch_enabled"], s["mailwatch_interval_min"]) == (False, "05:30,21:00", False, 5)
    assert c.put("/api/settings", json={"dhikr_times": "25:99"}).status_code == 400
