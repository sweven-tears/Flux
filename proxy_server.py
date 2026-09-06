# -*- coding: utf-8 -*-
"""极简 HTTP/HTTPS 正向代理：供模拟器 -http-proxy 使用，DNS 在宿主机侧解析。"""
import select
import socket
import threading
from urllib.parse import urlsplit


def pipe(a: socket.socket, b: socket.socket):
    try:
        pair = [a, b]
        while True:
            r, _, x = select.select(pair, [], pair, 120)
            if x or not r:
                break
            for s in r:
                data = s.recv(65536)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    except OSError:
        pass
    finally:
        try:
            a.close()
        except OSError:
            pass
        try:
            b.close()
        except OSError:
            pass


def handle(client: socket.socket):
    client.settimeout(60)
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = client.recv(8192)
        if not chunk:
            client.close()
            return
        data += chunk
    head, rest = data.split(b"\r\n\r\n", 1)
    lines = head.decode("latin1").split("\r\n")
    method, target, _ver = lines[0].split(" ", 2)

    if method == "CONNECT":
        host, port = target.rsplit(":", 1)
        try:
            upstream = socket.create_connection((host, int(port)), timeout=20)
        except OSError as e:
            client.sendall(f"HTTP/1.1 502 {e}\r\n\r\n".encode())
            client.close()
            return
        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        pipe(client, upstream)
        return

    # 普通请求：绝对 URI 改写为源站形式
    u = urlsplit(target)
    host, port = u.hostname, u.port or 80
    path = (u.path or "/") + (("?" + u.query) if u.query else "")
    headers = [
        l for l in lines[1:]
        if not l.lower().startswith(("proxy-connection", "proxy-authorization"))
    ]
    host_hdr = host if u.port is None else f"{host}:{port}"
    req = (
        f"{method} {path} HTTP/1.1\r\n"
        + "\r\n".join(headers)
        + f"\r\nHost: {host_hdr}\r\n\r\n"
    )
    try:
        upstream = socket.create_connection((host, port), timeout=20)
    except OSError as e:
        client.sendall(f"HTTP/1.1 502 {e}\r\n\r\n".encode())
        client.close()
        return
    upstream.sendall(req.encode("latin1") + rest)
    pipe(client, upstream)


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 18080))
    srv.listen(64)
    print("forward proxy on 127.0.0.1:18080", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
