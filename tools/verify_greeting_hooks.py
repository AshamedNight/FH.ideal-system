# -*- coding: utf-8 -*-
"""验算开场白的「槽位 + 游标轮换」行为。

本机没有 JDK，跑不了 Kotlin 单测。这里的做法是：
直接从 GreetingHooks.kt 里把 DEFAULT 那 20 条字符串读出来（不手抄，避免两份走偏），
再按 GreetingHooks.fill 和 ReplyEngine 里那段游标逻辑复刻一遍，看：

  1. 轮换是不是真的走完 20 条（旧实现按对方消息 hashCode 取模，只会用同一组）
  2. 昵称/原话填不上时，是不是跳过而不是发出字面的 {昵称}
  3. 昵称是纯数字（系统号）时会不会被拒
"""

import re
import pathlib

SRC = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/com/autoreply/app/core/llm/GreetingHooks.kt"
)

text = SRC.read_text(encoding="utf-8")
block = text.split("val DEFAULT: List<String> = listOf(", 1)[1].split("\n    )", 1)[0]
# 只取被双引号包起来的整行字符串，注释里的引号会带别的内容但不会单独成行
HOOKS = [m.group(1) for m in re.finditer(r'^\s*"([^"]*)",\s*$', block, re.M)]

SLOT_NAME, SLOT_ECHO, SLOT_TIME = "{昵称}", "{原话}", "{时段}"


def fill(line, name, echo, time_word):
    s = line
    if SLOT_NAME in s:
        n = name.strip()
        if not n or len(n) > 12 or n.isdigit():
            return None
        s = s.replace(SLOT_NAME, n)
    if SLOT_ECHO in s:
        e = echo.strip()
        if not e or len(e) > 12:
            return None
        s = s.replace(SLOT_ECHO, e)
    if SLOT_TIME in s:
        if not time_word:
            return None
        s = s.replace(SLOT_TIME, time_word)
    return s.strip() or None


def run(name, echo, time_word, sends, cursor=0, show=False):
    used, out = [], []
    for _ in range(sends):
        start = cursor
        picked = None
        for offset in range(len(HOOKS)):
            i = (start + offset) % len(HOOKS)
            got = fill(HOOKS[i], name, echo, time_word)
            if got is None:
                continue
            cursor = (i + 1) % len(HOOKS)
            picked = (i, got)
            break
        if picked is None:
            out.append(None)
            continue
        used.append(picked[0])
        out.append(picked[1])
    if show:
        for i, s in enumerate(out, 1):
            print(f"  {i:>2}. {s}")
    return used, out


print(f"从源码读到 {len(HOOKS)} 条钩子\n")

print("【1】正常情况：昵称=小雅 / 原话=你好 / 时段=深夜，连发 25 次")
used, out = run("小雅", "你好", "深夜", 25, show=True)
print(f"  用到过的库下标：{sorted(set(used))}  → 共 {len(set(used))} 条不同")
assert len(set(used)) == len(HOOKS), "轮换没走完全库"
assert all("{" not in s for s in out if s), "有花括号漏出来了"

print("\n【2】昵称读不到：带 {昵称} 的那几条应当被跳过，不报错")
used2, out2 = run("", "你好", "晚上", 40)
print(f"  用到的下标：{sorted(set(used2))}")
print(f"  被跳过的下标：{sorted(set(range(len(HOOKS))) - set(used2))}")
for idx in sorted(set(range(len(HOOKS))) - set(used2)):
    print(f"    [{idx}] {HOOKS[idx]}")
assert all("{" not in s for s in out2 if s), "有花括号漏出来了"

print("\n【3】昵称是系统号（纯数字）→ 同样跳过")
used3, _ = run("8823", "你好", "晚上", 40)
print(f"  跳过：{sorted(set(range(len(HOOKS))) - set(used3))}")

print("\n【4】对方原话太长（>12 字）→ 引用型那几条跳过，不硬引用")
long_echo = "你好，我看了你的资料觉得挺有意思的"
used4, _ = run("小雅", long_echo, "晚上", 40)
print(f"  跳过：{sorted(set(range(len(HOOKS))) - set(used4))}")
for idx in sorted(set(range(len(HOOKS))) - set(used4)):
    print(f"    [{idx}] {HOOKS[idx]}")

print("\n全部断言通过")
