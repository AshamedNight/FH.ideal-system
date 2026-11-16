#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
necessity.py — 回复必要性判定（借鉴 MaiBot 的 HeartFlow / reply_necessity）

为什么需要这一层
----------------
上一版 Gateway 是"来一条回一条"。这在真实聊天里是最大的破绽：
没有任何真人会 24 小时秒回每一条消息。MaiBot 的核心洞察就在这里——
它给每条消息打"激活度"分数，超阈值才开口，大部分时候保持沉默。

沉默才是拟人的关键，不是话术。

判定分三档
----------
  reply   → 直接生成回复
  wait    → 暂不回复，但保留观察（可稍后由上下文触发）
  silent  → 本条不参与，连草稿都不生成，不占资源

关于「被提及」
--------------
原来被提及是短路返回 1.0「强制回复」。这个设计有问题：
强制意味着「@我 6」也会回，而这一条在公开评论区是明显的机器人特征。

现在改成高权重加分（+0.65）并豁免「未被点名」和「刚回复过」两项扣分：
  · 被点名且说了事 → 一定回（0.65 单独过阈值，配问句/意图更稳）
  · 被点名但只说「嗯」「6」→ 纯语气词规则先命中，直接沉默
  · 被点名但只有一个字且不是语气词（「贵」）→ 过短扣 0.20 落到 0.45，观望

这才对得上产品语义：**评论区提到我的人，尽量回**，不是「一律回」。

设计原则
--------
1. 纯函数：不读时间以外的外部状态，不调网络，方便单测
2. 显式规则：每条加减分都写清楚原因，便于调参与排障
3. 被提及高优先但不强制：优先级不等于放弃判断
4. 宁可少说：分数不够就不说，符合"AI 不该抢话"的产品直觉
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Protocol, Sequence

# 只依赖标准库与人设配置，不 import gateway —— 否则两边互引会成环。
# 消息与人设用结构类型约束，不绑定具体类。
from persona_schema import GENERIC_INTENTS, detect_intent
from textutil import coverage, lcs_len as _lcs_len


class MessageLike(Protocol):
    text: str
    channel: str


class PersonaLike(Protocol):
    name: str
    knowledge: list
    few_shot: list
    profile: object          # PersonaProfile；这里不写具体类型，避免跨模块强耦合

# 三档阈值
THRESHOLD_REPLY = 0.60
THRESHOLD_WAIT = 0.30

# 直接呼唤词：命中即强制回复，不参与打分
SUMMON_WORDS = ("在吗", "在不在", "hello", "hi", "你好", "有人吗")

# 纯语气词：出现在消息里就几乎不值得回
FILLER_PATTERN = re.compile(r"^(嗯+|哦+|噢+|好+|行|收到|ok|OK|哈哈+|hhh+|额|啊这|草|6+)[。.!！~～]*$")

# 问句特征
QUESTION_PATTERN = re.compile(r"[?？]|吗|呢|多少|怎么|咋|能不能|可不可以|有没有|是不是|为什么|多久|几个|几种")

# 意图词表不在这里了。
#
# 原来这里硬编码着「求链接 / 在哪买 / 价格」——那等于把判定逻辑绑在电商上，
# 换个商品还能凑合，换个赛道（做内容、做咨询、不卖货）就全错。
# 现在词表放在人设的 profile.intents 里，判定只认意图标签。


@dataclass
class Context:
    """判定所需的外部上下文。由 Hub 在收到消息时组装。"""
    surface: str = "private"          # private / group / comment
    is_mentioned: bool = False
    self_said_ago: float = 1e9        # 距自己上次发言多少秒，越大越久没说话
    group_burst: int = 0              # 最近 30 秒该会话的消息条数，越高说明在热聊
    recent_texts: Sequence[str] = field(default_factory=tuple)


@dataclass
class Judgment:
    score: float
    action: str                       # reply / wait / silent
    reasons: list[str] = field(default_factory=list)

    @property
    def speaks(self) -> bool:
        return self.action == "reply"


def _clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


def judge(msg: MessageLike, ctx: Context, persona: PersonaLike,
          reply_at: float = THRESHOLD_REPLY,
          wait_at: float = THRESHOLD_WAIT) -> Judgment:
    """
    给一条消息打激活度分数，决定要不要开口。

    短路的两种情况（不看分数直接回）：
      1. 私聊且是呼唤词 —— 一对一场景，不回就是不礼貌
      2. 私聊里的问句 —— 用户已经开口问了，沉默等于失职

    被提及（@ 或叫名字）不再短路，改为 +0.55 的高权重，
    理由见模块文档：强制回复会让「@我 6」也发出去。
    """
    text = msg.text.strip()
    reasons: list[str] = []
    private = ctx.surface == "private"
    comment = ctx.surface == "comment"

    # ---- 纯语气词直接沉默（优先级最高，被提及也一样）----
    if FILLER_PATTERN.match(text):
        return Judgment(0.0, "silent", ["纯语气词，无信息量"])

    # ---- 意图识别：从人设的词表读，不硬编码商品词 ----
    #
    # 这一步是「换商品不用改代码」的关键。词表在人设里，
    # 判定只认意图标签（购买/询问/售后/社交/负面），不认具体是卖桌子还是卖课。
    profile = getattr(persona, "profile", None)
    intent, intent_word = ("", "")
    if profile is not None:
        intent, intent_word = detect_intent(text, profile)

    # 负面内容不接。骂人的评论回什么都像在吵架，而且截图传出去更糟。
    if intent == "negative":
        return Judgment(0.0, "silent",
                        [f"负面内容（{intent_word}），不接"])

    # ---- 被直接呼唤：私聊里是硬要求 ----
    name_hit = bool(persona.name) and persona.name in text
    summon_hit = any(w in text.lower() for w in SUMMON_WORDS)

    if private and summon_hit:
        return Judgment(1.0, "reply", ["私聊呼唤，强制回复"])

    # ---- 基础分 ----
    score = 0.0

    # 被提及：给足权重让它单独跨过阈值，但不过分到「什么都回」。
    #
    # 0.65 是实测算的：阈值 0.60，所以「@我 好看」这种两字短评能过；
    # 而「@我 6」会被过短规则扣掉 0.20 落到 0.45，仍然不回复。
    # 这个位置刚好把「被点名且说了事」和「被点名但没内容」分开。
    mentioned = bool(ctx.is_mentioned or name_hit)
    if mentioned:
        score += 0.65
        reasons.append("被直接提及（评论区点名）+0.65")

    if QUESTION_PATTERN.search(text):
        score += 0.35
        reasons.append("含问句特征 +0.35")

    kb_hit = [item["topic"] for item in persona.knowledge
              if any(k in text for k in item.get("keys", []))]
    if kb_hit:
        score += 0.30
        reasons.append(f"命中知识条目（{kb_hit[0]}）+0.30")

    # 意图加分。权重来自 persona_schema.GENERIC_INTENTS，与商品无关：
    # 购买 0.50 / 询问 0.35 / 售后 0.60 / 社交 0.15。
    #
    # 不再限定 comment 场景。原来只有评论区才算购买意图，是因为把「评论」
    # 和「营销场景」绑在一起想；实际上私信里问「多少钱」比评论区更强。
    # 场景差异交给下面的 surface 分支处理，不要在这里重复扣一次。
    intent_hit = bool(intent)
    if intent_hit:
        weight = profile.weight_of(intent) if profile is not None else 0.35
        label = GENERIC_INTENTS[intent]["label"]
        score += weight
        reasons.append(f"命中「{label}」（{intent_word}）+{weight:.2f}")

    # 与示范语料相近 = 这是人设该管的事。
    # 用覆盖率不用 LCS 绝对值：2 字短句（"在吗"）的覆盖率极易虚高到 0.5，
    # 会给无关消息白送分。3 字以内的语料直接不参与这一项。
    best_cov = 0.0
    for pair in persona.few_shot:
        if len(pair["q"]) < 3:
            continue
        best_cov = max(best_cov, coverage(text, pair["q"]))
    if best_cov >= 0.55:
        score += 0.25
        reasons.append(f"与示范语料相近（覆盖 {best_cov:.0%}）+0.25")

    if len(text) >= 5:
        score += 0.10
        reasons.append("消息有一定长度 +0.10")

    # 强相关：消息明显是冲这个产品/这个人设来的。
    # 强相关时不再扣「未被点名」的分——否则一句「搬得动吗」会因为
    # 没 @ 就被判成观望，这在真实群里是明显的失职。
    # 被点名本身就算强相关：对方指名道姓找你，不能当没看见。
    strong = bool(kb_hit) or intent_hit or best_cov >= 0.60 or mentioned

    # ---- 场景分 ----
    # 过短只判单字。原来判「≤2 字」会把「好看」「真棒」「多少」这类
    # 两个汉字的短评一起扣掉——中文两字已经能表达明确意思，
    # 真正该过滤的是单字（「6」「1」）和无意义串，后者由 FILLER 负责。
    if len(text) <= 1:
        score -= 0.20
        reasons.append("单字消息 -0.20")

    if private:
        # 私聊是一对一必答场景：用户主动来找你，装看不见没有道理。
        # 也因此私聊不吃「刚说过话」的惩罚——一问一答本来就是连续的。
        #
        # 这里给 0.40 而不是 0.30：实测「求链接」这种短句在私信里只能拿到
        # 0.25（语料命中），加 0.30 是 0.55，卡在阈值下方被判观望——
        # 一对一场景把「求链接」判成观望，是明显的失职。
        score += 0.40
        reasons.append("私聊场景（一对一必答）+0.40")
    elif comment:
        # 评论区是公开场合。评论大多在评价内容，不一定是冲你来的，
        # 所以默认不插话；只有购买意图明确才接。
        if not strong:
            score -= 0.10
            reasons.append("公开评论区且无明确购买意图 -0.10")
        else:
            reasons.append("公开评论区但意图明确，不扣参与欲")
        # 被点名时不扣「刚回复过」：一对一的追问本来就该连着答，
        # 刚回过就不再回，等于对方问第二句你就不理人了。
        if ctx.self_said_ago < 60 and not mentioned:
            score -= 0.25
            reasons.append(f"{int(ctx.self_said_ago)} 秒前刚回复过 -0.25")
    else:
        if not strong:
            score -= 0.15
            reasons.append("群聊且未被点名 -0.15")
        else:
            reasons.append("群聊但消息强相关，不扣参与欲")
        # 抢话惩罚只对群聊生效：同一分钟里连着开口两次，像刷屏。
        if ctx.self_said_ago < 60:
            score -= 0.25
            reasons.append(f"{int(ctx.self_said_ago)} 秒前刚在群里说过话 -0.25")

    if ctx.group_burst >= 5:
        score -= 0.10
        reasons.append(f"消息刷屏（{ctx.group_burst} 条/30 秒）-0.10")

    # ---- 群聊里如果话题一直在别人之间转，降低参与欲 ----
    if ctx.recent_texts:
        unrelated = sum(1 for t in ctx.recent_texts
                        if _lcs_len(t, text) < 2)
        if unrelated >= 3:
            score -= 0.10
            reasons.append("最近几条与自己无关 -0.10")

    score = _clamp(score)

    if score >= reply_at:
        action = "reply"
    elif score >= wait_at:
        action = "wait"
    else:
        action = "silent"

    if not reasons:
        reasons.append("无任何命中信号")

    return Judgment(round(score, 2), action, reasons)
