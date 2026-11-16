#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecom_sim.py — 没有企业微信资格，也能把全链路跑通

它做的三件事
------------
1. **冒充平台**：本地起一个 HTTP 服务代替 qyapi.weixin.qq.com，
   提供 gettoken 与 message/send，并把收到的每条消息原样记录下来。
2. **扮演用户**：把场景里的消息按真实协议加密、签名，再 POST 到回调服务。
   走的是真的 HTTP、真的 AES、真的签名，不是打桩。
3. **自己验收**：跑完检查「该发的发了、该拦的拦了、重复推送没回两遍」。

为什么不能只替换 transport
--------------------------
替换 transport 能测业务逻辑，但测不到 URL 编码、CDATA、Content-Type、
端口绑定这些真实部署才会出问题的地方。所以这里连 HTTP 层一起走。

跑法
----
  python wecom_sim.py                          # 默认客服场景
  python wecom_sim.py --scenario recordings/wecom_chat.json
  python wecom_sim.py --persona personas/wecom_chat.json
  python wecom_sim.py --keep                   # 跑完不退出，留 /health 可查

不需要网络、不需要注册企业、不需要公网地址。依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from wecom_crypto import WeComCrypto
from wecom_server import Dispatcher, make_handler, parse_xml

HERE = Path(__file__).parent
DEFAULT_SCENARIO = HERE / "recordings" / "wecom_chat.json"
DEFAULT_PERSONA = HERE / "personas" / "wecom_chat.json"

# 假凭据。格式必须真实（EncodingAESKey 解码后要正好 32 字节），
# 否则连初始化都过不去，测不出任何东西。
SIM_CORP_ID = "wwsim0000000000001"
SIM_SECRET = "sim_corp_secret_not_real"
SIM_AGENT_ID = "1000002"
SIM_TOKEN = "SimCallbackToken2026"
SIM_AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C"


# ---------------------------------------------------------------------------
# 冒充 qyapi.weixin.qq.com
# ---------------------------------------------------------------------------

class FakePlatform:
    """
    假的接口服务器。

    只实现两个端点，因为客户端只会调这两个。
    令牌数和发送记录都留着，供自检统计。
    """

    def __init__(self) -> None:
        self.token_calls = 0
        self.sent: list[dict] = []
        self.fail_send = False          # 置 True 模拟发送失败
        self._lock = threading.Lock()
        platform = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "wecom-sim/1.0"

            def log_message(self, fmt, *args):
                pass

            def _json(self, obj: dict, code: int = 200) -> None:
                raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self) -> None:
                parsed = urllib.parse.urlparse(self.path)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
                if parsed.path == "/cgi-bin/gettoken":
                    if q.get("corpsecret") != SIM_SECRET:
                        # 40001 是 secret 错误，客户端会抛不可恢复异常
                        self._json({"errcode": 40001, "errmsg": "invalid credential"})
                        return
                    with platform._lock:
                        platform.token_calls += 1
                    self._json({"errcode": 0, "errmsg": "ok",
                                "access_token": "SIM_ACCESS_TOKEN",
                                "expires_in": 7200})
                    return
                self._json({"errcode": 404}, 404)

            def do_POST(self) -> None:
                parsed = urllib.parse.urlparse(self.path)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode("utf-8", "ignore")
                if parsed.path != "/cgi-bin/message/send":
                    self._json({"errcode": 404}, 404)
                    return
                if q.get("access_token") != "SIM_ACCESS_TOKEN":
                    # 42001 是令牌过期，客户端会清缓存重试
                    self._json({"errcode": 42001, "errmsg": "access_token expired"})
                    return
                if platform.fail_send:
                    self._json({"errcode": 45009, "errmsg": "api freq out of limit"})
                    return
                data = json.loads(body or "{}")
                with platform._lock:
                    platform.sent.append({
                        "to": data.get("touser", ""),
                        "agentid": data.get("agentid"),
                        "content": ((data.get("text") or {}).get("content") or ""),
                        "at": time.strftime("%H:%M:%S"),
                    })
                self._json({"errcode": 0, "errmsg": "ok"})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()


# ---------------------------------------------------------------------------
# 扮演平台推送消息
# ---------------------------------------------------------------------------

def build_xml(fields: dict) -> str:
    """拼回调 XML。真实平台用的就是这种 CDATA 写法。"""
    parts = ["<xml>"]
    for key, value in fields.items():
        parts.append(f"<{key}><![CDATA[{value}]]></{key}>")
    parts.append("</xml>")
    return "".join(parts)


class Simulator:
    """把「平台推消息」「用户收消息」这两侧都模拟出来。"""

    def __init__(self, scenario: dict, persona_path: Path, db_path: Path,
                 dry_run: bool = False, on_trace=None):
        self.scenario = scenario
        self.crypto = WeComCrypto(SIM_TOKEN, SIM_AES_KEY, SIM_CORP_ID)
        self.platform = FakePlatform()

        config = {
            "corp_id": SIM_CORP_ID,
            "corp_secret": SIM_SECRET,
            "agent_id": SIM_AGENT_ID,
            "token": SIM_TOKEN,
            "encoding_aes_key": SIM_AES_KEY,
            "port": 0,
            "db": str(db_path),
            "persona": str(persona_path),
        }
        self.dispatcher = Dispatcher(config, dry_run=dry_run, on_trace=on_trace)
        # 把客户端的接口根地址指向假平台：其余代码一行不改，
        # 所以被验证的就是部署时会跑的那份代码。
        self.dispatcher.client.base = self.platform.base
        # 令牌缓存也换成独立文件，并清掉可能从共享缓存读到的值。
        # 不隔离的话，上一次运行写的 wecom_token.json 会让这次
        # 一次 gettoken 都不调，测出来的「缓存生效」是假的。
        self.dispatcher.client.cache_path = (
            db_path.parent / f"{db_path.stem}_token.json")
        self.dispatcher.client.forget_token()

        self.callback = ThreadingHTTPServer(
            ("127.0.0.1", 0), make_handler(self.dispatcher))
        self.callback_port = self.callback.server_address[1]
        self._cb_thread = threading.Thread(
            target=self.callback.serve_forever, daemon=True)

        self.results: list[dict] = []

    # ---------- 生命周期 ----------

    def start(self) -> None:
        self.platform.start()
        self._cb_thread.start()

    def stop(self) -> None:
        self.callback.shutdown()
        self.callback.server_close()
        self.platform.stop()
        # 用 close() 而不是 stop()：要连数据库句柄一起释放，
        # 否则临时目录删不掉，Windows 上会留下锁住的文件。
        self.dispatcher.close()

    # ---------- 协议动作 ----------

    def verify_url(self) -> tuple[bool, str]:
        """
        模拟平台的「保存回调地址」动作：发一个加密 echostr，看能否原样解回。

        这一步是企业微信后台点「保存」时真正发生的事。
        它失败，后面所有消息都不会到达——所以单独作为一个验收项。
        """
        ts = str(int(time.time()))
        nonce = "1234567890"
        plain = f"echo-{ts}"
        enc = self.crypto.encrypt(plain)
        sig = self.crypto.signature(ts, nonce, enc)
        query = urllib.parse.urlencode({
            "msg_signature": sig, "timestamp": ts,
            "nonce": nonce, "echostr": urllib.parse.quote(enc),
        })
        url = f"http://127.0.0.1:{self.callback_port}/wecom/callback?{query}"
        try:
            with urllib.request.urlopen(url, timeout=8) as r:
                got = r.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            return False, f"HTTP {exc.code}"
        except Exception as exc:                       # noqa: BLE001
            return False, f"{type(exc).__name__}: {exc}"
        return got == plain, got

    def push(self, fields: dict) -> bool:
        """
        扮演平台把一条消息推给回调服务。

        返回是否被服务端接受（HTTP 200）。是否入队要看 store 去重，
        所以重复推送也会返回 True——这跟真实平台的行为一致。
        """
        inner = build_xml(fields)
        enc = self.crypto.encrypt(inner)
        ts = str(int(time.time()))
        nonce = str(int(time.time() * 1000) % 10**9)
        sig = self.crypto.signature(ts, nonce, enc)
        body = f"<xml><Encrypt><![CDATA[{enc}]]></Encrypt></xml>".encode("utf-8")
        query = urllib.parse.urlencode({
            "msg_signature": sig, "timestamp": ts, "nonce": nonce})
        url = f"http://127.0.0.1:{self.callback_port}/wecom/callback?{query}"
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "text/xml"})
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                return r.status == 200
        except Exception as exc:                       # noqa: BLE001
            self.results.append({"error": f"推送失败：{exc}"})
            return False

    # ---------- 跑场景 ----------

    def run(self, step_each: bool = True) -> dict:
        """
        逐条推送并处理。

        step_each=True 时每条消息都单独跑一轮 Hub，这样「上一条刚回过」
        这类上下文判断才和真实节奏一致；一次性灌进去会让所有消息
        共享同一个处理时刻，把连续对话误判成刷屏。
        """
        turns = self.scenario.get("turns", [])
        base_time = int(time.time()) - sum(
            int(t.get("gap", 1)) for t in turns) * 60
        clock = base_time
        msgs = []

        for i, turn in enumerate(turns):
            # 用场景里的 gap 推进消息时间，这样「连续追问」和「隔天再问」
            # 在刷屏判定里表现不同，跟真实聊天一致。
            clock += int(turn.get("gap", 1)) * 60
            fields = {
                "ToUserName": SIM_CORP_ID,
                "FromUserName": turn.get("from") or "zhangsan",
                "CreateTime": str(clock),
                "MsgType": "text",
                "Content": turn.get("text", ""),
                "MsgId": str(7300000000000000000 + i),
                "AgentID": SIM_AGENT_ID,
            }
            msgs.append(fields)
            self.push(fields)
            if step_each:
                self.dispatcher.run_once()

        if not step_each:
            self.dispatcher.run_once()

        return self.summary(msgs)

    def summary(self, msgs: list[dict]) -> dict:
        hub = self.dispatcher.hub
        return {
            "pushed": len(msgs),
            "accepted": self.dispatcher.adapter.accepted,
            "dropped": self.dispatcher.adapter.dropped,
            "traces": hub.traces,
            "blocked": [r.reason for r in hub.blocked],
            "pending": [{"to": r.conv_id, "text": r.text,
                         "why": r.reason or "待确认"} for r in hub.pending],
            "sent": list(self.platform.sent),
            "token_calls": self.platform.token_calls,
        }


# ---------------------------------------------------------------------------
# 展示与自检
# ---------------------------------------------------------------------------

MARK = {"sent": "已发出", "pending": "待确认", "blocked": "拦截",
        "silent": "沉默", "no_draft": "无草稿"}


def show(result: dict) -> None:
    print("=" * 74)
    print("企业微信全链路模拟（无资质，本地运行，不联网）")
    print("=" * 74)

    print(f"\n推送 {result['pushed']} 条 → 入队 {result['accepted']} 条"
          f"（丢弃 {result['dropped']}）")

    print("\n每条的处理结果：")
    for t in result["traces"]:
        mark = MARK.get(t["final"], t["final"])
        print(f"  [{mark:<4}] {t['sender']:<10} 「{t['text']}」"
              f" 分数 {t.get('necessity')}")
        if t["final"] == "sent":
            print(f"            → {t['draft'][:38]}")
        elif t["final"] == "blocked":
            print(f"            → {t.get('reason') or '边界拦截'}")

    print(f"\n假平台实际收到 {len(result['sent'])} 条消息：")
    for s in result["sent"]:
        print(f"  发给 {s['to']:<10} {s['at']}  {s['content'][:36]}")

    if result["pending"]:
        print(f"\n待人工确认 {len(result['pending'])} 条：")
        for p in result["pending"]:
            print(f"  {p['to']}：{p['text'][:30] or '（无草稿）'}  ← {p['why']}")


def self_check(result: dict, url_ok: bool) -> list[tuple[str, bool, str]]:
    """每项都给出证据，不只是打个勾。"""
    traces = result["traces"]
    finals = [t["final"] for t in traces]
    sent = result["sent"]
    blocked = result["blocked"]

    checks: list[tuple[str, bool, str]] = []

    checks.append((
        "回调地址验证",
        url_ok,
        "加密 echostr 原样解回" if url_ok else "平台「保存」这一步就会失败",
    ))

    checks.append((
        "消息能到达",
        result["accepted"] == result["pushed"] and result["pushed"] > 0,
        f"推送 {result['pushed']} 条，入队 {result['accepted']} 条",
    ))

    checks.append((
        "确实发出去了",
        len(sent) > 0 and "sent" in finals,
        f"假平台收到 {len(sent)} 条，而非只记在本地",
    ))

    checks.append((
        "边界确实拦住了",
        len(blocked) >= 1 and "blocked" in finals,
        f"拦截 {len(blocked)} 条：{'、'.join(blocked[:3]) or '无'}",
    ))

    checks.append((
        "令牌只取一次",
        result["token_calls"] == 1,
        f"gettoken 被调用 {result['token_calls']} 次（缓存生效则应为 1）",
    ))

    return checks


def dedupe_check(sim: Simulator) -> tuple[bool, str]:
    """
    重复推送同一条消息，验证不会回两遍。

    平台在 5 秒内收不到响应会重试三次，MsgId 不变。
    不去重的话，同一句话会被回三遍——这是最容易被用户发现的那种错。
    """
    fields = {
        "ToUserName": SIM_CORP_ID,
        "FromUserName": "zhangsan",
        "CreateTime": str(int(time.time())),
        "MsgType": "text",
        "Content": "在吗在吗在吗",
        "MsgId": "7300000099999999999",
        "AgentID": SIM_AGENT_ID,
    }
    before = sim.dispatcher.adapter.accepted
    sim.push(fields)
    sim.push(fields)
    sim.push(fields)
    accepted = sim.dispatcher.adapter.accepted - before
    return accepted == 1, f"同一 MsgId 推 3 次，入队 {accepted} 条"


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def load_scenario(path: Path) -> dict:
    if not path.exists():
        return {"name": "内置默认", "turns": [
            {"from": "zhangsan", "text": "在吗"},
            {"from": "zhangsan", "text": "这个多少钱"},
            {"from": "zhangsan", "text": "能便宜点吗"},
            {"from": "zhangsan", "text": "多久能到"},
        ]}
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="企业微信全链路模拟器")
    ap.add_argument("--scenario", type=Path, default=DEFAULT_SCENARIO)
    ap.add_argument("--persona", type=Path, default=DEFAULT_PERSONA)
    ap.add_argument("--keep", action="store_true", help="跑完不退出，保留服务")
    ap.add_argument("--dry", action="store_true", help="只判定不发送")
    ap.add_argument("--batch", action="store_true",
                    help="一次性灌入（用于对照：会暴露刷屏判定）")
    args = ap.parse_args(argv)

    scenario = load_scenario(args.scenario)
    persona = args.persona if args.persona.exists() else HERE / "persona.json"

    # 用临时库，避免污染真实部署的 wecom_state.db
    db = HERE / "wecom_sim_state.db"
    if db.exists():
        db.write_bytes(b"")            # 只清空不删除：沙箱环境可能禁删

    sim = Simulator(scenario, persona, db, dry_run=args.dry)
    sim.start()
    try:
        url_ok, echo = sim.verify_url()
        print(f"场景：{scenario.get('name', '未命名')}｜人设：{persona.name}")
        print(f"回调端口 {sim.callback_port}｜假平台端口 {sim.platform.port}")
        print(f"URL 验证：{'通过' if url_ok else '失败'}（回显 {echo[:20]}）")

        result = sim.run(step_each=not args.batch)
        show(result)

        dedupe_ok, dedupe_msg = dedupe_check(sim)

        print("\n" + "=" * 74)
        ok_all = True
        for name, ok, evidence in self_check(result, url_ok):
            ok_all &= ok
            print(f"  {'✓' if ok else '✗'} {name}：{evidence}")
        ok_all &= dedupe_ok
        print(f"  {'✓' if dedupe_ok else '✗'} 重复推送去重：{dedupe_msg}")

        print("=" * 74)
        print("结论：" + ("通过 —— 全链路可用，差的只有真实凭据"
                        if ok_all else "存在问题，见上"))
        if args.keep:
            print(f"\n服务保持运行。健康检查："
                  f"http://127.0.0.1:{sim.callback_port}/health")
            print("Ctrl+C 退出")
            try:
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                print("\n已停止")
        return 0 if ok_all else 1
    finally:
        sim.stop()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
