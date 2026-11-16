#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gateway.py — 多渠道 AI 回复网关（最小可运行骨架）

设计目标
--------
把"平台连接"和"回复逻辑"彻底解耦：
  · 每个平台写一个 Adapter，只负责 收消息 / 发消息
  · 人设、风控、生成逻辑全部在 Hub 里，与平台无关
  · 新增平台 = 新增一个 Adapter 类 + 一行注册，不改核心

依赖：仅标准库（Python 3.10+）。
用法：
  python gateway.py demo         # 用模拟适配器跑一遍完整链路
  python gateway.py onebot      # 连接真实的 OneBot v11 实现（NapCat / Lagrange）

OneBot 连接示例（先启动 NapCat，开放 HTTP 与反向 WS）：
  set ONEBOT_API=http://127.0.0.1:3000
  set ONEBOT_TOKEN=你的token
  python gateway.py onebot
"""

from __future__ import annotations

import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Iterable, Protocol

from persona_schema import PersonaProfile, load_profile
from textutil import coverage, lcs_len as _lcs_len


# ---------------------------------------------------------------------------
# 1. 统一消息模型
# ---------------------------------------------------------------------------

class Surface(str, Enum):
    """
    消息出现在什么场合。这个字段决定了两件事：
      1. 回复必要性怎么打分（私信必答 / 群聊克制 / 评论更克制）
      2. 风控有多严（公开场合一次回错，所有人都看得见）

    它不决定"怎么连接平台"，那是适配器的事。
    """
    PRIVATE = "private"     # 一对一私信
    GROUP = "group"         # 群聊
    COMMENT = "comment"     # 公开评论区（笔记 / 视频下）


@dataclass
class Inbound:
    """从任意平台归一化后的入站消息。"""
    channel: str            # 平台标识：qq / wechat / douyin / xhs
    conv_id: str            # 会话标识（群号、用户 id、笔记 id）
    sender: str             # 发送者昵称
    text: str               # 纯文本内容
    ts: float = field(default_factory=time.time)
    raw: dict = field(default_factory=dict)
    surface: Surface = Surface.PRIVATE
    # 评论场景的父级标识：note_id（小红书）或 item_id（抖音）。
    # 回复要挂在这个 thread 下，否则会变成一条新的顶级评论。
    thread: str = ""
    # 是否被直接点名（@ 或回复了你的评论）。
    # 这个字段原来在 Hub 里被写死成 False，等于"被 @"这个场景从来没生效过。
    # 判定层需要它，因为被点名和"路过看到"是两种完全不同的应答义务。
    is_mentioned: bool = False
    # 被回复的父评论 id（二级评论场景）。空表示这是一条顶级评论。
    parent_id: str = ""


@dataclass
class Reply:
    """要发出的回复。人工接管时 approved=False。"""
    channel: str
    conv_id: str
    text: str
    approved: bool = False      # 是否已通过人工确认
    reason: str = ""            # 未自动发送的原因
    # 评论场景必须有：回复要挂在某个 item_id / note_id 下面，
    # 否则会变成一条新的顶级评论，而不是回给那个人。
    thread: str = ""
    # 对方昵称与原话。人工确认时必须看到"谁在说什么"，
    # 只给一个 comment_id 在人数多的时候根本没法判断。
    sender: str = ""
    incoming: str = ""


# ---------------------------------------------------------------------------
# 2. 渠道适配器接口
# ---------------------------------------------------------------------------

class ChannelAdapter(ABC):
    """所有平台适配器的统一接口。只做收发，不含任何业务逻辑。"""

    name: str = "base"
    # 该渠道是否允许全自动发送。False 表示必须人工确认。
    auto_send_allowed: bool = False

    @abstractmethod
    def poll(self) -> list[Inbound]:
        """拉取新消息。返回空列表表示暂时没有。"""

    @abstractmethod
    def send(self, reply: Reply) -> bool:
        """真正把消息发出去。返回是否成功。"""


class MockAdapter(ChannelAdapter):
    """演示用适配器：从预置脚本里按顺序吐消息，发送只打印。"""

    name = "mock"
    auto_send_allowed = True

    def __init__(self, script: list[Inbound]) -> None:
        self._script = list(script)
        self.sent: list[Reply] = []

    def poll(self) -> list[Inbound]:
        if not self._script:
            return []
        return [self._script.pop(0)]

    def send(self, reply: Reply) -> bool:
        self.sent.append(reply)
        return True


class OneBotAdapter(ChannelAdapter):
    """
    OneBot v11 适配器（QQ 侧）。对接 NapCat / Lagrange / LLOneBot 均可。

    这里只实现了"发消息"与"手动拉取"，事件接收建议用正向/反向 WebSocket，
    避免轮询丢消息。骨架阶段先保证接口形状正确。
    """

    name = "qq"
    auto_send_allowed = True   # OneBot 非官方协议，生产环境应改为 False

    def __init__(self, api_base: str, token: str = "") -> None:
        self.api_base = api_base.rstrip("/")
        self.token = token
        self._inbox: deque[Inbound] = deque()

    def _call(self, action: str, payload: dict) -> dict:
        url = f"{self.api_base}/{action}"
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url, data=data,
            headers={"Content-Type": "application/json",
                     **({"Authorization": f"Bearer {self.token}"} if self.token else {})},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            print(f"[onebot] 调用 {action} 失败：{exc}")
            return {}

    def poll(self) -> list[Inbound]:
        # 真实场景应由 WS 事件推入 self._inbox；此处留出接入点。
        out, self._inbox = list(self._inbox), deque()
        return out

    def push_event(self, event: dict) -> None:
        """供 WebSocket 回调调用，把 OneBot 事件转成 Inbound。"""
        if event.get("post_type") != "message":
            return
        text = event.get("raw_message", "") or event.get("message", "")
        if isinstance(text, list):  # 消息段数组
            text = "".join(seg.get("data", {}).get("text", "")
                           for seg in text if seg.get("type") == "text")
        self._inbox.append(Inbound(
            channel=self.name,
            conv_id=str(event.get("group_id") or event.get("user_id")),
            sender=event.get("sender", {}).get("nickname", "unknown"),
            text=str(text),
            raw=event,
            # group_id 存在即群消息，否则是单聊
            surface=Surface.GROUP if event.get("group_id") else Surface.PRIVATE,
        ))

    def send(self, reply: Reply) -> bool:
        is_group = reply.conv_id.isdigit() and len(reply.conv_id) > 6
        action = "send_group_msg" if is_group else "send_private_msg"
        key = "group_id" if is_group else "user_id"
        res = self._call(action, {key: int(reply.conv_id), "message": reply.text})
        return bool(res.get("status") == "ok" or res.get("retcode") == 0)


# ---------------------------------------------------------------------------
# 3. 人设（跨渠道共享的单一实体）
# ---------------------------------------------------------------------------

@dataclass
class Persona:
    """
    人设是平台无关的。示范语料（few_shot）的权重高于任何形容词描述，
    因为决定回复像不像人的是样例，不是"语气活泼"这种指令。

    profile 是「与行业无关的那一层」：意图词表、边界、兜底策略。
    换商品时改 knowledge / few_shot；换赛道时改 profile.intents。
    判定逻辑只认意图标签，不认商品，所以代码不用动。
    """
    name: str
    identity: str
    tone: dict
    knowledge: list[dict]
    boundaries: list[str]
    few_shot: list[dict]
    taboo_words: list[str]
    # 旧配置没有这一段，加载时会从旧字段迁移一个默认的出来
    profile: PersonaProfile = field(default_factory=PersonaProfile)

    @classmethod
    def load(cls, path: str | Path) -> "Persona":
        data, profile = load_profile(path)
        return cls(
            name=data.get("name", ""),
            identity=data.get("identity", ""),
            tone=data.get("tone", {}),
            knowledge=data.get("knowledge", []),
            boundaries=data.get("boundaries", []),
            few_shot=data.get("few_shot", []),
            taboo_words=data.get("taboo_words", []),
            profile=profile,
        )


# ---------------------------------------------------------------------------
# 4. 风控闸门（纯函数，便于单测）
# ---------------------------------------------------------------------------

DECISION_AUTO = "auto"          # 可自动发送
DECISION_REVIEW = "review"      # 需人工确认
DECISION_BLOCK = "block"        # 禁止回复，直接转人工

# 宽松匹配允许关键词各字之间最多隔几个字符。
# 中文里同一个意思的写法太多（"打折" vs "打个折"），纯字面包含会大量漏判。
_LOOSE_GAP = 2


def _loose_match(text: str, kw: str) -> bool:
    """
    先做精确包含，再退化为"各字按序出现且间隔不超过 _LOOSE_GAP"。

    这个函数刻意偏保守（宁可误报）：漏判的代价是把不该发的话自动发出去，
    误判的代价只是多一条转人工。两者的后果不对等，所以宁可多拦。

    注意：这是关键词层面的权宜之计。真实业务里用户会说"能不能少点""这个价有点高"
    "我朋友上个月买好像便宜些"，这些靠词表永远补不全。生产环境应当在前面加一层
    意图分类（小模型或 LLM 判断"是否涉及议价/退款/投诉"），关键词只做兜底。
    """
    if kw in text:
        return True
    if len(kw) < 2:
        return False
    pattern = f".{{0,{_LOOSE_GAP}}}".join(re.escape(ch) for ch in kw)
    return re.search(pattern, text) is not None


def check(text: str, persona: Persona, recent: int, limit: int,
          surface: Surface = Surface.PRIVATE,
          allow_public_auto: bool = False) -> tuple[str, str]:
    """
    判定一条消息能否自动回复。返回 (结论, 原因)。

    刻意做成纯函数：不读全局状态、不发网络请求，方便写单测。
    判定顺序有意义——频控优先于边界，因为"这分钟已经发太多了"是渠道级问题，
    换一条消息也解决不了；边界是消息级问题。
    """
    # 1) 频控：同一渠道一分钟内超过上限，全部转人工
    if recent >= limit:
        return DECISION_REVIEW, f"触发频控（每分钟已发 {recent} 条，上限 {limit}）"

    # 2) 边界命中：议价 / 退款 / 投诉 / 承诺
    for kw in persona.boundaries:
        if _loose_match(text, kw):
            return DECISION_BLOCK, f"命中人设边界：{kw}"

    # 3) 违禁词
    for kw in persona.taboo_words:
        if _loose_match(text, kw):
            return DECISION_BLOCK, f"命中违禁词：{kw}"

    # 4) 公开评论区：私信回错只影响一个人，评论回错所有人都看得见。
    #    所以默认强制人工确认，要全自动必须显式打开 allow_public_auto。
    if surface is Surface.COMMENT and not allow_public_auto:
        return DECISION_REVIEW, "公开评论区，默认需人工确认"

    return DECISION_AUTO, ""


# ---------------------------------------------------------------------------
# 5. 回复引擎
# ---------------------------------------------------------------------------

class ReplyEngine(Protocol):
    def generate(self, msg: Inbound, persona: Persona) -> str: ...


class MockEngine:
    """
    不调外部模型也能跑的引擎：先在示范语料里找最接近的一条，再套知识条目。
    这不是"效果差一点"，而是刻意的设计——它证明了 few_shot 的检索价值：
    只靠 12 组样例，回复就已经能靠住人设。
    """

    def generate(self, msg: Inbound, persona: Persona) -> str:
        text = msg.text.strip()

        # 1) 在示范语料里找最接近的一条。
        #
        # 这里必须用覆盖率而不是 LCS 绝对值：中文里"这个"两个字就能凑出 LCS=2，
        # 于是"这个在哪买"会匹配到"这个桌子承重多少"，回复出承重参数——答非所问。
        # 归一化之后，短句要求几乎完全一致，长句才允许部分重合。
        #
        # 同覆盖率时取更短的语料：短句更精确。
        # 「这个多少钱」对「多少钱」和「这个桌子承重多少」都是 100%，
        # 不设这个次序就会把问价的答成承重参数——同样的坑第二次踩到。
        best, best_cov, best_len = None, 0.0, 10**6
        for pair in persona.few_shot:
            q = pair["q"]
            cov = coverage(text, q)
            # 3 字以内的语料（"在吗"）只能精确命中，否则会被大量长句抢走
            if len(q) <= 3 and cov < 0.99:
                continue
            if cov > best_cov or (abs(cov - best_cov) < 1e-9 and len(q) < best_len):
                best, best_cov, best_len = pair, cov, len(q)
        if best and best_cov >= 0.55:
            return best["a"]

        # 2) 知识条目关键词命中
        for item in persona.knowledge:
            if any(k in text for k in item.get("keys", [])):
                return _apply_tone(item["answer"], persona)

        # 3) 兜底：不硬编，交给人
        return ""


class LLMEngine:
    """接 OpenAI 兼容接口。不配 key 时自动降级到 MockEngine。"""

    def __init__(self, base_url: str, api_key: str, model: str,
                 fallback: ReplyEngine | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.fallback = fallback or MockEngine()

    def generate(self, msg: Inbound, persona: Persona) -> str:
        if not self.api_key:
            return self.fallback.generate(msg, persona)

        system = _build_system_prompt(persona)
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": msg.text},
            ],
            "temperature": 0.8,
            "max_tokens": 300,
        }
        req = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {self.api_key}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=40) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"].strip()
        except (urllib.error.URLError, TimeoutError, KeyError,
                json.JSONDecodeError, IndexError) as exc:
            print(f"[llm] 调用失败，降级到规则引擎：{exc}")
            return self.fallback.generate(msg, persona)


def _build_system_prompt(persona: Persona) -> str:
    """
    提示词的结构顺序很重要：先身份，再边界，最后放示范语料。
    把 few_shot 放在末尾，是因为靠近 user 消息的样例对风格影响最大。
    """
    tone = persona.tone
    lines = [
        f"你是{persona.name}。{persona.identity}",
        "",
        "说话方式：",
        f"- 正式度 {tone.get('formal', 50)}/100（越低越口语）",
        f"- 句长偏好 {tone.get('length', 50)}/100（越低越短句）",
        f"- emoji 频率 {tone.get('emoji', 30)}/100",
    ]
    if tone.get("catchphrases"):
        lines.append(f"- 常用口头禅：{'、'.join(tone['catchphrases'])}")

    if persona.knowledge:
        lines.append("")
        lines.append("你可以引用的事实（不要编造以外的东西）：")
        for item in persona.knowledge:
            lines.append(f"- {item['topic']}：{item['answer']}")

    if persona.boundaries:
        lines.append("")
        lines.append(f"禁止回应以下话题，遇到就直接说稍后回复：{'、'.join(persona.boundaries)}")

    if persona.few_shot:
        lines.append("")
        lines.append("以下是你过去真实的回复方式，严格模仿这个语感：")
        for pair in persona.few_shot[:10]:
            lines.append(f"对方：{pair['q']}")
            lines.append(f"你：{pair['a']}")

    lines.append("")
    lines.append("只输出回复正文，不要加引号、不要解释。")
    return "\n".join(lines)


def _apply_tone(text: str, persona: Persona) -> str:
    """按 emoji 偏好做轻量修饰。"""
    em = persona.tone.get("emoji", 30)
    if em >= 60 and not re.search(r"[\U0001F300-\U0001FAFF]", text):
        return text + " 😊"
    if em < 15:
        return re.sub(r"[\U0001F300-\U0001FAFF]", "", text).strip()
    return text


# ---------------------------------------------------------------------------
# 6. Hub：把适配器、人设、闸门、引擎编排起来
# ---------------------------------------------------------------------------

class Hub:
    def __init__(self, persona: Persona, engine: ReplyEngine,
                 rate_limit: int = 6, human_delay: tuple[float, float] = (0.0, 0.0),
                 dry_run: bool = False, necessity: bool = False,
                 allow_public_auto: bool = False,
                 autopilot: bool = False,
                 auto_cfg=None, credit=None, store=None) -> None:
        self.persona = persona
        self.engine = engine
        self.rate_limit = rate_limit
        self.human_delay = human_delay
        self.dry_run = dry_run
        # 是否启用「回复必要性」判定。关闭时退化为"来一条回一条"，
        # 这是骨架的默认行为，便于对照观察差异。
        self.necessity = necessity
        # 公开评论区全自动（旧的全局开关）。保留它，但更推荐 autopilot：
        # 全开等于放弃所有判断，分级放权才能既自动又不失控。
        self.allow_public_auto = allow_public_auto
        # 分级放权：按话术风险 + 置信度 + 信任额度决定能否自动发。
        self.autopilot = autopilot
        if autopilot:
            from autopilot import AutopilotConfig, Credit
            self.auto_cfg = auto_cfg or AutopilotConfig()
            self.credit = credit or Credit()
        else:
            self.auto_cfg = auto_cfg
            self.credit = credit
        self._auto_sent: deque[float] = deque()   # 自动发送时间戳，用于小时上限
        # 一个适配器可能同时服务多个渠道（例如 Mock 同时模拟 qq/wechat/xhs，
        # 又如一个多开实例服务多个号）。所以适配器列表与"渠道→适配器"映射分开维护：
        # 按 name 索引会让同类型适配器互相覆盖，审批时也会找不到对应渠道。
        self.adapters: list[ChannelAdapter] = []
        self.by_channel: dict[str, ChannelAdapter] = {}
        self._recent: dict[str, deque[float]] = defaultdict(deque)
        self._last_self: dict[str, float] = {}          # 上次自己在该渠道发言的时间
        self._burst: dict[str, deque[float]] = defaultdict(deque)  # 会话消息密度
        self.pending: list[Reply] = []      # 待人工确认的队列
        self.blocked: list[Reply] = []      # 被边界拦下的
        self.silent: list[tuple[Inbound, float, list[str]]] = []   # 判定为不回复的
        self.log: list[str] = []
        # 每条消息的完整决策链。CLI 里用不上，但本地控制台需要显示
        # "为什么这条被拦了"——排查线上问题时，光看结论不够。
        self.traces: list[dict] = []

        # 持久层。传了 store 就把队列、已发送、信任额度落到 SQLite；
        # 不传则退回纯内存（测试和演示不需要落盘）。
        # 这是"后台"和"演示"的分界线：进程重启后队列还在，才叫后台。
        self.store = store
        self._pending_ids: list[int] = []
        if store is not None:
            self._restore()

    def _restore(self) -> None:
        """从持久层恢复未处理完的队列与信任额度。"""
        rows = self.store.load_pending()
        self._pending_ids = [qid for qid, _ in rows]
        self.pending = [r for _, r in rows]

        snap = self.store.get_kv("credit")
        if snap and self.credit is not None:
            self.credit.approvals = int(snap.get("approvals", 0))
            self.credit.rejections = int(snap.get("rejections", 0))

    def _enqueue(self, reply: Reply) -> None:
        """
        入队。有持久层时同步落盘——重启后运营还能看到这批草稿。

        pending 与 _pending_ids 必须严格平行：前者给调用方按下标取，
        后者用来更新持久层。分开维护是因为列表下标不是数据库主键。
        """
        self.pending.append(reply)
        if self.store is not None:
            self._pending_ids.append(self.store.enqueue(reply))
        else:
            self._pending_ids.append(0)

    def _save_credit(self) -> None:
        if self.store is not None and self.credit is not None:
            self.store.set_kv("credit", self.credit.snapshot())

    def register(self, adapter: ChannelAdapter,
                 channels: Iterable[str] | None = None) -> None:
        """注册适配器。channels 省略时用 adapter.name 作为渠道标识。"""
        self.adapters.append(adapter)
        for ch in (channels if channels is not None else [adapter.name]):
            self.by_channel[ch] = adapter

    def _rate_used(self, channel: str) -> int:
        q = self._recent[channel]
        now = time.time()
        while q and now - q[0] > 60:
            q.popleft()
        return len(q)

    def _auto_sent_last_hour(self) -> int:
        q = self._auto_sent
        now = time.time()
        while q and now - q[0] > 3600:
            q.popleft()
        return len(q)

    def _conv_key(self, msg: Inbound) -> str:
        """
        刷屏判定的粒度。

        评论按"哪条笔记/视频"分组：同一条内容下连发三条才是刷屏，
        在不同内容下各回一条不是。原来按渠道分组，会把正常的分批回复
        误判成刷屏——8 条评论瞬间涌入时，后面 7 条全被降权。
        """
        if msg.surface is Surface.COMMENT and msg.thread:
            return f"{msg.channel}:{msg.thread}"
        return msg.channel

    def _auto_gate(self, decision: str, adapter: ChannelAdapter,
                   trace: dict, draft: str) -> tuple[bool, list[str]]:
        """
        决定这句回复能不能不经人工直接发出。

        三层依次收紧：
          1. 风控结论（消息层面）
          2. 渠道能力（这句能不能发出去）
          3. 分级放权（这条该不该由系统自己发）
        """
        if decision == DECISION_REVIEW:
            return False, ["风控要求人工确认"]
        if not adapter.auto_send_allowed:
            return False, ["该渠道不支持自动发送"]
        if not self.autopilot:
            return True, ["未启用分级放权，直接放行"]

        from autopilot import decide as ap_decide

        # 置信度来自必要性判定。没启用 necessity 时它是 0，
        # autopilot 会一直退回队列——这是对的：没有置信度就不该自动发。
        confidence = float(trace.get("necessity") or 0.0)
        action, reasons = ap_decide(
            draft, confidence, self.credit, self.auto_cfg,
            self._auto_sent_last_hour(),
        )
        return action == "auto", reasons

    def step(self) -> int:
        """跑一轮：所有适配器拉取 → 判定 → 生成 → 发送/挂起。返回处理条数。"""
        total = 0
        for adapter in self.adapters:
            for msg in adapter.poll():
                total += 1
                self._handle(adapter, msg)
        return total

    def _handle(self, adapter: ChannelAdapter, msg: Inbound) -> None:
        trace = {
            "channel": msg.channel,
            "sender": msg.sender,
            "text": msg.text,
            "surface": msg.surface.value,
            "thread": msg.thread,
            "guard": "", "guard_reason": "",
            "necessity": None, "necessity_action": "", "necessity_reasons": [],
            "draft": "", "final": "", "auto_gate": None,
        }

        def done(result: str) -> None:
            trace["final"] = result
            self.traces.append(trace)

        decision, reason = check(
            msg.text, self.persona,
            recent=self._rate_used(msg.channel),
            limit=self.rate_limit,
            surface=msg.surface,
            # autopilot 开启时，公开场合不再一律强制人工——
            # 由 autopilot 按话术风险 + 置信度 + 信任额度做更细的判定。
            allow_public_auto=self.allow_public_auto or self.autopilot,
        )
        trace["guard"], trace["guard_reason"] = decision, reason

        if decision == DECISION_BLOCK:
            self.blocked.append(Reply(msg.channel, msg.conv_id, "", False, reason,
                                      thread=msg.thread,
                                      sender=msg.sender, incoming=msg.text))
            self.log.append(f"[拦截] {msg.channel}/{msg.sender}：{reason}")
            done("blocked")
            return

        # 先过边界，再过必要性：一条要退款的消息不应该被"沉默"吞掉，
        # 它必须留下拦截记录，否则风控告警会漏事件。
        if self.necessity:
            from necessity import Context, judge   # 局部导入，避免模块循环引用

            # 用消息时间而不是处理时间：回放历史评论、补处理积压消息时，
            # 处理时刻都是"现在"，会把一批正常间隔的消息判成连续轰炸。
            now = msg.ts
            key = self._conv_key(msg)
            q = self._burst[key]
            q.append(now)
            while q and now - q[0] > 30:
                q.popleft()

            last = self._last_self.get(key)
            ctx = Context(
                surface=msg.surface.value,
                is_mentioned=msg.is_mentioned,
                self_said_ago=(now - last) if last is not None else 1e9,
                group_burst=len(q),
            )
            j = judge(msg, ctx, self.persona)
            trace["necessity"] = j.score
            trace["necessity_action"] = j.action
            trace["necessity_reasons"] = list(j.reasons)
            if j.action != "reply":
                self.silent.append((msg, j.score, j.reasons))
                self.log.append(
                    f"[沉默] {msg.channel}/{msg.sender} 分数 {j.score}：{j.reasons[0]}"
                )
                done("silent")
                return

        draft = self.engine.generate(msg, self.persona)
        trace["draft"] = draft
        if not draft.strip():
            self._enqueue(Reply(msg.channel, msg.conv_id, "", False,
                                "引擎无合适回复", thread=msg.thread,
                                sender=msg.sender, incoming=msg.text))
            self.log.append(f"[待人工] {msg.channel}/{msg.sender}：引擎给不出草稿")
            done("no_draft")
            return

        reply = Reply(msg.channel, msg.conv_id, draft, thread=msg.thread,
                      sender=msg.sender, incoming=msg.text)

        # 自动发送准入：渠道能力 + 风控结论 + （启用时）分级放权
        allowed, why = self._auto_gate(decision, adapter, trace, draft)
        trace["auto_gate"] = {"allowed": allowed, "reasons": why}
        if not allowed:
            self._enqueue(reply)
            self.log.append(f"[待确认] {msg.channel}/{msg.sender} → {draft[:28]}…")
            done("pending")
            return

        reply.approved = True
        if self.human_delay[1] > 0:
            time.sleep(random.uniform(*self.human_delay))
        sent = True
        if not self.dry_run:
            sent = adapter.send(reply)

        if not sent:
            # 发送失败不能当作已发。原来的写法直接往下走，
            # 结果是"日志显示已发送、用户根本没收到、而且没人知道"。
            # 正确做法是退回队列，让它在控制台里继续可见。
            reply.approved = False
            self._enqueue(reply)
            self.log.append(f"[发送失败] {msg.channel}/{msg.sender} → 已退回待确认")
            done("pending")
            return

        if self.store is not None:
            self.store.record_sent(reply, auto=True)

        self._last_self[self._conv_key(msg)] = msg.ts
        # 频控看的是真实发送速率，所以这里仍用处理时刻
        self._recent[msg.channel].append(time.time())
        self._auto_sent.append(time.time())
        self.log.append(f"[已发送] {msg.channel}/{msg.sender} → {draft[:28]}…")
        done("sent")

    def reject(self, index: int) -> bool:
        """人工丢弃第 index 条待发草稿。保留记录，便于统计驳回率。"""
        if not 0 <= index < len(self.pending):
            return False
        reply = self.pending.pop(index)
        qid = self._pending_ids.pop(index) if index < len(self._pending_ids) else 0
        if self.store is not None:
            self.store.resolve(qid, "rejected")
        # 驳回说明系统判断错了，扣分权重高于确认
        if self.credit is not None:
            self.credit.reject()
            self._save_credit()
        self.log.append(f"[已驳回] {reply.channel} → {reply.text[:24] or reply.reason}…")
        return True

    def approve(self, index: int) -> bool:
        """人工确认第 index 条待发草稿。"""
        if not 0 <= index < len(self.pending):
            return False
        reply = self.pending.pop(index)
        qid = self._pending_ids.pop(index) if index < len(self._pending_ids) else 0
        adapter = self.by_channel.get(reply.channel)
        if not adapter or not reply.text:
            self.pending.insert(index, reply)   # 取不出来就放回去，别吞掉草稿
            self._pending_ids.insert(index, qid)
            return False
        reply.approved = True
        sent = adapter.send(reply)
        if not sent:
            # 发送失败放回原位，排队等下一次。吞掉才是问题。
            reply.approved = False
            self.pending.insert(index, reply)
            self._pending_ids.insert(index, qid)
            self.log.append(f"[发送失败] {reply.channel} → 已放回队列")
            return False
        if self.store is not None:
            self.store.resolve(qid, "approved")
            self.store.record_sent(reply, auto=False)
        # 每一次人工放行都是对系统判断的确认，累加信任额度
        if self.credit is not None:
            self.credit.approve()
            self._save_credit()
        self.log.append(f"[人工放行] {reply.channel} → {reply.text[:28]}…")
        return True


# ---------------------------------------------------------------------------
# 7. 演示与入口
# ---------------------------------------------------------------------------

def demo() -> int:
    here = Path(__file__).parent
    persona = Persona.load(here / "persona.json")

    script = [
        Inbound("qq", "88347621", "阿哲", "这个桌子承重多少"),
        Inbound("qq", "88347621", "阿哲", "女孩子一个人搬得动吗"),
        Inbound("qq", "88347621", "阿哲", "能便宜点吗我买两个"),
        Inbound("wechat", "wxid_mili", "米粒", "在吗"),
        Inbound("wechat", "wxid_mili", "米粒", "多久能到"),
        Inbound("xhs", "note_9912", "用户8823", "求链接 这个在哪买", surface=Surface.COMMENT),
        Inbound("wechat", "wxid_mili", "米粒", "我要退款"),
    ]
    adapter = MockAdapter(script)
    # demo 里打开 allow_public_auto，是为了让"评论回复"这条链路也能演示完整；
    # 真实环境默认关闭，评论回复会全部进人工队列。
    hub = Hub(persona, MockEngine(), rate_limit=6, human_delay=(0, 0),
              allow_public_auto=True)
    hub.register(adapter, channels=["qq", "wechat", "xhs"])

    print("=" * 68)
    print(f"人设：{persona.name}｜示范语料 {len(persona.few_shot)} 条｜"
          f"知识 {len(persona.knowledge)} 条｜边界 {len(persona.boundaries)} 条")
    print("=" * 68)

    n = 0
    while hub.step():
        n += 1
        if n > 50:
            break

    for line in hub.log:
        print("  " + line)

    print("-" * 68)
    print(f"自动发送 {len(adapter.sent)} 条｜待确认 {len(hub.pending)} 条｜"
          f"拦截 {len(hub.blocked)} 条")
    for r in hub.blocked:
        print(f"  拦截原因：{r.reason}")
    print("-" * 68)

    # --- 审批链路验证 ---
    # 上一步所有消息都自动发了，待确认队列为 0，审批分支并未被覆盖。
    # 这里用"不允许自动发送"的适配器单独跑一遍，确保这条路径也被验证到。
    manual = MockAdapter([Inbound("wechat", "wxid_test", "测试", "在吗")])
    manual.auto_send_allowed = False
    hub2 = Hub(persona, MockEngine(), rate_limit=6, human_delay=(0, 0))
    hub2.register(manual, channels=["wechat"])
    hub2.step()
    queued = len(hub2.pending)
    approved = hub2.approve(0) if queued else False
    sent_after = len(manual.sent)
    print(f"审批链路：入队 {queued} 条｜放行 {'成功' if approved else '失败'}｜"
          f"放行后发送 {sent_after} 条")

    # 自检：三条关键路径都要有用例覆盖，缺一条就不算通过
    ok_block = len(hub.blocked) >= 2          # 边界拦截
    ok_auto = len(adapter.sent) == 5          # 自动发送
    ok_manual = queued == 1 and approved and sent_after == 1   # 人工审批
    ok = ok_block and ok_auto and ok_manual

    print("=" * 68)
    print(f"自检：边界拦截 {'✓' if ok_block else '✗'}｜"
          f"自动发送 {'✓' if ok_auto else '✗'}｜"
          f"人工审批 {'✓' if ok_manual else '✗'}")
    print("结果：" + ("全部通过" if ok else "存在未覆盖路径"))
    return 0 if ok else 1


def flow() -> int:
    """
    展示回复必要性判定：同一批消息里，哪些该开口、哪些该闭嘴。

    这一层是对 MaiBot 心流设计的简化实现。它的价值不在于让 AI 说得更好，
    而在于让 AI 说得更少——真实聊天里最大的破绽不是语气，是"每条都秒回"。
    """
    from necessity import Context, judge

    persona = Persona.load(Path(__file__).parent / "persona.json")

    cases = [
        (Inbound("qq", "g1", "阿哲", "这个桌子承重多少", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "阿哲", "哈哈哈", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "路人甲", "今天外面下雨了", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "阿哲", "女孩子一个人搬得动吗", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "路人甲", "嗯", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "阿哲", "小陆在不在", surface=Surface.GROUP),
         Context(surface="group")),
        (Inbound("qq", "g1", "阿哲", "能带炉子吗", surface=Surface.GROUP),
         Context(surface="group", self_said_ago=25)),
        (Inbound("wechat", "wx1", "米粒", "在吗", surface=Surface.PRIVATE),
         Context(surface="private")),
        (Inbound("wechat", "wx1", "米粒", "多久能到", surface=Surface.PRIVATE),
         Context(surface="private", self_said_ago=20)),
        (Inbound("qq", "g1", "路人乙", "这个有货吗", surface=Surface.GROUP),
         Context(surface="group")),
        # 以下是评论区场景：公开场合，标准与上面不同
        (Inbound("xhs", "note_1", "用户8823", "求链接", surface=Surface.COMMENT),
         Context(surface="comment")),
        (Inbound("xhs", "note_1", "用户A", "这个好看", surface=Surface.COMMENT),
         Context(surface="comment")),
        (Inbound("douyin", "item_1", "用户B", "这个在哪买", surface=Surface.COMMENT),
         Context(surface="comment")),
    ]

    print("=" * 72)
    print("回复必要性判定 · 借鉴 MaiBot 的 HeartFlow（心流）设计")
    print("=" * 72)

    n_reply = n_hold = 0
    for i, (msg, ctx) in enumerate(cases, 1):
        j = judge(msg, ctx, persona)
        scope = {"private": "私", "group": "群", "comment": "评"}[msg.surface.value]
        mark = {"reply": "回复", "wait": "观望", "silent": "沉默"}[j.action]
        if j.action == "reply":
            n_reply += 1
        else:
            n_hold += 1

        print(f"\n{i}. [{scope}] {msg.sender}：{msg.text}")
        print(f"   → {j.score:>4} / {mark}")
        for r in j.reasons:
            print(f"     · {r}")

    print("\n" + "-" * 72)
    print(f"共 {len(cases)} 条消息：开口 {n_reply} 条｜不开口 {n_hold} 条")
    print(f"不开口占比 {n_hold / len(cases):.0%}"
          "（真实聊天里没人会回应每一条消息）")

    # 自检：必须同时出现"该回的回了"和"不该回的不回"，否则这层等于没生效
    ok_reply = n_reply >= 5
    ok_hold = n_hold >= 3
    filler_silent = any(
        judge(m, c, persona).action == "silent"
        for (m, c) in cases if m.text.strip() == "嗯"
    )
    private_answered = all(
        judge(m, c, persona).action == "reply"
        for (m, c) in cases
        if m.surface is Surface.PRIVATE and not FILLER_ONLY(m.text)
    )
    # 评论区必须能接住购买意图，同时不接单纯夸赞
    comment_ok = (
        all(judge(m, c, persona).action == "reply"
            for (m, c) in cases
            if m.surface is Surface.COMMENT
            and any(w in m.text for w in ("求链接", "在哪买")))
        and all(judge(m, c, persona).action != "reply"
                for (m, c) in cases
                if m.surface is Surface.COMMENT and "好看" in m.text)
    )
    ok = ok_reply and ok_hold and filler_silent and private_answered and comment_ok

    print("=" * 72)
    print(f"自检：该回的回了 {'✓' if ok_reply else '✗'}｜"
          f"不该回的不回 {'✓' if ok_hold else '✗'}｜"
          f"语气词沉默 {'✓' if filler_silent else '✗'}｜"
          f"私聊不漏答 {'✓' if private_answered else '✗'}｜"
          f"评论接住购买意图 {'✓' if comment_ok else '✗'}")
    print("结果：" + ("通过" if ok else "判定失衡"))
    return 0 if ok else 1


def FILLER_ONLY(text: str) -> bool:
    """测试辅助：判断是否为纯语气词，与 necessity 内部规则保持一致。"""
    from necessity import FILLER_PATTERN
    return bool(FILLER_PATTERN.match(text.strip()))


def run_onebot() -> int:
    here = Path(__file__).parent
    persona = Persona.load(here / "persona.json")

    api = os.environ.get("ONEBOT_API", "http://127.0.0.1:3000")
    token = os.environ.get("ONEBOT_TOKEN", "")

    adapter = OneBotAdapter(api, token)
    # OneBot 走社区协议，默认不自动发送，全部进人工队列
    adapter.auto_send_allowed = False

    engine = LLMEngine(
        base_url=os.environ.get("LLM_BASE", "https://api.openai.com/v1"),
        api_key=os.environ.get("LLM_KEY", ""),
        model=os.environ.get("LLM_MODEL", "gpt-4o-mini"),
    )

    hub = Hub(persona, engine, rate_limit=6, human_delay=(1.5, 4.0), dry_run=True)
    hub.register(adapter)

    print(f"OneBot 端点：{api}｜人设：{persona.name}")
    print("dry_run=True：只生成草稿，不会真的发出消息。")
    print("把 WS 事件喂给 adapter.push_event(event) 即可开始接收。Ctrl+C 退出。")

    try:
        while True:
            if hub.step():
                for line in hub.log[-6:]:
                    print("  " + line)
            time.sleep(2)
    except KeyboardInterrupt:
        print(f"\n退出。待确认 {len(hub.pending)} 条｜拦截 {len(hub.blocked)} 条")
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "demo"
    if mode == "demo":
        sys.exit(demo())
    if mode == "flow":
        sys.exit(flow())
    sys.exit(run_onebot())
