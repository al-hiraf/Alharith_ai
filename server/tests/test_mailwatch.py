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
    # الجدولة الذاتية للدورة التالية
    assert app.db.one("SELECT 1 x FROM jobs WHERE kind='mailwatch' AND status='pending' AND dedupe_key=?",
                      (f"mailwatch:next:{user['id']}",))
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
