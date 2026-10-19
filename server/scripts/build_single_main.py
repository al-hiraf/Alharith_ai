import base64, hashlib, io, sys, zipfile
from pathlib import Path
R = Path(sys.argv[1]); out = Path(sys.argv[2])
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p in sorted((R / "harith").rglob("*")):
        if p.is_file() and "__pycache__" not in p.parts:
            z.write(p, p.relative_to(R).as_posix())
    z.write(R / "requirements.txt", "requirements.txt")
raw = buf.getvalue()
sig = hashlib.sha1(raw).hexdigest()[:12]
b64 = base64.b64encode(raw).decode()
lines = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76))
src = (R / "main.py").read_text(encoding="utf-8")
head = f'''"""رفيق — ملف واحد يحمل الخادم كاملًا. ارفعه وحده ثم أعد التشغيل (أي تحديث = استبدال هذا الملف).
كل ما تحتاجه الاستضافة: أمر التشغيل `python main.py`."""
import base64 as _b64, io as _io, shutil as _sh, sys as _sys, zipfile as _zf
from pathlib import Path as _P

_HERE = _P(__file__).resolve().parent
_BUNDLE_SIG = "{sig}"
_STAMP = _HERE / ".bundle_stamp"
if not (_HERE / "harith" / "__init__.py").exists() or not _STAMP.exists() or _STAMP.read_text().strip() != _BUNDLE_SIG:
    print("تجهيز ملفات رفيق…", flush=True)
    if (_HERE / "harith").exists():
        _sh.rmtree(_HERE / "harith")  # نحذف الكود القديم كله (البيانات في data/ لا تُمس)
    with _zf.ZipFile(_io.BytesIO(_b64.b64decode(_BUNDLE))) as _z:
        _z.extractall(_HERE)
    _STAMP.write_text(_BUNDLE_SIG)
    print("✓ جاهز.", flush=True)

'''
# main.py الأصلي بعد إزالة منطق فك الملفات المضغوطة الخارجية
start = src.index("def _bundles():"); end = src.index("if not _code_ok():")
body = src[:start] + src[end:]
body = body.replace('ارفع rafiq-server-full.zip بجانب main.py ثم أعد التشغيل', 'أعد رفع main.py ثم أعد التشغيل')
out.write_text(head + body + f'\n\n_BUNDLE = """\n{lines}\n"""\n' if False else head.replace("_BUNDLE)))", "_BUNDLE)))") , encoding="utf-8")
# _BUNDLE must exist before use: put it first
final = f'_BUNDLE = """\n{lines}\n"""\n' + head + body
out.write_text(final, encoding="utf-8")
print("bundle", len(raw), "file", out.stat().st_size, "sig", sig)
