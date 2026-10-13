"""ربط تطبيق الجوال برمز من 6 أرقام."""
from starlette.testclient import TestClient

from harith.security import new_pair_code
from harith.web import create_app


def _c(app):
    return TestClient(create_app(app, manage_lifecycle=False))


def test_pair_code_gives_working_token_once(app, user):
    code = new_pair_code(app.db, user["id"])
    c = _c(app)
    r = c.post("/api/public/pair", json={"code": code[:3] + " " + code[3:], "device_name": "جوال مهند"})
    assert r.status_code == 200, r.text
    tok = r.json()["token"]
    assert tok.startswith("hrt_")
    me = c.get("/api/me", headers={"Authorization": f"Bearer {tok}"})
    assert me.status_code == 200 and me.json()["user"]["username"] == "mohand"
    # مرة واحدة فقط
    assert c.post("/api/public/pair", json={"code": code}).status_code == 403


def test_wrong_and_expired_codes_rejected_and_rate_limited(app, user):
    c = _c(app)
    assert c.post("/api/public/pair", json={"code": "12"}).status_code == 403
    codes = [c.post("/api/public/pair", json={"code": "000000"}).status_code for _ in range(10)]
    assert 429 in codes
    app.db.execute("UPDATE pair_codes SET expires_at='2000-01-01T00:00:00Z'")


def test_expired_code(app, user):
    code = new_pair_code(app.db, user["id"])
    app.db.execute("UPDATE pair_codes SET expires_at='2000-01-01T00:00:00Z'")
    assert _c(app).post("/api/public/pair", json={"code": code}).status_code == 403


def test_dashboard_creates_pair_code(app, user):
    c = _c(app)
    c.post("/api/login", json={"username": "mohand", "password": "password123"})
    r = c.post("/api/pair-code", json={}, headers={"X-Harith": "1"}).json()
    assert len(r["code"]) == 6 and r["code"].isdigit()


def test_cli_pair_creates_admin_when_missing(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("HARITH_DATA_DIR", str(tmp_path / "d"))
    from harith.__main__ import main
    env = tmp_path / "x.env"
    env.write_text(f"HARITH_DATA_DIR={tmp_path / 'd'}\nAI_PROVIDER=fake\n")
    assert main(["pair", "--env", str(env)]) == 0
    out = capsys.readouterr().out
    assert "رمز الربط" in out and "admin" in out


def test_cli_token_prints_working_token_and_url(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("HARITH_DATA_DIR", str(tmp_path / "d"))
    from harith.__main__ import main
    from harith.config import load_settings
    from harith.db import DB
    from harith.security import user_for_api_token
    env = tmp_path / "x.env"
    env.write_text(f"HARITH_DATA_DIR={tmp_path / 'd'}\nAI_PROVIDER=fake\n")
    main(["publish-url", "https://abc-def.trycloudflare.com", "--env", str(env)])
    capsys.readouterr()
    assert main(["token", "Instinct", "--env", str(env)]) == 0
    out = capsys.readouterr().out
    tok = next(line for line in out.splitlines() if line.startswith("hrt_"))
    assert "https://abc-def.trycloudflare.com" in out
    db = DB(load_settings(str(env)).db_path)
    assert user_for_api_token(db, tok)
    assert db.one("SELECT name FROM api_tokens")["name"] == "Instinct"


def test_server_info_and_url_change_notice(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("HARITH_DATA_DIR", str(tmp_path / "d"))
    from harith.__main__ import main
    from harith.config import load_settings
    from harith.db import DB
    from harith.security import create_user
    env = tmp_path / "x.env"
    env.write_text(f"HARITH_DATA_DIR={tmp_path / 'd'}\nAI_PROVIDER=fake\n")
    s = load_settings(str(env))
    s.data_dir.mkdir(parents=True, exist_ok=True)
    db = DB(s.db_path)
    create_user(db, "boss", "password123", role="admin")
    db.close()
    main(["publish-url", "https://one-a.trycloudflare.com", "--env", str(env)])
    main(["publish-url", "https://two-b.trycloudflare.com", "--env", str(env)])
    db = DB(s.db_path)
    jobs = db.all("SELECT payload FROM jobs WHERE kind='notify'")
    assert len(jobs) == 1 and "two-b" in jobs[0]["payload"]


def test_server_info_endpoint(app, user):
    c = _c(app)
    c.post("/api/login", json={"username": "mohand", "password": "password123"})
    (app.s.data_dir / "tunnel-url.txt").write_text("https://x-y.trycloudflare.com")
    r = c.get("/api/server-info").json()
    assert r["public_url"] == "https://x-y.trycloudflare.com" and r["version"] and "tokens" in r
