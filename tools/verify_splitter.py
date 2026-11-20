# -*- coding: utf-8 -*-
"""用 Python 复刻 SentenceSplitter 的逻辑，验证「开场白第二句多一个句号」的修复。

本机没有 JDK，跑不了 Kotlin 单测，所以按 Kotlin 源码逐行翻译一份，
对比修复前（拼接符 = 句号）和修复后（拼接符 = 换行）的拆句结果。
逻辑一旦和 .kt 不同步，这里的结果就没有参考价值 —— 改动 split 时记得同步。
"""

HARD = set("。！？…!?\n")
SOFT = set("，；、,;")
SOFT_MIN_CHARS = 5
MIN_SEGMENT_CHARS = 2
MAX_SEGMENTS = 3
TERMINATORS = set("。？！…?!")
SPLITTERS = HARD | SOFT


def content_length(s: str) -> int:
    return sum(1 for c in s if c not in SPLITTERS and not c.isspace())


def tidy(raw: str) -> str:
    """新增：删掉紧跟在本句收尾标点后面的句号。"""
    if len(raw) < 2:
        return raw
    out = []
    for ch in raw:
        if ch == "。" and out and out[-1] in TERMINATORS:
            continue
        out.append(ch)
    return "".join(out)


def split(raw: str, fix: bool) -> list:
    text = (tidy(raw) if fix else raw).strip()
    if not text:
        return [text]

    pieces = []
    sb = []
    for i, ch in enumerate(text):
        sb.append(ch)
        if ch in HARD:
            pieces.append("".join(sb))
            sb = []
        elif ch in SOFT:
            head = content_length("".join(sb))
            rest = text[i + 1:]
            nxt = next((j for j, c in enumerate(rest) if c in HARD), -1)
            tail = content_length(rest if nxt < 0 else rest[: nxt + 1])
            if head >= SOFT_MIN_CHARS and tail >= SOFT_MIN_CHARS:
                pieces.append("".join(sb))
                sb = []
    if sb:
        pieces.append("".join(sb))

    if len(pieces) <= 1:
        return [text]

    merged = []
    for p in pieces:
        if not merged:
            merged.append(p)
        elif content_length(p) < MIN_SEGMENT_CHARS:
            merged[-1] = merged[-1] + p
        else:
            merged.append(p)

    if len(merged) > MAX_SEGMENTS:
        result = merged[: MAX_SEGMENTS - 1] + ["".join(merged[MAX_SEGMENTS - 1:])]
    else:
        result = merged

    out = []
    for seg in result:
        t = seg.strip()
        if sum(1 for c in t if not c.isspace()) > 1:
            t = t.rstrip("，,；;、 ")
        out.append(t)
    out = [s for s in out if s]
    return out or [text]


GREETINGS = ["在", "干嘛呢？", "你平时喜欢做些什么"]

print("=" * 60)
print("修复前：joinToString(\"。\")")
old = "。".join(GREETINGS)
print("  拼接结果:", repr(old))
for i, seg in enumerate(split(old, fix=False), 1):
    print(f"  第{i}条 -> {seg!r}")

print("=" * 60)
print("修复后：joinToString(\"\\n\")")
new = "\n".join(GREETINGS)
print("  拼接结果:", repr(new))
for i, seg in enumerate(split(new, fix=True), 1):
    print(f"  第{i}条 -> {seg!r}")

print("=" * 60)
print("兜底：即使上游还用句号拼接，tidy 也能拦住")
for i, seg in enumerate(split(old, fix=True), 1):
    print(f"  第{i}条 -> {seg!r}")

print("=" * 60)
print("回归：不该被误伤的正常句子")
for s in [
    "在张店这边。常来没。",
    "真的假的？！",
    "嗯……我想想",
    "电商运营，天天跟数据打交道",
    "可以啊，不归我得先了解一下你这个人。",
]:
    print(f"  {s!r} -> {split(s, fix=True)!r}")
