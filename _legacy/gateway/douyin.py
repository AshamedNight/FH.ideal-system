#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
douyin.py — 抖音评论获客链路（完整版）

为什么单独成文件
----------------
评论回复的骨架在 adapters.py 里已经能跑，但那只够演示。
真跑起来会碰到四件骨架没处理的事：

  1. access_token 只有 15 天，refresh_token 30 天，最长续期 195 天
     —— 不处理就是第 16 天整条链路静默停摆
  2. 同一条评论会被反复拉到 —— 不去重就会重复回复
  3. 接口有 QPS 限制 —— 不限流会被频控
  4. 错误码分很多种 —— 有的能自动恢复，有的必须人工重新授权

这四件事都不是"加个功能"，是"不加就跑不下去"。

依赖：仅标准库。所有网络访问都经过可注入的 transport，
所以不联网也能跑测试和 demo。

抖音接口事实（据开放平台文档）：
  GET  /oauth/access_token/       用授权码换 token
  GET  /oauth/refresh_token/      刷新 token
  GET  /video/comment/list/       评论列表
  POST /video/comment/reply/      回复评论

  access_token  有效期 15 天
  refresh_token 有效期 30 天
  错误码 10008 / 2190008 → access_token 过期，可刷新
  错误码 10010          → refresh_token 过期，必须重新授权
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

from gateway import ChannelAdapter, Inbound, Reply, Surface
from works import detect_mention, strip_mention

BASE = "https://open.douyin.com"

# access_token 过期错误码（两个值平台都在用，必须都判）
ERR_TOKEN_EXPIRED = {10008, 2190008}
# refresh_token 过期，刷新也救不回来，只能重新走用户授权
ERR_REFRESH_EXPIRED = 10010


# ---------------------------------------------------------------------------
# 传输层：默认走 urllib，测试时注入假实现
# ---------------------------------------------------------------------------

Transport = Callable[[str, str, dict], dict]


def _default_transport(method: str, url: str, params: dict) -> dict:
    try:
        if method == "GET":
            full = f"{url}?{urllib.parse.urlencode(params)}"
            with urllib.request.urlopen(full, timeout=15) as r:
                return json.loads(r.read().decode("utf-8"))
        body = urllib.parse.urlencode(params).encode("utf-8")
        req = urllib.request.Request(
            url, data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        return {"_error": str(exc)}


# ---------------------------------------------------------------------------
# 令牌
# ---------------------------------------------------------------------------

@dataclass
class Token:
    access_token: str
    refresh_token: str
    open_id: str
    expires_at: float          # unix 时间戳

    def expired(self, margin: float = 3600) -> bool:
        """预留一小时余量：本地判断没过期、请求路上过期，同样会失败。"""
        return time.time() >= self.expires_at - margin


class TokenStore:
    """令牌落盘。放在本地文件而不是内存，因为进程重启很常见。"""

    def __init__(self, path: str | Path):
        self.path = Path(path)

    def load(self) -> Token | None:
        if not self.path.exists():
            return None
        try:
            d = json.loads(self.path.read_text(encoding="utf-8"))
            return Token(**d)
        except (json.JSONDecodeError, TypeError, KeyError):
            # 文件损坏时当作没有令牌，走重新授权，不要让程序崩
            return None

    def save(self, token: Token) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(asdict(token), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


# ---------------------------------------------------------------------------
# 限流
# ---------------------------------------------------------------------------

class RateLimiter:
    """
    最小间隔限流。比令牌桶简单，但足够用——
    评论拉取本来就是低频操作，真正需要控制的是"别在几秒内打十几次"。
    """

    def __init__(self, min_interval: float = 1.0):
        self.min_interval = min_interval
        self._last = 0.0

    def wait(self) -> float:
        """返回实际等待秒数。测试时把 min_interval 设 0 即可跳过。"""
        now = time.time()
        gap = now - self._last
        slept = 0.0
        if gap < self.min_interval:
            slept = self.min_interval - gap
            time.sleep(slept)
        self._last = time.time()
        return slept


# ---------------------------------------------------------------------------
# 去重
# ---------------------------------------------------------------------------

class SeenStore:
    """
    已处理的评论 id。用 jsonl 追加，不做数据库——
    量级是每天几百条，文件完全够，而且出问题能直接打开看。
    """

    def __init__(self, path: str | Path, keep_days: float = 30.0):
        self.path = Path(path)
        self.keep_days = keep_days
        self._ids: dict[str, float] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        cutoff = time.time() - self.keep_days * 86400
        try:
            for line in self.path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                rec = json.loads(line)
                ts = float(rec.get("ts", 0))
                if ts >= cutoff:
                    self._ids[str(rec.get("id", ""))] = ts
        except (json.JSONDecodeError, ValueError, OSError):
            # 单行坏了不影响整体，重置即可
            self._ids = {}

    def seen(self, cid: str) -> bool:
        return cid in self._ids

    def mark(self, cid: str) -> None:
        if cid in self._ids:
            return
        ts = time.time()
        self._ids[cid] = ts
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"id": cid, "ts": ts}) + "\n")

    def __len__(self) -> int:
        return len(self._ids)


# ---------------------------------------------------------------------------
# 客户端
# ---------------------------------------------------------------------------

class DouyinError(Exception):
    """带错误码的异常，便于上层区分"能不能自动恢复"。"""

    def __init__(self, code: int, message: str):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message

    @property
    def recoverable(self) -> bool:
        """token 过期可刷新恢复；refresh_token 过期和其它错误都要人工介入。"""
        return self.code in ERR_TOKEN_EXPIRED


class DouyinClient:
    """
    抖音 API 客户端。三层职责：
      1. 令牌生命周期（过期判断 + 自动刷新）
      2. 请求节流
      3. 错误码分类

    不做业务判断——判断在 Hub 里，这里只管"能不能把请求发出去"。
    """

    def __init__(self, client_key: str, client_secret: str, open_id: str,
                 token_store: TokenStore,
                 transport: Transport | None = None,
                 min_interval: float = 1.0,
                 on_reauth_needed: Callable[[str], None] | None = None):
        self.client_key = client_key
        self.client_secret = client_secret
        self.open_id = open_id
        self.store = token_store
        self.transport = transport or _default_transport
        self.limiter = RateLimiter(min_interval)
        self.on_reauth_needed = on_reauth_needed or (lambda msg: None)
        self._token: Token | None = token_store.load()

    # ---------- 令牌 ----------

    @property
    def token(self) -> Token | None:
        return self._token

    def set_token(self, access_token: str, refresh_token: str,
                  expires_in: float = 15 * 86400) -> Token:
        """授权流程拿到的 code 换完 token 后调用。"""
        t = Token(
            access_token=access_token,
            refresh_token=refresh_token,
            open_id=self.open_id,
            expires_at=time.time() + expires_in,
        )
        self._token = t
        self.store.save(t)
        return t

    def ensure_token(self) -> Token:
        """
        确保有可用令牌。过期就刷新，刷新失败就明确报错而不是继续请求。

        这里刻意不吞异常：令牌问题必须在日志里显式暴露，
        否则表现是"回复莫名其妙不发了"，排查成本极高。
        """
        if self._token is None:
            raise DouyinError(ERR_REFRESH_EXPIRED,
                              "没有令牌，需要先完成用户授权")
        if not self._token.expired():
            return self._token

        resp = self.transport("GET", f"{BASE}/oauth/refresh_token/", {
            "client_key": self.client_key,
            "grant_type": "refresh_token",
            "refresh_token": self._token.refresh_token,
        })
        data = resp.get("data") or {}
        code = int(data.get("error_code", 0) or 0)

        if code == ERR_REFRESH_EXPIRED:
            # refresh_token 也过期了，只能重新授权
            self.on_reauth_needed("refresh_token 已过期，需要重新完成抖音账号授权")
            raise DouyinError(code, "refresh_token 已过期，需要重新授权")
        if code not in (0,) or not data.get("access_token"):
            raise DouyinError(code or -1,
                              f"刷新令牌失败：{data.get('description') or resp}")

        # 刷新后 refresh_token 有效期不变，因此沿用旧的
        return self.set_token(
            access_token=data["access_token"],
            refresh_token=data.get("refresh_token") or self._token.refresh_token,
            expires_in=float(data.get("expires_in", 15 * 86400)),
        )

    # ---------- 请求 ----------

    def _call(self, method: str, path: str, params: dict,
              retry_on_expired: bool = True) -> dict:
        token = self.ensure_token()
        self.limiter.wait()
        resp = self.transport(method, f"{BASE}{path}", {
            **params,
            "open_id": self.open_id,
            "access_token": token.access_token,
        })

        data = resp.get("data") or {}
        code = int(data.get("error_code", 0) or 0)

        # access_token 可能在两次调用之间刚好过期：刷新一次再试，
        # 但只重试一次，避免令牌真的有问题时死循环。
        if code in ERR_TOKEN_EXPIRED and retry_on_expired:
            self._token.expires_at = 0        # 标记为过期，强制刷新
            return self._call(method, path, params, retry_on_expired=False)

        if code:
            raise DouyinError(code, str(data.get("description") or "调用失败"))
        return resp

    # ---------- 业务接口 ----------

    def list_comments(self, item_id: str, cursor: int = 0,
                      count: int = 20) -> tuple[list[dict], int]:
        """返回 (评论列表, 下一页 cursor)。has_more 为 0 时 cursor 原样返回。"""
        resp = self._call("GET", "/video/comment/list/", {
            "item_id": urllib.parse.quote(item_id, safe=""),
            "cursor": cursor,
            "count": count,
            "sort_type": "time",
        })
        d = resp.get("data") or {}
        comments = d.get("list") or []
        has_more = bool(d.get("has_more"))
        next_cursor = int(d.get("cursor", cursor)) if has_more else cursor
        return comments, next_cursor

    def reply_comment(self, item_id: str, comment_id: str, content: str) -> str:
        """
        回复一条评论。成功返回新评论 id，失败返回空串。

        返回值从 bool 改成 id，是因为发送后必须知道自己发了哪条：
        这条回复会出现在评论列表里，不记住就会在下一轮被自己回复一次。
        """
        # 缺任一标识都无法定位回复位置。这种情况说明上游数据有问题，
        # 直接拒掉比发出一条挂错位置的评论好——后者是公开可见的错误。
        if not item_id or not comment_id:
            return ""
        text = content.strip()[:500]        # 平台上限 500 字
        if not text:
            return ""
        resp = self._call("POST", "/video/comment/reply/", {
            "item_id": urllib.parse.quote(item_id, safe=""),
            "comment_id": comment_id,
            "content": text,
        })
        d = resp.get("data") or {}
        return str(d.get("comment_id") or "")

    def list_works(self, cursor: int = 0, count: int = 20) -> tuple[list[dict], int]:
        """
        拉取授权账号的作品列表。返回 (作品列表, 下一页 cursor)。

        平台限制：最多 4 页，每页 ≤20，不支持时间过滤。
        合计最多 80 条——这是硬上限，作品更多的账号，老视频的评论永远拉不到。
        """
        count = max(1, min(int(count), 20))
        resp = self._call("GET", "/video/list/", {
            "cursor": cursor,
            "count": count,
        })
        d = resp.get("data") or {}
        works = d.get("list") or []
        has_more = bool(d.get("has_more"))
        next_cursor = int(d.get("cursor", cursor)) if has_more else cursor
        return works, next_cursor

    def list_replies(self, item_id: str, comment_id: str,
                     cursor: int = 0, count: int = 20) -> tuple[list[dict], int]:
        """
        拉取某条评论下的回复（二级评论）。返回 (回复列表, 下一页 cursor)。

        不拉的话，用户在你回复下面的追问永远收不到——
        而追问的人，恰恰是意向最强的那一批。
        """
        count = max(1, min(int(count), 50))     # 回复列表单页上限 50
        resp = self._call("GET", "/video/comment/reply/list/", {
            "item_id": urllib.parse.quote(item_id, safe=""),
            "comment_id": urllib.parse.quote(comment_id, safe=""),
            "cursor": cursor,
            "count": count,
            "sort_type": "time",
        })
        d = resp.get("data") or {}
        replies = d.get("list") or []
        has_more = bool(d.get("has_more"))
        next_cursor = int(d.get("cursor", cursor)) if has_more else cursor
        return replies, next_cursor


# ---------------------------------------------------------------------------
# 适配器
# ---------------------------------------------------------------------------

class DouyinAdapter(ChannelAdapter):
    """
    抖音评论适配器。

    auto_send_allowed 为 True：官方通道技术上能发。
    但"能不能自己发出去"不在这里决定——那由 Hub 的三层闸门管：
      风控结论 → 渠道能力 → 分级放权（autopilot）
    在适配器层硬拦会有一个副作用：autopilot 永远没机会判断，
    于是"要自动化"就变成了"永远全人工"。适配器只回答技术问题。

    这一版补上了三件"不补就跑不起来"的事：

      1. 作品自动发现（传 registry 时）。item_ids 手写死等于没有后台。
      2. 自回自答过滤。自己发的回复也会出现在评论列表里，
         不过滤就是自己回自己，能无限循环。
      3. 二级评论。用户在你回复下面的追问，是意向最强的一批人。
    """

    name = "douyin"
    auto_send_allowed = True

    def __init__(self, client: DouyinClient, item_ids: list[str] | None = None,
                 seen: SeenStore | None = None,
                 page_size: int = 20, max_pages: int = 3,
                 registry=None,
                 own_user_ids: list[str] | None = None,
                 my_names: list[str] | None = None,
                 watch_replies: bool = True,
                 reply_page_size: int = 20, max_reply_pages: int = 1,
                 own_cache: str | Path | None = None):
        self.client = client
        self.item_ids = list(item_ids or [])
        # 必须用 `is not None` 而不是 `seen or ...`：
        # SeenStore 定义了 __len__，一个空的 SeenStore 在布尔判断里是 False，
        # 用 or 会把调用方传入的实例悄悄丢掉，退回默认路径。
        # 症状是测试之间互相污染、去重结果对不上，且完全不会报错。
        self.seen = seen if seen is not None else SeenStore("douyin_seen.jsonl")
        self.page_size = page_size
        # 每轮最多翻几页：防止一次拉太多被频控，也防止首次接入时刷屏
        self.max_pages = max_pages

        # 作品自动发现。不传则退回手写 item_ids（演示与测试用）。
        self.registry = registry

        # 自己的 comment_user_id。抖音评论返回里没有 is_author 字段，
        # 只能拿 comment_user_id 和自己的比对。
        self.own_user_ids = {str(x) for x in (own_user_ids or []) if x}
        self.my_names = [str(x) for x in (my_names or []) if x]

        self.watch_replies = watch_replies
        self.reply_page_size = reply_page_size
        self.max_reply_pages = max_reply_pages

        # 自己发出过的评论 id。第二道自回自答防线：
        # own_user_ids 可能因为配置缺失而失效，发送记录不会。
        # 必须落盘——否则重启后第一批就是自己的历史回复。
        self.own_cache = Path(own_cache) if own_cache else None
        self._own_comment_ids: set[str] = set()
        self._load_own()

        # 父评论 id → 上次扫到的回复总数。只有总数变了才重扫，
        # 免得每轮都把同一条 thread 的回复翻一遍。
        self._reply_scan_state: dict[str, int] = {}

        self.last_errors: list[str] = []
        self.skipped_own = 0        # 本轮跳过的自己发的评论数
        self.mention_hits = 0       # 本轮识别到的"被 @"条数

    # ---------- 自己的评论记录 ----------

    def _load_own(self) -> None:
        if not self.own_cache or not self.own_cache.exists():
            return
        try:
            d = json.loads(self.own_cache.read_text(encoding="utf-8"))
            self._own_comment_ids = {str(x) for x in d.get("ids", [])}
        except (json.JSONDecodeError, OSError, TypeError):
            self._own_comment_ids = set()

    def _save_own(self) -> None:
        if not self.own_cache:
            return
        self.own_cache.parent.mkdir(parents=True, exist_ok=True)
        # 只留最近 500 条：够覆盖在线的评论区，文件也不会无限长
        ids = list(self._own_comment_ids)[-500:]
        self.own_cache.write_text(
            json.dumps({"ids": ids}, ensure_ascii=False), encoding="utf-8")

    def remember_own(self, comment_id: str) -> None:
        """把自己发出的评论 id 记下来，供下一轮过滤。"""
        if not comment_id:
            return
        self._own_comment_ids.add(str(comment_id))
        self._save_own()

    def _is_own(self, c: dict) -> bool:
        cid = str(c.get("comment_id") or "")
        if cid and cid in self._own_comment_ids:
            return True
        uid = str(c.get("comment_user_id")
                  or (c.get("user") or {}).get("open_id")
                  or (c.get("user") or {}).get("uid")
                  or "")
        return bool(uid and uid in self.own_user_ids)

    # ---------- 拉取 ----------

    def poll(self) -> list[Inbound]:
        out: list[Inbound] = []
        self.last_errors.clear()
        self.skipped_own = 0
        self.mention_hits = 0

        # 1) 作品发现。失败不阻断本轮——用上次缓存的列表继续跑，
        #    否则一次 4xx 会让整个服务停摆。
        if self.registry is not None:
            try:
                self.registry.refresh()
            except Exception as exc:          # noqa: BLE001
                self.last_errors.append(f"作品列表刷新失败：{exc}")

        item_ids = self.registry.item_ids if self.registry is not None else self.item_ids

        # 2) 逐作品拉顶级评论
        for item_id in item_ids:
            if not item_id:
                continue
            try:
                self._scan_item(item_id, out)
            except DouyinError as exc:
                # 单条视频失败不影响其它视频
                self.last_errors.append(f"{item_id}: {exc}")
            except Exception as exc:          # noqa: BLE001
                self.last_errors.append(f"{item_id}: 未知错误 {exc}")

        return out

    def _scan_item(self, item_id: str, out: list[Inbound]) -> None:
        cursor = 0
        for _ in range(self.max_pages):
            comments, next_cursor = self.client.list_comments(
                item_id, cursor=cursor, count=self.page_size)

            for c in comments:
                self._handle_comment(item_id, c, out)

            if next_cursor == cursor:
                break
            cursor = next_cursor

    def _handle_comment(self, item_id: str, c: dict,
                        out: list[Inbound], parent_id: str = "") -> None:
        cid = str(c.get("comment_id") or "")
        if not cid:
            return

        mine = self._is_own(c)

        # 自己的评论不产生入站消息，但仍然要看它下面的回复——
        # 用户在我回复下面的追问，正是最该接住的那批。
        if mine:
            self.skipped_own += 1
        elif not self.seen.seen(cid):
            self.seen.mark(cid)
            out.append(self._to_inbound(item_id, c, parent_id=parent_id))

        if self.watch_replies:
            self._scan_replies(item_id, c, out, parent_is_mine=mine)

    def _scan_replies(self, item_id: str, parent: dict,
                      out: list[Inbound], parent_is_mine: bool) -> None:
        pid = str(parent.get("comment_id") or "")
        total = int(parent.get("reply_comment_total") or 0)
        if not pid or total <= 0:
            return
        # 回复数没变说明没有新回复，跳过。用总数而不是时间，
        # 因为回复列表接口没有可靠的"更新于"字段。
        if self._reply_scan_state.get(pid) == total:
            return
        self._reply_scan_state[pid] = total

        cursor = 0
        for _ in range(self.max_reply_pages):
            try:
                replies, nxt = self.client.list_replies(
                    item_id, pid, cursor=cursor, count=self.reply_page_size)
            except DouyinError as exc:
                self.last_errors.append(f"{item_id}/{pid} 回复列表：{exc}")
                return

            for r in replies:
                if self._is_own(r):
                    self.skipped_own += 1
                    continue
                rid = str(r.get("comment_id") or "")
                if not rid or self.seen.seen(rid):
                    continue
                self.seen.mark(rid)
                # 只有"回复的是我发的评论"才算被点名。
                # 两个路人在我的评论区互相聊，不该插嘴。
                out.append(self._to_inbound(
                    item_id, r, parent_id=pid, force_mention=parent_is_mine))

            if nxt == cursor:
                break
            cursor = nxt

    def _to_inbound(self, item_id: str, c: dict, parent_id: str = "",
                    force_mention: bool = False) -> Inbound:
        raw_text = str(c.get("content") or "")
        hit = detect_mention(raw_text, self.my_names)
        mentioned = bool(force_mention or hit)
        if mentioned:
            self.mention_hits += 1
        # 命中 @我 时把 @ 片段去掉：正文里的「@小陆」会白送两个字的重合度，
        # 让语料匹配误判成"问过类似问题"。
        text = strip_mention(raw_text) if hit else raw_text

        return Inbound(
            channel=self.name,
            conv_id=str(c.get("comment_id") or ""),
            sender=str((c.get("user") or {}).get("nickname")
                       or c.get("nick_name") or "unknown"),
            text=text,
            # 用评论的发布时间，不是拉取时间。
            # 否则一批历史评论的处理时刻都相同，会被刷屏规则全部降权。
            ts=float(c.get("create_time") or time.time()),
            surface=Surface.COMMENT,
            thread=item_id,
            is_mentioned=mentioned,
            parent_id=parent_id,
            raw=c,
        )

    def send(self, reply: Reply) -> bool:
        if not reply.text.strip():
            return False
        try:
            new_id = self.client.reply_comment(
                reply.thread, reply.conv_id, reply.text)
        except DouyinError as exc:
            self.last_errors.append(f"回复失败：{exc}")
            return False
        if not new_id:
            self.last_errors.append("回复接口未返回 comment_id，无法防止自回自答")
            return False
        self.remember_own(new_id)
        return True


# ---------------------------------------------------------------------------
# 演示：不联网跑完整个链路
# ---------------------------------------------------------------------------

def demo() -> int:
    """
    用假 transport 模拟抖音接口，把整条链路跑一遍。

    演示四件真跑时一定会遇到、但骨架没处理的事：
      ① 令牌过期 → 自动刷新后继续，业务层无感
      ② 第二次拉取同一批评论 → 去重生效，返回 0 条
      ③ 评论分四类结局 → 接意图 / 沉默 / 拦截 / 入人工队列
      ④ refresh_token 也过期 → 明确报错，不静默失败
    """
    import tempfile
    from gateway import Hub, MockEngine, Persona

    persona = Persona.load(Path(__file__).parent / "persona.json")
    workdir = Path(tempfile.mkdtemp(prefix="douyin_demo_"))

    COMMENTS = {"data": {"error_code": 0, "has_more": 0, "list": [
        {"comment_id": "c1", "content": "求链接", "user": {"nickname": "小王"}},
        {"comment_id": "c2", "content": "这个好看", "user": {"nickname": "小李"}},
        {"comment_id": "c3", "content": "这个在哪买", "user": {"nickname": "小张"}},
        {"comment_id": "c4", "content": "能便宜点吗", "user": {"nickname": "小赵"}},
    ]}}

    counters = {"refresh": 0, "list": 0, "token_state": "expired"}

    def fake_transport(method: str, url: str, params: dict) -> dict:
        if url.endswith("/oauth/refresh_token/"):
            counters["refresh"] += 1
            counters["token_state"] = "refreshed"
            return {"data": {"error_code": 0, "access_token": "fresh_token",
                             "expires_in": 15 * 86400}}
        if url.endswith("/video/comment/list/"):
            counters["list"] += 1
            return COMMENTS
        if url.endswith("/video/comment/reply/"):
            return {"data": {"error_code": 0}}
        return {"data": {"error_code": 0}}

    client = DouyinClient(
        client_key="demo_key", client_secret="demo_secret", open_id="demo_open_id",
        token_store=TokenStore(workdir / "token.json"),
        transport=fake_transport, min_interval=0,
        on_reauth_needed=lambda msg: print(f"  [需重新授权] {msg}"),
    )
    # 造一个"已过期"的令牌，触发第一次自动刷新
    client.set_token("old_token", "old_refresh", expires_in=-10)

    adapter = DouyinAdapter(client, ["item_1001"],
                            seen=SeenStore(workdir / "seen.jsonl"))
    hub = Hub(persona, MockEngine(), rate_limit=10, necessity=True)
    hub.register(adapter, channels=["douyin"])

    print("=" * 74)
    print("抖音评论链路演示 · 假 transport，不联网")
    print("=" * 74)

    print(f"\n① 首次拉取（令牌状态：{counters['token_state']}）")
    hub.step()
    for line in hub.log:
        print("   " + line)
    print(f"   → 令牌刷新 {counters['refresh']} 次｜评论接口调用 {counters['list']} 次")

    print("\n② 再次拉取同一批评论（验证去重）")
    before = len(hub.log)
    hub.step()
    new_lines = hub.log[before:]
    print(f"   → 新增处理 {len(new_lines)} 条（应为 0）")

    print("\n③ 结局分布")
    print(f"   待人工确认 {len(hub.pending)} 条｜沉默 {len(hub.silent)} 条"
          f"｜拦截 {len(hub.blocked)} 条")
    for r in hub.blocked:
        print(f"   拦截原因：{r.reason}")

    print("\n④ refresh_token 也过期时的行为")
    try:
        client.set_token("x", "expired_refresh", expires_in=-10)

        def failing(method, url, params):
            if url.endswith("/oauth/refresh_token/"):
                return {"data": {"error_code": ERR_REFRESH_EXPIRED,
                                 "description": "refresh token expired"}}
            return {"data": {"error_code": 0}}

        client.transport = failing
        client.ensure_token()
        reauth_ok = False
    except DouyinError as exc:
        reauth_ok = (exc.code == ERR_REFRESH_EXPIRED and not exc.recoverable)
        print(f"   → 正确抛出不可恢复错误：{exc}")

    ok_new = len(hub.pending) == 2
    ok_silent = len(hub.silent) == 1
    ok_blocked = len(hub.blocked) == 1
    ok_dedupe = len(new_lines) == 0
    ok_refresh = counters["refresh"] >= 1
    ok = all([ok_new, ok_silent, ok_blocked, ok_dedupe, ok_refresh, reauth_ok])

    print("\n" + "=" * 74)
    print(f"自检：意图进队列 {'✓' if ok_new else '✗'}｜夸赞沉默 {'✓' if ok_silent else '✗'}"
          f"｜议价拦截 {'✓' if ok_blocked else '✗'}｜去重生效 {'✓' if ok_dedupe else '✗'}"
          f"｜令牌自动刷新 {'✓' if ok_refresh else '✗'}"
          f"｜过期可识别 {'✓' if reauth_ok else '✗'}")
    print("结果：" + ("通过" if ok else "链路异常"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(demo())
