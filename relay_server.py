# -*- coding: utf-8 -*-
"""把本地 HTTP 请求中继到指定 HTTPS 上游（宿主机侧解析 DNS/TLS），支持 SSE 流式透传。"""
import http.client
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM_HOST = "tokenrhythm.studio"
HOP_HEADERS = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host",
}


class RelayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.relay("GET")

    def do_POST(self):
        self.relay("POST")

    def relay(self, method):
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length) if length else None
        headers = {
            k: v for k, v in self.headers.items()
            if k.lower() not in HOP_HEADERS
        }
        try:
            conn = http.client.HTTPSConnection(UPSTREAM_HOST, timeout=60)
            conn.request(method, self.path, body=body, headers=headers)
            resp = conn.getresponse()
        except Exception as e:
            payload = f'{{"error":{{"message":"relay upstream error: {e}"}}}}'.encode()
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() in HOP_HEADERS or k.lower() == "content-length":
                continue
            self.send_header(k, v)
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        try:
            while True:
                chunk = resp.read1(2048)
                if not chunk:
                    break
                self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass
        finally:
            conn.close()

    def log_message(self, fmt, *args):
        print("[relay]", fmt % args, flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9998
    server = ThreadingHTTPServer(("127.0.0.1", port), RelayHandler)
    print(f"relay 127.0.0.1:{port} -> https://{UPSTREAM_HOST}", flush=True)
    server.serve_forever()
