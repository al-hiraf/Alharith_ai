"""اختبارات رسالة اليوم التحفيزية — النص اليومي، الإشعار الاختياري، الحدود."""
import pytest

from harith import motivation
from harith.db import now_iso


@pytest.fixture
def uid(user):
    return user["id"]


def test_today_stable_and_short():
    a = motivation.today()
    assert isinstance(a, str) and 3 < len(a) <= 80
    assert motivation.today() == a  # ثابتة خلال اليوم


def test_messages_clean():
    assert len(motivation.MESSAGES) == 50
    assert len(set(motivation.MESSAGES)) == 50  # بلا تكرار
    assert all("\n" not in m and "http" not in m for m in motivation.MESSAGES)


def test_ensure_and_cancel(app, uid):
    motivation.ensure_today(app, uid, "Asia/Riyadh")
    motivation.ensure_today(app, uid, "Asia/Riyadh")  # لا تكرار
    rows = app.db.all("SELECT * FROM jobs WHERE kind='motivation' AND user_id=? AND status='pending'", (uid,))
    assert len(rows) == 1
    motivation.cancel_all(app, uid)
    assert not app.db.one("SELECT 1 FROM jobs WHERE kind='motivation' AND user_id=? AND status='pending'", (uid,))


@pytest.mark.asyncio
async def test_handler_respects_off_and_reschedules(app, uid):
    app.db.set_setting(uid, "motivation_notify", False)
    msg = await motivation.motivation({"user_id": uid, "id": 999997, "run_at": now_iso()}, {"day": "x"}, app)
    assert "موقوف" in msg
    assert not app.db.one("SELECT 1 FROM jobs WHERE kind='motivation' AND user_id=?", (uid,))


@pytest.mark.asyncio
async def test_handler_schedules_tomorrow_when_on(app, uid):
    app.db.set_setting(uid, "motivation_notify", True)
    msg = await motivation.motivation({"user_id": uid, "id": 999996, "run_at": now_iso()}, {"day": "x"}, app)
    assert "أُرسلت" in msg
    nxt = app.db.one("SELECT * FROM jobs WHERE kind='motivation' AND user_id=? AND status='pending'", (uid,))
    assert nxt is not None and nxt["run_at"].endswith(":00:00Z")  # 08:00 محلي = دقائق صفرية بالتخزين UTC
    # رسالة وارد حقيقية بالنص اليومي
    inbox = app.db.one("SELECT content FROM messages WHERE user_id=? AND channel='motivation' ORDER BY id DESC", (uid,))
    assert inbox and motivation.today() in inbox["content"]
    motivation.cancel_all(app, uid)
    app.db.set_setting(uid, "motivation_notify", False)
