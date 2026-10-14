"""الفريق: أعضاء المشاريع ودعوات الانضمام وسجل النشاط.

القواعد:
- صاحب المشروع (من أنشأه) يدير الأعضاء والدعوات ويعدّل كل شيء.
- العضو يرى المشروع كاملًا (اللوحة، المراحل، الجدول، الفريق، النشاط) لكن لا يرى المالية ولا الميزانية
  ولا سجل القرارات والعقود، ويعدّل فقط المهام المسندة إليه (نقلها بين الأعمدة وتسجيل الوقت).
- كل تعديل يُسجَّل باسم من نفّذه في «نشاط المشروع».
"""
from __future__ import annotations

import secrets
from datetime import timedelta

from .db import iso, now_iso, utcnow
from .timeutil import to_local_str

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # بلا O/0 و I/1 لتجنب الالتباس


def display(u: dict | None) -> str:
    if not u:
        return "—"
    return (u.get("display_name") or u.get("username") or "—").strip()


def access(db, uid: int, pid: int) -> tuple[int, str] | None:
    """(معرّف صاحب المشروع، الدور) أو None إن لم يكن للمستخدم وصول."""
    p = db.one("SELECT user_id FROM projects WHERE id=?", (pid,))
    if not p:
        return None
    if p["user_id"] == uid:
        return uid, "owner"
    if db.one("SELECT 1 FROM project_members WHERE project_id=? AND user_id=?", (pid, uid)):
        return p["user_id"], "member"
    return None


def is_member(db, pid: int, uid: int) -> bool:
    return access(db, uid, pid) is not None


def members(db, pid: int) -> list[dict]:
    """صاحب المشروع أولًا ثم الأعضاء، مع عدد المهام المفتوحة والمتأخرة لكل واحد."""
    p = db.one("SELECT user_id FROM projects WHERE id=?", (pid,))
    if not p:
        return []
    rows = [{"user_id": p["user_id"], "role": "owner"}] + [
        {"user_id": r["user_id"], "role": r["role"]} for r in
        db.all("SELECT user_id, role FROM project_members WHERE project_id=? ORDER BY added_at", (pid,))]
    now = now_iso()
    out = []
    for r in rows:
        u = db.one("SELECT id, username, display_name, disabled FROM users WHERE id=?", (r["user_id"],))
        if not u:
            continue
        st = db.one("SELECT COUNT(*) n, SUM(due_at IS NOT NULL AND due_at<?) od FROM tasks WHERE project_id=? "
                    "AND assignee_user_id=? AND status IN ('open','in_progress')", (now, pid, u["id"]))
        out.append({"user_id": u["id"], "name": display(u), "username": u["username"], "role": r["role"],
                    "role_ar": "صاحب المشروع" if r["role"] == "owner" else "عضو", "disabled": bool(u["disabled"]),
                    "open": st["n"] or 0, "overdue": st["od"] or 0})
    return out


def add_member(db, pid: int, uid: int, by: int | None) -> bool:
    if access(db, uid, pid):
        return False
    db.execute("INSERT OR IGNORE INTO project_members(project_id,user_id,role,added_by,added_at) VALUES(?,?,?,?,?)",
               (pid, uid, "member", by, now_iso()))
    return True


def remove_member(db, pid: int, uid: int) -> bool:
    n = db.execute("DELETE FROM project_members WHERE project_id=? AND user_id=?", (pid, uid)).rowcount
    if n:   # مهامه تبقى بالاسم لكن تُفك عن حسابه
        db.execute("UPDATE tasks SET assignee_user_id=NULL WHERE project_id=? AND assignee_user_id=?", (pid, uid))
    return bool(n)


def new_invite(db, pid: int, by: int, days: int = 7, max_uses: int = 10) -> dict:
    code = "".join(secrets.choice(ALPHABET) for _ in range(8))
    exp = iso(utcnow() + timedelta(days=days))
    db.execute("DELETE FROM team_invites WHERE expires_at<?", (now_iso(),))
    db.execute("INSERT INTO team_invites(code,project_id,created_by,expires_at,max_uses,created_at) VALUES(?,?,?,?,?,?)",
               (code, pid, by, exp, max_uses, now_iso()))
    return {"code": code, "expires_at": exp, "max_uses": max_uses}


def norm_code(code: str) -> str:
    return "".join(ch for ch in str(code).upper() if ch in ALPHABET)


def peek_invite(db, code: str) -> dict | None:
    c = norm_code(code)
    if len(c) != 8:
        return None
    r = db.one("SELECT i.*, p.name project FROM team_invites i JOIN projects p ON p.id=i.project_id "
               "WHERE i.code=? AND i.expires_at>? AND i.uses<i.max_uses", (c, now_iso()))
    return r


def use_invite(db, code: str) -> dict | None:
    r = peek_invite(db, code)
    if r:
        db.execute("UPDATE team_invites SET uses=uses+1 WHERE code=?", (r["code"],))
    return r


def log(db, pid: int | None, uid: int | None, actor: str, action: str) -> None:
    if not pid:
        return
    db.insert("INSERT INTO project_activity(project_id,user_id,actor,action,created_at) VALUES(?,?,?,?,?)",
              (pid, uid, actor[:80], action[:300], now_iso()))


def activity(db, pid: int, tzname: str, limit: int = 60) -> list[dict]:
    rows = db.all("SELECT actor, action, created_at FROM project_activity WHERE project_id=? ORDER BY id DESC LIMIT ?",
                  (pid, limit))
    for r in rows:
        r["when"] = to_local_str(r["created_at"], tzname)
    return rows


def actor_of(ctx) -> tuple[int, str]:
    """من نفّذ فعلًا: عند تصرّف العضو نمرّر بياناته داخل ctx.user (_actor_id/_actor_name)."""
    u = ctx.user
    return int(u.get("_actor_id") or u["id"]), (u.get("_actor_name") or display(u))


def resolve_assignee(db, pid: int | None, a: dict) -> tuple[bool, int | None, str | None]:
    """يحوّل المسؤول إلى حساب حقيقي إن كان عضوًا في المشروع.
    يعيد (هل تغيّر، معرّف الحساب، الاسم المعروض الجديد أو None للإبقاء على النص)."""
    if not pid:
        return False, None, None
    if a.get("assignee_user") not in (None, ""):
        target = int(a["assignee_user"])
        if target == 0:
            return True, None, ""
        if not is_member(db, pid, target):
            raise ValueError("هذا الشخص ليس عضوًا في فريق المشروع — أضفه للفريق أولًا")
        u = db.one("SELECT username, display_name FROM users WHERE id=?", (target,))
        return True, target, display(u)
    if a.get("assignee") is not None:
        name = str(a["assignee"]).strip()
        if not name:
            return True, None, None
        for m in members(db, pid):
            if name in (m["name"], m["username"]):
                return True, m["user_id"], m["name"]
        return True, None, None
    return False, None, None


def shared_projects(db, uid: int) -> list[dict]:
    return db.all(
        "SELECT p.id, p.name, p.goal, p.status, p.start_on, p.end_on, p.owner, p.user_id owner_id, "
        "(SELECT COALESCE(NULLIF(display_name,''), username) FROM users WHERE id=p.user_id) owner_name, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id AND t.status!='cancelled') tasks_total, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id AND t.status='done') tasks_done "
        "FROM projects p JOIN project_members m ON m.project_id=p.id "
        "WHERE m.user_id=? AND p.status!='archived' ORDER BY p.updated_at DESC", (uid,))


def my_tasks(db, uid: int, tzname: str) -> list[dict]:
    """المهام المسندة لي في مشاريع الآخرين (فريق)."""
    from .pm import task_extras_view
    from .tools import _task_view
    rows = db.all("SELECT t.*, p.name project_name FROM tasks t JOIN projects p ON p.id=t.project_id "
                  "WHERE t.assignee_user_id=? AND t.user_id!=? AND t.status IN ('open','in_progress') "
                  "ORDER BY COALESCE(t.due_at,'9999'), t.id LIMIT 100", (uid, uid))
    out = []
    for r in rows:
        v = _task_view(r, tzname)
        v.update(task_extras_view(db, r, tzname))
        v["project"] = r["project_name"]
        out.append(v)
    return out


def team_alerts_text(db, uid: int, tzname: str) -> str:
    """يُضاف للتنبيهات الصباحية: مهامك في الفرق — المتأخر وما يستحق اليوم."""
    from .timeutil import local_now
    tasks = my_tasks(db, uid, tzname)
    if not tasks:
        return ""
    today = local_now(tzname).date().isoformat()
    late = [t for t in tasks if t["overdue"]]
    due_today = [t for t in tasks if not t["overdue"] and t["due"].startswith(today)]
    if not late and not due_today:
        return ""
    lines = ["👥 مهامك في الفريق:"]
    lines += [f"⚠️ متأخرة: {t['title']} — {t['project']}" for t in late[:8]]
    lines += [f"📌 اليوم: {t['title']} — {t['project']}" for t in due_today[:8]]
    return "\n".join(lines)


def notify_later(app, uid: int, text: str) -> None:
    """إشعار عبر طابور المهام (يصل لتطبيق العضو وتيليجرام إن كان مربوطًا)."""
    try:
        app.scheduler.enqueue(uid, "notify", {"text": text}, utcnow(), max_attempts=3)
    except Exception:  # noqa: BLE001 — الإشعار لا يُفشل العملية الأصلية
        pass
