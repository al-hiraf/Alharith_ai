"""اختبار حي من البداية للنهاية: يشغّل الخادم الحقيقي كعملية منفصلة، مع خادم تيليجرام وهمي وخادم ذكاء
اصطناعي وهمي متوافق مع OpenAI، ثم يتحقق من: الربط، الأوامر، تذكير يصل في وقته حتى بعد قتل الخادم
وإعادة تشغيله، رفض غير المصرح له، منع التكرار، وتدفق الموافقة.
python scripts/e2e_live.py
"""
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

import httpx
import uvicorn
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

ROOT = Path(__file__).resolve().parent.parent
TG_PORT, AI_PORT, PORT = 8790, 8791, 8792
BASE = f"http://127.0.0.1:{PORT}"
TOKEN = "123456:TESTTOKEN"

updates: list[dict] = []
sent: list[dict] = []
lock = threading.Lock()
uid_counter = [100]


# ——— تيليجرام وهمي
async def tg(req: Request):
    method = req.path_params["method"]
    body = await req.json() if req.headers.get("content-type", "").startswith("application/json") else {}
    if method == "getMe":
        return JSONResponse({"ok": True, "result": {"username": "harith_test_bot"}})
    if method == "getUpdates":
        off = body.get("offset", 0)
        for _ in range(20):
            with lock:
                out = [u for u in updates if u["update_id"] >= off]
            if out:
                return JSONResponse({"ok": True, "result": out})
            import asyncio
            await asyncio.sleep(0.1)
        return JSONResponse({"ok": True, "result": []})
    with lock:
        sent.append({"method": method, **body})
    return JSONResponse({"ok": True, "result": {"message_id": len(sent)}})


def push(update: dict, uid: int | None = None):
    with lock:
        if uid is None:
            uid_counter[0] += 1
            uid = uid_counter[0]
        updates.append({"update_id": uid, **update})
    return uid


def msg(chat: int, text: str):
    return {"message": {"chat": {"id": chat, "type": "private"}, "from": {"username": "mohand"}, "text": text}}


# ——— ذكاء اصطناعي وهمي (OpenAI Chat Completions)
async def ai(req: Request):
    b = await req.json()
    last = b["messages"][-1]
    tool_names = {t["function"]["name"] for t in b.get("tools", [])}

    def tool_call(name, args):
        return JSONResponse({"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
            {"id": f"c{time.time_ns()}", "type": "function", "function": {"name": name,
                                                                         "arguments": json.dumps(args, ensure_ascii=False)}}]}}],
            "usage": {"prompt_tokens": 900, "completion_tokens": 40}})

    def text(t):
        return JSONResponse({"choices": [{"message": {"role": "assistant", "content": t}}],
                             "usage": {"prompt_tokens": 950, "completion_tokens": 30}})
    if last["role"] == "tool":
        res = json.loads(last["content"])
        return text(("تم ✅ " if res["status"].startswith("نجح") else "لم يتم: ") + res["message"])
    u = last["content"]
    if "ذكرني" in u and "create_reminder" in tool_names:
        return tool_call("create_reminder", {"text": "شرب الماء", "in_minutes": 1})
    if "بريد" in u and "send_email" in tool_names:
        return tool_call("send_email", {"to": "client@example.com", "subject": "عرض السعر", "body": "مرفق العرض"})
    if "مهمة" in u:
        return tool_call("add_task", {"title": "مراجعة العقد", "priority": "high"})
    return text("أهلًا مهند.")


def serve(app, port):
    cfg = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    srv = uvicorn.Server(cfg)
    threading.Thread(target=srv.run, daemon=True).start()
    return srv


serve(Starlette(routes=[Route("/bot{token}/{method}", tg, methods=["POST", "GET"])]), TG_PORT)
serve(Starlette(routes=[Route("/v1/chat/completions", ai, methods=["POST"])]), AI_PORT)

data = Path(tempfile.mkdtemp())
env = dict(os.environ, HARITH_DATA_DIR=str(data), HARITH_HOME=str(data), HARITH_PORT=str(PORT),
           AI_PROVIDER="custom", CUSTOM_BASE_URL=f"http://127.0.0.1:{AI_PORT}/v1", AI_MODEL="mock-model",
           CUSTOM_API_KEY="x", TELEGRAM_BOT_TOKEN=TOKEN, TELEGRAM_API_BASE=f"http://127.0.0.1:{TG_PORT}",
           GEMINI_API_KEY="", HARITH_TIMEZONE="Asia/Riyadh")
logf = open(data / "server.log", "w")


def start():
    p = subprocess.Popen([sys.executable, "-m", "harith", "run"], cwd=ROOT, env=env, stdout=logf, stderr=logf)
    for _ in range(80):
        try:
            if httpx.get(f"{BASE}/api/health").status_code == 200:
                return p
        except httpx.HTTPError:
            time.sleep(0.25)
    raise SystemExit("server did not start")


def wait_for(pred, timeout=20, what=""):
    end = time.time() + timeout
    while time.time() < end:
        with lock:
            hit = [s for s in sent if pred(s)]
        if hit:
            return hit[-1]
        time.sleep(0.2)
    raise AssertionError(f"لم يحدث: {what}")


results = []


def ok(name, cond=True):
    results.append((name, bool(cond)))
    print(("✅ " if cond else "❌ ") + name, flush=True)


proc = start()
try:
    c = httpx.Client(base_url=BASE, headers={"X-Harith": "1"})
    c.post("/api/setup", json={"username": "mohand", "password": "live-test-123", "display_name": "مهند"}).raise_for_status()
    ok("إنشاء حساب المدير من الإعداد الأول")
    ok("لا يمكن إعادة الإعداد بعد إنشاء الحساب",
       httpx.post(f"{BASE}/api/setup", json={"username": "x", "password": "hackerpass1"}).status_code == 403)

    # غير مصرح له
    push(msg(777, "احذف كل المهام"))
    m = wait_for(lambda s: s.get("chat_id") == 777, what="رد للمحادثة غير المربوطة")
    ok("محادثة غير مربوطة تُرفض", "خاص" in m["text"])

    code = c.post("/api/telegram/link-code").json()["code"]
    push(msg(555, f"/link {code}"))
    m = wait_for(lambda s: s.get("chat_id") == 555 and "ربط" in s.get("text", ""), what="تأكيد الربط")
    ok("ربط تيليجرام بالرمز")

    # أمر عربي عبر تيليجرام → تذكير حقيقي
    t0 = time.time()
    push(msg(555, "ذكرني بعد دقيقة بشرب الماء"))
    m = wait_for(lambda s: s.get("chat_id") == 555 and "التذكير" in s.get("text", ""), what="رد إنشاء التذكير")
    ok(f"أمر عربي عبر تيليجرام ← «{m['text'][:60]}»", "تم" in m["text"])
    rem = c.get("/api/reminders").json()
    ok("التذكير محفوظ في قاعدة البيانات", rem and rem[0]["status"] == "pending")

    # تكرار نفس التحديث لا يُنفّذ مرتين
    before = len(c.get("/api/runs").json())
    dup = push(msg(555, "أضف مهمة"))
    wait_for(lambda s: s.get("chat_id") == 555 and "أُضيفت" in s.get("text", ""), what="إضافة مهمة")
    push(msg(555, "أضف مهمة"), uid=dup)  # نفس update_id
    time.sleep(2)
    ok("التحديث المكرر يُعالَج مرة واحدة", len(c.get("/api/runs").json()) == before + 1)

    # قتل الخادم بقسوة ثم إعادة تشغيله قبل موعد التذكير
    proc.send_signal(signal.SIGKILL)
    proc.wait()
    ok("قُتل الخادم (SIGKILL) قبل موعد التذكير")
    proc = start()
    c = httpx.Client(base_url=BASE, headers={"X-Harith": "1"})
    c.post("/api/login", json={"username": "mohand", "password": "live-test-123"}).raise_for_status()
    ok("الجلسات والبيانات باقية بعد إعادة التشغيل", len(c.get("/api/tasks").json()) == 1)

    m = wait_for(lambda s: s.get("chat_id") == 555 and "🔔" in s.get("text", ""), timeout=120, what="وصول التذكير")
    late = time.time() - t0
    ok(f"وصل التذكير بعد إعادة التشغيل: «{m['text']}» بعد {late:.0f} ثانية", 55 <= late <= 100)
    time.sleep(3)
    with lock:
        n_rem = len([s for s in sent if s.get("chat_id") == 555 and "🔔" in s.get("text", "")])
    ok("التذكير أُرسل مرة واحدة فقط", n_rem == 1)

    # تدفق الموافقة
    push(msg(555, "أرسل بريد العرض للعميل"))
    m = wait_for(lambda s: s.get("chat_id") == 555 and "reply_markup" in s, what="رسالة طلب الموافقة بأزرار")
    ok("طلب موافقة بأزرار قبل الإرسال", "طلب موافقة" in m["text"])
    aid = m["reply_markup"]["inline_keyboard"][0][0]["callback_data"].split(":")[1]
    push({"callback_query": {"id": "cb1", "data": f"ap:{aid}:yes", "message": {"message_id": 1, "chat": {"id": 555}}}})
    m = wait_for(lambda s: s.get("chat_id") == 555 and ("نُفّذ" in s.get("text", "") or "فشل التنفيذ" in s.get("text", "")),
                 what="نتيجة الموافقة")
    ok(f"بعد الموافقة يُنفَّذ ويبلّغ بالنتيجة الحقيقية: «{m['text'][:70]}»", "البريد غير مُعدّ" in m["text"])

    # إيقاف طارئ من تيليجرام
    push(msg(555, "/stop"))
    wait_for(lambda s: s.get("chat_id") == 555 and "أوقفت" in s.get("text", ""), what="الإيقاف")
    push(msg(555, "أضف مهمة"))
    m = wait_for(lambda s: s.get("chat_id") == 555 and "موقوف" in s.get("text", ""), what="رفض أثناء الإيقاف")
    ok("الإيقاف الطارئ يمنع التنفيذ", len(c.get("/api/tasks").json()) == 1)
    push(msg(555, "/resume"))
    wait_for(lambda s: s.get("chat_id") == 555 and "استؤنف" in s.get("text", ""), what="الاستئناف")

    ops = c.get("/api/operations").json()
    ok(f"سجل العمليات: {len(ops)} عملية مسجلة بحالاتها", {o["status"] for o in ops} >= {"success", "needs_approval"})
    u = c.get("/api/usage").json()
    ok(f"تتبع الاستهلاك والتكلفة: {sum(d['calls'] for d in u['by_day'])} طلب", u["by_day"])
    h = c.get("/api/overview").json()["health"]
    ok("حالة تيليجرام في اللوحة: " + h["channels"].get("telegram", "?"), h["channels"].get("telegram") == "متصل")
finally:
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
    logf.close()
    errs = [ln for ln in (data / "server.log").read_text().splitlines() if "Traceback" in ln or "ERROR" in ln]
    ok("لا أخطاء في سجل الخادم", not errs)
    for e in errs[:10]:
        print("   ", e)

passed = sum(1 for _, r in results if r)
print(f"\n{passed}/{len(results)} نجح")
sys.exit(0 if passed == len(results) else 1)
