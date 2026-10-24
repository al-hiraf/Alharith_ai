"""اختبارات طبقة الأعمال: فصل الجهات، المالية، الفواتير، الاجتماعات، التصدير، الموافقات، والقيود."""
import asyncio
from datetime import date, timedelta

import pytest

from harith.ai import AIResponse
from harith.business import business_alerts, global_search, invoice_html, parse_amount
from harith.core import biz_alerts_text
from harith.toolkit import Ctx
from harith.xlsx import read_xlsx_rows

from test_acceptance import call, client, script


def run(c):
    return asyncio.run(c)


def ex(app, user, _tool, **a):
    return run(app.tools.execute(Ctx(app, user, "test"), _tool, a))


def test_parse_amount_variants():
    assert parse_amount("5,000") == 500000
    assert parse_amount("٢٥٠٠") == 250000
    assert parse_amount("1,250.50") == 125050
    assert parse_amount(99.99) == 9999
    assert parse_amount("12,5") == 1250
    for bad in ("0", "-5", "", "abc"):
        with pytest.raises(ValueError):
            parse_amount(bad)


def test_personal_and_company_money_never_mix(app, user):
    assert ex(app, user, "workspace_create", name="مؤسسة البناء", currency="SAR").ok
    assert ex(app, user, "finance_record", kind="income", amount="10000", workspace="مؤسسة البناء", category="مبيعات").ok
    assert ex(app, user, "finance_record", kind="expense", amount="3000", workspace="البناء", category="مواد").ok
    assert ex(app, user, "finance_record", kind="expense", amount="400", category="مطاعم").ok  # شخصي
    co = ex(app, user, "workspace_overview", workspace="مؤسسة البناء").data["finance"]["totals"][0]
    me = ex(app, user, "workspace_overview").data["finance"]["totals"][0]
    assert (co["income"], co["expense"], co["net"]) == (10000, 3000, 7000)
    assert (me["income"], me["expense"]) == (0, 400)
    allv = ex(app, user, "finance_overview_all").data
    assert {x["workspace"] for x in allv} == {"شخصي", "مؤسسة البناء"}


def test_unknown_workspace_is_rejected_not_guessed(app, user):
    r = ex(app, user, "finance_record", kind="expense", amount="50", workspace="شركة لا توجد")
    assert not r.ok and "لا توجد" in r.text
    assert app.db.one("SELECT COUNT(*) c FROM ledger")["c"] == 0


def test_currencies_are_not_converted(app, user):
    ex(app, user, "finance_record", kind="income", amount="100", currency="USD")
    ex(app, user, "finance_record", kind="income", amount="100", currency="ريال")
    tots = {t["currency"]: t["income"] for t in ex(app, user, "workspace_overview").data["finance"]["totals"]}
    assert tots == {"USD": 100, "SAR": 100}


def test_record_is_not_payment_and_says_so(app, user):
    r = ex(app, user, "finance_record", kind="expense", amount="700", category="إيجار")
    assert r.ok and r.verified and "لم يُنفّذ أي دفع" in r.text


def test_budget_warning(app, user):
    ex(app, user, "workspace_create", name="متجر")
    ex(app, user, "budget_set", workspace="متجر", category="تسويق", amount="1000")
    assert "85٪" in ex(app, user, "finance_record", kind="expense", amount="850", workspace="متجر", category="تسويق").text
    r = ex(app, user, "finance_record", kind="expense", amount="300", workspace="متجر", category="تسويق")
    assert "تجاوزت" in r.text
    b = ex(app, user, "workspace_overview", workspace="متجر").data["finance"]["budgets"][0]
    assert b["percent"] == 115


def test_invoice_vat_payment_and_overdue(app, user):
    ex(app, user, "workspace_create", name="مقاولات الحارث", vat_rate=15)
    r = ex(app, user, "invoice_create", kind="invoice", workspace="مقاولات الحارث", contact="شركة النخبة",
           items=[{"description": "أعمال خرسانة", "qty": 2, "price": 5000}], issued_on="2026-01-01", due_on="2026-01-31")
    assert r.ok and r.data["total"] == 11500 and r.data["vat"] == 1500 and r.data["status"] == "draft"
    assert "لم تُرسل لأحد" in r.text
    num = r.data["number"]
    assert num.startswith("INV-2026-")
    # المسودة لا تُعد متأخرة حتى يقول المستخدم إنه أرسلها
    assert not business_alerts(app.db, user["id"], user["timezone"])["overdue_invoices"]
    ex(app, user, "invoice_update", invoice=num, status="sent")
    al = business_alerts(app.db, user["id"], user["timezone"])["overdue_invoices"]
    assert al and al[0]["number"] == num
    p = ex(app, user, "invoice_update", invoice=num, payment="5000")
    assert p.ok and p.data["status"] == "overdue" and p.data["outstanding"] == 6500  # جزئي ومتأخر
    assert app.db.one("SELECT status FROM invoices WHERE number=?", (num,))["status"] == "partial"
    too_much = ex(app, user, "invoice_update", invoice=num, payment="999999")
    assert not too_much.ok
    ex(app, user, "invoice_update", invoice=num, payment="6500")
    inv = app.db.one("SELECT * FROM invoices WHERE number=?", (num,))
    assert inv["status"] == "paid" and inv["paid_minor"] == 1150000
    # الدفعات أصبحت قيود دخل في نفس الجهة فقط
    ws = app.db.one("SELECT id FROM workspaces WHERE name='مقاولات الحارث'")["id"]
    assert app.db.one("SELECT SUM(amount_minor) s FROM ledger WHERE invoice_id=? AND workspace_id=?", (inv["id"], ws))["s"] == 1150000
    assert app.db.one("SELECT COUNT(*) c FROM contacts WHERE name='شركة النخبة' AND kind='client'")["c"] == 1


def test_quote_to_invoice_and_numbers_unique(app, user):
    ex(app, user, "workspace_create", name="ورشة")
    q = ex(app, user, "invoice_create", kind="quote", workspace="ورشة", contact="أبو خالد", amount="2000", title="صيانة")
    assert q.data["number"].startswith("QT-") and not ex(app, user, "invoice_update", invoice=q.data["number"], payment="10").ok
    inv = ex(app, user, "invoice_from_quote", quote=q.data["number"])
    assert inv.ok and inv.data["kind"] == "invoice" and inv.data["total"] == 2300
    assert app.db.one("SELECT status FROM invoices WHERE number=?", (q.data["number"],))["status"] == "accepted"
    a = ex(app, user, "invoice_create", kind="invoice", workspace="ورشة", amount="10")
    assert a.data["number"] != inv.data["number"]


def test_invoice_document_is_safe_and_honest(app, user):
    r = ex(app, user, "invoice_create", kind="invoice", contact="<script>alert(1)</script>", amount="100")
    inv = app.db.one("SELECT * FROM invoices WHERE id=?", (r.data["id"],))
    h = invoice_html(app.db, user["id"], inv)
    assert "<script>alert" not in h and "&lt;script&gt;" in h
    assert "ليس فاتورة ضريبية إلكترونية معتمدة" in h
    d = ex(app, user, "invoice_document", invoice=r.data["number"])
    assert d.ok and d.verified and d.data["name"].endswith(".html")


def test_finance_delete_needs_approval(app, user):
    lid = ex(app, user, "finance_record", kind="expense", amount="50").data["id"]
    r = ex(app, user, "finance_delete", id=lid)
    assert "طلب موافقة" in r.text
    assert app.db.one("SELECT 1 FROM ledger WHERE id=?", (lid,))
    aid = r.data["approval_id"]
    run(app.decide_approval(user["id"], aid, True))
    assert app.db.one("SELECT 1 FROM ledger WHERE id=?", (lid,)) is None


def test_meeting_minutes_become_real_tasks(app, user):
    ex(app, user, "workspace_create", name="مكتب")
    ex(app, user, "create_project", name="فيلا الياسمين", workspace="مكتب")
    when = (date.today() + timedelta(days=3)).isoformat() + "T10:00"
    m = ex(app, user, "meeting_add", title="اجتماع المالك", when=when, workspace="مكتب", project="فيلا الياسمين",
           agenda="الجدول الزمني")
    assert m.ok and "سأذكّرك" in m.text and "لم أرسل دعوات" in m.text
    mid = m.data["id"]
    assert app.db.one("SELECT reminder_id FROM meetings WHERE id=?", (mid,))["reminder_id"]
    r = ex(app, user, "meeting_record", meeting=mid, minutes="نوقش التأخير",
           decisions=["اعتماد مقاول باطن للتشطيب"],
           actions=[{"title": "إرسال جدول زمني محدث", "owner": "م. أحمد", "due": when}, {"title": "طلب عرض سعر بلاط"}])
    assert r.ok and r.verified and len(r.data["tasks"]) == 2
    t = app.db.one("SELECT * FROM tasks WHERE title='إرسال جدول زمني محدث'")
    assert "م. أحمد" in t["notes"] and t["project_id"]
    assert app.db.one("SELECT 1 FROM project_entries WHERE kind='decision' AND content LIKE '%مقاول باطن%'")
    assert app.db.one("SELECT status FROM meetings WHERE id=?", (mid,))["status"] == "done"


def test_export_xlsx_real_file(app, user):
    ex(app, user, "workspace_create", name="شركة أ")
    ex(app, user, "finance_record", kind="income", amount="1500", workspace="شركة أ", category="مبيعات", date="2026-03-05")
    ex(app, user, "finance_record", kind="expense", amount="200.5", workspace="شركة أ", category="وقود", date="2026-03-06")
    r = ex(app, user, "export_report", kind="ledger", workspace="شركة أ", month="2026-03", format="xlsx")
    assert r.ok and r.verified and r.data["rows"] == 2
    from harith.tools import file_path
    f = app.db.one("SELECT * FROM files WHERE id=?", (r.data["file_id"],))
    rows = read_xlsx_rows(file_path(app, user["id"], f).read_bytes())
    assert rows[0][0] == "التاريخ" and rows[1][3] == "1500.0" and rows[2][2] == "وقود"
    rep = ex(app, user, "export_report", kind="report", workspace="شركة أ", month="2026-03", format="xlsx")
    assert rep.ok
    html = ex(app, user, "export_report", kind="report", workspace="شركة أ", month="2026-03", format="html")
    assert html.ok and "PDF" in html.text


def test_report_assumptions_and_no_invented_forecast(app, user):
    d = ex(app, user, "finance_report").data
    assert d["forecast"] == []  # لا بيانات ⇒ لا توقع مخترع
    assert any("لا يوجد ربط" in x for x in d["assumptions"])


def test_recurring_alert_does_not_post_expense(app, user):
    from harith.timeutil import local_now
    today = local_now(user["timezone"]).date()
    dom = min(today.day, 28)
    r = ex(app, user, "recurring_add", amount="3000", category="إيجار", day_of_month=dom)
    assert r.ok and "لن يُسجَّل" in r.text
    b = business_alerts(app.db, user["id"], user["timezone"])
    if today.day <= 28:
        assert "إيجار" in biz_alerts_text(b, today)
    assert app.db.one("SELECT COUNT(*) c FROM ledger")["c"] == 0


def test_contract_end_alerts(app, user):
    from harith.timeutil import local_now
    today = local_now(user["timezone"]).date()
    ex(app, user, "contact_add", kind="employee", name="سالم", role="مهندس موقع",
       contract_end=(today + timedelta(days=7)).isoformat())
    assert "ينتهي بعد 7" in biz_alerts_text(business_alerts(app.db, user["id"], user["timezone"]), today)


def test_global_search(app, user):
    ex(app, user, "contact_add", kind="supplier", name="مصنع الخليج للحديد", phone="0500000000")
    ex(app, user, "add_task", title="الاتصال بمصنع الخليج")
    hits = global_search(app.db, user["id"], "الخليج", user["timezone"])
    assert {h["type"] for h in hits} >= {"contact", "task"}
    assert global_search(app.db, user["id"], "x", user["timezone"]) == []


def test_web_endpoints_and_tool_gate(app, user):
    c = client(app)
    r = c.post("/api/tool/workspace_create", json={"name": "شركة الويب"}).json()
    assert r["ok"]
    assert c.post("/api/tool/finance_record", json={"kind": "income", "amount": "900", "workspace": "شركة الويب"}).json()["ok"]
    ov = c.get("/api/biz/overview", params={"workspace": "شركة الويب"}).json()
    assert ov["finance"]["totals"][0]["income"] == 900
    # أدوات غير تجارية (مثل send_email) لا تُستدعى من هذا المسار
    assert c.post("/api/tool/send_email", json={"to": "a@b.c", "subject": "x", "body": "y"}).status_code == 404
    assert c.post("/api/tool/delete_file", json={"file": "1"}).status_code == 404
    inv = c.post("/api/tool/invoice_create", json={"kind": "invoice", "amount": "100", "workspace": "شركة الويب"}).json()
    p = c.get(f"/api/biz/invoices/{inv['data']['id']}/print")
    assert p.status_code == 200 and "طباعة" in p.text and "script-src 'self'" in p.headers["content-security-policy"]
    assert c.get("/api/search", params={"q": "شركة الويب"}).json()
    # عزل المستخدمين
    from harith.security import create_user
    create_user(app.db, "other", "password123")
    from starlette.testclient import TestClient
    from harith.web import create_app
    o = TestClient(create_app(app, manage_lifecycle=False))
    o.post("/api/login", json={"username": "other", "password": "password123"})
    o.headers["X-Harith"] = "1"
    assert o.get(f"/api/biz/invoices/{inv['data']['id']}/print").status_code == 404
    assert o.get("/api/biz/overview", params={"workspace": "شركة الويب"}).status_code == 400


def test_kill_switch_blocks_business_writes(app, user):
    app.set_paused(True, user["id"])
    r = ex(app, user, "finance_record", kind="income", amount="10")
    assert not r.ok and app.db.one("SELECT COUNT(*) c FROM ledger")["c"] == 0


def test_agent_records_expense_via_voice_style_command(app, user):
    script(app, call("finance_record", kind="expense", amount="٢٥٠", category="وقود"),
           AIResponse("سجّلت مصروف وقود 250 ر.س في حسابك الشخصي."))
    c = client(app)
    r = c.post("/api/chat", json={"text": "سجل مصروف بنزين ٢٥٠"}).json()
    assert r["status"] == "success"
    assert app.db.one("SELECT amount_minor FROM ledger")["amount_minor"] == 25000
