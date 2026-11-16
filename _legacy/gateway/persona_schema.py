#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
persona_schema.py — 与行业无关的人设配置

要解决的问题
------------
现在的判定和生成是绑在「卖木工桌」上的：
  · necessity.py 里硬编码了「求链接 / 在哪买 / 多少钱」这些购买词
  · 语料全是桌子相关的问答
  · 换商品、换赛道、换账号，全要重做

这不是配置问题，是分层问题——**逻辑和素材揉在一起了**。

拆法
----
把「什么样的评论值得回」和「我卖什么」分成两层：

  意图层（跨行业固定）    素材层（每个账号一份）
  ─────────────────      ──────────────────
  purchase 购买意向       业务词表：你这个行业怎么说「想买」
  inquiry  细节询问       答案库：你这个产品怎么答
  after_sales 售后        风格样本：你怎么说话
  social   社交互动       边界词：什么不能说
  negative 负面攻击       兜底策略：答不上来怎么办

判定逻辑只认意图，不认商品。所以：
  · 换商品 A→B：只改词表和答案库，判定骨架不动
  · 换赛道电商→内容号：把 purchase 词表清空，其余不动
  · 换账号：整份配置换掉，代码一行不改

这就是「先设人设，再由人设反推回复」的实现方式——
不是让模型猜你是干什么的，是把「你是干什么的」写成可执行的结构。

依赖：仅标准库。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# 意图层：这五个标签是固定的，跨行业通用。
# weight 是该意图在「要不要开口」打分里的加分值。
GENERIC_INTENTS: dict[str, dict] = {
    "purchase": {
        "label": "购买意向",
        # 0.65 单独就能过阈值（0.60）。这个数字不能低：
        # 一个刚建的新账号还没有任何语料，遇到「求链接」必须能接住，
        # 否则最值钱的那批评论全被漏掉。原来设 0.50 时，
        # 只有搭配语料命中才够分，等于要求「先有语料才认得商机」。
        "weight": 0.65,
        "desc": "要链接、问在哪买、问价格、想下单",
    },
    "inquiry": {
        "label": "细节询问",
        # 0.35 单独不够，要配合问句特征或知识命中。
        # 这是有意的：单纯问一句「多久」不足以确认是在问你。
        "weight": 0.35,
        "desc": "问参数、问用法、问时效",
    },
    "after_sales": {
        "label": "售后诉求",
        # 和 purchase 一样要能单独成立：退款、坏了这类消息必须留下记录，
        # 哪怕最终被边界规则拦下转人工。
        "weight": 0.65,
        "desc": "退换、投诉、坏了",
    },
    "social": {
        "label": "社交互动",
        # 刻意压低：夸赞不该单独构成回复理由，
        # 否则每条「好看」都要回一遍，评论区会显得很吵。
        "weight": 0.15,
        "desc": "夸赞、玩梗、闲聊",
    },
    "negative": {
        "label": "负面攻击",
        # 权重不参与比较——detect_intent 里对 negative 做了前置判断，
        # 命中就返回，不看权重。理由见那个函数。
        "weight": 0.0,
        "desc": "骂人、指控、恶意差评——不接",
    },
}

# 兜底动作：答不上来时的行为。
FALLBACK_ACTIONS = ("handoff", "silent", "template")

# 提供形态。决定默认的意图权重取向，也用于配置校验。
OFFERINGS = ("product", "content", "service", "none")


# ---------------------------------------------------------------------------
# 默认词表
# ---------------------------------------------------------------------------

# 这套是「电商通用」词，不是某个商品专用。换商品时通常不用改；
# 换赛道（比如做内容号）时需要把 purchase 清空。
DEFAULT_INTENT_WORDS: dict[str, list[str]] = {
    "purchase": ["求链接", "在哪买", "哪里买", "怎么买", "怎么卖", "想买",
                 "下单", "有卖吗", "有货", "多少钱", "价格", "想要"],
    "inquiry": ["怎么用", "怎么保养", "多久", "能不能", "可不可以",
                "适合", "有什么区别"],
    "after_sales": ["退款", "退货", "换货", "坏了", "保修", "售后",
                    "投诉", "赔偿"],
    "social": ["好看", "喜欢", "厉害", "牛", "赞", "收藏了"],
    "negative": ["骗", "垃圾", "差评", "恶心", "举报"],
}


@dataclass
class Fallback:
    """答不上来时怎么办。显式定义，而不是隐式地「进队列」。"""
    action: str = "handoff"        # handoff / silent / template
    template: str = ""             # action=template 时用的话术

    def to_dict(self) -> dict:
        return {"action": self.action, "template": self.template}


@dataclass
class PersonaProfile:
    """
    一份与行业无关的人设配置。

    字段刻意少：填得越多越难维护，而且大部分字段对判定没有影响。
    真正影响行为的只有四样：叫法、意图词表、边界、兜底。
    """
    role: str = ""                          # 一句话身份：卖什么 / 做什么内容
    offering: str = "product"               # product / content / service / none
    aliases: list[str] = field(default_factory=list)   # 别人怎么称呼你
    intents: dict[str, list[str]] = field(default_factory=dict)
    fallback: Fallback = field(default_factory=Fallback)

    # ---------- 构造 ----------

    @classmethod
    def from_dict(cls, d: dict | None) -> "PersonaProfile":
        d = d or {}
        intents = {k: list(v) for k, v in (d.get("intents") or {}).items()
                   if k in GENERIC_INTENTS and isinstance(v, list)}
        fb = d.get("fallback") or {}
        return cls(
            role=str(d.get("role") or ""),
            offering=str(d.get("offering") or "product"),
            aliases=[str(x) for x in (d.get("aliases") or []) if x],
            intents=intents,
            fallback=Fallback(
                action=str(fb.get("action") or "handoff"),
                template=str(fb.get("template") or ""),
            ),
        )

    @classmethod
    def from_legacy(cls, data: dict) -> "PersonaProfile":
        """
        从旧格式（只有 name/identity/knowledge/boundaries/few_shot）迁移。

        迁移策略是保守的：词表用电商通用默认，不根据 knowledge 去猜行业——
        猜错会让用户以为配置好了，实际判定全歪。
        """
        aliases = [str(data["name"])] if data.get("name") else []
        return cls(
            role=str(data.get("identity") or "")[:80],
            offering="product",
            aliases=aliases,
            intents={k: list(v) for k, v in DEFAULT_INTENT_WORDS.items()},
            fallback=Fallback(),
        )

    # ---------- 查询 ----------

    def words_for(self, intent: str) -> list[str]:
        return list(self.intents.get(intent) or [])

    @property
    def active_intents(self) -> list[str]:
        """词表非空的意图。空词表 = 这个账号不关心这类评论。"""
        return [k for k in GENERIC_INTENTS if self.intents.get(k)]

    def weight_of(self, intent: str) -> float:
        return float(GENERIC_INTENTS.get(intent, {}).get("weight", 0.0))

    # ---------- 校验 ----------

    def validate(self) -> list[str]:
        """
        返回问题清单，空表示通过。

        校验只报「必然导致行为错误」的问题，不做风格检查——
        配置好不好用要靠一致率测，不靠 lint。
        """
        problems: list[str] = []
        if self.offering not in OFFERINGS:
            problems.append(f"offering 必须是 {OFFERINGS} 之一，当前：{self.offering}")
        if self.fallback.action not in FALLBACK_ACTIONS:
            problems.append(
                f"fallback.action 必须是 {FALLBACK_ACTIONS} 之一，"
                f"当前：{self.fallback.action}")
        if self.fallback.action == "template" and not self.fallback.template.strip():
            problems.append("fallback.action=template 但 template 是空的")
        for k in self.intents:
            if k not in GENERIC_INTENTS:
                problems.append(f"未知意图：{k}（可选：{list(GENERIC_INTENTS)}）")
        # 卖货账号没有 purchase 词表，几乎肯定配错了；
        # 非卖货账号有 purchase 词表，会把闲聊当商机。
        if self.offering == "product" and not self.intents.get("purchase"):
            problems.append("offering=product 但 purchase 词表为空，购买意向不会被识别")
        if self.offering in ("content", "none") and self.intents.get("purchase"):
            problems.append(
                f"offering={self.offering} 却配了 purchase 词表，"
                "会把普通评论误判成购买意图")
        return problems

    def to_dict(self) -> dict:
        return {
            "role": self.role,
            "offering": self.offering,
            "aliases": self.aliases,
            "intents": self.intents,
            "fallback": self.fallback.to_dict(),
        }


# ---------------------------------------------------------------------------
# 文件读写
# ---------------------------------------------------------------------------

def load_profile(path: str | Path) -> tuple[dict, PersonaProfile]:
    """
    读人设文件，返回 (原始数据, 结构化 profile)。

    原始数据要一并返回：旧字段（tone / knowledge / few_shot）仍在用，
    迁移只补 profile，不删原有内容。
    """
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data.get("profile"), dict):
        profile = PersonaProfile.from_dict(data["profile"])
    else:
        profile = PersonaProfile.from_legacy(data)
        data["profile"] = profile.to_dict()
    return data, profile


def save_profile(path: str | Path, data: dict, profile: PersonaProfile) -> None:
    data = dict(data)
    data["profile"] = profile.to_dict()
    Path(path).write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def new_persona(template: str, name: str, role: str) -> dict:
    """
    按模板生成一份最小可用人设。

    模板只给结构，不给成品话术——话术要靠用户自己的语料，
    这里塞进去的示例反而会污染判定。
    """
    base = {
        "name": name,
        "identity": role,
        "tone": {"formal": 30, "length": 35, "emoji": 15, "catchphrases": []},
        "knowledge": [],
        "boundaries": ["便宜", "优惠", "打折", "少点", "退款", "退货", "投诉", "差评"],
        "taboo_words": ["加微信", "私聊转账"],
        "few_shot": [],
    }
    offerings = {
        "ecommerce": "product",
        "creator": "content",
        "consulting": "service",
        "personal": "none",
    }
    intents = {k: list(v) for k, v in DEFAULT_INTENT_WORDS.items()}
    if template in ("creator", "personal"):
        # 不卖货就不该识别购买意图，否则每句「在哪看」都被当成商机
        intents["purchase"] = []
    if template == "creator":
        intents["inquiry"] = ["怎么做的", "用的什么", "什么设备", "怎么剪",
                              "背景音乐", "教程"]
    if template == "consulting":
        intents["purchase"] = ["怎么收费", "多少钱", "怎么约", "能咨询吗",
                               "怎么合作", "报价"]
        intents["inquiry"] = ["多久", "怎么进行", "需要准备"]

    base["profile"] = PersonaProfile(
        role=role,
        offering=offerings.get(template, "product"),
        aliases=[name],
        intents=intents,
        fallback=Fallback(action="handoff"),
    ).to_dict()
    return base


TEMPLATES = {
    "ecommerce": "电商卖货：有明确商品，购买意图权重最高",
    "creator": "内容创作者：不卖货，问的是怎么做内容",
    "consulting": "服务咨询：卖的是时间和方案，问的是怎么约",
    "personal": "个人号：不卖任何东西，只做互动",
}


def detect_intent(text: str, profile: PersonaProfile) -> tuple[str, str]:
    """
    找出这条消息命中的最强意图。返回 (意图, 命中的词)，没命中返回 ("", "")。

    多个意图同时命中时取权重最高的：
    「能便宜点吗，求链接」同时命中「求链接」（purchase）和议价，
    应该按 purchase 处理——用户想买，只是顺口砍价。
    按 after_sales 处理会把它送进售后队列，方向就错了。

    这是纯函数：不读配置以外的东西，方便单测和回溯。
    """
    # negative 前置判断：命中就返回，不参与权重比较。
    #
    # 原因是它的权重必须是 0（不该加分），但 0 又让它永远比不过别的意图。
    # 「骗人的吧，求链接」如果按权重比，会被判成 purchase——方向完全反了。
    #
    # 代价是关键词的固有局限：「这个价格不骗人」也会命中「骗」。
    # 所以 negative 词表要由人设配得更精确（写「骗子」而不是「骗」），
    # 而不是靠这里做语义判断。
    for word in profile.words_for("negative"):
        if word and word in text:
            return "negative", word

    best_intent, best_word, best_w = "", "", -1.0
    for intent in GENERIC_INTENTS:
        if intent == "negative":
            continue
        w = profile.weight_of(intent)
        for word in profile.words_for(intent):
            if word and word in text and w > best_w:
                best_intent, best_word, best_w = intent, word, w
    return best_intent, best_word
