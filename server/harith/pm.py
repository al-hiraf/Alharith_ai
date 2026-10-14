"""إدارة المشاريع المتكاملة: لوحة كانبان، مراحل (معالم)، جدول زمني، مسؤولون وعبء العمل، تقدير ووقت فعلي،
اعتماديات بين المهام، وسوم، قوالب مشاريع جاهزة، وصحة المشروع (أخضر/أصفر/أحمر مع الأسباب)."""
from __future__ import annotations

from datetime import date, timedelta

from .db import iso, now_iso
from .timeutil import local_now, parse_local, to_local_str
from .toolkit import Ctx, Registry, Tool, ToolResult, i, obj, s

COLUMNS = ["todo", "doing", "waiting", "done"]
COLUMN_AR = {"todo": "للتنفيذ", "doing": "قيد التنفيذ", "waiting": "بانتظار", "done": "مكتملة"}


def column_of(t: dict) -> str:
    if t["status"] in ("done", "cancelled"):
        return "done"
    if t.get("board") == "waiting":
        return "waiting"
    if t["status"] == "in_progress":
        return "doing"
    return "todo"


def task_extras_view(db, t: dict, tzname: str) -> dict:
    blocked_by = None
    if db is not None and t.get("depends_on"):
        d = db.one("SELECT id, title, status FROM tasks WHERE id=?", (t["depends_on"],))
        if d and d["status"] not in ("done", "cancelled"):
            blocked_by = {"id": d["id"], "title": d["title"]}
    return {"start": to_local_str(t["start_at"], tzname, False) if t.get("start_at") else "",
            "assignee": t.get("assignee") or "", "assignee_user_id": t.get("assignee_user_id"), "tags": [x for x in (t.get("tags") or "").split(",") if x],
            "estimate_min": t.get("estimate_min"), "spent_min": t.get("spent_min") or 0,
            "milestone_id": t.get("milestone_id"), "depends_on": t.get("depends_on"), "blocked_by": blocked_by,
            "column": column_of(t), "column_ar": COLUMN_AR[column_of(t)]}


def _minutes(a: dict, key_min: str, key_hours: str) -> int | None:
    if a.get(key_min) not in (None, ""):
        return max(0, int(float(a[key_min])))
    if a.get(key_hours) not in (None, ""):
        return max(0, int(round(float(a[key_hours]) * 60)))
    return None


def _milestone_id(db, uid: int, project_id: int | None, ref) -> int | None:
    if ref in (None, "", 0):
        return None
    r = str(ref).strip()
    q = "SELECT id, project_id FROM milestones WHERE user_id=? AND "
    m = db.one(q + "id=?", (uid, int(r))) if r.isdigit() else db.one(
        q + "title LIKE ?" + (" AND project_id=?" if project_id else "") + " ORDER BY id DESC",
        (uid, f"%{r}%", project_id) if project_id else (uid, f"%{r}%"))
    if not m:
        raise ValueError(f"لا توجد مرحلة: {ref}")
    if project_id and m["project_id"] != project_id:
        raise ValueError("المرحلة تتبع مشروعًا آخر")
    return int(m["id"])


def _creates_cycle(db, tid: int, dep: int) -> bool:
    seen, cur = set(), dep
    while cur and cur not in seen:
        if cur == tid:
            return True
        seen.add(cur)
        r = db.one("SELECT depends_on FROM tasks WHERE id=?", (cur,))
        cur = r["depends_on"] if r else None
    return False


def apply_task_extras(ctx: Ctx, tid: int, a: dict) -> list[str]:
    """يطبّق حقول إدارة المشاريع على مهمة. يعيد قائمة الحقول التي تغيّرت."""
    db, uid = ctx.app.db, ctx.uid
    t = db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", (tid, uid))
    if not t:
        raise ValueError("المهمة غير موجودة")
    f: dict = {}
    if a.get("start") is not None:
        f["start_at"] = iso(parse_local(a["start"], ctx.tz)) if a["start"] else None
    if a.get("assignee") is not None:
        f["assignee"] = str(a["assignee"]).strip()[:80]
    if a.get("tags") is not None:
        tags = a["tags"] if isinstance(a["tags"], list) else str(a["tags"]).replace("،", ",").split(",")
        f["tags"] = ",".join(dict.fromkeys(x.strip()[:30] for x in tags if str(x).strip()))[:300]
    est = _minutes(a, "estimate_min", "estimate_hours")
    if est is not None:
        f["estimate_min"] = est
    if a.get("milestone") is not None:
        f["milestone_id"] = _milestone_id(db, uid, t["project_id"], a["milestone"])
    if a.get("depends_on") is not None:
        dep = int(a["depends_on"]) if str(a["depends_on"]).strip() not in ("", "0") else None
        if dep:
            if dep == tid or not db.one("SELECT 1 FROM tasks WHERE id=? AND user_id=?", (dep, uid)):
                raise ValueError("المهمة السابقة غير صحيحة")
            if _creates_cycle(db, tid, dep):
                raise ValueError("هذا الربط يصنع حلقة اعتماد (كل مهمة تنتظر الأخرى)")
        f["depends_on"] = dep
    if a.get("column") in COLUMNS:
        c = a["column"]
        f["board"] = "waiting" if c == "waiting" else ""
        f["status"] = {"todo": "open", "doing": "in_progress", "waiting": "open", "done": "done"}[c]
        f["completed_at"] = now_iso() if c == "done" else None
    if a.get("sort") is not None:
        f["sort_order"] = float(a["sort"])
    # المسؤول كحساب حقيقي: إن كان عضوًا في فريق المشروع تصله المهمة في تطبيقه
    from .team import actor_of, notify_later, resolve_assignee
    from .team import log as team_log
    a_changed, a_uid, a_name = resolve_assignee(db, t["project_id"], a)
    if a_changed:
        f["assignee_user_id"] = a_uid
        if a_name is not None:
            f["assignee"] = a_name[:80]
    if not f:
        return []
    f["updated_at"] = now_iso()
    db.execute(f"UPDATE tasks SET {', '.join(k + '=?' for k in f)} WHERE id=? AND user_id=?", (*f.values(), tid, uid))
    if t["project_id"]:
        db.execute("UPDATE projects SET updated_at=? WHERE id=?", (now_iso(), t["project_id"]))
        actor_id, actor = actor_of(ctx)
        if a.get("column") in COLUMNS and column_of(t) != a["column"]:
            team_log(db, t["project_id"], actor_id, actor, f"نقل «{t['title']}» إلى «{COLUMN_AR[a['column']]}»")
        if a_changed and a_uid and a_uid != t.get("assignee_user_id"):
            team_log(db, t["project_id"], actor_id, actor, f"أسند «{t['title']}» إلى {f.get('assignee', '')}")
            if a_uid != actor_id:
                pname = (db.one("SELECT name FROM projects WHERE id=?", (t["project_id"],)) or {}).get("name", "")
                due = db.one("SELECT due_at FROM tasks WHERE id=?", (tid,))["due_at"]
                notify_later(ctx.app, a_uid, f"📌 أُسندت إليك مهمة «{t['title']}» في مشروع «{pname}»"
                             + (f" — الموعد {to_local_str(due, ctx.tz)}" if due else "") + f" (من {actor})")
    return [k for k in f if k != "updated_at"]


# ═══════════════ القوالب
# كل مرحلة: (العنوان، المدة بالأيام، [مهام: (العنوان، أيام من بداية المرحلة، المدة بالأيام، الأولوية)])
TEMPLATES: dict[str, dict] = {
    "construction": {"name": "مشروع إنشاء (فيلا/مبنى)", "phases": [
        ("التصميم والتراخيص", 30, [("اعتماد المخططات المعمارية", 0, 10, "high"), ("المخططات الإنشائية والكهروميكانيكية", 5, 15, "normal"),
                                   ("استخراج رخصة البناء", 15, 15, "high")]),
        ("الحفر والأساسات", 21, [("تجهيز الموقع والحفر", 0, 5, "normal"), ("صب النظافة والقواعد", 5, 8, "high"),
                                 ("العزل والردم", 13, 8, "normal")]),
        ("الهيكل الخرساني", 45, [("أعمدة وجدران الدور الأرضي", 0, 12, "high"), ("سقف الدور الأرضي", 12, 10, "high"),
                                 ("الدور الأول والسطح", 22, 23, "high")]),
        ("المباني واللياسة", 30, [("البلك والمباني", 0, 15, "normal"), ("التلييس الداخلي والخارجي", 15, 15, "normal")]),
        ("الكهرباء والسباكة والتكييف", 25, [("تمديدات الكهرباء", 0, 12, "normal"), ("تمديدات السباكة", 0, 12, "normal"),
                                            ("تمديدات التكييف", 10, 15, "normal")]),
        ("التشطيبات", 45, [("البلاط والرخام", 0, 20, "normal"), ("الدهانات", 15, 15, "normal"), ("الأبواب والنوافذ", 20, 15, "normal"),
                           ("تركيب الإنارة والأدوات الصحية", 30, 15, "normal")]),
        ("الاستلام والتسليم", 10, [("المعاينة وقائمة الملاحظات", 0, 4, "high"), ("إصلاح الملاحظات", 4, 4, "normal"),
                                   ("التسليم للمالك والمستخلص الختامي", 8, 2, "high")]),
    ]},
    "software": {"name": "تطوير تطبيق/برنامج", "phases": [
        ("التحليل والمتطلبات", 10, [("جمع المتطلبات من العميل", 0, 5, "high"), ("كتابة وثيقة المواصفات", 5, 5, "high")]),
        ("التصميم", 14, [("تصميم تجربة المستخدم (UX)", 0, 7, "normal"), ("تصميم الواجهات (UI)", 5, 9, "normal")]),
        ("التطوير", 40, [("قاعدة البيانات والخادم", 0, 15, "high"), ("الواجهات الأمامية", 10, 25, "high"), ("الربط والتكاملات", 25, 15, "normal")]),
        ("الاختبار", 14, [("اختبارات الجودة", 0, 10, "high"), ("إصلاح الأخطاء", 5, 9, "high")]),
        ("الإطلاق", 7, [("النشر على الخوادم والمتاجر", 0, 4, "high"), ("التدريب والتسليم", 4, 3, "normal")]),
    ]},
    "event": {"name": "تنظيم فعالية/مؤتمر", "phases": [
        ("التخطيط", 14, [("تحديد الهدف والميزانية", 0, 4, "high"), ("حجز المكان والتاريخ", 4, 10, "high")]),
        ("التجهيز", 21, [("المتحدثون والبرنامج", 0, 14, "normal"), ("الموردون (ضيافة، صوت، تصميم)", 5, 16, "normal")]),
        ("التسويق والتسجيل", 21, [("حملة الإعلان", 0, 21, "normal"), ("فتح التسجيل وإدارة الدعوات", 3, 18, "normal")]),
        ("يوم الفعالية", 2, [("البروفة النهائية", 0, 1, "high"), ("تشغيل الفعالية", 1, 1, "high")]),
        ("ما بعد الفعالية", 7, [("الشكر واستبيان الرضا", 0, 3, "normal"), ("التقرير الختامي والمالي", 2, 5, "normal")]),
    ]},
    "marketing": {"name": "حملة تسويقية", "phases": [
        ("الاستراتيجية", 7, [("تحديد الجمهور والرسالة", 0, 4, "high"), ("الميزانية والقنوات", 3, 4, "high")]),
        ("المحتوى", 14, [("كتابة المحتوى", 0, 8, "normal"), ("التصاميم والفيديو", 4, 10, "normal")]),
        ("الإطلاق", 21, [("نشر الإعلانات", 0, 3, "high"), ("المتابعة اليومية والتحسين", 3, 18, "normal")]),
        ("القياس", 5, [("تقرير النتائج وKPI", 0, 5, "normal")]),
    ]},
    "general": {"name": "مشروع عام", "phases": [
        ("البدء", 7, [("تحديد الأهداف والنطاق", 0, 3, "high"), ("تحديد الفريق والمسؤوليات", 2, 5, "normal")]),
        ("التنفيذ", 30, [("تنفيذ المهام الرئيسية", 0, 30, "high")]),
        ("الإغلاق", 5, [("المراجعة والتسليم", 0, 3, "high"), ("الدروس المستفادة", 3, 2, "low")]),
    ]},
}


def _local_dt(day: date, tzname: str) -> str:
    return iso(parse_local(day.isoformat() + "T09:00", tzname))


async def project_from_template(ctx: Ctx, a: dict) -> ToolResult:
    from .tools import create_project
    key = a.get("template") if a.get("template") in TEMPLATES else "general"
    tpl = TEMPLATES[key]
    db = ctx.app.db
    start = date.fromisoformat(str(a["start"])[:10]) if a.get("start") else local_now(ctx.tz).date()
    res = await create_project(ctx, {"name": a.get("name") or tpl["name"], "goal": a.get("goal") or "",
                                     "workspace": a.get("workspace"), "budget": a.get("budget")})
    if not res.ok:
        return res
    pid = res.data["id"]
    cur, prev_last, n_tasks = start, None, 0
    now = now_iso()
    for k, (phase, days, tasks) in enumerate(tpl["phases"]):
        end = cur + timedelta(days=days)
        mid = db.insert("INSERT INTO milestones(user_id,project_id,title,start_on,due_on,sort,created_at) VALUES(?,?,?,?,?,?,?)",
                        (ctx.uid, pid, phase, cur.isoformat(), end.isoformat(), k, now))
        first = True
        for j, (title, off, dur, prio) in enumerate(tasks):
            s_day = cur + timedelta(days=off)
            tid = db.insert(
                "INSERT INTO tasks(user_id,project_id,title,priority,start_at,due_at,milestone_id,depends_on,assignee,"
                "sort_order,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (ctx.uid, pid, title, prio, _local_dt(s_day, ctx.tz), _local_dt(s_day + timedelta(days=dur), ctx.tz), mid,
                 prev_last if first else None, a.get("owner") or "", k * 100 + j, "template", now, now))
            first, last = False, tid
            n_tasks += 1
        prev_last = last
        cur = end
    db.execute("UPDATE projects SET start_on=?, end_on=?, owner=? WHERE id=?",
               (start.isoformat(), cur.isoformat(), a.get("owner") or "", pid))
    return ToolResult(True, f"أُنشئ مشروع «{res.data['name']}» من قالب {tpl['name']}: {len(tpl['phases'])} مراحل و{n_tasks} مهمة، "
                            f"من {start.isoformat()} إلى {cur.isoformat()}.",
                      {"id": pid, "name": res.data["name"], "milestones": len(tpl["phases"]), "tasks": n_tasks,
                       "start": start.isoformat(), "end": cur.isoformat()}, verified=True)


# ═══════════════ المراحل
def _project(ctx: Ctx, ref) -> int:
    from .tools import _project_id
    pid = _project_id(ctx, ref)
    if not pid:
        raise ValueError("حدد المشروع")
    return pid


async def milestone_add(ctx: Ctx, a: dict) -> ToolResult:
    pid = _project(ctx, a.get("project"))
    title = (a.get("title") or "").strip()
    if not title:
        raise ValueError("اسم المرحلة مطلوب")
    db = ctx.app.db
    n = db.one("SELECT COALESCE(MAX(sort),-1)+1 n FROM milestones WHERE project_id=?", (pid,))["n"]
    mid = db.insert("INSERT INTO milestones(user_id,project_id,title,start_on,due_on,sort,created_at) VALUES(?,?,?,?,?,?,?)",
                    (ctx.uid, pid, title[:120], (a.get("start") or None) and str(a["start"])[:10],
                     (a.get("due") or None) and str(a["due"])[:10], n, now_iso()))
    return ToolResult(True, f"أُضيفت المرحلة «{title}»", {"id": mid}, verified=True)


async def milestone_update(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    m = db.one("SELECT * FROM milestones WHERE id=? AND user_id=?", (int(a["id"]), ctx.uid))
    if not m:
        raise ValueError("المرحلة غير موجودة")
    f = {}
    if a.get("title"):
        f["title"] = str(a["title"])[:120]
    for k in ("start", "due"):
        if a.get(k) is not None:
            f[k + "_on"] = str(a[k])[:10] or None
    if a.get("status") in ("open", "done"):
        f["status"] = a["status"]
    if not f:
        raise ValueError("لا تعديلات")
    db.execute(f"UPDATE milestones SET {', '.join(k + '=?' for k in f)} WHERE id=?", (*f.values(), m["id"]))
    return ToolResult(True, "عُدّلت المرحلة", {"id": m["id"]}, verified=True)


# ═══════════════ الوقت
async def log_time(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    tid = int(a["task"])
    t = db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", (tid, ctx.uid))
    if not t:
        raise ValueError("المهمة غير موجودة")
    mins = _minutes(a, "minutes", "hours")
    if not mins:
        raise ValueError("حدد المدة بالدقائق أو الساعات")
    db.insert("INSERT INTO time_logs(user_id,task_id,minutes,note,logged_at) VALUES(?,?,?,?,?)",
              (ctx.uid, tid, mins, (a.get("note") or "")[:300], now_iso()))
    db.execute("UPDATE tasks SET spent_min=spent_min+?, updated_at=? WHERE id=?", (mins, now_iso(), tid))
    from .team import actor_of
    from .team import log as team_log
    actor_id, actor = actor_of(ctx)
    team_log(db, t["project_id"], actor_id, actor, f"سجّل {mins} دقيقة على «{t['title']}»")
    t = db.one("SELECT spent_min, estimate_min FROM tasks WHERE id=?", (tid,))
    over = t["estimate_min"] and t["spent_min"] > t["estimate_min"]
    return ToolResult(True, f"سُجّل {mins} دقيقة على «{tid}». الإجمالي {t['spent_min']} دقيقة"
                      + (f" — تجاوز التقدير ({t['estimate_min']})" if over else ""),
                      {"spent_min": t["spent_min"], "estimate_min": t["estimate_min"]}, verified=True)


# ═══════════════ العروض: اللوحة، الجدول الزمني، العبء، الصحة
def board_data(db, uid: int, pid: int, tzname: str) -> dict:
    from .tools import _task_view
    rows = db.all("SELECT * FROM tasks WHERE user_id=? AND project_id=? AND status!='cancelled' "
                  "ORDER BY sort_order, COALESCE(due_at,'9999'), id", (uid, pid))
    cols = {c: [] for c in COLUMNS}
    for r in rows:
        v = _task_view(r, tzname)
        v.update(task_extras_view(db, r, tzname))
        cols[v["column"]].append(v)
    return {"columns": [{"id": c, "title": COLUMN_AR[c], "tasks": cols[c]} for c in COLUMNS]}


def timeline_data(db, uid: int, pid: int, tzname: str) -> dict:
    p = db.one("SELECT * FROM projects WHERE id=? AND user_id=?", (pid, uid))
    if not p:
        raise ValueError("المشروع غير موجود")
    ms = db.all("SELECT * FROM milestones WHERE project_id=? ORDER BY sort, COALESCE(start_on, due_on), id", (pid,))
    tasks = db.all("SELECT * FROM tasks WHERE project_id=? AND user_id=? AND status!='cancelled' "
                   "ORDER BY COALESCE(start_at, due_at, created_at)", (pid, uid))
    today = local_now(tzname).date().isoformat()
    out_ms = []
    for m in ms:
        mt = [t for t in tasks if t["milestone_id"] == m["id"]]
        done = sum(1 for t in mt if t["status"] == "done")
        late = m["status"] != "done" and m["due_on"] and m["due_on"] < today and done < len(mt)
        out_ms.append({"id": m["id"], "title": m["title"], "start": m["start_on"] or "", "due": m["due_on"] or "",
                       "status": m["status"], "progress": round(100 * done / len(mt)) if mt else (100 if m["status"] == "done" else 0),
                       "tasks": len(mt), "late": bool(late)})

    def day(v):
        return to_local_str(v, tzname, False)[:10] if v else ""
    bars = [{"id": t["id"], "title": t["title"], "start": day(t["start_at"]) or day(t["created_at"]), "end": day(t["due_at"]),
             "milestone_id": t["milestone_id"], "status": t["status"], "assignee": t["assignee"],
             "assignee_user_id": t["assignee_user_id"],
             "depends_on": t["depends_on"]} for t in tasks if t["due_at"] or t["start_at"]]
    dates = [x for m in out_ms for x in (m["start"], m["due"]) if x] + [x for b in bars for x in (b["start"], b["end"]) if x]
    return {"project": {"id": p["id"], "name": p["name"], "start": p["start_on"] or "", "end": p["end_on"] or ""},
            "milestones": out_ms, "tasks": bars, "today": today,
            "range": [min(dates), max(dates)] if dates else [today, today]}


def workload_data(db, uid: int, tzname: str, pid: int | None = None) -> list[dict]:
    q = ("SELECT COALESCE(NULLIF(assignee,''),'غير مُسند') who, COUNT(*) n, "
         "SUM(due_at IS NOT NULL AND due_at<?) overdue, COALESCE(SUM(estimate_min),0) est, COALESCE(SUM(spent_min),0) spent "
         "FROM tasks WHERE user_id=? AND status IN ('open','in_progress')")
    p: list = [now_iso(), uid]
    if pid:
        q += " AND project_id=?"
        p.append(pid)
    rows = db.all(q + " GROUP BY who ORDER BY n DESC", p)
    return [{"assignee": r["who"], "open": r["n"], "overdue": r["overdue"] or 0,
             "estimate_hours": round(r["est"] / 60, 1), "spent_hours": round(r["spent"] / 60, 1)} for r in rows]


def health_data(db, uid: int, pid: int, tzname: str) -> dict:
    p = db.one("SELECT * FROM projects WHERE id=? AND user_id=?", (pid, uid))
    if not p:
        raise ValueError("المشروع غير موجود")
    tasks = db.all("SELECT * FROM tasks WHERE project_id=? AND status!='cancelled'", (pid,))
    total, done = len(tasks), sum(1 for t in tasks if t["status"] == "done")
    now = now_iso()
    overdue = sum(1 for t in tasks if t["status"] in ("open", "in_progress") and t["due_at"] and t["due_at"] < now)
    tl = timeline_data(db, uid, pid, tzname)
    late_ms = [m["title"] for m in tl["milestones"] if m["late"]]
    blocked = sum(1 for t in tasks if t["board"] == "waiting" and t["status"] != "done")
    spent = db.one("SELECT COALESCE(SUM(amount_minor),0) s FROM ledger WHERE project_id=? AND kind='expense'", (pid,))["s"]
    reasons, level = [], "green"
    if overdue:
        reasons.append(f"{overdue} مهمة متأخرة")
    if late_ms:
        reasons.append("مراحل متأخرة: " + "، ".join(late_ms[:3]))
    if blocked:
        reasons.append(f"{blocked} مهمة بانتظار طرف آخر")
    budget_pct = round(100 * spent / p["budget_minor"]) if p["budget_minor"] else None
    if budget_pct is not None and budget_pct > 100:
        reasons.append(f"تجاوز الميزانية ({budget_pct}٪)")
    # الجدول: هل نسبة الإنجاز أقل من نسبة الوقت المنقضي؟
    progress = round(100 * done / total) if total else 0
    time_pct = None
    if p["start_on"] and p["end_on"]:
        s0, e0 = date.fromisoformat(p["start_on"]), date.fromisoformat(p["end_on"])
        today = local_now(tzname).date()
        if e0 > s0:
            time_pct = max(0, min(100, round(100 * (today - s0).days / (e0 - s0).days)))
            if time_pct - progress >= 15:
                reasons.append(f"الإنجاز {progress}٪ والوقت المنقضي {time_pct}٪")
    if late_ms or (budget_pct or 0) > 100 or overdue >= 5 or (time_pct is not None and time_pct - progress >= 30):
        level = "red"
    elif reasons:
        level = "amber"
    return {"level": level, "level_ar": {"green": "على المسار", "amber": "يحتاج انتباه", "red": "متعثر"}[level],
            "reasons": reasons, "progress": progress, "time_elapsed": time_pct, "overdue": overdue,
            "late_milestones": late_ms, "budget_percent": budget_pct}


async def project_status(ctx: Ctx, a: dict) -> ToolResult:
    pid = _project(ctx, a.get("project"))
    db = ctx.app.db
    return ToolResult(True, "حالة المشروع (أرقام فعلية)", {"health": health_data(db, ctx.uid, pid, ctx.tz),
                      "timeline": timeline_data(db, ctx.uid, pid, ctx.tz), "workload": workload_data(db, ctx.uid, ctx.tz, pid)},
                      verified=True)


async def team_workload(ctx: Ctx, a: dict) -> ToolResult:
    pid = _project(ctx, a["project"]) if a.get("project") else None
    return ToolResult(True, "عبء العمل لكل مسؤول", workload_data(ctx.app.db, ctx.uid, ctx.tz, pid), verified=True)


async def task_move(ctx: Ctx, a: dict) -> ToolResult:
    from .tools import _task_view
    tid = int(a["task"])
    if a.get("column") not in COLUMNS:
        raise ValueError("العمود: todo/doing/waiting/done")
    t = ctx.app.db.one("SELECT * FROM tasks WHERE id=? AND user_id=?", (tid, ctx.uid))
    if not t:
        raise ValueError("المهمة غير موجودة")
    if a["column"] in ("doing", "done") and t["depends_on"]:
        d = ctx.app.db.one("SELECT title, status FROM tasks WHERE id=?", (t["depends_on"],))
        if d and d["status"] not in ("done", "cancelled") and not a.get("force"):
            return ToolResult(False, f"هذه المهمة تنتظر «{d['title']}» — أكملها أولًا (أو أرسل force=true).")
    apply_task_extras(ctx, tid, {"column": a["column"]})
    row = ctx.app.db.one("SELECT * FROM tasks WHERE id=?", (tid,))
    return ToolResult(True, f"نُقلت إلى «{COLUMN_AR[a['column']]}»", _task_view(row, ctx.tz), verified=True)


async def team_invite(ctx: Ctx, a: dict) -> ToolResult:
    from . import team
    from .tools import _project_id
    pid = _project_id(ctx, a.get("project"))
    if not pid or not ctx.app.db.one("SELECT 1 FROM projects WHERE id=? AND user_id=?", (pid, ctx.uid)):
        raise ValueError("حدد مشروعًا من مشاريعك")
    inv = team.new_invite(ctx.app.db, pid, ctx.uid)
    f = ctx.app.s.data_dir / "tunnel-url.txt"
    base = f.read_text(encoding="utf-8").strip().rstrip("/") if f.exists() else ""
    link = f"{base}/join/{inv['code']}" if base else ""
    team.log(ctx.app.db, pid, ctx.uid, team.display(ctx.user), "أنشأ رابط دعوة للفريق")
    msg = (f"رابط الدعوة: {link}" if link else f"رمز الدعوة: {inv['code']} (الوصول من الإنترنت غير مفعّل، فالرابط لا يعمل خارج جوالك)")
    return ToolResult(True, msg + f" — صالح 7 أيام لـ {inv['max_uses']} أشخاص. لا يُرسل لأحد تلقائيًا؛ شاركه أنت.",
                      {"code": inv["code"], "link": link}, verified=True)


async def team_members(ctx: Ctx, a: dict) -> ToolResult:
    from . import team
    from .tools import _project_id
    pid = _project_id(ctx, a.get("project"))
    if not pid or not team.access(ctx.app.db, ctx.uid, pid):
        raise ValueError("المشروع غير موجود")
    ms = team.members(ctx.app.db, pid)
    return ToolResult(True, "، ".join(f"{m['name']} ({m['role_ar']}: {m['open']} مفتوحة، {m['overdue']} متأخرة)" for m in ms),
                      ms, verified=True)


def register_pm_tools(r: Registry) -> None:
    T = lambda *x, **k: r.add(Tool(*x, **k))  # noqa: E731
    T("project_from_template", "إنشاء مشروع كامل من قالب جاهز بمراحله ومهامه ومواعيده واعتمادياته "
      "(construction=مشروع إنشاء/فيلا، software=تطوير تطبيق، event=فعالية، marketing=حملة تسويقية، general=عام).",
      obj({"template": s("", list(TEMPLATES)), "name": s("اسم المشروع"), "start": s("تاريخ البداية YYYY-MM-DD"),
           "workspace": s("الشركة"), "budget": s("الميزانية"), "owner": s("المسؤول الافتراضي"), "goal": s("")}, ["template"]),
      project_from_template, category="المشاريع")
    T("milestone_add", "إضافة مرحلة/معلم لمشروع بتاريخ بداية ونهاية.", obj({"project": s(""), "title": s(""),
      "start": s("YYYY-MM-DD"), "due": s("YYYY-MM-DD")}, ["project", "title"]), milestone_add, category="المشاريع")
    T("milestone_update", "تعديل مرحلة أو تعليمها مكتملة.", obj({"id": i(""), "title": s(""), "start": s(""), "due": s(""),
      "status": s("", ["open", "done"])}, ["id"]), milestone_update, category="المشاريع")
    T("task_move", "نقل مهمة بين أعمدة لوحة المشروع: todo للتنفيذ، doing قيد التنفيذ، waiting بانتظار، done مكتملة.",
      obj({"task": i("رقم المهمة"), "column": s("", COLUMNS), "force": {"type": "boolean"}}, ["task", "column"]), task_move,
      category="المشاريع")
    T("log_time", "تسجيل وقت عمل فعلي على مهمة.", obj({"task": i(""), "minutes": i(""), "hours": {"type": "number"},
      "note": s("")}, ["task"]), log_time, category="المشاريع")
    T("project_status", "حالة مشروع: الصحة (على المسار/يحتاج انتباه/متعثر) وأسبابها، المراحل والجدول الزمني، وعبء الفريق.",
      obj({"project": s("")}, ["project"]), project_status, category="المشاريع")
    T("team_invite", "إنشاء رابط دعوة لفريق مشروع (العضو يرى المشروع ويعدّل مهامه فقط، بلا مالية). لا يُرسل الرابط لأحد — يُعطى للمستخدم ليشاركه.",
      obj({"project": s("")}, ["project"]), team_invite, category="المشاريع")
    T("team_members", "أعضاء فريق مشروع ومهامهم المفتوحة والمتأخرة.", obj({"project": s("")}, ["project"]), team_members,
      category="المشاريع")
    T("team_workload", "عبء العمل: المهام المفتوحة والمتأخرة والساعات لكل مسؤول.", obj({"project": s("")}), team_workload,
      category="المشاريع")


__all__ = ["register_pm_tools", "apply_task_extras", "task_extras_view", "board_data", "timeline_data", "workload_data",
           "health_data", "TEMPLATES", "COLUMNS", "COLUMN_AR"]
