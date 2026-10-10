"""أدوات رفيق: المهام، التذكيرات، الذاكرة، المشاريع، العادات، التركيز، الويب، الملفات، البريد."""
from __future__ import annotations

import asyncio
import csv
import io
import ipaddress
import json
import mimetypes
import re
import smtplib
import socket
import zipfile
from datetime import timedelta
from email.message import EmailMessage
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx

from .db import iso, now_iso, utcnow
from .security import looks_secret, wrap_untrusted
from .timeutil import local_day_bounds, local_now, parse_local, to_local_str
from .toolkit import FORBIDDEN, LOW, NEEDS_APPROVAL, Ctx, Registry, Tool, ToolResult, i, obj, s

PRIORITIES = ["high", "normal", "low"]
PRIO_AR = {"high": "عالية", "normal": "عادية", "low": "منخفضة"}


def _task_view(t: dict, tzname: str) -> dict:
    return {"id": t["id"], "title": t["title"], "priority": t["priority"], "status": t["status"],
            "due": to_local_str(t["due_at"], tzname) if t["due_at"] else "", "project_id": t["project_id"],
            "parent_id": t["parent_id"], "notes": t["notes"][:200],
            "overdue": bool(t["due_at"] and t["status"] in ("open", "in_progress") and t["due_at"] < now_iso())}


def _project_id(ctx: Ctx, ref) -> int | None:
    if ref in (None, "", 0):
        return None
    db = ctx.app.db
    if isinstance(ref, int) or str(ref).isdigit():
        r = db.one("SELECT id FROM projects WHERE id=? AND user_id=?", (int(ref), ctx.uid))
    else:
        r = db.one("SELECT id FROM projects WHERE user_id=? AND name LIKE ? ORDER BY id DESC", (ctx.uid, f"%{ref}%"))
    if not r:
        raise ValueError(f"لا يوجد مشروع بهذا الاسم أو الرقم: {ref}")
    return int(r["id"])


# ═══════════════ المهام
async def add_task(ctx: Ctx, a: dict) -> ToolResult:
    title = (a.get("title") or "").strip()
    if not title:
        raise ValueError("عنوان المهمة مطلوب")
    prio = a.get("priority") or "normal"
    if prio not in PRIORITIES:
        prio = "normal"
    due = iso(parse_local(a["due"], ctx.tz)) if a.get("due") else None
    pid = _project_id(ctx, a.get("project"))
    parent = int(a["parent_id"]) if a.get("parent_id") else None
    if parent and not ctx.app.db.one("SELECT 1 FROM tasks WHERE id=? AND user_id=?", (parent, ctx.uid)):
        raise ValueError("المهمة الأم غير موجودة")
    now = now_iso()
    tid = ctx.app.db.insert(
        "INSERT INTO tasks(user_id,project_id,title,notes,priority,due_at,scope,parent_id,source,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (ctx.uid, pid, title, a.get("notes") or "", prio, due, a.get("scope") or "", parent, ctx.channel, now, now),
    )
    row = ctx.app.db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", (tid, ctx.uid))
    if not row:
        return ToolResult(False, "لم تُحفظ المهمة")
    return ToolResult(True, f"أُضيفت المهمة رقم {tid}", _task_view(row, ctx.tz), verified=True)


async def update_task(ctx: Ctx, a: dict) -> ToolResult:
    tid = int(a["id"])
    db = ctx.app.db
    t = db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", (tid, ctx.uid))
    if not t:
        return ToolResult(False, f"لا توجد مهمة رقم {tid}")
    fields: dict = {}
    for k in ("title", "notes", "scope"):
        if a.get(k) is not None:
            fields[k] = a[k]
    if a.get("priority") in PRIORITIES:
        fields["priority"] = a["priority"]
    if a.get("status") in ("open", "in_progress", "done", "cancelled"):
        fields["status"] = a["status"]
        fields["completed_at"] = now_iso() if a["status"] == "done" else None
    if a.get("due") is not None:
        fields["due_at"] = iso(parse_local(a["due"], ctx.tz)) if a["due"] else None
    if a.get("project") is not None:
        fields["project_id"] = _project_id(ctx, a["project"])
    if not fields:
        return ToolResult(False, "لا توجد تعديلات")
    fields["updated_at"] = now_iso()
    sets = ", ".join(f"{k}=?" for k in fields)
    db.execute(f"UPDATE tasks SET {sets} WHERE id=? AND user_id=?", (*fields.values(), tid, ctx.uid))
    row = db.one("SELECT * FROM tasks WHERE id=?", (tid,))
    ok = all(row[k] == v for k, v in fields.items() if k != "updated_at")
    return ToolResult(ok, "عُدّلت المهمة" if ok else "لم يُطبَّق التعديل", _task_view(row, ctx.tz), verified=ok)


async def complete_task(ctx: Ctx, a: dict) -> ToolResult:
    return await update_task(ctx, {"id": a["id"], "status": "done"})


async def list_tasks(ctx: Ctx, a: dict) -> ToolResult:
    f = a.get("filter") or "open"
    db = ctx.app.db
    q = "SELECT * FROM tasks WHERE user_id=?"
    p: list = [ctx.uid]
    now = now_iso()
    if f == "open":
        q += " AND status IN ('open','in_progress')"
    elif f == "today":
        _, end = local_day_bounds(ctx.tz)
        q += " AND status IN ('open','in_progress') AND due_at IS NOT NULL AND due_at < ?"
        p.append(end)
    elif f == "overdue":
        q += " AND status IN ('open','in_progress') AND due_at IS NOT NULL AND due_at < ?"
        p.append(now)
    elif f == "week":
        q += " AND status IN ('open','in_progress') AND due_at IS NOT NULL AND due_at < ?"
        p.append(iso(utcnow() + timedelta(days=7)))
    elif f == "done":
        q += " AND status='done'"
    if a.get("project"):
        q += " AND project_id=?"
        p.append(_project_id(ctx, a["project"]))
    if a.get("query"):
        q += " AND (title LIKE ? OR notes LIKE ?)"
        p += [f"%{a['query']}%"] * 2
    q += " ORDER BY CASE priority WHEN 'high' THEN 0 WHEN 'normal' THEN 1 ELSE 2 END, COALESCE(due_at,'9999') LIMIT 50"
    rows = db.all(q, p)
    return ToolResult(True, f"{len(rows)} مهمة", [_task_view(r, ctx.tz) for r in rows], verified=True)


# ═══════════════ التذكيرات
async def create_reminder(ctx: Ctx, a: dict) -> ToolResult:
    text = (a.get("text") or "").strip()
    if not text:
        raise ValueError("نص التذكير مطلوب")
    if a.get("in_minutes"):
        due = utcnow() + timedelta(minutes=int(a["in_minutes"]))
    elif a.get("when"):
        due = parse_local(a["when"], ctx.tz)
    else:
        raise ValueError("حدد وقت التذكير (when) أو بعد كم دقيقة (in_minutes)")
    if due < utcnow() - timedelta(seconds=30):
        return ToolResult(False, f"الوقت {to_local_str(iso(due), ctx.tz)} مضى بالفعل. اطلب من المستخدم وقتًا مستقبليًا.")
    recur = a.get("recur") or "none"
    if recur not in ("none", "daily", "weekdays", "weekly", "monthly"):
        recur = "none"
    rid = ctx.app.reminders.create(ctx.uid, text, iso(due), recur, a.get("task_id"))
    row = ctx.app.db.one("SELECT * FROM reminders WHERE id=? AND user_id=?", (rid, ctx.uid))
    job = ctx.app.db.one("SELECT id FROM jobs WHERE dedupe_key=?", (f"reminder:{rid}:{row['due_at']}",))
    ok = bool(row and job)
    return ToolResult(ok, "جُدول التذكير" if ok else "فشل حفظ التذكير", {
        "id": rid, "text": text, "when_local": to_local_str(row["due_at"], ctx.tz), "timezone": ctx.tz,
        "recur": recur}, verified=ok)


async def list_reminders(ctx: Ctx, a: dict) -> ToolResult:
    rows = ctx.app.db.all("SELECT * FROM reminders WHERE user_id=? AND status='pending' ORDER BY due_at LIMIT 50",
                          (ctx.uid,))
    return ToolResult(True, f"{len(rows)} تذكير", [
        {"id": r["id"], "text": r["text"], "when": to_local_str(r["due_at"], ctx.tz), "recur": r["recur"]} for r in rows
    ], verified=True)


async def cancel_reminder(ctx: Ctx, a: dict) -> ToolResult:
    rid = int(a["id"])
    ok = ctx.app.reminders.cancel(ctx.uid, rid)
    return ToolResult(ok, "أُلغي التذكير" if ok else f"لا يوجد تذكير نشط رقم {rid}", verified=ok)


# ═══════════════ الذاكرة
MEM_KINDS = ["fact", "preference", "project", "decision"]


async def remember(ctx: Ctx, a: dict) -> ToolResult:
    content = (a.get("content") or "").strip()
    if not content:
        raise ValueError("المحتوى مطلوب")
    if not ctx.app.db.get_setting(ctx.uid, "memory_enabled", True):
        return ToolResult(False, "الذاكرة طويلة المدى معطلة من الإعدادات.")
    if looks_secret(content):
        return ToolResult(False, "لا أحفظ كلمات المرور أو المفاتيح أو أرقام البطاقات في الذاكرة.")
    kind = a.get("kind") if a.get("kind") in MEM_KINDS else "fact"
    days = a.get("retention_days") or ctx.app.db.get_setting(ctx.uid, "memory_retention_days", 0)
    expires = iso(utcnow() + timedelta(days=int(days))) if days else None
    dup = ctx.app.db.one("SELECT id FROM memories WHERE user_id=? AND content=?", (ctx.uid, content))
    if dup:
        return ToolResult(True, f"المعلومة محفوظة مسبقًا (رقم {dup['id']})", {"id": dup["id"]}, verified=True)
    now = now_iso()
    mid = ctx.app.db.insert(
        "INSERT INTO memories(user_id,kind,content,source,created_at,updated_at,expires_at) VALUES(?,?,?,?,?,?,?)",
        (ctx.uid, kind, content, ctx.channel, now, now, expires))
    ok = bool(ctx.app.db.one("SELECT 1 FROM memories WHERE id=? AND user_id=?", (mid, ctx.uid)))
    return ToolResult(ok, f"حُفظ في الذاكرة برقم {mid}", {"id": mid, "kind": kind}, verified=ok)


def fts_query(text: str) -> str:
    words = re.findall(r"[\w؀-ۿ]{2,}", text or "")
    return " OR ".join(f'"{w}"' for w in words[:12])


def search_memories(db, uid: int, query: str, limit: int = 10) -> list[dict]:
    q = fts_query(query)
    now = now_iso()
    if q:
        rows = db.all(
            "SELECT m.id, m.kind, m.content, m.updated_at FROM memories_fts f JOIN memories m ON m.id=f.rowid "
            "WHERE memories_fts MATCH ? AND m.user_id=? AND (m.expires_at IS NULL OR m.expires_at>?) "
            "ORDER BY rank LIMIT ?", (q, uid, now, limit))
        if rows:
            return rows
    return db.all("SELECT id, kind, content, updated_at FROM memories WHERE user_id=? AND content LIKE ? "
                  "AND (expires_at IS NULL OR expires_at>?) ORDER BY id DESC LIMIT ?",
                  (uid, f"%{query}%", now, limit))


async def search_memory(ctx: Ctx, a: dict) -> ToolResult:
    rows = search_memories(ctx.app.db, ctx.uid, a.get("query") or "", 15)
    return ToolResult(True, f"{len(rows)} نتيجة", rows, verified=True)


async def update_memory(ctx: Ctx, a: dict) -> ToolResult:
    mid, content = int(a["id"]), (a.get("content") or "").strip()
    if looks_secret(content):
        return ToolResult(False, "لا أحفظ أسرارًا في الذاكرة.")
    ctx.app.db.execute("UPDATE memories SET content=?, updated_at=? WHERE id=? AND user_id=?",
                       (content, now_iso(), mid, ctx.uid))
    r = ctx.app.db.one("SELECT content FROM memories WHERE id=? AND user_id=?", (mid, ctx.uid))
    ok = bool(r and r["content"] == content)
    return ToolResult(ok, "عُدّلت المعلومة" if ok else f"لا توجد معلومة رقم {mid}", verified=ok)


async def forget_memory(ctx: Ctx, a: dict) -> ToolResult:
    mid = int(a["id"])
    ctx.app.db.execute("DELETE FROM memories WHERE id=? AND user_id=?", (mid, ctx.uid))
    gone = ctx.app.db.one("SELECT 1 FROM memories WHERE id=? AND user_id=?", (mid, ctx.uid)) is None
    return ToolResult(gone, f"حُذفت المعلومة {mid} نهائيًا" if gone else "لم تُحذف", verified=gone)


async def search_history(ctx: Ctx, a: dict) -> ToolResult:
    q = fts_query(a.get("query") or "")
    if not q:
        return ToolResult(False, "اكتب كلمات للبحث")
    rows = ctx.app.db.all(
        "SELECT m.id, m.role, m.channel, substr(m.content,1,300) AS content, m.created_at FROM messages_fts f "
        "JOIN messages m ON m.id=f.rowid WHERE messages_fts MATCH ? AND m.user_id=? ORDER BY m.id DESC LIMIT 15",
        (q, ctx.uid))
    for r in rows:
        r["created_at"] = to_local_str(r["created_at"], ctx.tz)
    return ToolResult(True, f"{len(rows)} رسالة", rows, verified=True)


# ═══════════════ المشاريع
ENTRY_KINDS = ["decision", "risk", "blocker", "note", "meeting", "contract", "deadline", "assignment", "lead"]


async def create_project(ctx: Ctx, a: dict) -> ToolResult:
    name = (a.get("name") or "").strip()
    if not name:
        raise ValueError("اسم المشروع مطلوب")
    from .business import find_ws, parse_amount
    ws = find_ws(ctx.app.db, ctx.uid, a["workspace"]) if a.get("workspace") else None
    budget = parse_amount(a["budget"]) if a.get("budget") not in (None, "") else None
    now = now_iso()
    pid = ctx.app.db.insert(
        "INSERT INTO projects(user_id,name,goal,description,workspace_id,budget_minor,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (ctx.uid, name, a.get("goal") or "", a.get("description") or "", ws["id"] if ws else None, budget, now, now))
    ok = bool(ctx.app.db.one("SELECT 1 FROM projects WHERE id=? AND user_id=?", (pid, ctx.uid)))
    return ToolResult(ok, f"أُنشئ المشروع رقم {pid}", {"id": pid, "name": name}, verified=ok)


async def list_projects(ctx: Ctx, a: dict) -> ToolResult:
    rows = ctx.app.db.all(
        "SELECT p.id, p.name, p.goal, p.status, p.workspace_id, p.budget_minor, "
        "(SELECT name FROM workspaces w WHERE w.id=p.workspace_id) AS workspace, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id) AS tasks_total, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id AND t.status='done') AS tasks_done "
        "FROM projects p WHERE p.user_id=? AND p.status!='archived' ORDER BY p.updated_at DESC", (ctx.uid,))
    return ToolResult(True, f"{len(rows)} مشروع", rows, verified=True)


async def add_project_entry(ctx: Ctx, a: dict) -> ToolResult:
    pid = _project_id(ctx, a.get("project"))
    if not pid:
        raise ValueError("حدد المشروع")
    kind = a.get("kind") if a.get("kind") in ENTRY_KINDS else "note"
    due = iso(parse_local(a["due"], ctx.tz)) if a.get("due") else None
    eid = ctx.app.db.insert(
        "INSERT INTO project_entries(project_id,user_id,kind,content,owner,due_at,created_at) VALUES(?,?,?,?,?,?,?)",
        (pid, ctx.uid, kind, a.get("content") or "", a.get("owner") or "", due, now_iso()))
    ctx.app.db.execute("UPDATE projects SET updated_at=? WHERE id=?", (now_iso(), pid))
    ok = bool(ctx.app.db.one("SELECT 1 FROM project_entries WHERE id=?", (eid,)))
    return ToolResult(ok, f"سُجّل ({kind}) في المشروع", {"id": eid}, verified=ok)


def project_report_data(db, uid: int, pid: int, tzname: str) -> dict:
    p = db.one("SELECT * FROM projects WHERE id=? AND user_id=?", (pid, uid))
    if not p:
        raise ValueError("المشروع غير موجود")
    tasks = db.all("SELECT * FROM tasks WHERE project_id=? AND user_id=?", (pid, uid))
    done = [t for t in tasks if t["status"] == "done"]
    now = now_iso()
    overdue = [t for t in tasks if t["status"] in ("open", "in_progress") and t["due_at"] and t["due_at"] < now]
    entries = db.all("SELECT kind, content, owner, due_at, status, created_at FROM project_entries "
                     "WHERE project_id=? ORDER BY id DESC LIMIT 60", (pid,))
    for e in entries:
        e["due_at"] = to_local_str(e["due_at"], tzname) if e["due_at"] else ""
        e["created_at"] = to_local_str(e["created_at"], tzname)
    return {
        "project": {"id": p["id"], "name": p["name"], "goal": p["goal"], "status": p["status"]},
        "progress_percent": round(100 * len(done) / len(tasks)) if tasks else 0,
        "tasks_total": len(tasks), "tasks_done": len(done),
        "overdue": [_task_view(t, tzname) for t in overdue],
        "open_tasks": [_task_view(t, tzname) for t in tasks if t["status"] in ("open", "in_progress")][:30],
        "entries": entries,
    }


async def project_report(ctx: Ctx, a: dict) -> ToolResult:
    pid = _project_id(ctx, a.get("project"))
    data = project_report_data(ctx.app.db, ctx.uid, pid, ctx.tz)
    return ToolResult(True, "بيانات التقرير (استخدم الأرقام كما هي دون تقدير)", data, verified=True)


# ═══════════════ العادات والتركيز
async def add_habit(ctx: Ctx, a: dict) -> ToolResult:
    name = (a.get("name") or "").strip()
    hid = ctx.app.db.insert("INSERT INTO habits(user_id,name,target_per_week,created_at) VALUES(?,?,?,?)",
                            (ctx.uid, name, int(a.get("target_per_week") or 7), now_iso()))
    return ToolResult(True, f"أُضيفت العادة رقم {hid}", {"id": hid}, verified=True)


def _habit(ctx: Ctx, ref) -> dict:
    db = ctx.app.db
    if str(ref).isdigit():
        h = db.one("SELECT * FROM habits WHERE id=? AND user_id=?", (int(ref), ctx.uid))
    else:
        h = db.one("SELECT * FROM habits WHERE user_id=? AND name LIKE ? AND archived=0", (ctx.uid, f"%{ref}%"))
    if not h:
        raise ValueError(f"لا توجد عادة: {ref}")
    return h


async def check_habit(ctx: Ctx, a: dict) -> ToolResult:
    h = _habit(ctx, a.get("habit"))
    day = a.get("day") or local_now(ctx.tz).strftime("%Y-%m-%d")
    ctx.app.db.execute("INSERT OR IGNORE INTO habit_checks(habit_id, day) VALUES(?,?)", (h["id"], day))
    ok = bool(ctx.app.db.one("SELECT 1 FROM habit_checks WHERE habit_id=? AND day=?", (h["id"], day)))
    return ToolResult(ok, f"سُجّل إنجاز «{h['name']}» ليوم {day}", verified=ok)


def habits_summary(db, uid: int, tzname: str) -> list[dict]:
    today = local_now(tzname).date()
    week_start = (today - timedelta(days=6)).isoformat()
    out = []
    for h in db.all("SELECT * FROM habits WHERE user_id=? AND archived=0", (uid,)):
        days = {r["day"] for r in db.all("SELECT day FROM habit_checks WHERE habit_id=?", (h["id"],))}
        streak, d = 0, today
        while d.isoformat() in days:
            streak += 1
            d -= timedelta(days=1)
        week = len([x for x in days if x >= week_start])
        out.append({"id": h["id"], "name": h["name"], "done_today": today.isoformat() in days,
                    "this_week": week, "target_per_week": h["target_per_week"], "streak": streak})
    return out


async def habit_report(ctx: Ctx, a: dict) -> ToolResult:
    return ToolResult(True, "ملخص العادات", habits_summary(ctx.app.db, ctx.uid, ctx.tz), verified=True)


async def start_focus(ctx: Ctx, a: dict) -> ToolResult:
    minutes = max(1, min(int(a.get("minutes") or 25), 180))
    label = a.get("label") or "جلسة تركيز"
    run_at = utcnow() + timedelta(minutes=minutes)
    jid = ctx.app.scheduler.enqueue(ctx.uid, "notify", {"text": f"⏰ انتهت {label} ({minutes} دقيقة). خذ استراحة قصيرة."},
                                    run_at)
    ok = bool(ctx.app.db.one("SELECT 1 FROM jobs WHERE id=? AND status='pending'", (jid,)))
    return ToolResult(ok, f"بدأت {label} لمدة {minutes} دقيقة، وسأنبهك عند انتهائها",
                      {"ends_at": to_local_str(iso(run_at), ctx.tz)}, verified=ok)


# ═══════════════ نظرة على اليوم (للتخطيط والملخص)
def day_overview(db, uid: int, tzname: str) -> dict:
    start, end = local_day_bounds(tzname)
    now = now_iso()
    open_q = "SELECT * FROM tasks WHERE user_id=? AND status IN ('open','in_progress')"
    overdue = db.all(open_q + " AND due_at IS NOT NULL AND due_at<? ORDER BY due_at", (uid, now))
    today = db.all(open_q + " AND due_at>=? AND due_at<? ORDER BY due_at", (uid, now, end))
    high = db.all(open_q + " AND priority='high' AND due_at IS NULL LIMIT 10", (uid,))
    rems = db.all("SELECT * FROM reminders WHERE user_id=? AND status='pending' AND due_at>=? AND due_at<? "
                  "ORDER BY due_at", (uid, start, end))
    done_today = db.all("SELECT title FROM tasks WHERE user_id=? AND status='done' AND completed_at>=?", (uid, start))
    deadlines = db.all("SELECT e.content, e.kind, e.due_at, p.name AS project FROM project_entries e "
                       "JOIN projects p ON p.id=e.project_id WHERE e.user_id=? AND e.status='open' "
                       "AND e.due_at IS NOT NULL AND e.due_at<? ORDER BY e.due_at LIMIT 10",
                       (uid, iso(utcnow() + timedelta(days=7))))
    for d in deadlines:
        d["due_at"] = to_local_str(d["due_at"], tzname)
    return {
        "now_local": local_now(tzname).strftime("%A %Y-%m-%d %H:%M"),
        "overdue": [_task_view(t, tzname) for t in overdue],
        "due_today": [_task_view(t, tzname) for t in today],
        "high_priority_undated": [_task_view(t, tzname) for t in high],
        "reminders_today": [{"text": r["text"], "when": to_local_str(r["due_at"], tzname, False)} for r in rems],
        "completed_today": [t["title"] for t in done_today],
        "deadlines_7_days": deadlines,
        "habits": habits_summary(db, uid, tzname),
    }


async def get_day_overview(ctx: Ctx, a: dict) -> ToolResult:
    return ToolResult(True, "بيانات اليوم الفعلية", day_overview(ctx.app.db, ctx.uid, ctx.tz), verified=True)


# ═══════════════ الويب
class _TextExtractor(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "head", "nav", "footer", "form"}

    def __init__(self):
        super().__init__()
        self.out: list[str] = []
        self.skip = 0
        self.title = ""
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        if tag == "title":
            self._in_title = True
        if tag in ("p", "br", "div", "li", "h1", "h2", "h3", "h4", "tr", "section", "article"):
            self.out.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        elif not self.skip:
            self.out.append(data)

    def text(self) -> str:
        t = "".join(self.out)
        t = re.sub(r"[ \t\r\f\v]+", " ", t)
        return re.sub(r"\n\s*\n+", "\n\n", t).strip()


def _check_public_url(url: str) -> str:
    """يتحقق أن الرابط عام ويعيد عنوان IP المسموح (نتصل به مباشرة لمنع إعادة الربط عبر DNS)."""
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        raise ValueError("الرابط يجب أن يبدأ بـ http أو https")
    try:
        infos = socket.getaddrinfo(u.hostname, u.port or (443 if u.scheme == "https" else 80), proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        raise ValueError("تعذّر الوصول إلى اسم النطاق")
    ips = []
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified:
            raise ValueError("لا يُسمح بفتح عناوين الشبكة الداخلية")
        ips.append(ip)
    if not ips:
        raise ValueError("تعذّر الوصول إلى اسم النطاق")
    return str(ips[0])


MAX_PAGE_BYTES = 2_000_000


async def _fetch_pinned(http: httpx.AsyncClient, url: str) -> tuple[int, dict, bytes]:
    """يتصل بعنوان IP الذي تم التحقق منه مع الحفاظ على اسم النطاق (Host و SNI)، ويقرأ بحد أقصى للحجم."""
    ip = await asyncio.to_thread(_check_public_url, url)
    u = urlparse(url)
    host_ip = f"[{ip}]" if ":" in ip else ip
    netloc = host_ip + (f":{u.port}" if u.port else "")
    pinned = u._replace(netloc=netloc).geturl()
    headers = {"User-Agent": "Mozilla/5.0 (Rafiq assistant)", "Host": u.netloc.split("@")[-1]}
    ext = {"sni_hostname": u.hostname} if u.scheme == "https" else {}
    req = http.build_request("GET", pinned, headers=headers, timeout=25, extensions=ext)
    resp = await http.send(req, stream=True, follow_redirects=False)
    try:
        chunks, size = [], 0
        async for chunk in resp.aiter_bytes():
            size += len(chunk)
            if size > MAX_PAGE_BYTES:
                break
            chunks.append(chunk)
        return resp.status_code, dict(resp.headers), b"".join(chunks)
    finally:
        await resp.aclose()


async def fetch_url(ctx: Ctx, a: dict) -> ToolResult:
    url = a.get("url") or ""
    http = ctx.app.ai.http
    for _ in range(5):
        status, headers, raw = await _fetch_pinned(http, url)
        if status in (301, 302, 303, 307, 308) and headers.get("location"):
            url = urljoin(url, headers["location"])
            continue
        break
    else:
        return ToolResult(False, "تحويلات كثيرة")
    if status >= 400:
        return ToolResult(False, f"الصفحة أعادت الخطأ {status}")
    ctype = headers.get("content-type", "")
    charset = (re.search(r"charset=([\w-]+)", ctype) or [None, "utf-8"])[1]
    try:
        text = raw.decode(charset, errors="replace")
    except LookupError:
        text = raw.decode("utf-8", errors="replace")
    if "html" in ctype:
        ex = _TextExtractor()
        ex.feed(text)
        title, body = ex.title.strip(), ex.text()
    elif "text" in ctype or "json" in ctype:
        title, body = "", text
    else:
        return ToolResult(False, f"نوع المحتوى غير مدعوم للقراءة: {ctype}")
    return ToolResult(True, "قُرئت الصفحة", {"url": url, "title": title,
                                            "content": wrap_untrusted(url, body, 10000)}, verified=True)


async def web_search(ctx: Ctx, a: dict) -> ToolResult:
    q = (a.get("query") or "").strip()
    if not q:
        raise ValueError("اكتب عبارة البحث")
    st, http = ctx.app.s, ctx.app.ai.http
    results: list[dict] = []
    summary = ""
    if st.tavily_api_key:
        r = await http.post("https://api.tavily.com/search", json={"api_key": st.tavily_api_key, "query": q,
                                                                    "max_results": 6, "include_answer": True})
        if r.status_code < 400:
            d = r.json()
            summary = d.get("answer") or ""
            results = [{"title": x.get("title"), "url": x.get("url"), "snippet": (x.get("content") or "")[:500]}
                       for x in d.get("results", [])]
    elif st.brave_api_key:
        r = await http.get("https://api.search.brave.com/res/v1/web/search", params={"q": q, "count": 6},
                           headers={"X-Subscription-Token": st.brave_api_key, "Accept": "application/json"})
        if r.status_code < 400:
            results = [{"title": x.get("title"), "url": x.get("url"), "snippet": x.get("description", "")}
                       for x in (r.json().get("web") or {}).get("results", [])]
    elif st.gemini_api_key:
        body = {"contents": [{"role": "user", "parts": [{"text": q}]}], "tools": [{"google_search": {}}]}
        r = await http.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
                            json=body, headers={"x-goog-api-key": st.gemini_api_key})
        if r.status_code < 400:
            c = (r.json().get("candidates") or [{}])[0]
            summary = "".join(p.get("text", "") for p in (c.get("content") or {}).get("parts", []))
            for ch in (c.get("groundingMetadata") or {}).get("groundingChunks", []):
                w = ch.get("web") or {}
                if w.get("uri"):
                    results.append({"title": w.get("title"), "url": w.get("uri"), "snippet": ""})
    else:
        return ToolResult(False, "البحث يحتاج مفتاح Tavily أو Brave أو Gemini في ملف .env")
    if not results and not summary:
        return ToolResult(False, "لم يُرجع البحث نتائج (أو فشل الاتصال بخدمة البحث)")
    return ToolResult(True, "نتائج البحث (اذكر الروابط كمصادر وميّز المؤكد من الاستنتاج)", {
        "summary": wrap_untrusted("محرك البحث", summary, 4000) if summary else "",
        "results": results[:8]}, verified=True)


# ═══════════════ الملفات (داخل مجلد المستخدم فقط)
TEXT_EXT = {".txt", ".md", ".csv", ".json", ".log", ".html", ".xml", ".py", ".js", ".ts", ".kt", ".yaml", ".yml"}


def user_dir(ctx_app, uid: int) -> Path:
    d = ctx_app.s.files_dir / f"u{uid}"
    d.mkdir(parents=True, exist_ok=True)
    return d


def safe_name(name: str) -> str:
    name = Path(name or "file").name
    name = re.sub(r"[^\w؀-ۿ.\- ]", "_", name).strip(" .") or "file"
    return name[:120]


def save_user_file(app, uid: int, name: str, data: bytes, project_id: int | None = None) -> int:
    if len(data) > 20 * 1024 * 1024:
        raise ValueError("حجم الملف أكبر من 20 ميجابايت")
    d = user_dir(app, uid)
    name = safe_name(name)
    target = d / name
    n = 1
    while target.exists():
        target = d / f"{Path(name).stem}-{n}{Path(name).suffix}"
        n += 1
    target.write_bytes(data)
    mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
    return app.db.insert(
        "INSERT INTO files(user_id,project_id,name,rel_path,size,mime,created_at) VALUES(?,?,?,?,?,?,?)",
        (uid, project_id, target.name, str(target.relative_to(app.s.files_dir)), len(data), mime, now_iso()))


def file_path(app, uid: int, f: dict) -> Path:
    p = (app.s.files_dir / f["rel_path"]).resolve()
    if not str(p).startswith(str((app.s.files_dir / f"u{uid}").resolve())):
        raise ValueError("مسار غير مسموح")
    return p


def extract_text(path: Path) -> str:
    ext = path.suffix.lower()
    if ext in TEXT_EXT:
        return path.read_text(encoding="utf-8", errors="replace")
    if ext == ".pdf":
        try:
            from pypdf import PdfReader
        except ImportError:
            raise ValueError("قراءة PDF تحتاج تثبيت pypdf")
        reader = PdfReader(str(path))
        return "\n".join((pg.extract_text() or "") for pg in reader.pages[:60])
    if ext == ".docx":
        with zipfile.ZipFile(path) as z:
            xml = z.read("word/document.xml").decode("utf-8", "replace")
        xml = re.sub(r"</w:p>", "\n", xml)
        return re.sub(r"<[^>]+>", "", xml)
    if ext == ".xlsx":
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if n.startswith("xl/sharedStrings")]
            xml = z.read(names[0]).decode("utf-8", "replace") if names else ""
        return re.sub(r"<[^>]+>", " ", xml)
    raise ValueError(f"نوع الملف {ext} غير مدعوم للقراءة")


def _find_file(ctx: Ctx, ref) -> dict:
    db = ctx.app.db
    if str(ref).isdigit():
        f = db.one("SELECT * FROM files WHERE id=? AND user_id=?", (int(ref), ctx.uid))
    else:
        f = db.one("SELECT * FROM files WHERE user_id=? AND name LIKE ? ORDER BY id DESC", (ctx.uid, f"%{ref}%"))
    if not f:
        raise ValueError(f"لا يوجد ملف: {ref}")
    return f


async def list_files(ctx: Ctx, a: dict) -> ToolResult:
    rows = ctx.app.db.all("SELECT id, name, size, mime, project_id, created_at FROM files WHERE user_id=? "
                          "ORDER BY id DESC LIMIT 100", (ctx.uid,))
    return ToolResult(True, f"{len(rows)} ملف", rows, verified=True)


async def read_file(ctx: Ctx, a: dict) -> ToolResult:
    f = _find_file(ctx, a.get("file"))
    text = await asyncio.to_thread(extract_text, file_path(ctx.app, ctx.uid, f))
    start = int(a.get("offset") or 0)
    chunk = text[start:start + 15000]
    return ToolResult(True, f"محتوى {f['name']} ({len(text)} حرفًا)", {
        "file": f["name"], "offset": start, "total_chars": len(text),
        "content": wrap_untrusted(f"الملف {f['name']}", chunk, 15000)}, verified=True)


async def search_files(ctx: Ctx, a: dict) -> ToolResult:
    q = (a.get("query") or "").strip().lower()
    hits = []
    for f in ctx.app.db.all("SELECT * FROM files WHERE user_id=?", (ctx.uid,)):
        try:
            text = await asyncio.to_thread(extract_text, file_path(ctx.app, ctx.uid, f))
        except Exception:
            continue
        idx = text.lower().find(q)
        if idx >= 0 or q in f["name"].lower():
            snippet = text[max(0, idx - 150): idx + 250] if idx >= 0 else ""
            hits.append({"id": f["id"], "name": f["name"], "snippet": snippet})
        if len(hits) >= 15:
            break
    return ToolResult(True, f"{len(hits)} ملف مطابق", hits, verified=True)


async def write_file(ctx: Ctx, a: dict) -> ToolResult:
    name = safe_name(a.get("name") or "report.md")
    if Path(name).suffix.lower() not in (".md", ".txt", ".csv", ".json", ".html"):
        name += ".md"
    content = a.get("content") or ""
    if a.get("rows"):  # جدول
        buf = io.StringIO()
        csv.writer(buf).writerows(a["rows"])
        content = buf.getvalue()
        if not name.endswith(".csv"):
            name = Path(name).stem + ".csv"
    pid = _project_id(ctx, a.get("project")) if a.get("project") else None
    fid = save_user_file(ctx.app, ctx.uid, name, content.encode("utf-8"), pid)
    f = ctx.app.db.one("SELECT * FROM files WHERE id=?", (fid,))
    ok = file_path(ctx.app, ctx.uid, f).exists()
    return ToolResult(ok, f"أُنشئ الملف {f['name']}", {"id": fid, "name": f["name"], "size": f["size"]}, verified=ok)


async def delete_file(ctx: Ctx, a: dict) -> ToolResult:  # مستوى 3: لا يُنفّذ تلقائيًا
    return ToolResult(False, "الحذف النهائي يتم من لوحة التحكم فقط")


# ═══════════════ البريد
def _email_summary(a: dict) -> str:
    return f"إرسال بريد إلى {a.get('to')}\nالموضوع: {a.get('subject')}\n\n{(a.get('body') or '')[:600]}"


async def draft_email(ctx: Ctx, a: dict) -> ToolResult:
    body = f"إلى: {a.get('to', '')}\nالموضوع: {a.get('subject', '')}\n\n{a.get('body', '')}"
    fid = save_user_file(ctx.app, ctx.uid, safe_name(f"مسودة-{a.get('subject') or 'بريد'}.txt"), body.encode())
    return ToolResult(True, "حُفظت المسودة في الملفات ولم تُرسل", {"file_id": fid}, verified=True)


def _send_smtp(st, to: str, subject: str, body: str) -> dict:
    msg = EmailMessage()
    msg["From"] = st.smtp_from or st.smtp_user
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    with smtplib.SMTP(st.smtp_host, st.smtp_port, timeout=30) as smtp:
        smtp.ehlo()
        if st.smtp_port != 25:
            smtp.starttls()
        if st.smtp_user:
            smtp.login(st.smtp_user, st.smtp_password)
        return smtp.send_message(msg)


async def send_email(ctx: Ctx, a: dict) -> ToolResult:
    st = ctx.app.s
    if not st.smtp_host:
        return ToolResult(False, "البريد غير مُعدّ: أضف إعدادات SMTP في ملف .env")
    to = (a.get("to") or "").strip()
    if not re.fullmatch(r"[^@\s,]+@[^@\s,]+\.[^@\s,]+", to):
        raise ValueError("عنوان البريد غير صحيح")
    refused = await asyncio.to_thread(_send_smtp, st, to, a.get("subject") or "", a.get("body") or "")
    if refused:
        return ToolResult(False, f"رفض الخادم المستلم: {refused}")
    return ToolResult(True, f"قبل خادم البريد الرسالة إلى {to}", verified=True)


# ═══════════════ التسجيل
def build_registry() -> Registry:
    r = Registry()
    T = lambda *a, **k: r.add(Tool(*a, **k))  # noqa: E731
    when = s("وقت محلي بصيغة YYYY-MM-DDTHH:MM بتوقيت المستخدم")

    T("add_task", "إضافة مهمة. استخدم parent_id لتقسيم هدف كبير إلى خطوات.", obj({
        "title": s("عنوان المهمة"), "notes": s("تفاصيل"), "priority": s("الأولوية", PRIORITIES),
        "due": when, "scope": s("نطاق القائمة", ["day", "week", "month", ""]),
        "project": s("اسم المشروع أو رقمه"), "parent_id": i("رقم المهمة الأم")}, ["title"]), add_task, category="المهام")
    T("update_task", "تعديل مهمة (العنوان، الأولوية، الموعد، الحالة، المشروع). due فارغ يلغي الموعد.", obj({
        "id": i("رقم المهمة"), "title": s(""), "notes": s(""), "priority": s("", PRIORITIES), "due": when,
        "status": s("", ["open", "in_progress", "done", "cancelled"]), "project": s(""), "scope": s("")}, ["id"]),
      update_task, category="المهام")
    T("complete_task", "تعليم مهمة كمكتملة.", obj({"id": i("رقم المهمة")}, ["id"]), complete_task, category="المهام")
    T("list_tasks", "عرض المهام.", obj({"filter": s("", ["open", "today", "overdue", "week", "done", "all"]),
                                        "project": s(""), "query": s("بحث في العنوان")}), list_tasks, category="المهام")
    T("create_reminder", "إنشاء تذكير حقيقي يُرسل للمستخدم في وقته. تحقق من التاريخ والوقت والمنطقة الزمنية.", obj({
        "text": s("نص التذكير"), "when": when, "in_minutes": i("بعد كم دقيقة (بديل عن when)"),
        "recur": s("التكرار", ["none", "daily", "weekdays", "weekly", "monthly"]), "task_id": i("مهمة مرتبطة")},
        ["text"]), create_reminder, category="التذكيرات")
    T("list_reminders", "عرض التذكيرات القادمة.", obj(), list_reminders, category="التذكيرات")
    T("cancel_reminder", "إلغاء تذكير.", obj({"id": i("رقم التذكير")}, ["id"]), cancel_reminder, category="التذكيرات")
    T("remember", "حفظ معلومة في الذاكرة طويلة المدى. فقط إذا طلب المستخدم الحفظ أو وافق صراحة. لا أسرار.", obj({
        "content": s("المعلومة بصياغة واضحة"), "kind": s("النوع", MEM_KINDS),
        "retention_days": i("مدة الاحتفاظ بالأيام (0 = دائم)")}, ["content"]), remember, category="الذاكرة")
    T("search_memory", "البحث في الذاكرة المحفوظة.", obj({"query": s("")}, ["query"]), search_memory, category="الذاكرة")
    T("update_memory", "تصحيح معلومة محفوظة.", obj({"id": i(""), "content": s("")}, ["id", "content"]),
      update_memory, category="الذاكرة")
    T("forget_memory", "حذف معلومة من الذاكرة بطلب المستخدم.", obj({"id": i("")}, ["id"]), forget_memory,
      category="الذاكرة")
    T("search_history", "البحث في المحادثات السابقة.", obj({"query": s("")}, ["query"]), search_history,
      category="الذاكرة")
    T("create_project", "إنشاء مشروع (اختياريًا تابع لشركة وبميزانية). لخطة مشروع متكاملة: أنشئه ثم قسّمه لمهام بمراحل ومسؤولين ومواعيد، "
      "وسجّل المخاطر ومؤشرات النجاح.", obj({"name": s(""), "goal": s(""), "description": s("النطاق"),
      "workspace": s("الشركة/المساحة"), "budget": s("الميزانية بعملة الجهة")}, ["name"]),
      create_project, category="المشاريع")
    T("list_projects", "عرض المشاريع ونسب الإنجاز.", obj(), list_projects, category="المشاريع")
    T("add_project_entry", "تسجيل قرار أو خطر أو عائق أو محضر اجتماع أو عقد أو موعد نهائي أو تكليف أو فرصة عمل.",
      obj({"project": s("اسم المشروع أو رقمه"), "kind": s("", ENTRY_KINDS), "content": s(""),
           "owner": s("المسؤول"), "due": when}, ["project", "kind", "content"]), add_project_entry, category="المشاريع")
    T("project_report", "بيانات تقرير مشروع (نسبة الإنجاز، المتأخر، القرارات، المخاطر).", obj({"project": s("")},
      ["project"]), project_report, category="المشاريع")
    T("add_habit", "إضافة عادة لمتابعتها.", obj({"name": s(""), "target_per_week": i("")}, ["name"]), add_habit,
      category="العادات")
    T("check_habit", "تسجيل إنجاز عادة اليوم.", obj({"habit": s("اسم العادة أو رقمها"), "day": s("YYYY-MM-DD")},
      ["habit"]), check_habit, category="العادات")
    T("habit_report", "ملخص العادات والسلاسل.", obj(), habit_report, category="العادات")
    T("start_focus", "بدء جلسة تركيز (بومودورو) مع تنبيه عند الانتهاء.", obj({"minutes": i("الافتراضي 25"),
      "label": s("")}), start_focus, category="العادات")
    T("get_day_overview", "بيانات اليوم الفعلية للتخطيط أو الملخص: المتأخر، مهام اليوم، التذكيرات، العادات.", obj(),
      get_day_overview, category="التخطيط")
    T("web_search", "بحث في الإنترنت مع مصادر.", obj({"query": s("")}, ["query"]), web_search, category="الويب")
    T("fetch_url", "فتح رابط وقراءة نص الصفحة.", obj({"url": s("")}, ["url"]), fetch_url, category="الويب")
    T("list_files", "عرض ملفات المستخدم.", obj(), list_files, category="الملفات")
    T("read_file", "قراءة ملف (txt, md, csv, pdf, docx…).", obj({"file": s("اسم الملف أو رقمه"),
      "offset": i("للصفحات التالية")}, ["file"]), read_file, category="الملفات")
    T("search_files", "البحث داخل الملفات.", obj({"query": s("")}, ["query"]), search_files, category="الملفات")
    T("write_file", "إنشاء تقرير أو مستند (md/txt) أو جدول (rows ⇒ csv) في ملفات المستخدم.", obj({
        "name": s("اسم الملف"), "content": s("المحتوى"),
        "rows": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}, "description": "صفوف جدول"},
        "project": s("")}, ["name"]), write_file, category="الملفات")
    T("delete_file", "حذف ملف نهائيًا (محظور على المساعد).", obj({"file": s("")}, ["file"]), delete_file,
      level=FORBIDDEN, category="الملفات")
    T("draft_email", "إعداد مسودة بريد دون إرسال.", obj({"to": s(""), "subject": s(""), "body": s("")},
      ["subject", "body"]), draft_email, category="البريد")
    T("send_email", "إرسال بريد. يتطلب موافقة المستخدم قبل التنفيذ.", obj({"to": s(""), "subject": s(""),
      "body": s("")}, ["to", "subject", "body"]), send_email, level=NEEDS_APPROVAL, summarize=_email_summary,
      category="البريد")
    from .business import register_business_tools
    register_business_tools(r)
    return r


__all__ = ["build_registry", "day_overview", "search_memories", "project_report_data", "habits_summary",
           "save_user_file", "file_path", "extract_text", "LOW"]
