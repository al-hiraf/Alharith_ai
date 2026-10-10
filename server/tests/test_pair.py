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


def test_cli_pair_creates_admin_when_missing(tmp_path, capsys):
    from harith.__main__ import main
    env = tmp_path / "x.env"
    env.write_text(f"HARITH_DATA_DIR={tmp_path / 'd'}\nAI_PROVIDER=fake\n")
    assert main(["pair", "--env", str(env)]) == 0
    out = capsys.readouterr().out
    assert "رمز الربط" in out and "admin" in out
