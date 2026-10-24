import base64
import json

import httpx
import pytest

from harith.publish import publish_url


def fake_github():
    state = {"branch": False, "file": None, "puts": 0}

    def h(req: httpx.Request):
        p = req.url.path
        if p.endswith("/branches/server-url"):
            return httpx.Response(200 if state["branch"] else 404, json={})
        if p == "/repos/o/r":
            return httpx.Response(200, json={"default_branch": "main"})
        if p.endswith("/git/ref/heads/main"):
            return httpx.Response(200, json={"object": {"sha": "abc"}})
        if p.endswith("/git/refs"):
            state["branch"] = True
            return httpx.Response(201, json={})
        if p.endswith("/contents/rafiq-url.txt") and req.method == "GET":
            if state["file"] is None:
                return httpx.Response(404, json={})
            return httpx.Response(200, json={"sha": "s1", "content": base64.b64encode(state["file"]).decode()})
        if p.endswith("/contents/rafiq-url.txt") and req.method == "PUT":
            b = json.loads(req.content)
            state["file"] = base64.b64decode(b["content"])
            state["puts"] += 1
            return httpx.Response(201, json={})
        return httpx.Response(404)
    return state, httpx.Client(base_url="https://api.github.com", transport=httpx.MockTransport(h))


def test_publish_creates_branch_and_file_and_skips_unchanged():
    st, c = fake_github()
    assert publish_url("https://abc-def.trycloudflare.com", "t", "o/r", client=c) == "نُشر"
    assert st["branch"] and st["file"] == b"https://abc-def.trycloudflare.com\n"
    assert publish_url("https://abc-def.trycloudflare.com/", "t", "o/r", client=c) == "بلا تغيير"
    assert publish_url("https://new-one.trycloudflare.com", "t", "o/r", client=c) == "نُشر"
    assert st["puts"] == 2


def test_publish_rejects_bad_urls():
    with pytest.raises(ValueError):
        publish_url("http://insecure.example.com", "t", "o/r")
    with pytest.raises(ValueError):
        publish_url("https://x.trycloudflare.com", "", "o/r")
