"""تنبيه المتصفح: حدث حقيقي على الخادم ← يظهر تنبيه نظام عندما تكون اللوحة في الخلفية."""
import json, random, urllib.request
from playwright.sync_api import sync_playwright
B = "http://127.0.0.1:8799"
with sync_playwright() as p:
    b = p.chromium.launch(); c = b.new_context(locale="ar"); c.grant_permissions(["notifications"], origin=B)
    c.add_init_script("""window.__notes = []; const N = window.Notification;
      window.Notification = function (t, o) { window.__notes.push({ t, body: o && o.body }); return { close() {} }; };
      window.Notification.permission = 'granted'; window.Notification.requestPermission = async () => 'granted';
      Object.defineProperty(document, 'hidden', { get: () => window.__hidden === true });""")
    pg = c.new_page(); pg.goto(B + "/"); pg.wait_for_selector("form input[type=password]")
    pg.locator("form input").nth(0).fill("admin"); pg.locator("form input[type=password]").fill("Admin-pass-2026")
    pg.locator("form button").first.click(); pg.wait_for_selector("main.main"); pg.wait_for_timeout(1500)
    pg.evaluate("window.__hidden = true")
    req = urllib.request.Request(B + "/api/public/register", data=json.dumps({"device_id": f"dev-bn-{random.randint(10**8, 10**9)}", "name": "سلمان"}).encode(), headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()
    pg.wait_for_function("() => window.__notes.length > 0", timeout=40000)
    notes = pg.evaluate("window.__notes")
    print(("PASS" if len(notes) == 1 and "سلمان" in notes[0]["body"] else "FAIL") + " browser system notification while panel in background:", notes)
    b.close()
