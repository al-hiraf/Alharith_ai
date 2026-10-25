"""لوحة المدير: تحليلات حقيقية، شرائح، مراسلة بحالة صادقة، بريد اختياري بمصدره، وتنظيف غير النشطين."""
from datetime import timedelta

from harith.db import iso, now_iso, utcnow
from harith.security import create_user, new_api_token, new_session
from test_acceptance import client
from test_team import bearer


def mk_app_user(app, name, suffix, last_seen=None, created=None, disabled=False):
    uid = create_user(app.db, f"app_{suffix}", "password12345", role="user", display_name=name)
    if last_seen:
        new_api_token(app.db, uid, "جوال")
        app.db.execute("UPDATE api_tokens SET last_used=? WHERE user_id=?", (last_seen, uid))
    if created:
        app.db.execute("UPDATE users SET created_at=? WHERE id=?", (created, uid))
    if disabled:
        app.db.execute("UPDATE users SET disabled=1 WHERE id=?", (uid,))
    return uid


def test_analytics_admin_only_and_truthful(app, user):
    adm = client(app)
    old = iso(utcnow() - timedelta(days=40))
    now = iso(utcnow())
    a = mk_app_user(app, "نشيط", "a1", last_seen=now)
    mk_app_user(app, "خامل", "b2", last_seen=old)
    mk_app_user(app, "لم يظهر", "c3", created=old)
    mk_app_user(app, "موقوف", "d4", last_seen=now, disabled=True)
    app.db.insert("INSERT INTO messages(user_id,channel,role,content,created_at) VALUES(?,?,?,?,?)",
                  (a, "android", "user", "مهمة", now))
    # غير المدير ممنوع
    tok = new_api_token(app.db, a, "جوال2")
    t = bearer(app, tok)
    for path in ("/api/admin/analytics", "/api/admin/audience", "/api/admin/broadcasts"):
        assert t.get(path).status_code == 403
    assert t.post("/api/admin/broadcast", json={"text": "x"}).status_code == 403
    assert t.post("/api/admin/users/delete", json={"ids": [1], "confirm": "احذف"}).status_code == 403

    r = adm.get("/api/admin/analytics?days=30").json()
    T, R = r["totals"], r["reach"]
    assert T["app_users"] == 4 and T["enabled"] == 3 and T["disabled"] == 1
    assert T["inactive"] == 2  # خامل + لم يظهر
    assert T["never_seen"] == 1
    assert R["inbox"] == 3
    assert any(d["n"] >= 1 for d in r["signups"])
    assert any(d["n"] >= 1 for d in r["active"])  # نشيط: رسالة + ظهور
    # المدة تُقيَّد بين 7 و90
    assert adm.get("/api/admin/analytics?days=9999").json()["days"] == 90


def test_audience_kinds(app, user):
    adm = client(app)
    old = iso(utcnow() - timedelta(days=40))
    now = iso(utcnow())
    mk_app_user(app, "نشيط", "a1", last_seen=now)
    iid = mk_app_user(app, "خامل", "b2", last_seen=old)
    did = mk_app_user(app, "موقوف", "d4", last_seen=now, disabled=True)
    q = adm.get("/api/admin/audience?kind=active&days=30").json()
    assert [x["display_name"] for x in q["users"]] == ["نشيط"]
    q = adm.get("/api/admin/audience?kind=inactive&days=30").json()
    assert [x["display_name"] for x in q["users"]] == ["خامل"]  # الموقوف نشط فلا يدخل
    q = adm.get("/api/admin/audience?kind=all").json()
    assert {x["id"] for x in q["users"]} >= {iid, did}
    # بلا بريد مسجّل: شريحة البريد فارغة بصدق
    q = adm.get("/api/admin/audience?kind=email").json()
    assert q["count"] == 0
    assert adm.get("/api/admin/audience?kind=bogus").status_code == 400


def test_broadcast_app_truthful_states(app, user):
    adm = client(app)
    u1 = mk_app_user(app, "واحد", "a1", last_seen=iso(utcnow()))
    u2 = mk_app_user(app, "اثنان", "b2", last_seen=iso(utcnow()))
    off = mk_app_user(app, "موقوف", "d4", last_seen=iso(utcnow()), disabled=True)
    # الإرسال بلا معرّفات معاينة مرفوض — لا تُحسب الشريحة من جديد عند الإرسال
    assert adm.post("/api/admin/broadcast", json={"text": "هلا", "kind": "all"}).status_code == 400
    # المعرّفات من المعاينة حرفيًا؛ الموقوف يُتخطى بسبب صادق
    ids = [x["id"] for x in adm.get("/api/admin/audience?kind=all").json()["users"]]
    r = adm.post("/api/admin/broadcast", json={"text": "تحديث مهم", "kind": "all", "ids": ids}).json()
    assert r["recipients"] == 2 and r["sent"] == 2 and r["failed"] == 0
    assert r["skipped"] == [{"id": off, "reason": "حساب موقوف"}]
    for uid in (u1, u2):
        m = app.db.one("SELECT * FROM messages WHERE user_id=? AND role='notice'", (uid,))
        assert m["content"] == "تحديث مهم" and m["read_at"] is None
    assert app.db.one("SELECT 1 FROM messages WHERE user_id=? AND role='notice'", (off,)) is None
    # السجل والتفاصيل: صندوق التطبيق ✓، لم يفتح أحد صندوقه بعد
    rows = adm.get("/api/admin/broadcasts").json()
    assert rows[0]["recipients"] == 2 and rows[0]["inbox_opened"] == 0 and rows[0]["ok"] == 2
    d = adm.get(f"/api/admin/broadcasts/{rows[0]['id']}").json()
    assert len(d["sends"]) == 2
    s0 = d["sends"][0]
    assert s0["status"]["inbox"] is True and s0["status"]["ok"] is True and s0["inbox_opened"] is False
    # واحد يفتح صندوق إشعاراته ← يظهر «فتح الصندوق» (وليست قراءة مؤكدة لهذه الرسالة)
    tok = new_api_token(app.db, u1, "جوال")
    bearer(app, tok).post("/api/notifications/read", json={"all": True})
    d = adm.get(f"/api/admin/broadcasts/{rows[0]['id']}").json()
    by_name = {s["display_name"]: s for s in d["sends"]}
    assert by_name["واحد"]["inbox_opened"] is True and by_name["اثنان"]["inbox_opened"] is False
    # نص طويل أو فارغ مرفوض
    assert adm.post("/api/admin/broadcast", json={"text": ""}).status_code == 400
    assert adm.post("/api/admin/broadcast", json={"text": "x" * 501, "ids": [u1]}).status_code == 400


def test_broadcast_binds_to_previewed_ids(app, user):
    """لا مستلم إضافي صامت: من انضم بعد المعاينة لا يُراسَل، ومن فقد الأهلية يُتخطى بسبب."""
    adm = client(app)
    u1 = mk_app_user(app, "قديم", "a1", last_seen=iso(utcnow()))
    ids = [x["id"] for x in adm.get("/api/admin/audience?kind=all").json()["users"]]  # معاينة قبل الجديد
    newcomer = mk_app_user(app, "انضم بعد المعاينة", "b2", last_seen=iso(utcnow()))
    r = adm.post("/api/admin/broadcast", json={"text": "للمعاينة فقط", "kind": "all", "ids": ids}).json()
    assert r["recipients"] == 1  # الجديد لم يُضف صامتًا
    assert app.db.one("SELECT 1 FROM messages WHERE user_id=? AND role='notice'", (u1,)) is not None
    assert app.db.one("SELECT 1 FROM messages WHERE user_id=? AND role='notice'", (newcomer,)) is None
    # معرّف لم يعد مطابقًا للشريحة يُتخطى بسبب صادق
    app.db.execute("UPDATE users SET disabled=1 WHERE id=?", (newcomer,))
    r = adm.post("/api/admin/broadcast",
                 json={"text": "شريحة النشطين", "kind": "active", "days": 30, "ids": [u1, newcomer]}).json()
    assert r["recipients"] == 1 and [s["reason"] for s in r["skipped"]] == ["حساب موقوف"]
    # معرّف ليس حساب تطبيق أصلًا (المدير مثلًا)
    r = adm.post("/api/admin/broadcast", json={"text": "x", "kind": "all", "ids": [u1, user["id"]]}).json()
    assert r["recipients"] == 1 and [s["reason"] for s in r["skipped"]] == ["ليس حساب تطبيق موجودًا"]


def test_email_capture_provenance_and_campaign_gate(app, user):
    """جمع البريد مع مصدره وحالة توثيقه مستمر؛ الإرسال البريدي الجماعي معطّل بلا موافقة تسويقية وإيقاف موثّق."""
    adm = client(app)
    u1 = mk_app_user(app, "صاحب بريد", "a1", last_seen=iso(utcnow()))
    mk_app_user(app, "بلا بريد", "b2", last_seen=iso(utcnow()))
    # تحقق الصيغة
    assert adm.patch(f"/api/users/{u1}", json={"email": "not-an-email"}).status_code == 400
    # المدير يسجّل بريدًا ← مصدره «المدير»
    adm.patch(f"/api/users/{u1}", json={"email": "user1@example.com"})
    row = app.db.one("SELECT email, email_source FROM users WHERE id=?", (u1,))
    assert row["email"] == "user1@example.com" and row["email_source"] == "admin"
    # المستخدم يضيف بريده بنفسه ← مصدره «بنفسه»
    tok = new_api_token(app.db, u1, "جوال")
    t = bearer(app, tok)
    assert t.put("/api/settings", json={"email": "bad@"}).status_code == 400
    t.put("/api/settings", json={"email": "self@example.com"})
    row = app.db.one("SELECT email, email_source, email_verified FROM users WHERE id=?", (u1,))
    assert row["email"] == "self@example.com" and row["email_source"] == "self"
    assert row["email_verified"] == 0  # لا توثيق بعد — يظهر «غير موثّق» بصدق
    assert t.get("/api/me").json()["user"]["email"] == "self@example.com"
    # شريحة البريد تطابق فقط من سجّل (إدارة العناوين مستمرة)
    q = adm.get("/api/admin/audience?kind=email").json()
    assert [x["display_name"] for x in q["users"]] == ["صاحب بريد"]
    # الحملة البريدية معطّلة بصدق — حتى مع SMTP مُعدّ وحتى بمعرّفات صالحة
    app.s.smtp_host, app.s.smtp_port = "127.0.0.1", 2525
    try:
        r = adm.post("/api/admin/broadcast",
                     json={"text": "نص البريد", "kind": "email", "channel": "email", "ids": [u1]})
        assert r.status_code == 409
        assert "ليس موافقة تسويقية" in str(r.json()) and "إيقاف" in str(r.json())
        # ولا رسالة وصلت صندوق أحد
        assert app.db.one("SELECT 1 FROM messages WHERE role='notice'") is None
    finally:
        app.s.smtp_host = ""


def test_admin_delete_inactive_requires_confirm_and_protects(app, user):
    adm = client(app)
    uid = mk_app_user(app, "قديم", "a1", created=iso(utcnow() - timedelta(days=60)))
    tok = new_api_token(app.db, uid, "جوال")
    app.db.insert("INSERT INTO messages(user_id,channel,role,content,created_at) VALUES(?,?,?,?,?)",
                  (uid, "android", "user", "قديمة", iso(utcnow())))
    # بلا تأكيد أو بتأكيد خاطئ: مرفوض
    assert adm.post("/api/admin/users/delete", json={"ids": [uid]}).status_code == 400
    assert adm.post("/api/admin/users/delete", json={"ids": [uid], "confirm": "delete"}).status_code == 400
    # المدير نفسه وحسابات اللوحة (غير app_) محمية
    r = adm.post("/api/admin/users/delete", json={"ids": [user["id"], uid], "confirm": "احذف"}).json()
    assert user["id"] in [s["id"] for s in r["skipped"]] and uid in r["deleted"]
    assert app.db.one("SELECT 1 FROM users WHERE id=?", (uid,)) is None
    assert app.db.one("SELECT 1 FROM messages WHERE user_id=?", (uid,)) is None
    assert app.db.one("SELECT 1 FROM api_tokens WHERE token_hash IS NOT NULL AND user_id=?", (uid,)) is None
    # مفتاحه الملغى لم يعد يعمل
    assert bearer(app, tok).get("/api/me").status_code == 401
    # الحذف مسجّل في سجل الأحداث
    assert app.db.one("SELECT 1 FROM event_log WHERE source='privacy' AND message LIKE '%حذف حساب التطبيق%'")


def test_test_account_flag_and_analytics_split(app, user):
    adm = client(app)
    u1 = mk_app_user(app, "بشري", "a1", last_seen=iso(utcnow()))
    u2 = mk_app_user(app, "تجريبي", "b2", created=iso(utcnow() - timedelta(days=50)))
    adm.patch(f"/api/users/{u2}", json={"is_test": True})
    assert app.db.get_setting(u2, "is_test") is True
    r = adm.get("/api/admin/analytics").json()["totals"]
    assert r["app_users"] == 2 and r["test"] == 1 and r["humans"] == 1 and r["unused"] == 0  # التشغيلية بشرية فقط
    q = adm.get("/api/admin/audience?kind=all").json()
    by_id = {x["id"]: x for x in q["users"]}
    assert by_id[u2]["is_test"] == 1 and by_id[u2]["unused"] is True
    assert by_id[u1]["unused"] is False
    adm.patch(f"/api/users/{u2}", json={"is_test": False})
    assert app.db.get_setting(u2, "is_test") is False


def test_last_activity_counts_dashboard_sessions(app, user):
    adm = client(app)
    uid = mk_app_user(app, "يدخل من المتصفح", "a1")  # بلا مفتاح تطبيق إطلاقًا
    assert adm.get("/api/admin/audience?kind=all").json()["users"][0]["last_seen"] is None
    new_session(app.db, uid)  # جلسة لوحة تحكم = نشاط حقيقي
    row = [x for x in adm.get("/api/admin/audience?kind=all").json()["users"] if x["id"] == uid][0]
    assert row["last_seen"] is not None and row["unused"] is False


def test_delete_protects_shared_projects(app, user):
    adm = client(app)
    owner = mk_app_user(app, "صاحب مشروع", "a1")
    member = mk_app_user(app, "عضو", "b2")
    pid = app.db.insert("INSERT INTO projects(user_id,name,created_at,updated_at) VALUES(?,?,?,?)",
                        (owner, "مشروع مشترك", now_iso(), now_iso()))
    app.db.insert("INSERT INTO project_members(project_id,user_id,role,added_at) VALUES(?,?,?,?)",
                  (pid, member, "member", now_iso()))
    r = adm.post("/api/admin/users/delete", json={"ids": [owner], "confirm": "احذف"}).json()
    assert r["deleted"] == [] and r["skipped"][0]["id"] == owner
    assert "مشروع" in r["skipped"][0]["reason"]
    assert app.db.one("SELECT 1 FROM users WHERE id=?", (owner,)) is not None  # لم يُحذف
    # العضو (لا يملك شيئًا مشتركًا) يُحذف طبيعي وتبقى عضويته مرفوعة عن المشروع
    r = adm.post("/api/admin/users/delete", json={"ids": [member], "confirm": "احذف"}).json()
    assert member in r["deleted"]
    assert app.db.one("SELECT 1 FROM project_members WHERE user_id=?", (member,)) is None


def test_notices_bound_to_selected_range(app, user):
    """بطاقة الإشعارات تحترم مدة اللوحة (7/30/90) ولا تعرض أرقام «كل الأوقات» تحتها."""
    adm = client(app)
    uid = mk_app_user(app, "مستلم", "a1", last_seen=iso(utcnow()))
    old = iso(utcnow() - timedelta(days=40))
    new = iso(utcnow())
    for when in (old, new):
        app.db.insert("INSERT INTO messages(user_id,channel,role,content,created_at) VALUES(?,?,?,?,?)",
                      (uid, "web", "notice", "إشعار", when))
    r7 = adm.get("/api/admin/analytics?days=7").json()["notices"]
    assert r7 == {"sent": 1, "inbox_opened": 0}  # القديم خارج المدة لا يُحسب
    r90 = adm.get("/api/admin/analytics?days=90").json()["notices"]
    assert r90 == {"sent": 2, "inbox_opened": 0}


def test_series_exclude_marked_test_accounts(app, user):
    """رسوم التسجيل/النشاط/الرسائل/التكلفة تستبعد المعلَّم «تجريبي» صراحة — لا تخمين بالاسم."""
    adm = client(app)
    human = mk_app_user(app, "بشري", "a1", last_seen=iso(utcnow()))
    fixture = mk_app_user(app, "تجريبي", "b2", last_seen=iso(utcnow()))
    adm.patch(f"/api/users/{fixture}", json={"is_test": True})
    now = now_iso()
    for uid in (human, fixture):
        app.db.insert("INSERT INTO messages(user_id,channel,role,content,created_at) VALUES(?,?,?,?,?)",
                      (uid, "android", "user", "مرحبا", now))
    a = adm.get("/api/admin/analytics?days=7").json()
    assert sum(d["n"] for d in a["messages"]) == 1  # رسالة البشري فقط
    assert sum(d["n"] for d in a["active"]) == 1
    assert sum(d["n"] for d in a["signups"]) == 1
    # إلغاء الوسم يعيده للأرقام — الاستبعاد بالعلامة الصريحة لا بشيء دائم
    adm.patch(f"/api/users/{fixture}", json={"is_test": False})
    a = adm.get("/api/admin/analytics?days=7").json()
    assert sum(d["n"] for d in a["messages"]) == 2
