"""البريد: إرسال فعلي عبر SMTP إلى خادم بريد محلي حقيقي (بروتوكول SMTP كامل)، الموافقة قبل الإرسال،
عدم التكرار عند إعادة الموافقة، وتسجيل الفشل بسبب واضح."""
import asyncio
import socket
import socketserver
import threading

from harith.toolkit import Ctx

RECEIVED: list[dict] = []


class _SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        w = lambda s: self.wfile.write((s + "\r\n").encode())  # noqa: E731
        w("220 test ESMTP")
        mail = {"rcpt": [], "data": ""}
        while True:
            line = self.rfile.readline().decode(errors="replace").rstrip("\r\n")
            if not line:
                return
            cmd = line[:4].upper()
            if cmd in ("EHLO", "HELO"):
                w("250-test"); w("250 8BITMIME")
            elif cmd == "MAIL":
                mail["from"] = line; w("250 OK")
            elif cmd == "RCPT":
                mail["rcpt"].append(line); w("250 OK")
            elif cmd == "DATA":
                w("354 go")
                buf = []
                while True:
                    l2 = self.rfile.readline().decode(errors="replace")
                    if l2.rstrip("\r\n") == ".":
                        break
                    buf.append(l2)
                mail["data"] = "".join(buf); RECEIVED.append(dict(mail)); w("250 queued")
            elif cmd == "QUIT":
                w("221 bye"); return
            else:
                w("250 OK")


def _server():
    srv = socketserver.ThreadingTCPServer(("127.0.0.1", 0), _SMTP)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def test_email_needs_approval_sends_once_and_logs_failures(app, user):
    srv = _server()
    app.s.smtp_host, app.s.smtp_port, app.s.smtp_user, app.s.smtp_from = "127.0.0.1", srv.server_address[1], "", "rafiq@example.com"
    RECEIVED.clear()
    ctx = Ctx(app, user, "web", "t1")
    res = asyncio.run(app.tools.execute(ctx, "send_email", {"to": "client@example.com", "subject": "فاتورة", "body": "مرحبًا، مرفق ملخص الفاتورة."}))
    assert "موافقة" in res.text and RECEIVED == []  # لا يُرسل قبل الموافقة
    aid = app.db.one("SELECT id FROM approvals WHERE user_id=? AND tool='send_email'", (user["id"],))["id"]
    r = asyncio.run(app.decide_approval(user["id"], aid, True))
    assert r["ok"] and len(RECEIVED) == 1, r
    assert "client@example.com" in RECEIVED[0]["rcpt"][0] and "Subject:" in RECEIVED[0]["data"]
    r2 = asyncio.run(app.decide_approval(user["id"], aid, True))  # موافقة مكررة لا تعيد الإرسال
    assert not r2["ok"] and len(RECEIVED) == 1
    assert app.db.one("SELECT status FROM approvals WHERE id=?", (aid,))["status"] == "executed"
    # فشل: خادم البريد متوقف ← العملية تُسجّل فاشلة بسبب واضح، ولا نجاح كاذب
    srv.shutdown(); srv.server_close()
    s = socket.socket(); s.bind(("127.0.0.1", 0)); dead = s.getsockname()[1]; s.close()
    app.s.smtp_port = dead
    asyncio.run(app.tools.execute(ctx, "send_email", {"to": "client@example.com", "subject": "x", "body": "y"}))
    aid2 = app.db.one("SELECT id FROM approvals WHERE user_id=? AND tool='send_email' AND status='pending'", (user["id"],))["id"]
    r3 = asyncio.run(app.decide_approval(user["id"], aid2, True))
    assert not r3["ok"] and "فشل" in r3["text"]
    assert app.db.one("SELECT status FROM approvals WHERE id=?", (aid2,))["status"] == "failed"
    assert len(RECEIVED) == 1


def test_email_refuses_bad_address_and_unconfigured(app, user):
    ctx = Ctx(app, user, "web", "t2")
    app.s.smtp_host = ""
    from harith.tools import send_email
    r = asyncio.run(send_email(ctx, {"to": "a@b.com", "subject": "s", "body": "b"}))
    assert not r.ok and "غير مُعدّ" in r.text
    app.s.smtp_host = "127.0.0.1"
    try:
        asyncio.run(send_email(ctx, {"to": "not-an-email", "subject": "s", "body": "b"}))
        assert False
    except ValueError as e:
        assert "غير صحيح" in str(e)
