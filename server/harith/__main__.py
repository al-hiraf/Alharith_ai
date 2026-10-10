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

    from .config import load_settings
    s = load_settings(a.env)

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
