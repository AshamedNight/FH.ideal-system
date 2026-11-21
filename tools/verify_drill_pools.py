# -*- coding: utf-8 -*-
"""验算两件"纯逻辑"的事，本机没有 JDK 也能跑：

1. **系统提示判定**（NodeQuery.looksLikeSystemNotice）
   截图里那三条平台营销文案必须被丢掉，真人短句必须留着。

2. **演练话术的多样性与场景**（DrillReplier）
   - 同样的「约吗」，连续几轮不该给同一句
   - 「我猜不出来」不能被当成邀约（`出来` 这个词太宽的经典误伤）

池子和词表都用 regex 直接从 .kt 源码里读，不手抄 —— 改实现时这里自动跟着变。
"""
import re
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def kotlin_list(src, name):
    """把 `private val NAME = listOf(...)` 里的字符串读出来。"""
    body = src.split(f"private val {name} = listOf(", 1)[1]
    body = body.split("\n    )", 1)[0]
    return re.findall(r'"([^"]*)"', body)


HINTS = kotlin_list(read("app/src/main/java/com/autoreply/app/platform/NodeQuery.kt"),
                    "SYSTEM_NOTICE_HINTS")
drill = read("app/src/main/java/com/autoreply/app/core/llm/DrillReplier.kt")
INVITE = kotlin_list(drill, "INVITE_WORDS")
POOLS = {n: kotlin_list(drill, n) for n in ("DEFLECT", "TOPICS", "ACK", "FOLLOW_UP")}

MIN_NOTICE_LEN = 14


def is_system(text):
    t = text.strip()
    if len(t) < MIN_NOTICE_LEN:
        return False
    return any(h in t for h in HINTS)


def looks_like_invite(text):
    return any(w in text for w in INVITE)


def java_hash(s):
    """Java String.hashCode()，pick() 里用的就是它。"""
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - 2 ** 32 if h >= 2 ** 31 else h


def pick(pool, seed, round_):
    return pool[(java_hash(seed) + round_ * 7) % len(pool)]


fail = 0


def expect(title, got, want):
    global fail
    ok = got == want
    if not ok:
        fail += 1
    print(f"  {'✓' if ok else '✗'} {title:<34} 得到={got!s:<5} 期望={want}")


print(f"【1】系统提示判定（词表 {len(HINTS)} 条，长度门槛 {MIN_NOTICE_LEN}）")
for text, want in [
    ("别错过缘分，主动发一句消息可以加深他对你的印象。", True),
    ("2分钟内回复有效内容还有额外红包奖励哟!", True),
    ("消息发出还没回复？ 试试热聊话题吧~", True),
    ("收到搭讪礼物 纸杯蛋糕 x 1", True),
    ("录制语音签名，得金币收益红包奖励～", True),
    ("你怎么还没回复我", False),
    ("今天加班到十点才到家", False),
    ("我平时就爱在家待着看看剧", False),
    ("晚上出来放松放松", False),
]:
    expect(text[:30], is_system(text), want)

print(f"\n【2】邀约判定（{len(INVITE)} 个词组）")
for text, want in [
    ("约吗", True),
    ("晚上出来放松放松", True),
    ("今晚有空吗", True),
    ("你出来玩吗", True),
    ("我猜不出来", False),
    ("这事我想不出来", False),
    ("大约几点下班", False),
    ("今天好累", False),
]:
    expect(text, looks_like_invite(text), want)

print("\n【3】多样性：同一句「约吗」连续 6 轮")
got = [pick(POOLS["DEFLECT"], "约吗", r) for r in range(6)]
for i, s in enumerate(got):
    print(f"  第{i + 1}轮 → {s}")
expect("不该全部相同", len(set(got)) > 1, True)

print("\n【4】不同的话落在不同位置")
for q in ["约吗", "出来玩吗", "今晚有空吗"]:
    print(f"  {q:<10} → {pick(POOLS['DEFLECT'], q, 1)}")

print("\n【5】池子大小")
for name, pool in POOLS.items():
    print(f"  {name:<10} {len(pool)} 条")
expect("DEFLECT 至少 6 条", len(POOLS["DEFLECT"]) >= 6, True)
expect("TOPICS 至少 6 条", len(POOLS["TOPICS"]) >= 6, True)

print("\n" + ("全部通过" if fail == 0 else f"有 {fail} 项失败"))
sys.exit(1 if fail else 0)
