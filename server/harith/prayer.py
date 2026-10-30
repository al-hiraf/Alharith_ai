"""تنبيهات الصلوات الخمس بصوت الأذان — مطفأة افتراضياً وتُفعَّل من الإعدادات (prayer_alerts).

المواقيت تُجلب يومياً من Aladhan API (طريقة أم القرى) لمدينة المستخدم وتُخزَّن مؤقتاً —
لا مواقيت ثابتة تنزاح مع الأيام. الرنين على قناة المنبّه نفسها (تتجاوز الصامت)؛
التطبيق 3.9.2+ يشغّل صوت الأذان وما قبله يرنّ بالنغمة العادية — لا كسر للقديم.
"""
import json
from datetime import timedelta

import httpx

from .db import now_iso, parse_iso, utcnow
from .timeutil import local_now, parse_local

PRAYERS = (("fajr", "الفجر"), ("dhuhr", "الظهر"), ("asr", "العصر"),
           ("maghrib", "المغرب"), ("isha", "العشاء"))

CITIES = {
    "Riyadh": "الرياض", "Madinah": "المدينة المنورة", "Makkah": "مكة المكرمة",
    "Jeddah": "جدة", "Dammam": "الدمام", "Khobar": "الخبر", "Taif": "الطائف",
    "Abha": "أبها", "Tabuk": "تبوك", "Buraidah": "بريدة", "Hail": "حائل",
    "Jazan": "جازان", "Najran": "نجران", "AlUla": "العلا",
}

_API = "https://api.aladhan.com/v1/timingsByCity/{date}?city={city}&country=Saudi%20Arabia&method=4"
_ALADHAN_KEYS = {"fajr": "Fajr", "dhuhr": "Dhuhr", "asr": "Asr", "maghrib": "Maghrib", "isha": "Isha"}


def parse_timings(payload: dict) -> dict[str, str]:
    """يستخرج المواقيت الخمس من رد Aladhan (يرفع ValueError عند نقصها)."""
    t = payload["data"]["timings"]
    return {k: str(t[label]).split(" ")[0][:5] for k, label in _ALADHAN_KEYS.items()}


async def timings_for(app, city: str, day: str) -> dict[str, str] | None:
    """مواقيت يوم (YYYY-MM-DD) — من الكاش أولاً ثم الشبكة؛ None عند تعذّر الجلب."""
    row = app.db.one("SELECT timings FROM prayer_cache WHERE city=? AND day=?", (city, day))
    if row:
        try:
            data = json.loads(row["timings"])
            if all(k in data for k, _ in PRAYERS):
                return data
        except ValueError:
            pass
    d = f"{day[8:10]}-{day[5:7]}-{day[0:4]}"
    try:
        async with httpx.AsyncClient(timeout=12) as c:
            data = parse_timings((await c.get(_API.format(date=d, city=city))).json())
    except Exception:
        return None
    app.db.execute("INSERT OR REPLACE INTO prayer_cache(city,day,timings,fetched_at) VALUES(?,?,?,?)",
                   (city, day, json.dumps(data, ensure_ascii=False), now_iso()))
    return data


def plan_day(app, uid: int, tz: str, timings: dict[str, str], day: str) -> int:
    """يجدول صلوات اليوم القادمة فقط؛ يعيد عدد ما جُدول."""
    now = utcnow()
    n = 0
    for name, label in PRAYERS:
        hhmm = timings.get(name)
        if not hhmm:
            continue
        try:
            at = parse_local(f"{day}T{hhmm}", tz)
        except ValueError:
            continue
        if at <= now:
            continue
        app.scheduler.enqueue(uid, "prayer", {"name": name, "label": label, "hhmm": hhmm, "day": day},
                              at, dedupe_key=f"prayer:{uid}:{day}:{name}", max_attempts=3)
        n += 1
    return n


def ensure_today(app, uid: int, tz: str) -> None:
    """يضمن وجود مهمة prayer_day قادمة (تفعيل/إقلاع) — لا يكرر سلسلة حيّة."""
    if app.db.one("SELECT 1 x FROM jobs WHERE kind='prayer_day' AND user_id=? AND status IN ('pending','running')",
                  (uid,)):
        return
    day = local_now(tz).strftime("%Y-%m-%d")
    app.scheduler.enqueue(uid, "prayer_day", {}, now_iso(),
                          dedupe_key=f"prayer_day:{uid}:{day}:ensure", max_attempts=3)


def cancel_all(app, uid: int) -> None:
    app.db.execute("DELETE FROM jobs WHERE user_id=? AND status='pending' AND kind IN ('prayer','prayer_day')",
                   (uid,))


async def prayer_day(job: dict, p: dict, app) -> str:
    """يجدول صلوات اليوم ثم يجدول نفسه للغد — دورة يومية ذاتية التجديد."""
    u = app.db.one("SELECT id, timezone FROM users WHERE id=? AND disabled=0", (job["user_id"],))
    if not u:
        return "مستخدم غير موجود"
    if not app.db.get_setting(u["id"], "prayer_alerts", False):
        return "تخطّي: التنبيهات موقوفة"
    city = str(app.db.get_setting(u["id"], "prayer_city", "Riyadh"))
    if city not in CITIES:
        city = "Riyadh"
    loc = local_now(u["timezone"])
    day = loc.strftime("%Y-%m-%d")
    # الغد يُجدول أولاً: تعطّل موقع المواقيت اليوم لا يقطع الدورة اليومية
    tomorrow = (loc + timedelta(days=1)).strftime("%Y-%m-%d")
    app.scheduler.enqueue(u["id"], "prayer_day", {}, parse_local(f"{tomorrow}T00:07", u["timezone"]),
                          dedupe_key=f"prayer_day:{u['id']}:{tomorrow}", max_attempts=3)
    timings = await timings_for(app, city, day)
    note = ""
    if timings is None:
        # احتياط: آخر مواقيت محفوظة للمدينة (تتغير دقيقة أو دقيقتين يوميًا)
        row = app.db.one("SELECT timings, day FROM prayer_cache WHERE city=? ORDER BY day DESC LIMIT 1", (city,))
        try:
            timings = json.loads(row["timings"]) if row else None
        except ValueError:
            timings = None
        if timings is None:
            from .scheduler import RetryLater
            raise RetryLater(f"تعذّر جلب مواقيت {city}")
        note = f" (من مواقيت {row['day']} المحفوظة)"
    n = plan_day(app, u["id"], u["timezone"], timings, day)
    return f"جُدولت {n} صلوات ليوم {day} ({CITIES[city]}){note}"


async def prayer(job: dict, p: dict, app) -> str:
    """يُطلق تنبيه الصلاة بصوت الأذان — يتخطّى المواعيد الفائتة +٤٥ دقيقة (قاعدة الأذكار نفسها)."""
    u = app.db.one("SELECT id, timezone FROM users WHERE id=? AND disabled=0", (job["user_id"],))
    if not u:
        return "مستخدم غير موجود"
    if not app.db.get_setting(u["id"], "prayer_alerts", False):
        return "تخطّي: التنبيهات موقوفة"
    if (utcnow() - parse_iso(job["run_at"])).total_seconds() > 45 * 60:
        return "تخطّي: فات الموعد"
    label = p.get("label") or "الصلاة"
    text = f"🕌 حان الآن وقت صلاة {label}"
    r = await app.notify(u["id"], text, dedupe=f"job:{job['id']}", kind="reminder",
                         push_data={"t": "reminder", "title": text, "sound": "adhan"})
    if r.get("failed") and not any(v is True for k, v in r.items() if k != "inbox"):
        from .scheduler import RetryLater
        raise RetryLater(r["failed"])  # إعادة محاولة لهذه الصلاة فقط؛ الدورة اليومية مستقلة عنها
    return "أُرسل التنبيه"
