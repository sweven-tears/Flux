# -*- coding: utf-8 -*-
"""OpenAI 兼容 Mock 服务：GET /v1/models + POST /v1/chat/completions(SSE)"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._send_json(200, {
                "object": "list",
                "data": [
                    {"id": "mock-mini", "object": "model", "owned_by": "mock"},
                    {"id": "mock-pro", "object": "model", "owned_by": "mock"},
                ],
            })
        else:
            self._send_json(200, {"name": "mock-openai-server"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            req = json.loads(raw)
        except Exception:
            self._send_json(400, {"error": {"message": "bad json"}})
            return
        stream = bool(req.get("stream"))
        print("[mock] body:", raw, flush=True)
        if not stream:
            self._send_json(200, {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "model": req.get("model"),
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "你好！这是 Mock 模型的非流式回复。"},
                    "finish_reason": "stop",
                }],
            })
            return
        # 流式 SSE：先输出思考（reasoning_content），再输出正文
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for piece in ["用户发来了", "一条消息。", "我需要", "生成一段", "流式回复，",
                      "顺便验证", "思考过程的", "折叠展示。"]:
            payload = {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "model": req.get("model"),
                "choices": [{"index": 0, "delta": {"reasoning_content": piece},
                             "finish_reason": None}],
            }
            self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.12)
        chunks = ["你好", "！", "这是", "来自", "Mock", "厂商", "的", "流式", "回复", "。",
                  "\n\n**支持 Markdown**，还能兼容 <b>HTML 加粗</b> 与 <h3>HTML 标题</h3>。",
                  "\n- 列表项一\n- 列表项二\n\n[点我打开示例](https://example.com)"]
        for i, piece in enumerate(chunks):
            payload = {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "model": req.get("model"),
                "choices": [{"index": 0, "delta": {"content": piece},
                             "finish_reason": None}],
            }
            self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.15)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, fmt, *args):
        print("[mock]", fmt % args, flush=True)


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 9999), Handler)
    print("mock server on 127.0.0.1:9999", flush=True)
    server.serve_forever()
