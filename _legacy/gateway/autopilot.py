#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
autopilot.py — 自动发送的分级放权

要解决的问题
------------
之前只有一个开关 allow_public_auto：False 就全要人工确认，True 就全放开。
前者等于半自动，后者等于失控。中间没有档位。

真实产品不该这样。正确的做法是让系统**自己挣取信任**：

  · 一开始只能人工确认，积累样本
  · 你连续确认得多、驳得少，才逐步解锁自动发送
  · 一旦你开始驳回，额度立刻回退

三个判定维度
------------
1. **话术风险** —— 这句回复本身有多大风险，与消息无关
2. **意图置信度** —— 这条消息有多明确是在问价/求链接
3. **信任额度** —— 你对这个系统的历史采纳率

三者都过了才自动发，任何一个不过就进队列或按住。

为什么话术要单独分级
--------------------
"链接在主页置顶" 和 "保证七天无理由退换" 是两种性质。
前者说错了顶多是信息滞后，后者说错了是法律承诺。
把它们放在同一个"自动发送"开关下面，等于用最坏的情况绑架最好的情况。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# 高风险：涉及价格、承诺、售后、法律含义的表述。
# 命中即永久人工，不参与信任额度解锁——这类话说错的代价不对等。
# 「价格」单独列进来：哪怕回复只是「价格私信说」，也是把人往交易上引，
# 必须人工看一眼再发。
HIGH_RISK = (
    "便宜", "优惠", "折扣", "打折", "特价", "秒杀", "价格",
    "保证", "一定", "绝对", "包退", "包换", "包邮",
    "退款", "退货", "赔偿", "承诺", "永久", "终身",
)

# 中风险：涉及时效、流程、易变信息的表述。
# 信任额度够了可以自动发，因为说错了通常还能补救。
MEDIUM_RISK = (
    "今天", "明天", "后天", "当天", "次日", "小时内", "尽快", "马上",
    "发货", "到货", "快递", "库存", "现货", "有货", "预售", "补货",
)

# 金额形态：429 / 429元 / ¥429 / 429.00
_MONEY = re.compile(r"(?:[¥￥]\s*\d+)|(?:\d+\s*(?:元|块|rmb|RMB))|(?:\d+\.\d{2})")


def classify_risk(draft: str) -> tuple[str, list[str]]:
    """
    给一句回复定风险等级。返回 (等级, 命中的词)。

    只看回复本身，不看用户说了什么——
    用户问价格不代表我可以报价，这是两件事。
    """
    text = draft or ""
    hits_high = [w for w in HIGH_RISK if w in text]
    if hits_high:
        return "high", hits_high
    if _MONEY.search(text):
        return "high", ["金额"]
    hits_mid = [w for w in MEDIUM_RISK if w in text]
    if hits_mid:
        return "medium", hits_mid
    return "low", []


# ---------------------------------------------------------------------------
# 信任额度
# ---------------------------------------------------------------------------

@dataclass
class Credit:
    """
    信任额度。确认加一分，驳回扣两分——驳回的权重更高，因为它说明
    "系统判断错了"，比"系统判断对了"更能反映问题。

    这是刻意的非对称：宁可解锁慢一点，也不要放过一批错话。
    """
    approvals: int = 0
    rejections: int = 0

    @property
    def score(self) -> int:
        return self.approvals - 2 * self.rejections

    @property
    def samples(self) -> int:
        return self.approvals + self.rejections

    def approve(self) -> None:
        self.approvals += 1

    def reject(self) -> None:
        self.rejections += 1

    def snapshot(self) -> dict:
        return {
            "approvals": self.approvals,
            "rejections": self.rejections,
            "score": self.score,
            "samples": self.samples,
        }


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

@dataclass
class AutopilotConfig:
    # 置信度门槛。
    #
    # 定 0.75 不是拍的：实测「求链接」「这个在哪买」这类高意图评论落在 0.75，
    # 「多久能到」落在 0.80，而「质量怎么样」0.70。门槛设 0.80 会把
    # 意图最强的「这个在哪买」挡在外面，却放过「多久能到」——同一类消息被切成两半。
    # 0.75 能把整组高意图消息一起纳入，0.70 那条仍走人工，分界线落对了位置。
    min_confidence: float = 0.75
    # 需要累计多少次"确认"才解锁。默认 10，一天就能攒到
    unlock_after: int = 10
    # 解锁后是否允许中风险话术也自动发
    allow_medium_after_unlock: bool = True
    # 每小时自动发送上限。防止某条爆款视频下瞬间发几十条
    max_auto_per_hour: int = 5


# ---------------------------------------------------------------------------
# 判定
# ---------------------------------------------------------------------------

AUTO = "auto"       # 可以直接发
QUEUE = "queue"     # 进人工队列
HOLD = "hold"       # 按住，必须人工看过


def decide(draft: str, confidence: float, credit: Credit,
           cfg: AutopilotConfig | None = None,
           auto_sent_last_hour: int = 0) -> tuple[str, list[str]]:
    """
    决定这句回复能不能自动发。返回 (动作, 理由列表)。

    判定顺序有意为之：
      1. 风险先判 —— 高风险的不用看额度，直接按住
      2. 再看置信度 —— 不够就别硬发
      3. 最后看额度 —— 前面都过了，才轮到"系统敢不敢"

    把风险放第一位，是因为它是唯一一个"无论多少历史数据成立"的条件。
    """
    cfg = cfg or AutopilotConfig()
    reasons: list[str] = []

    risk, hits = classify_risk(draft)
    if risk == "high":
        reasons.append(f"话术含高风险表述（{'、'.join(hits)}），必须人工")
        return HOLD, reasons
    reasons.append(f"话术风险 {risk}")

    if confidence < cfg.min_confidence:
        reasons.append(f"置信度 {confidence:.2f} < {cfg.min_confidence}")
        return QUEUE, reasons
    reasons.append(f"置信度 {confidence:.2f} 达标")

    if credit.score < cfg.unlock_after:
        reasons.append(
            f"信任额度不足（{credit.score}/{cfg.unlock_after}，"
            f"确认 {credit.approvals} / 驳回 {credit.rejections}）")
        return QUEUE, reasons
    reasons.append(f"信任额度已解锁（{credit.score}）")

    if auto_sent_last_hour >= cfg.max_auto_per_hour:
        reasons.append(f"本小时自动发送已达上限 {cfg.max_auto_per_hour} 条")
        return QUEUE, reasons

    if risk == "medium" and not cfg.allow_medium_after_unlock:
        reasons.append("中风险话术按配置仍走人工")
        return QUEUE, reasons

    reasons.append("全部条件满足，允许自动发送")
    return AUTO, reasons


def preview(rows: list[dict], credit: Credit,
            cfg: AutopilotConfig | None = None) -> dict:
    """
    演练：假设现在开启自动发送，这批草稿里哪些会被放出去。

    这个函数存在的理由很实际——"开了自动发送会怎样"这个问题，
    不该等到开了之后才知道。先演练一遍，看过结果再决定。
    """
    cfg = cfg or AutopilotConfig()
    auto, queue, hold = [], [], []
    for r in rows:
        action, reasons = decide(
            r.get("draft", ""), float(r.get("confidence") or 0),
            credit, cfg, r.get("auto_sent_last_hour", 0))
        item = {**r, "action": action, "reasons": reasons}
        {"auto": auto, "queue": queue, "hold": hold}[action].append(item)
    return {"auto": auto, "queue": queue, "hold": hold,
            "summary": {"auto": len(auto), "queue": len(queue), "hold": len(hold)}}
