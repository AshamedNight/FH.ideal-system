#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecom_live.py — 能当演示看的交互式聊天窗口

和 wecom_sim.py 的区别
----------------------
sim 是「跑一遍就退出」的批处理，用来证明链路通；
live 是「一直开着」的服务，你在浏览器里打一句话，
1 秒多之后会收到自动回复——这才是"自动化"看起来该有的样子。

三条链路全是真的
----------------
  你打的字 → 真实 AES 加密 + SHA1 签名 → HTTP POST 到本地回调
           → 验签解密 → 判定 → 生成 → 闸门 → 主动调 message/send
           → 假平台收到 → 界面显示

除了"平台是假的"，其余每一段都部署时会跑的那份代码。

跑法
----
  python wecom_live.py
  python wecom_live.py --persona personas/wecom_chat.json
  python wecom_live.py --dry            # 只判定不发送，看草稿
  python wecom_live.py --port 8790

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

from wecom_sim import SIM_AGENT_ID, SIM_CORP_ID, Simulator

HERE = Path(__file__).parent
UI_FILE = HERE / "wecom_live_ui.html"
DEFAULT_PERSONA = HERE / "personas" / "wecom_chat.json"
DEFAULT_PORT = 8790

# 每题之后的处理节奏。0.6 秒比后台 2 秒更有"秒回"的观感，
# 又不会快到看不出处理过程。
STEP_INTERVAL = 0.6


def _hhmmss(ts: float) -> str:
    return time.strftime("%H:%M:%S", time.localtime(ts))


class LiveSession:
    """一条常驻会话：收消息、跑判定、记录时间线。"""

    def __init__(self, persona_path: Path, db_path: Path,
                 dry_run: bool = False, sender: str = "zhangsan"):
        self.dry_run = dry_run
        self.sender = sender
        self.feed: list[dict] = []
        self._lock = threading.Lock()
        self._seq = 0
        self._pending: dict[str, tuple[float, int]] = {}
        self._stop = threading.Event()

        self.sim = Simulator({"turns": []}, persona_path, db_path,
                             dry_run=dry_run, on_trace=self._on_trace)
        self.sim.start()
        # 回调地址验证：对应后台点「保存」那一刻。失败会打印在启动信息里。
        self.url_ok, self.echo = self.sim.verify_url()

        self._worker = threading.Thread(target=self._loop, daemon=True)

    # ---------- 生命周期 ----------

    def start(self) -> None:
        self._worker.start()

    def stop(self) -> None:
        self._stop.set()
        self.sim.stop()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.sim.dispatcher.run_once()
            except Exception as exc:              # noqa: BLE001
                self.sim.dispatcher.last_error = f"{type(exc).__name__}: {exc}"
            self._stop.wait(STEP_INTERVAL)

    # ---------- 收一条"用户"消息 ----------

    def say(self, text: str) -> dict:
        text = (text or "").strip()
        if not text:
            return {"ok": False, "error": "消息为空"}
        now = time.time()
        with self._lock:
            self._seq += 1
            seq = self._seq
            self._pending[text] = (now, seq)
            self.feed.append({
                "seq": seq, "role": "user", "sender": self.sender,
                "text": text, "at": _hhmmss(now), "t": now,
            })
        # MsgId 必须每次不同，否则会被去重逻辑当成平台重试而丢掉
        msg_id = f"{int(now * 1000)}{seq:03d}"
        fields = {
            "ToUserName": SIM_CORP_ID,
            "FromUserName": self.sender,
            "CreateTime": str(int(now)),
            "MsgType": "text",
            "Content": text,
            "MsgId": msg_id,
            "AgentID": SIM_AGENT_ID,
        }
        ok = self.sim.push(fields)
        return {"ok": ok, "seq": seq}

    def _on_trace(self, trace: dict) -> None:
        now = time.time()
        text = trace.get("text", "")
        with self._lock:
            started, _ = self._pending.pop(text, (None, None))
            self._seq += 1
            self.feed.append({
                "seq": self._seq,
                "role": "bot",
                "replies_to": text,
                "text": trace.get("draft", ""),
                "final": trace.get("final", ""),
                "necessity": trace.get("necessity"),
                "why": self._explain(trace),
                "at": _hhmmss(now),
                "ms": int((now - started) * 1000) if started else None,
            })

    @staticmethod
    def _explain(trace: dict) -> str:
        """
        把决策翻成一句人能读懂的话。

        同一个 final 值背后的原因可能完全不同——「拦截」可能是议价也可能是退款，
        所以这里优先取具体原因，取不到才退回固定文案。
        """
        final = trace.get("final", "")
        if final == "blocked":
            return trace.get("guard_reason") or "命中人设边界"
        if final == "silent":
            reasons = trace.get("necessity_reasons") or []
            return f"判定不回复：{reasons[0]}" if reasons else "判定不回复"
        if final == "no_draft":
            return "语料里没有相近的问法，没生成草稿"
        gate = trace.get("auto_gate") or {}
        if gate.get("reasons"):
            last = gate["reasons"][-1]
            # 引擎内部的措辞「未启用分级放权，直接放行」对着界面讲不通，
            # 换成实际发生的事：私聊一对一、边界没命中、渠道支持自动发。
            if last.startswith("未启用分级放权"):
                where = "私聊一对一" if trace.get("surface") == "private" else "公开场合"
                return f"{where}，边界没命中，闸门放行"
            return last
        if final == "pending":
            return "低于自动发送门槛，转人工确认"
        return "闸门全过，自动发出"

    # ---------- 状态 ----------

    def state(self, since: int = 0) -> dict:
        with self._lock:
            items = [x for x in self.feed if x["seq"] > since]
            user_n = sum(1 for x in self.feed if x["role"] == "user")
            bot = [x for x in self.feed if x["role"] == "bot"]
            sent = sum(1 for x in bot if x["final"] == "sent")
            pending = sum(1 for x in bot if x["final"] == "pending")
            blocked = sum(1 for x in bot if x["final"] == "blocked")
            silent = sum(1 for x in bot if x["final"] in ("silent", "no_draft"))
            spans = [x["ms"] for x in bot if x.get("ms")]
            avg = int(sum(spans) / len(spans)) if spans else 0
        return {
            "items": items,
            "seq": self._seq,
            "stats": {
                "user": user_n, "bot": len(bot), "sent": sent,
                "pending": pending, "blocked": blocked, "silent": silent,
                "avg_ms": avg,
            },
            "mode": "只生成草稿（不发送）" if self.dry_run else "自动发送",
            "url_ok": self.url_ok,
        }

    def reset(self) -> None:
        with self._lock:
            self.feed.clear()
            self._pending.clear()
            self._seq = 0
        self.sim.platform.sent.clear()
        self.sim.dispatcher.hub.traces.clear()
        self.sim.dispatcher.hub.pending.clear()
        self.sim.dispatcher.hub.blocked.clear()
        self.sim.dispatcher.hub.silent.clear()


SESSION: LiveSession | None = None


# ---------------------------------------------------------------------------
# HTTP 服务
# ---------------------------------------------------------------------------

def make_ui_handler(session: LiveSession):
    class Handler(BaseHTTPRequestHandler):
        server_version = "wecom-live/1.0"

        def log_message(self, fmt, *args):
            pass

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

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}

            if parsed.path in ("/", "/index.html"):
                if not UI_FILE.exists():
                    self._send(500, "缺少 wecom_live_ui.html".encode("utf-8"),
                               "text/plain; charset=utf-8")
                    return
                self._send(200, UI_FILE.read_bytes(), "text/html; charset=utf-8")
                return

            if parsed.path == "/api/state":
                try:
                    since = int(q.get("since") or 0)
                except ValueError:
                    since = 0
                self._json(session.state(since))
                return

            if parsed.path == "/health":
                self._json({"ok": True, "url_verified": session.url_ok,
                            "stats": session.state(0)["stats"]})
                return

            self._json({"error": "not found"}, 404)

        def do_POST(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            try:
                body = json.loads(raw.decode("utf-8") or "{}")
            except json.JSONDecodeError:
                self._json({"ok": False, "error": "请求体不是合法 JSON"}, 400)
                return

            if parsed.path == "/api/say":
                self._json(session.say(str(body.get("text") or "")))
                return

            if parsed.path == "/api/reset":
                session.reset()
                self._json({"ok": True})
                return

            self._json({"error": "not found"}, 404)

    return Handler


def _free_port(start: int, tries: int = 20) -> int:
    """端口被占用时往后找。上一次没关掉的服务不该挡住这次演示。"""
    import socket
    for offset in range(tries):
        port = start + offset
        s = socket.socket()
        s.settimeout(0.3)
        try:
            s.bind(("127.0.0.1", port))
            return port
        except OSError:
            continue
        finally:
            s.close()
    return start


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="企业微信交互式演示（无需资质）")
    ap.add_argument("--persona", type=Path, default=DEFAULT_PERSONA)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--dry", action="store_true", help="只判定不发送")
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)

    persona = args.persona if args.persona.exists() else HERE / "persona.json"
    db = HERE / "wecom_live_state.db"
    if db.exists():
        db.write_bytes(b"")            # 清空不删除，沙箱可能禁删

    global SESSION
    SESSION = LiveSession(persona, db, dry_run=args.dry)
    SESSION.start()

    port = _free_port(args.port)
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_ui_handler(SESSION))
    url = f"http://127.0.0.1:{port}"

    print("=" * 66)
    print("企业微信交互式演示 —— 不需要注册、不需要公网、不联网")
    print("=" * 66)
    print(f"  人设      {persona.name}")
    print(f"  模式      {'只生成草稿' if args.dry else '自动发送'}")
    print(f"  回调验证  {'通过' if SESSION.url_ok else '失败'}"
          f"（协议层：加密 echostr 原样解回）")
    print(f"  打开      {url}")
    print()
    print("  在浏览器里打一句话，1 秒多会自动回复。")
    print("  试这几句能看出不同结局：在吗 / 多少钱 / 能便宜点吗 / 我要退款 / 嗯")
    print("  Ctrl+C 退出")
    print("=" * 66)

    if not args.no_browser:
        try:
            import webbrowser
            webbrowser.open(url)
        except Exception:                          # noqa: BLE001
            pass

    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        srv.server_close()
        SESSION.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
