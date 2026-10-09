"""مزامنة ثنائية الاتجاه مع تطبيق رفيق على الجوال (المهام والذاكرة).

القاعدة: الأحدث يفوز (حسب وقت التعديل). كل عنصر له مرجع ثابت ref:
  - عنصر أُنشئ في الجوال: ref = رقمه المحلي
  - عنصر أُنشئ في الخادم: ref = "s<id>"
الحذف يُنقل عبر سجل deletions.
"""
from __future__ import annotations

from datetime import datetime, timezone

from .db import DB, iso, now_iso, parse_iso
from .security import looks_secret
from .timeutil import parse_local, to_local_str

PRIO_IN = {"low": "low", "medium": "normal", "normal": "normal", "high": "high", "urgent": "high"}
STATUS_IN = {"new": "open", "open": "open", "postponed": "open", "in_progress": "in_progress", "done": "done",
             "cancelled": "cancelled"}


def _ms(s: str | None) -> int:
    d = parse_iso(s)
    return int(d.timestamp() * 1000) if d else 0


def _iso_ms(ms: int) -> str:
    return iso(datetime.fromtimestamp(ms / 1000, tz=timezone.utc))  # type: ignore[return-value]


def _find(db: DB, table: str, uid: int, ref: str) -> dict | None:
    r = db.one(f"SELECT * FROM {table} WHERE user_id=? AND client_ref=?", (uid, ref))
    if not r and ref.startswith("s") and ref[1:].isdigit():
        r = db.one(f"SELECT * FROM {table} WHERE user_id=? AND id=?", (uid, int(ref[1:])))
    return r


def _deleted_after(db: DB, uid: int, kind: str, ref: str, upd_ms: int) -> bool:
    r = db.one("SELECT MAX(deleted_at) d FROM deletions WHERE user_id=? AND kind=? AND ref=?", (uid, kind, ref))
    return bool(r and r["d"] and _ms(r["d"]) >= upd_ms)


def _ref(row: dict) -> str:
    return row["client_ref"] or f"s{row['id']}"


def sync(db: DB, user: dict, body: dict) -> dict:
    uid, tzname = int(user["id"]), user["timezone"]
    since = body.get("since") or "1970-01-01T00:00:00Z"
    cursor = now_iso()
    applied, skipped, rejected = 0, 0, []

    # ——— المهام الواردة
    for it in body.get("tasks") or []:
        ref = str(it.get("ref") or "").strip()
        if not ref:
            continue
        upd = int(it.get("updated_ms") or 0)
        row = _find(db, "tasks", uid, ref)
        if row and _ms(row["updated_at"]) > upd:
            skipped += 1
            continue
        if it.get("deleted"):
            if row:
                db.execute("DELETE FROM tasks WHERE id=?", (row["id"],))
                applied += 1
            continue
        title = (it.get("title") or "").strip()
        if not title:
            continue
        if not row and _deleted_after(db, uid, "task", ref, upd):
            skipped += 1  # حُذفت في الخادم بعد آخر تعديل في الجوال: لا نعيد إحياءها
            continue
        due = None
        if it.get("due"):
            try:
                due = iso(parse_local(str(it["due"]), tzname))
            except ValueError:
                due = row["due_at"] if row else None
        vals = (title, it.get("notes") or "", PRIO_IN.get(it.get("priority") or "", "normal"),
                STATUS_IN.get(it.get("status") or "", "open"), due, _iso_ms(upd) if upd else now_iso())
        if row:
            db.execute("UPDATE tasks SET title=?, notes=?, priority=?, status=?, due_at=?, updated_at=?, "
                       "completed_at=CASE WHEN ?='done' THEN COALESCE(completed_at, ?) ELSE NULL END WHERE id=?",
                       (*vals, vals[3], now_iso(), row["id"]))
        else:
            db.execute("INSERT INTO tasks(user_id,title,notes,priority,status,due_at,updated_at,created_at,source,"
                       "client_ref,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                       (uid, *vals, now_iso(), "android", ref, now_iso() if vals[3] == "done" else None))
        applied += 1

    # ——— الذاكرة الواردة
    for it in body.get("memories") or []:
        ref = str(it.get("ref") or "").strip()
        if not ref:
            continue
        upd = int(it.get("updated_ms") or 0)
        row = _find(db, "memories", uid, ref)
        if row and _ms(row["updated_at"]) > upd:
            skipped += 1
            continue
        if it.get("deleted"):
            if row:
                db.execute("DELETE FROM memories WHERE id=?", (row["id"],))
                applied += 1
            continue
        text = (it.get("text") or "").strip()
        if not text:
            continue
        if not row and _deleted_after(db, uid, "memory", ref, upd):
            skipped += 1
            continue
        if looks_secret(text):
            rejected.append({"ref": ref, "reason": "لا تُحفظ الأسرار في الذاكرة"})
            continue
        exp = _iso_ms(int(it["expires_ms"])) if it.get("expires_ms") else None
        when = _iso_ms(upd) if upd else now_iso()
        if row:
            db.execute("UPDATE memories SET content=?, expires_at=?, updated_at=? WHERE id=?",
                       (text, exp, when, row["id"]))
        else:
            db.execute("INSERT INTO memories(user_id,kind,content,source,created_at,updated_at,expires_at,client_ref) "
                       "VALUES(?,?,?,?,?,?,?,?)", (uid, "fact", text, "android", when, when, exp, ref))
        applied += 1

    # ——— الصادر: كل ما تغيّر منذ آخر مزامنة
    tasks = [{
        "ref": _ref(t), "title": t["title"], "notes": t["notes"], "priority": t["priority"], "status": t["status"],
        "due": to_local_str(t["due_at"], tzname, False).replace(" ", "T") if t["due_at"] else "",
        "updated_ms": _ms(t["updated_at"]), "project_id": t["project_id"],
    } for t in db.all("SELECT * FROM tasks WHERE user_id=? AND updated_at>=? AND parent_id IS NULL ORDER BY id",
                      (uid, since))]
    mems = [{
        "ref": _ref(m), "text": m["content"], "kind": m["kind"], "updated_ms": _ms(m["updated_at"]),
        "expires_ms": _ms(m["expires_at"]) if m["expires_at"] else 0,
    } for m in db.all("SELECT * FROM memories WHERE user_id=? AND updated_at>=? ORDER BY id", (uid, since))]
    dels = db.all("SELECT kind, ref FROM deletions WHERE user_id=? AND deleted_at>=?", (uid, since))
    return {"cursor": cursor, "tasks": tasks, "memories": mems, "deleted": dels,
            "applied": applied, "skipped": skipped, "rejected": rejected}
