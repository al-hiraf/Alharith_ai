"""اختبارات تنبيهات الصلوات — كاش المواقيت، الجدولة، التفعيل/الإيقاف، الحدود."""
import json
from datetime import timedelta

import pytest

from harith import prayer
from harith.db import iso, now_iso, utcnow


@pytest.fixture
def uid(user):
    return user["id"]


FIXTURE_PAYLOAD = {"data": {"timings": {"Fajr": "04:39", "Dhuhr": "11:37", "Asr": "14:52",
                                        "Maghrib": "17:15", "Isha": "18:45"}}}


def test_parse_timings():
    t = prayer.parse_timings(FIXTURE_PAYLOAD)
    assert t == {"fajr": "04:39", "dhuhr": "11:37", "asr": "14:52", "maghrib": "17:15", "isha": "18:45"}


def test_parse_timings_strips_timezone_suffix():
    payload = {"data": {"timings": {"Fajr": "04:39 (+03)", "Dhuhr": "11:37 (+03)", "Asr": "14:52 (+03)",
                                    "Maghrib": "17:15 (+03)", "Isha": "18:45 (+03)"}}}
    assert prayer.parse_timings(payload)["fajr"] == "04:39"


@pytest.mark.asyncio
async def test_timings_cache_roundtrip(app):
    app.db.execute("INSERT INTO prayer_cache(city,day,timings,fetched_at) VALUES(?,?,?,?)",
                   ("Riyadh", "2099-01-02", json.dumps({"fajr": "04:39", "dhuhr": "11:37", "asr": "14:52",
                                                        "maghrib": "17:15", "isha": "18:45"}), now_iso()))
    got = await prayer.timings_for(app, "Riyadh", "2099-01-02")
    assert got["fajr"] == "04:39"  # من الكاش دون شبكة
    app.db.execute("DELETE FROM prayer_cache WHERE city='Riyadh' AND day='2099-01-02'")


def test_plan_day_skips_past(app, uid):
    day = (utcnow() + timedelta(days=1)).strftime("%Y-%m-%d")
    n = prayer.plan_day(app, uid, "Asia/Riyadh", {"fajr": "04:39", "dhuhr": "11:37", "asr": "14:52",
                                                  "maghrib": "17:15", "isha": "18:45"}, day)
    assert n == 5
    jobs = app.db.all("SELECT payload, run_at, dedupe_key FROM jobs WHERE kind='prayer' AND user_id=?", (uid,))
    assert len(jobs) == 5
    keys = {j["dedupe_key"] for j in jobs}
    assert f"prayer:{uid}:{day}:fajr" in keys
    labels = {json.loads(j["payload"])["label"] for j in jobs}
    assert "الفجر" in labels
    # يوم ماضٍ: لا جدولة
    past = (utcnow() - timedelta(days=1)).strftime("%Y-%m-%d")
    assert prayer.plan_day(app, uid, "Asia/Riyadh", {"fajr": "04:39"}, past) == 0
    app.db.execute("DELETE FROM jobs WHERE kind='prayer' AND user_id=?", (uid,))


def test_ensure_and_cancel(app, uid):
    app.db.set_setting(uid, "prayer_alerts", True)
    prayer.ensure_today(app, uid, "Asia/Riyadh")
    prayer.ensure_today(app, uid, "Asia/Riyadh")  # لا تكرار
    rows = app.db.all("SELECT * FROM jobs WHERE kind='prayer_day' AND user_id=? AND status='pending'", (uid,))
    assert len(rows) == 1
    prayer.cancel_all(app, uid)
    assert not app.db.one("SELECT 1 FROM jobs WHERE user_id=? AND status='pending' "
                          "AND kind IN ('prayer','prayer_day')", (uid,))
    app.db.set_setting(uid, "prayer_alerts", False)


@pytest.mark.asyncio
async def test_prayer_day_respects_off(app, uid):
    app.db.set_setting(uid, "prayer_alerts", False)
    msg = await prayer.prayer_day({"user_id": uid, "id": 999999}, {}, app)
    assert "موقوفة" in msg


@pytest.mark.asyncio
async def test_prayer_handler_stale_and_off(app, uid):
    app.db.set_setting(uid, "prayer_alerts", False)
    job = {"user_id": uid, "id": 999998, "run_at": now_iso()}
    assert "موقوفة" in await prayer.prayer(job, {"label": "الفجر"}, app)
    app.db.set_setting(uid, "prayer_alerts", True)
    old = iso(utcnow() - timedelta(hours=2))
    assert "فات الموعد" in await prayer.prayer({**job, "run_at": old}, {"label": "الفجر"}, app)
    app.db.set_setting(uid, "prayer_alerts", False)
