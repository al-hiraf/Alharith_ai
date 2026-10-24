"""طبقة الأعمال: مساحات الشركات، المالية (فصل الشخصي عن كل شركة)، الفواتير والعروض، العملاء والموردون والموظفون،
مؤشرات الأداء، الاجتماعات ومحاضرها، التصدير إلى Excel/طباعة PDF.

مبادئ ثابتة:
- كل رقم مالي يأتي من تسجيل المستخدم فقط؛ لا ربط بنكي ولا أرصدة مخترعة، والتقارير تقول ذلك صراحة.
- لا تحويل عملات تلقائي (لا أسعار صرف مخترعة): كل عملة تُجمع وحدها.
- لا دفع ولا إرسال خارجي ولا اعتماد نهائي من هنا؛ الحذف المالي يحتاج موافقة.
- المبالغ تُخزن بالهللة/السنت (أعداد صحيحة) لتجنب أخطاء الكسور.
"""
from __future__ import annotations

import html
import json
import re
from datetime import date, datetime, timedelta

from .db import now_iso
from .timeutil import local_now, parse_local, to_local_str
from .toolkit import NEEDS_APPROVAL, Ctx, Registry, Tool, ToolResult, i, obj, s
from .xlsx import build_xlsx

WS_KINDS = ["personal", "company", "activity"]
WS_AR = {"personal": "شخصي", "company": "شركة", "activity": "نشاط"}
CONTACT_KINDS = ["client", "supplier", "lead", "partner", "employee"]
CONTACT_AR = {"client": "عميل", "supplier": "مورد", "lead": "عميل محتمل", "partner": "شريك", "employee": "موظف"}
LEAD_STAGES = ["new", "contacted", "proposal", "negotiation", "won", "lost"]
DOC_KINDS = ["invoice", "quote", "bill"]
DOC_AR = {"invoice": "فاتورة", "quote": "عرض سعر", "bill": "فاتورة مورد"}
DOC_PREFIX = {"invoice": "INV", "quote": "QT", "bill": "BILL"}
DOC_STATUSES = ["draft", "sent", "accepted", "rejected", "partial", "paid", "cancelled"]
STATUS_AR = {"draft": "مسودة", "sent": "أُرسلت", "accepted": "مقبول", "rejected": "مرفوض", "partial": "مدفوعة جزئيًا",
             "paid": "مدفوعة", "cancelled": "ملغاة", "overdue": "متأخرة"}
_AR_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٫٬", "01234567890123456789.,")
NO_BANK_NOTE = "الأرقام مبنية على ما سجّلته أنت فقط؛ لا يوجد ربط بحساب بنكي أو نظام محاسبي."


# ═══════════════ أدوات مساعدة
def parse_amount(v) -> int:
    """'5,000' أو '٥٠٠٠' أو 1250.5 ← بالهللة. يرفض الصفر والسالب."""
    if v is None or v == "":
        raise ValueError("المبلغ مطلوب")
    if isinstance(v, (int, float)):
        x = float(v)
    else:
        t = str(v).translate(_AR_DIGITS).replace(" ", "")
        t = re.sub(r"[^\d.,\-]", "", t)
        if t.count(",") and t.count("."):
            t = t.replace(",", "")
        elif t.count(",") == 1 and len(t.split(",")[1]) <= 2:
            t = t.replace(",", ".")
        else:
            t = t.replace(",", "")
        if not t or t in ("-", "."):
            raise ValueError(f"مبلغ غير مفهوم: {v}")
        x = float(t)
    minor = int(round(x * 100))
    if minor <= 0:
        raise ValueError("المبلغ يجب أن يكون أكبر من صفر")
    if minor > 10**15:
        raise ValueError("المبلغ كبير جدًا")
    return minor


def money(minor: int | None, cur: str = "") -> str:
    v = (minor or 0) / 100
    txt = f"{v:,.2f}".rstrip("0").rstrip(".") if v != int(v) else f"{int(v):,}"
    return f"{txt} {cur_label(cur)}".strip()


def cur_label(cur: str) -> str:
    return {"SAR": "ر.س", "USD": "$", "AED": "د.إ", "EUR": "€", "KWD": "د.ك", "QAR": "ر.ق", "BHD": "د.ب",
            "OMR": "ر.ع", "EGP": "ج.م", "JOD": "د.أ"}.get(cur or "", cur or "")


def norm_currency(v, default: str = "SAR") -> str:
    if not v:
        return default
    t = str(v).strip().upper()
    alias = {"ريال": "SAR", "ر.س": "SAR", "ريال سعودي": "SAR", "SR": "SAR", "دولار": "USD", "$": "USD",
             "درهم": "AED", "يورو": "EUR", "€": "EUR", "دينار كويتي": "KWD", "جنيه": "EGP", "ريال قطري": "QAR"}
    t = alias.get(str(v).strip(), t)
    if not re.fullmatch(r"[A-Z]{3}", t):
        raise ValueError(f"رمز العملة غير معروف: {v} (استخدم مثل SAR أو USD)")
    return t


def parse_day(v, tzname: str) -> str:
    """تاريخ محلي YYYY-MM-DD. يقبل today/اليوم/أمس."""
    today = local_now(tzname).date()
    if not v:
        return today.isoformat()
    t = str(v).strip().translate(_AR_DIGITS).lower()
    if t in ("today", "اليوم"):
        return today.isoformat()
    if t in ("yesterday", "أمس", "امس"):
        return (today - timedelta(days=1)).isoformat()
    if t in ("tomorrow", "غدا", "غدًا", "بكرة"):
        return (today + timedelta(days=1)).isoformat()
    try:
        return date.fromisoformat(t[:10]).isoformat()
    except ValueError:
        raise ValueError(f"تاريخ غير صالح: {v} (الصيغة YYYY-MM-DD)")


def _month(v, tzname: str) -> str:
    if not v:
        return local_now(tzname).strftime("%Y-%m")
    t = str(v).strip().translate(_AR_DIGITS)
    if not re.fullmatch(r"\d{4}-\d{2}", t):
        raise ValueError("الشهر بصيغة YYYY-MM")
    return t


def _months_back(ym: str, n: int) -> list[str]:
    y, m = map(int, ym.split("-"))
    out = []
    for _ in range(n):
        out.append(f"{y:04d}-{m:02d}")
        m -= 1
        if m == 0:
            y, m = y - 1, 12
    return list(reversed(out))


def audit(db, uid: int, ws: int | None, entity: str, eid: int | None, action: str, detail: str = "") -> None:
    db.execute("INSERT INTO audit(user_id,workspace_id,entity,entity_id,action,detail,created_at) VALUES(?,?,?,?,?,?,?)",
               (uid, ws, entity, eid, action, detail[:500], now_iso()))


def personal_ws(db, uid: int) -> dict:
    w = db.one("SELECT * FROM workspaces WHERE user_id=? AND kind='personal' ORDER BY id LIMIT 1", (uid,))
    if w:
        return w
    now = now_iso()
    wid = db.insert("INSERT INTO workspaces(user_id,name,kind,currency,vat_rate,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?,?)", (uid, "شخصي", "personal", "SAR", 0, now, now))
    return db.one("SELECT * FROM workspaces WHERE id=?", (wid,))


def find_ws(db, uid: int, ref, default_personal: bool = True) -> dict:
    if ref in (None, "", 0):
        if default_personal:
            return personal_ws(db, uid)
        raise ValueError("حدد الشركة أو المساحة")
    r = str(ref).strip()
    if r in ("شخصي", "personal", "الشخصي", "حسابي الشخصي"):
        return personal_ws(db, uid)
    if r.isdigit():
        w = db.one("SELECT * FROM workspaces WHERE id=? AND user_id=?", (int(r), uid))
    else:
        w = (db.one("SELECT * FROM workspaces WHERE user_id=? AND name=? AND status='active'", (uid, r)) or
             db.one("SELECT * FROM workspaces WHERE user_id=? AND name LIKE ? AND status='active' ORDER BY id",
                    (uid, f"%{r}%")))
    if not w:
        raise ValueError(f"لا توجد شركة/مساحة بهذا الاسم: {ref}. أنشئها أولًا (workspace_create).")
    return w


def _project(db, uid: int, ref, ws: dict | None = None) -> int | None:
    if ref in (None, "", 0):
        return None
    r = str(ref)
    p = (db.one("SELECT id, workspace_id FROM projects WHERE id=? AND user_id=?", (int(r), uid)) if r.isdigit() else
         db.one("SELECT id, workspace_id FROM projects WHERE user_id=? AND name LIKE ? ORDER BY id DESC", (uid, f"%{r}%")))
    if not p:
        raise ValueError(f"لا يوجد مشروع: {ref}")
    if ws and p["workspace_id"] and p["workspace_id"] != ws["id"]:
        raise ValueError("المشروع يتبع جهة أخرى؛ لا يمكن خلط أموال الجهات")
    return int(p["id"])


def _contact(db, uid: int, ref, kinds: tuple[str, ...] | None = None, create_kind: str | None = None,
             ws: dict | None = None) -> int | None:
    if ref in (None, "", 0):
        return None
    r = str(ref).strip()
    q = "SELECT id FROM contacts WHERE user_id=? AND status='active'"
    if r.isdigit():
        c = db.one(q + " AND id=?", (uid, int(r)))
    else:
        c = db.one(q + " AND (name=? OR company=?)", (uid, r, r)) or \
            db.one(q + " AND (name LIKE ? OR company LIKE ?) ORDER BY id DESC", (uid, f"%{r}%", f"%{r}%"))
    if c:
        return int(c["id"])
    if create_kind and not r.isdigit():
        now = now_iso()
        cid = db.insert("INSERT INTO contacts(user_id,workspace_id,kind,name,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                        (uid, ws["id"] if ws else None, create_kind, r[:120], now, now))
        audit(db, uid, ws["id"] if ws else None, "contact", cid, "create", f"أُنشئ تلقائيًا: {r}")
        return cid
    raise ValueError(f"لا توجد جهة اتصال: {ref}")


def _ws_view(w: dict) -> dict:
    return {"id": w["id"], "name": w["name"], "kind": w["kind"], "kind_ar": WS_AR.get(w["kind"], w["kind"]),
            "currency": w["currency"], "vat_rate": w["vat_rate"], "goals": w["goals"], "status": w["status"]}


def _outstanding(inv: dict) -> int:
    return max(0, inv["total_minor"] - inv["paid_minor"])


def _inv_state(inv: dict, today: str) -> str:
    if inv["doc_kind"] != "quote" and inv["status"] in ("sent", "partial", "draft") and inv["due_on"] and \
            inv["due_on"] < today and _outstanding(inv) > 0 and inv["status"] != "draft":
        return "overdue"
    return inv["status"]


def _inv_view(db, inv: dict, today: str) -> dict:
    c = db.one("SELECT name, company, phone, email FROM contacts WHERE id=?", (inv["contact_id"],)) if inv["contact_id"] else None
    w = db.one("SELECT name FROM workspaces WHERE id=?", (inv["workspace_id"],))
    st = _inv_state(inv, today)
    return {"id": inv["id"], "number": inv["number"], "kind": inv["doc_kind"], "kind_ar": DOC_AR[inv["doc_kind"]],
            "title": inv["title"], "workspace": w["name"] if w else "", "workspace_id": inv["workspace_id"],
            "contact": (c["name"] if c else ""), "contact_id": inv["contact_id"], "currency": inv["currency"],
            "subtotal": inv["subtotal_minor"] / 100, "vat_rate": inv["vat_rate"], "vat": inv["vat_minor"] / 100,
            "total": inv["total_minor"] / 100, "paid": inv["paid_minor"] / 100,
            "outstanding": _outstanding(inv) / 100, "total_text": money(inv["total_minor"], inv["currency"]),
            "outstanding_text": money(_outstanding(inv), inv["currency"]),
            "issued_on": inv["issued_on"], "due_on": inv["due_on"] or "", "status": st,
            "status_ar": STATUS_AR.get(st, st), "items": json.loads(inv["items"] or "[]"), "notes": inv["notes"]}


def _ledger_view(r: dict) -> dict:
    return {"id": r["id"], "kind": r["kind"], "kind_ar": "دخل" if r["kind"] == "income" else "مصروف",
            "amount": r["amount_minor"] / 100, "amount_text": money(r["amount_minor"], r["currency"]),
            "currency": r["currency"], "category": r["category"], "note": r["note"], "date": r["occurred_on"],
            "workspace_id": r["workspace_id"], "project_id": r["project_id"], "contact_id": r["contact_id"],
            "invoice_id": r["invoice_id"]}


# ═══════════════ المساحات (شركات/أنشطة/شخصي)
async def workspace_create(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    name = (a.get("name") or "").strip()
    if not name:
        raise ValueError("اسم الشركة/المساحة مطلوب")
    kind = a.get("kind") if a.get("kind") in WS_KINDS else "company"
    if kind == "personal":
        w = personal_ws(db, ctx.uid)
        return ToolResult(True, "المساحة الشخصية موجودة", _ws_view(w), verified=True)
    if db.one("SELECT 1 FROM workspaces WHERE user_id=? AND name=? AND status='active'", (ctx.uid, name)):
        raise ValueError("توجد مساحة بنفس الاسم")
    cur = norm_currency(a.get("currency"))
    vat = float(a["vat_rate"]) if a.get("vat_rate") not in (None, "") else (15.0 if cur == "SAR" else 0.0)
    if not 0 <= vat <= 100:
        raise ValueError("نسبة الضريبة بين 0 و100")
    now = now_iso()
    wid = db.insert("INSERT INTO workspaces(user_id,name,kind,currency,vat_rate,goals,notes,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?)", (ctx.uid, name, kind, cur, vat, a.get("goals") or "",
                                                 a.get("notes") or "", now, now))
    audit(db, ctx.uid, wid, "workspace", wid, "create", name)
    w = db.one("SELECT * FROM workspaces WHERE id=? AND user_id=?", (wid, ctx.uid))
    return ToolResult(bool(w), f"أُنشئت مساحة «{name}» برقم {wid}", _ws_view(w), verified=bool(w))


async def workspace_update(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    w = find_ws(db, ctx.uid, a.get("workspace"), default_personal=False)
    sets, vals = [], []
    for k in ("name", "goals", "notes"):
        if a.get(k) is not None:
            sets.append(f"{k}=?")
            vals.append(str(a[k]))
    if a.get("currency"):
        sets.append("currency=?")
        vals.append(norm_currency(a["currency"]))
    if a.get("vat_rate") not in (None, ""):
        sets.append("vat_rate=?")
        vals.append(float(a["vat_rate"]))
    if a.get("status") in ("active", "archived"):
        if w["kind"] == "personal" and a["status"] == "archived":
            raise ValueError("لا يمكن أرشفة المساحة الشخصية")
        sets.append("status=?")
        vals.append(a["status"])
    if not sets:
        raise ValueError("لا توجد تعديلات")
    db.execute(f"UPDATE workspaces SET {', '.join(sets)}, updated_at=? WHERE id=? AND user_id=?",
               (*vals, now_iso(), w["id"], ctx.uid))
    audit(db, ctx.uid, w["id"], "workspace", w["id"], "update", json.dumps({k: a[k] for k in a if k != "workspace"},
                                                                         ensure_ascii=False))
    return ToolResult(True, "عُدّلت المساحة", _ws_view(db.one("SELECT * FROM workspaces WHERE id=?", (w["id"],))),
                      verified=True)


def list_workspaces(db, uid: int) -> list[dict]:
    personal_ws(db, uid)
    return [_ws_view(w) for w in db.all("SELECT * FROM workspaces WHERE user_id=? AND status='active' "
                                        "ORDER BY kind='personal' DESC, id", (uid,))]


async def workspace_list(ctx: Ctx, a: dict) -> ToolResult:
    rows = list_workspaces(ctx.app.db, ctx.uid)
    return ToolResult(True, f"{len(rows)} مساحة", rows, verified=True)


def workspace_overview_data(db, uid: int, w: dict, tzname: str) -> dict:
    today = local_now(tzname).date().isoformat()
    month = today[:7]
    fin = finance_summary(db, uid, w, month, tzname)
    projects = db.all(
        "SELECT p.id, p.name, p.status, p.budget_minor, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id) total, "
        "(SELECT COUNT(*) FROM tasks t WHERE t.project_id=p.id AND t.status='done') done, "
        "(SELECT COALESCE(SUM(amount_minor),0) FROM ledger l WHERE l.project_id=p.id AND l.kind='expense') spent "
        "FROM projects p WHERE p.user_id=? AND p.workspace_id=? AND p.status!='archived' ORDER BY p.updated_at DESC",
        (uid, w["id"]))
    for p in projects:
        p["progress"] = round(100 * p["done"] / p["total"]) if p["total"] else 0
        p["budget_text"] = money(p["budget_minor"], w["currency"]) if p["budget_minor"] else ""
        p["spent_text"] = money(p["spent"], w["currency"])
        p["over_budget"] = bool(p["budget_minor"] and p["spent"] > p["budget_minor"])
    kpis = [{"id": k["id"], "name": k["name"], "target": k["target"], "current": k["current"], "unit": k["unit"],
             "direction": k["direction"],
             "percent": (round(100 * (k["current"] or 0) / k["target"]) if k["target"] and k["direction"] == "up" else
                         (round(100 * k["target"] / k["current"]) if k["current"] and k["target"] else None))}
            for k in db.all("SELECT * FROM kpis WHERE user_id=? AND workspace_id=? ORDER BY id", (uid, w["id"]))]
    meetings = db.all("SELECT id, title, starts_at, status FROM meetings WHERE user_id=? AND workspace_id=? AND "
                      "status='planned' AND starts_at>=? ORDER BY starts_at LIMIT 5", (uid, w["id"], now_iso()))
    for m in meetings:
        m["when"] = to_local_str(m["starts_at"], tzname)
    contacts = db.one("SELECT SUM(kind='client') clients, SUM(kind='supplier') suppliers, SUM(kind='lead') leads, "
                      "SUM(kind='employee') employees FROM contacts WHERE user_id=? AND workspace_id=? AND status='active'",
                      (uid, w["id"]))
    risks = db.all("SELECT e.content, p.name project FROM project_entries e JOIN projects p ON p.id=e.project_id "
                   "WHERE e.user_id=? AND p.workspace_id=? AND e.kind IN ('risk','blocker') AND e.status='open' "
                   "ORDER BY e.id DESC LIMIT 8", (uid, w["id"]))
    log = db.all("SELECT entity, action, detail, created_at FROM audit WHERE user_id=? AND workspace_id=? "
                 "ORDER BY id DESC LIMIT 15", (uid, w["id"]))
    for x in log:
        x["created_at"] = to_local_str(x["created_at"], tzname)
    return {"workspace": _ws_view(w), "finance": fin, "projects": projects, "kpis": kpis, "meetings": meetings,
            "contacts": {k: v or 0 for k, v in (contacts or {}).items()}, "risks": risks, "log": log}


async def workspace_overview(ctx: Ctx, a: dict) -> ToolResult:
    w = find_ws(ctx.app.db, ctx.uid, a.get("workspace"))
    return ToolResult(True, f"ملخص «{w['name']}». {NO_BANK_NOTE}",
                      workspace_overview_data(ctx.app.db, ctx.uid, w, ctx.tz), verified=True)


# ═══════════════ المالية
async def finance_record(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    kind = a.get("kind")
    if kind not in ("income", "expense"):
        raise ValueError("النوع: income (دخل) أو expense (مصروف)")
    w = find_ws(db, ctx.uid, a.get("workspace"))
    amt = parse_amount(a.get("amount"))
    cur = norm_currency(a.get("currency"), w["currency"])
    day = parse_day(a.get("date"), ctx.tz)
    pid = _project(db, ctx.uid, a.get("project"), w)
    cid = _contact(db, ctx.uid, a.get("contact"), create_kind="client" if kind == "income" else "supplier", ws=w)
    cat = (a.get("category") or "عام").strip()[:60]
    lid = db.insert("INSERT INTO ledger(user_id,workspace_id,project_id,contact_id,kind,amount_minor,currency,category,"
                    "note,occurred_on,source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (ctx.uid, w["id"], pid, cid, kind, amt, cur, cat, (a.get("note") or "")[:500], day, ctx.channel,
                     now_iso()))
    audit(db, ctx.uid, w["id"], "ledger", lid, "create", f"{'دخل' if kind == 'income' else 'مصروف'} {money(amt, cur)} — {cat}")
    row = db.one("SELECT * FROM ledger WHERE id=? AND user_id=?", (lid, ctx.uid))
    if not row:
        return ToolResult(False, "لم يُحفظ القيد")
    warn = _budget_warning(db, ctx.uid, w, cat, day[:7], cur) if kind == "expense" else ""
    return ToolResult(True, f"سُجّل {'دخل' if kind == 'income' else 'مصروف'} {money(amt, cur)} في «{w['name']}» "
                            f"(تسجيل فقط — لم يُنفّذ أي دفع أو تحويل).{warn}",
                      _ledger_view(row), verified=True)


def _budget_warning(db, uid: int, w: dict, cat: str, month: str, cur: str) -> str:
    b = db.one("SELECT amount_minor FROM budgets WHERE workspace_id=? AND category=? AND month IN (?, '') "
               "AND currency=? ORDER BY month DESC LIMIT 1", (w["id"], cat, month, cur))
    if not b:
        return ""
    spent = db.one("SELECT COALESCE(SUM(amount_minor),0) s FROM ledger WHERE workspace_id=? AND kind='expense' AND "
                   "category=? AND currency=? AND substr(occurred_on,1,7)=?", (w["id"], cat, cur, month))["s"]
    pct = round(100 * spent / b["amount_minor"])
    if pct >= 100:
        return f" ⚠️ تجاوزت ميزانية «{cat}» لهذا الشهر ({pct}٪)."
    if pct >= 80:
        return f" تنبيه: استهلكت {pct}٪ من ميزانية «{cat}»."
    return ""


async def finance_list(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    w = find_ws(db, ctx.uid, a.get("workspace"))
    q, p = "SELECT * FROM ledger WHERE user_id=? AND workspace_id=?", [ctx.uid, w["id"]]
    if a.get("month"):
        q += " AND substr(occurred_on,1,7)=?"
        p.append(_month(a["month"], ctx.tz))
    if a.get("kind") in ("income", "expense"):
        q += " AND kind=?"
        p.append(a["kind"])
    if a.get("category"):
        q += " AND category LIKE ?"
        p.append(f"%{a['category']}%")
    if a.get("query"):
        q += " AND (note LIKE ? OR category LIKE ?)"
        p += [f"%{a['query']}%"] * 2
    rows = db.all(q + " ORDER BY occurred_on DESC, id DESC LIMIT ?", (*p, min(int(a.get("limit") or 100), 500)))
    return ToolResult(True, f"{len(rows)} قيد في «{w['name']}»", [_ledger_view(r) for r in rows], verified=True)


async def finance_delete(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    r = db.one("SELECT * FROM ledger WHERE id=? AND user_id=?", (int(a["id"]), ctx.uid))
    if not r:
        return ToolResult(False, "القيد غير موجود")
    db.execute("DELETE FROM ledger WHERE id=? AND user_id=?", (r["id"], ctx.uid))
    if r["invoice_id"]:
        _recompute_paid(db, r["invoice_id"])
    audit(db, ctx.uid, r["workspace_id"], "ledger", r["id"], "delete",
          f"{r['kind']} {money(r['amount_minor'], r['currency'])} {r['category']} {r['occurred_on']}")
    gone = db.one("SELECT 1 FROM ledger WHERE id=?", (r["id"],)) is None
    return ToolResult(gone, "حُذف القيد" if gone else "فشل الحذف", verified=gone)


def _fin_del_summary(a: dict) -> str:
    return f"حذف القيد المالي رقم {a.get('id')}"


async def budget_set(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    w = find_ws(db, ctx.uid, a.get("workspace"))
    cat = (a.get("category") or "").strip()
    if not cat:
        raise ValueError("بند الميزانية مطلوب (مثل: رواتب، إيجار، تسويق)")
    month = _month(a["month"], ctx.tz) if a.get("month") else ""
    amt = parse_amount(a.get("amount"))
    cur = norm_currency(a.get("currency"), w["currency"])
    db.execute("INSERT INTO budgets(user_id,workspace_id,category,month,amount_minor,currency) VALUES(?,?,?,?,?,?) "
               "ON CONFLICT(workspace_id,category,month) DO UPDATE SET amount_minor=excluded.amount_minor, "
               "currency=excluded.currency", (ctx.uid, w["id"], cat, month, amt, cur))
    audit(db, ctx.uid, w["id"], "budget", None, "set", f"{cat} {month or 'كل شهر'} {money(amt, cur)}")
    return ToolResult(True, f"ميزانية «{cat}» في «{w['name']}»: {money(amt, cur)} {'لشهر ' + month if month else 'شهريًا'}",
                      {"category": cat, "month": month or "monthly", "amount": amt / 100, "currency": cur}, verified=True)


async def recurring_add(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    kind = a.get("kind") if a.get("kind") in ("income", "expense") else "expense"
    w = find_ws(db, ctx.uid, a.get("workspace"))
    day = int(a.get("day_of_month") or 1)
    if not 1 <= day <= 28:
        raise ValueError("يوم الاستحقاق بين 1 و28")
    amt = parse_amount(a.get("amount"))
    cur = norm_currency(a.get("currency"), w["currency"])
    rid = db.insert("INSERT INTO recurring(user_id,workspace_id,kind,amount_minor,currency,category,note,day_of_month,"
                    "created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    (ctx.uid, w["id"], kind, amt, cur, (a.get("category") or "عام")[:60], (a.get("note") or "")[:300],
                     day, now_iso()))
    audit(db, ctx.uid, w["id"], "recurring", rid, "create", f"{money(amt, cur)} يوم {day}")
    return ToolResult(True, f"أُضيف التزام متكرر: {money(amt, cur)} يوم {day} من كل شهر. سأنبّهك في موعده، "
                            "ولن يُسجَّل كمصروف إلا بعد تأكيدك.", {"id": rid}, verified=True)


async def recurring_list(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    rows = db.all("SELECT r.*, w.name ws FROM recurring r JOIN workspaces w ON w.id=r.workspace_id "
                  "WHERE r.user_id=? AND r.active=1 ORDER BY r.day_of_month", (ctx.uid,))
    return ToolResult(True, f"{len(rows)} التزام متكرر", [
        {"id": r["id"], "workspace": r["ws"], "kind": r["kind"], "amount_text": money(r["amount_minor"], r["currency"]),
         "category": r["category"], "note": r["note"], "day_of_month": r["day_of_month"]} for r in rows], verified=True)


async def recurring_cancel(ctx: Ctx, a: dict) -> ToolResult:
    n = ctx.app.db.execute("UPDATE recurring SET active=0 WHERE id=? AND user_id=?", (int(a["id"]), ctx.uid)).rowcount
    return ToolResult(bool(n), "أُوقف الالتزام المتكرر" if n else "غير موجود", verified=bool(n))


def finance_summary(db, uid: int, w: dict, month: str, tzname: str) -> dict:
    """ملخص شهر لمساحة واحدة — مجمّع حسب العملة (لا تحويل عملات)."""
    rows = db.all("SELECT kind, currency, category, SUM(amount_minor) s, COUNT(*) n FROM ledger WHERE user_id=? AND "
                  "workspace_id=? AND substr(occurred_on,1,7)=? GROUP BY kind, currency, category", (uid, w["id"], month))
    by_cur: dict[str, dict] = {}
    for r in rows:
        c = by_cur.setdefault(r["currency"], {"income": 0, "expense": 0, "categories": {}})
        c[r["kind"]] += r["s"]
        if r["kind"] == "expense":
            c["categories"][r["category"]] = c["categories"].get(r["category"], 0) + r["s"]
    budgets = db.all("SELECT category, month, amount_minor, currency FROM budgets WHERE workspace_id=? AND month IN (?, '') "
                     "ORDER BY month", (w["id"], month))
    bmap: dict[tuple, dict] = {}
    for b in budgets:  # الشهر المحدد يغلب الميزانية الشهرية العامة
        bmap[(b["category"], b["currency"])] = b
    budget_rows = []
    for (cat, cur), b in bmap.items():
        spent = by_cur.get(cur, {}).get("categories", {}).get(cat, 0)
        budget_rows.append({"category": cat, "currency": cur, "budget": b["amount_minor"] / 100, "spent": spent / 100,
                            "budget_text": money(b["amount_minor"], cur), "spent_text": money(spent, cur),
                            "percent": round(100 * spent / b["amount_minor"]) if b["amount_minor"] else 0,
                            "remaining_text": money(b["amount_minor"] - spent, cur) if spent <= b["amount_minor"]
                            else "-" + money(spent - b["amount_minor"], cur)})
    today = local_now(tzname).date().isoformat()
    invs = db.all("SELECT * FROM invoices WHERE user_id=? AND workspace_id=? AND doc_kind IN ('invoice','bill') AND "
                  "status IN ('sent','partial')", (uid, w["id"]))
    recv, pay, overdue = {}, {}, []
    for inv in invs:
        out = _outstanding(inv)
        tgt = recv if inv["doc_kind"] == "invoice" else pay
        tgt[inv["currency"]] = tgt.get(inv["currency"], 0) + out
        if _inv_state(inv, today) == "overdue":
            overdue.append(_inv_view(db, inv, today))
    totals = [{"currency": cur, "income": v["income"] / 100, "expense": v["expense"] / 100,
               "net": (v["income"] - v["expense"]) / 100, "income_text": money(v["income"], cur),
               "expense_text": money(v["expense"], cur),
               "net_text": ("-" if v["income"] < v["expense"] else "") + money(abs(v["income"] - v["expense"]), cur),
               "top_expenses": sorted(({"category": k, "amount_text": money(x, cur), "amount": x / 100}
                                       for k, x in v["categories"].items()), key=lambda z: -z["amount"])[:6]}
              for cur, v in by_cur.items()]
    return {"month": month, "totals": totals, "budgets": budget_rows,
            "receivables": [{"currency": c, "amount_text": money(v, c), "amount": v / 100} for c, v in recv.items() if v],
            "payables": [{"currency": c, "amount_text": money(v, c), "amount": v / 100} for c, v in pay.items() if v],
            "overdue_invoices": overdue, "has_data": bool(rows or invs or budgets)}


def finance_report_data(db, uid: int, w: dict, tzname: str, month: str | None = None, year: str | None = None) -> dict:
    now = local_now(tzname)
    if year:
        if not re.fullmatch(r"\d{4}", str(year)):
            raise ValueError("السنة بصيغة YYYY")
        months = [f"{year}-{m:02d}" for m in range(1, 13)]
        period = str(year)
    else:
        month = _month(month, tzname)
        months = _months_back(month, 6)
        period = month
    flow = db.all("SELECT substr(occurred_on,1,7) ym, kind, currency, SUM(amount_minor) s FROM ledger WHERE user_id=? "
                  "AND workspace_id=? AND substr(occurred_on,1,7) BETWEEN ? AND ? GROUP BY ym, kind, currency",
                  (uid, w["id"], months[0], months[-1]))
    curs = sorted({f["currency"] for f in flow}) or [w["currency"]]
    cash = []
    for ym in months:
        for cur in curs:
            inc = sum(f["s"] for f in flow if f["ym"] == ym and f["kind"] == "income" and f["currency"] == cur)
            exp = sum(f["s"] for f in flow if f["ym"] == ym and f["kind"] == "expense" and f["currency"] == cur)
            cash.append({"month": ym, "currency": cur, "income": inc / 100, "expense": exp / 100, "net": (inc - exp) / 100})
    summary = finance_summary(db, uid, w, months[-1] if not year else now.strftime("%Y-%m"), tzname)
    if year:  # إجمالي السنة حسب البند
        rows = db.all("SELECT kind, currency, category, SUM(amount_minor) s FROM ledger WHERE user_id=? AND workspace_id=? "
                      "AND substr(occurred_on,1,4)=? GROUP BY kind, currency, category ORDER BY s DESC", (uid, w["id"], year))
        summary["year_by_category"] = [{"kind": r["kind"], "currency": r["currency"], "category": r["category"],
                                        "amount_text": money(r["s"], r["currency"]), "amount": r["s"] / 100} for r in rows]
    # توقع بسيط وصريح الافتراض: متوسط صافي آخر 3 أشهر مكتملة + الالتزامات المتكررة
    forecast = []
    done_months = [m for m in _months_back(now.strftime("%Y-%m"), 4)[:-1]]
    for cur in curs:
        nets = [sum(f["s"] * (1 if f["kind"] == "income" else -1) for f in db.all(
            "SELECT kind, SUM(amount_minor) s FROM ledger WHERE user_id=? AND workspace_id=? AND currency=? AND "
            "substr(occurred_on,1,7)=? GROUP BY kind", (uid, w["id"], cur, m))) for m in done_months]
        months_with_data = [n for n, m in zip(nets, done_months) if db.one(
            "SELECT 1 FROM ledger WHERE workspace_id=? AND currency=? AND substr(occurred_on,1,7)=? LIMIT 1",
            (w["id"], cur, m))]
        rec = db.one("SELECT COALESCE(SUM(CASE kind WHEN 'income' THEN amount_minor ELSE -amount_minor END),0) s "
                     "FROM recurring WHERE workspace_id=? AND currency=? AND active=1", (w["id"], cur))["s"]
        if months_with_data:
            avg = sum(months_with_data) // len(months_with_data)
            forecast.append({"currency": cur, "expected_monthly_net_text": ("-" if avg < 0 else "") + money(abs(avg), cur),
                             "recurring_monthly_text": ("-" if rec < 0 else "") + money(abs(rec), cur),
                             "basis": f"متوسط صافي {len(months_with_data)} شهر مكتمل سابق ({', '.join(done_months)})",
                             "confidence": "منخفضة" if len(months_with_data) < 3 else "متوسطة"})
    return {"workspace": _ws_view(w), "period": period, "cash_flow": cash, "summary": summary, "forecast": forecast,
            "assumptions": [NO_BANK_NOTE, "لا يوجد تحويل بين العملات؛ كل عملة محسوبة وحدها.",
                            "التوقع تقدير إحصائي بسيط وليس حقيقة."]}


async def finance_report(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    w = find_ws(db, ctx.uid, a.get("workspace"))
    data = finance_report_data(db, ctx.uid, w, ctx.tz, a.get("month"), a.get("year"))
    return ToolResult(True, "بيانات التقرير المالي — استخدم الأرقام كما هي، واذكر الافتراضات.", data, verified=True)


async def finance_overview_all(ctx: Ctx, a: dict) -> ToolResult:
    """ملخص الشهر لكل المساحات، كل واحدة منفصلة (لا دمج بين الشخصي والشركات)."""
    db = ctx.app.db
    month = _month(a.get("month"), ctx.tz)
    out = []
    for wv in list_workspaces(db, ctx.uid):
        w = db.one("SELECT * FROM workspaces WHERE id=?", (wv["id"],))
        sm = finance_summary(db, ctx.uid, w, month, ctx.tz)
        out.append({"id": wv["id"], "workspace": wv["name"], "kind": wv["kind_ar"], "totals": sm["totals"],
                    "receivables": sm["receivables"], "overdue_count": len(sm["overdue_invoices"])})
    return ToolResult(True, f"ملخص {month} لكل جهة على حدة. {NO_BANK_NOTE}", out, verified=True)


# ═══════════════ الفواتير وعروض الأسعار وفواتير الموردين
def _next_number(db, uid: int, kind: str, year: int) -> str:
    pre = f"{DOC_PREFIX[kind]}-{year}-"
    r = db.one("SELECT number FROM invoices WHERE user_id=? AND number LIKE ? ORDER BY id DESC LIMIT 1", (uid, pre + "%"))
    n = int(r["number"].rsplit("-", 1)[1]) + 1 if r and r["number"].rsplit("-", 1)[1].isdigit() else 1
    while db.one("SELECT 1 FROM invoices WHERE user_id=? AND number=?", (uid, f"{pre}{n:04d}")):
        n += 1
    return f"{pre}{n:04d}"


def _items(raw, single_amount=None) -> tuple[list[dict], int]:
    items = []
    if isinstance(raw, str) and raw.strip():
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            raw = [{"description": raw, "qty": 1, "price": single_amount}]
    for it in raw or []:
        desc = str(it.get("description") or it.get("desc") or it.get("name") or "").strip()
        if not desc:
            continue
        qty = float(str(it.get("qty") or it.get("quantity") or 1).translate(_AR_DIGITS))
        if qty <= 0:
            raise ValueError(f"كمية غير صالحة في البند «{desc}»")
        price = parse_amount(it.get("price") if it.get("price") not in (None, "") else it.get("unit_price"))
        line = int(round(qty * price))
        items.append({"description": desc[:200], "qty": qty, "price": price / 100, "total": line / 100})
    if not items and single_amount not in (None, ""):
        amt = parse_amount(single_amount)
        items.append({"description": "", "qty": 1, "price": amt / 100, "total": amt / 100})
    if not items:
        raise ValueError("أضف بندًا واحدًا على الأقل (الوصف والكمية والسعر) أو مبلغًا")
    return items, sum(int(round(x["total"] * 100)) for x in items)


async def invoice_create(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    kind = a.get("kind") if a.get("kind") in DOC_KINDS else "invoice"
    w = find_ws(db, ctx.uid, a.get("workspace"))
    cur = norm_currency(a.get("currency"), w["currency"])
    items, sub = _items(a.get("items"), a.get("amount"))
    if items[0]["description"] == "":
        items[0]["description"] = a.get("title") or DOC_AR[kind]
    vat = float(a["vat_rate"]) if a.get("vat_rate") not in (None, "") else float(w["vat_rate"])
    if not 0 <= vat <= 100:
        raise ValueError("نسبة الضريبة بين 0 و100")
    vat_minor = int(round(sub * vat / 100))
    cid = _contact(db, ctx.uid, a.get("contact"), create_kind="supplier" if kind == "bill" else "client", ws=w)
    pid = _project(db, ctx.uid, a.get("project"), w)
    issued = parse_day(a.get("issued_on"), ctx.tz)
    due = parse_day(a["due_on"], ctx.tz) if a.get("due_on") else (
        None if kind == "quote" else (date.fromisoformat(issued) + timedelta(days=int(a.get("due_days") or 30))).isoformat())
    year = int(issued[:4])
    number = (a.get("number") or "").strip()[:40] or _next_number(db, ctx.uid, kind, year)
    if db.one("SELECT 1 FROM invoices WHERE user_id=? AND number=?", (ctx.uid, number)):
        raise ValueError(f"الرقم {number} مستخدم")
    now = now_iso()
    iid = db.insert(
        "INSERT INTO invoices(user_id,workspace_id,project_id,contact_id,doc_kind,number,title,items,subtotal_minor,vat_rate,"
        "vat_minor,total_minor,currency,issued_on,due_on,status,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (ctx.uid, w["id"], pid, cid, kind, number, (a.get("title") or "")[:200], json.dumps(items, ensure_ascii=False),
         sub, vat, vat_minor, sub + vat_minor, cur, issued, due, "draft", (a.get("notes") or "")[:1000], now, now))
    audit(db, ctx.uid, w["id"], "invoice", iid, "create", f"{DOC_AR[kind]} {number} {money(sub + vat_minor, cur)}")
    inv = db.one("SELECT * FROM invoices WHERE id=? AND user_id=?", (iid, ctx.uid))
    if not inv:
        return ToolResult(False, "لم يُحفظ المستند")
    v = _inv_view(db, inv, local_now(ctx.tz).date().isoformat())
    return ToolResult(True, f"أُنشئت مسودة {DOC_AR[kind]} {number} بإجمالي {v['total_text']}. "
                            "لم تُرسل لأحد؛ هي مسودة حتى تعتمدها.", v, verified=True)


def _recompute_paid(db, iid: int) -> None:
    inv = db.one("SELECT * FROM invoices WHERE id=?", (iid,))
    if not inv:
        return
    paid = db.one("SELECT COALESCE(SUM(amount_minor),0) s FROM ledger WHERE invoice_id=?", (iid,))["s"]
    st = inv["status"]
    if paid >= inv["total_minor"] and inv["total_minor"] > 0:
        st = "paid"
    elif paid > 0:
        st = "partial"
    elif st in ("paid", "partial"):
        st = "sent"
    db.execute("UPDATE invoices SET paid_minor=?, status=?, updated_at=? WHERE id=?", (paid, st, now_iso(), iid))


def _find_inv(db, uid: int, ref) -> dict:
    r = str(ref or "").strip()
    inv = (db.one("SELECT * FROM invoices WHERE user_id=? AND number=?", (uid, r)) or
           (db.one("SELECT * FROM invoices WHERE user_id=? AND id=?", (uid, int(r))) if r.isdigit() else None) or
           db.one("SELECT * FROM invoices WHERE user_id=? AND number LIKE ? ORDER BY id DESC", (uid, f"%{r}%")))
    if not inv:
        raise ValueError(f"لا يوجد مستند بالرقم {ref}")
    return inv


async def invoice_update(ctx: Ctx, a: dict) -> ToolResult:
    """تغيير الحالة أو تسجيل دفعة استلمها/دفعها المستخدم (تسجيل فقط)."""
    db = ctx.app.db
    inv = _find_inv(db, ctx.uid, a.get("invoice"))
    msgs = []
    if a.get("status"):
        st = a["status"]
        if st not in DOC_STATUSES or st in ("paid", "partial"):
            raise ValueError("الحالة: draft/sent/accepted/rejected/cancelled (للدفع استخدم payment)")
        if inv["doc_kind"] != "quote" and st in ("accepted", "rejected"):
            raise ValueError("قبول/رفض للعروض فقط")
        db.execute("UPDATE invoices SET status=?, updated_at=? WHERE id=?", (st, now_iso(), inv["id"]))
        audit(db, ctx.uid, inv["workspace_id"], "invoice", inv["id"], "status", f"{inv['number']} ← {STATUS_AR[st]}")
        msgs.append(f"الحالة: {STATUS_AR[st]}" + (" (سجّلت أنك أرسلتها؛ أنا لم أرسل شيئًا)" if st == "sent" else ""))
    if a.get("payment") not in (None, ""):
        if inv["doc_kind"] == "quote":
            raise ValueError("العرض لا يُدفع؛ حوّله لفاتورة أولًا (invoice_from_quote)")
        if inv["status"] == "cancelled":
            raise ValueError("المستند ملغى")
        amt = parse_amount(a["payment"])
        if amt > _outstanding(inv):
            raise ValueError(f"الدفعة أكبر من المتبقي ({money(_outstanding(inv), inv['currency'])})")
        kind = "income" if inv["doc_kind"] == "invoice" else "expense"
        day = parse_day(a.get("date"), ctx.tz)
        lid = db.insert("INSERT INTO ledger(user_id,workspace_id,project_id,contact_id,invoice_id,kind,amount_minor,currency,"
                        "category,note,occurred_on,source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        (ctx.uid, inv["workspace_id"], inv["project_id"], inv["contact_id"], inv["id"], kind, amt,
                         inv["currency"], "مبيعات" if kind == "income" else "مشتريات",
                         f"دفعة {inv['number']}" + (f" — {a['note']}" if a.get("note") else ""), day, ctx.channel, now_iso()))
        if inv["status"] == "draft":
            db.execute("UPDATE invoices SET status='sent' WHERE id=?", (inv["id"],))
        _recompute_paid(db, inv["id"])
        audit(db, ctx.uid, inv["workspace_id"], "ledger", lid, "payment", f"{inv['number']} {money(amt, inv['currency'])}")
        msgs.append(f"سُجّلت دفعة {money(amt, inv['currency'])}")
    if not msgs:
        raise ValueError("حدد status أو payment")
    v = _inv_view(db, db.one("SELECT * FROM invoices WHERE id=?", (inv["id"],)), local_now(ctx.tz).date().isoformat())
    return ToolResult(True, f"{inv['number']}: " + "، ".join(msgs) + f". المتبقي {v['outstanding_text']}.", v, verified=True)


async def invoice_from_quote(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    q = _find_inv(db, ctx.uid, a.get("quote"))
    if q["doc_kind"] != "quote":
        raise ValueError("هذا ليس عرض سعر")
    w = db.one("SELECT * FROM workspaces WHERE id=?", (q["workspace_id"],))
    res = await invoice_create(ctx, {"kind": "invoice", "workspace": w["id"], "contact": q["contact_id"],
                                     "project": q["project_id"], "items": json.loads(q["items"]), "vat_rate": q["vat_rate"],
                                     "currency": q["currency"], "title": q["title"], "notes": f"من العرض {q['number']}",
                                     "due_days": a.get("due_days") or 30})
    db.execute("UPDATE invoices SET status='accepted', updated_at=? WHERE id=?", (now_iso(), q["id"]))
    audit(db, ctx.uid, q["workspace_id"], "invoice", q["id"], "convert", f"{q['number']} ← {res.data['number']}")
    return res


async def invoice_list(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    q, p = "SELECT * FROM invoices WHERE user_id=?", [ctx.uid]
    if a.get("workspace"):
        q += " AND workspace_id=?"
        p.append(find_ws(db, ctx.uid, a["workspace"])["id"])
    if a.get("kind") in DOC_KINDS:
        q += " AND doc_kind=?"
        p.append(a["kind"])
    if a.get("contact"):
        q += " AND contact_id=?"
        p.append(_contact(db, ctx.uid, a["contact"]))
    rows = db.all(q + " ORDER BY issued_on DESC, id DESC LIMIT 300", p)
    today = local_now(ctx.tz).date().isoformat()
    out = [_inv_view(db, r, today) for r in rows]
    f = a.get("filter") or "all"
    if f == "overdue":
        out = [x for x in out if x["status"] == "overdue"]
    elif f == "unpaid":
        out = [x for x in out if x["kind"] != "quote" and x["status"] in ("sent", "partial", "overdue", "draft")
               and x["outstanding"] > 0]
    elif f == "open_quotes":
        out = [x for x in out if x["kind"] == "quote" and x["status"] in ("draft", "sent")]
    return ToolResult(True, f"{len(out)} مستند", out, verified=True)


def invoice_html(db, uid: int, inv: dict, printable_js: bool = False, today: str | None = None) -> str:
    """مستند A4 عربي جاهز للطباعة/الحفظ PDF من المتصفح."""
    w = db.one("SELECT * FROM workspaces WHERE id=?", (inv["workspace_id"],))
    c = db.one("SELECT * FROM contacts WHERE id=?", (inv["contact_id"],)) if inv["contact_id"] else None
    e = html.escape
    items = json.loads(inv["items"] or "[]")
    cur = inv["currency"]
    rows = "".join(f"<tr><td>{k + 1}</td><td>{e(it['description'])}</td><td>{it['qty']:g}</td>"
                   f"<td>{e(money(int(round(it['price'] * 100)), cur))}</td><td>{e(money(int(round(it['total'] * 100)), cur))}</td></tr>"
                   for k, it in enumerate(items))
    kind = DOC_AR[inv["doc_kind"]]
    party = "المورد" if inv["doc_kind"] == "bill" else "العميل"
    script = '<script src="/static/print.js"></script>' if printable_js else ""
    return f"""<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{e(kind)} {e(inv['number'])}</title>
<style>
@page{{size:A4;margin:16mm}}
body{{font-family:Tajawal,"Noto Naskh Arabic","Segoe UI",Tahoma,Arial,sans-serif;color:#1E1E1E;margin:0;background:#f4f1ea}}
.page{{max-width:800px;margin:24px auto;background:#fff;padding:36px 40px;box-shadow:0 2px 18px rgba(0,0,0,.08)}}
.head{{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:3px solid #B8862B;padding-bottom:16px}}
.head h1{{margin:0;font-size:28px;color:#6B4A1E}} .head .no{{font-size:15px;color:#555;margin-top:6px}}
.org{{text-align:left;font-size:14px;color:#333}} .org b{{font-size:18px;color:#1E1E1E}}
.meta{{display:flex;gap:24px;margin:22px 0;font-size:14px}} .meta div{{flex:1;background:#faf6ec;padding:12px 14px;border-radius:8px}}
.meta span{{display:block;color:#8a7650;font-size:12px;margin-bottom:4px}}
table{{width:100%;border-collapse:collapse;font-size:14px}} th{{background:#1E1E1E;color:#F7E7B0;padding:10px;text-align:right}}
td{{padding:10px;border-bottom:1px solid #eee}} tr:nth-child(even) td{{background:#fcfaf5}}
.tot{{width:320px;margin-inline-start:auto;margin-top:18px;font-size:15px}} .tot div{{display:flex;justify-content:space-between;padding:6px 0}}
.tot .g{{border-top:2px solid #B8862B;font-weight:bold;font-size:18px;color:#6B4A1E;padding-top:10px}}
.notes{{margin-top:24px;font-size:13px;color:#444;white-space:pre-wrap}}
.foot{{margin-top:32px;font-size:11px;color:#888;border-top:1px dashed #ccc;padding-top:10px}}
.bar{{max-width:800px;margin:16px auto 0;text-align:left}} .bar button{{font:inherit;padding:8px 18px;border:0;border-radius:8px;background:#B8862B;color:#fff;cursor:pointer}}
@media print{{body{{background:#fff}} .page{{box-shadow:none;margin:0;max-width:none;padding:0}} .bar{{display:none}}}}
</style></head><body>
{'<div class="bar"><button id="print">طباعة / حفظ PDF</button></div>' if printable_js else ''}
<div class="page">
<div class="head"><div><h1>{e(kind)}</h1><div class="no">رقم: {e(inv['number'])}{(' — ' + e(inv['title'])) if inv['title'] else ''}</div></div>
<div class="org"><b>{e(w['name'] if w else '')}</b></div></div>
<div class="meta"><div><span>{party}</span>{e(c['name'] if c else '—')}{('<br>' + e(c['company'])) if c and c['company'] else ''}
{('<br>' + e(c['phone'])) if c and c['phone'] else ''}{('<br>' + e(c['email'])) if c and c['email'] else ''}</div>
<div><span>تاريخ الإصدار</span>{e(inv['issued_on'])}</div>
<div><span>{'صالح حتى' if inv['doc_kind'] == 'quote' else 'تاريخ الاستحقاق'}</span>{e(inv['due_on'] or '—')}</div>
<div><span>الحالة</span>{e(STATUS_AR.get(_inv_state(inv, today) if today else inv['status'], inv['status']))}</div></div>
<table><thead><tr><th>#</th><th>الوصف</th><th>الكمية</th><th>سعر الوحدة</th><th>الإجمالي</th></tr></thead><tbody>{rows}</tbody></table>
<div class="tot"><div><span>المجموع قبل الضريبة</span><span>{e(money(inv['subtotal_minor'], cur))}</span></div>
<div><span>ضريبة القيمة المضافة ({inv['vat_rate']:g}٪)</span><span>{e(money(inv['vat_minor'], cur))}</span></div>
<div class="g"><span>الإجمالي</span><span>{e(money(inv['total_minor'], cur))}</span></div>
{f'<div><span>المدفوع</span><span>{e(money(inv["paid_minor"], cur))}</span></div><div><span>المتبقي</span><span>{e(money(_outstanding(inv), cur))}</span></div>' if inv['paid_minor'] else ''}</div>
{f'<div class="notes">{e(inv["notes"])}</div>' if inv['notes'] else ''}
<div class="foot">مستند أُعد بواسطة رفيق. ليس فاتورة ضريبية إلكترونية معتمدة لدى هيئة الزكاة والضريبة والجمارك (فاتورة)؛
للفوترة الإلكترونية الرسمية استخدم نظامًا معتمدًا.</div>
</div>{script}</body></html>"""


async def invoice_document(ctx: Ctx, a: dict) -> ToolResult:
    from .tools import file_path, save_user_file
    db = ctx.app.db
    inv = _find_inv(db, ctx.uid, a.get("invoice"))
    data = invoice_html(db, ctx.uid, inv, today=local_now(ctx.tz).date().isoformat()).encode("utf-8")
    fid = save_user_file(ctx.app, ctx.uid, f"{inv['number']}.html", data, inv["project_id"])
    f = db.one("SELECT * FROM files WHERE id=?", (fid,))
    ok = file_path(ctx.app, ctx.uid, f).exists()
    return ToolResult(ok, f"جُهّز المستند {f['name']} (افتحه واطبعه أو احفظه PDF). لم يُرسل لأحد.",
                      {"file_id": fid, "name": f["name"]}, verified=ok)


# ═══════════════ العملاء والموردون والعملاء المحتملون والموظفون
async def contact_add(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    name = (a.get("name") or "").strip()
    if not name:
        raise ValueError("الاسم مطلوب")
    kind = a.get("kind") if a.get("kind") in CONTACT_KINDS else "client"
    w = find_ws(db, ctx.uid, a.get("workspace")) if a.get("workspace") else None
    stage = a.get("stage") if a.get("stage") in LEAD_STAGES else ("new" if kind == "lead" else "")
    ce = parse_day(a["contract_end"], ctx.tz) if a.get("contract_end") else None
    now = now_iso()
    cid = db.insert("INSERT INTO contacts(user_id,workspace_id,kind,name,company,phone,email,role,stage,contract_end,notes,"
                    "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (ctx.uid, w["id"] if w else None, kind, name[:120], (a.get("company") or "")[:120],
                     (a.get("phone") or "")[:40], (a.get("email") or "")[:120], (a.get("role") or "")[:80], stage, ce,
                     (a.get("notes") or "")[:2000], now, now))
    audit(db, ctx.uid, w["id"] if w else None, "contact", cid, "create", f"{CONTACT_AR[kind]}: {name}")
    ok = bool(db.one("SELECT 1 FROM contacts WHERE id=?", (cid,)))
    return ToolResult(ok, f"أُضيف {CONTACT_AR[kind]} «{name}» برقم {cid}", {"id": cid}, verified=ok)


async def contact_update(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    cid = _contact(db, ctx.uid, a.get("contact"))
    sets, vals = [], []
    for k in ("name", "company", "phone", "email", "role", "notes"):
        if a.get(k) is not None:
            sets.append(f"{k}=?")
            vals.append(str(a[k])[:2000])
    if a.get("stage"):
        if a["stage"] not in LEAD_STAGES:
            raise ValueError("المرحلة: " + "/".join(LEAD_STAGES))
        sets.append("stage=?")
        vals.append(a["stage"])
    if a.get("kind") in CONTACT_KINDS:
        sets.append("kind=?")
        vals.append(a["kind"])
    if a.get("contract_end"):
        sets.append("contract_end=?")
        vals.append(parse_day(a["contract_end"], ctx.tz))
    if a.get("status") in ("active", "archived"):
        sets.append("status=?")
        vals.append(a["status"])
    if not sets:
        raise ValueError("لا توجد تعديلات")
    db.execute(f"UPDATE contacts SET {', '.join(sets)}, updated_at=? WHERE id=? AND user_id=?", (*vals, now_iso(), cid, ctx.uid))
    c = db.one("SELECT * FROM contacts WHERE id=?", (cid,))
    audit(db, ctx.uid, c["workspace_id"], "contact", cid, "update", c["name"])
    return ToolResult(True, f"عُدّلت بيانات «{c['name']}»", {"id": cid}, verified=True)


def contact_rows(db, uid: int, kind: str | None = None, query: str = "", ws_id: int | None = None) -> list[dict]:
    q, p = "SELECT * FROM contacts WHERE user_id=? AND status='active'", [uid]
    if kind in CONTACT_KINDS:
        q += " AND kind=?"
        p.append(kind)
    if ws_id:
        q += " AND workspace_id=?"
        p.append(ws_id)
    if query:
        q += " AND (name LIKE ? OR company LIKE ? OR phone LIKE ? OR email LIKE ? OR notes LIKE ?)"
        p += [f"%{query}%"] * 5
    rows = db.all(q + " ORDER BY updated_at DESC LIMIT 300", p)
    for c in rows:
        c["kind_ar"] = CONTACT_AR.get(c["kind"], c["kind"])
        bal = db.all("SELECT currency, SUM(total_minor-paid_minor) s FROM invoices WHERE contact_id=? AND "
                     "doc_kind='invoice' AND status IN ('sent','partial') GROUP BY currency", (c["id"],))
        c["owes_you"] = "، ".join(money(b["s"], b["currency"]) for b in bal if b["s"])
    return rows


async def contact_list(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    ws_id = find_ws(db, ctx.uid, a["workspace"])["id"] if a.get("workspace") else None
    rows = contact_rows(db, ctx.uid, a.get("kind"), a.get("query") or "", ws_id)
    keep = ("id", "kind", "kind_ar", "name", "company", "phone", "email", "role", "stage", "contract_end", "owes_you")
    return ToolResult(True, f"{len(rows)} جهة", [{k: r[k] for k in keep} for r in rows], verified=True)


async def pipeline(ctx: Ctx, a: dict) -> ToolResult:
    """قمع المبيعات: العملاء المحتملون حسب المرحلة + العروض المفتوحة."""
    db = ctx.app.db
    leads = db.all("SELECT stage, COUNT(*) n FROM contacts WHERE user_id=? AND kind='lead' AND status='active' GROUP BY stage",
                   (ctx.uid,))
    quotes = (await invoice_list(ctx, {"filter": "open_quotes"})).data
    by_cur: dict[str, int] = {}
    for q in quotes:
        by_cur[q["currency"]] = by_cur.get(q["currency"], 0) + int(round(q["total"] * 100))
    return ToolResult(True, "قمع المبيعات", {"leads_by_stage": {x["stage"] or "new": x["n"] for x in leads},
                                             "open_quotes": quotes[:30],
                                             "open_quotes_value": [money(v, c) for c, v in by_cur.items()]}, verified=True)


# ═══════════════ مؤشرات الأداء
async def kpi_set(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    w = find_ws(db, ctx.uid, a.get("workspace"))
    name = (a.get("name") or "").strip()
    if not name:
        raise ValueError("اسم المؤشر مطلوب")

    def num(v):
        return None if v in (None, "") else float(str(v).translate(_AR_DIGITS).replace(",", ""))
    old = db.one("SELECT * FROM kpis WHERE workspace_id=? AND name=?", (w["id"], name))
    target = num(a.get("target")) if a.get("target") not in (None, "") else (old["target"] if old else None)
    current = num(a.get("current")) if a.get("current") not in (None, "") else (old["current"] if old else None)
    direction = a.get("direction") if a.get("direction") in ("up", "down") else (old["direction"] if old else "up")
    pid = _project(db, ctx.uid, a.get("project"), w)
    db.execute("INSERT INTO kpis(user_id,workspace_id,project_id,name,target,current,unit,direction,updated_at) "
               "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(workspace_id,name) DO UPDATE SET target=excluded.target, "
               "current=excluded.current, unit=excluded.unit, direction=excluded.direction, project_id=excluded.project_id, "
               "updated_at=excluded.updated_at",
               (ctx.uid, w["id"], pid, name, target, current, a.get("unit") or (old["unit"] if old else ""), direction,
                now_iso()))
    audit(db, ctx.uid, w["id"], "kpi", None, "set",
          f"{name}: {'—' if current is None else f'{current:g}'} / {'—' if target is None else f'{target:g}'} {a.get('unit') or ''}".strip())
    return ToolResult(True, f"المؤشر «{name}»: {current if current is not None else '—'} من {target if target is not None else '—'}",
                      {"name": name, "target": target, "current": current}, verified=True)


# ═══════════════ الاجتماعات
async def meeting_add(ctx: Ctx, a: dict) -> ToolResult:
    from .tools import create_reminder
    db = ctx.app.db
    title = (a.get("title") or "").strip()
    if not title:
        raise ValueError("عنوان الاجتماع مطلوب")
    w = find_ws(db, ctx.uid, a["workspace"]) if a.get("workspace") else None
    pid = _project(db, ctx.uid, a.get("project"), w)
    starts = parse_local(a["when"], ctx.tz) if a.get("when") else None
    now = now_iso()
    from .db import iso
    mid = db.insert("INSERT INTO meetings(user_id,workspace_id,project_id,title,starts_at,duration_min,attendees,agenda,"
                    "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (ctx.uid, w["id"] if w else None, pid, title[:200], iso(starts) if starts else None,
                     int(a.get("duration_min") or 60), (a.get("attendees") or "")[:500], (a.get("agenda") or "")[:4000],
                     now, now))
    note = ""
    if starts:
        before = int(a.get("remind_before_min") if a.get("remind_before_min") not in (None, "") else 30)
        at = starts - timedelta(minutes=before)
        if at > datetime.now(at.tzinfo):
            r = await create_reminder(ctx, {"text": f"اجتماع «{title}» بعد {before} دقيقة",
                                            "when": to_local_str(iso(at), ctx.tz, False).replace(" ", "T")})
            if r.ok:
                db.execute("UPDATE meetings SET reminder_id=? WHERE id=?", (r.data["id"], mid))
                note = f" وسأذكّرك قبله بـ{before} دقيقة."
    audit(db, ctx.uid, w["id"] if w else None, "meeting", mid, "create", title)
    return ToolResult(True, f"سُجّل الاجتماع «{title}» برقم {mid}" + (f" في {to_local_str(iso(starts), ctx.tz)}" if starts else "")
                      + note + " (في رفيق فقط؛ لم أرسل دعوات لأحد).", {"id": mid}, verified=True)


async def meeting_list(ctx: Ctx, a: dict) -> ToolResult:
    db = ctx.app.db
    q = "SELECT m.*, w.name ws FROM meetings m LEFT JOIN workspaces w ON w.id=m.workspace_id WHERE m.user_id=?"
    if (a.get("filter") or "upcoming") == "upcoming":
        q += " AND m.status='planned' AND (m.starts_at IS NULL OR m.starts_at>=?) ORDER BY m.starts_at"
        rows = db.all(q + " LIMIT 50", (ctx.uid, now_iso()))
    else:
        rows = db.all(q + " ORDER BY COALESCE(m.starts_at, m.created_at) DESC LIMIT 50", (ctx.uid,))
    return ToolResult(True, f"{len(rows)} اجتماع", [
        {"id": m["id"], "title": m["title"], "when": to_local_str(m["starts_at"], ctx.tz) if m["starts_at"] else "",
         "workspace": m["ws"] or "", "attendees": m["attendees"], "agenda": m["agenda"][:500], "status": m["status"],
         "has_minutes": bool(m["minutes"])} for m in rows], verified=True)


async def meeting_record(ctx: Ctx, a: dict) -> ToolResult:
    """حفظ المحضر + تحويل كل إجراء إلى مهمة فعلية + تسجيل القرارات في المشروع."""
    db = ctx.app.db
    r = str(a.get("meeting") or "")
    m = (db.one("SELECT * FROM meetings WHERE id=? AND user_id=?", (int(r), ctx.uid)) if r.isdigit() else
         db.one("SELECT * FROM meetings WHERE user_id=? AND title LIKE ? ORDER BY id DESC", (ctx.uid, f"%{r}%")))
    if not m:
        raise ValueError("الاجتماع غير موجود")
    from .db import iso
    created, decisions = [], []
    now = now_iso()
    for act in a.get("actions") or []:
        title = str(act.get("title") or "").strip()
        if not title:
            continue
        due = iso(parse_local(act["due"], ctx.tz)) if act.get("due") else None
        owner = str(act.get("owner") or "").strip()
        tid = db.insert("INSERT INTO tasks(user_id,project_id,title,notes,priority,due_at,source,created_at,updated_at) "
                        "VALUES(?,?,?,?,?,?,?,?,?)",
                        (ctx.uid, m["project_id"], title[:300], f"من اجتماع «{m['title']}»" + (f" — المسؤول: {owner}" if owner else ""),
                         act.get("priority") if act.get("priority") in ("high", "normal", "low") else "normal", due,
                         "meeting", now, now))
        created.append({"task_id": tid, "title": title, "owner": owner, "due": to_local_str(due, ctx.tz) if due else ""})
    for d in a.get("decisions") or []:
        d = str(d).strip()
        if not d:
            continue
        if m["project_id"]:
            db.insert("INSERT INTO project_entries(project_id,user_id,kind,content,created_at) VALUES(?,?,?,?,?)",
                      (m["project_id"], ctx.uid, "decision", d[:1000], now))
        decisions.append(d)
    minutes = (a.get("minutes") or "").strip()
    full = minutes + ("\n\nالقرارات:\n- " + "\n- ".join(decisions) if decisions else "")
    db.execute("UPDATE meetings SET minutes=?, status='done', updated_at=? WHERE id=?", (full[:20000], now, m["id"]))
    audit(db, ctx.uid, m["workspace_id"], "meeting", m["id"], "minutes", f"{len(created)} مهمة، {len(decisions)} قرار")
    ok = len(created) == len([x for x in (a.get("actions") or []) if str(x.get("title") or "").strip()])
    return ToolResult(ok, f"حُفظ محضر «{m['title']}»: {len(created)} مهمة جديدة و{len(decisions)} قرار.",
                      {"tasks": created, "decisions": decisions}, verified=ok)


# ═══════════════ التصدير
def export_rows(db, uid: int, kind: str, w: dict | None, tzname: str, month: str | None, year: str | None):
    today = local_now(tzname).date().isoformat()
    if kind == "ledger":
        q, p = "SELECT l.*, c.name cn, pr.name pn FROM ledger l LEFT JOIN contacts c ON c.id=l.contact_id " \
               "LEFT JOIN projects pr ON pr.id=l.project_id WHERE l.user_id=? AND l.workspace_id=?", [uid, w["id"]]
        if year:
            q += " AND substr(l.occurred_on,1,4)=?"
            p.append(str(year))
        elif month:
            q += " AND substr(l.occurred_on,1,7)=?"
            p.append(month)
        rows = db.all(q + " ORDER BY l.occurred_on, l.id", p)
        return [("القيود", [["التاريخ", "النوع", "البند", "المبلغ", "العملة", "الجهة", "المشروع", "ملاحظة"]] +
                 [[r["occurred_on"], "دخل" if r["kind"] == "income" else "مصروف", r["category"], r["amount_minor"] / 100,
                   r["currency"], r["cn"] or "", r["pn"] or "", r["note"]] for r in rows])]
    if kind == "invoices":
        rows = db.all("SELECT * FROM invoices WHERE user_id=?" + (" AND workspace_id=?" if w else "") +
                      " ORDER BY issued_on", (uid, w["id"]) if w else (uid,))
        vs = [_inv_view(db, r, today) for r in rows]
        return [("المستندات", [["الرقم", "النوع", "العميل/المورد", "الإصدار", "الاستحقاق", "قبل الضريبة", "الضريبة",
                                 "الإجمالي", "المدفوع", "المتبقي", "العملة", "الحالة"]] +
                 [[v["number"], v["kind_ar"], v["contact"], v["issued_on"], v["due_on"], v["subtotal"], v["vat"], v["total"],
                   v["paid"], v["outstanding"], v["currency"], v["status_ar"]] for v in vs])]
    if kind == "contacts":
        rows = contact_rows(db, uid, None, "", w["id"] if w else None)
        return [("جهات الاتصال", [["النوع", "الاسم", "الشركة", "الجوال", "البريد", "المرحلة", "انتهاء العقد", "مستحق لك"]] +
                 [[r["kind_ar"], r["name"], r["company"], r["phone"], r["email"], r["stage"], r["contract_end"] or "",
                   r["owes_you"]] for r in rows if r["kind"] != "employee"])]
    if kind == "report":
        d = finance_report_data(db, uid, w, tzname, month, year)
        s1 = [["الشهر", "العملة", "الدخل", "المصروف", "الصافي"]] + [[c["month"], c["currency"], c["income"], c["expense"], c["net"]]
                                                                for c in d["cash_flow"]]
        s2 = [["البند", "العملة", "الميزانية", "المصروف", "النسبة٪"]] + [[b["category"], b["currency"], b["budget"], b["spent"],
                                                                     b["percent"]] for b in d["summary"]["budgets"]]
        s3 = [["الرقم", "العميل", "الاستحقاق", "المتبقي", "العملة"]] + [[o["number"], o["contact"], o["due_on"], o["outstanding"],
                                                                     o["currency"]] for o in d["summary"]["overdue_invoices"]]
        s4 = [["ملاحظات وافتراضات"]] + [[x] for x in d["assumptions"]] + [[f"توقع ({f['currency']}): {f['expected_monthly_net_text']} — {f['basis']}"]
                                                                       for f in d["forecast"]]
        sheets = [("التدفق النقدي", s1), ("الميزانية مقابل الفعلي", s2), ("فواتير متأخرة", s3), ("الافتراضات", s4)]
        if d["summary"].get("year_by_category"):
            sheets.insert(1, ("حسب البند", [["النوع", "البند", "المبلغ", "العملة"]] +
                              [["دخل" if r["kind"] == "income" else "مصروف", r["category"], r["amount"], r["currency"]]
                               for r in d["summary"]["year_by_category"]]))
        return sheets
    if kind == "tasks":
        rows = db.all("SELECT t.*, p.name pn FROM tasks t LEFT JOIN projects p ON p.id=t.project_id WHERE t.user_id=? "
                      "ORDER BY t.status, t.due_at", (uid,))
        return [("المهام", [["العنوان", "الحالة", "الأولوية", "الموعد", "المشروع"]] +
                 [[r["title"], r["status"], r["priority"], to_local_str(r["due_at"], tzname, False) if r["due_at"] else "", r["pn"] or ""]
                  for r in rows])]
    raise ValueError("نوع التصدير: ledger/invoices/contacts/report/tasks")


def report_html(title: str, sheets) -> str:
    e = html.escape
    def table(rows):
        if len(rows) <= 1:
            return '<p style="color:#888;font-size:13px">لا يوجد.</p>'
        return ("<table><thead><tr>" + "".join(f"<th>{e(str(h))}</th>" for h in rows[0]) + "</tr></thead><tbody>" +
                "".join("<tr>" + "".join(f"<td>{e(f'{c:,.2f}' if isinstance(c, float) else str(c))}</td>" for c in r) + "</tr>"
                        for r in rows[1:]) + "</tbody></table>")
    body = "".join(f"<h2>{e(n)}</h2>{table(rows)}" for n, rows in sheets)
    return (f'<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><title>{e(title)}</title><style>'
            '@page{size:A4;margin:14mm}body{font-family:Tajawal,"Noto Naskh Arabic",Tahoma,Arial,sans-serif;color:#1E1E1E;margin:24px}'
            'h1{color:#6B4A1E;border-bottom:3px solid #B8862B;padding-bottom:8px}h2{color:#6B4A1E;margin-top:28px;font-size:18px}'
            'table{width:100%;border-collapse:collapse;font-size:13px}th{background:#1E1E1E;color:#F7E7B0;padding:8px;text-align:right}'
            'td{padding:7px 8px;border-bottom:1px solid #eee}tr:nth-child(even) td{background:#fcfaf5}</style></head>'
            f'<body><h1>{e(title)}</h1>{body}<p style="font-size:11px;color:#888;margin-top:30px">{e(NO_BANK_NOTE)}</p></body></html>')


async def export_report(ctx: Ctx, a: dict) -> ToolResult:
    from .tools import file_path, save_user_file
    db = ctx.app.db
    kind = a.get("kind") or "report"
    fmt = a.get("format") if a.get("format") in ("xlsx", "csv", "html") else "xlsx"
    w = find_ws(db, ctx.uid, a.get("workspace")) if (a.get("workspace") or kind in ("ledger", "report")) else None
    month = _month(a["month"], ctx.tz) if a.get("month") else (None if a.get("year") else local_now(ctx.tz).strftime("%Y-%m"))
    sheets = export_rows(db, ctx.uid, kind, w, ctx.tz, month, a.get("year"))
    label = {"ledger": "القيود", "invoices": "الفواتير", "contacts": "جهات الاتصال", "report": "تقرير مالي", "tasks": "المهام"}[kind]
    period = a.get("year") or month or ""
    title = f"{label} — {w['name'] if w else 'الكل'}" + (f" — \u2066{period}\u2069" if kind in ("ledger", "report") else "")
    base = re.sub(r"\s+", "-", f"{label}-{w['name'] if w else 'all'}-{period}".strip("-"))
    if fmt == "xlsx":
        data, name = build_xlsx(sheets), base + ".xlsx"
    elif fmt == "csv":
        import csv
        import io
        buf = io.StringIO()
        buf.write("﻿")
        csv.writer(buf).writerows(sheets[0][1])
        data, name = buf.getvalue().encode("utf-8"), base + ".csv"
    else:
        data, name = report_html(title, sheets).encode("utf-8"), base + ".html"
    fid = save_user_file(ctx.app, ctx.uid, name, data)
    f = db.one("SELECT * FROM files WHERE id=?", (fid,))
    ok = file_path(ctx.app, ctx.uid, f).exists() and f["size"] == len(data)
    rows_n = sum(max(0, len(r) - 1) for _, r in sheets)
    return ToolResult(ok, f"جُهّز الملف {f['name']} ({rows_n} صف)" + (" — افتحه واختر طباعة ← حفظ PDF" if fmt == "html" else ""),
                      {"file_id": fid, "name": f["name"], "rows": rows_n}, verified=ok)


# ═══════════════ التنبيهات اليومية (للملخص الصباحي ولوحة التحكم)
def business_alerts(db, uid: int, tzname: str) -> dict:
    today_d = local_now(tzname).date()
    today = today_d.isoformat()
    soon = (today_d + timedelta(days=7)).isoformat()
    invs = db.all("SELECT * FROM invoices WHERE user_id=? AND doc_kind IN ('invoice','bill') AND status IN ('sent','partial')",
                  (uid,))
    overdue = [_inv_view(db, x, today) for x in invs if _inv_state(x, today) == "overdue"]
    due_soon = [_inv_view(db, x, today) for x in invs if x["due_on"] and today <= x["due_on"] <= soon]
    recs = []
    for r in db.all("SELECT r.*, w.name ws FROM recurring r JOIN workspaces w ON w.id=r.workspace_id WHERE r.user_id=? AND r.active=1",
                    (uid,)):
        for k in range(8):
            d = today_d + timedelta(days=k)
            if d.day == r["day_of_month"]:
                recs.append({"id": r["id"], "workspace": r["ws"], "kind": r["kind"], "category": r["category"],
                             "note": r["note"], "amount_text": money(r["amount_minor"], r["currency"]), "date": d.isoformat()})
                break
    contracts = db.all("SELECT id, name, kind, contract_end FROM contacts WHERE user_id=? AND status='active' AND "
                       "contract_end IS NOT NULL AND contract_end BETWEEN ? AND ? ORDER BY contract_end",
                       (uid, today, (today_d + timedelta(days=30)).isoformat()))
    for c in contracts:
        c["kind_ar"] = CONTACT_AR.get(c["kind"], c["kind"])
    meetings = db.all("SELECT id, title, starts_at FROM meetings WHERE user_id=? AND status='planned' AND starts_at>=? "
                      "ORDER BY starts_at LIMIT 6", (uid, now_iso()))
    for m in meetings:
        m["when"] = to_local_str(m["starts_at"], tzname)
    return {"overdue_invoices": overdue[:10], "due_within_7_days": due_soon[:10], "recurring_next_7_days": recs[:10],
            "contracts_ending_30_days": contracts[:10], "upcoming_meetings": meetings}


# ═══════════════ البحث الشامل
def global_search(db, uid: int, q: str, tzname: str, limit: int = 8) -> list[dict]:
    q = (q or "").strip()
    if len(q) < 2:
        return []
    like = f"%{q}%"
    out: list[dict] = []

    def add(kind, label, rows, title, sub=lambda r: "", page=""):
        for r in rows:
            out.append({"type": kind, "type_ar": label, "id": r["id"], "title": title(r), "sub": sub(r), "page": page})
    add("task", "مهمة", db.all("SELECT id, title, status, due_at FROM tasks WHERE user_id=? AND (title LIKE ? OR notes LIKE ?) "
                               "ORDER BY id DESC LIMIT ?", (uid, like, like, limit)),
        lambda r: r["title"], lambda r: to_local_str(r["due_at"], tzname) if r["due_at"] else r["status"], "tasks")
    add("project", "مشروع", db.all("SELECT id, name, goal FROM projects WHERE user_id=? AND (name LIKE ? OR goal LIKE ? OR "
                                   "description LIKE ?) LIMIT ?", (uid, like, like, like, limit)),
        lambda r: r["name"], lambda r: r["goal"][:80], "projects")
    add("workspace", "جهة", db.all("SELECT id, name, kind FROM workspaces WHERE user_id=? AND name LIKE ? LIMIT ?",
                                   (uid, like, limit)), lambda r: r["name"], lambda r: WS_AR.get(r["kind"], ""), "business")
    add("contact", "جهة اتصال", db.all("SELECT id, name, company, kind, phone FROM contacts WHERE user_id=? AND status='active' AND "
                                       "(name LIKE ? OR company LIKE ? OR phone LIKE ? OR email LIKE ? OR notes LIKE ?) LIMIT ?",
                                       (uid, like, like, like, like, like, limit)),
        lambda r: r["name"], lambda r: " · ".join(x for x in (CONTACT_AR.get(r["kind"], ""), r["company"], r["phone"]) if x), "contacts")
    add("invoice", "مستند مالي", db.all("SELECT id, number, title, doc_kind, total_minor, currency FROM invoices WHERE user_id=? AND "
                                        "(number LIKE ? OR title LIKE ? OR items LIKE ? OR notes LIKE ?) LIMIT ?",
                                        (uid, like, like, like, like, limit)),
        lambda r: f"{DOC_AR[r['doc_kind']]} {r['number']}", lambda r: f"{r['title']} · {money(r['total_minor'], r['currency'])}", "invoices")
    add("ledger", "قيد مالي", db.all("SELECT id, kind, category, note, amount_minor, currency, occurred_on FROM ledger WHERE user_id=? "
                                     "AND (note LIKE ? OR category LIKE ?) ORDER BY occurred_on DESC LIMIT ?", (uid, like, like, limit)),
        lambda r: f"{'دخل' if r['kind'] == 'income' else 'مصروف'} {money(r['amount_minor'], r['currency'])}",
        lambda r: f"{r['category']} · {r['occurred_on']} · {r['note'][:50]}", "finance")
    add("meeting", "اجتماع", db.all("SELECT id, title, starts_at FROM meetings WHERE user_id=? AND (title LIKE ? OR agenda LIKE ? OR "
                                    "minutes LIKE ? OR attendees LIKE ?) LIMIT ?", (uid, like, like, like, like, limit)),
        lambda r: r["title"], lambda r: to_local_str(r["starts_at"], tzname) if r["starts_at"] else "", "meetings")
    add("memory", "ذاكرة", db.all("SELECT id, content FROM memories WHERE user_id=? AND content LIKE ? LIMIT ?", (uid, like, limit)),
        lambda r: r["content"][:90], page="memory")
    add("file", "ملف", db.all("SELECT id, name FROM files WHERE user_id=? AND name LIKE ? LIMIT ?", (uid, like, limit)),
        lambda r: r["name"], page="files")
    return out


# ═══════════════ التسجيل
def register_business_tools(r: Registry) -> None:
    T = lambda *a, **k: r.add(Tool(*a, **k))  # noqa: E731
    ws = s("اسم الشركة/المساحة أو رقمها. فارغ = المساحة الشخصية. لا تخلط أموال الجهات أبدًا.")
    amount = s("المبلغ بالأرقام (مثل 2500 أو 1,250.50)")
    cur = s("رمز العملة ISO مثل SAR, USD, AED. فارغ = عملة المساحة")
    day = s("التاريخ المحلي YYYY-MM-DD (فارغ = اليوم)")
    items = {"type": "array", "description": "البنود", "items": {"type": "object", "properties": {
        "description": s("الوصف"), "qty": {"type": "number", "description": "الكمية"},
        "price": {"type": "number", "description": "سعر الوحدة قبل الضريبة"}}, "required": ["description", "price"]}}

    T("workspace_create", "إنشاء مساحة مستقلة لشركة أو نشاط (لكل منها أموالها ومشاريعها وعملاؤها).", obj({
        "name": s("الاسم"), "kind": s("النوع", ["company", "activity"]), "currency": cur,
        "vat_rate": {"type": "number", "description": "نسبة ضريبة القيمة المضافة٪ (الافتراضي 15 للريال)"},
        "goals": s("الأهداف"), "notes": s("")}, ["name"]), workspace_create, category="الأعمال")
    T("workspace_update", "تعديل بيانات شركة/مساحة أو أرشفتها.", obj({"workspace": ws, "name": s(""), "goals": s(""),
      "notes": s(""), "currency": cur, "vat_rate": {"type": "number"}, "status": s("", ["active", "archived"])},
      ["workspace"]), workspace_update, category="الأعمال")
    T("workspace_list", "عرض الشركات والمساحات.", obj(), workspace_list, category="الأعمال")
    T("workspace_overview", "لوحة جهة واحدة: المالية للشهر، المستحقات، المتأخرات، المشاريع والميزانيات، KPI، الاجتماعات، المخاطر، سجل التعديلات.",
      obj({"workspace": ws}), workspace_overview, category="الأعمال")
    T("finance_record", "تسجيل دخل أو مصروف حدث فعلًا كما ذكره المستخدم (تسجيل فقط؛ لا ينفذ أي دفع). اسأل إن لم يتضح المبلغ أو الجهة.",
      obj({"kind": s("", ["income", "expense"]), "amount": amount, "currency": cur, "workspace": ws,
           "category": s("البند: رواتب، إيجار، مواد، مبيعات، تسويق، وقود…"), "note": s("وصف"), "date": day,
           "project": s("مشروع مرتبط"), "contact": s("العميل أو المورد")}, ["kind", "amount"]),
      finance_record, category="المالية")
    T("finance_list", "عرض القيود المالية لجهة.", obj({"workspace": ws, "month": s("YYYY-MM"), "kind": s("", ["income", "expense"]),
      "category": s(""), "query": s(""), "limit": i("")}), finance_list, category="المالية")
    T("finance_report", "تقرير مالي: التدفق النقدي، الميزانية مقابل الفعلي، المستحقات والمتأخرات، توقع بافتراضات صريحة.",
      obj({"workspace": ws, "month": s("YYYY-MM"), "year": s("YYYY للتقرير السنوي")}), finance_report, category="المالية")
    T("finance_overview_all", "ملخص الشهر لكل الجهات (الشخصي وكل شركة) كلٌّ على حدة.", obj({"month": s("YYYY-MM")}),
      finance_overview_all, category="المالية")
    T("finance_delete", "حذف قيد مالي (يتطلب موافقة).", obj({"id": i("رقم القيد")}, ["id"]), finance_delete,
      level=NEEDS_APPROVAL, summarize=_fin_del_summary, category="المالية")
    T("budget_set", "تحديد ميزانية لبند (شهرية دائمة أو لشهر محدد).", obj({"workspace": ws, "category": s("البند"),
      "amount": amount, "currency": cur, "month": s("YYYY-MM أو فارغ = كل شهر")}, ["category", "amount"]),
      budget_set, category="المالية")
    T("recurring_add", "التزام شهري متكرر (إيجار، رواتب، اشتراك، قسط). ينبّه في موعده ولا يسجّل مصروفًا دون تأكيد.",
      obj({"workspace": ws, "kind": s("", ["expense", "income"]), "amount": amount, "currency": cur, "category": s(""),
           "note": s(""), "day_of_month": i("يوم الاستحقاق 1-28")}, ["amount", "day_of_month"]), recurring_add, category="المالية")
    T("recurring_list", "عرض الالتزامات المتكررة.", obj(), recurring_list, category="المالية")
    T("recurring_cancel", "إيقاف التزام متكرر.", obj({"id": i("")}, ["id"]), recurring_cancel, category="المالية")
    T("invoice_create", "إنشاء مسودة فاتورة لعميل (invoice) أو عرض سعر (quote) أو تسجيل فاتورة مورد (bill). الضريبة تُحسب تلقائيًا. لا تُرسل لأحد.",
      obj({"kind": s("", DOC_KINDS), "workspace": ws, "contact": s("اسم العميل/المورد (يُنشأ إن لم يوجد)"), "items": items,
           "amount": amount, "title": s("عنوان/موضوع"), "vat_rate": {"type": "number"}, "currency": cur,
           "issued_on": day, "due_on": s("الاستحقاق/صلاحية العرض YYYY-MM-DD"), "due_days": i("مهلة السداد بالأيام (افتراضي 30)"),
           "project": s(""), "notes": s("شروط/ملاحظات"), "number": s("رقم مخصص (اختياري)")}, ["kind"]),
      invoice_create, category="الفواتير")
    T("invoice_update", "تحديث حالة مستند (أرسله المستخدم بنفسه، قُبل العرض، أُلغي) أو تسجيل دفعة استلمها/دفعها المستخدم.",
      obj({"invoice": s("رقم المستند مثل INV-2026-0003"), "status": s("", ["draft", "sent", "accepted", "rejected", "cancelled"]),
           "payment": amount, "date": day, "note": s("")}, ["invoice"]), invoice_update, category="الفواتير")
    T("invoice_from_quote", "تحويل عرض سعر مقبول إلى مسودة فاتورة.", obj({"quote": s("رقم العرض"), "due_days": i("")}, ["quote"]),
      invoice_from_quote, category="الفواتير")
    T("invoice_list", "عرض الفواتير والعروض.", obj({"filter": s("", ["all", "unpaid", "overdue", "open_quotes"]), "kind": s("", DOC_KINDS),
      "workspace": ws, "contact": s("")}), invoice_list, category="الفواتير")
    T("invoice_document", "تجهيز مستند الفاتورة/العرض للطباعة أو الحفظ PDF (ملف HTML في ملفات المستخدم).",
      obj({"invoice": s("رقم المستند")}, ["invoice"]), invoice_document, category="الفواتير")
    T("contact_add", "إضافة عميل أو مورد أو عميل محتمل أو شريك أو موظف.", obj({"kind": s("", CONTACT_KINDS), "name": s(""),
      "company": s(""), "phone": s(""), "email": s(""), "role": s("المسمى/التخصص"), "stage": s("مرحلة العميل المحتمل", LEAD_STAGES),
      "contract_end": s("تاريخ انتهاء العقد YYYY-MM-DD"), "workspace": ws, "notes": s("")}, ["name"]), contact_add, category="العملاء")
    T("contact_update", "تعديل جهة اتصال أو نقل عميل محتمل لمرحلة أخرى.", obj({"contact": s("الاسم أو الرقم"), "name": s(""),
      "company": s(""), "phone": s(""), "email": s(""), "role": s(""), "stage": s("", LEAD_STAGES), "kind": s("", CONTACT_KINDS),
      "contract_end": s(""), "notes": s(""), "status": s("", ["active", "archived"])}, ["contact"]), contact_update, category="العملاء")
    T("contact_list", "عرض العملاء/الموردين/الموظفين مع المبالغ المستحقة.", obj({"kind": s("", CONTACT_KINDS), "query": s(""),
      "workspace": ws}), contact_list, category="العملاء")
    T("sales_pipeline", "قمع المبيعات: العملاء المحتملون حسب المرحلة والعروض المفتوحة وقيمتها.", obj(), pipeline, category="العملاء")
    T("kpi_set", "تحديد أو تحديث مؤشر أداء لجهة (الهدف والقيمة الحالية).", obj({"workspace": ws, "name": s("اسم المؤشر"),
      "target": {"type": "number"}, "current": {"type": "number"}, "unit": s("الوحدة"), "direction": s("الأعلى أفضل أم الأقل", ["up", "down"]),
      "project": s("")}, ["name"]), kpi_set, category="الأعمال")
    T("meeting_add", "تسجيل اجتماع قادم مع جدول الأعمال وتذكير قبله (لا يرسل دعوات).", obj({"title": s(""), "when": s("YYYY-MM-DDTHH:MM محلي"),
      "duration_min": i(""), "attendees": s("الحضور"), "agenda": s("جدول الأعمال"), "workspace": ws, "project": s(""),
      "remind_before_min": i("التذكير قبل كم دقيقة (افتراضي 30)")}, ["title"]), meeting_add, category="الاجتماعات")
    T("meeting_list", "عرض الاجتماعات.", obj({"filter": s("", ["upcoming", "all"])}), meeting_list, category="الاجتماعات")
    T("meeting_record", "حفظ محضر اجتماع: استخرج أنت القرارات وكل إجراء مطلوب (مع المسؤول والموعد) ومرّرها هنا لتصبح مهامًا فعلية.",
      obj({"meeting": s("رقم الاجتماع أو عنوانه"), "minutes": s("ملخص المحضر"), "decisions": {"type": "array", "items": {"type": "string"}},
           "actions": {"type": "array", "items": {"type": "object", "properties": {"title": s(""), "owner": s("المسؤول"),
                       "due": s("YYYY-MM-DDTHH:MM"), "priority": s("", ["high", "normal", "low"])}, "required": ["title"]}}},
          ["meeting"]), meeting_record, category="الاجتماعات")
    T("export_report", "تصدير إلى Excel (xlsx) أو CSV أو صفحة طباعة/PDF (html): القيود، الفواتير، جهات الاتصال، التقرير المالي، المهام.",
      obj({"kind": s("", ["report", "ledger", "invoices", "contacts", "tasks"]), "format": s("", ["xlsx", "csv", "html"]),
           "workspace": ws, "month": s("YYYY-MM"), "year": s("YYYY")}, ["kind"]), export_report, category="التصدير")


__all__ = ["register_business_tools", "export_rows", "report_html", "finance_overview_all", "finance_list",
           "invoice_list", "meeting_list", "recurring_list", "business_alerts", "global_search", "list_workspaces", "workspace_overview_data",
           "finance_report_data", "invoice_html", "find_ws", "contact_rows", "money", "parse_amount"]
