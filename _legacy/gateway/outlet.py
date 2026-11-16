#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
outlet.py — 演示投递：把回复发到真实可达的信道

为什么需要这一层
----------------
抖音评论区回复要审核。审核没下来之前，整条链路走到「草稿」就断了，
演示时只能说「你看，它生成了这句话」——这句话到底能不能发出去、
发出去长什么样、别人收到是什么感觉，全都验证不了。

这个模块解决的就是这一段：**把发送终点从抖音评论区，换成你自己能收到的地方**。

判定是真的、生成是真的、投递是真的、到达是真的，只有终点不是抖音。
演示时手机能收到消息，这就不是 PPT，是跑起来的系统。

三种出口
--------
  LocalOutlet   写 JSONL，控制台和终端能看。零配置，演示兜底。
  WebhookOutlet 企业微信 / 钉钉 / 飞书 的群机器人。**不需要任何审核**，
                建个群、加个机器人、拿一个 URL 就行。
  OneBotOutlet  通过 NapCat 发到自己的 QQ 群。需要本地起 OneBot 服务。

明确不做的事
------------
不把消息发到任何真实的抖音用户那里。没有审核就等于没有发送权限，
绕过它去发属于违反平台规则，不是技术问题。

依赖：仅标准库。网络访问可注入，所以不联网也能测。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

Transport = Callable[[str, dict], dict]


@dataclass
class Delivery:
    """一次投递的结果。"""
    ok: bool
    outlet: str
    detail: str = ""
    at: float = field(default_factory=time.time)

    def __str__(self) -> str:
        mark = "✓" if self.ok else "✗"
        return f"[{mark} {self.outlet}] {self.detail}"


class Outlet(ABC):
    """投递出口。只管把一段文字送出去，不做任何业务判断。"""

    name: str = "base"

    @abstractmethod
    def deliver(self, text: str, meta: dict | None = None) -> Delivery:
        ...


# ---------------------------------------------------------------------------
# 本地文件
# ---------------------------------------------------------------------------

class LocalOutlet(Outlet):
    """
    写到 JSONL。演示时的兜底出口——不依赖任何外部服务，
    跑起来就一定能看到结果，不会因为网络问题让演示中断。
    """

    name = "local"

    def __init__(self, path: str | Path = "demo_outbox.jsonl"):
        self.path = Path(path)
        self.items: list[dict] = []

    def deliver(self, text: str, meta: dict | None = None) -> Delivery:
        rec = {"text": text, "at": time.time(), **(meta or {})}
        self.items.append(rec)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except OSError as exc:
            return Delivery(False, self.name, f"写入失败：{exc}")
        return Delivery(True, self.name, f"已写入 {self.path.name}")


# ---------------------------------------------------------------------------
# 群机器人
# ---------------------------------------------------------------------------

class WebhookOutlet(Outlet):
    """
    企业微信 / 钉钉 / 飞书 的群机器人。

    这是「没有审核也能真实发送」的关键：这类机器人是平台官方支持的，
    建群 → 添加机器人 → 复制 URL，三分钟就能有。不需要开发者账号，
    不需要资质，也不需要应用审核。

    三种平台的报文结构不同，用 style 区分：
      wecom    {"msgtype":"text","text":{"content": ...}}
      dingtalk 同上
      feishu   {"msg_type":"text","content":{"text": ...}}
    """

    name = "webhook"

    def __init__(self, url: str, style: str = "wecom",
                 prefix: str = "", timeout: float = 8.0,
                 transport: Transport | None = None):
        if style not in ("wecom", "dingtalk", "feishu"):
            raise ValueError(f"不支持的 style：{style}")
        self.url = url
        self.style = style
        self.prefix = prefix
        self.timeout = timeout
        self.transport = transport or self._default_transport

    @staticmethod
    def _default_transport(url: str, payload: dict) -> dict:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                raw = r.read().decode("utf-8") or "{}"
            return json.loads(raw)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            return {"_error": str(exc)}

    def build_payload(self, text: str) -> dict:
        content = f"{self.prefix}{text}" if self.prefix else text
        if self.style == "feishu":
            return {"msg_type": "text", "content": {"text": content}}
        return {"msgtype": "text", "text": {"content": content}}

    def deliver(self, text: str, meta: dict | None = None) -> Delivery:
        if not text.strip():
            return Delivery(False, self.name, "内容为空")
        resp = self.transport(self.url, self.build_payload(text))

        if "_error" in resp:
            return Delivery(False, self.name, f"请求失败：{resp['_error']}")

        # 三个平台的成功码不统一：企微/钉钉 errcode=0，飞书 code=0 或 StatusCode=0。
        # 不判状态码只看有没有返回，会把「token 失效」当成成功。
        code = resp.get("errcode", resp.get("code", resp.get("StatusCode", 0)))
        if code not in (0, None):
            msg = resp.get("errmsg") or resp.get("msg") or str(resp)[:120]
            return Delivery(False, self.name, f"平台返回错误 {code}：{msg}")
        return Delivery(True, self.name, f"已送达 {self.style} 群")


# ---------------------------------------------------------------------------
# QQ 群（走 OneBot / NapCat）
# ---------------------------------------------------------------------------

class OneBotOutlet(Outlet):
    """
    通过 NapCat / Lagrange 的 OneBot HTTP 接口发到自己的 QQ 群。

    比群机器人多一点准备：要起一个 OneBot 服务并登录一个小号。
    好处是能发到 QQ 群，展示场景更接近「真的在聊天」。
    """

    name = "onebot"

    def __init__(self, api_base: str, group_id: str, token: str = "",
                 timeout: float = 8.0, transport: Transport | None = None):
        self.api_base = api_base.rstrip("/")
        self.group_id = str(group_id)
        self.token = token
        self.timeout = timeout
        self.transport = transport or self._default_transport

    def _default_transport(self, url: str, payload: dict) -> dict:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(url, data=body, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                return json.loads(r.read().decode("utf-8") or "{}")
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            return {"_error": str(exc)}

    def deliver(self, text: str, meta: dict | None = None) -> Delivery:
        if not text.strip():
            return Delivery(False, self.name, "内容为空")
        resp = self.transport(f"{self.api_base}/send_group_msg", {
            "group_id": self.group_id,
            "message": text,
        })
        if "_error" in resp:
            return Delivery(False, self.name, f"请求失败：{resp['_error']}")
        if resp.get("status") == "failed" or resp.get("retcode", 0) != 0:
            return Delivery(False, self.name, f"OneBot 返回失败：{str(resp)[:120]}")
        return Delivery(True, self.name, f"已发到 QQ 群 {self.group_id}")


# ---------------------------------------------------------------------------
# 组合
# ---------------------------------------------------------------------------

class CompositeOutlet(Outlet):
    """
    同时投递到多个出口。

    设计取舍：任一成功就算成功，但会把每个出口的结果都记下来。
    为什么不是「全部成功才算成功」——演示时本地文件几乎不会失败，
    如果因为群机器人 URL 过期就让整次投递判定失败，会把真正的问题淹没。
    """

    name = "composite"

    def __init__(self, outlets: list[Outlet]):
        self.outlets = list(outlets)

    def deliver(self, text: str, meta: dict | None = None) -> Delivery:
        results = [o.deliver(text, meta) for o in self.outlets]
        ok = any(r.ok for r in results)
        detail = "｜".join(str(r) for r in results) or "没有配置任何出口"
        return Delivery(ok, self.name, detail)


def build_outlet(config: dict) -> Outlet:
    """
    按配置装配出口。配置缺省时退回本地文件——
    演示不能因为「没填 webhook」就跑不起来。
    """
    config = config or {}
    kind = config.get("kind") or "local"
    if kind == "webhook":
        return WebhookOutlet(
            config["url"],
            style=config.get("style", "wecom"),
            prefix=config.get("prefix", ""),
        )
    if kind == "onebot":
        return OneBotOutlet(
            config["api_base"], config["group_id"],
            token=config.get("token", ""))
    if kind == "composite":
        subs = [build_outlet(c) for c in config.get("outlets", [])]
        return CompositeOutlet(subs)
    return LocalOutlet(config.get("path", "demo_outbox.jsonl"))
