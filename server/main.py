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

LIBS = HERE / ".pylibs"
sys.path.insert(0, str(LIBS))


def _deps_ok() -> bool:
    import importlib
    importlib.invalidate_caches()
    try:
        for m in ("starlette", "uvicorn", "httpx", "pypdf"):
            importlib.import_module(m)
        return True
    except ImportError:
        return False


def _code_ok() -> bool:
    return (HERE / "harith" / "__init__.py").exists()


# إن رُفع الملف المضغوط دون فكّه، أو نقص منه شيء: نفكّه تلقائيًا
# (لوحات بعض الاستضافات لا تفك الضغط أو لا ترفع المجلدات)
if not _code_ok() or not _deps_ok():
    import zipfile
    for z in sorted(HERE.glob("*.zip")):
        try:
            with zipfile.ZipFile(z) as zf:
                names = zf.namelist()
                if not any(n.startswith("harith/") for n in names):
                    continue
                print(f"فك الملف المضغوط {z.name}…", flush=True)
                for n in names:
                    if n != "main.py":  # لا نستبدل الملف الذي يعمل الآن
                        zf.extract(n, HERE)
                print("✓ فُكّ الضغط.", flush=True)
                break
        except zipfile.BadZipFile:
            continue

if not _code_ok():
    print("✗ مجلد harith غير موجود: ارفع rafiq-server-full.zip بجانب main.py ثم أعد التشغيل.", flush=True)
    sys.exit(1)

# آخر حل: تثبيت المكتبات من الإنترنت (إن لم تكن مرفقة)
if not _deps_ok():
    req = HERE / "requirements.txt"
    if not req.exists():
        print("✗ المكتبات ناقصة: ارفع rafiq-server-full.zip بجانب main.py ثم أعد التشغيل.", flush=True)
        sys.exit(1)
    import subprocess
    print("تثبيت المكتبات المطلوبة (دقيقة تقريبًا)…", flush=True)
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--no-cache-dir", "--disable-pip-version-check",
                        "--target", str(LIBS), "-r", str(req)])
    if r.returncode != 0 or not _deps_ok():
        print("✗ فشل تثبيت المكتبات. ارفع rafiq-server-full.zip بجانب main.py ثم أعد التشغيل.", flush=True)
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
