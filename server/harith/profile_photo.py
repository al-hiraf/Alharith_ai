"""Private, per-account JPEG avatar stored in existing settings; no public upload URL."""
import base64
import binascii


def validate_photo(value):
    if not isinstance(value, str):
        raise ValueError("الصورة غير صالحة")
    if not value:
        return ""
    prefix = "data:image/jpeg;base64,"
    if len(value) > 180000 or not value.startswith(prefix):
        raise ValueError("اختر صورة JPEG صغيرة (أقل من ١٣٠ كيلوبايت)")
    try:
        raw = base64.b64decode(value[len(prefix):], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("الصورة غير صالحة")
    if not raw.startswith(b"\xff\xd8\xff") or not raw.endswith(b"\xff\xd9"):
        raise ValueError("الصورة غير صالحة")
    return value
