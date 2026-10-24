"""اختبار حقيقي للوحة رفيق في متصفح: إعداد، دخول، كل الصفحات، إضافة/تعديل/حذف، صلاحيات، جوال/كمبيوتر.
يطبع PASS/FAIL لكل بند ويجمع أخطاء المتصفح والطلبات الفاشلة."""
import json, re, sys, time
from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8799"
OUT = sys.argv[2] if len(sys.argv) > 2 else "."
PAGES = ["home", "chat", "tasks", "business", "finance", "invoices", "contacts", "meetings", "projects", "memory",
         "files", "scheduled", "log", "usage", "integrations", "settings", "devices", "alerts", "users"]
results = []


def log(ok, name, detail=""):
    results.append((ok, name, detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


def watch(page, bag):
    page.on("console", lambda m: bag.append(f"console.{m.type}: {m.text}") if m.type in ("error",) else None)
    page.on("pageerror", lambda e: bag.append(f"pageerror: {e}"))
    page.on("response", lambda r: bag.append(f"HTTP {r.status} {r.request.method} {r.url.replace(BASE, '')}")
            if r.status >= 400 and "/api/" in r.url else None)


def api(page, method, path, body=None):
    return page.evaluate("""async ([m, p, b]) => { const r = await fetch(p, {method: m, credentials: 'same-origin',
        headers: {'X-Harith': '1', 'Content-Type': 'application/json'}, body: b ? JSON.stringify(b) : undefined});
        let j = null; try { j = await r.json(); } catch (e) {} return {status: r.status, body: j}; }""", [method, path, body])


def goto(page, pid):
    page.evaluate(f"location.hash = '{pid}'")
    page.wait_for_function("() => !document.querySelector('main[aria-busy]')", timeout=15000)
    page.wait_for_timeout(300)


with sync_playwright() as p:
    br = p.chromium.launch()
    # ——— كمبيوتر
    ctx = br.new_context(viewport={"width": 1366, "height": 860}, locale="ar")
    page = ctx.new_page(); errs = []; watch(page, errs)
    page.goto(BASE + "/"); page.wait_for_selector("form", timeout=15000)
    # إعداد أول بكلمة مرور قصيرة يجب أن يُرفض
    r = api(page, "POST", "/api/setup", {"username": "admin", "password": "123", "display_name": "مدير"})
    log(r["status"] in (400, 422), "setup rejects a short password", f"status={r['status']} {r['body']}")
    r = api(page, "POST", "/api/setup", {"username": "admin"})
    log(r["status"] == 400 and "'password'" not in json.dumps(r["body"], ensure_ascii=False), "setup without password returns a clear 400", f"status={r['status']} {r['body']}")
    # الإعداد الحقيقي من الواجهة
    inputs = page.locator("form input")
    inputs.nth(0).fill("admin")
    for i in range(inputs.count()):
        t = inputs.nth(i).get_attribute("type")
        if t == "password": inputs.nth(i).fill("Admin-pass-2026")
        elif i > 0 and t in (None, "text") and not inputs.nth(i).input_value(): inputs.nth(i).fill("مهند")
    page.locator("form button[type=submit], form button").first.click()
    page.wait_for_selector("nav.side", timeout=15000)
    log(True, "setup via UI creates the admin and logs in")
    r = api(page, "POST", "/api/setup", {"username": "x", "password": "Another-pass-1"})
    log(r["status"] == 403, "second setup is refused", f"status={r['status']}")

    # ——— كل الصفحات
    for pid in PAGES:
        before = len(errs)
        goto(page, pid)
        txt = page.locator("main").inner_text()
        bad = [e for e in errs[before:]]
        ok = not bad and len(txt.strip()) > 0 and "Loading" not in txt and "جارٍ التحميل" not in txt
        log(ok, f"page {pid} renders without errors", "; ".join(bad)[:300] if bad else "")
        page.screenshot(path=f"{OUT}/desk_{pid}.png", full_page=False)

    # ——— المهام: إضافة، تعديل، إكمال، بحث، حذف
    r = api(page, "POST", "/api/tasks", {"title": "مراجعة عقد المورد", "priority": "high", "due": "2030-01-05"})
    tid = (r["body"] or {}).get("task", {}).get("id")
    log(r["status"] == 200 and bool(tid), "create task", json.dumps(r["body"], ensure_ascii=False)[:200])
    goto(page, "tasks")
    log("مراجعة عقد المورد" in page.locator("main").inner_text(), "new task shows on the tasks page")
    r = api(page, "PATCH", f"/api/tasks/{tid}", {"title": "مراجعة عقد المورد النهائي"})
    log(r["status"] == 200, "edit task title", str(r))
    lst = api(page, "GET", "/api/tasks?filter=open")["body"]
    log(any(t.get("title") == "مراجعة عقد المورد النهائي" for t in (lst or [])), "edited title is saved in the database")
    r = api(page, "GET", "/api/search?q=" + "المورد")
    log(r["status"] == 200 and "المورد" in json.dumps(r["body"], ensure_ascii=False), "search finds the task", str(r["status"]))
    r = api(page, "PATCH", f"/api/tasks/{tid}", {"status": "done"})
    lst = api(page, "GET", "/api/tasks?filter=open")["body"]
    log(r["status"] == 200 and not any(t.get("id") == tid for t in (lst or [])), "completed task leaves the open filter")
    r = api(page, "DELETE", f"/api/tasks/{tid}")
    alls = api(page, "GET", "/api/tasks?filter=all")["body"]
    log(r["status"] == 200 and not any(t.get("id") == tid for t in (alls or [])), "delete task removes it", str(r))
    r = api(page, "PATCH", "/api/tasks/999999", {"title": "x"})
    log(r["status"] == 404, "editing a missing task returns 404", f"status={r['status']}")
    r = api(page, "POST", "/api/tasks", {"title": ""})
    log(r["status"] == 400, "empty task title is rejected", f"status={r['status']}")

    # ——— الذاكرة والعادات والتذكيرات
    r = api(page, "POST", "/api/memory", {"content": "يفضّل الاجتماعات صباحًا"})
    mems = api(page, "GET", "/api/memory")["body"] or []
    mid = next((m["id"] for m in mems if "صباحًا" in (m.get("content") or "")), None)
    log(r["status"] == 200 and mid, "create memory and read it back", str(r))
    r2 = api(page, "PATCH", f"/api/memory/{mid}", {"content": "يفضّل الاجتماعات بعد التاسعة"})
    mems = api(page, "GET", "/api/memory")["body"] or []
    log(r2["status"] == 200 and any("التاسعة" in (m.get("content") or "") for m in mems), "edit memory is saved", str(r2))
    r3 = api(page, "DELETE", f"/api/memory/{mid}")
    mems = api(page, "GET", "/api/memory")["body"] or []
    log(r3["status"] == 200 and not any(m["id"] == mid for m in mems), "delete memory removes it", str(r3))
    r = api(page, "POST", "/api/reminders", {"text": "اتصل بالمحاسب", "when": "2030-01-05 09:00"})
    rid = ((r["body"] or {}).get("reminder") or {}).get("id")
    rl = api(page, "GET", "/api/reminders")["body"] or []
    log(r["status"] == 200 and any(x.get("text") == "اتصل بالمحاسب" for x in rl), "create reminder and list it", json.dumps(r["body"], ensure_ascii=False)[:200])
    if rid:
        r = api(page, "DELETE", f"/api/reminders/{rid}")
        rl = api(page, "GET", "/api/reminders")["body"] or []
        log(r["status"] == 200 and all(x.get("status") != "pending" for x in rl if x.get("id") == rid), "cancel reminder", str(r))

    # ——— المستخدمون والصلاحيات
    r = api(page, "POST", "/api/users", {"username": "sara", "password": "Sara-pass-2026", "role": "user"})
    log(r["status"] == 200, "admin creates a normal user", str(r))
    r = api(page, "POST", "/api/users", {"username": "sara", "password": "Sara-pass-2026", "role": "user"})
    log(r["status"] == 409, "duplicate username is refused cleanly", f"status={r['status']} {r['body']}")
    r = api(page, "POST", "/api/users", {"username": "weak", "password": "1"})
    log(r["status"] == 400, "weak password for new user is refused", f"status={r['status']} {r['body']}")
    r = api(page, "POST", "/api/tasks", {"title": "مهمة المدير الخاصة"}); admin_tid = r["body"]["task"]["id"]
    sara_id = next(x["id"] for x in api(page, "GET", "/api/users")["body"] if x["username"] == "sara")
    # حدث حقيقي: تغيير صلاحية سارة مرتين ← إشعاران لها (لا للمدير)
    api(page, "PATCH", f"/api/users/{sara_id}", {"role": "admin"}); api(page, "PATCH", f"/api/users/{sara_id}", {"role": "user"})
    # خروج من الواجهة
    goto(page, "home")
    page.get_by_role("button", name=re.compile("خروج|Sign out")).first.click()
    page.wait_for_selector("form input[type=password]", timeout=10000)
    log(api(page, "GET", "/api/me")["status"] == 401, "logout ends the session")
    # دخول خاطئ ثم صحيح
    r = api(page, "POST", "/api/login", {"username": "sara", "password": "wrong-pass"})
    log(r["status"] == 401, "wrong password is refused", f"status={r['status']}")
    pw = page.locator("form input")
    pw.nth(0).fill("sara"); page.locator("form input[type=password]").fill("Sara-pass-2026")
    page.locator("form button").first.click(); page.wait_for_selector("nav.side", timeout=10000)
    log(True, "normal user logs in via UI")
    # ——— مركز الإشعارات عند سارة
    page.wait_for_function("() => [...document.querySelectorAll('.notice-badge')].some(b => !b.hidden && b.getAttribute('aria-label') === '2')", timeout=45000)
    log(True, "notification badge shows 2 unread after two real role changes")
    page.locator("nav.side .nav-link", has_text="الإشعارات").click()
    page.wait_for_selector(".notice-row")
    rows = page.locator(".notice-row")
    log(rows.count() == 2 and page.locator(".notice-row.unread").count() == 2, "list shows both notices as unread", str(rows.count()))
    log("مستخدم" in rows.nth(0).inner_text() and "مدير" in rows.nth(1).inner_text(), "newest first, correct text", rows.nth(0).inner_text()[:80])
    rows.nth(0).locator(".notice-open").click()
    page.wait_for_function("() => location.hash === '#settings'", timeout=5000)
    page.wait_for_function("() => [...document.querySelectorAll('.notice-badge')].every(b => b.getAttribute('aria-label') === '1')", timeout=8000)
    log(True, "opening a notice goes to its page and drops the unread count to 1")
    page.locator("nav.side .nav-link", has_text="الإشعارات").click(); page.wait_for_selector(".notice-row")
    page.get_by_role("button", name="تحديد الكل كمقروء").click()
    page.wait_for_function("() => document.querySelectorAll('.notice-row.unread').length === 0", timeout=8000)
    log(api(page, "GET", "/api/notifications/summary")["body"]["unread"] == 0, "mark all read sets unread to 0 (saved on server)")
    page.locator(".notice-row").nth(0).get_by_role("button", name="حذف الإشعار").click()
    page.wait_for_function("() => document.querySelectorAll('.notice-row').length === 1", timeout=8000)
    log(len(api(page, "GET", "/api/notifications/summary")["body"]["items"]) == 1, "delete removes one notice on the server")
    page.keyboard.press("Escape"); page.locator(".modal-back").evaluate("n => n.remove()")
    side = page.locator("nav.side").inner_text()
    log("المستخدمون" not in side and "مستخدمو التطبيق" not in side, "admin pages hidden from a normal user's menu")
    for path in ["/api/users", "/api/devices", "/api/admin/invites", "/api/backups", "/api/events"]:
        st = api(page, "GET", path)["status"]
        log(st == 403, f"normal user blocked from {path}", f"status={st}")
    st = api(page, "PATCH", f"/api/tasks/{admin_tid}", {"title": "hacked"})["status"]
    log(st == 404, "normal user can't edit the admin's task", f"status={st}")
    st = api(page, "DELETE", f"/api/tasks/{admin_tid}")["status"]
    log(st == 404 and True, "normal user can't delete the admin's task", f"status={st}")
    mine = api(page, "GET", "/api/tasks?filter=all")["body"]
    log(not any("المدير" in (t.get("title") or "") for t in mine), "normal user doesn't see the admin's tasks")
    for pid in PAGES[:16]:
        before = len(errs); goto(page, pid)
        bad = [e for e in errs[before:]]
        log(not bad, f"user page {pid} without errors", "; ".join(bad)[:300])
    ctx.close()

    # ——— جوال
    for name, vp in [("phone", {"width": 390, "height": 844}), ("tablet", {"width": 820, "height": 1180})]:
        c = br.new_context(viewport=vp, locale="ar", is_mobile=name == "phone", has_touch=True)
        pg = c.new_page(); e2 = []; watch(pg, e2)
        pg.goto(BASE + "/"); pg.wait_for_selector("form input[type=password]")
        pg.locator("form input").nth(0).fill("admin"); pg.locator("form input[type=password]").fill("Admin-pass-2026")
        pg.locator("form button").first.click(); pg.wait_for_selector("main.main", timeout=10000)
        over = []
        for pid in PAGES:
            goto(pg, pid)
            w = pg.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
            if w > 2: over.append(f"{pid}(+{w}px)")
            pg.screenshot(path=f"{OUT}/{name}_{pid}.png")
        log(not over, f"{name}: no horizontal overflow on any page", ", ".join(over))
        e2 = [x for x in e2 if "/api/me" not in x and "401" not in x]  # قبل الدخول: 401 من /api/me متوقع
        log(not e2, f"{name}: no browser/API errors", "; ".join(e2)[:300])
        c.close()
    br.close()

fails = [r for r in results if not r[0]]
print(f"\nTOTAL {len(results)} PASS {len(results) - len(fails)} FAIL {len(fails)}")
json.dump([{"ok": a, "name": b, "detail": c} for a, b, c in results], open(f"{OUT}/web_e2e.json", "w"), ensure_ascii=False, indent=1)
