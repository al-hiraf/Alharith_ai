"""نشر رابط الخادم الحالي (نفق Cloudflare السريع يتغير عند كل تشغيل) في ملف ثابت على GitHub
يقرأه تطبيق رفيق، فيصل المستخدمون للخادم دائمًا دون نطاق مدفوع."""
from __future__ import annotations

import base64
import re

import httpx

URL_RX = re.compile(r"^https://[a-z0-9.-]+(:\d+)?$")


def publish_url(url: str, token: str, repo: str, branch: str = "server-url", path: str = "rafiq-url.txt",
                client: httpx.Client | None = None) -> str:
    url = url.strip().rstrip("/")
    if not URL_RX.match(url):
        raise ValueError(f"رابط غير صالح: {url}")
    if not token or "/" not in repo:
        raise ValueError("أضف GITHUB_TOKEN و GITHUB_REPO في ملف .env")
    h = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    c = client or httpx.Client(base_url="https://api.github.com", timeout=20)
    api = f"/repos/{repo}"
    # الفرع: نُنشئه من الفرع الافتراضي إن لم يوجد
    r = c.get(f"{api}/branches/{branch}", headers=h)
    if r.status_code == 404:
        default = c.get(api, headers=h).json().get("default_branch", "main")
        sha = c.get(f"{api}/git/ref/heads/{default}", headers=h).json()["object"]["sha"]
        cr = c.post(f"{api}/git/refs", headers=h, json={"ref": f"refs/heads/{branch}", "sha": sha})
        if cr.status_code >= 300:
            raise RuntimeError(f"تعذّر إنشاء الفرع: {cr.status_code}")
    elif r.status_code >= 300:
        raise RuntimeError(f"GitHub {r.status_code}: تحقق من صلاحيات المفتاح")
    cur = c.get(f"{api}/contents/{path}", headers=h, params={"ref": branch})
    body = {"message": "rafiq server url", "branch": branch,
            "content": base64.b64encode((url + "\n").encode()).decode()}
    if cur.status_code == 200:
        old = base64.b64decode(cur.json().get("content", "")).decode().strip()
        if old == url:
            return "بلا تغيير"
        body["sha"] = cur.json()["sha"]
    put = c.put(f"{api}/contents/{path}", headers=h, json=body)
    if put.status_code >= 300:
        raise RuntimeError(f"تعذّر النشر: GitHub {put.status_code}")
    return "نُشر"
