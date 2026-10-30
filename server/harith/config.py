"""الإعدادات: تُقرأ من متغيرات البيئة أو ملف .env (لا أسرار داخل الشيفرة)."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if v[:1] in ('"', "'") and v[0] in v[1:]:
            v = v[1:v.index(v[0], 1)]
        else:
            v = "" if v.startswith("#") else re.split(r"\s+#", v, maxsplit=1)[0].strip()
        os.environ.setdefault(k, v)


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass
class Settings:
    data_dir: Path
    host: str = "127.0.0.1"
    port: int = 8787
    timezone: str = "Asia/Riyadh"
    admin_password: str = ""            # يُستخدم مرة واحدة لإنشاء حساب المدير
    secret_key: str = ""                # لتوقيع الجلسات (يولَّد تلقائيًا إن لم يُحدَّد)
    # مزودو الذكاء الاصطناعي
    ai_provider: str = "gemini"         # gemini | openai | anthropic | openrouter | custom | fake
    ai_model: str = ""
    ai_fallback_provider: str = ""
    ai_fallback_model: str = ""          # موديل الاحتياطي؛ مع openrouter يجب أن ينتهي بـ :free
    openrouter_strict_zdr: bool = True   # ZDR مغلق لكل طلب؛ 0 يعتمده على مستوى الحساب (OPENROUTER_STRICT_ZDR=0)
    gemini_api_key_backup: str = ""    # مفتاح Gemini احتياطي (مشروع مختلف)، لا يعمل بدون ai_proxy_key_failover
    ai_proxy_key_failover: bool = False   # AI_PROXY_KEY_FAILOVER=1
    ai_proxy_fallback: bool = False   # الاحتياطي في ai_proxy يحتاج موافقة صريحة (AI_PROXY_FALLBACK=1)
    gemini_api_key: str = ""
    openai_api_key: str = ""
    anthropic_api_key: str = ""
    openrouter_api_key: str = ""
    custom_base_url: str = ""
    custom_api_key: str = ""
    # البحث
    tavily_api_key: str = ""
    brave_api_key: str = ""
    # تيليجرام
    telegram_bot_token: str = ""
    telegram_api_base: str = "https://api.telegram.org"
    # الإشعارات الفورية (Firebase Cloud Messaging): رسائل إيقاظ بلا محتوى، والمحتوى يُجلب من الخادم بمفتاح المستخدم
    fcm_credentials_file: str = ""
    fcm_app_id: str = ""
    fcm_api_key: str = ""
    fcm_sender_id: str = ""
    fcm_api_base: str = "https://fcm.googleapis.com"
    fcm_token_uri: str = "https://oauth2.googleapis.com/token"
    # إشعارات متصفحات الويب (Web Push/VAPID): تشمل آيفون iOS 16.4+ بعد إضافة رفيق للشاشة الرئيسية
    vapid_private_key: str = ""
    vapid_public_key: str = ""
    vapid_subject: str = ""
    vapid_allowed_hosts: str = ""   # إضافة خدمات دفع موثوقة فقط (على مسؤولية المشغّل)؛ القائمة المدمجة تبقى دائمًا
    # توزيع رفيق على مستخدمين آخرين: مفتاح إدارة OpenRouter يُنشئ مفتاحًا فرعيًا محدودًا لكل جهاز
    openrouter_provisioning_key: str = ""
    openrouter_api_base: str = "https://openrouter.ai/api/v1"
    provision_limit_usd: float = 5.0
    provision_limit_reset: str = "monthly"
    provision_max_devices: int = 200
    provision_invite_code: str = ""
    provision_model: str = "google/gemini-2.5-flash"
    # البريد (SMTP)
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""
    # البريد الوارد (IMAP) ومراقبة الردود المقترحة — إضافة فقط؛ الافتراضي متوقف
    imap_host: str = ""
    imap_port: int = 993
    imap_user: str = ""
    imap_password: str = ""
    mailwatch_users: str = ""      # أرقام الحسابات مفصولة بفواصل (فارغ = المراقبة متوقفة)
    mailwatch_interval_min: int = 10
    mailwatch_lookback_hours: int = 48  # نافذة الفحص الزمنية — تشمل المقروء وغير المقروء
    mailwatch_ai_daily: int = 40   # حد طلبات فرز البريد بالذكاء لكل مستخدم يوميًا (يحمي الحصة المجانية)
    google_client_id: str = ""         # ربط بريد قوقل لكل مستخدم (OAuth) — فارغ = معطل
    google_client_secret: str = ""
    google_oauth_redirect: str = ""    # فارغ = يُشتق من عنوان الخادم العام
    token_enc_key: str = ""            # مفتاح Fernet لتشفير رموز قوقل المخزنة
    # حدود
    daily_cost_limit_usd: float = 2.0
    tool_timeout_s: int = 60
    max_agent_steps: int = 12
    history_retention_days: int = 90
    backup_keep: int = 14
    extra: dict = field(default_factory=dict)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "harith.db"

    @property
    def files_dir(self) -> Path:
        return self.data_dir / "files"

    @property
    def backups_dir(self) -> Path:
        return self.data_dir / "backups"

    def ensure_dirs(self) -> None:
        for d in (self.data_dir, self.files_dir, self.backups_dir):
            d.mkdir(parents=True, exist_ok=True)


def load_settings(env_file: str | None = None, **overrides) -> Settings:
    base = Path(os.environ.get("HARITH_HOME", Path(__file__).resolve().parent.parent))
    _load_dotenv(Path(env_file) if env_file else base / ".env")
    e = os.environ.get
    s = Settings(
        data_dir=Path(e("HARITH_DATA_DIR", str(base / "data"))),
        host=e("HARITH_HOST", "127.0.0.1"),
        port=_int("HARITH_PORT", 8787),
        timezone=e("HARITH_TIMEZONE", "Asia/Riyadh"),
        admin_password=e("HARITH_ADMIN_PASSWORD", ""),
        secret_key=e("HARITH_SECRET_KEY", ""),
        ai_provider=e("AI_PROVIDER", "gemini"),
        ai_model=e("AI_MODEL", ""),
        ai_fallback_provider=e("AI_FALLBACK_PROVIDER", ""),
        ai_fallback_model=e("AI_FALLBACK_MODEL", ""),
        openrouter_strict_zdr=e("OPENROUTER_STRICT_ZDR", "1").lower() in ("1", "true", "yes"),
        gemini_api_key_backup=e("GEMINI_API_KEY_BACKUP", ""),
        ai_proxy_key_failover=e("AI_PROXY_KEY_FAILOVER", "").lower() in ("1", "true", "yes"),
        ai_proxy_fallback=e("AI_PROXY_FALLBACK", "").lower() in ("1", "true", "yes"),
        gemini_api_key=e("GEMINI_API_KEY", ""),
        openai_api_key=e("OPENAI_API_KEY", ""),
        anthropic_api_key=e("ANTHROPIC_API_KEY", ""),
        openrouter_api_key=e("OPENROUTER_API_KEY", ""),
        custom_base_url=e("CUSTOM_BASE_URL", ""),
        custom_api_key=e("CUSTOM_API_KEY", ""),
        tavily_api_key=e("TAVILY_API_KEY", ""),
        brave_api_key=e("BRAVE_API_KEY", ""),
        telegram_bot_token=e("TELEGRAM_BOT_TOKEN", ""),
        telegram_api_base=e("TELEGRAM_API_BASE", "https://api.telegram.org").rstrip("/"),
        fcm_credentials_file=e("FCM_CREDENTIALS_FILE", ""),
        fcm_app_id=e("FCM_APP_ID", ""),
        fcm_api_key=e("FCM_API_KEY", ""),
        fcm_sender_id=e("FCM_SENDER_ID", ""),
        fcm_api_base=e("FCM_API_BASE", "https://fcm.googleapis.com").rstrip("/"),
        fcm_token_uri=e("FCM_TOKEN_URI", "https://oauth2.googleapis.com/token"),
        vapid_private_key=e("VAPID_PRIVATE_KEY", ""),
        vapid_public_key=e("VAPID_PUBLIC_KEY", ""),
        vapid_subject=e("VAPID_SUBJECT", ""),
        vapid_allowed_hosts=e("VAPID_ALLOWED_HOSTS", ""),
        openrouter_provisioning_key=e("OPENROUTER_PROVISIONING_KEY", ""),
        openrouter_api_base=e("OPENROUTER_API_BASE", "https://openrouter.ai/api/v1").rstrip("/"),
        provision_limit_usd=_float("PROVISION_LIMIT_USD", 5.0),
        provision_limit_reset=e("PROVISION_LIMIT_RESET", "monthly"),
        provision_max_devices=_int("PROVISION_MAX_DEVICES", 200),
        provision_invite_code=e("PROVISION_INVITE_CODE", ""),
        provision_model=e("PROVISION_MODEL", "google/gemini-2.5-flash"),
        smtp_host=e("SMTP_HOST", ""),
        smtp_port=_int("SMTP_PORT", 587),
        smtp_user=e("SMTP_USER", ""),
        smtp_password=e("SMTP_PASSWORD", ""),
        smtp_from=e("SMTP_FROM", ""),
        imap_host=e("IMAP_HOST", ""),
        imap_port=_int("IMAP_PORT", 993),
        imap_user=e("IMAP_USER", ""),
        imap_password=e("IMAP_PASSWORD", ""),
        mailwatch_users=e("MAILWATCH_USERS", ""),
        mailwatch_interval_min=_int("MAILWATCH_INTERVAL_MIN", 10),
        mailwatch_lookback_hours=_int("MAILWATCH_LOOKBACK_HOURS", 48),
        mailwatch_ai_daily=_int("MAILWATCH_AI_DAILY", 40),
        google_client_id=e("GOOGLE_CLIENT_ID", ""),
        google_client_secret=e("GOOGLE_CLIENT_SECRET", ""),
        google_oauth_redirect=e("GOOGLE_OAUTH_REDIRECT", ""),
        token_enc_key=e("TOKEN_ENC_KEY", ""),
        daily_cost_limit_usd=_float("DAILY_COST_LIMIT_USD", 2.0),
        tool_timeout_s=_int("TOOL_TIMEOUT_S", 60),
        max_agent_steps=_int("MAX_AGENT_STEPS", 12),
        history_retention_days=_int("HISTORY_RETENTION_DAYS", 90),
        backup_keep=_int("BACKUP_KEEP", 14),
    )
    for k, v in overrides.items():
        setattr(s, k, v)
    _auto_provider(s)
    s.ensure_dirs()
    return s


_PROVIDER_KEYS = {"openrouter": "openrouter_api_key", "gemini": "gemini_api_key",
                  "openai": "openai_api_key", "anthropic": "anthropic_api_key"}


def _auto_provider(s: Settings) -> None:
    """إن كان المزوّد المختار بلا مفتاح ويوجد مفتاح لمزوّد آخر، نستخدم الموجود بدل التوقف
    (مثال: AI_PROVIDER=gemini بلا مفتاح، و OPENROUTER_API_KEY مضاف ← نعمل عبر OpenRouter)."""
    cur = _PROVIDER_KEYS.get(s.ai_provider)
    if not cur or getattr(s, cur):
        return
    for name, attr in _PROVIDER_KEYS.items():
        if getattr(s, attr):
            s.ai_provider, s.ai_model = name, ""
            return
