#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
douyin_auth.py — 抖音授权：三种拿 code 的方式

为什么单独做这个工具
--------------------
抖音的 OAuth 走授权码模式：浏览器完成授权后，平台把 `code` 拼在
`redirect_uri` 后面跳回去。常规做法是让自己的服务器接住这次跳转。

但没有备案域名时，接不住。于是有人以为「必须备案才能授权」——
其实不一定。**`redirect_uri` 只是浏览器要跳的一个地址**，
那个地址打不开也不影响一件事：code 已经出现在地址栏里了。

所以三种方式，从最省事到最完整：

  ① paste   手动复制 code          不需要服务器、不需要域名能访问
  ② serve   本地回调 + 内网穿透     需要 ngrok / cpolar 这类工具
  ③ mini    小程序 ticket           不需要 redirect_uri（走另一套接口）

①是本文档默认推荐：只要后台能保存 redirect_uri，剩下的全是复制粘贴。

跑法
----
  # 生成授权链接（填你自己的 client_key 和后台配的回调地址）
  python douyin_auth.py url --client-key tt1234567890 \
      --redirect https://example.com/douyin/callback \
      --scope user_info,item.comment

  # 浏览器授权完，把地址栏整条 URL 丢进来自动提取 code
  python douyin_auth.py paste --client-key tt... --secret abc... \
      --code "https://example.com/cb?code=ffab5ec2&state=xyz"

  # 或者起本地回调，配合内网穿透自动收
  python douyin_auth.py serve --client-key tt... --secret abc... --port 9527

  # 看当前令牌还能用几天
  python douyin_auth.py status

依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from douyin import BASE, Token, TokenStore, _default_transport

HERE = Path(__file__).parent
AUTH_URL = f"{BASE}/platform/oauth/connect/"
TOKEN_PATH = f"{BASE}/oauth/access_token/"

# code 有效期 10 分钟、只能用一次。超时报 10007。
ERR_CODE_EXPIRED = 10007
ERR_PARAM = 10002

CODE_TTL = 600           # 秒，文档口径


# ---------------------------------------------------------------------------
# 授权链接
# ---------------------------------------------------------------------------

def build_auth_url(client_key: str, redirect_uri: str, scope: str,
                   state: str = "s1") -> str:
    """
    拼授权链接。

    redirect_uri 必须与后台「开发配置 → 授权回调」里填的完全一致，
    包括协议、path、末尾斜杠。差一个字符就是 redirect_uri not allowed。
    """
    if not client_key:
        raise ValueError("client_key 不能为空")
    if not redirect_uri:
        raise ValueError("redirect_uri 不能为空")
    q = urllib.parse.urlencode({
        "client_key": client_key,
        "response_type": "code",
        "scope": scope,
        "redirect_uri": redirect_uri,
        "state": state,
    })
    return f"{AUTH_URL}?{q}"


def extract_code(raw: str) -> str:
    """
    从用户粘贴的内容里取出 code。

    能处理三种情况，因为用户实际会粘这三种：
      · 裸 code：ffab5ec26cd958fd
      · 完整回调 URL：https://x/cb?code=ffab&state=s1
      · 带引号或空格的 URL：  " https://x/cb?code=ffab "
    """
    text = (raw or "").strip().strip('"').strip("'").strip()
    if not text:
        return ""
    if "code=" not in text:
        return text.split()[0]
    query = urllib.parse.urlparse(text).query
    if not query and "?" in text:
        query = text.split("?", 1)[1]
    values = urllib.parse.parse_qs(query).get("code") or []
    return values[0] if values else text.split()[0]


# ---------------------------------------------------------------------------
# 换令牌
# ---------------------------------------------------------------------------

class AuthError(Exception):
    def __init__(self, code: int, message: str, hint: str = ""):
        super().__init__(message)
        self.code = code
        self.message = message
        self.hint = hint


HINTS = {
    ERR_CODE_EXPIRED: "授权码只有 10 分钟且只能用一次，重新生成授权链接再来",
    ERR_PARAM: "检查 client_key / client_secret / code 是否漏传或含多余空格",
    10010: "这是 refresh_token 的错误码，说明粘错了内容",
    2100005: "参数错误：确认 grant_type 是 authorization_code",
}


def exchange_code(client_key: str, client_secret: str, code: str,
                  transport=None) -> Token:
    """
    用授权码换 access_token。

    返回 Token（含 open_id 和过期时间），调用方负责落盘。
    失败抛 AuthError，并且带上人话提示——错误码单独看没法处理。
    """
    code = extract_code(code)
    if not code:
        raise AuthError(-1, "授权码为空")
    if len(code) < 8:
        raise AuthError(-1, f"授权码太短（{len(code)} 字符），大概率粘错了")

    resp = (transport or _default_transport)("POST", TOKEN_PATH, {
        "client_key": client_key,
        "client_secret": client_secret,
        "code": code,
        "grant_type": "authorization_code",
    })

    if "_error" in resp:
        raise AuthError(-1, f"网络失败：{resp['_error']}", "检查网络或代理")

    data = resp.get("data") or {}
    code_no = int(data.get("error_code") or 0)
    if code_no:
        desc = data.get("description") or resp.get("message") or "未知错误"
        raise AuthError(code_no, f"[{code_no}] {desc}", HINTS.get(code_no, ""))

    access = data.get("access_token") or ""
    if not access:
        raise AuthError(-1, "返回里没有 access_token",
                        f"原始响应：{json.dumps(resp, ensure_ascii=False)[:200]}")

    expires_in = float(data.get("expires_in") or 15 * 86400)
    return Token(
        access_token=access,
        refresh_token=data.get("refresh_token") or "",
        open_id=data.get("open_id") or "",
        expires_at=time.time() + expires_in,
    )


def save_token(token: Token, path: Path = None) -> Path:
    path = path or (HERE / "token.json")
    TokenStore(path).save(token)
    return path


# ---------------------------------------------------------------------------
# 本地回调
# ---------------------------------------------------------------------------

class CallbackServer:
    """
    接住平台跳转的 code。

    只做一件事：从 query 里取 code，存进 result，然后回一句提示。
    不做授权、不存令牌——那只在 paste/主流程里做，避免两处逻辑。
    """

    def __init__(self, path: str = "/douyin/callback", port: int = 0):
        self.path = path
        self.result: dict = {}
        try:
            self._server = ThreadingHTTPServer(("127.0.0.1", port),
                                               self._handler())
        except OSError as exc:
            raise AuthError(
                -1, f"端口 {port} 绑定失败：{exc}",
                "换一个端口，或先关掉占用它的程序") from exc
        self.port = self._server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"
        self._thread: threading.Thread | None = None

    def _handler(self):
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_GET(self):
                parsed = urllib.parse.urlparse(self.path)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
                if parsed.path != owner.path:
                    self.send_response(404)
                    self.end_headers()
                    return
                owner.result = q
                body = (
                    "<html><meta charset=utf-8>"
                    "<body style='font:16px/1.8 system-ui;padding:40px'>"
                    + ("<h3>授权码已收到</h3><p>可以关掉这个页面，回到终端。</p>"
                       if q.get("code") else
                       f"<h3>没有拿到 code</h3><pre>{q}</pre>")
                    + "</body></html>"
                ).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        return Handler

    def start(self) -> None:
        """
        后台开始接收请求。

        不用 wait() 里那种「边等边 handle_request」的写法：
        调用方一旦想先验证别的路径（比如 404），服务就没人接手，
        连接会一直挂到超时。后台线程更接近真实部署的行为。
        """
        self._thread = threading.Thread(
            target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def wait(self, timeout: float = 300.0) -> str:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.result.get("code"):
                return self.result["code"]
            time.sleep(0.1)
        return ""

    def close(self):
        try:
            self._server.shutdown()
        finally:
            self._server.server_close()


# ---------------------------------------------------------------------------
# 状态
# ---------------------------------------------------------------------------

def describe(token: Token) -> str:
    left = token.expires_at - time.time()
    days = left / 86400
    if left <= 0:
        return "已过期（access_token 15 天，需要重新授权或刷新）"
    if days < 1:
        return f"还剩 {left / 3600:.1f} 小时，尽快刷新"
    return f"还剩 {days:.1f} 天"


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def cmd_url(ns) -> int:
    url = build_auth_url(ns.client_key, ns.redirect, ns.scope, ns.state)
    print("把下面这条链接在浏览器里打开，用要授权的抖音号扫码：\n")
    print(url)
    print("\n授权完成后浏览器会跳到一个地址，地址栏里带 code=...。")
    print("整条 URL 复制下来，交给：")
    print(f"  python douyin_auth.py paste --client-key {ns.client_key} "
          f"--secret <你的 secret> --code \"<粘在这里>\"")
    print("\n注意：code 只有 10 分钟有效期，且只能用一次（错误码 10007）。")
    return 0


def cmd_paste(ns) -> int:
    code = extract_code(ns.code)
    print(f"识别到授权码：{code[:12]}…（{len(code)} 字符）")
    try:
        token = exchange_code(ns.client_key, ns.secret, code)
    except AuthError as exc:
        print(f"\n换取失败：{exc.message}", file=sys.stderr)
        if exc.hint:
            print(f"怎么办：{exc.hint}", file=sys.stderr)
        return 1

    path = save_token(token)
    print("\n成功。")
    print(f"  open_id       {token.open_id}")
    print(f"  有效期        {describe(token)}")
    print(f"  已保存到      {path.name}")
    print("\n下一步：python douyin.py 跑真实拉取（不是 demo）")
    return 0


def cmd_serve(ns) -> int:
    try:
        server = CallbackServer(ns.path, ns.port)
    except AuthError as exc:
        print(exc.message, file=sys.stderr)
        if exc.hint:
            print(f"怎么办：{exc.hint}", file=sys.stderr)
        return 1
    print("=" * 66)
    print("本地回调服务已启动")
    print("=" * 66)
    print(f"  本机地址   {server.base}{ns.path}")
    print()
    print("  这个地址平台访问不到，需要用内网穿透映射成 https：")
    print(f"    ngrok http {server.port}")
    print(f"    cpolar http {server.port}")
    print()
    print("  把穿透给出的 https 地址 + 路径填进后台的授权回调，例如：")
    print(f"    https://xxxx.ngrok-free.app{ns.path}")
    print()
    print("  然后打开这个链接授权：")
    print(f"  {build_auth_url(ns.client_key, ns.public or '<穿透地址>' + ns.path, ns.scope)}")
    print()
    print(f"  等待回调（{ns.timeout} 秒）…")
    print("=" * 66)

    server.start()
    try:
        code = server.wait(ns.timeout)
    finally:
        server.close()

    if not code:
        print("超时，没有收到 code。", file=sys.stderr)
        print("三个常见原因：穿透没生效、后台回调地址与授权链接不一致、"
              "授权时点了取消。", file=sys.stderr)
        return 1

    print(f"收到授权码：{code[:12]}…")
    try:
        token = exchange_code(ns.client_key, ns.secret, code)
    except AuthError as exc:
        print(f"换取失败：{exc.message}", file=sys.stderr)
        if exc.hint:
            print(f"怎么办：{exc.hint}", file=sys.stderr)
        return 1

    path = save_token(token)
    print(f"成功｜open_id {token.open_id}｜{describe(token)}｜已存 {path.name}")
    return 0


def cmd_mini(ns) -> int:
    """
    小程序路径：tt.showDouyinOpenAuth 返回的 ticket 就是 code。

    不需要 redirect_uri，所以不需要域名——这是绕开备案的正路。
    """
    print("小程序路径：把小程序里 tt.showDouyinOpenAuth 返回的 ticket 贴进来。")
    print("文档写明「不是从 tt.login 获取」，两个别混。\n")
    ticket = extract_code(ns.ticket)
    try:
        token = exchange_code(ns.client_key, ns.secret, ticket)
    except AuthError as exc:
        print(f"换取失败：{exc.message}", file=sys.stderr)
        if exc.hint:
            print(f"怎么办：{exc.hint}", file=sys.stderr)
        return 1
    path = save_token(token)
    print(f"\n成功｜open_id {token.open_id}｜{describe(token)}｜已存 {path.name}")
    return 0


def cmd_status(ns) -> int:
    store = TokenStore(ns.token)
    token = store.load()
    if token is None:
        print("没有本地令牌。先走 url + paste 拿一个。")
        return 1
    print(f"  open_id       {token.open_id}")
    print(f"  access_token  {token.access_token[:16]}…")
    print(f"  状态          {describe(token)}")
    if token.expired():
        print("\n已过期。先试 python douyin.py --refresh，"
              "失败再重新授权（refresh_token 30 天，最长续到 195 天）。")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="抖音授权码换取工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p1 = sub.add_parser("url", help="生成授权链接")
    p1.add_argument("--client-key", required=True)
    p1.add_argument("--redirect", required=True,
                    help="后台配置的授权回调地址，必须完全一致")
    p1.add_argument("--scope", default="user_info,item.comment")
    p1.add_argument("--state", default="s1")
    p1.set_defaults(func=cmd_url)

    p2 = sub.add_parser("paste", help="粘贴 code 换令牌（不需要服务器）")
    p2.add_argument("--client-key", required=True)
    p2.add_argument("--secret", required=True)
    p2.add_argument("--code", required=True, help="code 或整条回调 URL")
    p2.set_defaults(func=cmd_paste)

    p3 = sub.add_parser("serve", help="本地回调 + 内网穿透")
    p3.add_argument("--client-key", required=True)
    p3.add_argument("--secret", required=True)
    p3.add_argument("--scope", default="user_info,item.comment")
    p3.add_argument("--path", default="/douyin/callback")
    p3.add_argument("--public", default="", help="穿透后的公网地址前缀")
    p3.add_argument("--port", default=0, type=int, help="固定端口给穿透用")
    p3.add_argument("--timeout", type=float, default=300.0)
    p3.set_defaults(func=cmd_serve)

    p4 = sub.add_parser("mini", help="小程序 ticket 换令牌")
    p4.add_argument("--client-key", required=True)
    p4.add_argument("--secret", required=True)
    p4.add_argument("--ticket", required=True)
    p4.set_defaults(func=cmd_mini)

    p5 = sub.add_parser("status", help="查看本地令牌")
    p5.add_argument("--token", type=Path, default=HERE / "token.json")
    p5.set_defaults(func=cmd_status)

    ns = ap.parse_args(argv)
    return ns.func(ns)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
