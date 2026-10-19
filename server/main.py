"""تشغيل رفيق على استضافات اللوحات الجاهزة (مثل Botkeep وما يشبهها): أمر التشغيل `python main.py`.

- يستمع على المنفذ الذي تعطيه الاستضافة (PORT أو SERVER_PORT) ومن كل العناوين.
- البيانات داخل مجلد المشروع (data/) لأن هذه الاستضافات لا تسمح بالكتابة خارجه.
- أول تشغيل بلا مستخدمين: يولّد رمز إعداد سريًّا ويطبع رابط الإعداد في سجل التشغيل (Console)،
  فلا يستطيع أحد غيرك إنشاء حساب المدير قبلك.
"""
import os
import secrets
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.environ.setdefault("HARITH_HOME", str(HERE))
sys.path.insert(0, str(HERE))

# بعض الاستضافات لا تثبّت requirements.txt تلقائيًا: نثبّتها مرة واحدة داخل مجلد المشروع
LIBS = HERE / ".pylibs"
sys.path.insert(0, str(LIBS))


def _deps_ok() -> bool:
    import importlib
    try:
        for m in ("starlette", "uvicorn", "httpx", "pypdf"):
            importlib.import_module(m)
        return True
    except ImportError:
        return False


if not _deps_ok():
    import subprocess
    print("أول تشغيل: تثبيت المكتبات المطلوبة (دقيقة تقريبًا)…", flush=True)
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--no-cache-dir", "--disable-pip-version-check",
                        "--target", str(LIBS), "-r", str(HERE / "requirements.txt")])
    if r.returncode != 0:
        print("✗ فشل تثبيت المكتبات. تأكد أن الخادم متصل بالإنترنت ثم أعد التشغيل.", flush=True)
        sys.exit(1)
    import importlib
    importlib.invalidate_caches()
    if not _deps_ok():
        print("✗ المكتبات لم تُحمَّل بعد التثبيت. أعد التشغيل.", flush=True)
        sys.exit(1)
    print("✓ ثُبّتت المكتبات.", flush=True)


def _first(*names: str) -> str:
    for n in names:
        v = os.environ.get(n, "").strip()
        if v.isdigit():
            return v
    return ""


port = _first("HARITH_PORT", "PORT", "SERVER_PORT", "APP_PORT")
if port:
    os.environ["HARITH_PORT"] = port
os.environ.setdefault("HARITH_HOST", "0.0.0.0")
os.environ.setdefault("BACKUP_KEEP", "5")  # المساحة محدودة (١ جيجا في الباقات المجانية)

from harith.config import load_settings  # noqa: E402

s = load_settings()
s.ensure_dirs()

if not os.environ.get("RAFIQ_SETUP_TOKEN", "").strip():
    tok_file = s.data_dir / ".setup_token"
    if not tok_file.exists():
        tok_file.write_text(secrets.token_urlsafe(18))
    os.environ["RAFIQ_SETUP_TOKEN"] = tok_file.read_text().strip()

base = os.environ.get("RAFIQ_PUBLIC_URL", "").rstrip("/") or f"http://<رابطك>:{s.port}"
print("════════════════════════════════════════", flush=True)
print(f"رابط الإعداد أول مرة (لا تشاركه): {base}/?setup={os.environ['RAFIQ_SETUP_TOKEN']}", flush=True)
print(f"صفحة التحميل للناس: {base}/download", flush=True)
print("════════════════════════════════════════", flush=True)

from harith.__main__ import main  # noqa: E402

sys.exit(main(["run"]))
