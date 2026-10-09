import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from harith.config import load_settings  # noqa: E402
from harith.core import Harith  # noqa: E402
from harith.security import create_user  # noqa: E402


@pytest.fixture
def settings(tmp_path):
    return load_settings(env_file=str(tmp_path / "none.env"), data_dir=tmp_path / "data", ai_provider="fake",
                         admin_password="", telegram_bot_token="", gemini_api_key="", openai_api_key="",
                         anthropic_api_key="", openrouter_api_key="", tavily_api_key="", brave_api_key="",
                         smtp_host="", daily_cost_limit_usd=2.0)


@pytest.fixture
def app(settings):
    a = Harith(settings)
    create_user(a.db, "mohand", "password123", role="admin", display_name="مهند")
    yield a
    try:
        a.db.close()
    except Exception:
        pass


@pytest.fixture
def user(app):
    return app.db.one("SELECT * FROM users WHERE username='mohand'")
