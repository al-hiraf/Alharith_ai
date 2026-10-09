"""قلب الحارث: يربط الإعدادات وقاعدة البيانات والذكاء الاصطناعي والأدوات والمجدول والقنوات."""
from __future__ import annotations

import json
import secrets
from datetime import timedelta
from typing import Any, Protocol

from .agent import Agent
from .ai import AIRouter
from .config import Settings
from .db import DB, iso, now_iso, utcnow
from .scheduler import Reminders, Scheduler, next_after_now, run_backup, run_cleanup
from .security import create_user, redact
from .timeutil import next_occurrence, parse_local, to_local_str
from .tools import build_registry, day_overview
from .toolkit import Ctx, Tool, ToolResult, i, obj, s

GLOBAL = 0  # user_id للإعدادات العامة


class Channel(Protocol):
    name: str

    async def send(self, uid: int, text: str, buttons: list[list[dict]] | None = None) -> bool | None: ...


class Harith:
    def __init__(self, settings: Settings):
        self.s = settings
        self.db = DB(settings.db_path)
        self.ai = AIRouter(settings)
        self.tools = build_registry()
        self.scheduler = Scheduler(self)
        self.reminders = Reminders(self)
        self.agent = Agent(self)
        self.channels: list[Channel] = []
        self.started_at = now_iso()
        if not self.s.secret_key:
            self.s.secret_key = self._persistent_secret()
        self._register_jobs()
        self._register_scheduled_tools()
        self.bootstrap_admin()

    # ——— إعداد أولي
    def _persistent_secret(self) -> str:
        key = self.db.get_setting(GLOBAL, "secret_key")
        if not key:
            key = secrets.token_urlsafe(32)
            self.db.set_setting(GLOBAL, "secret_key", key)
        return key

    def bootstrap_admin(self) -> None:
        if self.db.one("SELECT 1 FROM users LIMIT 1"):
            return
        if self.s.admin_password:
            create_user(self.db, "admin", self.s.admin_password, role="admin", display_name="المدير",
                        tz=self.s.timezone)
            self.db.log_event("info", "setup", "أُنشئ حساب المدير admin")

    # ——— الإيقاف الطارئ
    def is_paused(self, uid: int | None = None) -> bool:
        if self.db.get_setting(GLOBAL, "paused", False):
            return True
        return bool(uid and self.db.get_setting(uid, "paused", False))

    def set_paused(self, paused: bool, uid: int | None = None) -> dict:
        self.db.set_setting(uid or GLOBAL, "paused", paused)
        stopped = 0
        if paused:
            stopped = self.agent.cancel(uid=uid) if uid else self.agent.cancel()
        self.db.log_event("warning" if paused else "info", "kill-switch",
                          f"{'إيقاف' if paused else 'استئناف'} التنفيذ ({'عام' if not uid else f'مستخدم {uid}'})")
        return {"paused": paused, "stopped_runs": stopped}

    # ——— الإشعارات (لكل القنوات + صندوق الإشعارات)
    async def notify(self, uid: int, text: str, dedupe: str | None = None, kind: str = "notice",
                     buttons: list[list[dict]] | None = None) -> dict:
        out: dict[str, Any] = {}
        inbox_key = f"{dedupe}:inbox" if dedupe else None
        if not inbox_key or self.db.claim_once(inbox_key):
            self.db.insert("INSERT INTO messages(user_id,channel,role,content,created_at) VALUES(?,?,?,?,?)",
                           (uid, kind, "notice", text, now_iso()))
        out["inbox"] = True
        errors = []
        for ch in self.channels:
            key = f"{dedupe}:{ch.name}" if dedupe else None
            if key and self.db.one("SELECT 1 FROM processed_keys WHERE key=?", (key,)):
                out[ch.name] = True
                continue
            try:
                ok = await ch.send(uid, text, buttons)
            except Exception as e:  # noqa: BLE001
                ok = False
                errors.append(f"{ch.name}: {redact(str(e))[:200]}")
            if ok is None:   # المستخدم غير مرتبط بهذه القناة
                continue
            out[ch.name] = bool(ok)
            if ok and key:
                self.db.claim_once(key)
            elif not ok and not errors:
                errors.append(f"{ch.name}: لم يُرسل")
        if errors:
            out["failed"] = "؛ ".join(errors)
        return out

    async def notify_approval(self, uid: int, aid: int, summary: str) -> None:
        buttons = [[{"text": "✅ موافقة", "data": f"ap:{aid}:yes"}, {"text": "✖️ رفض", "data": f"ap:{aid}:no"}]]
        await self.notify(uid, f"🛡️ طلب موافقة #{aid}\n{summary}", dedupe=f"approval:{aid}", kind="approval",
                          buttons=buttons)

    async def decide_approval(self, uid: int, aid: int, approve: bool, channel: str = "web") -> dict:
        n = self.db.execute("UPDATE approvals SET status=?, decided_at=? WHERE id=? AND user_id=? AND status='pending'",
                            ("approved" if approve else "denied", now_iso(), aid, uid)).rowcount
        if not n:
            a = self.db.one("SELECT status FROM approvals WHERE id=? AND user_id=?", (aid, uid))
            return {"ok": False, "text": "الطلب غير موجود" if not a else f"الطلب سبق التعامل معه ({a['status']})"}
        if not approve:
            return {"ok": True, "text": f"رُفض الطلب #{aid} ولن يُنفّذ."}
        a = self.db.one("SELECT * FROM approvals WHERE id=?", (aid,))
        user = self.db.one("SELECT * FROM users WHERE id=?", (uid,))
        ctx = Ctx(app=self, user=user, channel=channel, run_id=f"approval-{aid}", approved=True)
        res = await self.tools.execute(ctx, a["tool"], json.loads(a["args"]))
        self.db.execute("UPDATE approvals SET status=?, result=? WHERE id=?",
                        ("executed" if res.ok else "failed", redact(res.text)[:2000], aid))
        return {"ok": res.ok, "text": ("✅ نُفّذ: " if res.ok else "❌ فشل التنفيذ: ") + res.text}

    # ——— المهام المجدولة
    def _register_jobs(self) -> None:
        sch = self.scheduler
        sch.register("reminder", self.reminders.fire)

        async def notify_job(job, p):
            r = await self.notify(job["user_id"], p["text"], dedupe=f"job:{job['id']}")
            if r.get("failed") and not any(v is True for k, v in r.items() if k not in ("inbox",)):
                from .scheduler import RetryLater
                raise RetryLater(r["failed"])
            return "أُرسل"

        async def briefing(job, p, evening=False):
            user = self.db.one("SELECT * FROM users WHERE id=? AND disabled=0", (job["user_id"],))
            if not user:
                return "مستخدم غير موجود"
            text = await self.briefing_text(user, evening)
            await self.notify(user["id"], text, dedupe=f"job:{job['id']}", kind="briefing")
            return "أُرسل الملخص"

        async def morning(job, p):
            return await briefing(job, p, False)

        async def evening(job, p):
            return await briefing(job, p, True)

        async def backup(job, p):
            return f"نسخة: {run_backup(self).name}"

        async def cleanup(job, p):
            return run_cleanup(self)

        async def agent_run(job, p):
            """إعادة تنفيذ طلب حُفظ أثناء انقطاع خدمة الذكاء الاصطناعي."""
            user = self.db.one("SELECT * FROM users WHERE id=?", (job["user_id"],))
            r = self.db.one("SELECT status FROM runs WHERE id=?", (p["run_id"],))
            if not user or (r and r["status"] in ("success", "cancelled", "partial")):
                return "تخطّي"
            res = await self.agent.run(user, p["text"], p.get("channel", "web"), run_id=p["run_id"], store_input=False)
            if res["status"] == "queued":
                from .scheduler import RetryLater
                raise RetryLater("ما زالت الخدمة غير متاحة")
            await self.notify(user["id"], f"✅ نفّذت طلبك المؤجل «{p['text'][:80]}»:\n{res['text']}",
                              dedupe=f"agent_run_done:{p['run_id']}")
            return res["status"]

        async def agent_prompt(job, p):
            """أمر مجدول بلغة طبيعية (مثل: كل صباح لخّص مهامي)."""
            user = self.db.one("SELECT * FROM users WHERE id=? AND disabled=0", (job["user_id"],))
            if not user:
                return "مستخدم غير موجود"
            if p.get("recur", "none") != "none":
                # من الموعد الأصلي (وليس وقت إعادة المحاولة)، ومتخطيًا ما فات أثناء التوقف
                nxt = next_after_now(p.get("at") or job["run_at"], p["recur"], user["timezone"])
                if nxt:
                    self.scheduler.enqueue(user["id"], "agent_prompt", {**p, "at": nxt}, nxt,
                                           dedupe_key=f"agent_prompt:{p['sid']}:{nxt}")
            if self.is_paused(user["id"]):
                return "تخطّي: التنفيذ موقوف"
            res = await self.agent.run(user, p["prompt"], "scheduled")
            await self.notify(user["id"], f"🗓️ {p.get('title') or 'مهمة مجدولة'}:\n{res['text']}",
                              dedupe=f"job:{job['id']}", kind="scheduled")
            return res["status"]

        for k, fn in {"notify": notify_job, "briefing_morning": morning, "briefing_evening": evening,
                      "backup": backup, "cleanup": cleanup, "agent_run": agent_run,
                      "agent_prompt": agent_prompt}.items():
            sch.register(k, fn)

    def _register_scheduled_tools(self) -> None:
        async def schedule_agent_task(ctx: Ctx, a: dict) -> ToolResult:
            prompt = (a.get("prompt") or "").strip()
            if not prompt:
                raise ValueError("نص الأمر مطلوب")
            at = parse_local(a["when"], ctx.tz)
            if at < utcnow():
                return ToolResult(False, "الوقت مضى؛ حدد وقتًا مستقبليًا")
            recur = a.get("recur") or "none"
            sid = secrets.token_hex(4)
            jid = self.scheduler.enqueue(ctx.uid, "agent_prompt", {"prompt": prompt, "recur": recur, "sid": sid,
                                                                   "title": a.get("title") or prompt[:40],
                                                                   "at": iso(at)},
                                         at, dedupe_key=f"agent_prompt:{sid}:{iso(at)}")
            ok = bool(self.db.one("SELECT 1 FROM jobs WHERE id=? AND status='pending'", (jid,)))
            return ToolResult(ok, "جُدولت المهمة", {"job_id": jid, "first_run": to_local_str(iso(at), ctx.tz),
                                                    "recur": recur}, verified=ok)

        async def list_scheduled(ctx: Ctx, a: dict) -> ToolResult:
            rows = self.db.all("SELECT id, kind, payload, run_at FROM jobs WHERE user_id=? AND status='pending' AND "
                               "kind IN ('agent_prompt','notify') ORDER BY run_at", (ctx.uid,))
            return ToolResult(True, f"{len(rows)} مهمة مجدولة", [
                {"id": r["id"], "what": json.loads(r["payload"]).get("title") or json.loads(r["payload"]).get("text"),
                 "recur": json.loads(r["payload"]).get("recur", "none"), "next": to_local_str(r["run_at"], ctx.tz)}
                for r in rows], verified=True)

        async def cancel_scheduled(ctx: Ctx, a: dict) -> ToolResult:
            n = self.db.execute("UPDATE jobs SET status='cancelled', finished_at=? WHERE id=? AND user_id=? AND "
                                "status='pending' AND kind IN ('agent_prompt','notify')",
                                (now_iso(), int(a["id"]), ctx.uid)).rowcount
            return ToolResult(bool(n), "أُلغيت المهمة المجدولة" if n else "لا توجد مهمة مجدولة بهذا الرقم",
                              verified=bool(n))

        self.tools.add(Tool("schedule_agent_task", "جدولة أمر يُنفّذه المساعد لاحقًا أو بشكل متكرر "
                            "(مثل: كل يوم 8 صباحًا لخّص المهام المتأخرة).",
                            obj({"prompt": s("الأمر بلغة طبيعية"), "when": s("أول تنفيذ: YYYY-MM-DDTHH:MM محلي"),
                                 "recur": s("", ["none", "daily", "weekdays", "weekly", "monthly"]),
                                 "title": s("عنوان قصير")}, ["prompt", "when"]), schedule_agent_task,
                            category="الجدولة"))
        self.tools.add(Tool("list_scheduled", "عرض المهام المجدولة.", obj(), list_scheduled, category="الجدولة"))
        self.tools.add(Tool("cancel_scheduled", "إلغاء مهمة مجدولة.", obj({"id": i("")}, ["id"]), cancel_scheduled,
                            category="الجدولة"))

    # ——— الملخص الصباحي والمسائي
    async def briefing_text(self, user: dict, evening: bool = False) -> str:
        data = day_overview(self.db, user["id"], user["timezone"])
        name = user.get("display_name") or user["username"]
        if self.ai.configured() and not self.is_paused(user["id"]):
            prompt = ("اكتب خلاصة مسائية قصيرة: ما أُنجز اليوم، ما تأخر، وأهم 3 أولويات للغد."
                      if evening else
                      "اكتب ملخصًا صباحيًا قصيرًا: تحية، أهم الأولويات، المتأخر، التذكيرات، واقتراح ترتيب لليوم.")
            try:
                resp = await self.ai.chat(
                    f"أنت الحارث، مساعد {name}. استخدم البيانات المعطاة فقط ولا تختلق شيئًا. نص قصير مناسب للجوال.",
                    [{"role": "user", "content": prompt + "\nالبيانات:\n" + json.dumps(data, ensure_ascii=False)}], [])
                if resp.text.strip():
                    return resp.text.strip()
            except Exception as e:  # noqa: BLE001
                self.db.log_event("warning", "briefing", redact(str(e))[:300])
        return template_briefing(name, data, evening)

    # ——— التشغيل
    async def start(self) -> None:
        self.scheduler.start()
        for ch in self.channels:
            start = getattr(ch, "start", None)
            if start:
                await start()

    async def stop(self) -> None:
        for ch in self.channels:
            stop = getattr(ch, "stop", None)
            if stop:
                await stop()
        await self.scheduler.stop()
        await self.ai.aclose()
        self.db.close()

    def health(self) -> dict:
        jobs = self.db.one("SELECT SUM(status='pending') p, SUM(status='failed') f, SUM(status='running') r FROM jobs")
        return {
            "ok": True, "started_at": self.started_at, "paused": self.is_paused(),
            "schema_version": self.db.schema_version, "scheduler_last_tick": self.scheduler.last_tick,
            "ai": {"provider": self.s.ai_provider, "model": self.ai.model_for(self.s.ai_provider),
                   "configured": self.ai.configured(), "fallback": self.s.ai_fallback_provider or None},
            "channels": {getattr(c, "name", "?"): getattr(c, "status", lambda: "unknown")() for c in self.channels},
            "telegram_configured": bool(self.s.telegram_bot_token),
            "search_configured": bool(self.s.tavily_api_key or self.s.brave_api_key or self.s.gemini_api_key),
            "email_configured": bool(self.s.smtp_host),
            "jobs": {"pending": jobs["p"] or 0, "failed": jobs["f"] or 0, "running": jobs["r"] or 0},
        }


def template_briefing(name: str, d: dict, evening: bool) -> str:
    lines = [f"{'مساء الخير' if evening else 'صباح الخير'} يا {name}."]
    if evening:
        lines.append(f"أنجزت اليوم {len(d['completed_today'])} مهمة.")
    if d["overdue"]:
        lines.append(f"⚠️ متأخر ({len(d['overdue'])}): " + "، ".join(t["title"] for t in d["overdue"][:5]))
    if d["due_today"]:
        lines.append("📌 اليوم: " + "، ".join(t["title"] for t in d["due_today"][:6]))
    if d["reminders_today"]:
        lines.append("🔔 تذكيرات: " + "، ".join(f"{r['when'][-5:]} {r['text']}" for r in d["reminders_today"][:6]))
    if d["deadlines_7_days"]:
        lines.append("⏳ مواعيد نهائية قريبة: " + "، ".join(x["content"][:40] for x in d["deadlines_7_days"][:4]))
    if len(lines) == 1 + (1 if evening else 0):
        lines.append("لا توجد مهام أو تذكيرات مسجلة لليوم.")
    return "\n".join(lines)


__all__ = ["Harith", "GLOBAL", "timedelta"]
