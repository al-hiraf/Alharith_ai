"""تحويلات الوقت بين المنطقة الزمنية للمستخدم وUTC."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    from backports.zoneinfo import ZoneInfo  # type: ignore

from .db import iso, parse_iso

AR_DAYS = ["الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"]


def tz(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except Exception:
        return ZoneInfo("Asia/Riyadh")


def local_now(tzname: str) -> datetime:
    return datetime.now(tz(tzname))


def parse_local(value: str, tzname: str) -> datetime:
    """يقبل 2026-10-10T09:00 أو 2026-10-10 09:00 أو 2026-10-10 (بالتوقيت المحلي) ويعيد وقتًا بتوقيت UTC."""
    v = (value or "").strip().replace(" ", "T")
    if not v:
        raise ValueError("الوقت فارغ")
    if len(v) == 10:
        v += "T09:00"
    dt = datetime.fromisoformat(v.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz(tzname))
    return dt.astimezone(timezone.utc)


def to_local_str(utc_iso: str | None, tzname: str, with_day: bool = True) -> str:
    dt = parse_iso(utc_iso)
    if not dt:
        return ""
    loc = dt.astimezone(tz(tzname))
    s = loc.strftime("%Y-%m-%d %H:%M")
    return f"{AR_DAYS[loc.weekday()]} {s}" if with_day else s


def local_day_bounds(tzname: str, day: datetime | None = None) -> tuple[str, str]:
    loc = (day or local_now(tzname)).astimezone(tz(tzname))
    start = loc.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + timedelta(days=1)
    return iso(start), iso(end)  # type: ignore[return-value]


def next_occurrence(utc_iso: str, recur: str, tzname: str) -> str | None:
    """الموعد التالي لتذكير متكرر بالتوقيت المحلي (يحافظ على الساعة عند تغيّر التوقيت)."""
    dt = parse_iso(utc_iso)
    if not dt or recur in ("", "none"):
        return None
    loc = dt.astimezone(tz(tzname))
    if recur == "daily":
        nxt = loc + timedelta(days=1)
    elif recur == "weekdays":
        nxt = loc + timedelta(days=1)
        while nxt.weekday() in (4, 5):  # الجمعة والسبت عطلة
            nxt += timedelta(days=1)
    elif recur == "weekly":
        nxt = loc + timedelta(weeks=1)
    elif recur == "monthly":
        m = loc.month + 1
        y = loc.year + (m - 1) // 12
        m = (m - 1) % 12 + 1
        day = min(loc.day, [31, 29 if y % 4 == 0 and (y % 100 or y % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30,
                            31, 30, 31][m - 1])
        nxt = loc.replace(year=y, month=m, day=day)
    else:
        return None
    nxt = nxt.replace(tzinfo=None).replace(tzinfo=tz(tzname))  # إعادة ربط بالمنطقة (توقيت صيفي)
    return iso(nxt)
