#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
textutil.py — 文本相似度工具

单独成文件是因为 gateway（选回复）和 necessity（决定是否开口）都要用，
放在任一模块里都会造成循环导入。
"""

from __future__ import annotations


def lcs_len(a: str, b: str) -> int:
    """
    最长公共子序列长度。

    中文没有词边界，用编辑距离反而被字符插入干扰；LCS 对"打了个折"和"打折"
    这类包含关系的判断更稳。实现是滚动数组，O(len(a) * len(b)) 空间 O(len(b))。
    """
    if not a or not b:
        return 0
    prev = [0] * (len(b) + 1)
    for ch in a:
        cur = [0] * (len(b) + 1)
        for j, ch2 in enumerate(b, 1):
            cur[j] = prev[j - 1] + 1 if ch == ch2 else max(prev[j], cur[j - 1])
        prev = cur
    return prev[-1]


def coverage(a: str, b: str) -> float:
    """
    LCS 长度占较短串的比例，用来判断两句话是不是在说同一件事。

    为什么不能直接用 LCS 的绝对值：中文里"这个"两个字就能凑出 2，
    "这个在哪买" 和 "这个桌子承重多少" 的 LCS 正好是 2，
    用绝对阈值判断就会把买家的购买意图匹配到承重参数上——答非所问。
    归一化之后，短句要求几乎完全一致，长句允许部分重合。
    """
    m = min(len(a), len(b))
    if m == 0:
        return 0.0
    return lcs_len(a, b) / m
