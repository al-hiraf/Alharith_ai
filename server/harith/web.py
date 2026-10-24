"""واجهة API ولوحة التحكم (Starlette)."""
from __future__ import annotations

import hmac
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
B_CATEGORIES = {"الأعمال", "المالية", "الفواتير", "العملاء", "الاجتماعات", "التصدير", "المشاريع", "المهام"}
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
    # حدود عامة تحمي الخادم عند ١٠٠ مستخدم: لكل مستخدم، ولكل عنوان بلا دخول، وأضيق على المحادثة (تكلّف ذكاءً)
    user_limiter, anon_limiter, ai_limiter = RateLimiter(300, 60), RateLimiter(120, 60), RateLimiter(20, 60)

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
                    if not user_limiter.allow(f"u{u['id']}") or (
                            req.url.path == "/api/chat" and not ai_limiter.allow(f"u{u['id']}")):
                        raise HTTPError(429, "طلبات كثيرة خلال دقيقة، انتظر قليلًا ثم حاول")
                    return await fn(req, u)
                ip = req.client.host if req.client else "?"
                if not anon_limiter.allow(ip):
                    raise HTTPError(429, "طلبات كثيرة، انتظر دقيقة")
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

    def _setup_guard(req: Request) -> None:
        # على خادم عام: الإعداد الأول يحتاج رمزًا يطبعه أمر التثبيت (يمنع أي أحد يسبقك للإعداد)
        import os as _os
        want = _os.environ.get("RAFIQ_SETUP_TOKEN", "").strip()
        if want and not hmac.compare_digest(req.headers.get("x-setup-token", ""), want):
            raise HTTPError(403, "افتح رابط الإعداد اللي ظهر لك في نهاية أمر التثبيت")

    async def setup(req: Request):
        _setup_guard(req)
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
        from . import team
        from .pm import health_data
        res = await app.tools.get("list_projects").handler(Ctx(app, u), {})
        rows = res.data
        for r in rows:
            p = app.db.one("SELECT start_on, end_on, owner FROM projects WHERE id=?", (r["id"],))
            r.update(p or {})
            r["role"] = "owner"
            r["members"] = app.db.one("SELECT COUNT(*) n FROM project_members WHERE project_id=?", (r["id"],))["n"]
        # مشاريع الآخرين التي أنا عضو في فريقها (بلا مالية)
        for r in team.shared_projects(app.db, u["id"]):
            r["role"], r["shared"] = "member", True
            rows.append(r)
        for r in rows:
            try:
                owner = r.get("owner_id") or u["id"]
                h = health_data(app.db, owner, r["id"], u["timezone"])
                r["health"], r["health_ar"], r["progress"] = h["level"], h["level_ar"], h["progress"]
            except ValueError:
                pass
        return J(rows)

    def _pacc(req: Request, u: dict) -> tuple[int, int, str]:
        """(رقم المشروع، صاحبه، دوري) — أو 404 إن لم يكن لي وصول."""
        from .team import access
        pid = int(req.path_params["id"])
        acc = access(app.db, u["id"], pid)
        if not acc:
            raise HTTPError(404, "المشروع غير موجود")
        return pid, acc[0], acc[1]

    async def project_board(req: Request, u: dict):
        from .pm import board_data
        pid, owner, role = _pacc(req, u)
        return J({**board_data(app.db, owner, pid, u["timezone"]), "role": role, "me": u["id"]})

    async def project_timeline(req: Request, u: dict):
        from .pm import timeline_data
        pid, owner, role = _pacc(req, u)
        return J({**timeline_data(app.db, owner, pid, u["timezone"]), "role": role})

    async def project_health(req: Request, u: dict):
        from .pm import health_data, workload_data
        pid, owner, role = _pacc(req, u)
        h = health_data(app.db, owner, pid, u["timezone"])
        if role != "owner":   # الميزانية والمصروفات لصاحب المشروع فقط
            h["budget_percent"] = None
            h["reasons"] = [x for x in h["reasons"] if "الميزانية" not in x]
        return J({"health": h, "workload": workload_data(app.db, owner, u["timezone"], pid), "role": role})

    # ——— الفريق
    async def project_team(req: Request, u: dict):
        from . import team
        pid, owner, role = _pacc(req, u)
        return J({"role": role, "me": u["id"], "members": team.members(app.db, pid),
                  "activity": team.activity(app.db, pid, u["timezone"])})

    def _public_url(req: Request | None = None) -> str:
        import os as _os
        fixed = _os.environ.get("RAFIQ_PUBLIC_URL", "").strip().rstrip("/")
        if fixed.startswith("https://"):
            return fixed  # خادم دائم برابط ثابت (VPS)
        f = app.s.data_dir / "tunnel-url.txt"
        if f.exists():
            t = f.read_text(encoding="utf-8").strip().rstrip("/")
            if t:
                return t
        # خلف استضافة توفّر HTTPS (Botkeep وغيرها): الرابط الذي فُتحت منه الصفحة هو الرابط العام
        if req is not None:
            proto = (req.headers.get("x-forwarded-proto") or req.url.scheme or "").split(",")[0].strip()
            host = (req.headers.get("x-forwarded-host") or req.headers.get("host") or "").split(",")[0].strip()
            if proto == "https" and host and "." in host:
                return f"https://{host}"
        return ""

    async def project_invite(req: Request, u: dict):
        from . import team
        pid, owner, role = _pacc(req, u)
        if role != "owner":
            raise HTTPError(403, "صاحب المشروع فقط يدعو أعضاء")
        inv = team.new_invite(app.db, pid, u["id"])
        base = _public_url(req) or str(req.base_url).rstrip("/")
        inv["link"] = f"{base}/join/{inv['code']}"
        inv["expires"] = to_local_str(inv["expires_at"], u["timezone"])
        inv["local_only"] = not _public_url(req)
        team.log(app.db, pid, u["id"], team.display(u), "أنشأ رابط دعوة للفريق")
        return J(inv)

    async def project_member_add(req: Request, u: dict):
        from . import team
        pid, owner, role = _pacc(req, u)
        if role != "owner":
            raise HTTPError(403, "صاحب المشروع فقط يضيف أعضاء")
        b = await body(req)
        who = app.db.one("SELECT * FROM users WHERE (username=? OR id=?) AND disabled=0",
                         (str(b.get("username", "")).strip(), int(b.get("user_id") or 0)))
        if not who:
            raise HTTPError(404, "لا يوجد مستخدم بهذا الاسم على الخادم")
        if not team.add_member(app.db, pid, who["id"], u["id"]):
            return J({"ok": True, "message": "موجود في الفريق مسبقًا"})
        pname = app.db.one("SELECT name FROM projects WHERE id=?", (pid,))["name"]
        team.log(app.db, pid, u["id"], team.display(u), f"أضاف {team.display(who)} إلى الفريق")
        team.notify_later(app, who["id"], f"👥 أضافك {team.display(u)} إلى فريق مشروع «{pname}» في رفيق.")
        return J({"ok": True, "message": f"أُضيف {team.display(who)}"})

    async def project_member_remove(req: Request, u: dict):
        from . import team
        pid, owner, role = _pacc(req, u)
        target = int(req.path_params["uid"])
        if role != "owner" and target != u["id"]:
            raise HTTPError(403, "صاحب المشروع فقط يزيل أعضاء")
        who = app.db.one("SELECT * FROM users WHERE id=?", (target,))
        ok = team.remove_member(app.db, pid, target)
        if ok:
            team.log(app.db, pid, u["id"], team.display(u),
                     "غادر الفريق" if target == u["id"] else f"أزال {team.display(who)} من الفريق")
        return J({"ok": ok})

    def _team_task(u: dict, tid: int) -> tuple[dict, dict, str]:
        """المهمة + سياق صاحبها + دوري. العضو يعدّل فقط المهام المسندة إليه."""
        from .team import access, display
        t = app.db.one("SELECT * FROM tasks WHERE id=?", (tid,))
        acc = access(app.db, u["id"], t["project_id"]) if t and t["project_id"] else None
        if not t or not acc:
            raise HTTPError(404, "المهمة غير موجودة")
        owner, role = acc
        if role != "owner" and t["assignee_user_id"] != u["id"]:
            raise HTTPError(403, "تعدّل فقط المهام المسندة إليك — اطلب من صاحب المشروع إسنادها لك")
        ou = app.db.one("SELECT * FROM users WHERE id=?", (owner,))
        ctx_user = dict(ou, _actor_id=u["id"], _actor_name=display(u))
        return t, ctx_user, role

    async def team_task_move(req: Request, u: dict):
        from . import team
        from .pm import COLUMN_AR
        t, cu, role = _team_task(u, int(req.path_params["id"]))
        b = await body(req)
        res = await app.tools.execute(Ctx(app, cu, "web", "team"), "task_move",
                                      {"task": t["id"], "column": b.get("column"), "force": bool(b.get("force"))})
        if res.ok and role != "owner" and b.get("column") == "done":
            pname = app.db.one("SELECT name FROM projects WHERE id=?", (t["project_id"],))["name"]
            team.notify_later(app, cu["id"], f"✅ {team.display(u)} أنهى «{t['title']}» في مشروع «{pname}»")
        elif res.ok and role != "owner" and b.get("column") == "waiting":
            pname = app.db.one("SELECT name FROM projects WHERE id=?", (t["project_id"],))["name"]
            team.notify_later(app, cu["id"], f"⏳ {team.display(u)} وضع «{t['title']}» في «{COLUMN_AR['waiting']}» — مشروع «{pname}»")
        return J({"ok": res.ok, "text": res.text, "data": res.data}, 200 if res.ok else 400)

    async def team_task_log(req: Request, u: dict):
        t, cu, role = _team_task(u, int(req.path_params["id"]))
        b = await body(req)
        res = await app.tools.execute(Ctx(app, cu, "web", "team"), "log_time",
                                      {"task": t["id"], "minutes": b.get("minutes"), "hours": b.get("hours"), "note": b.get("note", "")})
        return J({"ok": res.ok, "text": res.text, "data": res.data}, 200 if res.ok else 400)

    async def team_my_tasks(req: Request, u: dict):
        from .team import my_tasks
        return J(my_tasks(app.db, u["id"], u["timezone"]))

    team_ip_limiter, team_global = RateLimiter(10, 600), RateLimiter(60, 600)

    async def public_invite(req: Request):
        """معاينة الدعوة قبل الانضمام: اسم المشروع فقط."""
        from .team import peek_invite
        from .team import display, peek_signup
        inv = peek_invite(app.db, req.path_params["code"]) or peek_signup(app.db, req.path_params["code"])
        if not inv:
            raise HTTPError(404, "الدعوة غير صحيحة أو انتهت صلاحيتها — اطلب رابطًا جديدًا")
        inviter = app.db.one("SELECT username, display_name FROM users WHERE id=?", (inv["created_by"],))
        return J({"project": inv.get("project"), "signup": "project" not in inv.keys(), "from": display(inviter)})

    async def public_join(req: Request):
        """الانضمام لفريق مشروع برمز دعوة: ينشئ حسابًا (أو يستخدم حسابك إن كان موجودًا بكلمة مرورك)
        ثم يعيد مفتاح وصول للتطبيق، وجلسة للوحة التحكم."""
        from . import team
        ip = req.client.host if req.client else "?"
        if not team_ip_limiter.allow(ip) or not team_global.allow("all"):
            raise HTTPError(429, "محاولات كثيرة، انتظر 10 دقائق")
        b = await body(req)
        inv = team.peek_invite(app.db, str(b.get("code", "")))
        signup = None if inv else team.peek_signup(app.db, str(b.get("code", "")))
        if not inv and not signup:
            app.db.log_event("warning", "team", f"رمز دعوة خاطئ من {ip}")
            raise HTTPError(403, "الدعوة غير صحيحة أو انتهت صلاحيتها — اطلب رابطًا جديدًا من صاحب المشروع")
        username = str(b.get("username", "")).strip().lower()
        password = str(b.get("password", ""))
        name = str(b.get("name", "")).strip()[:60]
        if not username or len(username) < 3 or not all(ch.isalnum() or ch in "._-" for ch in username):
            raise HTTPError(400, "اسم المستخدم: 3 أحرف على الأقل بالإنجليزية أو أرقام (بدون مسافات)")
        existing = app.db.one("SELECT * FROM users WHERE username=?", (username,))
        if existing:
            u = authenticate(app.db, username, password)
            if not u:
                raise HTTPError(409, "اسم المستخدم مستخدم — إن كان حسابك فاكتب كلمة مروره الصحيحة، وإلا اختر اسمًا آخر")
        else:
            if len(password) < 8:
                raise HTTPError(400, "كلمة المرور 8 أحرف على الأقل")
            uid = create_user(app.db, username, password, role="user", display_name=name or username, tz=app.s.timezone)
            u = app.db.one("SELECT * FROM users WHERE id=?", (uid,))
        if inv:
            team.use_invite(app.db, inv["code"])
            added = team.add_member(app.db, inv["project_id"], u["id"], inv["created_by"])
            if added:
                team.log(app.db, inv["project_id"], u["id"], team.display(u), "انضم إلى الفريق")
                team.notify_later(app, inv["created_by"], f"👥 انضم {team.display(u)} إلى فريق مشروع «{inv['project']}».")
            app.db.log_event("info", "team", f"انضمام {username} لمشروع {inv['project_id']} من {ip}")
        else:
            team.use_signup(app.db, signup["code"])
            team.notify_later(app, signup["created_by"], f"🎉 مجرّب جديد في رفيق: {team.display(u)}")
            app.db.log_event("info", "signup", f"تسجيل مجرّب {username} بدعوة من {ip}")
        token = new_api_token(app.db, u["id"], ("جوال: " + str(b.get("device_name") or "تطبيق رفيق"))[:60]) if b.get("app") else None
        resp = J({"ok": True, "user": team.display(u), "project": inv["project"] if inv else "",
                  "project_id": inv["project_id"] if inv else 0, "token": token, "server_url": _public_url(req)})
        if not b.get("app"):
            _set_cookie(req, resp, new_session(app.db, u["id"]))
        return resp

    async def join_page(req: Request):
        # صفحة التحميل نفسها، وتقرأ رمز الدعوة من الرابط (تحميل التطبيق ثم الانضمام، أو الدخول من المتصفح)
        return _page("download.html")

    async def project_update(req: Request, u: dict):
        b = await body(req)
        pid = int(req.path_params["id"])
        if not app.db.one("SELECT 1 FROM projects WHERE id=? AND user_id=?", (pid, u["id"])):
            raise HTTPError(404, "غير موجود")
        f = {}
        for k in ("name", "goal", "description", "owner"):
            if b.get(k) is not None:
                f[k] = str(b[k])[:500]
        for k in ("start_on", "end_on"):
            if b.get(k) is not None:
                f[k] = str(b[k])[:10] or None
        if b.get("status") in ("active", "paused", "done", "archived"):
            f["status"] = b["status"]
        if not f:
            raise HTTPError(400, "لا تعديلات")
        f["updated_at"] = now_iso()
        app.db.execute(f"UPDATE projects SET {', '.join(k + '=?' for k in f)} WHERE id=?", (*f.values(), pid))
        return J({"ok": True})

    async def workload(req: Request, u: dict):
        from .pm import workload_data
        return J(workload_data(app.db, u["id"], u["timezone"]))

    async def pm_templates(req: Request, u: dict):
        from .pm import TEMPLATES
        return J([{"id": k, "name": v["name"], "phases": [ph[0] for ph in v["phases"]],
                   "days": sum(ph[1] for ph in v["phases"]), "tasks": sum(len(ph[2]) for ph in v["phases"])}
                  for k, v in TEMPLATES.items()])

    async def projects_create(req: Request, u: dict):
        res = await app.tools.execute(Ctx(app, u, "web", "dashboard"), "create_project", await body(req))
        return J({"ok": res.ok, "message": res.text, "project": res.data}, 200 if res.ok else 400)

    async def project_get(req: Request, u: dict):
        pid, owner, role = _pacc(req, u)
        if role != "owner":   # العضو: بيانات المشروع العامة فقط (بلا مالية ولا عقود ولا ملفات)
            p = app.db.one("SELECT id, name, goal, status, start_on, end_on, owner FROM projects WHERE id=?", (pid,))
            ou = app.db.one("SELECT username, display_name FROM users WHERE id=?", (owner,))
            from .team import display
            p["owner_name"] = display(ou)
            return J({"project": p, "role": role, "entries": [], "files": [], "open_tasks": [], "overdue": []})
        data = project_report_data(app.db, u["id"], int(req.path_params["id"]), u["timezone"])
        data["role"] = "owner"
        data["files"] = app.db.all("SELECT id, name, size FROM files WHERE project_id=? AND user_id=?",
                                   (int(req.path_params["id"]), u["id"]))
        extra = app.db.one("SELECT start_on, end_on, owner, budget_minor FROM projects WHERE id=?", (int(req.path_params["id"]),))
        data["project"].update(extra or {})
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
                    "briefing_evening_enabled": bool, "biz_alerts_enabled": bool, "home_widgets": list, "memory_enabled": bool, "memory_retention_days": int,
                    "history_retention_days": int, "daily_cost_limit_usd": float, "voice_replies": bool}

    async def settings_get(req: Request, u: dict):
        defaults = {"briefing_time": "07:30", "evening_time": "21:00", "briefing_morning_enabled": True,
                    "briefing_evening_enabled": True, "memory_enabled": True, "memory_retention_days": 0,
                    "history_retention_days": app.s.history_retention_days,
                    "daily_cost_limit_usd": app.s.daily_cost_limit_usd, "voice_replies": False,
                    "biz_alerts_enabled": True, "home_widgets": []}
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
                if k == "home_widgets":
                    v = [str(x)[:30] for x in v][:30]
                if k in ("briefing_time", "evening_time") and not _valid_hhmm(v):
                    raise HTTPError(400, f"وقت غير صحيح: {v}")
                if k == "daily_cost_limit_usd":
                    v = max(0.0, float(v))
                    if u["role"] != "admin":  # المستخدم يخفّض حدّه فقط؛ الرفع للمدير
                        v = min(v, float(app.db.get_setting(u["id"], "max_daily_cost_usd", app.s.daily_cost_limit_usd)))
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
                                "briefing_evening_enabled", "biz_alerts_enabled")):
            app.db.execute("DELETE FROM jobs WHERE user_id=? AND status='pending' AND kind IN "
                           "('briefing_morning','briefing_evening','biz_alerts')", (u["id"],))
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
                  "operations", "files", "workspaces", "ledger", "invoices", "contacts", "budgets", "recurring", "kpis",
                  "meetings", "audit"):
            data[t] = app.db.all(f"SELECT * FROM {t} WHERE user_id=?", (uid,))
        data["settings"] = app.db.all("SELECT key, value FROM settings_kv WHERE user_id=?", (uid,))
        return Response(json.dumps(data, ensure_ascii=False, indent=1, default=str), media_type="application/json",
                        headers={"Content-Disposition": 'attachment; filename="harith-export.json"'})

    def _wipe_all(uid: int) -> int:
        n = 0
        for t in ("messages", "memories", "reminders", "ledger", "invoices", "budgets", "recurring", "kpis",
                  "meetings", "contacts", "audit", "workspaces", "tasks", "project_entries", "projects", "habits",
                  "approvals", "operations", "runs", "usage"):
            n += app.db.execute(f"DELETE FROM {t} WHERE user_id=?", (uid,)).rowcount
        for f in app.db.all("SELECT * FROM files WHERE user_id=?", (uid,)):
            file_path(app, uid, f).unlink(missing_ok=True)
        n += app.db.execute("DELETE FROM files WHERE user_id=?", (uid,)).rowcount
        app.scheduler.cancel_jobs(uid)
        return n

    async def account_delete(req: Request, u: dict):
        """حذف الحساب نهائيًا مع كل بياناته (بكلمة المرور). المدير الوحيد لا يحذف نفسه."""
        b = await body(req)
        if not verify_password(b.get("password", ""), u["password_hash"]):
            raise HTTPError(403, "أدخل كلمة المرور لتأكيد حذف الحساب")
        uid = u["id"]
        if u["role"] == "admin" and (app.db.one("SELECT COUNT(*) c FROM users WHERE role='admin' AND disabled=0")["c"] or 0) <= 1:
            raise HTTPError(400, "أنت المدير الوحيد للخادم، لا يمكن حذف حسابك")
        n = _wipe_all(uid)
        for t in ("sessions", "api_tokens", "telegram_links", "link_codes", "settings_kv", "project_members", "jobs"):
            try:
                n += app.db.execute(f"DELETE FROM {t} WHERE user_id=?", (uid,)).rowcount
            except Exception:  # noqa: BLE001 — جدول بلا عمود user_id
                pass
        app.db.execute("UPDATE tasks SET assignee_user_id=NULL WHERE assignee_user_id=?", (uid,))
        app.db.execute("DELETE FROM users WHERE id=?", (uid,))
        app.db.log_event("warning", "privacy", f"حُذف الحساب {uid} نهائيًا مع {n} سجل")
        resp = J({"ok": True, "deleted": n})
        resp.delete_cookie(COOKIE)
        return resp

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
            n = _wipe_all(uid)
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
        if "daily_limit_usd" in b:  # حد الصرف اليومي على الذكاء لهذا المستخدم (المدير فقط يرفعه)
            lim = max(0.0, min(float(b["daily_limit_usd"]), 50.0))
            app.db.set_setting(uid, "max_daily_cost_usd", lim)
            app.db.set_setting(uid, "daily_cost_limit_usd", lim)
        return J({"ok": True})

    # ——— دعوات المجرّبين (حساب جديد بلا مشروع)
    async def invites_list(req: Request, u: dict):
        admin(u)
        rows = app.db.all("SELECT code, note, expires_at, uses, max_uses, created_at FROM signup_invites "
                          "WHERE created_by=? ORDER BY created_at DESC LIMIT 50", (u["id"],))
        base = _public_url(req) or str(req.base_url).rstrip("/")
        for r in rows:
            r["link"] = f"{base}/join/{r['code']}"
            r["active"] = r["uses"] < r["max_uses"] and r["expires_at"] > now_iso()
        return J({"invites": rows, "local_only": not _public_url(req)})

    async def invites_create(req: Request, u: dict):
        admin(u)
        from .team import new_signup_invite
        b = await body(req)
        inv = new_signup_invite(app.db, u["id"], int(b.get("days") or 14), int(b.get("max_uses") or 20), b.get("note") or "")
        base = _public_url(req) or str(req.base_url).rstrip("/")
        inv["link"] = f"{base}/join/{inv['code']}"
        inv["local_only"] = not _public_url(req)
        return J(inv)

    async def invites_delete(req: Request, u: dict):
        admin(u)
        from .team import norm_code
        app.db.execute("UPDATE signup_invites SET max_uses=uses WHERE code=? AND created_by=?",
                       (norm_code(req.path_params["code"]), u["id"]))
        return J({"ok": True})

    # ——— ذكاء الخادم للتطبيق: واجهة متوافقة مع OpenAI، بمفتاح الخادم وحد الصرف اليومي لكل مستخدم.
    # المجرّب لا يحتاج أي مفتاح: التطبيق يرسل طلبه هنا بمفتاح الوصول الخاص به (hrt_…).
    OPENAI_COMPAT = {"openrouter": "https://openrouter.ai/api/v1", "openai": "https://api.openai.com/v1",
                     "gemini": "https://generativelanguage.googleapis.com/v1beta/openai"}

    async def ai_status(req: Request, u: dict):
        limit = float(app.db.get_setting(u["id"], "daily_cost_limit_usd", app.s.daily_cost_limit_usd))
        return J({"available": app.ai.configured() and (app.s.ai_provider in OPENAI_COMPAT or app.s.ai_provider in ("custom", "fake")),
                  "spent_today": round(app.agent.cost_today(u["id"]), 4), "limit": limit})

    async def ai_proxy(req: Request, u: dict):
        from .ai import estimate_cost
        p = app.s.ai_provider
        base = app.s.custom_base_url.rstrip("/") if p == "custom" else OPENAI_COMPAT.get(p, "")
        key = app.ai._key(p)
        if p != "fake" and (not base or (not key and p != "custom")):
            raise HTTPError(503, "ذكاء الخادم غير مفعّل. اطلب من مدير رفيق إضافة مفتاح الذكاء.")
        limit = float(app.db.get_setting(u["id"], "daily_cost_limit_usd", app.s.daily_cost_limit_usd))
        if app.agent.cost_today(u["id"]) >= limit:
            raise HTTPError(429, "وصلت حد استخدامك اليومي للذكاء. يتجدد بكرة، وباقي رفيق يشتغل عادي.")
        if p == "fake":
            return J({"choices": [{"message": {"role": "assistant", "content": "تم"}, "finish_reason": "stop"}],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
        b = await body(req)
        model = app.ai.model_for(p)
        out = {"model": model, "messages": b.get("messages") or [], "max_tokens": min(int(b.get("max_tokens") or 1500), 4000)}
        for k in ("tools", "tool_choice", "temperature"):
            if k in b:
                out[k] = b[k]
        r = await app.ai.http.post(f"{base}/chat/completions", json=out, timeout=120,
                                   headers={"Authorization": f"Bearer {key}"} if key else {})
        try:
            data = r.json()
        except ValueError:
            raise HTTPError(502, f"رد غير مفهوم من مزوّد الذكاء ({r.status_code})")
        if isinstance(data, list):  # أخطاء جوجل تأتي أحيانًا كقائمة: [{"error": {...}}]
            data = data[0] if data and isinstance(data[0], dict) else {}
        if not isinstance(data, dict):
            data = {}
        err = data.get("error")
        if r.status_code == 429 or (isinstance(err, dict) and err.get("code") == 429):
            raise HTTPError(429, "خدمة ذكاء خادم رفيق مزدحمة حاليًا، حاول بعد قليل.")
        if err and r.status_code >= 300:
            raise HTTPError(502, f"مزوّد الذكاء رفض الطلب ({r.status_code})")
        usage = data.get("usage") or {}
        tin, tout = int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0)
        if r.status_code < 300:
            app.db.execute("INSERT INTO usage(user_id,provider,model,input_tokens,output_tokens,cost_usd,created_at) "
                           "VALUES(?,?,?,?,?,?,?)", (u["id"], p, model, tin, tout, estimate_cost(model, tin, tout), now_iso()))
        return J(data, r.status_code)

    # ——— صفحات الموقع العامة: التحميل، الخصوصية، الشروط
    def _page(name: str):
        import os as _os
        from starlette.responses import HTMLResponse
        html = (STATIC / name).read_text(encoding="utf-8")
        html = _versioned(html.replace("{{CONTACT}}", _os.environ.get("RAFIQ_CONTACT", "مدير خدمة رفيق")))
        return HTMLResponse(html, headers={"Cache-Control": "no-cache"})

    async def sw_js(req: Request):
        return Response((STATIC / "sw.js").read_text(encoding="utf-8"), media_type="application/javascript",
                        headers={"Cache-Control": "no-cache", "Service-Worker-Allowed": "/"})

    async def download_page(req: Request):
        return _page("download.html")

    async def privacy_page(req: Request):
        return _page("privacy.html")

    async def terms_page(req: Request):
        return _page("terms.html")

    async def backups_list(req: Request, u: dict):
        admin(u)
        return J([{"name": p.name, "size": p.stat().st_size} for p in sorted(app.s.backups_dir.glob("harith-*.db"),
                                                                              reverse=True)])

    async def backups_create(req: Request, u: dict):
        admin(u)
        p = run_backup(app)
        return J({"ok": True, "name": p.name, "size": p.stat().st_size})

    async def backups_download(req: Request, u: dict):
        admin(u)
        from starlette.responses import FileResponse
        name = req.path_params["name"]
        p = app.s.backups_dir / name
        if "/" in name or not name.startswith("harith-") or not name.endswith(".db") or not p.exists():
            raise HTTPError(404, "النسخة غير موجودة")
        return FileResponse(str(p), filename=name, media_type="application/octet-stream")

    async def setup_restore(req: Request):
        """نقل رفيق إلى خادم جديد: يُسمح فقط قبل إنشاء أي حساب. يستورد نسخة احتياطية كاملة من الخادم القديم."""
        import sqlite3
        import tempfile
        _setup_guard(req)
        if app.db.one("SELECT 1 FROM users LIMIT 1"):
            raise HTTPError(403, "الخادم فيه حسابات من قبل؛ الاستيراد متاح فقط في خادم جديد")
        raw = await req.body()
        if len(raw) > 500 * 1024 * 1024 or not raw.startswith(b"SQLite format 3\x00"):
            raise HTTPError(400, "هذا الملف ليس نسخة احتياطية من رفيق (ملف ‎.db)")
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False, dir=str(app.s.data_dir)) as f:
            f.write(raw)
            tmp = f.name
        try:
            src = sqlite3.connect(tmp)
            try:
                if src.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise HTTPError(400, "النسخة الاحتياطية تالفة")
                admins = [r[0] for r in src.execute("SELECT username FROM users WHERE role='admin' AND disabled=0")]
                if not admins:
                    raise HTTPError(400, "النسخة لا تحتوي حساب مدير")
                with app.db._lock:
                    src.backup(app.db.conn)  # استبدال حي لمحتوى القاعدة
                app.db.migrate()  # ترقية مخطط النسخة الأقدم
            finally:
                src.close()
        except sqlite3.DatabaseError:
            raise HTTPError(400, "تعذّرت قراءة النسخة الاحتياطية")
        finally:
            Path(tmp).unlink(missing_ok=True)
        app.db.log_event("warning", "restore", "استُوردت نسخة احتياطية إلى هذا الخادم الجديد")
        return J({"ok": True, "admins": admins})

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
            {"id": "provision", "name": "توزيع رفيق بلا إعداد (OpenRouter)",
             "status": "ok" if app.provision.enabled else "setup",
             "detail": (f"مفعّل — حد {s.provision_limit_usd}$ {s.provision_limit_reset} لكل مستخدم"
                        if app.provision.enabled else "غير مفعّل"),
             "setup": "OPENROUTER_PROVISIONING_KEY (مفتاح إدارة)"},
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

    # ——— توزيع رفيق: تسجيل الأجهزة بلا إعداد (عام) وإدارتها (للمدير)
    # ——— التسجيل التلقائي: ينزّل الشخص التطبيق ويضغط «ابدأ» فيُنشأ له حساب على هذا الخادم ويستخدم مفتاح الذكاء
    # الخاص بالخادم (المفتاح لا يغادر الخادم أبدًا). محمي بعدد أقصى للمستخدمين وحد يومي لكل مستخدم وحد لكل IP.
    import os as _os_l
    try:  # شبكات الجوال تشارك عنوانًا واحدًا بين مشتركين كثيرين، فالحد لكل عنوان يجب ألا يكون صغيرًا
        _per_ip = max(1, int(_os_l.environ.get("RAFIQ_SIGNUPS_PER_IP", "20")))
    except ValueError:
        _per_ip = 20
    open_ip_limiter = RateLimiter(_per_ip, 86400)

    def _open_signup_enabled() -> bool:
        import os as _os
        flag = _os.environ.get("RAFIQ_OPEN_SIGNUP", "1").strip().lower() not in ("0", "false", "no", "off")
        return flag and app.ai.configured()

    def _max_users() -> int:
        import os as _os
        try:
            return int(_os.environ.get("RAFIQ_MAX_USERS", "100"))
        except ValueError:
            return 100

    async def public_info(req: Request):
        open_ok = _open_signup_enabled()
        return J({"name": "رفيق", "provisioning": app.provision.enabled or open_ok, "mode": "openrouter" if app.provision.enabled else ("server" if open_ok else ""),
                  "invite_required": bool(app.s.provision_invite_code) and app.provision.enabled})

    async def _open_register(req: Request, b: dict, ip: str):
        import hashlib, secrets as _sec
        dev = str(b.get("device_id", "")).strip()
        if len(dev) < 8:
            raise HTTPError(400, "معرّف الجهاز غير صالح")
        uname = "app_" + hashlib.sha256(("rafiq:" + dev).encode()).hexdigest()[:12]
        raw_name = str(b.get("name", "")).strip()[:60]
        name = raw_name or "مستخدم رفيق"
        u = app.db.one("SELECT * FROM users WHERE username=?", (uname,))
        if u and u.get("disabled"):
            raise HTTPError(403, "هذا الحساب موقوف من مدير الخدمة")
        if u and raw_name and name != u.get("display_name"):  # المستخدم غيّر اسمه في التطبيق
            app.db.execute("UPDATE users SET display_name=? WHERE id=?", (name, u["id"]))
            u["display_name"] = name
        if not u:
            if not raw_name:
                raise HTTPError(400, "الاسم مطلوب للتسجيل")
            n = app.db.one("SELECT COUNT(*) n FROM users WHERE substr(username, 1, 4) = 'app_'")["n"]
            if n >= _max_users():
                raise HTTPError(403, "اكتمل عدد المستخدمين في هذه المرحلة، جرّب لاحقًا")
            if not open_ip_limiter.allow(ip):
                raise HTTPError(429, "تسجيلات كثيرة من نفس الشبكة اليوم، جرّب غدًا")
            uid = create_user(app.db, uname, _sec.token_urlsafe(24), role="user", display_name=name, tz=app.s.timezone)
            u = app.db.one("SELECT * FROM users WHERE id=?", (uid,))
            app.db.log_event("info", "signup", f"مستخدم جديد من التطبيق ({name}) من {ip}")
            admins = app.db.all("SELECT id FROM users WHERE role='admin' AND disabled=0")
            if admins:
                from . import team
                team.notify_later(app, admins[0]["id"], f"🎉 مستخدم جديد في رفيق: {name}")
        token = new_api_token(app.db, u["id"], ("جوال: " + name)[:60])
        limit = float(app.db.get_setting(u["id"], "daily_cost_limit_usd", app.s.daily_cost_limit_usd) or app.s.daily_cost_limit_usd)
        return J({"mode": "server", "token": token, "user": u.get("display_name") or name,
                  "server_url": _public_url(req) or str(req.base_url).rstrip("/"), "limit_usd": limit})

    async def public_register(req: Request):
        from .provision import ProvisionError
        b = await body(req)
        ip = req.client.host if req.client else "?"
        if not app.provision.enabled:
            if not _open_signup_enabled():
                raise HTTPError(503, "التسجيل التلقائي غير متاح الآن")
            return await _open_register(req, b, ip)
        try:
            return J(await app.provision.register(str(b.get("device_id", "")), str(b.get("name", ""))[:60], ip,
                                                  str(b.get("invite", "")), str(b.get("app_version", ""))))
        except ProvisionError as e:
            raise HTTPError(e.status, str(e))

    pair_ip_limiter, pair_global = RateLimiter(8, 600), RateLimiter(40, 600)
    pair_fail = {"n": 0}

    async def public_pair(req: Request):
        """ربط تطبيق الجوال برمز من 6 أرقام ← مفتاح وصول. محدود المحاولات، والرمز لمرة واحدة."""
        from .security import consume_pair_code
        ip = req.client.host if req.client else "?"
        if not pair_ip_limiter.allow(ip) or not pair_global.allow("all"):
            raise HTTPError(429, "محاولات كثيرة، انتظر 10 دقائق")
        b = await body(req)
        uid = consume_pair_code(app.db, str(b.get("code", "")))
        if not uid:
            pair_fail["n"] += 1
            if pair_fail["n"] >= 20:  # حماية من التخمين: إبطال كل الرموز النشطة
                app.db.execute("DELETE FROM pair_codes")
                pair_fail["n"] = 0
            app.db.log_event("warning", "pair", f"رمز ربط خاطئ من {ip}")
            raise HTTPError(403, "الرمز غير صحيح أو انتهت صلاحيته — اطلب رمزًا جديدًا")
        pair_fail["n"] = 0
        u = app.db.one("SELECT * FROM users WHERE id=? AND disabled=0", (uid,))
        if not u:
            raise HTTPError(403, "الحساب غير متاح")
        name = ("جوال: " + str(b.get("device_name") or "تطبيق رفيق"))[:60]
        token = new_api_token(app.db, uid, name)
        app.db.log_event("info", "pair", f"رُبط جهاز جديد ({name}) من {ip}")
        return J({"token": token, "user": u.get("display_name") or u["username"]})

    async def server_info(req: Request, u: dict):
        """شاشة «خادمي» في التطبيق: الحالة، رابط الإنترنت، المفاتيح."""
        from . import __version__
        f = app.s.data_dir / "tunnel-url.txt"
        public = f.read_text(encoding="utf-8").strip() if f.exists() else ""
        return J({"version": __version__, "public_url": public,
                  "public_updated": iso(datetime_from_ts(f.stat().st_mtime)) if f.exists() else None,
                  "local_url": f"http://127.0.0.1:{app.s.port}", "paused": app.is_paused(u["id"]),
                  "ai": {"provider": app.s.ai_provider, "configured": app.ai.configured()},
                  "telegram": bool(app.s.telegram_bot_token),
                  "tokens": app.db.all("SELECT id, name, created_at, last_used FROM api_tokens WHERE user_id=? "
                                       "ORDER BY id DESC", (u["id"],))})

    async def pair_code(req: Request, u: dict):
        from .security import new_pair_code
        return J({"code": new_pair_code(app.db, u["id"]), "expires_minutes": 10})

    async def devices_list(req: Request, u: dict):
        admin(u)
        from .timeutil import local_day_bounds
        start, _ = local_day_bounds(app.s.timezone)
        rows = app.db.all(
            "SELECT u.id, u.display_name, u.disabled, u.created_at,"
            " (SELECT MAX(last_used) FROM api_tokens t WHERE t.user_id=u.id) last_seen,"
            " (SELECT COUNT(*) FROM messages m WHERE m.user_id=u.id AND m.role='user') msgs,"
            " (SELECT COALESCE(SUM(cost_usd),0) FROM usage g WHERE g.user_id=u.id AND g.created_at>=?) cost_today,"
            " (SELECT COALESCE(SUM(cost_usd),0) FROM usage g WHERE g.user_id=u.id) cost_total"
            " FROM users u WHERE substr(u.username,1,4)='app_' ORDER BY u.created_at DESC", (start,))
        for r in rows:
            r["limit"] = float(app.db.get_setting(r["id"], "daily_cost_limit_usd", app.s.daily_cost_limit_usd) or 0)
        return J({"enabled": app.provision.enabled, "limit_default": app.s.provision_limit_usd,
                  "max": app.s.provision_max_devices,
                  "devices": await app.provision.list_devices() if app.provision.enabled else [],
                  "app_users": rows, "app_max": _max_users(), "open_signup": _open_signup_enabled()})

    async def devices_update(req: Request, u: dict):
        from .provision import ProvisionError
        admin(u)
        b = await body(req)
        pk = int(req.path_params["id"])
        try:
            if "disabled" in b:
                await app.provision.set_disabled(pk, bool(b["disabled"]))
            if "limit" in b:
                await app.provision.set_limit(pk, float(b["limit"]))
        except ProvisionError as e:
            raise HTTPError(e.status, str(e))
        return J({"ok": True})

    async def devices_delete(req: Request, u: dict):
        admin(u)
        await app.provision.revoke(int(req.path_params["id"]))
        return J({"ok": True})

    # ——— الأعمال: القراءة مباشرة، والتعديل عبر سجل الأدوات (موافقات + إيقاف طارئ + سجل العمليات)
    from . import business as B

    def _q(req: Request, k: str, d: str = "") -> str:
        return (req.query_params.get(k) or d).strip()

    async def tool_call(req: Request, u: dict):
        name = req.path_params["name"]
        tool = app.tools.get(name)
        if not tool or tool.category not in B_CATEGORIES:
            raise HTTPError(404, "أداة غير متاحة من اللوحة")
        b = await body(req)
        res = await app.tools.execute(Ctx(app, u, "web"), name, b)
        return J({"ok": res.ok, "text": res.text, "data": res.data}, 200 if res.ok else 400)

    def _ws(req: Request, u: dict) -> dict:
        return B.find_ws(app.db, u["id"], _q(req, "workspace"))

    async def biz_workspaces(req: Request, u: dict):
        return J(B.list_workspaces(app.db, u["id"]))

    async def biz_overview(req: Request, u: dict):
        return J(B.workspace_overview_data(app.db, u["id"], _ws(req, u), u["timezone"]))

    async def biz_report(req: Request, u: dict):
        return J(B.finance_report_data(app.db, u["id"], _ws(req, u), u["timezone"], _q(req, "month") or None,
                                       _q(req, "year") or None))

    async def biz_ledger(req: Request, u: dict):
        res = await B.finance_list(Ctx(app, u, "web"), {k: _q(req, k) for k in ("workspace", "month", "kind", "query")
                                                         if _q(req, k)})
        return J(res.data)

    async def biz_invoices(req: Request, u: dict):
        res = await B.invoice_list(Ctx(app, u, "web"), {k: _q(req, k) for k in ("workspace", "filter", "kind") if _q(req, k)})
        return J(res.data)

    async def biz_invoice_print(req: Request, u: dict):
        inv = app.db.one("SELECT * FROM invoices WHERE id=? AND user_id=?", (int(req.path_params["id"]), u["id"]))
        if not inv:
            raise HTTPError(404, "غير موجود")
        from .timeutil import local_now
        return Response(B.invoice_html(app.db, u["id"], inv, printable_js=True,
                                       today=local_now(u["timezone"]).date().isoformat()),
                        media_type="text/html; charset=utf-8")

    async def biz_contacts(req: Request, u: dict):
        ws_id = B.find_ws(app.db, u["id"], _q(req, "workspace"))["id"] if _q(req, "workspace") else None
        rows = B.contact_rows(app.db, u["id"], _q(req, "kind") or None, _q(req, "q"), ws_id)
        return J(rows)

    async def biz_meetings(req: Request, u: dict):
        res = await B.meeting_list(Ctx(app, u, "web"), {"filter": _q(req, "filter", "all")})
        return J(res.data)

    async def biz_alerts_ep(req: Request, u: dict):
        return J(B.business_alerts(app.db, u["id"], u["timezone"]))

    async def biz_recurring(req: Request, u: dict):
        res = await B.recurring_list(Ctx(app, u, "web"), {})
        return J(res.data)

    async def biz_home(req: Request, u: dict):
        res = await B.finance_overview_all(Ctx(app, u, "web"), {})
        return J({"workspaces": res.data, "alerts": B.business_alerts(app.db, u["id"], u["timezone"]),
                  "widgets_hidden": app.db.get_setting(u["id"], "home_widgets", [])})

    async def biz_report_print(req: Request, u: dict):
        w = _ws(req, u)
        month = _q(req, "month") or None
        year = _q(req, "year") or None
        if not year and not month:
            from .timeutil import local_now
            month = local_now(u["timezone"]).strftime("%Y-%m")
        sheets = B.export_rows(app.db, u["id"], "report", w, u["timezone"], month, year)
        h = B.report_html(f"التقرير المالي — {w['name']} — \u2066{year or month}\u2069", sheets)
        h = h.replace("<body>", '<body><div style="text-align:left"><button id="print" style="font:inherit;padding:8px 18px;'
                      'border:0;border-radius:8px;background:#B8862B;color:#fff">طباعة / حفظ PDF</button></div>', 1)
        h = h.replace("</body>", '<script src="/static/print.js"></script></body>')
        return Response(h, media_type="text/html; charset=utf-8")

    async def search_all(req: Request, u: dict):
        return J(B.global_search(app.db, u["id"], _q(req, "q"), u["timezone"]))

    def _asset_ver() -> str:
        # بصمة تتغير مع كل تحديث للملفات: تجبر المتصفح على تحميل أحدث نسخة بدل المخزّنة
        import hashlib
        h = hashlib.sha1()
        for n in ("app.js", "biz.js", "pm.js", "style.css", "download.js"):
            f = STATIC / n
            if f.exists():
                st = f.stat()
                h.update(f"{n}:{st.st_size}:{int(st.st_mtime)}".encode())
        return h.hexdigest()[:10]

    def _versioned(html: str) -> str:
        v = _asset_ver()
        for n in ("app.js", "biz.js", "pm.js", "style.css", "download.js"):
            html = html.replace(f'"/static/{n}"', f'"/static/{n}?v={v}"')
        return html

    async def index(req: Request):
        h = _versioned((STATIC / "index.html").read_text(encoding="utf-8"))
        return Response(h, media_type="text/html; charset=utf-8", headers={"Cache-Control": "no-cache"})

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
        R("/api/projects/{id:int}", project_update, ["PATCH"]), R("/api/projects/{id:int}/board", project_board),
        R("/api/projects/{id:int}/timeline", project_timeline), R("/api/projects/{id:int}/health", project_health),
        R("/api/workload", workload), R("/api/pm/templates", pm_templates),
        R("/api/projects/{id:int}/team", project_team), R("/api/projects/{id:int}/invite", project_invite, ["POST"]),
        R("/api/projects/{id:int}/members", project_member_add, ["POST"]),
        R("/api/projects/{id:int}/members/{uid:int}", project_member_remove, ["DELETE"]),
        R("/api/team/tasks/{id:int}/move", team_task_move, ["POST"]), R("/api/team/tasks/{id:int}/log", team_task_log, ["POST"]),
        R("/api/team/my-tasks", team_my_tasks),
        R("/api/public/invite/{code}", public_invite, auth=False), R("/api/public/join", public_join, ["POST"], auth=False),
        R("/join/{code}", join_page, auth=False), R("/download", download_page, auth=False), R("/sw.js", sw_js, auth=False),
        R("/privacy", privacy_page, auth=False), R("/terms", terms_page, auth=False),
        R("/api/account/delete", account_delete, ["POST"]), R("/v1/chat/completions", ai_proxy, ["POST"]),
        R("/api/ai/status", ai_status),
        R("/api/admin/invites", invites_list), R("/api/admin/invites", invites_create, ["POST"]),
        R("/api/admin/invites/{code}", invites_delete, ["DELETE"]),
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
        R("/api/backups/{name}/download", backups_download), R("/api/setup/restore", setup_restore, ["POST"], auth=False),
        R("/api/integrations", integrations), R("/api/tools", tools_catalog), R("/api/sync", sync_ep, ["POST"]),
        R("/api/public/pair", public_pair, ["POST"], auth=False), R("/api/pair-code", pair_code, ["POST"]),
        R("/api/server-info", server_info),
        R("/api/public/info", public_info, auth=False), R("/api/public/register", public_register, ["POST"], auth=False),
        R("/api/devices", devices_list), R("/api/devices/{id:int}", devices_update, ["PATCH"]),
        R("/api/devices/{id:int}", devices_delete, ["DELETE"]),
        R("/api/tool/{name}", tool_call, ["POST"]), R("/api/search", search_all),
        R("/api/biz/workspaces", biz_workspaces), R("/api/biz/overview", biz_overview), R("/api/biz/report", biz_report),
        R("/api/biz/report/print", biz_report_print), R("/api/biz/home", biz_home),
        R("/api/biz/ledger", biz_ledger), R("/api/biz/invoices", biz_invoices),
        R("/api/biz/invoices/{id:int}/print", biz_invoice_print), R("/api/biz/contacts", biz_contacts),
        R("/api/biz/meetings", biz_meetings), R("/api/biz/alerts", biz_alerts_ep), R("/api/biz/recurring", biz_recurring),
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


def datetime_from_ts(ts: float):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(ts, tz=timezone.utc)


def _public_user(u: dict) -> dict:
    return {k: u[k] for k in ("id", "username", "display_name", "role", "timezone", "disabled", "created_at") if k in u}


def _valid_hhmm(v: str) -> bool:
    try:
        h, m = v.split(":")
        return 0 <= int(h) < 24 and 0 <= int(m) < 60 and len(m) == 2
    except ValueError:
        return False
