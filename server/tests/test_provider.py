from harith.config import load_settings


def _clear(monkeypatch, tmp_path):
    monkeypatch.setenv("HARITH_DATA_DIR", str(tmp_path))
    for k in ("GEMINI_API_KEY", "OPENROUTER_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "AI_MODEL"):
        monkeypatch.setenv(k, "")


def test_switches_to_available_key(monkeypatch, tmp_path):
    _clear(monkeypatch, tmp_path)
    monkeypatch.setenv("AI_PROVIDER", "gemini")
    monkeypatch.setenv("AI_MODEL", "gemini-2.5-flash")
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-test")
    s = load_settings(env_file=str(tmp_path / "none.env"))
    assert s.ai_provider == "openrouter" and s.ai_model == ""


def test_keeps_configured_provider(monkeypatch, tmp_path):
    _clear(monkeypatch, tmp_path)
    monkeypatch.setenv("AI_PROVIDER", "gemini")
    monkeypatch.setenv("GEMINI_API_KEY", "AIza-x")
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-test")
    assert load_settings(env_file=str(tmp_path / "none.env")).ai_provider == "gemini"


def test_fake_untouched(monkeypatch, tmp_path):
    _clear(monkeypatch, tmp_path)
    monkeypatch.setenv("AI_PROVIDER", "fake")
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-test")
    assert load_settings(env_file=str(tmp_path / "none.env")).ai_provider == "fake"
