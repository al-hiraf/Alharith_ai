"""سطر الأوامر:
  python -m harith run                  تشغيل الخادم
  python -m harith check                فحص الإعدادات
  python -m harith create-user NAME     إنشاء مستخدم (يطلب كلمة المرور)
  python -m harith reset-password NAME  تغيير كلمة مرور
  python -m harith backup               نسخة احتياطية الآن
  python -m harith restore FILE         استعادة نسخة (أوقف الخادم أولًا)
  python -m harith pause | resume       الإيقاف الطارئ من الطرفية
  python -m harith publish-url URL      نشر رابط النفق الحالي ليجده تطبيق رفيق
  python -m harith pair                 رمز ربط تطبيق الجوال (6 أرقام) — أو الأمر المختصر: rafiq-pair
  python -m harith webpush-keys [FILE]  توليد مفاتيح VAPID لإشعارات الويب (يُكتب الملف بصلاحية 600، لا يُطبع الخاص)
  python -m harith token NAME           مفتاح وصول لخدمة خارجية + عنوان الخادم — أو: rafiq-token NAME
"""
from __future__ import annotations

import argparse
import asyncio
import getpass
import sys
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="harith")
    p.add_argument("cmd", nargs="?", default="run")
    p.add_argument("arg", nargs="?")
    p.add_argument("--env", help="مسار ملف .env")
    a = p.parse_args(argv)

    if a.cmd == "webpush-keys":
        # توليد زوج مفاتيح VAPID (P-256) لإشعارات الويب. الخاص لا يُطبع أبدًا — يُكتب لملف بصلاحية 600
        import os
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import serialization
        import base64 as _b64m
        b64 = lambda b: _b64m.urlsafe_b64encode(b).rstrip(b"=").decode()
        k = ec.generate_private_key(ec.SECP256R1())
        priv = b64(k.private_numbers().private_value.to_bytes(32, "big"))
        pub = b64(k.public_key().public_bytes(serialization.Encoding.X962,
                                             serialization.PublicFormat.UncompressedPoint))
        out = Path(a.arg or "vapid-keys.env")
        if out.exists():
            print(f"الملف موجود أصلًا: {out} — احذفه أولًا أو اختر مسارًا آخر.", file=sys.stderr)
            return 1
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)   # بلا نافذة صلاحيات، بلا كتابة فوق ملف
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(f"VAPID_PRIVATE_KEY={priv}\nVAPID_PUBLIC_KEY={pub}\n")
        print(f"كُتبت المفاتيح في: {out} (صلاحية 600)")
        print("أضف السطرين إلى ملف .env، واضبط VAPID_SUBJECT على رابط موقعك (https://…) أو mailto:، ثم أعد تشغيل الخدمة.")
        print(f"تحقق: VAPID_PUBLIC_KEY يجب أن يبدأ بـ {pub[:12]}…")
        return 0

    from .config import load_settings
    s = load_settings(a.env)

    if a.cmd == "token":
        # مفتاح وصول لخدمة خارجية (مثل Instinct) + عنوان الخادم الحالي — سطران للنسخ
        import secrets as _s
        from .db import DB
        from .security import create_user, new_api_token
        name = (a.arg or "خدمة خارجية").strip()[:50]
        db = DB(s.db_path)
        u = db.one("SELECT id FROM users WHERE role='admin' AND disabled=0 ORDER BY id LIMIT 1")
        if not u:
            pw = _s.token_urlsafe(9)
            create_user(db, "admin", pw, role="admin", display_name="", tz=s.timezone)
            u = db.one("SELECT id FROM users WHERE username='admin'")
            print(f"أُنشئ حساب المدير: admin / {pw}  (احفظها)")
        tok = new_api_token(db, int(u["id"]), name)
        db.close()
        url_file = s.data_dir / "tunnel-url.txt"
        public = url_file.read_text(encoding="utf-8").strip() if url_file.exists() else ""
        print()
        print(f"المفتاح ({name}) — انسخه كاملًا:")
        print(tok)
        print()
        if public:
            print("عنوان الخادم على الإنترنت:")
            print(public)
            print("(يتغير عند إعادة تشغيل الخادم — شغّل rafiq-token مرة أخرى لتعرف الجديد)")
        else:
            print(f"عنوان الخادم على هذا الجوال فقط: http://127.0.0.1:{s.port}")
            print("للوصول من الإنترنت فعّل النفق: أعد أمر التثبيت واختر y عند سؤال التوزيع.")
        print()
        print("لإلغاء هذا المفتاح لاحقًا: لوحة التحكم ← الإعدادات ← تطبيق رفيق على الجوال.")
        return 0

    if a.cmd == "pair":
        # رمز ربط تطبيق الجوال — ينشئ حساب المدير تلقائيًا إن لم يوجد
        import secrets as _s
        from .db import DB
        from .security import create_user, new_pair_code
        db = DB(s.db_path)
        u = db.one("SELECT id, username FROM users WHERE role='admin' AND disabled=0 ORDER BY id LIMIT 1")
        if not u:
            pw = _s.token_urlsafe(9)
            create_user(db, "admin", pw, role="admin", display_name="", tz=s.timezone)
            u = db.one("SELECT id, username FROM users WHERE username='admin'")
            print(f"أُنشئ حساب المدير للوحة التحكم:  المستخدم admin  —  كلمة المرور {pw}  (احفظها)")
        code = new_pair_code(db, int(u["id"]))
        db.close()
        print()
        print("  ╔══════════════════════════╗")
        print(f"  ║   رمز الربط:  {code[:3]} {code[3:]}     ║")
        print("  ╚══════════════════════════╝")
        print()
        print("  في تطبيق رفيق: الأعمال (أو الإعدادات ← العقل المشترك) ← «اربط برمز» واكتب الرمز.")
        print("  صالح 10 دقائق ولمرة واحدة.")
        return 0

    if a.cmd == "publish-url":
        import os
        from .publish import publish_url
        new_url = (a.arg or "").strip()
        try:  # نحفظ الرابط محليًا دائمًا (لأمر rafiq-token وشاشة «خادمي») حتى لو لم يُضبط النشر على GitHub
            s.data_dir.mkdir(parents=True, exist_ok=True)
            f = s.data_dir / "tunnel-url.txt"
            old_url = f.read_text(encoding="utf-8").strip() if f.exists() else ""
            f.write_text(new_url, encoding="utf-8")
            if old_url and new_url and old_url != new_url:
                # تنبيه المدير بالرابط الجديد (يصل للتطبيق وتيليجرام عبر المجدول)
                import json as _json
                from .db import DB, now_iso
                db = DB(s.db_path)
                for u in db.all("SELECT id FROM users WHERE role='admin' AND disabled=0"):
                    try:
                        db.insert("INSERT INTO jobs(user_id,kind,payload,run_at,max_attempts,dedupe_key,created_at) "
                                  "VALUES(?,?,?,?,?,?,?)",
                                  (u["id"], "notify", _json.dumps({"text": "🔗 تغيّر رابط خادم رفيق على الإنترنت:\n" + new_url +
                                   "\nحدّثه في Instinct وأي خدمة مربوطة. (التطبيق يحدّث نفسه تلقائيًا)"}, ensure_ascii=False),
                                   now_iso(), 3, f"tunnel-url:{u['id']}:{new_url}", now_iso()))
                    except Exception:  # noqa: BLE001
                        pass
                db.close()
        except OSError:
            pass
        try:
            print("✅ " + publish_url(a.arg or "", os.environ.get("GITHUB_TOKEN", ""),
                                      os.environ.get("GITHUB_REPO", "al-hiraf/Alharith_ai")) + f": {a.arg}")
            return 0
        except Exception as e:  # noqa: BLE001
            print(f"❌ {e}")
            return 1

    if a.cmd == "restore":
        from .scheduler import restore_backup
        if not a.arg:
            print("حدد ملف النسخة. النسخ المتاحة:")
            for b in sorted(s.backups_dir.glob("harith-*.db")):
                print("  ", b.name)
            return 1
        src = Path(a.arg)
        if not src.exists():
            src = s.backups_dir / a.arg
        restore_backup(s.db_path, src)
        print(f"✅ استُعيدت النسخة {src.name}. شغّل الخادم الآن.")
        return 0

    from .core import Harith
    app = Harith(s)

    if a.cmd == "run":
        import uvicorn
        from .web import create_app
        if s.telegram_bot_token:
            from .telegram import Telegram
            app.channels.append(Telegram(app, s.telegram_bot_token))
        if s.fcm_credentials_file:
            from .fcm import FcmPush
            app.push = FcmPush(app)
            app.channels.append(app.push)
        if s.vapid_private_key and s.vapid_public_key:
            from .webpush import WebPush
            app.webpush = WebPush(app)
            app.channels.append(app.webpush)
        if not app.db.one("SELECT 1 FROM users LIMIT 1"):
            print(f"⚠️ لا يوجد مستخدمون: افتح http://{s.host}:{s.port} لإنشاء حساب المدير.")
        print(f"رفيق يعمل على http://{s.host}:{s.port}  (الذكاء الاصطناعي: {s.ai_provider}, "
              f"تيليجرام: {'مفعّل' if s.telegram_bot_token else 'غير مُعدّ'})")
        uvicorn.run(create_app(app), host=s.host, port=s.port, log_level="warning", proxy_headers=True)
        return 0

    if a.cmd == "check":
        h = app.health()
        ok = lambda b: "✅" if b else "⚠️"  # noqa: E731
        print(f"{ok(True)} قاعدة البيانات: {s.db_path} (الإصدار {h['schema_version']})")
        print(f"{ok(h['ai']['configured'])} الذكاء الاصطناعي: {h['ai']['provider']} / {h['ai']['model']}")
        print(f"{ok(h['telegram_configured'])} تيليجرام")
        print(f"{ok(h['search_configured'])} البحث")
        print(f"{ok(h['email_configured'])} البريد")
        print(f"{ok(bool(app.db.one('SELECT 1 FROM users')))} المستخدمون")
        if h["ai"]["configured"] and s.ai_provider != "fake":
            try:
                r = asyncio.run(app.ai.chat("أجب بكلمة واحدة.", [{"role": "user", "content": "قل: جاهز"}], []))
                print(f"✅ اختبار الاتصال بالذكاء الاصطناعي: {r.text.strip()[:40]}")
            except Exception as e:  # noqa: BLE001
                print(f"❌ اختبار الاتصال بالذكاء الاصطناعي فشل: {e}")
                return 2
        return 0

    if a.cmd in ("create-user", "reset-password"):
        from .security import create_user, hash_password
        name = a.arg or input("اسم المستخدم: ")
        pw = getpass.getpass("كلمة المرور (8+): ")
        if a.cmd == "create-user":
            role = "admin" if not app.db.one("SELECT 1 FROM users") else "user"
            create_user(app.db, name, pw, role=role)
            print(f"✅ أُنشئ المستخدم {name} ({role})")
        else:
            n = app.db.execute("UPDATE users SET password_hash=? WHERE username=?", (hash_password(pw), name.lower()))
            print("✅ تم" if n.rowcount else "❌ مستخدم غير موجود")
        return 0

    if a.cmd == "backup":
        from .scheduler import run_backup
        print(f"✅ {run_backup(app)}")
        return 0

    if a.cmd in ("pause", "resume"):
        app.set_paused(a.cmd == "pause")
        print("⏸️ أُوقف التنفيذ" if a.cmd == "pause" else "▶️ استؤنف التنفيذ")
        return 0

    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
