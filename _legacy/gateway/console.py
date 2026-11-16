#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
console.py — 本地控制台（假数据测试用）

为什么需要这个
--------------
命令行能验证逻辑对不对，但验证不了两件事：

  1. 你看得懂每条消息为什么被这样处理吗
  2. 你真的愿意把生成的草稿发出去吗

第二点尤其重要。整个项目的成败不在技术，在于"你愿不愿意用"。
这个控制台就是为了让这件事在一个下午之内有答案——不需要任何平台资质。

启动
----
  python console.py
  # 浏览器打开 http://127.0.0.1:8770

依赖：仅标准库。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import urllib.parse
from urllib.parse import urlparse

from autopilot import AutopilotConfig, Credit, preview
from douyin import DouyinAdapter, DouyinClient, TokenStore
from gateway import Hub, MockEngine, Persona
from store import Store, StoreSeen

HERE = Path(__file__).parent
PORT = 8770


# ---------------------------------------------------------------------------
# 假抖音：不联网，但把真实接口的行为模拟出来
# ---------------------------------------------------------------------------

class FakeDouyin:
    """
    按真实接口的形状返回假数据。

    分批的节奏由 round 控制，而 round 由 Session.run_round 递增——
    不能放在 list 接口里自增，因为一轮 poll 会对每个 item_id 各调一次，
    那样第一轮就会把两个视频的评论一次性全吐出来，看不出"增量"。
    """

    def __init__(self) -> None:
        self.round = 0
        self.refresh_count = 0
        self.token_expired = True
        self.posted: list[dict] = []

    # 两个视频，各四条评论，合起来覆盖四种结局
    COMMENTS = {
        "item_1001": [
            {"comment_id": "c1", "content": "求链接", "user": {"nickname": "小王"}},
            {"comment_id": "c2", "content": "这个好看", "user": {"nickname": "小李"}},
            {"comment_id": "c3", "content": "这个在哪买", "user": {"nickname": "小张"}},
            {"comment_id": "c4", "content": "能便宜点吗", "user": {"nickname": "小赵"}},
        ],
        "item_1002": [
            {"comment_id": "c5", "content": "质量怎么样啊", "user": {"nickname": "小陈"}},
            {"comment_id": "c6", "content": "哈哈哈", "user": {"nickname": "小周"}},
            {"comment_id": "c7", "content": "我要退款", "user": {"nickname": "小吴"}},
            {"comment_id": "c8", "content": "多久能到", "user": {"nickname": "小郑"}},
        ],
    }

    def __call__(self, method: str, url: str, params: dict) -> dict:
        if url.endswith("/oauth/refresh_token/"):
            self.refresh_count += 1
            self.token_expired = False
            return {"data": {"error_code": 0, "access_token": "fresh_token",
                             "expires_in": 15 * 86400}}
        if url.endswith("/video/comment/list/"):
            item = urllib.parse.unquote(params.get("item_id", ""))
            pool = self.COMMENTS.get(item, [])
            # 第一轮每个视频露 2 条，第二轮露全 4 条 → 增量可见
            n = min(self.round * 2, len(pool))
            # 给每条评论一个真实的发布时间，间隔 5 分钟。
            # 如果全都用"现在"，一批评论会被刷屏规则判成连续轰炸而集体降权，
            # 那是测试环境的假象，不是真实行为。
            base = time.time() - 3600
            rows = [
                {**c, "create_time": base + i * 300}
                for i, c in enumerate(pool[:n])
            ]
            return {"data": {"error_code": 0, "has_more": 0, "list": rows}}
        if url.endswith("/video/comment/reply/"):
            self.posted.append({
                "item_id": urllib.parse.unquote(params.get("item_id", "")),
                "comment_id": params.get("comment_id", ""),
                "content": params.get("content", ""),
                "at": time.strftime("%H:%M:%S"),
            })
            return {"data": {"error_code": 0}}
        return {"data": {"error_code": 0}}


# ---------------------------------------------------------------------------
# 会话：一次运行的全部状态
# ---------------------------------------------------------------------------

class Session:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        # 持久化到固定路径，不是临时目录：
        # 重启控制台之后队列还在，这才叫后台。临时目录等于每次重启都清零。
        self.store = Store(HERE / "console_state.db")
        # 有历史数据就恢复，没有才初始化。
        # 这里不能再无脑 reset——启动即清空会把持久化的意义抹掉。
        if self.store.seen_count() == 0 and self.store.pending_count() == 0 \
                and not self.store.recent_sent(1):
            self.reset()
        else:
            self._build()

    def reset(self, keep_credit: bool = True) -> None:
        """重置数据。默认保留信任额度——额度是对系统能力的长期判断，
        不该因为换了一批测试数据就清零。需要清零时传 keep_credit=False。"""
        self.store.reset(keep_credit=keep_credit)
        self._build()

    def _build(self) -> None:
        """装配一套可运行的会话。重置与恢复共用这一段。"""
        self.workdir = Path(tempfile.mkdtemp(prefix="console_"))
        self.persona = Persona.load(HERE / "persona.json")
        self.fake = FakeDouyin()
        self.client = DouyinClient(
            client_key="demo_key", client_secret="demo_secret",
            open_id="demo_open_id",
            token_store=TokenStore(self.workdir / "token.json"),
            transport=self.fake, min_interval=0,
        )
        # 造一个已过期令牌，第一次跑就能看到自动刷新
        self.client.set_token("stale_token", "stale_refresh", expires_in=-10)

        self.adapter = DouyinAdapter(
            self.client, ["item_1001", "item_1002"],
            # 去重改用持久表：重启后不会把已经回过的评论再回一遍
            seen=StoreSeen(self.store),
            page_size=20, max_pages=2,
        )
        # credit 独立于 Hub 创建：这样开关 autopilot 时额度不会丢。
        # 实际数值由 Hub 从 store 里恢复，这里只是提供一个空壳。
        self.credit = Credit()
        # 演示环境把解锁门槛降到 4，否则一次会话里攒不满 10 次确认。
        # 生产默认是 10（见 autopilot.AutopilotConfig）。
        self.auto_cfg = AutopilotConfig(unlock_after=4)
        self.hub = Hub(self.persona, MockEngine(), rate_limit=20,
                       necessity=True, allow_public_auto=False,
                       autopilot=False, auto_cfg=self.auto_cfg,
                       credit=self.credit, store=self.store)
        self.hub.register(self.adapter, channels=["douyin"])
        self.total_seen = self.store.seen_count()

    # ---------- 操作 ----------

    def run_round(self) -> dict:
        with self.lock:
            # 轮次由这里控制：一轮 poll 会对每个 item_id 各调一次 list，
            # 如果让假数据在 list 里自增，第一轮就会把两个视频全吐出来。
            self.fake.round += 1
            before = len(self.hub.traces)
            processed = self.hub.step()
            # 去重计数从持久层读，不再自己累加——累加值在重启后会归零，
            # 而实际的去重记录还在数据库里，两个数字对不上就没人信这个面板。
            self.total_seen = self.store.seen_count()
            return {"processed": processed,
                    "new_traces": len(self.hub.traces) - before}

    def set_autopilot(self, on: bool) -> None:
        with self.lock:
            if on:
                if self.hub.credit is None:
                    self.hub.credit = Credit()
                if self.hub.auto_cfg is None:
                    self.hub.auto_cfg = AutopilotConfig()
            self.hub.autopilot = bool(on)

    def approve(self, index: int) -> bool:
        with self.lock:
            return self.hub.approve(index)

    def reject(self, index: int) -> bool:
        with self.lock:
            return self.hub.reject(index)

    def approve_all(self) -> int:
        with self.lock:
            n = 0
            while self.hub.pending and self.hub.approve(0):
                n += 1
            return n

    # ---------- 状态 ----------

    def state(self) -> dict:
        with self.lock:
            token = self.client.token
            expires_in = max(0, int((token.expires_at - time.time()) / 86400)) if token else 0
            traces = list(reversed(self.hub.traces))   # 最新的在上
            counts: dict[str, int] = {}
            for t in self.hub.traces:
                counts[t["final"]] = counts.get(t["final"], 0) + 1

            pending = [
                {"index": i,
                 "sender": r.sender or r.conv_id,   # 昵称优先，没有才退回 id
                 "incoming": r.incoming,            # 对方原话，方便对比
                 "text": r.text,
                 "thread": r.thread,
                 "reason": r.reason or "待确认"}
                for i, r in enumerate(self.hub.pending)
            ]

            # 演练：当前额度下，这批待确认里有多少会自动发
            cfg = self.hub.auto_cfg or AutopilotConfig()
            drill = preview(
                [{"draft": p["text"], "confidence": 0.0} for p in pending],
                self.credit, cfg) if pending else {"summary": {"auto": 0}}
            would_auto = drill["summary"]["auto"]

            return {
                "ok": True,
                "persona": {
                    "name": self.persona.name,
                    "few_shot": len(self.persona.few_shot),
                    "knowledge": len(self.persona.knowledge),
                    "boundaries": len(self.persona.boundaries),
                },
                "token": {
                    "state": "已刷新" if self.fake.refresh_count else "未刷新",
                    "refresh_count": self.fake.refresh_count,
                    "expires_in_days": expires_in,
                },
                "seen": len(self.adapter.seen),
                "rounds": self.fake.round,
                "traces": traces,
                "pending": pending,
                "autopilot": {
                    "on": self.hub.autopilot,
                    "credit": self.credit.snapshot(),
                    "unlock_after": (self.hub.auto_cfg or AutopilotConfig()).unlock_after,
                    "would_auto": would_auto,
                },
                "posted": list(reversed(self.fake.posted))[:20],
                "stats": {
                    "total": len(self.hub.traces),
                    "pending": counts.get("pending", 0),
                    "silent": counts.get("silent", 0),
                    "blocked": counts.get("blocked", 0),
                    "sent": counts.get("sent", 0),
                    "no_draft": counts.get("no_draft", 0),
                },
            }


SESSION = Session()


# ---------------------------------------------------------------------------
# HTTP 服务
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "NexusConsole/1.0"

    def log_message(self, fmt, *args):   # 静默默认访问日志，只留错误
        pass

    # ---------- 工具 ----------

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _body(self) -> dict:
        try:
            n = int(self.headers.get("Content-Length", 0))
            if not n:
                return {}
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except (ValueError, json.JSONDecodeError):
            return {}

    # ---------- 路由 ----------

    def do_GET(self) -> None:                      # noqa: N802
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            html = (HERE / "console_ui.html").read_bytes()
            self._send(200, html, "text/html; charset=utf-8")
            return
        if path == "/api/state":
            self._json(SESSION.state())
            return
        self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self) -> None:                     # noqa: N802
        path = urlparse(self.path).path
        body = self._body()

        try:
            if path == "/api/run":
                self._json({**SESSION.run_round(), "ok": True})
            elif path == "/api/approve":
                ok = SESSION.approve(int(body.get("index", -1)))
                self._json({"ok": ok})
            elif path == "/api/reject":
                ok = SESSION.reject(int(body.get("index", -1)))
                self._json({"ok": ok})
            elif path == "/api/approve_all":
                self._json({"ok": True, "count": SESSION.approve_all()})
            elif path == "/api/autopilot":
                SESSION.set_autopilot(bool(body.get("on")))
                self._json({"ok": True})
            elif path == "/api/reset":
                SESSION.reset(keep_credit=bool(body.get("keep_credit", True)))
                self._json({"ok": True})
            else:
                self._json({"ok": False, "error": "not found"}, 404)
        except Exception as exc:                    # noqa: BLE001
            # 控制台崩了比返回错误更糟：至少让页面能看到发生了什么
            self._json({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, 500)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except (ValueError, OSError):
            pass

    url = f"http://127.0.0.1:{PORT}"
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)

    print("=" * 64)
    print("NEXUS 本地控制台 · 假数据模式（不连任何平台）")
    print("=" * 64)
    print(f"  打开：{url}")
    print("  数据全部是模拟的，不会对外发出任何消息")
    print("  按 Ctrl+C 退出")
    print("=" * 64)

    try:
        if not os.environ.get("NEXUS_NO_BROWSER"):
            webbrowser.open(url)
    except Exception:        # noqa: BLE001
        pass                 # 打不开浏览器不影响服务，手动访问即可

    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出。")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
