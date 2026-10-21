#!/usr/bin/env python3
"""
模拟 fnOS 网关：把 /app/tvboxhub/* 反代到 hub 的 TCP 端口。
用来在真实浏览器里复现「网关前缀」场景。

    python3 gw_proxy.py <listen_port> <upstream_port> <prefix>
"""
import sys
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
UPSTREAM = int(sys.argv[2]) if len(sys.argv) > 2 else 8098
PREFIX = sys.argv[3] if len(sys.argv) > 3 else "/app/tvboxhub"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _proxy(self, method):
        path = self.path
        # 去掉网关前缀，模拟 fnOS 转发到 socket 后的样子
        if path.startswith(PREFIX):
            path = path[len(PREFIX):] or "/"
        url = f"http://127.0.0.1:{UPSTREAM}{path}"
        body = None
        if method == "POST":
            n = int(self.headers.get("content-length") or 0)
            body = self.rfile.read(n) if n else b""
        req = urllib.request.Request(url, data=body, method=method)
        for h in ("content-type", "accept"):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
                self.send_response(r.status)
                self.send_header("Content-Type", r.headers.get("content-type", "application/json"))
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        except urllib.error.HTTPError as e:
            data = e.read()
            self.send_response(e.code)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            data = str(e).encode()
            self.send_response(502)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")


if __name__ == "__main__":
    print(f"网关模拟: http://127.0.0.1:{LISTEN}{PREFIX}/  →  :{UPSTREAM}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", LISTEN), H).serve_forever()
