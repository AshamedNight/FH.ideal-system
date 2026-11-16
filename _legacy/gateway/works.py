#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
works.py — 作品发现与"被 @"识别

为什么需要这个文件
------------------
douyin.py 里的 DouyinAdapter 收的是 item_ids 列表，等于说每发一条新视频，
都得有人手动把 id 填进去。这不是"后台"，是玩具。

真跑要解决两件事：

  1. 新作品自动纳入监听。靠 /video/list/ 分页拉取，定期刷新。
  2. 旧作品自动移出监听（删除、下架、私密化），否则会一直对着 404 发请求。

关于"被 @"的一个硬事实
----------------------
抖音没有"@我的"消息流接口。开放平台的互动管理只有四个动作：
评论列表 / 评论回复列表 / 回复评论 / 置顶评论，全部以 item_id 为入口。

所以"被 @"这件事只能这样做：
  遍历自己所有作品的评论 → 在文本里找 @我的昵称

这条路有三个天然缺陷，写在代码里也写在文档里：
  · 只能覆盖"评论区里 @ 我"，覆盖不了"发布视频时 @ 我"
  · 昵称可以改、可以重名，文本匹配会有误判和漏判
  · 靠轮询发现，不是实时推送，最坏延迟等于一个轮询周期

依赖：仅标准库。
"""

from __future__ import annotations

import json
import re
import time
from pathlib import Path

# @ 后面跟的昵称。抖音昵称可以含中文、字母、数字、下划线、点、短横线，
# 但不能含空格——遇到空格就认为昵称结束了。
_MENTION_RE = re.compile(r"@([^\s@]{1,24})")


def detect_mention(text: str, my_names: list[str]) -> str:
    """
    从评论正文里找出"是否 @ 了我"。命中返回昵称，否则返回空串。

    为什么用双向包含而不是等值比较
    ------------------------------
    抖音在正文里渲染昵称时会截断。我的昵称是「小陆的木工房」，
    评论里可能显示成「@小陆的木工房 」也可能是「@小陆的木工… 」。
    等值比较会漏掉截断的情况，双向包含能覆盖。

    代价是短昵称容易误伤：昵称「陆」会匹配到「@陆家嘴」。
    所以调用方应该传足够长的昵称，代码里也对过短的昵称做了保护。
    """
    if not text or not my_names:
        return ""
    # 昵称太短（1 个字）时双向包含几乎必然误判，直接不做匹配
    names = [n for n in my_names if n and len(n) >= 2]
    if not names:
        return ""

    for candidate in _MENTION_RE.findall(text):
        for name in names:
            if candidate == name:
                return name
            # 截断渲染：候选是昵称的前缀
            if len(candidate) >= 2 and name.startswith(candidate):
                return name
            # 正文里带了后缀标点或表情残留：昵称是候选的前缀
            if candidate.startswith(name):
                return name
    return ""


def strip_mention(text: str) -> str:
    """
    去掉正文里的 @ 片段，剩下的才是用户真正说的话。

    「@小陆 这个承重多少」去掉后是「这个承重多少」。
    不去掉的话，问句特征和语料匹配都会受干扰——
    "@小陆" 这两个字会白白拉高与语料的重合度。
    """
    return _MENTION_RE.sub("", text or "").strip()


# ---------------------------------------------------------------------------
# 作品注册表
# ---------------------------------------------------------------------------

class WorkRegistry:
    """
    维护"当前在监听的作品"。

    刷新策略是"全量拉取 + 差集比较"，不是增量游标。
    原因是 /video/list/ 不支持时间过滤，也没有"更新于"字段，
    只能每次从头拉一遍再比对。好在最多 4 页，成本可以接受。

    平台限制（必须知道）：
      · 最多 4 页，每页 ≤20 → 单个账号最多只能监听到 80 条作品
      · 超过 80 条的作品，其评论永远拉不到
    这是接口决定的，不是实现偷懒。
    """

    MAX_PAGES = 4
    PAGE_SIZE = 20            # /video/list/ 单页上限
    HARD_LIMIT = MAX_PAGES * PAGE_SIZE

    def __init__(self, client, cache_path: str | Path | None = None,
                 ttl: float = 3600.0, max_works: int = HARD_LIMIT):
        self.client = client
        self.cache_path = Path(cache_path) if cache_path else None
        self.ttl = ttl
        # 平台硬上限 80，配置再大也没用，这里直接夹住
        self.max_works = min(int(max_works), self.HARD_LIMIT)
        self._ids: list[str] = []
        self._meta: dict[str, dict] = {}
        self._updated_at = 0.0
        self._truncated = False       # 是否因为超过 80 条而截断
        self._load()

    # ---------- 持久化 ----------

    def _load(self) -> None:
        if not self.cache_path or not self.cache_path.exists():
            return
        try:
            d = json.loads(self.cache_path.read_text(encoding="utf-8"))
            self._ids = [str(x) for x in d.get("ids", [])]
            self._meta = d.get("meta", {}) or {}
            self._updated_at = float(d.get("updated_at", 0))
            self._truncated = bool(d.get("truncated", False))
        except (json.JSONDecodeError, OSError, TypeError, ValueError):
            # 缓存坏了就当作空的，下次刷新会重建。不要因为缓存问题启动失败。
            self._ids, self._meta, self._updated_at = [], {}, 0.0

    def _save(self) -> None:
        if not self.cache_path:
            return
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(json.dumps({
            "ids": self._ids,
            "meta": self._meta,
            "updated_at": self._updated_at,
            "truncated": self._truncated,
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---------- 查询 ----------

    @property
    def item_ids(self) -> list[str]:
        return list(self._ids)

    @property
    def truncated(self) -> bool:
        return self._truncated

    def stale(self) -> bool:
        return (time.time() - self._updated_at) > self.ttl

    # ---------- 刷新 ----------

    def refresh(self, force: bool = False) -> tuple[list[str], list[str]]:
        """
        重新拉取作品列表。返回 (新增的 id, 消失的 id)。

        调用方拿这两个列表做两件事：
          · 新增 → 加进监听，并立刻拉一次评论（新视频往往前几小时评论最密集）
          · 消失 → 从监听移除，同时清掉该作品下评论的去重记录
        """
        if not force and not self.stale() and self._ids:
            return [], []

        collected: list[dict] = []
        cursor = 0
        for _ in range(self.MAX_PAGES):
            works, next_cursor = self.client.list_works(
                cursor=cursor, count=self.PAGE_SIZE)
            collected.extend(works)
            if next_cursor == cursor or not works:
                break
            cursor = next_cursor

        # 按发布时间倒序，超出上限时丢最老的。
        # 宁可漏掉老视频的评论，也不要漏掉新视频的——新视频才有转化。
        collected.sort(
            key=lambda w: float(w.get("create_time") or 0), reverse=True)
        self._truncated = len(collected) > self.max_works
        kept = collected[:self.max_works]

        new_ids = [str(w.get("item_id")) for w in kept if w.get("item_id")]
        new_set = set(new_ids)
        old_set = set(self._ids)

        self._ids = new_ids
        self._meta = {
            str(w.get("item_id")): {
                "create_time": w.get("create_time"),
                "title": (w.get("title") or w.get("desc") or "")[:40],
            }
            for w in kept if w.get("item_id")
        }
        self._updated_at = time.time()
        self._save()

        added = [i for i in new_ids if i not in old_set]
        removed = [i for i in old_set if i not in new_set]
        return added, removed
