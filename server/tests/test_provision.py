"""توزيع رفيق: مفتاح فرعي لكل جهاز من مفتاح الإدارة، بلا إعداد من المستخدم."""
import json

import httpx
from starlette.testclient import TestClient

from harith.web import create_app


class FakeOpenRouter:
    def __init__(self):
        self.keys = {}
        self.calls = []
        self.n = 0

    def handler(self, req: httpx.Request) -> httpx.Response:
        assert req.headers["authorization"] == "Bearer prov-secret"
        self.calls.append((req.method, req.url.path))
        path = req.url.path.replace("/api/v1", "")
        if req.method == "POST" and path == "/keys":
            b = json.loads(req.content)
            self.n += 1
            h = f"hash{self.n}"
            self.keys[h] = {"hash": h, "name": b["name"], "label": f"sk-or-v1-ab...{self.n}", "disabled": False,
                            "limit": b.get("limit"), "limit_remaining": b.get("limit"), "limit_reset": b.get("limit_reset"),
                            "usage": 0.25 * self.n, "usage_monthly": 0.25 * self.n}
            return httpx.Response(201, json={"data": self.keys[h], "key": f"sk-or-v1-{'a' * 60}{self.n:04d}"})
        if req.method == "GET" and path == "/keys":
            return httpx.Response(200, json={"data": list(self.keys.values())})
        h = path.split("/")[-1]
        if req.method == "PATCH":
            self.keys[h].update(json.loads(req.content))
            return httpx.Response(200, json={"data": self.keys[h]})
        if req.method == "DELETE":
            self.keys.pop(h, None)
            return httpx.Response(200, json={"deleted": True})
        return httpx.Response(404)


def setup(app, **kw):
    fake = FakeOpenRouter()
    app.s.openrouter_provisioning_key = "prov-secret"
    for k, v in kw.items():
        setattr(app.s, k, v)
    app.ai.http = httpx.AsyncClient(transport=httpx.MockTransport(fake.handler))
    return fake, TestClient(create_app(app, manage_lifecycle=False))


def admin_client(app):
    c = TestClient(create_app(app, manage_lifecycle=False))
    c.post("/api/login", json={"username": "mohand", "password": "password123"})
    c.headers["X-Harith"] = "1"
    return c


DEV = "dev_0123456789abcdef"


def test_register_gives_limited_key_without_setup(app):
    fake, c = setup(app, provision_limit_usd=5.0)
    assert c.get("/api/public/info").json()["provisioning"] is True
    r = c.post("/api/public/register", json={"device_id": DEV, "name": "أحمد", "app_version": "2.2.0"}).json()
    assert r["key"].startswith("sk-or-v1-") and r["provider"] == "openrouter" and r["limit_usd"] == 5.0
    k = next(iter(fake.keys.values()))
    assert k["limit"] == 5.0 and k["limit_reset"] == "monthly"
    # مفتاح الإدارة والمفتاح الفرعي لا يُخزَّنان في قاعدة البيانات
    dump = json.dumps(app.db.all("SELECT * FROM devices"), ensure_ascii=False)
    assert "prov-secret" not in dump and r["key"] not in dump


def test_reinstall_revokes_old_key(app):
    fake, c = setup(app)
    c.post("/api/public/register", json={"device_id": DEV, "name": "أحمد"})
    c.post("/api/public/register", json={"device_id": DEV, "name": "أحمد"})
    assert list(fake.keys) == ["hash2"]
    assert app.db.one("SELECT COUNT(*) c FROM devices")["c"] == 1


def test_disabled_when_no_management_key(app):
    c = TestClient(create_app(app, manage_lifecycle=False))
    assert c.get("/api/public/info").json()["provisioning"] is False
    assert c.post("/api/public/register", json={"device_id": DEV}).status_code == 503


def test_invite_code_and_limits(app):
    fake, c = setup(app, provision_invite_code="RAFIQ2026", provision_max_devices=1)
    assert c.post("/api/public/register", json={"device_id": DEV, "invite": "x"}).status_code == 403
    assert c.post("/api/public/register", json={"device_id": "bad id"}).status_code == 400
    assert c.post("/api/public/register", json={"device_id": DEV, "invite": "RAFIQ2026"}).status_code == 200
    r = c.post("/api/public/register", json={"device_id": "dev_ffffffffffffffff", "invite": "RAFIQ2026"})
    assert r.status_code == 403 and "اكتمل" in r.json()["error"]


def test_register_rate_limited_per_ip(app):
    fake, c = setup(app)
    codes = [c.post("/api/public/register", json={"device_id": f"dev_{i:016d}"}).status_code for i in range(7)]
    assert codes[:5] == [200] * 5 and codes[-1] == 429


def test_admin_manage_devices(app):
    fake, c = setup(app)
    c.post("/api/public/register", json={"device_id": DEV, "name": "أحمد"})
    a = admin_client(app)
    a.app.state.harith.ai.http = app.ai.http
    d = a.get("/api/devices").json()
    dev = d["devices"][0]
    assert dev["name"] == "أحمد" and dev["usage_monthly"] == 0.25 and dev["synced"]
    assert a.patch(f"/api/devices/{dev['id']}", json={"limit": 10}).json()["ok"]
    assert fake.keys["hash1"]["limit"] == 10
    assert a.patch(f"/api/devices/{dev['id']}", json={"disabled": True}).json()["ok"]
    assert fake.keys["hash1"]["disabled"] is True
    # جهاز موقوف لا يستطيع أخذ مفتاح جديد بإعادة التثبيت
    assert c.post("/api/public/register", json={"device_id": DEV}).status_code == 403
    assert a.delete(f"/api/devices/{dev['id']}").json()["ok"]
    assert fake.keys == {} and a.get("/api/devices").json()["devices"] == []
    # غير المدير لا يرى الأجهزة
    assert TestClient(create_app(app, manage_lifecycle=False)).get("/api/devices").status_code == 401
