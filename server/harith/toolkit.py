"""إطار الأدوات: تعريف الأداة، مستوى الخطورة، التنفيذ مع مهلة، والتسجيل."""
from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from .db import now_iso
from .security import redact

if TYPE_CHECKING:
    from .core import Harith

# مستويات الموافقة
LOW, NEEDS_APPROVAL, FORBIDDEN = 1, 2, 3


@dataclass
class ToolResult:
    ok: bool
    text: str
    data: Any = None
    verified: bool = False   # True فقط إذا تحققنا من النتيجة الفعلية (قراءة بعد الكتابة، أو قبول الخادم)

    def for_model(self) -> str:
        status = "نجح" if self.ok else "فشل"
        if self.ok and not self.verified:
            status += " (غير مُتحقق منه)"
        payload = {"status": status, "message": self.text}
        if self.data is not None:
            payload["data"] = self.data
        return json.dumps(payload, ensure_ascii=False, default=str)[:12000]


@dataclass
class Ctx:
    app: "Harith"
    user: dict
    channel: str = "web"
    run_id: str = ""
    approved: bool = False   # True عند التنفيذ بعد موافقة المستخدم

    @property
    def uid(self) -> int:
        return int(self.user["id"])

    @property
    def tz(self) -> str:
        return self.user.get("timezone") or self.app.s.timezone


Handler = Callable[[Ctx, dict], Awaitable[ToolResult]]


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    handler: Handler
    level: int = LOW
    summarize: Callable[[dict], str] | None = None
    category: str = "عام"

    def schema(self) -> dict:
        return {"name": self.name, "description": self.description, "parameters": self.parameters}


def obj(props: dict[str, dict] | None = None, required: list[str] | None = None) -> dict:
    return {"type": "object", "properties": props or {}, "required": required or []}


def s(desc: str, enum: list[str] | None = None) -> dict:
    d: dict[str, Any] = {"type": "string", "description": desc}
    if enum:
        d["enum"] = enum
    return d


def i(desc: str) -> dict:
    return {"type": "integer", "description": desc}


class Registry:
    def __init__(self):
        self.tools: dict[str, Tool] = {}

    def add(self, tool: Tool) -> None:
        self.tools[tool.name] = tool

    def get(self, name: str) -> Tool | None:
        return self.tools.get(name)

    def schemas(self) -> list[dict]:
        return [t.schema() for t in self.tools.values() if t.level < FORBIDDEN]

    def catalog(self) -> list[dict]:
        return [{"name": t.name, "description": t.description, "level": t.level, "category": t.category}
                for t in self.tools.values()]

    async def execute(self, ctx: Ctx, name: str, args: dict) -> ToolResult:
        """ينفذ الأداة مع: الإيقاف الطارئ، مستويات الموافقة، المهلة، وتسجيل العملية."""
        app = ctx.app
        tool = self.get(name)
        safe_args = redact(json.dumps(args, ensure_ascii=False))[:2000]
        level = tool.level if tool else 0
        op_id = app.db.insert(
            "INSERT INTO operations(user_id,run_id,kind,tool,args,level,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
            (ctx.uid, ctx.run_id, "tool", name, safe_args, level, "running", now_iso()),
        )

        def finish(status: str, res: ToolResult) -> ToolResult:
            app.db.execute(
                "UPDATE operations SET status=?, result=?, error=?, finished_at=? WHERE id=?",
                (status, redact(res.text)[:2000], "" if res.ok else redact(res.text)[:1000], now_iso(), op_id),
            )
            return res

        if tool is None:
            return finish("failed", ToolResult(False, f"أداة غير موجودة: {name}"))
        if app.is_paused(ctx.uid):
            return finish("blocked", ToolResult(False, "التنفيذ موقوف حاليًا (زر الإيقاف الطارئ مفعّل). لم يُنفّذ شيء."))
        if tool.level >= FORBIDDEN:
            return finish("blocked", ToolResult(
                False, "هذا إجراء عالي الخطورة ولا يُنفَّذ تلقائيًا. يجب أن يقوم به المستخدم بنفسه."))
        if tool.level == NEEDS_APPROVAL and not ctx.approved:
            summary = tool.summarize(args) if tool.summarize else f"{tool.name}: {safe_args}"
            aid = app.db.insert(
                "INSERT INTO approvals(user_id,tool,args,summary,status,channel,created_at) VALUES(?,?,?,?,?,?,?)",
                (ctx.uid, name, json.dumps(args, ensure_ascii=False), summary, "pending", ctx.channel, now_iso()),
            )
            await app.notify_approval(ctx.uid, aid, summary)
            return finish("needs_approval", ToolResult(
                True, f"أُنشئ طلب موافقة رقم {aid} ولم يُنفَّذ الإجراء بعد. سيُنفَّذ فقط بعد أن يوافق المستخدم.",
                {"approval_id": aid}, verified=True))
        try:
            res = await asyncio.wait_for(tool.handler(ctx, args), timeout=app.s.tool_timeout_s)
        except asyncio.TimeoutError:
            return finish("failed", ToolResult(False, f"تجاوزت الأداة المهلة ({app.s.tool_timeout_s} ثانية) وأُوقفت."))
        except (ValueError, KeyError, TypeError) as e:
            return finish("failed", ToolResult(False, f"مدخلات غير صحيحة: {e}"))
        except asyncio.CancelledError:
            finish("cancelled", ToolResult(False, "أُلغيت"))
            raise
        except Exception as e:  # noqa: BLE001
            app.db.log_event("error", f"tool:{name}", redact(repr(e)))
            return finish("failed", ToolResult(False, f"خطأ أثناء التنفيذ: {type(e).__name__}: {redact(str(e))[:300]}"))
        return finish("success" if res.ok else "failed", res)
