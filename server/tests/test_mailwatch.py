"""مراقبة البريد: اقتراح رد لكل رسالة جديدة، لا إرسال إلا بموافقة، لا تكرار، تخطي الآلية ورسائله هو،
والإرسال الفعلي عبر خادم SMTP محلي حقيقي مع ترويسة In-Reply-To."""
import asyncio
import email
from email.message import EmailMessage

from harith.ai import AIResponse

from test_email import _server, RECEIVED


def _raw(frm: str, subject: str, body: str, mid: str, extra: dict | None = None) -> bytes:
    m = EmailMessage()
    m["From"] = frm
    m["To"] = "harith@example.com"
    m["Subject"] = subject
    m["Message-ID"] = mid
    m["Date"] = "Tue, 27 Oct 2026 05:00:00 +0300"
    for k, v in (extra or {}).items():
        m[k] = v
    m.set_content(body)
    return m.as_bytes()


class _FakeIMAP:
    """IMAP4_SSL وهمي بصندوق قابل للتعديل: رسالة عميل، رسالة من بريده هو، ونشرة آلية."""
    uids: list = ["101", "102", "103"]
    raws: dict = {
        "101": None, "102": None, "103": None,
    }

    def __init__(self, host, port, timeout=30):
        self.host, self.port = host, port

    @classmethod
    def reset(cls):
        cls.uids = ["101", "102", "103"]
        cls.raws = {
            "101": _raw("عميل مهم <client@example.com>", "استفسار عن الحالة", "مرحبًا، ما آخر المستجدات؟", "<m101@x>"),
            "102": _raw("Harith <harith@example.com>", "رسالتي أنا", "نص", "<m102@x>"),
            "103": _raw("نشرة <news@example.com>", "نشرة", "نص", "<m103@x>", {"Auto-Submitted": "auto-generated"}),
        }

    def login(self, user, password):
        self.logged_in = (user, password)

    def select(self, box):
        assert box == "INBOX"

    def uid(self, cmd, *a):
        if cmd == "search":
            return "OK", [" ".join(_FakeIMAP.uids).encode()]
        if cmd == "fetch":
            u = a[0].decode() if isinstance(a[0], bytes) else a[0]
            raw = _FakeIMAP.raws[u]
            return "OK", [(b"1 (UID %s BODY[] {%d}" % (u.encode(), len(raw)), raw)]
        raise AssertionError(cmd)

    def logout(self):
        pass


def _setup(app, monkeypatch, user):
    _FakeIMAP.reset()
    app.s.imap_host, app.s.imap_port = "imap.test", 993
    app.s.imap_user, app.s.imap_password = "harith@example.com", "app-pass"
    monkeypatch.setattr("harith.mailwatch.imaplib.IMAP4_SSL", _FakeIMAP)
    app.ai.fake_handler = lambda messages, tools: AIResponse(text="شكرًا على رسالتك، سأراجع وأرد عليك قريبًا.\n\nالحارث")
    sent = []
    orig = app.notify_approval
    async def cap(uid, aid, summary):
        sent.append((uid, aid, summary))
        await orig(uid, aid, summary)
    app.notify_approval = cap
    return sent


def test_mailwatch_seeds_then_proposes_once_and_sends_after_approval(app, user, monkeypatch):
    sent = _setup(app, monkeypatch, user)
    job = {"id": 1, "user_id": user["id"]}
    r0 = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "البذرة الأولى" in r0 and "3 رسالة" in r0, r0
    assert app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply'") == []  # لا اقتراحات للقديم
    # رسالة جديدة تصل بعد البذرة
    _FakeIMAP.uids.append("104")
    _FakeIMAP.raws["104"] = _raw("عميل مهم <client@example.com>", "استفسار عن الحالة", "مرحبًا، ما آخر المستجدات؟", "<m101@x>")
    r = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "1 اقتراح" in r, r
    rows = app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))
    assert len(rows) == 1  # رسالة العميل فقط؛ رسالته هو والنشرة الآلية تُخطآن
    import json
    args = json.loads(rows[0]["args"])
    assert args["to"] == "client@example.com" and args["subject"].startswith("Re: ")
    assert args["in_reply_to"] == "<m101@x>" and "الحارث" in args["body"]
    assert len(sent) == 1 and "الرد المقترح" in sent[0][2]
    # دورة ثانية: لا اقتراح مكرر لنفس الرسائل
    r2 = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "0 اقتراح" in r2
    assert len(app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))) == 1
    # الجدولة الذاتية للدورة التالية (مفتاح منع التكرار مرتبط بموعد الدورة لا بمفتاح ثابت)
    assert app.db.one("SELECT 1 x FROM jobs WHERE kind='mailwatch' AND status='pending' AND user_id=?",
                      (user["id"],))
    # دورة إضافية بنفس الموعد لا تُكرر الصف، والمهمة المكتملة لا تمنع الجدولة
    app.mailwatch._schedule_next(user["id"])
    # الموافقة ترسل فعليًا مع In-Reply-To، والرفض لا يرسل
    srv = _server()
    app.s.smtp_host, app.s.smtp_port, app.s.smtp_user, app.s.smtp_from = "127.0.0.1", srv.server_address[1], "", "rafiq@example.com"
    RECEIVED.clear()
    aid = rows[0]["id"]
    ok = asyncio.run(app.decide_approval(user["id"], aid, True))
    assert ok["ok"], ok
    assert len(RECEIVED) == 1 and "In-Reply-To:" in RECEIVED[0]["data"]
    assert app.db.one("SELECT status FROM approvals WHERE id=?", (aid,))["status"] == "executed"
    srv.shutdown(); srv.server_close()


def test_mailwatch_disabled_and_unconfigured(app, user, monkeypatch):
    sent = _setup(app, monkeypatch, user)
    app.db.set_setting(user["id"], "mailwatch_enabled", False)
    r = asyncio.run(app.mailwatch.run({"id": 2, "user_id": user["id"]}, {"uid": user["id"]}))
    assert "متوقف" in r and sent == []
    app.db.set_setting(user["id"], "mailwatch_enabled", True)
    app.s.imap_host = ""
    r2 = asyncio.run(app.mailwatch.run({"id": 3, "user_id": user["id"]}, {"uid": user["id"]}))
    assert "غير مُعدّ" in r2


def test_ensure_recurring_enqueues_only_configured_users(app, user):
    app.s.mailwatch_users = f"{user['id']},x,"
    app.scheduler.ensure_recurring()
    rows = app.db.all("SELECT user_id,dedupe_key FROM jobs WHERE kind='mailwatch'")
    assert len(rows) == 1 and rows[0]["user_id"] == user["id"]
    app.scheduler.ensure_recurring()  # تكرار آمن
    assert len(app.db.all("SELECT user_id FROM jobs WHERE kind='mailwatch'")) == 1
    app.s.mailwatch_users = ""


def test_gemini_backup_key_failover(app, monkeypatch):
    """عند استنفاد حصة المفتاح الأساسي (429) يُستخدم المفتاح الاحتياطي تلقائيًا."""
    import asyncio
    from harith.ai import AIError, AIResponse
    app.s.ai_provider, app.s.ai_fallback_provider = "gemini", ""
    app.s.gemini_api_key = "primary-key"
    app.s.gemini_api_key_backup = "backup-key"
    app.s.ai_proxy_key_failover = True
    tried: list = []

    async def fake_gemini(self, model, system, messages, tools, key=None):
        tried.append((key, model))
        if key is None:
            raise AIError("gemini 429: quota", retryable=True, status=429)
        return AIResponse(text="رد من الاحتياطي")
    monkeypatch.setattr("harith.ai.AIRouter._gemini", fake_gemini)
    r = asyncio.run(app.ai.chat("sys", [{"role": "user", "content": "x"}], []))
    assert r.text == "رد من الاحتياطي" and tried[-1][0] == "backup-key" and all(k is None for k, _ in tried[:-1])


def test_gemini_model_fallback_when_both_keys_exhausted(app, monkeypatch):
    """إذا استُنفد النموذج المضبوط على المفتاحين معًا يُجرَّب النموذج الافتراضي."""
    import asyncio
    from harith.ai import AIError, AIResponse, DEFAULT_MODELS
    app.s.ai_provider, app.s.ai_fallback_provider = "gemini", ""
    app.s.gemini_api_key = "primary-key"
    app.s.gemini_api_key_backup = "backup-key"
    app.s.ai_proxy_key_failover = True
    app.s.ai_model = "gemini-3.5-flash"
    tried: list = []

    async def fake_gemini(self, model, system, messages, tools, key=None):
        tried.append((key, model))
        if model == "gemini-3.5-flash":
            raise AIError("gemini 429: quota", retryable=True, status=429)
        return AIResponse(text="رد بالنموذج الافتراضي")
    monkeypatch.setattr("harith.ai.AIRouter._gemini", fake_gemini)
    monkeypatch.setattr("harith.ai.AIRouter._with_retry", lambda self, fn, attempts=1: fn())
    r = asyncio.run(app.ai.chat("sys", [{"role": "user", "content": "x"}], []))
    assert r.text == "رد بالنموذج الافتراضي"
    assert tried[-1][1] == DEFAULT_MODELS["gemini"] and tried[0][1] == "gemini-3.5-flash"


def test_mailwatch_window_seed_for_upgraded_users(app, user, monkeypatch):
    """من فُعّلت مراقبته بنمط «غير المقروء فقط»: أول دورة بعد الترقية تسجّل رسائل النافذة
    (ومنها المقروءة) دون اقتراحات، ثم تبدأ الاقتراحات بالجديد فقط — مقروءًا كان أو غير مقروء."""
    sent = _setup(app, monkeypatch, user)
    app.db.set_setting(user["id"], "mailwatch_seeded", True)  # بذرة قديمة بنمط UNSEEN
    job = {"id": 1, "user_id": user["id"]}
    r0 = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "تفعيل مراقبة المقروء وغير المقروء" in r0 and "3 رسالة" in r0, r0
    assert app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply'") == []
    # رسالة جديدة بعد التفعيل تحصل على اقتراح حتى لو قرأها قبل دورة الفحص (بحث SINCE لا UNSEEN)
    _FakeIMAP.uids.append("201")
    _FakeIMAP.raws["201"] = _raw("عميل مهم <client@example.com>", "طلب عرض سعر", "نرجو التفاصيل.", "<m201@x>")
    r = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "1 اقتراح" in r, r
    rows = app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))
    assert len(rows) == 1 and len(sent) == 1


def test_revise_email_reply_then_approve_sends_revised(app, user, monkeypatch):
    """تعديل المسودة بتعليمة قصيرة: تتحدث الحجج والملخص ويبقى الطلب معلّقًا،
    والموافقة بعد التعديل ترسل النص المعدَّل فعليًا."""
    import json
    from harith.ai import AIResponse as _R
    sent = _setup(app, monkeypatch, user)
    app.db.set_setting(user["id"], "mailwatch_seeded", True)
    app.db.set_setting(user["id"], "mailwatch_window_seeded", True)
    job = {"id": 1, "user_id": user["id"]}
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))  # كل الرسائل مُعالجة سابقًا
    _FakeIMAP.uids.append("301")
    _FakeIMAP.raws["301"] = _raw("عميل مهم <client@example.com>", "استفسار", "ما الجديد؟", "<m301@x>")
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    row = app.db.one("SELECT * FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))
    assert row and row["status"] == "pending"
    aid = row["id"]
    # تعليمة فارغة تُرفض، وغير المعلّق يُرفض
    assert not asyncio.run(app.revise_email_reply(user["id"], aid, "   "))["ok"]
    assert not asyncio.run(app.revise_email_reply(user["id"], aid + 999, "x"))["ok"]
    # تعليمة حقيقية تُحدّث المسودة ويبقى الطلب معلقًا
    app.ai.fake_handler = lambda messages, tools: _R(text="أهلًا بك، العرض جاهز وسيصلك اليوم.\n\nالحارث")
    res = asyncio.run(app.revise_email_reply(user["id"], aid, "أخبره أن العرض سيصل اليوم"))
    assert res["ok"] and "العرض جاهز" in res["args"]["body"]
    assert "العرض جاهز" in res["summary"] and "— الرد المقترح —" in res["summary"]
    row2 = app.db.one("SELECT * FROM approvals WHERE id=?", (aid,))
    assert row2["status"] == "pending" and "العرض جاهز" in json.loads(row2["args"])["body"]
    # الموافقة ترسل النص المعدَّل
    srv = _server()
    app.s.smtp_host, app.s.smtp_port, app.s.smtp_user, app.s.smtp_from = "127.0.0.1", srv.server_address[1], "", "rafiq@example.com"
    RECEIVED.clear()
    ok = asyncio.run(app.decide_approval(user["id"], aid, True))
    assert ok["ok"], ok
    assert len(RECEIVED) == 1 and "العرض جاهز" in RECEIVED[0]["data"]
    srv.shutdown(); srv.server_close()
    # بعد التنفيذ لا يقبل تعديلًا
    assert not asyncio.run(app.revise_email_reply(user["id"], aid, "مرة أخرى"))["ok"]


def test_draft_language_matches_incoming(app, user, monkeypatch):
    """لغة الرد المقترح تُطلب بنفس لغة البريد الوارد: عربي ← عربي وإنجليزي ← إنجليزي،
    بتلميح صريح «لغة الرد المطلوبة» داخل الطلب المُرسل للنموذج."""
    sent = _setup(app, monkeypatch, user)
    seen = []
    orig = app.ai.fake_handler
    def cap(messages, tools):
        seen.append(messages[-1]["content"])
        return orig(messages, tools)
    app.ai.fake_handler = cap
    job = {"id": 1, "user_id": user["id"]}
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))  # البذرة
    _FakeIMAP.uids += ["401", "402"]
    _FakeIMAP.raws["401"] = _raw("عميل <client@example.com>", "استفسار", "ما آخر المستجدات؟", "<m401@x>")
    _FakeIMAP.raws["402"] = _raw("Partner <partner@example.com>", "Pricing question",
                                 "Could you send your rates please?", "<m402@x>")
    r = asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert "2 اقتراح" in r, r
    assert any("لغة الرد المطلوبة: العربية" in m for m in seen), seen
    assert any("لغة الرد المطلوبة: غير العربية" in m for m in seen), seen
    rows = app.db.all("SELECT * FROM approvals WHERE tool='send_email_reply' AND user_id=?", (user["id"],))
    assert len(rows) == 2


def test_duplicate_chains_self_heal(app, user, monkeypatch):
    """سلسلتان مكررتان (خلل قديم عند إقلاع أثناء التنفيذ) تلتئمان تلقائيًا إلى سلسلة واحدة:
    دورة تنتهي وسلسلة أخرى بانتظار التنفيذ ← لا تُجدل دورة جديدة."""
    _setup(app, monkeypatch, user)
    app.db.set_setting(user["id"], "mailwatch_seeded", True)
    app.db.set_setting(user["id"], "mailwatch_window_seeded", True)
    # سلسلة زائدة بانتظار التنفيذ (كما كان يحدث عند الإقلاع أثناء دورة قيد التنفيذ)
    from harith.db import iso as db_iso
    from harith.db import utcnow as _utcnow
    from datetime import timedelta as _td
    app.scheduler.enqueue(user["id"], "mailwatch", {"uid": user["id"]},
                          db_iso(_utcnow() + _td(minutes=5)), max_attempts=2)
    before = len(app.db.all("SELECT id FROM jobs WHERE kind='mailwatch' AND status='pending' AND user_id=?",
                            (user["id"],)))
    asyncio.run(app.mailwatch.run({"id": 77, "user_id": user["id"]}, {"uid": user["id"]}))
    after = len(app.db.all("SELECT id FROM jobs WHERE kind='mailwatch' AND status='pending' AND user_id=?",
                           (user["id"],)))
    assert before == 1 and after == 1  # لم تُولد دورة ثالثة؛ بقيت السلسلة الواحدة


def test_drafts_use_the_account_owner_name_and_daily_cap(app, user, monkeypatch):
    """المسودة باسم صاحب الحساب نفسه (لا اسم ثابت)، والفرز بالذكاء له حد يومي لكل مستخدم."""
    _setup(app, monkeypatch, user)
    app.db.execute("UPDATE users SET display_name='سارة' WHERE id=?", (user["id"],))
    systems = []
    orig = app.ai.chat
    async def cap(system, messages, tools, *a, **kw):
        systems.append(system)
        return await orig(system, messages, tools, *a, **kw)
    monkeypatch.setattr(app.ai, "chat", cap)
    job = {"id": 1, "user_id": user["id"]}
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))  # البذرة
    app.db.set_setting(user["id"], "mailwatch_ai_daily", 1)
    _FakeIMAP.uids += ["501", "502"]
    _FakeIMAP.raws["501"] = _raw("عميل <c1@example.com>", "سؤال", "متى التسليم؟", "<m501@x>")
    _FakeIMAP.raws["502"] = _raw("عميل <c2@example.com>", "سؤال آخر", "كم السعر؟", "<m502@x>")
    asyncio.run(app.mailwatch.run(job, {"uid": user["id"]}))
    assert len(systems) == 1, "الحد اليومي = 1 طلب فرز فقط"
    assert "مساعد سارة" in systems[0] and "الحارث" not in systems[0]
