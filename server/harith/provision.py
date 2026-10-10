"""توزيع رفيق على مستخدمين آخرين بلا إعداد: لكل جهاز مفتاح OpenRouter فرعي بحد شهري.

- مفتاح الإدارة (OPENROUTER_PROVISIONING_KEY) يبقى في الخادم فقط ولا يُرسل لأي تطبيق.
- المفتاح الفرعي يُعرض مرة واحدة (OpenRouter لا يعيده لاحقًا) ويُرسل للجهاز عبر HTTPS ولا يُخزَّن هنا.
- إعادة تثبيت التطبيق على نفس الجهاز: يُلغى المفتاح القديم ويُنشأ جديد.
"""
from __future__ import annotations

import re
from typing import TYPE_CHECKING

import httpx

from .db import now_iso
from .security import RateLimiter, redact

if TYPE_CHECKING:
    from .core import Harith

DEVICE_RX = re.compile(r"^[A-Za-z0-9_\-]{16,80}$")


class ProvisionError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class Provisioner:
    def __init__(self, app: "Harith"):
        self.app = app
        self.s = app.s
        self.ip_limiter = RateLimiter(5, 3600)        # 5 تسجيلات في الساعة لكل عنوان IP
        self.global_limiter = RateLimiter(60, 3600)   # حماية إضافية من الإغراق

    @property
    def enabled(self) -> bool:
        return bool(self.s.openrouter_provisioning_key)

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.s.openrouter_provisioning_key}", "Content-Type": "application/json"}

    async def _or(self, method: str, path: str, json: dict | None = None) -> dict:
        r = await self.app.ai.http.request(method, f"{self.s.openrouter_api_base}{path}", headers=self._headers(),
                                           json=json, timeout=30)
        if r.status_code >= 400:
            msg = r.text[:300]
            self.app.db.log_event("error", "openrouter-keys", f"{method} {path} → {r.status_code}: {redact(msg)}")
            raise ProvisionError(f"OpenRouter رفض الطلب ({r.status_code})", 502)
        return r.json() if r.content else {}

    # ——— التسجيل من التطبيق
    async def register(self, device_id: str, name: str, ip: str, invite: str = "", app_version: str = "") -> dict:
        if not self.enabled:
            raise ProvisionError("التسجيل التلقائي غير مفعّل في هذا الخادم", 503)
        if not DEVICE_RX.match(device_id or ""):
            raise ProvisionError("معرّف جهاز غير صالح")
        if self.s.provision_invite_code and invite.strip() != self.s.provision_invite_code:
            raise ProvisionError("رمز الدعوة غير صحيح", 403)
        if not self.ip_limiter.allow(ip) or not self.global_limiter.allow("all"):
            raise ProvisionError("محاولات كثيرة، حاول لاحقًا", 429)
        db = self.app.db
        existing = db.one("SELECT * FROM devices WHERE device_id=?", (device_id,))
        if existing and existing["disabled"]:
            raise ProvisionError("هذا الجهاز موقوف من المدير", 403)
        if not existing:
            count = db.one("SELECT COUNT(*) c FROM devices")["c"]
            if count >= self.s.provision_max_devices:
                raise ProvisionError("اكتمل عدد المستخدمين المسموح حاليًا", 403)
        # مفتاح قديم لنفس الجهاز (إعادة تثبيت): نلغيه أولًا
        if existing and existing["key_hash"]:
            try:
                await self._or("DELETE", f"/keys/{existing['key_hash']}")
            except ProvisionError:
                pass
        short = device_id[-6:]
        label_name = f"rafiq-{(name or 'user')[:24]}-{short}"
        body = {"name": label_name, "limit": self.s.provision_limit_usd, "limit_reset": self.s.provision_limit_reset,
                "include_byok_in_limit": True}
        res = await self._or("POST", "/keys", body)
        data = res.get("data") or {}
        key = res.get("key") or data.get("key")
        khash = data.get("hash") or res.get("hash")
        if not key or not khash:
            raise ProvisionError("لم يُرجع OpenRouter مفتاحًا", 502)
        now = now_iso()
        if existing:
            db.execute("UPDATE devices SET name=?, key_hash=?, key_label=?, limit_usd=?, ip=?, app_version=?, last_seen=? "
                       "WHERE id=?", (name[:60], khash, data.get("label", ""), self.s.provision_limit_usd, ip,
                                      app_version[:20], now, existing["id"]))
        else:
            db.execute("INSERT INTO devices(device_id,name,key_hash,key_label,limit_usd,ip,app_version,created_at,last_seen) "
                       "VALUES(?,?,?,?,?,?,?,?,?)", (device_id, name[:60], khash, data.get("label", ""),
                                                     self.s.provision_limit_usd, ip, app_version[:20], now, now))
        db.log_event("info", "provision", f"مفتاح جديد لجهاز {short} ({name[:24]})")
        return {"key": key, "provider": "openrouter", "model": self.s.provision_model,
                "limit_usd": self.s.provision_limit_usd, "limit_reset": self.s.provision_limit_reset}

    # ——— الإدارة
    async def list_devices(self) -> list[dict]:
        rows = self.app.db.all("SELECT * FROM devices ORDER BY last_seen DESC")
        usage: dict[str, dict] = {}
        if self.enabled and rows:
            try:
                offset = 0
                while True:
                    page = (await self._or("GET", f"/keys?offset={offset}")).get("data") or []
                    for k in page:
                        usage[k.get("hash")] = k
                    if len(page) < 100:
                        break
                    offset += 100
            except ProvisionError:
                pass
        out = []
        for r in rows:
            k = usage.get(r["key_hash"] or "", {})
            out.append({
                "id": r["id"], "name": r["name"], "device": r["device_id"][-6:], "created_at": r["created_at"],
                "last_seen": r["last_seen"], "app_version": r["app_version"], "disabled": bool(r["disabled"] or k.get("disabled")),
                "limit": k.get("limit", r["limit_usd"]), "limit_remaining": k.get("limit_remaining"),
                "usage_monthly": k.get("usage_monthly", k.get("usage")), "usage_total": k.get("usage"),
                "limit_reset": k.get("limit_reset"), "synced": bool(k),
            })
        return out

    async def set_disabled(self, device_pk: int, disabled: bool) -> None:
        d = self._device(device_pk)
        if d["key_hash"]:
            await self._or("PATCH", f"/keys/{d['key_hash']}", {"disabled": disabled})
        self.app.db.execute("UPDATE devices SET disabled=? WHERE id=?", (1 if disabled else 0, device_pk))

    async def set_limit(self, device_pk: int, limit: float) -> None:
        if limit < 0 or limit > 1000:
            raise ProvisionError("حد غير منطقي")
        d = self._device(device_pk)
        if d["key_hash"]:
            await self._or("PATCH", f"/keys/{d['key_hash']}", {"limit": limit})
        self.app.db.execute("UPDATE devices SET limit_usd=? WHERE id=?", (limit, device_pk))

    async def revoke(self, device_pk: int) -> None:
        d = self._device(device_pk)
        if d["key_hash"]:
            try:
                await self._or("DELETE", f"/keys/{d['key_hash']}")
            except ProvisionError:
                pass
        self.app.db.execute("DELETE FROM devices WHERE id=?", (device_pk,))

    def touch(self, device_id: str) -> None:
        self.app.db.execute("UPDATE devices SET last_seen=? WHERE device_id=?", (now_iso(), device_id))

    def _device(self, pk: int) -> dict:
        d = self.app.db.one("SELECT * FROM devices WHERE id=?", (pk,))
        if not d:
            raise ProvisionError("الجهاز غير موجود", 404)
        return d


__all__ = ["Provisioner", "ProvisionError", "httpx"]
