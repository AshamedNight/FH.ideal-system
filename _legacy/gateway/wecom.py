#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecom.py — 企业微信自建应用渠道

为什么这条路值得先做
--------------------
微信生态里能合法做自动回复的只有两个入口：公众号客服、企业微信自建应用。
个人号（wxid 登录的那种）已经被明确列为外挂，腾讯安全中心的公告原文
把「微信机器人：定时群发、自动回复、自动聊天」写在打击名单里，用的是阶梯式封号。

所以「微信自动回复」这句话，落到实处是企业微信，不是个人微信。
好消息是它比抖音快：不需要应用审核，注册个主体就能建应用。

和抖音的差别
-----------
  抖音：轮询 /video/comment/list/，要 OAuth、要权限审核、有 15 天令牌
  企业微信：平台把消息 POST 给你，要配回调 URL、要解密、5 秒内响应

一个是"我去拉"，一个是"你推我"。所以适配器形状不同：
抖音的 poll 会发 HTTP 请求，企业微信的 poll 只从内存队列取。

5 秒超时怎么处理
----------------
被动回复必须在 5 秒内返回，而判定 + 生成可能超时（接了模型就更慢）。
所以回调只做三件事：验签、解密、入队，然后立刻返回空串——
空串表示"这次不被动回复"，平台不会报错。
真正的回复由后台轮询处理完，再主动调 message/send 发出。

依赖：仅标准库。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from pathlib import Path
from typing import Callable

from gateway import ChannelAdapter, Inbound, Reply, Surface

BASE = "https://qyapi.weixin.qq.com"

Transport = Callable[[str, str, dict], dict]


def _default_transport(method: str, url: str, params: dict | None) -> dict:
    """
    发请求。GET 拼 query，POST 发 JSON——
    企业微信的 send 接口只认 JSON，用 form 编码会返回 40058。
    """
    try:
        if method == "GET":
            full = f"{url}?{urllib.parse.urlencode(params or {})}"
            with urllib.request.urlopen(full, timeout=10) as r:
                return json.loads(r.read().decode("utf-8") or "{}")
        body = json.dumps(params or {}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode("utf-8") or "{}")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        return {"_error": str(exc)}


# ---------------------------------------------------------------------------
# 客户端
# ---------------------------------------------------------------------------

class WeComError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message

    @property
    def recoverable(self) -> bool:
        # 42001 access_token 过期，刷新即可；40001 secret 错误必须改配置
        return self.code in (42001, 40014)


class WeComClient:
    """
    只做两件事：拿 access_token、发消息。

    access_token 有效期 7200 秒，且**每天获取次数有限**。
    每次都去拿会被限流（45009），所以必须缓存，并且提前刷新。
    """

    def __init__(self, corp_id: str, corp_secret: str, agent_id: str,
                 transport: Transport | None = None,
                 cache_path: str | Path | None = None,
                 refresh_margin: float = 300.0,
                 base: str = BASE):
        self.corp_id = corp_id
        self.corp_secret = corp_secret
        self.agent_id = str(agent_id)
        self.transport = transport or _default_transport
        # 接口根地址。默认是官方域名，测试时指向本地假平台——
        # 这样连 HTTP 层一起验证，而不是只替换 transport 绕过网络。
        self.base = base.rstrip("/")
        self.cache_path = Path(cache_path) if cache_path else None
        self.refresh_margin = refresh_margin
        self._token = ""
        self._expires_at = 0.0
        self._load()

    # ---------- 令牌 ----------

    def _load(self) -> None:
        if not self.cache_path or not self.cache_path.exists():
            return
        try:
            d = json.loads(self.cache_path.read_text(encoding="utf-8"))
            if d.get("corp_id") == self.corp_id:
                self._token = str(d.get("token") or "")
                self._expires_at = float(d.get("expires_at") or 0)
        except (json.JSONDecodeError, OSError, TypeError, ValueError):
            self._token, self._expires_at = "", 0.0

    def _save(self) -> None:
        if not self.cache_path:
            return
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(json.dumps({
            "corp_id": self.corp_id,
            "token": self._token,
            "expires_at": self._expires_at,
        }, ensure_ascii=False), encoding="utf-8")

    def forget_token(self) -> None:
        """
        丢掉内存里的令牌，下次调用重新取。

        什么时候要手动调：换了 corp_secret，或者缓存文件被别的实例写脏了。
        不丢的话会一直拿着一个失效令牌，症状是「每条都发送失败，
        但 gettoken 一次都不调」——很难从日志里看出来。
        """
        self._token, self._expires_at = "", 0.0

    def ensure_token(self) -> str:
        if self._token and time.time() < self._expires_at - self.refresh_margin:
            return self._token

        resp = self.transport("GET", f"{self.base}/cgi-bin/gettoken",
                              {"corpid": self.corp_id,
                               "corpsecret": self.corp_secret})
        if "_error" in resp:
            raise WeComError(-1, f"取令牌失败：{resp['_error']}")
        code = int(resp.get("errcode") or 0)
        if code:
            # 40001/40013 类是配置错，不是网络问题，重试没有意义
            raise WeComError(code, str(resp.get("errmsg") or "取令牌失败"))

        self._token = str(resp.get("access_token") or "")
        if not self._token:
            raise WeComError(-1, "返回里没有 access_token")
        self._expires_at = time.time() + float(resp.get("expires_in") or 7200)
        self._save()
        return self._token

    # ---------- 发消息 ----------

    def send_text(self, to_user: str, content: str) -> bool:
        """
        给一个成员发文本。to_user 用 userid，多个用 | 分隔。

        注意：自建应用只能发给**企业成员**。发给外部客户要走「微信客服」
        或「客户联系」，那是另一套接口和另一套权限，不要混。
        """
        text = (content or "").strip()[:2048]      # 平台上限 2048 字节
        if not text or not to_user:
            return False
        token = self.ensure_token()
        resp = self.transport(
            "POST", f"{self.base}/cgi-bin/message/send?access_token={token}", {
                "touser": to_user,
                "msgtype": "text",
                "agentid": int(self.agent_id) if self.agent_id.isdigit() else self.agent_id,
                "text": {"content": text},
                "safe": 0,
            })
        if "_error" in resp:
            return False
        code = int(resp.get("errcode") or 0)
        if code and code not in (0,):
            # 令牌过期时清空缓存，下一次调用会重新取
            if code in (40014, 42001):
                self._token, self._expires_at = "", 0.0
            return False
        return True


# ---------------------------------------------------------------------------
# 适配器
# ---------------------------------------------------------------------------

class WeComAdapter(ChannelAdapter):
    """
    企业微信适配器。

    与抖音不同，poll 不发网络请求——消息由平台 POST 过来，
    回调线程调用 push() 入队，poll() 只负责取走。
    """

    name = "wecom"
    # 企业微信是官方接口，技术上能发；该不该自己发由 Hub 的闸门决定
    auto_send_allowed = True

    def __init__(self, client: WeComClient, max_queue: int = 200):
        self.client = client
        self._inbox: deque[Inbound] = deque(maxlen=max_queue)
        self.last_errors: list[str] = []
        self.accepted = 0        # 回调收到的消息数
        self.dropped = 0         # 因为队列满被丢掉的

    def push(self, msg: Inbound) -> None:
        """回调线程调用。队列满时丢最老的，并记一笔。"""
        if len(self._inbox) == self._inbox.maxlen:
            self.dropped += 1
        self._inbox.append(msg)
        self.accepted += 1

    def poll(self) -> list[Inbound]:
        out = list(self._inbox)
        self._inbox.clear()
        return out

    def send(self, reply: Reply) -> bool:
        if not reply.text.strip():
            return False
        ok = self.client.send_text(reply.conv_id, reply.text)
        if not ok:
            self.last_errors.append(f"发送到 {reply.conv_id} 失败")
        return ok
