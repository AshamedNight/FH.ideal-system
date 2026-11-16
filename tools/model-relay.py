#!/usr/bin/env python3
"""模型中转：手机不挂代理，也能用需要翻墙的模型。

原理
----
    手机 (App)  --http://192.168.x.x:8787-->  这台电脑  --https-->  模型 API
                                              （电脑自己翻墙）

手机只访问局域网地址，出口 IP 仍然是家里的宽带 —— 目标平台看不到境外 IP。
这是"模型必须挂 VPN"与"封号风险最低"之间唯一自洽的解法：
**不要在手机上翻墙，把翻墙这件事留在电脑上。**

用法
----
    # 电脑上（Clash 保持开启即可，脚本会自动读系统代理）
    python tools/model-relay.py

    # 之后手机设置里填：
    #   接口地址  http://<脚本打印的那个内网 IP>:8787/v1
    #   API Key   你的真实 key（脚本原样转发，不落盘不打印）
    #   模型名    deepseek-chat

只用标准库，不需要 pip install。
"""

import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://api.deepseek.com/v1"
OPENER = None  # 在 main() 里初始化


def lan_ip() -> str:
    """取本机在局域网里的 IP。连 8.8.8.8 只为让内核选出口网卡，不发包。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # 默认实现会往 stderr 打日志，格式难看且带时间戳噪音
    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, body: bytes, ctype: str = "application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # 自检用：手机浏览器打开 http://<ip>:8787/health 能看到 ok，就说明通
        if self.path.rstrip("/") in ("/health", "/"):
            self._send(200, b'{"ok":true,"upstream":"' + UPSTREAM.encode() + b'"}')
        else:
            self._send(404, b'{"error":"not found"}')

    def do_POST(self):
        started = time.time()

        # App 里的接口地址是 .../v1，SDK 会拼 /chat/completions，
        # 所以这里要把 /v1 前缀剥掉再拼到上游 base 上，避免 /v1/v1。
        path = self.path
        if path.startswith("/v1"):
            path = path[3:]
        url = UPSTREAM.rstrip("/") + path

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""

        # 只透传认证和内容类型，其余头没必要，也不该转发（比如 Cookie）
        headers = {"Content-Type": "application/json"}
        auth = self.headers.get("Authorization")
        if auth:
            headers["Authorization"] = auth

        req = urllib.request.Request(url, data=body, headers=headers, method="POST")

        try:
            with OPENER.open(req, timeout=120) as resp:
                data = resp.read()
                code = resp.status
        except urllib.error.HTTPError as e:
            data = e.read() or b'{"error":"upstream http error"}'
            code = e.code
        except Exception as e:
            data = json.dumps(
                {"error": f"relay failed: {type(e).__name__}: {e}"}, ensure_ascii=False
            ).encode()
            code = 502

        elapsed = time.time() - started

        # 不含 Key、不含正文 —— 只留可判断"通没通"的信息
        try:
            model = json.loads(body.decode("utf-8")).get("model", "?")
        except Exception:
            model = "?"
        print(f"[{code}] {model} {elapsed:.1f}s  ->  {url}", flush=True)

        self._send(code, data)


def main():
    global UPSTREAM, OPENER

    # 重定向到文件时 stdout 会整块缓冲，导致"启动了但看不见任何输出"。
    # 行缓冲无害且能让日志实时可见。
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="OpenAI 兼容接口的中转，供不挂代理的手机使用")
    ap.add_argument("--base", default=UPSTREAM, help=f"上游 base，默认 {UPSTREAM}")
    ap.add_argument("--port", type=int, default=8787, help="监听端口，默认 8787")
    ap.add_argument(
        "--proxy",
        default="",
        help="访问上游用的代理，如 http://127.0.0.1:7890；留空则用系统代理",
    )
    args = ap.parse_args()

    UPSTREAM = args.base

    if args.proxy:
        OPENER = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})
        )
        print(f"上游代理：{args.proxy}（显式指定）")
    else:
        OPENER = urllib.request.build_opener()
        # Windows 下 urllib 会读注册表里的 WinINET 设置，Clash 的"系统代理"就是改那里
        print("上游代理：跟随系统代理（Clash 开着就行）")

    ip = lan_ip()
    port = args.port
    print()
    print("=" * 56)
    print(f"  中转已启动 → 上游 {UPSTREAM}")
    print()
    print("  手机设置 → 模型：")
    print(f"    接口地址   http://{ip}:{port}/v1")
    print(f"    API Key    你的真实 key")
    print("    模型名     deepseek-chat")
    print()
    print(f"  自检（手机浏览器打开）：http://{ip}:{port}/health")
    print("=" * 56)
    print()

    if ip == "127.0.0.1":
        print("！没取到局域网 IP，手机可能连不上。检查是否连了 WiFi。")
        print()

    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    sys.exit(main())
