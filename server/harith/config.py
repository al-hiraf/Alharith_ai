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
    # البريد (SMTP)
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""
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
        smtp_host=e("SMTP_HOST", ""),
        smtp_port=_int("SMTP_PORT", 587),
        smtp_user=e("SMTP_USER", ""),
        smtp_password=e("SMTP_PASSWORD", ""),
        smtp_from=e("SMTP_FROM", ""),
        daily_cost_limit_usd=_float("DAILY_COST_LIMIT_USD", 2.0),
        tool_timeout_s=_int("TOOL_TIMEOUT_S", 60),
        max_agent_steps=_int("MAX_AGENT_STEPS", 12),
        history_retention_days=_int("HISTORY_RETENTION_DAYS", 90),
        backup_keep=_int("BACKUP_KEEP", 14),
    )
    for k, v in overrides.items():
        setattr(s, k, v)
    s.ensure_dirs()
    return s
