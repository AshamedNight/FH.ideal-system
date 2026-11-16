#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adapters.py — 抖音 / 小红书评论适配器

这两个放在一起，是因为它们最能说明一件事：
**评论回复的逻辑和 IM 一样，但通道能力完全不同。**

  抖音   → 有官方开放接口，能拉评论、能发回复（需企业资质 + 互动管理权限）
  小红书 → 没有面向第三方应用的评论接口，只能半自动：出草稿，人工粘贴

所以同一个 Hub 挂两个适配器，行为天差地别，但上层代码一行都不用改。
这就是把适配器单独抽出来的意义。

依赖：仅标准库。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from gateway import ChannelAdapter, Inbound, Reply, Surface


class DouyinCommentAdapter(ChannelAdapter):
    """
    抖音评论适配器（官方开放平台）。

    前置条件：
      1. 企业主体 + 应用上线转正
      2. 申请「互动管理」权限，scope = video.comment
      3. 完成 OAuth 拿到 access_token 与 open_id

    接口：
      GET  /video/comment/list/    拉评论列表
      POST /video/comment/reply/   回复某条评论

    注意：参数名以抖音开放平台最新文档为准，这里按公开文档的字段写。
    平台改字段是常事，所以解析全部用 .get 链，缺字段不会崩，只会少取到数据。
    """

    name = "douyin"

    # 官方通道技术上能发，所以这里是 True。
    # "该不该自己发"由 Hub 的分级放权决定，不在适配器层硬拦——
    # 在这一层拦会让 autopilot 失去判断机会，结果就是永远全人工。
    auto_send_allowed = True

    BASE = "https://open.douyin.com"

    def __init__(self, open_id: str, access_token: str,
                 item_ids: list[str], poll_interval: float = 180.0) -> None:
        self.open_id = open_id
        self.access_token = access_token
        self.item_ids = list(item_ids)     # 要监听的视频 id
        self.poll_interval = poll_interval
        self._seen: set[str] = set()       # 已处理过的 comment_id，防重复回复
        self._last_poll = 0.0

    # ---------- 底层请求 ----------

    def _request(self, method: str, path: str, params: dict) -> dict:
        params = {**params, "access_token": self.access_token}
        url = f"{self.BASE}{path}"
        try:
            if method == "GET":
                with urllib.request.urlopen(
                    f"{url}?{urllib.parse.urlencode(params)}", timeout=15
                ) as r:
                    return json.loads(r.read().decode("utf-8"))
            body = urllib.parse.urlencode(params).encode("utf-8")
            req = urllib.request.Request(
                url, data=body,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            print(f"[douyin] {path} 请求失败：{exc}")
            return {}

    # ---------- 适配器接口 ----------

    def poll(self) -> list[Inbound]:
        now = time.time()
        # 官方接口有频率限制，别把轮询调太密
        if now - self._last_poll < self.poll_interval:
            return []
        self._last_poll = now

        out: list[Inbound] = []
        for item_id in self.item_ids:
            data = self._request("GET", "/video/comment/list/", {
                "open_id": self.open_id,
                "item_id": item_id,
                "cursor": 0,
                "count": 20,
                "sort_type": "time",
            })
            comments = (data.get("data") or {}).get("list") or []
            for c in comments:
                cid = str(c.get("comment_id", ""))
                if not cid or cid in self._seen:
                    continue
                self._seen.add(cid)
                out.append(Inbound(
                    channel=self.name,
                    conv_id=cid,                    # 评论 id：回复时要用
                    sender=str((c.get("user") or {}).get("nickname", "unknown")),
                    text=str(c.get("content", "")),
                    surface=Surface.COMMENT,
                    thread=item_id,                 # 视频 id：回复要挂在这
                    raw=c,
                ))
        return out

    def send(self, reply: Reply) -> bool:
        if not reply.text.strip():
            return False
        data = self._request("POST", "/video/comment/reply/", {
            "open_id": self.open_id,
            "item_id": reply.thread,        # 视频 id
            "comment_id": reply.conv_id,    # 被回复的评论 id
            "content": reply.text[:500],    # 平台限制 500 字
        })
        ok = data.get("data", {}).get("error_code", 0) == 0 and bool(data)
        if not ok:
            print(f"[douyin] 回复失败：{data.get('data') or data}")
        return ok


class XhsCommentAdapter(ChannelAdapter):
    """
    小红书评论适配器（半自动）。

    小红书没有面向第三方应用的评论读取/回复接口。市面上的方案都是浏览器自动化，
    依赖登录态、页面结构，且随时可能被风控打断。

    这里不假装自己有接口，只做一件确定有用的事：
    把生成的草稿落到 outbox 文件里，由你复制粘贴发布。

    这样设计的好处是——即使没有接口，整条链路（读意图 → 生成 → 审核 → 发布）
    依然是通顺的，只是最后一步由人完成。
    """

    name = "xhs"
    auto_send_allowed = False

    def __init__(self, outbox: str | Path | None = None) -> None:
        self.outbox = Path(outbox) if outbox else Path(__file__).parent / "outbox"
        self.outbox.mkdir(parents=True, exist_ok=True)
        self._queue: list[Inbound] = []

    # 外部把评论喂进来。可以来自剪贴板、浏览器插件、无障碍服务，或手动录入。
    def push_comment(self, note_id: str, comment_id: str,
                     sender: str, text: str) -> None:
        self._queue.append(Inbound(
            channel=self.name,
            conv_id=comment_id,
            sender=sender,
            text=text,
            surface=Surface.COMMENT,
            thread=note_id,
        ))

    def poll(self) -> list[Inbound]:
        out, self._queue = self._queue, []
        return out

    def send(self, reply: Reply) -> bool:
        """不真的发出去，写进 outbox 等你手动粘贴。"""
        if not reply.text.strip():
            return False
        record = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "note_id": reply.thread,
            "comment_id": reply.conv_id,
            "content": reply.text,
            "status": "pending_manual_publish",
        }
        path = self.outbox / "xhs_drafts.jsonl"
        with path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(f"[xhs] 草稿已写入 {path.name}，请手动粘贴到对应笔记的评论区")
        return True


# ---------------------------------------------------------------------------
# 演示：评论链路（不联网）
# ---------------------------------------------------------------------------

def comment_demo() -> int:
    """
    跑一遍评论回复的完整链路：四条评论，四种不同结局。

      求链接      → 生成草稿
      这个好看    → 沉默（不接夸赞）
      这个在哪买  → 生成草稿
      能便宜点吗  → 边界拦截（不报价）

    用小红书适配器，因为它不需要网络——它本来就是把草稿写进 outbox。
    """
    from gateway import Hub, MockEngine, Persona

    persona = Persona.load(Path(__file__).parent / "persona.json")

    # 用临时目录做 outbox：演示不应该污染真实草稿箱，
    # 也避免"先删旧文件"这种在受限环境里会失败的操作。
    import tempfile
    outbox = Path(tempfile.mkdtemp(prefix="xhs_outbox_"))
    adapter = XhsCommentAdapter(outbox)
    draft_file = outbox / "xhs_drafts.jsonl"

    hub = Hub(persona, MockEngine(), rate_limit=6, necessity=True,
              allow_public_auto=True)
    hub.register(adapter, channels=["xhs"])

    adapter.push_comment("note_1001", "c1", "用户A", "求链接")
    adapter.push_comment("note_1001", "c2", "用户B", "这个好看")
    adapter.push_comment("note_1001", "c3", "用户C", "这个在哪买")
    adapter.push_comment("note_1001", "c4", "用户D", "能便宜点吗")

    print("=" * 72)
    print("评论回复链路 · 公开场合的判定与拦截")
    print("=" * 72)

    hub.step()
    for line in hub.log:
        print("  " + line)

    print("-" * 72)
    print(f"待确认 {len(hub.pending)} 条｜沉默 {len(hub.silent)} 条｜"
          f"拦截 {len(hub.blocked)} 条")
    for r in hub.blocked:
        print(f"  拦截原因：{r.reason}")

    # 审批一条，验证草稿确实落到 outbox
    approved = hub.approve(0) if hub.pending else False
    print("-" * 72)
    print(f"人工放行：{'成功' if approved else '失败'}")

    written = draft_file.exists() and draft_file.stat().st_size > 0
    if written:
        first = draft_file.read_text(encoding="utf-8").strip().splitlines()[0]
        print(f"outbox 首条：{first}")

    ok_pending = len(hub.pending) == 1          # 一条已放行，剩一条
    ok_silent = len(hub.silent) == 1            # "这个好看" 不接
    ok_blocked = len(hub.blocked) == 1          # "能便宜点吗" 被拦
    ok = approved and written and ok_pending and ok_silent and ok_blocked

    print("=" * 72)
    print(f"自检：意图被接住 {'✓' if ok_pending else '✗'}｜"
          f"夸赞沉默 {'✓' if ok_silent else '✗'}｜"
          f"议价拦截 {'✓' if ok_blocked else '✗'}｜"
          f"草稿落盘 {'✓' if written else '✗'}")
    print("结果：" + ("通过" if ok else "链路异常"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(comment_demo())
