"""مجدول دائم: المهام محفوظة في قاعدة البيانات، تُستأنف بعد الانقطاع، ولا تُنفّذ مرتين."""
from __future__ import annotations

import asyncio
import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from typing import TYPE_CHECKING, Awaitable, Callable

from .db import iso, now_iso, parse_iso, utcnow
from .security import redact
from .timeutil import local_now, next_occurrence, parse_local, to_local_str, tz

if TYPE_CHECKING:
    from .core import Harith

JobHandler = Callable[[dict, dict], Awaitable[str]]
JOB_TIMEOUT_S = 300


class RetryLater(Exception):
    """فشل مؤقت: أعد المحاولة لاحقًا."""


class Scheduler:
    def __init__(self, app: "Harith"):
        self.app = app
        self.db = app.db
        self.handlers: dict[str, JobHandler] = {}
        self._task: asyncio.Task | None = None
        self._wake = asyncio.Event()
        self._stop = False
        self.last_tick: str | None = None

    def register(self, kind: str, fn: JobHandler) -> None:
        self.handlers[kind] = fn

    # ——— الإضافة
    def enqueue(self, user_id: int | None, kind: str, payload: dict, run_at: datetime | str,
                dedupe_key: str | None = None, max_attempts: int = 3) -> int:
        run_at_s = run_at if isinstance(run_at, str) else iso(run_at)
        try:
            jid = self.db.insert(
                "INSERT INTO jobs(user_id,kind,payload,run_at,max_attempts,dedupe_key,created_at) VALUES(?,?,?,?,?,?,?)",
                (user_id, kind, json.dumps(payload, ensure_ascii=False), run_at_s, max_attempts, dedupe_key, now_iso()))
        except sqlite3.IntegrityError:
            row = self.db.one("SELECT id FROM jobs WHERE dedupe_key=?", (dedupe_key,))
            return int(row["id"])
        self._wake.set()
        return jid

    def cancel_jobs(self, user_id: int | None = None, kinds: tuple[str, ...] | None = None) -> int:
        q = "UPDATE jobs SET status='cancelled', finished_at=? WHERE status='pending'"
        p: list = [now_iso()]
        if user_id is not None:
            q += " AND user_id=?"
            p.append(user_id)
        if kinds:
            q += f" AND kind IN ({','.join('?' * len(kinds))})"
            p += list(kinds)
        return self.db.execute(q, p).rowcount

    # ——— التشغيل
    def recover(self) -> int:
        """أي مهمة كانت قيد التنفيذ عند انقطاع الخادم تعود للانتظار."""
        return self.db.execute(
            "UPDATE jobs SET status='pending', last_error='استُؤنفت بعد انقطاع الخادم' WHERE status='running'").rowcount

    def start(self) -> None:
        self.recover()
        self._stop = False
        self._task = asyncio.create_task(self._loop(), name="scheduler")

    async def stop(self) -> None:
        self._stop = True
        self._wake.set()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass

    async def _loop(self) -> None:
        last_maint = 0.0
        while not self._stop:
            try:
                loop_t = asyncio.get_running_loop().time()
                if loop_t - last_maint > 60:
                    self.ensure_recurring()
                    last_maint = loop_t
                await self.run_due()
                self.last_tick = now_iso()
            except Exception as e:  # noqa: BLE001
                self.db.log_event("error", "scheduler", redact(repr(e)))
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=self._sleep_s())
            except asyncio.TimeoutError:
                pass

    def _sleep_s(self) -> float:
        r = self.db.one("SELECT MIN(run_at) AS t FROM jobs WHERE status='pending'")
        if not r or not r["t"]:
            return 30.0
        delta = (parse_iso(r["t"]) - utcnow()).total_seconds()
        return max(0.5, min(delta, 30.0))

    async def run_due(self, limit: int = 20) -> int:
        due = self.db.all("SELECT * FROM jobs WHERE status='pending' AND run_at<=? ORDER BY run_at LIMIT ?",
                          (now_iso(), limit))
        n = 0
        for job in due:
            # المطالبة الذرّية بالمهمة: لا تُنفذ مرتين حتى مع أكثر من عامل
            claimed = self.db.execute(
                "UPDATE jobs SET status='running', started_at=?, attempts=attempts+1 WHERE id=? AND status='pending'",
                (now_iso(), job["id"])).rowcount
            if not claimed:
                continue
            n += 1
            await self._run_one(self.db.one("SELECT * FROM jobs WHERE id=?", (job["id"],)))
        return n

    async def _run_one(self, job: dict) -> None:
        fn = self.handlers.get(job["kind"])
        if not fn:
            self._finish(job, "failed", f"نوع غير معروف: {job['kind']}")
            return
        try:
            result = await asyncio.wait_for(fn(job, json.loads(job["payload"] or "{}")), timeout=JOB_TIMEOUT_S)
            self._finish(job, "done", result or "")
        except asyncio.TimeoutError:
            self._retry_or_fail(job, f"تجاوزت المهلة ({JOB_TIMEOUT_S} ثانية)")
        except RetryLater as e:
            self._retry_or_fail(job, str(e))
        except Exception as e:  # noqa: BLE001
            self.db.log_event("error", f"job:{job['kind']}", redact(repr(e)))
            self._retry_or_fail(job, f"{type(e).__name__}: {redact(str(e))[:300]}")

    def _finish(self, job: dict, status: str, msg: str) -> None:
        self.db.execute("UPDATE jobs SET status=?, last_error=?, finished_at=? WHERE id=?",
                        (status, msg[:1000] if status != "done" else (msg[:1000] or None), now_iso(), job["id"]))

    def _retry_or_fail(self, job: dict, err: str) -> None:
        if job["attempts"] >= job["max_attempts"]:
            self._finish(job, "failed", err)
            self.db.log_event("warning", f"job:{job['kind']}", f"فشلت نهائيًا بعد {job['attempts']} محاولات: {err}")
            return
        delay = 30 * (2 ** (job["attempts"] - 1))
        self.db.execute("UPDATE jobs SET status='pending', run_at=?, last_error=? WHERE id=?",
                        (iso(utcnow() + timedelta(seconds=delay)), err[:1000], job["id"]))

    # ——— المهام اليومية المتكررة (الملخصات، النسخ الاحتياطي، التنظيف)
    def ensure_recurring(self) -> None:
        now = utcnow()
        for u in self.db.all("SELECT id, timezone FROM users WHERE disabled=0"):
            for kind, key, default in (("briefing_morning", "briefing_time", "07:30"),
                                       ("briefing_evening", "evening_time", "21:00")):
                if not self.db.get_setting(u["id"], f"{kind}_enabled", True):
                    continue
                hhmm = self.db.get_setting(u["id"], key, default)
                loc = local_now(u["timezone"])
                for add in (0, 1):
                    day = (loc + timedelta(days=add)).strftime("%Y-%m-%d")
                    try:
                        at = parse_local(f"{day}T{hhmm}", u["timezone"])
                    except ValueError:
                        break
                    if at > now:
                        self.enqueue(u["id"], kind, {}, at, dedupe_key=f"{kind}:{u['id']}:{day}:{hhmm}",
                                     max_attempts=2)
                        break
        day = now.strftime("%Y-%m-%d")
        self.enqueue(None, "backup", {}, now.replace(hour=0, minute=30, second=0) + timedelta(days=1),
                     dedupe_key=f"backup:{day}")
        self.enqueue(None, "cleanup", {}, now.replace(hour=1, minute=0, second=0) + timedelta(days=1),
                     dedupe_key=f"cleanup:{day}")


class Reminders:
    """التذكيرات: كل تذكير له مهمة مجدولة بمفتاح فريد، فلا يُرسل مرتين."""

    def __init__(self, app: "Harith"):
        self.app = app
        self.db = app.db

    def create(self, uid: int, text: str, due_at: str, recur: str = "none", task_id: int | None = None) -> int:
        with self.db.tx():
            rid = self.db.insert(
                "INSERT INTO reminders(user_id,text,due_at,recur,task_id,created_at) VALUES(?,?,?,?,?,?)",
                (uid, text, due_at, recur, task_id, now_iso()))
        self.app.scheduler.enqueue(uid, "reminder", {"id": rid, "due_at": due_at}, due_at,
                                   dedupe_key=f"reminder:{rid}:{due_at}", max_attempts=5)
        return rid

    def cancel(self, uid: int, rid: int) -> bool:
        n = self.db.execute("UPDATE reminders SET status='cancelled' WHERE id=? AND user_id=? AND status='pending'",
                            (rid, uid)).rowcount
        if n:
            self.db.execute("UPDATE jobs SET status='cancelled', finished_at=? WHERE kind='reminder' AND "
                            "status='pending' AND json_extract(payload,'$.id')=?", (now_iso(), rid))
        return bool(n)

    async def fire(self, job: dict, payload: dict) -> str:
        r = self.db.one("SELECT r.*, u.timezone FROM reminders r JOIN users u ON u.id=r.user_id WHERE r.id=?",
                        (payload["id"],))
        if not r or r["status"] != "pending" or r["due_at"] != payload.get("due_at"):
            return "تخطّي: التذكير أُلغي أو تغيّر"
        delivered = await self.app.notify(r["user_id"], f"🔔 تذكير: {r['text']}",
                                          dedupe=f"rem:{r['id']}:{r['due_at']}", kind="reminder")
        if delivered.get("failed") and not delivered.get("telegram"):
            self.db.execute("UPDATE reminders SET attempts=attempts+1, last_error=? WHERE id=?",
                            (delivered["failed"], r["id"]))
            raise RetryLater(f"تعذّر الإرسال: {delivered['failed']}")
        nxt = next_occurrence(r["due_at"], r["recur"], r["timezone"])
        with self.db.tx():
            if nxt:
                self.db.execute("UPDATE reminders SET due_at=?, sent_at=?, attempts=0 WHERE id=?",
                                (nxt, now_iso(), r["id"]))
            else:
                self.db.execute("UPDATE reminders SET status='sent', sent_at=? WHERE id=?", (now_iso(), r["id"]))
        if nxt:
            self.app.scheduler.enqueue(r["user_id"], "reminder", {"id": r["id"], "due_at": nxt}, nxt,
                                       dedupe_key=f"reminder:{r['id']}:{nxt}", max_attempts=5)
        ch = ", ".join(k for k, v in delivered.items() if v is True) or "صندوق الإشعارات"
        return f"أُرسل عبر: {ch}" + (f" — التالي {to_local_str(nxt, r['timezone'])}" if nxt else "")


def run_backup(app: "Harith") -> Path:
    stamp = utcnow().strftime("%Y%m%d-%H%M%S")
    dest = app.s.backups_dir / f"harith-{stamp}.db"
    app.db.backup_to(dest)
    # التحقق: افتح النسخة وتأكد من سلامتها
    con = sqlite3.connect(str(dest))
    try:
        ok = con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        con.execute("SELECT COUNT(*) FROM users").fetchone()
    finally:
        con.close()
    if not ok:
        dest.unlink(missing_ok=True)
        raise RuntimeError("النسخة الاحتياطية تالفة")
    backups = sorted(app.s.backups_dir.glob("harith-*.db"))
    for old in backups[:-app.s.backup_keep]:
        old.unlink(missing_ok=True)
    return dest


def run_cleanup(app: "Harith") -> str:
    db = app.db
    now = utcnow()
    n_msg = 0
    for u in db.all("SELECT id FROM users"):
        days = int(db.get_setting(u["id"], "history_retention_days", app.s.history_retention_days) or 0)
        if days > 0:
            n_msg += db.execute("DELETE FROM messages WHERE user_id=? AND created_at<?",
                                (u["id"], iso(now - timedelta(days=days)))).rowcount
    n_mem = db.execute("DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at<?", (iso(now),)).rowcount
    db.execute("DELETE FROM processed_keys WHERE created_at<?", (iso(now - timedelta(days=7)),))
    db.execute("DELETE FROM sessions WHERE expires_at<?", (iso(now),))
    db.execute("DELETE FROM link_codes WHERE expires_at<?", (iso(now),))
    db.execute("DELETE FROM jobs WHERE status IN ('done','cancelled') AND finished_at<?", (iso(now - timedelta(days=30)),))
    db.execute("DELETE FROM operations WHERE created_at<?", (iso(now - timedelta(days=180)),))
    db.execute("DELETE FROM event_log WHERE created_at<?", (iso(now - timedelta(days=60)),))
    db.execute("UPDATE approvals SET status='expired' WHERE status='pending' AND created_at<?",
               (iso(now - timedelta(days=3)),))
    return f"حُذفت {n_msg} رسالة قديمة و{n_mem} معلومة منتهية"


def restore_backup(db_path: Path, backup: Path) -> None:
    """يُستخدم والخادم متوقف: يستبدل قاعدة البيانات بنسخة احتياطية بعد التحقق منها."""
    con = sqlite3.connect(str(backup))
    try:
        if con.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("النسخة الاحتياطية تالفة")
    finally:
        con.close()
    for suffix in ("-wal", "-shm"):
        Path(str(db_path) + suffix).unlink(missing_ok=True)
    if db_path.exists():
        db_path.rename(db_path.with_suffix(f".before-restore-{utcnow().strftime('%Y%m%d%H%M%S')}.db"))
    src = sqlite3.connect(str(backup))
    dst = sqlite3.connect(str(db_path))
    try:
        src.backup(dst)
    finally:
        src.close()
        dst.close()


__all__ = ["Scheduler", "Reminders", "RetryLater", "run_backup", "run_cleanup", "restore_backup", "tz"]
