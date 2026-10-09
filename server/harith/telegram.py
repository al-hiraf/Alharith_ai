"""قناة تيليجرام: استطلاع طويل (لا يحتاج عنوانًا عامًا)، ربط آمن بالحساب، صوت وملفات وموافقات."""
from __future__ import annotations

import asyncio
import base64
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

import httpx

from .db import now_iso
from .security import RateLimiter, consume_link_code, redact
from .tools import file_path, save_user_file
from .toolkit import Ctx, Tool, ToolResult, obj, s

if TYPE_CHECKING:
    from .core import Harith

HELP = """أوامر الحارث:
/today — ملخص اليوم
/tasks — المهام المفتوحة
/status — حالة الخادم
/voice — تشغيل/إيقاف الرد الصوتي
/cancel — إيقاف الطلب الجاري
/stop — إيقاف طارئ لكل التنفيذ
/resume — استئناف التنفيذ
أو اكتب/سجّل طلبك مباشرة، وأرسل ملفات لحفظها."""


class Telegram:
    name = "telegram"

    def __init__(self, app: "Harith", token: str, http: httpx.AsyncClient | None = None):
        self.app, self.token = app, token
        self.base = app.s.telegram_api_base
        self.api = f"{self.base}/bot{token}"
        self.http = http or httpx.AsyncClient(timeout=httpx.Timeout(70, connect=15))
        self._task: asyncio.Task | None = None
        self._last_error = ""
        self._connected = False
        self.link_limiter = RateLimiter(5, 600)
        self.bot_username = ""
        self._handlers: set[asyncio.Task] = set()
        app.tools.add(Tool("send_file_to_me", "إرسال ملف من ملفات المستخدم إليه عبر تيليجرام.",
                           obj({"file": s("اسم الملف أو رقمه")}, ["file"]), self._tool_send_file, category="الملفات"))

    def status(self) -> str:
        if self._connected:
            return "متصل"
        return f"خطأ: {self._last_error}" if self._last_error else "جارٍ الاتصال"

    async def call(self, method: str, **params) -> dict:
        r = await self.http.post(f"{self.api}/{method}", json=params)
        data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        if not data.get("ok"):
            raise RuntimeError(f"telegram {method}: {data.get('description') or r.status_code}")
        return data.get("result")

    # ——— دورة الاستطلاع
    async def start(self) -> None:
        self._task = asyncio.create_task(self._poll(), name="telegram")

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
        await self.http.aclose()

    async def _poll(self) -> None:
        db = self.app.db
        backoff = 2
        while True:
            try:
                if not self.bot_username:
                    me = await self.call("getMe")
                    self.bot_username = me.get("username", "")
                    await self.call("setMyCommands", commands=[
                        {"command": c, "description": d} for c, d in (
                            ("today", "ملخص اليوم"), ("tasks", "المهام"), ("status", "الحالة"),
                            ("voice", "الرد الصوتي"), ("cancel", "إيقاف الطلب الجاري"), ("stop", "إيقاف طارئ"),
                            ("resume", "استئناف"), ("help", "المساعدة"))])
                offset = int(db.get_setting(0, "tg_offset", 0))
                updates = await self.call("getUpdates", offset=offset, timeout=50,
                                          allowed_updates=["message", "callback_query"])
                self._connected, self._last_error, backoff = True, "", 2
                for upd in updates:
                    db.set_setting(0, "tg_offset", upd["update_id"] + 1)
                    if db.claim_once(f"tg:{upd['update_id']}"):
                        t = asyncio.create_task(self._safe_handle(upd))
                        self._handlers.add(t)
                        t.add_done_callback(self._handlers.discard)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                self._connected = False
                self._last_error = redact(str(e))[:200]
                db.log_event("warning", "telegram", self._last_error)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 120)

    async def _safe_handle(self, upd: dict) -> None:
        try:
            await self.handle(upd)
        except Exception as e:  # noqa: BLE001
            self.app.db.log_event("error", "telegram", redact(repr(e))[:500])
            chat = (upd.get("message") or {}).get("chat", {}).get("id")
            if chat:
                try:
                    await self.call("sendMessage", chat_id=chat, text="حدث خطأ أثناء معالجة رسالتك، وسُجّل في السجلات.")
                except Exception:
                    pass

    # ——— المعالجة
    def _user_for_chat(self, chat_id: int) -> dict | None:
        return self.app.db.one("SELECT u.* FROM telegram_links l JOIN users u ON u.id=l.user_id "
                               "WHERE l.chat_id=? AND u.disabled=0", (chat_id,))

    async def handle(self, upd: dict) -> None:
        if "callback_query" in upd:
            return await self._callback(upd["callback_query"])
        msg = upd.get("message") or {}
        chat = msg.get("chat") or {}
        if chat.get("type") != "private":
            return
        chat_id = chat["id"]
        text = (msg.get("text") or "").strip()
        user = self._user_for_chat(chat_id)
        if not user:
            return await self._unlinked(chat_id, text, msg)
        if text.startswith("/"):
            return await self._command(user, chat_id, text)
        if msg.get("voice") or msg.get("audio"):
            return await self._voice(user, chat_id, msg)
        if msg.get("document") or msg.get("photo"):
            return await self._file(user, chat_id, msg)
        if text:
            await self._run(user, chat_id, text)

    async def _unlinked(self, chat_id: int, text: str, msg: dict) -> None:
        parts = text.split()
        code = parts[1] if len(parts) > 1 and parts[0] in ("/start", "/link") else ""
        if code:
            if not self.link_limiter.allow(str(chat_id)):
                await self.reply(chat_id, "محاولات كثيرة. حاول بعد 10 دقائق.")
                return
            uid = consume_link_code(self.app.db, code)
            if uid:
                self.app.db.execute("INSERT OR REPLACE INTO telegram_links(chat_id,user_id,tg_username,created_at) "
                                    "VALUES(?,?,?,?)", (chat_id, uid, (msg.get("from") or {}).get("username", ""),
                                                        now_iso()))
                self.app.db.log_event("info", "telegram", f"رُبطت محادثة بالمستخدم {uid}")
                await self.reply(chat_id, "✅ تم ربط حسابك بالحارث.\n\n" + HELP)
                return
            await self.reply(chat_id, "رمز الربط غير صحيح أو منتهي.")
            return
        await self.reply(chat_id, "هذا مساعد خاص. للربط: افتح لوحة التحكم ← الإعدادات ← «ربط تيليجرام»، "
                                  "ثم أرسل هنا: /link والرمز.")

    async def _command(self, user: dict, chat_id: int, text: str) -> None:
        cmd = text.split()[0].split("@")[0].lower()
        uid = user["id"]
        if cmd in ("/start", "/help"):
            await self.reply(chat_id, HELP)
        elif cmd == "/stop":
            r = self.app.set_paused(True, uid)
            await self.reply(chat_id, f"⏸️ أوقفت كل التنفيذ لحسابك (أُوقف {r['stopped_runs']} طلب جارٍ). /resume للاستئناف.")
        elif cmd == "/resume":
            self.app.set_paused(False, uid)
            await self.reply(chat_id, "▶️ استؤنف التنفيذ." + (" (ملاحظة: الإيقاف العام من المدير ما زال مفعّلًا)"
                                                             if self.app.is_paused() else ""))
        elif cmd == "/cancel":
            n = self.app.agent.cancel(uid=uid)
            await self.reply(chat_id, f"أوقفت {n} طلب جارٍ." if n else "لا يوجد طلب جارٍ.")
        elif cmd == "/voice":
            on = not self.app.db.get_setting(uid, "voice_replies", False)
            self.app.db.set_setting(uid, "voice_replies", on)
            await self.reply(chat_id, "🔊 الرد الصوتي مفعّل." if on else "🔇 الرد الصوتي متوقف.")
        elif cmd == "/status":
            h = self.app.health()
            await self.reply(chat_id, (f"الحالة: {'موقوف ⏸️' if self.app.is_paused(uid) else 'يعمل ✅'}\n"
                                       f"الذكاء الاصطناعي: {h['ai']['provider']} / {h['ai']['model']} "
                                       f"({'مُعدّ' if h['ai']['configured'] else 'غير مُعدّ'})\n"
                                       f"مهام مجدولة: {h['jobs']['pending']} — فاشلة: {h['jobs']['failed']}\n"
                                       f"تكلفة اليوم: {self.app.agent.cost_today(uid):.4f}$"))
        elif cmd == "/today":
            await self.reply(chat_id, await self.app.briefing_text(user))
        elif cmd == "/tasks":
            await self._run(user, chat_id, "اعرض مهامي المفتوحة مرتبة بالأولوية")
        else:
            await self.reply(chat_id, "أمر غير معروف.\n\n" + HELP)

    async def _run(self, user: dict, chat_id: int, text: str, attachments=None) -> None:
        typing = asyncio.create_task(self._typing(chat_id))
        try:
            res = await self.app.agent.run(user, text, "telegram", attachments=attachments)
        finally:
            typing.cancel()
        icon = {"queued": "⏳ ", "failed": "❌ ", "blocked": "⏸️ ", "cancelled": "⏹️ "}.get(res["status"], "")
        await self.reply(chat_id, icon + res["text"])
        if self.app.db.get_setting(user["id"], "voice_replies", False) and res["status"] in ("success", "partial"):
            await self._send_voice(chat_id, res["text"])

    async def _typing(self, chat_id: int) -> None:
        try:
            while True:
                await self.call("sendChatAction", chat_id=chat_id, action="typing")
                await asyncio.sleep(4.5)
        except (asyncio.CancelledError, Exception):
            pass

    async def _download(self, file_id: str, max_bytes: int = 20 * 1024 * 1024) -> tuple[bytes, str]:
        info = await self.call("getFile", file_id=file_id)
        if info.get("file_size", 0) > max_bytes:
            raise ValueError("الملف أكبر من الحد المسموح")
        r = await self.http.get(f"{self.base}/file/bot{self.token}/{info['file_path']}")
        r.raise_for_status()
        return r.content, info["file_path"]

    async def _voice(self, user: dict, chat_id: int, msg: dict) -> None:
        v = msg.get("voice") or msg.get("audio")
        data, path = await self._download(v["file_id"])
        try:
            text = await self.app.ai.transcribe(data, v.get("mime_type") or "audio/ogg")
        except Exception as e:  # noqa: BLE001
            await self.reply(chat_id, f"تعذّر تحويل الصوت إلى نص: {redact(str(e))[:200]}")
            return
        if not text:
            await self.reply(chat_id, "لم أفهم التسجيل، أعد المحاولة.")
            return
        await self.reply(chat_id, f"🎙️ «{text}»")
        await self._run(user, chat_id, text)

    async def _file(self, user: dict, chat_id: int, msg: dict) -> None:
        if msg.get("document"):
            d = msg["document"]
            name, fid = d.get("file_name") or "file", d["file_id"]
        else:
            ph = msg["photo"][-1]
            name, fid = f"photo-{ph['file_unique_id']}.jpg", ph["file_id"]
        try:
            data, _ = await self._download(fid)
        except ValueError as e:
            await self.reply(chat_id, str(e))
            return
        file_id = save_user_file(self.app, user["id"], name, data)
        f = self.app.db.one("SELECT name FROM files WHERE id=?", (file_id,))
        caption = (msg.get("caption") or "").strip()
        if not caption:
            await self.reply(chat_id, f"📎 حفظت الملف «{f['name']}» (رقم {file_id}). اطلب مني قراءته أو تلخيصه متى شئت.")
            return
        atts = None
        if name.lower().endswith((".jpg", ".jpeg", ".png")) and self.app.s.ai_provider == "gemini":
            atts = [{"mime": "image/jpeg" if not name.lower().endswith(".png") else "image/png",
                     "b64": base64.b64encode(data).decode()}]
        await self._run(user, chat_id, f"{caption}\n(الملف المرفق: «{f['name']}» رقم {file_id})", atts)

    async def _callback(self, cq: dict) -> None:
        chat_id = (cq.get("message") or {}).get("chat", {}).get("id")
        user = self._user_for_chat(chat_id) if chat_id else None
        data = cq.get("data") or ""
        if not user or not data.startswith("ap:"):
            await self.call("answerCallbackQuery", callback_query_id=cq["id"], text="غير مصرح")
            return
        _, aid, choice = data.split(":")
        await self.call("answerCallbackQuery", callback_query_id=cq["id"], text="جارٍ التنفيذ…")
        res = await self.app.decide_approval(user["id"], int(aid), choice == "yes", "telegram")
        try:
            await self.call("editMessageReplyMarkup", chat_id=chat_id, message_id=cq["message"]["message_id"],
                            reply_markup={"inline_keyboard": []})
        except Exception:
            pass
        await self.reply(chat_id, res["text"])

    # ——— الإرسال
    async def reply(self, chat_id: int, text: str, buttons: list[list[dict]] | None = None) -> None:
        chunks = [text[i:i + 4000] for i in range(0, len(text), 4000)] or ["…"]
        for n, ch in enumerate(chunks):
            params: dict = {"chat_id": chat_id, "text": ch, "disable_web_page_preview": True}
            if buttons and n == len(chunks) - 1:
                params["reply_markup"] = {"inline_keyboard": [[{"text": b["text"], "callback_data": b["data"]}
                                                               for b in row] for row in buttons]}
            await self.call("sendMessage", **params)

    async def send(self, uid: int, text: str, buttons: list[list[dict]] | None = None) -> bool | None:
        chats = self.app.db.all("SELECT chat_id FROM telegram_links WHERE user_id=?", (uid,))
        if not chats:
            return None
        ok = False
        for c in chats:
            await self.reply(c["chat_id"], text, buttons)
            ok = True
        return ok

    async def _send_voice(self, chat_id: int, text: str) -> None:
        try:
            audio = await self.app.ai.speak(text)
        except Exception:
            audio = None
        if not audio:
            return
        data, mime = audio
        if mime == "audio/wav" and shutil.which("ffmpeg"):
            data, mime = await asyncio.to_thread(_wav_to_ogg, data)
        files = {"voice" if mime == "audio/ogg" else "document":
                 ("reply.ogg" if mime == "audio/ogg" else "reply.wav", data, mime)}
        method = "sendVoice" if mime == "audio/ogg" else "sendDocument"
        await self.http.post(f"{self.api}/{method}", data={"chat_id": chat_id}, files=files)

    async def _tool_send_file(self, ctx: Ctx, a: dict) -> ToolResult:
        db = self.app.db
        ref = a.get("file")
        f = (db.one("SELECT * FROM files WHERE id=? AND user_id=?", (int(ref), ctx.uid)) if str(ref).isdigit() else
             db.one("SELECT * FROM files WHERE user_id=? AND name LIKE ? ORDER BY id DESC", (ctx.uid, f"%{ref}%")))
        if not f:
            return ToolResult(False, f"لا يوجد ملف: {ref}")
        chats = db.all("SELECT chat_id FROM telegram_links WHERE user_id=?", (ctx.uid,))
        if not chats:
            return ToolResult(False, "حسابك غير مربوط بتيليجرام")
        p = file_path(self.app, ctx.uid, f)
        for c in chats:
            r = await self.http.post(f"{self.api}/sendDocument", data={"chat_id": c["chat_id"]},
                                     files={"document": (f["name"], p.read_bytes(), f["mime"])})
            if not r.json().get("ok"):
                return ToolResult(False, f"رفض تيليجرام الإرسال: {r.json().get('description')}")
        return ToolResult(True, f"أُرسل الملف {f['name']}", verified=True)


def _wav_to_ogg(wav: bytes) -> tuple[bytes, str]:
    with tempfile.TemporaryDirectory() as d:
        src, dst = Path(d) / "a.wav", Path(d) / "a.ogg"
        src.write_bytes(wav)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-c:a", "libopus", str(dst)],
                       check=True, timeout=60)
        return dst.read_bytes(), "audio/ogg"
