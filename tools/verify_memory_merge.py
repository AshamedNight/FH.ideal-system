#!/usr/bin/env python3
"""
ConversationMemory.merge 的算法复刻 + 场景验证。

为什么用 Python 复刻：本机没有 JDK/Android SDK，跑不了单元测试；
而"接缝"这类纯逻辑错误在真机上的表现是"聊长了回复对不上"，很难一眼看出来。
这里把同一套判定写成 Python，用几个真实形状的窗口跑一遍，
断言"该续的续上、该忽略的不倒退、换人必须重置"。

对应源码：app/src/main/java/com/autoreply/app/core/llm/ConversationMemory.kt
改那边时记得同步改这里，否则这个脚本会变成假的"已通过"。
"""

MAX_TURNS = 40
OVERLAP_MAX = 6


class Turn:
    def __init__(self, text, from_other):
        self.text = text
        self.from_other = from_other

    def key(self):
        return (self.text.strip(), self.from_other)

    def __repr__(self):
        return ("对方:" if self.from_other else "我方:") + self.text


def same(a, b):
    return a.key() == b.key()


def tail_overlap(cached, fresh):
    k_max = min(OVERLAP_MAX, len(cached), len(fresh))
    for k in range(k_max, 0, -1):
        if all(same(cached[len(cached) - k + i], fresh[i]) for i in range(k)):
            return k
    return 0


def is_older_window(fresh, cached):
    if len(fresh) > len(cached):
        return False
    j = 0
    for t in fresh:
        while j < len(cached) and not same(cached[j], t):
            j += 1
        if j >= len(cached):
            return False
        j += 1
    return True


def merge(cached, fresh):
    """返回 (结果列表, 判定标签)。"""
    fresh = [t for t in fresh if t.text.strip()]
    if not fresh:
        return list(cached), "空读"
    if not cached:
        return fresh[-MAX_TURNS:], "初始化"

    k = tail_overlap(cached, fresh)
    if k > 0:
        out = cached + fresh[k:]
        return out[-MAX_TURNS:], f"尾部重叠 {k}"

    if is_older_window(fresh, cached):
        return list(cached), "旧窗口（用户翻历史，保持不动）"

    anchor = cached[-1]
    at = -1
    for i in range(len(fresh) - 1, -1, -1):
        if same(fresh[i], anchor):
            at = i
            break
    if at >= 0:
        out = cached + fresh[at + 1:]
        return out[-MAX_TURNS:], f"锚点 #{at}"

    return fresh[-MAX_TURNS:], "对不上 → 重置"


def t(*pairs):
    return [Turn(s, o) for s, o in pairs]


def show(case, got, want, tag):
    ok = [x.key() for x in got] == [x.key() for x in want]
    print(("  ✓ " if ok else "  ✗ ") + f"{case}  [{tag}]")
    if not ok:
        print("      期望:", want)
        print("      实际:", got)
    return ok


def main():
    ok = True

    # 1. 正常续聊：屏幕窗口顶部就是记忆的最后一条
    cached = t(("a", True), ("b", False), ("c", True))
    fresh = t(("c", True), ("d", False))
    got, tag = merge(cached, fresh)
    ok &= show("正常续聊", got, t(("a", True), ("b", False), ("c", True), ("d", False)), tag)

    # 2. 老了被顶上去：记忆很长，屏幕上只剩最后几轮 + 新的一句
    cached = t(*[(f"m{i}", i % 2 == 0) for i in range(10)])
    fresh = t(*[(f"m{i}", i % 2 == 0) for i in range(5, 10)], ("new", True))
    got, tag = merge(cached, fresh)
    ok &= show("窗口滑动", got, t(*[(f"m{i}", i % 2 == 0) for i in range(10)], ("new", True)), tag)

    # 3. 用户往上翻历史：这一屏全是"过去"，绝不能把记忆倒退回十分钟前
    cached = t(*[(f"m{i}", i % 2 == 0) for i in range(10)])
    fresh = t(("m2", True), ("m3", False), ("m4", True))
    got, tag = merge(cached, fresh)
    ok &= show("翻历史不倒退", got, cached, tag)

    # 4. 换人 / 消息被清理：三条判定全不成立 → 必须重置
    got, tag = merge(cached, t(("x", True), ("y", False)))
    ok &= show("换人重置", got, t(("x", True), ("y", False)), tag)

    # 5. 屏幕顶部多出一条更早的（记忆里没有）：重叠为 0，但锚点在中间 → 仍能续
    cached = t(("a", True), ("b", False), ("c", True))
    fresh = t(("x", True), ("a", True), ("b", False), ("c", True), ("d", False))
    got, tag = merge(cached, fresh)
    ok &= show("顶部多一条", got, t(("a", True), ("b", False), ("c", True), ("d", False)), tag)

    # 6. 同一句话被说过两次：只该续上后面真正的新消息，不能重复
    cached = t(("在吗", True), ("在", False), ("在吗", True))
    fresh = t(("在吗", True), ("聊会儿", False))
    got, tag = merge(cached, fresh)
    ok &= show("重复句子", got, t(("在吗", True), ("在", False), ("在吗", True), ("聊会儿", False)), tag)

    # 7. 同文不同向（我发的"好"和对方发的"好"）不能被当成同一条
    cached = t(("好", False))
    fresh = t(("好", True))
    got, tag = merge(cached, fresh)
    ok &= show("同文不同向", got, t(("好", True)), tag)

    print()
    print("全部通过" if ok else "有场景不符，先修算法")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
