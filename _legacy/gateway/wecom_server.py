#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecom_server.py — 企业微信自建应用的回调服务 + 自动回复

一个进程跑完整链路：
  平台 POST 消息 → 验签解密 → 入队 → 立即返回
                                    ↓
              后台线程每 2 秒跑一次 Hub：判定 → 生成 → 闸门 → 发送

为什么回调要「立即返回」
-----------------------
企业微信在 5 秒内收不到响应会断开并重试，总共三次。
判定 + 生成可能超过 5 秒（接了模型更慢），所以回调只做验签、解密、入队，
返回空串表示「这次不被动回复」——平台不会报错，我们改用主动发消息。

这个设计还有一个好处：回复不受 5 秒窗口限制，
可以走「先挂起、人工确认后再发」的流程，跟抖音那条链路一致。

跑法
----
  python wecom_server.py --config wecom.json
  python wecom_server.py --config wecom.json --dry     # 只收不回

wecom.json：
  {
    "corp_id": "ww...",
    "corp_secret": "应用 Secret",
    "agent_id": "1000002",
    "token": "回调 Token",
    "encoding_aes_key": "43 位 EncodingAESKey",
    "port": 8780,
    "persona": "persona.json"
  }

依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from gateway import Hub, Inbound, MockEngine, Persona, Surface
from store import Store
from wecom import WeComAdapter, WeComClient
from wecom_crypto import WeComCrypto

HERE = Path(__file__).parent


def parse_xml(xml_text: str) -> dict:
    """把回调 XML 拍平成 dict。CDATA 会被解析成普通文本。"""
    root = ET.fromstring(xml_text)
    return {child.tag: (child.text or "") for child in root}


def to_inbound(fields: dict) -> Inbound | None:
    """
    回调消息 → 统一消息模型。

    只接 text。图片、语音、事件（关注/进入应用）先不处理——
    接了也生成不出有意义的回复，反而会淹没真实消息。
    """
    if fields.get("MsgType") != "text":
        return None
    text = (fields.get("Content") or "").strip()
    user = fields.get("FromUserName") or ""
    if not text or not user:
        return None
    return Inbound(
        channel="wecom",
        conv_id=user,
        sender=user,             # 自建应用拿不到昵称，只有 userid
        text=text,
        ts=float(fields.get("CreateTime") or time.time()),
        surface=Surface.PRIVATE,  # 企业微信是点对点，不是公开评论区
        raw=fields,
    )


class Dispatcher:
    """把回调收消息、后台跑判定这两件事隔开。"""

    def __init__(self, config: dict, dry_run: bool = False, on_trace=None):
        self.config = config
        self.dry_run = dry_run
        # 每条决策产生时回调一次。演示界面靠它渲染实时时间线；
        # 生产部署不需要传，传了也只是多一次内存操作。
        self.on_trace = on_trace
        self.crypto = WeComCrypto(
            config["token"], config["encoding_aes_key"], config["corp_id"])
        self.client = WeComClient(
            config["corp_id"], config["corp_secret"], config["agent_id"],
            cache_path=HERE / "wecom_token.json")
        self.adapter = WeComAdapter(self.client)

        persona_path = Path(config.get("persona") or (HERE / "persona.json"))
        if not persona_path.is_absolute():
            persona_path = HERE / persona_path
        persona = Persona.load(persona_path)

        store_path = Path(config.get("db") or "wecom_state.db")
        if not store_path.is_absolute():
            store_path = HERE / store_path
        self.store = Store(store_path)
        self.hub = Hub(persona, MockEngine(), rate_limit=30, necessity=True,
                       store=self.store, dry_run=dry_run)
        self.hub.register(self.adapter, channels=["wecom"])

        self.rounds = 0
        self.last_error = ""
        self._stop = threading.Event()

    # ---------- 收 ----------

    def accept(self, fields: dict) -> bool:
        """
        收到一条回调。返回是否入队。

        用 MsgId 去重：平台重试时 MsgId 不变，不去重会回两遍。
        没有 MsgId 的消息（部分事件）用「用户+时间+内容」兜底。
        """
        msg_id = fields.get("MsgId") or "%s|%s|%s" % (
            fields.get("FromUserName"), fields.get("CreateTime"),
            fields.get("Content"))
        if self.store.seen(f"wecom:{msg_id}"):
            return False
        self.store.mark_seen(f"wecom:{msg_id}")

        msg = to_inbound(fields)
        if msg is None:
            return False
        self.adapter.push(msg)
        return True

    # ---------- 处理 ----------

    def run_once(self) -> int:
        before = len(self.hub.traces)
        try:
            n = self.hub.step()
            self.rounds += 1
            self.last_error = ""
        except Exception as exc:          # noqa: BLE001
            self.last_error = f"{type(exc).__name__}: {exc}"
            return 0

        # 把本轮新产生的决策交给观察者。
        # 观察者里的异常必须吞掉：渲染失败不能把主链路带停，
        # 那会变成「界面白屏 + 消息也不再回复」这种最难查的故障。
        if self.on_trace:
            for trace in self.hub.traces[before:]:
                try:
                    self.on_trace(trace)
                except Exception:         # noqa: BLE001
                    pass
        return n

    def loop(self, interval: float = 2.0) -> None:
        while not self._stop.is_set():
            self.run_once()
            self._stop.wait(interval)

    def start_background(self, interval: float = 2.0) -> threading.Thread:
        t = threading.Thread(target=self.loop, args=(interval,), daemon=True)
        t.start()
        return t

    def stop(self) -> None:
        self._stop.set()

    def close(self) -> None:
        """
        停止并释放数据库句柄。

        Windows 上不关连接会一直锁着 .db 文件：删不掉、改不了名，
        重新部署时表现为「文件被另一个程序占用」。所以停机走这个，
        不要只调 stop()。
        """
        self._stop.set()
        try:
            self.store.close()
        except Exception:                 # noqa: BLE001
            pass

    def stats(self) -> dict:
        return {
            "rounds": self.rounds,
            "accepted": self.adapter.accepted,
            "dropped": self.adapter.dropped,
            "pending": len(self.hub.pending),
            "queue": self.store.stats(),
            "last_error": self.last_error,
            "dry_run": self.dry_run,
        }


DISPATCHER: Dispatcher | None = None


def make_handler(dispatcher: Dispatcher):
    class Handler(BaseHTTPRequestHandler):
        server_version = "wecom-gateway/1.0"

        def log_message(self, fmt, *args):
            pass

        def _text(self, code: int, body: str) -> None:
            raw = body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _json(self, obj, code: int = 200) -> None:
            raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        # ---------- GET：URL 验证 ----------

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}

            if parsed.path == "/health":
                self._json(dispatcher.stats())
                return
            if parsed.path != "/wecom/callback":
                self._text(404, "not found")
                return

            sig = q.get("msg_signature", "")
            ts = q.get("timestamp", "")
            nonce = q.get("nonce", "")
            echostr = urllib.parse.unquote(q.get("echostr", ""))
            if not (sig and ts and nonce and echostr):
                self._text(400, "missing params")
                return
            try:
                if not dispatcher.crypto.verify(sig, ts, nonce, echostr):
                    self._text(403, "bad signature")
                    return
                # 必须原样返回解密后的 echostr，不加引号、不加换行
                self._text(200, dispatcher.crypto.decrypt(echostr))
            except Exception as exc:              # noqa: BLE001
                self._text(500, f"verify failed: {exc}")

        # ---------- POST：消息推送 ----------

        def do_POST(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/wecom/callback":
                self._text(404, "not found")
                return
            q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length).decode("utf-8", "ignore")

            try:
                outer = parse_xml(body)
                enc = outer.get("Encrypt") or ""
                if not dispatcher.crypto.verify(
                        q.get("msg_signature", ""), q.get("timestamp", ""),
                        q.get("nonce", ""), enc):
                    self._text(403, "bad signature")
                    return
                plain = dispatcher.crypto.decrypt(enc)
                fields = parse_xml(plain)
                dispatcher.accept(fields)
            except Exception as exc:              # noqa: BLE001
                # 解密失败要记日志，但不能返回 500——
                # 平台会重试三次，重试也解不开，只会刷日志。
                print(f"[wecom] 回调处理失败：{exc}", file=sys.stderr)
                self._text(200, "")
                return

            # 返回空串 = 这次不被动回复，回复走主动发送
            self._text(200, "")

    return Handler


def load_config(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    required = ("corp_id", "corp_secret", "agent_id", "token", "encoding_aes_key")
    missing = [k for k in required if not data.get(k)]
    if missing:
        raise ValueError(f"配置缺字段：{', '.join(missing)}")
    return data


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="企业微信自动回复服务")
    ap.add_argument("--config", type=Path, default=HERE / "wecom.json")
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--dry", action="store_true", help="只收不回（闸门全走人工）")
    args = ap.parse_args(argv)

    if not args.config.exists():
        print(f"找不到配置：{args.config}", file=sys.stderr)
        print("先复制 wecom.example.json 并填上 corp_id / secret / token / aes_key",
              file=sys.stderr)
        return 2

    try:
        cfg = load_config(args.config)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"配置有问题：{exc}", file=sys.stderr)
        return 2

    global DISPATCHER
    DISPATCHER = Dispatcher(cfg, dry_run=args.dry)
    DISPATCHER.start_background()

    port = args.port or int(cfg.get("port") or 8780)
    srv = ThreadingHTTPServer(("0.0.0.0", port), make_handler(DISPATCHER))
    print(f"企业微信回调已启动：http://0.0.0.0:{port}/wecom/callback")
    print(f"  健康检查：http://127.0.0.1:{port}/health")
    print(f"  模式：{'只收不回' if args.dry else '按闸门决定是否发送'}")
    print("  把这个 URL 填进「应用管理 → 接收消息 → 设置API接收」")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        DISPATCHER.close()
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
