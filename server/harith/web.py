"""واجهة API ولوحة التحكم (Starlette)."""
from __future__ import annotations

import json
import mimetypes
from contextlib import asynccontextmanager
from datetime import timedelta
from pathlib import Path
from typing import Any, Callable

from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse, Response
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles

from .core import GLOBAL, Harith
from .db import iso, now_iso, utcnow
from .scheduler import run_backup
from .security import (RateLimiter, authenticate, create_user, end_session, hash_password, new_api_token,
                       new_link_code, new_session, redact, user_for_api_token, user_for_session, verify_password)
from .timeutil import local_day_bounds, to_local_str
from .tools import day_overview, file_path, habits_summary, project_report_data, save_user_file, search_memories
from .toolkit import Ctx

STATIC = Path(__file__).parent / "static"
COOKIE = "harith_session"


class HTTPError(Exception):
    def __init__(self, status: int, message: str):
        self.status, self.message = status, message


def J(data: Any, status: int = 200) -> JSONResponse:
    return JSONResponse(data, status_code=status)


class SecurityHeaders(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        resp = await call_next(request)
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["X-Frame-Options"] = "DENY"
        resp.headers["Referrer-Policy"] = "no-referrer"
        resp.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            "font-src 'self'; script-src 'self'; frame-ancestors 'none'")
        if request.url.path.startswith("/api/"):
            resp.headers["Cache-Control"] = "no-store"
        return resp


def create_app(app: Harith, manage_lifecycle: bool = True) -> Starlette:
    login_limiter = RateLimiter(8, 300)

    # ——— المصادقة
    def current_user(req: Request) -> dict:
        auth = req.headers.get("authorization", "")
        if auth.lower().startswith("bearer "):
            u = user_for_api_token(app.db, auth[7:].strip())
            if u:
                return u
            raise HTTPError(401, "مفتاح الوصول غير صالح")
        u = user_for_session(app.db, req.cookies.get(COOKIE))
        if not u:
            raise HTTPError(401, "سجّل الدخول أولًا")
        # حماية CSRF: الطلبات المعدِّلة من المتصفح يجب أن تحمل ترويسة خاصة (لا ترسلها النماذج الخارجية)
        if req.method not in ("GET", "HEAD") and req.headers.get("x-harith") != "1":
            raise HTTPError(403, "طلب مرفوض")
        return u

    def admin(u: dict) -> None:
        if u["role"] != "admin":
            raise HTTPError(403, "هذه العملية للمدير فقط")

    def ep(fn: Callable, auth: bool = True):
        async def handler(req: Request):
            try:
                if auth:
                    u = current_user(req)
                    return await fn(req, u)
                return await fn(req)
            except HTTPError as e:
                return J({"error": e.message}, e.status)
            except (ValueError, KeyError, TypeError) as e:
                return J({"error": f"مدخلات غير صحيحة: {e}"}, 400)
            except json.JSONDecodeError:
                return J({"error": "JSON غير صالح"}, 400)
            except Exception as e:  # noqa: BLE001
                app.db.log_event("error", f"api:{req.url.path}", redact(repr(e))[:800])
                return J({"error": "خطأ داخلي، سُجّل في السجلات"}, 500)
        return handler

    async def body(req: Request) -> dict:
        raw = await req.body()
        return json.loads(raw) if raw else {}

    # ——— عام
    async def health(req: Request):
        return J({"ok": True, "paused": app.is_paused(), "time": now_iso()})

    async def setup_state(req: Request):
        return J({"needs_setup": app.db.one("SELECT 1 FROM users LIMIT 1") is None})

    async def setup(req: Request):
        if app.db.one("SELECT 1 FROM users LIMIT 1"):
            raise HTTPError(403, "تم الإعداد مسبقًا")
        b = await body(req)
        uid = create_user(app.db, b.get("username") or "admin", b["password"], role="admin",
                          display_name=b.get("display_name") or "", tz=app.s.timezone)
        resp = J({"ok": True})
        _set_cookie(req, resp, new_session(app.db, uid))
        return resp

    async def login(req: Request):
        ip = req.client.host if req.client else "?"
        if not login_limiter.allow(ip):
            raise HTTPError(429, "محاولات كثيرة، انتظر 5 دقائق")
        b = await body(req)
        u = authenticate(app.db, b.get("username", ""), b.get("password", ""))
        if not u:
            app.db.log_event("warning", "auth", f"محاولة دخول فاشلة من {ip}")
            raise HTTPError(401, "اسم المستخدم أو كلمة المرور غير صحيحة")
        resp = J({"ok": True, "user": _public_user(u)})
        _set_cookie(req, resp, new_session(app.db, u["id"]))
        return resp

    def _set_cookie(req: Request, resp: Response, token: str) -> None:
        resp.set_cookie(COOKIE, token, max_age=30 * 86400, httponly=True, samesite="strict",
                        secure=req.url.scheme == "https", path="/")

    async def logout(req: Request, u: dict):
        tok = req.cookies.get(COOKIE)
        if tok:
            end_session(app.db, tok)
        resp = J({"ok": True})
        resp.delete_cookie(COOKIE, path="/")
        return resp

    async def me(req: Request, u: dict):
        return J({"user": _public_user(u), "paused": app.is_paused(u["id"]), "global_paused": app.is_paused()})

    # ——— الرئيسية
    async def overview(req: Request, u: dict):
        db, uid, tzn = app.db, u["id"], u["timezone"]
        start, _ = local_day_bounds(tzn)
        counts = db.one("SELECT SUM(status IN ('open','in_progress')) open_, SUM(status='done') done FROM tasks "
                        "WHERE user_id=?", (uid,))
        return J({
            "day": day_overview(db, uid, tzn),
            "counts": {"open": counts["open_"] or 0, "done": counts["done"] or 0,
                       "memories": db.one("SELECT COUNT(*) c FROM memories WHERE user_id=?", (uid,))["c"],
                       "projects": db.one("SELECT COUNT(*) c FROM projects WHERE user_id=? AND status='active'",
                                          (uid,))["c"],
                       "approvals": db.one("SELECT COUNT(*) c FROM approvals WHERE user_id=? AND status='pending'",
                                           (uid,))["c"]},
            "cost_today": round(app.agent.cost_today(uid), 4),
            "health": app.health(),
            "recent_runs": [dict(r, created_at=to_local_str(r["created_at"], tzn)) for r in db.all(
                "SELECT id, channel, substr(input,1,120) input, status, created_at FROM runs WHERE user_id=? "
                "ORDER BY created_at DESC LIMIT 6", (uid,))],
            "since": start,
        })

    # ——— المحادثة
    async def chat(req: Request, u: dict):
        b = await body(req)
        text = (b.get("text") or "").strip()
        if not text:
            raise HTTPError(400, "اكتب رسالة")
        channel = b.get("channel") if b.get("channel") in ("web", "android") else "web"
        return J(await app.agent.run(u, text[:8000], channel))

    async def messages(req: Request, u: dict):
        q = req.query_params.get("q")
        before = int(req.query_params.get("before") or 10**12)
        if q:
            from .tools import fts_query
            rows = app.db.all("SELECT m.* FROM messages_fts f JOIN messages m ON m.id=f.rowid WHERE messages_fts "
                              "MATCH ? AND m.user_id=? ORDER BY m.id DESC LIMIT 100", (fts_query(q) or '""', u["id"]))
        else:
            rows = app.db.all("SELECT * FROM messages WHERE user_id=? AND id<? ORDER BY id DESC LIMIT 60",
                              (u["id"], before))
        for r in rows:
            r["created_local"] = to_local_str(r["created_at"], u["timezone"])
        return J(list(reversed(rows)) if not q else rows)

    async def notifications(req: Request, u: dict):
        after = int(req.query_params.get("after") or 0)
        rows = app.db.all("SELECT id, channel AS kind, content, created_at FROM messages WHERE user_id=? AND "
                          "role='notice' AND id>? ORDER BY id LIMIT 50", (u["id"], after))
        return J(rows)

    # ——— المهام
    async def tasks_list(req: Request, u: dict):
        f = req.query_params.get("filter", "open")
        res = await app.tools.get("list_tasks").handler(Ctx(app, u), {"filter": f,
                                                                      "project": req.query_params.get("project")})
        return J(res.data)

    async def tasks_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "add_task", await body(req))
        return J({"ok": res.ok, "message": res.text, "task": res.data}, 200 if res.ok else 400)

    async def tasks_update(req: Request, u: dict):
        b = await body(req)
        b["id"] = int(req.path_params["id"])
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "update_task", b)
        return J({"ok": res.ok, "message": res.text, "task": res.data}, 200 if res.ok else 400)

    async def tasks_delete(req: Request, u: dict):
        n = app.db.execute("DELETE FROM tasks WHERE id=? AND user_id=?", (int(req.path_params["id"]), u["id"])).rowcount
        return J({"ok": bool(n)})

    # ——— التذكيرات
    async def reminders_list(req: Request, u: dict):
        rows = app.db.all("SELECT * FROM reminders WHERE user_id=? ORDER BY CASE status WHEN 'pending' THEN 0 ELSE 1 "
                          "END, due_at LIMIT 100", (u["id"],))
        for r in rows:
            r["when"] = to_local_str(r["due_at"], u["timezone"])
        return J(rows)

    async def reminders_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "create_reminder", await body(req))
        return J({"ok": res.ok, "message": res.text, "reminder": res.data}, 200 if res.ok else 400)

    async def reminders_cancel(req: Request, u: dict):
        return J({"ok": app.reminders.cancel(u["id"], int(req.path_params["id"]))})

    # ——— المشاريع
    async def projects_list(req: Request, u: dict):
        res = await app.tools.get("list_projects").handler(Ctx(app, u), {})
        return J(res.data)

    async def projects_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "create_project", await body(req))
        return J({"ok": res.ok, "message": res.text, "project": res.data}, 200 if res.ok else 400)

    async def project_get(req: Request, u: dict):
        data = project_report_data(app.db, u["id"], int(req.path_params["id"]), u["timezone"])
        data["files"] = app.db.all("SELECT id, name, size FROM files WHERE project_id=? AND user_id=?",
                                   (int(req.path_params["id"]), u["id"]))
        return J(data)

    async def project_entry(req: Request, u: dict):
        b = await body(req)
        b["project"] = int(req.path_params["id"])
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "add_project_entry", b)
        return J({"ok": res.ok, "message": res.text}, 200 if res.ok else 400)

    # ——— الذاكرة
    async def memory_list(req: Request, u: dict):
        q = req.query_params.get("q")
        if q:
            return J(search_memories(app.db, u["id"], q, 50))
        return J(app.db.all("SELECT * FROM memories WHERE user_id=? ORDER BY id DESC LIMIT 300", (u["id"],)))

    async def memory_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "remember", await body(req))
        return J({"ok": res.ok, "message": res.text}, 200 if res.ok else 400)

    async def memory_update(req: Request, u: dict):
        b = await body(req)
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "update_memory",
                                      {"id": int(req.path_params["id"]), "content": b.get("content", "")})
        return J({"ok": res.ok, "message": res.text}, 200 if res.ok else 400)

    async def memory_delete(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "forget_memory", {"id": int(req.path_params["id"])})
        return J({"ok": res.ok, "message": res.text})

    # ——— الملفات
    async def files_list(req: Request, u: dict):
        return J(app.db.all("SELECT id, name, size, mime, project_id, created_at FROM files WHERE user_id=? "
                            "ORDER BY id DESC", (u["id"],)))

    async def files_upload(req: Request, u: dict):
        name = req.query_params.get("name") or "file"
        data = await req.body()
        pid = req.query_params.get("project")
        fid = save_user_file(app, u["id"], name, data, int(pid) if pid else None)
        return J({"ok": True, "id": fid})

    async def files_download(req: Request, u: dict):
        f = app.db.one("SELECT * FROM files WHERE id=? AND user_id=?", (int(req.path_params["id"]), u["id"]))
        if not f:
            raise HTTPError(404, "غير موجود")
        return FileResponse(file_path(app, u["id"], f), filename=f["name"],
                            media_type=f["mime"] or mimetypes.guess_type(f["name"])[0] or "application/octet-stream")

    async def files_delete(req: Request, u: dict):
        f = app.db.one("SELECT * FROM files WHERE id=? AND user_id=?", (int(req.path_params["id"]), u["id"]))
        if not f:
            raise HTTPError(404, "غير موجود")
        file_path(app, u["id"], f).unlink(missing_ok=True)
        app.db.execute("DELETE FROM files WHERE id=?", (f["id"],))
        app.db.log_event("info", "files", f"حذف المستخدم {u['id']} الملف {f['name']}")
        return J({"ok": True})

    # ——— العادات
    async def habits_list(req: Request, u: dict):
        return J(habits_summary(app.db, u["id"], u["timezone"]))

    async def habits_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "add_habit", await body(req))
        return J({"ok": res.ok, "message": res.text})

    async def habits_check(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "check_habit",
                                      {"habit": req.path_params["id"]})
        return J({"ok": res.ok, "message": res.text})

    # ——— المجدولة والسجلات
    async def jobs_list(req: Request, u: dict):
        rows = app.db.all("SELECT id, kind, payload, run_at, status, attempts, last_error FROM jobs WHERE "
                          "(user_id=? OR (? AND user_id IS NULL)) AND status IN ('pending','running','failed') "
                          "ORDER BY run_at LIMIT 200", (u["id"], u["role"] == "admin"))
        for r in rows:
            r["when"] = to_local_str(r["run_at"], u["timezone"])
            p = json.loads(r.pop("payload") or "{}")
            r["label"] = p.get("title") or p.get("text") or p.get("prompt") or ""
        return J(rows)

    async def jobs_cancel(req: Request, u: dict):
        n = app.db.execute("UPDATE jobs SET status='cancelled', finished_at=? WHERE id=? AND user_id=? AND "
                           "status='pending'", (now_iso(), int(req.path_params["id"]), u["id"])).rowcount
        return J({"ok": bool(n)})

    async def operations(req: Request, u: dict):
        rows = app.db.all("SELECT * FROM operations WHERE user_id=? ORDER BY id DESC LIMIT 200", (u["id"],))
        for r in rows:
            r["created_local"] = to_local_str(r["created_at"], u["timezone"])
        return J(rows)

    async def runs_list(req: Request, u: dict):
        rows = app.db.all("SELECT * FROM runs WHERE user_id=? ORDER BY created_at DESC LIMIT 100", (u["id"],))
        for r in rows:
            r["created_local"] = to_local_str(r["created_at"], u["timezone"])
            r["running"] = r["id"] in app.agent.running
        return J(rows)

    async def runs_cancel(req: Request, u: dict):
        rid = req.path_params.get("id")
        return J({"stopped": app.agent.cancel(run_id=rid, uid=u["id"])})

    async def events(req: Request, u: dict):
        admin(u)
        rows = app.db.all("SELECT * FROM event_log ORDER BY id DESC LIMIT 200")
        for r in rows:
            r["created_local"] = to_local_str(r["created_at"], u["timezone"])
        return J(rows)

    # ——— الموافقات
    async def approvals_list(req: Request, u: dict):
        rows = app.db.all("SELECT * FROM approvals WHERE user_id=? ORDER BY id DESC LIMIT 100", (u["id"],))
        for r in rows:
            r["created_local"] = to_local_str(r["created_at"], u["timezone"])
            r.pop("args", None)
        return J(rows)

    async def approvals_decide(req: Request, u: dict):
        b = await body(req)
        return J(await app.decide_approval(u["id"], int(req.path_params["id"]), bool(b.get("approve")), "web"))

    # ——— الاستخدام والتكلفة
    async def usage(req: Request, u: dict):
        since = iso(utcnow() - timedelta(days=30))
        scope = "1=1" if u["role"] == "admin" and req.query_params.get("all") else "user_id=?"
        params: list = [] if scope == "1=1" else [u["id"]]
        by_day = app.db.all(f"SELECT substr(created_at,1,10) day, SUM(input_tokens) tin, SUM(output_tokens) tout, "
                            f"ROUND(SUM(cost_usd),4) cost, COUNT(*) calls FROM usage WHERE {scope} AND created_at>=? "
                            f"GROUP BY day ORDER BY day", (*params, since))
        by_model = app.db.all(f"SELECT provider, model, SUM(input_tokens) tin, SUM(output_tokens) tout, "
                              f"ROUND(SUM(cost_usd),4) cost, COUNT(*) calls FROM usage WHERE {scope} AND created_at>=? "
                              f"GROUP BY provider, model ORDER BY cost DESC", (*params, since))
        return J({"by_day": by_day, "by_model": by_model, "today": round(app.agent.cost_today(u["id"]), 4),
                  "limit": app.db.get_setting(u["id"], "daily_cost_limit_usd", app.s.daily_cost_limit_usd)})

    # ——— الإعدادات
    SETTING_KEYS = {"briefing_time": str, "evening_time": str, "briefing_morning_enabled": bool,
                    "briefing_evening_enabled": bool, "memory_enabled": bool, "memory_retention_days": int,
                    "history_retention_days": int, "daily_cost_limit_usd": float, "voice_replies": bool}

    async def settings_get(req: Request, u: dict):
        defaults = {"briefing_time": "07:30", "evening_time": "21:00", "briefing_morning_enabled": True,
                    "briefing_evening_enabled": True, "memory_enabled": True, "memory_retention_days": 0,
                    "history_retention_days": app.s.history_retention_days,
                    "daily_cost_limit_usd": app.s.daily_cost_limit_usd, "voice_replies": False}
        vals = {k: app.db.get_setting(u["id"], k, v) for k, v in defaults.items()}
        return J({"settings": vals, "user": _public_user(u),
                  "telegram": app.db.all("SELECT chat_id, tg_username, created_at FROM telegram_links WHERE user_id=?",
                                         (u["id"],)),
                  "tokens": app.db.all("SELECT id, name, created_at, last_used FROM api_tokens WHERE user_id=?",
                                       (u["id"],)),
                  "telegram_bot": next((getattr(c, "bot_username", "") for c in app.channels
                                        if getattr(c, "name", "") == "telegram"), "")})

    async def settings_put(req: Request, u: dict):
        b = await body(req)
        for k, typ in SETTING_KEYS.items():
            if k in b:
                v = typ(b[k])
                if k in ("briefing_time", "evening_time") and not _valid_hhmm(v):
                    raise HTTPError(400, f"وقت غير صحيح: {v}")
                app.db.set_setting(u["id"], k, v)
        if b.get("display_name"):
            app.db.execute("UPDATE users SET display_name=? WHERE id=?", (str(b["display_name"])[:60], u["id"]))
        if b.get("timezone"):
            from zoneinfo import ZoneInfo
            try:
                ZoneInfo(str(b["timezone"]))
            except Exception:
                raise HTTPError(400, "منطقة زمنية غير معروفة (مثال: Asia/Riyadh)")
            app.db.execute("UPDATE users SET timezone=? WHERE id=?", (b["timezone"], u["id"]))
        if any(k in b for k in ("briefing_time", "evening_time", "briefing_morning_enabled",
                                "briefing_evening_enabled")):
            app.db.execute("DELETE FROM jobs WHERE user_id=? AND status='pending' AND kind IN "
                           "('briefing_morning','briefing_evening')", (u["id"],))
            app.scheduler.ensure_recurring()
        return J({"ok": True})

    async def password_change(req: Request, u: dict):
        b = await body(req)
        if not verify_password(b.get("current", ""), u["password_hash"]):
            raise HTTPError(403, "كلمة المرور الحالية غير صحيحة")
        if len(b.get("new", "")) < 8:
            raise HTTPError(400, "كلمة المرور الجديدة 8 أحرف على الأقل")
        app.db.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(b["new"]), u["id"]))
        app.db.execute("DELETE FROM sessions WHERE user_id=?", (u["id"],))
        return J({"ok": True})

    async def link_code(req: Request, u: dict):
        return J({"code": new_link_code(app.db, u["id"]), "expires_minutes": 10})

    async def unlink_telegram(req: Request, u: dict):
        app.db.execute("DELETE FROM telegram_links WHERE user_id=? AND chat_id=?",
                       (u["id"], int(req.path_params["chat"])))
        return J({"ok": True})

    async def tokens_create(req: Request, u: dict):
        b = await body(req)
        return J({"token": new_api_token(app.db, u["id"], b.get("name") or "تطبيق الجوال")})

    async def tokens_delete(req: Request, u: dict):
        app.db.execute("DELETE FROM api_tokens WHERE id=? AND user_id=?", (int(req.path_params["id"]), u["id"]))
        return J({"ok": True})

    # ——— الإيقاف الطارئ
    async def pause(req: Request, u: dict):
        b = await body(req)
        if b.get("scope") == "all":
            admin(u)
            return J(app.set_paused(bool(b.get("paused")), None))
        return J(app.set_paused(bool(b.get("paused")), u["id"]))

    # ——— البيانات: تصدير وحذف
    async def export(req: Request, u: dict):
        uid = u["id"]
        data = {"exported_at": now_iso(), "user": _public_user(u)}
        for t in ("tasks", "reminders", "memories", "projects", "project_entries", "habits", "messages", "approvals",
                  "operations", "files"):
            data[t] = app.db.all(f"SELECT * FROM {t} WHERE user_id=?", (uid,))
        data["settings"] = app.db.all("SELECT key, value FROM settings_kv WHERE user_id=?", (uid,))
        return Response(json.dumps(data, ensure_ascii=False, indent=1, default=str), media_type="application/json",
                        headers={"Content-Disposition": 'attachment; filename="harith-export.json"'})

    async def wipe(req: Request, u: dict):
        b = await body(req)
        if not verify_password(b.get("password", ""), u["password_hash"]):
            raise HTTPError(403, "أدخل كلمة المرور لتأكيد الحذف")
        scope = b.get("scope")
        uid = u["id"]
        if scope == "history":
            n = app.db.execute("DELETE FROM messages WHERE user_id=?", (uid,)).rowcount
        elif scope == "memories":
            n = app.db.execute("DELETE FROM memories WHERE user_id=?", (uid,)).rowcount
        elif scope == "all":
            n = 0
            for t in ("messages", "memories", "reminders", "tasks", "project_entries", "projects", "habits",
                      "approvals", "operations", "runs", "usage"):
                n += app.db.execute(f"DELETE FROM {t} WHERE user_id=?", (uid,)).rowcount
            for f in app.db.all("SELECT * FROM files WHERE user_id=?", (uid,)):
                file_path(app, uid, f).unlink(missing_ok=True)
            n += app.db.execute("DELETE FROM files WHERE user_id=?", (uid,)).rowcount
            app.scheduler.cancel_jobs(uid)
        else:
            raise HTTPError(400, "نطاق غير معروف")
        app.db.log_event("warning", "privacy", f"حذف المستخدم {uid} بياناته ({scope}): {n} سجل")
        return J({"ok": True, "deleted": n})

    # ——— المدير
    async def users_list(req: Request, u: dict):
        admin(u)
        return J([_public_user(x) for x in app.db.all("SELECT * FROM users ORDER BY id")])

    async def users_create(req: Request, u: dict):
        admin(u)
        b = await body(req)
        uid = create_user(app.db, b["username"], b["password"], role="admin" if b.get("role") == "admin" else "user",
                          display_name=b.get("display_name") or "", tz=b.get("timezone") or app.s.timezone)
        return J({"ok": True, "id": uid})

    async def users_update(req: Request, u: dict):
        admin(u)
        b = await body(req)
        uid = int(req.path_params["id"])
        if uid == u["id"] and (b.get("disabled") or b.get("role") == "user"):
            raise HTTPError(400, "لا يمكنك تعطيل حسابك أو إزالة صلاحية المدير عن نفسك")
        if "disabled" in b:
            app.db.execute("UPDATE users SET disabled=? WHERE id=?", (1 if b["disabled"] else 0, uid))
            if b["disabled"]:
                app.db.execute("DELETE FROM sessions WHERE user_id=?", (uid,))
        if b.get("role") in ("admin", "user"):
            app.db.execute("UPDATE users SET role=? WHERE id=?", (b["role"], uid))
        if b.get("password"):
            if len(b["password"]) < 8:
                raise HTTPError(400, "كلمة المرور 8 أحرف على الأقل")
            app.db.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(b["password"]), uid))
        return J({"ok": True})

    async def backups_list(req: Request, u: dict):
        admin(u)
        return J([{"name": p.name, "size": p.stat().st_size} for p in sorted(app.s.backups_dir.glob("harith-*.db"),
                                                                              reverse=True)])

    async def backups_create(req: Request, u: dict):
        admin(u)
        p = run_backup(app)
        return J({"ok": True, "name": p.name, "size": p.stat().st_size})

    # ——— التكاملات والأدوات
    async def integrations(req: Request, u: dict):
        h = app.health()
        s = app.s
        items = [
            {"id": "ai", "name": "الذكاء الاصطناعي", "status": "ok" if h["ai"]["configured"] else "setup",
             "detail": f"{h['ai']['provider']} / {h['ai']['model']}" + (f" — احتياطي: {s.ai_fallback_provider}"
                                                                          if s.ai_fallback_provider else ""),
             "setup": "AI_PROVIDER و GEMINI_API_KEY (أو OPENAI_API_KEY / ANTHROPIC_API_KEY / OPENROUTER_API_KEY)"},
            {"id": "telegram", "name": "تيليجرام", "status": ("ok" if h["channels"].get("telegram") == "متصل" else
                                                            "error" if "telegram" in h["channels"] else "setup"),
             "detail": h["channels"].get("telegram", "غير مُعدّ"), "setup": "TELEGRAM_BOT_TOKEN من @BotFather"},
            {"id": "search", "name": "البحث في الإنترنت", "status": "ok" if h["search_configured"] else "setup",
             "detail": "Tavily" if s.tavily_api_key else "Brave" if s.brave_api_key else
             "Gemini (بحث Google)" if s.gemini_api_key else "غير مُعدّ", "setup": "TAVILY_API_KEY أو BRAVE_API_KEY"},
            {"id": "email", "name": "البريد (إرسال بعد الموافقة)", "status": "ok" if h["email_configured"] else "setup",
             "detail": s.smtp_host or "غير مُعدّ", "setup": "SMTP_HOST, SMTP_USER, SMTP_PASSWORD (كلمة مرور تطبيق)"},
            {"id": "voice", "name": "الصوت (تحويل وتحدث)",
             "status": "ok" if (s.gemini_api_key or s.openai_api_key) else "setup",
             "detail": "Gemini / OpenAI", "setup": "يعمل بمفتاح Gemini أو OpenAI"},
            {"id": "calendar", "name": "التقويم والمكالمات والرسائل", "status": "app",
             "detail": "عبر تطبيق رفيق على الجوال (صلاحيات أندرويد الرسمية)", "setup": ""},
            {"id": "clickup", "name": "ClickUp", "status": "planned",
             "detail": "غير منفّذ بعد — المشاريع تُدار داخليًا الآن", "setup": ""},
        ]
        return J(items)

    async def tools_catalog(req: Request, u: dict):
        return J(app.tools.catalog())

    async def sync_ep(req: Request, u: dict):
        from .sync import sync
        b = await body(req)  # اقرأ الطلب كاملًا قبل فتح المعاملة
        with app.db.tx():
            return J(sync(app.db, u, b))

    async def index(req: Request):
        return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})

    R = lambda path, fn, methods=("GET",), auth=True: Route(path, ep(fn, auth), methods=list(methods))  # noqa: E731
    routes = [
        Route("/", index), Route("/api/health", health),
        R("/api/setup-state", setup_state, auth=False), R("/api/setup", setup, ["POST"], auth=False),
        R("/api/login", login, ["POST"], auth=False), R("/api/logout", logout, ["POST"]), R("/api/me", me),
        R("/api/overview", overview), R("/api/chat", chat, ["POST"]), R("/api/messages", messages),
        R("/api/notifications", notifications),
        R("/api/tasks", tasks_list), R("/api/tasks", tasks_create, ["POST"]),
        R("/api/tasks/{id:int}", tasks_update, ["PATCH"]), R("/api/tasks/{id:int}", tasks_delete, ["DELETE"]),
        R("/api/reminders", reminders_list), R("/api/reminders", reminders_create, ["POST"]),
        R("/api/reminders/{id:int}", reminders_cancel, ["DELETE"]),
        R("/api/projects", projects_list), R("/api/projects", projects_create, ["POST"]),
        R("/api/projects/{id:int}", project_get), R("/api/projects/{id:int}/entries", project_entry, ["POST"]),
        R("/api/memory", memory_list), R("/api/memory", memory_create, ["POST"]),
        R("/api/memory/{id:int}", memory_update, ["PATCH"]), R("/api/memory/{id:int}", memory_delete, ["DELETE"]),
        R("/api/files", files_list), R("/api/files", files_upload, ["POST"]),
        R("/api/files/{id:int}", files_download), R("/api/files/{id:int}", files_delete, ["DELETE"]),
        R("/api/habits", habits_list), R("/api/habits", habits_create, ["POST"]),
        R("/api/habits/{id:int}/check", habits_check, ["POST"]),
        R("/api/jobs", jobs_list), R("/api/jobs/{id:int}", jobs_cancel, ["DELETE"]),
        R("/api/operations", operations), R("/api/runs", runs_list), R("/api/runs/cancel", runs_cancel, ["POST"]),
        R("/api/runs/{id}/cancel", runs_cancel, ["POST"]), R("/api/events", events),
        R("/api/approvals", approvals_list), R("/api/approvals/{id:int}", approvals_decide, ["POST"]),
        R("/api/usage", usage), R("/api/settings", settings_get), R("/api/settings", settings_put, ["PUT"]),
        R("/api/password", password_change, ["POST"]), R("/api/telegram/link-code", link_code, ["POST"]),
        R("/api/telegram/{chat:int}", unlink_telegram, ["DELETE"]),
        R("/api/tokens", tokens_create, ["POST"]), R("/api/tokens/{id:int}", tokens_delete, ["DELETE"]),
        R("/api/pause", pause, ["POST"]), R("/api/export", export), R("/api/data", wipe, ["DELETE"]),
        R("/api/users", users_list), R("/api/users", users_create, ["POST"]),
        R("/api/users/{id:int}", users_update, ["PATCH"]),
        R("/api/backups", backups_list), R("/api/backups", backups_create, ["POST"]),
        R("/api/integrations", integrations), R("/api/tools", tools_catalog), R("/api/sync", sync_ep, ["POST"]),
        Mount("/static", StaticFiles(directory=str(STATIC)), name="static"),
    ]

    @asynccontextmanager
    async def lifespan(_):
        if manage_lifecycle:
            await app.start()
        yield
        if manage_lifecycle:
            await app.stop()

    web = Starlette(routes=routes, middleware=[Middleware(SecurityHeaders)], lifespan=lifespan)
    web.state.harith = app
    return web


def _public_user(u: dict) -> dict:
    return {k: u[k] for k in ("id", "username", "display_name", "role", "timezone", "disabled", "created_at") if k in u}


def _valid_hhmm(v: str) -> bool:
    try:
        h, m = v.split(":")
        return 0 <= int(h) < 24 and 0 <= int(m) < 60 and len(m) == 2
    except ValueError:
        return False
