"""OpenRouter وهمي لاختبار توزيع المفاتيح على المحاكي (لا يتصل بالإنترنت)."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

KEYS = {}


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_POST(self):
        n = len(KEYS) + 1
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        h = f"h{n}"
        KEYS[h] = {"hash": h, "name": body.get("name"), "label": "sk-or-v1-x", "limit": body.get("limit"),
                   "limit_remaining": body.get("limit"), "usage": 0, "usage_monthly": 0, "disabled": False}
        self._send(201, {"data": KEYS[h], "key": "sk-or-v1-" + "f" * 64})

    def do_GET(self):
        self._send(200, {"data": list(KEYS.values())})

    def do_DELETE(self):
        KEYS.pop(self.path.rsplit("/", 1)[-1], None); self._send(200, {"deleted": True})

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", 8799), H).serve_forever()
