"""يشغّل الخادم الحقيقي على منفذ مؤقت ببيانات عرض توضيحية، ويلتقط صور لوحة التحكم (جوال وحاسوب).
للتطوير فقط: يستخدم مزوّدًا وهميًا للذكاء الاصطناعي ولا يُستخدم في التشغيل الفعلي.
python scripts/demo_screenshots.py OUT_DIR
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "screens")
OUT.mkdir(parents=True, exist_ok=True)
PORT = 8799
BASE = f"http://127.0.0.1:{PORT}"

data = Path(tempfile.mkdtemp())
env = dict(os.environ, HARITH_DATA_DIR=str(data), HARITH_PORT=str(PORT), AI_PROVIDER="fake",
           HARITH_HOME=str(data), TELEGRAM_BOT_TOKEN="", GEMINI_API_KEY="")
proc = subprocess.Popen([sys.executable, "-m", "harith", "run"], cwd=ROOT, env=env,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
try:
    for _ in range(60):
        try:
            if httpx.get(f"{BASE}/api/health").status_code == 200:
                break
        except httpx.HTTPError:
            time.sleep(0.25)
    c = httpx.Client(base_url=BASE, headers={"X-Harith": "1"})
    assert c.get("/api/setup-state").json()["needs_setup"] is True
    c.post("/api/setup", json={"username": "mohand", "password": "demo-pass-123", "display_name": "مهند"}).raise_for_status()

    # بيانات عرض
    for t in [
        {"title": "إرسال عرض السعر لشركة البناء", "priority": "high", "due": "2020-01-01T10:00"},
        {"title": "مراجعة العقد الجديد", "priority": "normal", "due": "2020-01-02T12:00"},
        {"title": "متابعة أحمد بخصوص الدفعة", "priority": "normal"},
        {"title": "تجهيز تقرير الأداء الشهري", "priority": "high"},
        {"title": "حجز موعد صيانة السيارة", "priority": "low"},
    ]:
        c.post("/api/tasks", json=t).raise_for_status()
    import datetime as dt
    soon = (dt.datetime.now() + dt.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M")
    c.post("/api/reminders", json={"text": "الاتصال بالعميل", "when": soon}).raise_for_status()
    c.post("/api/reminders", json={"text": "مراجعة البريد", "when": soon, "recur": "weekdays"}).raise_for_status()
    p = c.post("/api/projects", json={"name": "فيلا حي النرجس", "goal": "تسليم الهيكل الإنشائي قبل نهاية الربع"}).json()
    pid = p["project"]["id"]
    for i, title in enumerate(["اعتماد المخططات", "توريد الحديد", "صب القواعد", "صب الأعمدة"]):
        r = c.post("/api/tasks", json={"title": title, "project": str(pid)}).json()
        if i < 2:
            c.patch(f"/api/tasks/{r['task']['id']}", json={"status": "done"})
    c.post(f"/api/projects/{pid}/entries", json={"kind": "risk", "content": "تأخر المورد في توريد الحديد", "owner": "خالد"})
    c.post(f"/api/projects/{pid}/entries", json={"kind": "decision", "content": "اعتماد مورد بديل للخرسانة"})
    c.post(f"/api/projects/{pid}/entries", json={"kind": "meeting", "content": "اجتماع الموقع الأسبوعي: نسبة الإنجاز 40٪"})
    c.post("/api/projects", json={"name": "منصة الحرف المتكاملة", "goal": "إطلاق النسخة التجريبية"})
    c.post("/api/memory", json={"content": "يفضّل الاجتماعات في الصباح قبل 11", "kind": "preference"})
    c.post("/api/memory", json={"content": "المحاسب المسؤول: أ. سامي", "kind": "fact"})
    c.post("/api/habits", json={"name": "قراءة 20 دقيقة"})
    c.post("/api/habits", json={"name": "مشي"})
    c.post("/api/habits/1/check")
    c.post("/api/chat", json={"text": "ما أولوياتي اليوم؟"})
    c.post("/api/files?name=" + "عرض-سعر.md", content="# عرض سعر\nسعر المتر 450 ريال".encode())

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path="/opt/pw-browsers/chromium-1194/chrome-linux/chrome"
                               if Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome").exists() else None)
        cookies = [{"name": k, "value": v, "url": BASE} for k, v in c.cookies.items()]
        shots = [("desktop", {"width": 1366, "height": 900}, False), ("mobile", {"width": 390, "height": 844}, True)]
        for label, vp, mobile in shots:
            for scheme in ("dark", "light"):
                ctx = b.new_context(viewport=vp, device_scale_factor=2 if mobile else 1, is_mobile=mobile,
                                    color_scheme=scheme, locale="ar-SA")
                page = ctx.new_page()
                errors = []
                page.on("pageerror", lambda e: errors.append(str(e)))
                page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
                if scheme == "dark" and label == "mobile":
                    page.goto(BASE + "/")
                    page.wait_for_timeout(500)
                    page.screenshot(path=str(OUT / "00_login_mobile.png"))
                ctx.add_cookies(cookies)
                pages = ["home", "chat", "tasks", "projects", "memory", "integrations", "log", "settings", "usage"] \
                    if scheme == "dark" else ["home", "tasks"]
                for name in pages:
                    page.goto(f"{BASE}/#{name}")
                    page.reload()
                    page.wait_for_timeout(700)
                    page.screenshot(path=str(OUT / f"{label}_{scheme}_{name}.png"), full_page=not mobile)
                if scheme == "dark":
                    page.goto(f"{BASE}/#projects")
                    page.reload()
                    page.wait_for_timeout(500)
                    page.locator(".card", has_text="فيلا").first.click()
                    page.wait_for_timeout(600)
                    page.screenshot(path=str(OUT / f"{label}_{scheme}_project.png"), full_page=not mobile)
                (OUT / f"console_{label}_{scheme}.txt").write_text("\n".join(errors), encoding="utf-8")
                ctx.close()
        b.close()
    print("ok", sorted(x.name for x in OUT.iterdir()))
finally:
    proc.terminate()
    try:
        out = proc.communicate(timeout=10)[0]
    except subprocess.TimeoutExpired:
        proc.kill()
        out = proc.communicate()[0]
    (OUT / "server.log").write_text(out or "", encoding="utf-8")
    shutil.rmtree(data, ignore_errors=True)
