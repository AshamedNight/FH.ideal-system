#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_douyin.py — 抖音链路测试

不联网：所有请求都走注入的假 transport。
跑法：python -m unittest test_douyin -v

覆盖的都是"不加就跑不下去"的部分：
令牌过期与刷新、错误码分类、去重、限流、空内容拦截。
"""

import json
import tempfile
import unittest
from pathlib import Path

from douyin import (
    BASE, ERR_REFRESH_EXPIRED, ERR_TOKEN_EXPIRED,
    DouyinAdapter, DouyinClient, DouyinError, SeenStore, Token, TokenStore,
)
from gateway import Inbound, Reply, Surface


class FakeTransport:
    """按路径返回预设响应，并记录调用次数。"""

    def __init__(self, routes: dict):
        self.routes = routes
        self.calls: list[tuple[str, str, dict]] = []

    def __call__(self, method: str, url: str, params: dict) -> dict:
        self.calls.append((method, url, params))
        path = url.replace(BASE, "")
        route = self.routes.get(path)
        if route is None:
            return {"data": {"error_code": 0}}
        if isinstance(route, list):
            # 列表表示连续多次调用依次返回，最后一项复用
            return route.pop(0) if len(route) > 1 else route[0]
        return route

    def count(self, path: str) -> int:
        return sum(1 for _, u, _ in self.calls if u.endswith(path))


def tomorrow() -> float:
    import time
    return time.time() + 86400


def yesterday() -> float:
    import time
    return time.time() - 86400


class TestTokenStore(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_save_and_load_roundtrip(self):
        store = TokenStore(self.dir / "t.json")
        t = Token("acc", "ref", "oid", tomorrow())
        store.save(t)
        loaded = store.load()
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded.access_token, "acc")
        self.assertEqual(loaded.refresh_token, "ref")

    def test_corrupt_file_returns_none(self):
        # 文件损坏不能崩，应当走重新授权
        p = self.dir / "bad.json"
        p.write_text("{not json", encoding="utf-8")
        self.assertIsNone(TokenStore(p).load())

    def test_missing_file_returns_none(self):
        self.assertIsNone(TokenStore(self.dir / "nope.json").load())


class TestTokenExpiry(unittest.TestCase):
    def test_expired_with_margin(self):
        # 距过期还有 30 分钟，但余量要求 1 小时 → 视为已过期
        import time
        t = Token("a", "r", "o", time.time() + 1800)
        self.assertTrue(t.expired(margin=3600))

    def test_not_expired(self):
        self.assertFalse(Token("a", "r", "o", tomorrow()).expired())


class TestClientTokenFlow(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.store = TokenStore(self.dir / "t.json")

    def _client(self, routes, token=None, **kw):
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=FakeTransport(routes), min_interval=0, **kw)
        if token:
            c._token = token
        return c

    def test_no_token_raises(self):
        c = self._client({})
        with self.assertRaises(DouyinError):
            c.ensure_token()

    def test_valid_token_skips_refresh(self):
        tr = FakeTransport({})
        c = self._client({}, token=Token("acc", "ref", "oid", tomorrow()))
        c.transport = tr
        c.ensure_token()
        self.assertEqual(tr.count("/oauth/refresh_token/"), 0)

    def test_expired_token_triggers_refresh(self):
        tr = FakeTransport({
            "/oauth/refresh_token/": {
                "data": {"error_code": 0, "access_token": "new_acc",
                         "refresh_token": "new_ref", "expires_in": 1296000}
            }
        })
        c = self._client({}, token=Token("old", "ref", "oid", yesterday()))
        c.transport = tr
        t = c.ensure_token()
        self.assertEqual(t.access_token, "new_acc")
        self.assertEqual(tr.count("/oauth/refresh_token/"), 1)
        # 刷新结果必须落盘，否则重启后又要重来
        self.assertEqual(self.store.load().access_token, "new_acc")

    def test_refresh_token_expired_requires_reauth(self):
        msgs = []
        c = self._client(
            {"/oauth/refresh_token/": {"data": {"error_code": ERR_REFRESH_EXPIRED}}},
            token=Token("old", "ref", "oid", yesterday()),
            on_reauth_needed=msgs.append,
        )
        with self.assertRaises(DouyinError) as ctx:
            c.ensure_token()
        self.assertEqual(ctx.exception.code, ERR_REFRESH_EXPIRED)
        self.assertFalse(ctx.exception.recoverable)
        self.assertTrue(msgs, "未触发重新授权提醒")


class TestClientRequest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.store = TokenStore(self.dir / "t.json")

    def test_expired_on_call_refreshes_and_retries_once(self):
        # 本地以为没过期，调用时返回 10008 → 刷新一次后重试成功
        routes = {
            "/video/comment/list/": [
                {"data": {"error_code": 10008, "description": "token expired"}},
                {"data": {"error_code": 0, "list": [
                    {"comment_id": "c1", "content": "求链接",
                     "user": {"nickname": "A"}}], "has_more": 0}},
            ],
            "/oauth/refresh_token/": {
                "data": {"error_code": 0, "access_token": "fresh",
                         "expires_in": 1296000}
            },
        }
        tr = FakeTransport(routes)
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=tr, min_interval=0)
        c._token = Token("stale", "ref", "oid", tomorrow())
        comments, _ = c.list_comments("item_1")
        self.assertEqual(len(comments), 1)
        self.assertEqual(tr.count("/oauth/refresh_token/"), 1)
        # 只重试一次：列表接口被调用两次（失败 + 成功），不能更多
        self.assertEqual(tr.count("/video/comment/list/"), 2)

    def test_non_token_error_raises_without_retry(self):
        routes = {
            "/video/comment/list/": {"data": {"error_code": 2100005,
                                              "description": "no permission"}},
        }
        tr = FakeTransport(routes)
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=tr, min_interval=0)
        c._token = Token("acc", "ref", "oid", tomorrow())
        with self.assertRaises(DouyinError) as ctx:
            c.list_comments("item_1")
        self.assertEqual(ctx.exception.code, 2100005)
        self.assertFalse(ctx.exception.recoverable)
        self.assertEqual(tr.count("/video/comment/list/"), 1)

    def test_reply_truncates_to_500(self):
        tr = FakeTransport({})
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=tr, min_interval=0)
        c._token = Token("acc", "ref", "oid", tomorrow())
        c.reply_comment("item_1", "c1", "字" * 800)
        params = [p for _, u, p in tr.calls if u.endswith("/video/comment/reply/")][0]
        self.assertEqual(len(params["content"]), 500)

    def test_empty_reply_rejected_before_request(self):
        tr = FakeTransport({})
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=tr, min_interval=0)
        c._token = Token("acc", "ref", "oid", tomorrow())
        self.assertFalse(c.reply_comment("item_1", "c1", "   "))
        self.assertEqual(tr.count("/video/comment/reply/"), 0)


class TestSeenStore(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_mark_and_seen(self):
        s = SeenStore(self.dir / "seen.jsonl")
        self.assertFalse(s.seen("c1"))
        s.mark("c1")
        self.assertTrue(s.seen("c1"))

    def test_persists_across_reload(self):
        p = self.dir / "seen.jsonl"
        SeenStore(p).mark("c1")
        self.assertTrue(SeenStore(p).seen("c1"), "重启后去重失效")

    def test_duplicate_mark_not_written_twice(self):
        p = self.dir / "seen.jsonl"
        s = SeenStore(p)
        s.mark("c1")
        s.mark("c1")
        lines = [l for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual(len(lines), 1)


class TestAdapter(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.store = TokenStore(self.dir / "t.json")

    def _adapter(self, routes):
        c = DouyinClient("ck", "cs", "oid", self.store,
                         transport=FakeTransport(routes), min_interval=0)
        c._token = Token("acc", "ref", "oid", tomorrow())
        return DouyinAdapter(c, ["item_1"],
                             seen=SeenStore(self.dir / "seen.jsonl")), c

    def test_poll_marks_and_dedupes(self):
        routes = {"/video/comment/list/": {"data": {
            "error_code": 0, "has_more": 0,
            "list": [
                {"comment_id": "c1", "content": "求链接", "user": {"nickname": "A"}},
                {"comment_id": "c2", "content": "好看", "user": {"nickname": "B"}},
            ]}}}
        ad, _ = self._adapter(routes)
        first = ad.poll()
        self.assertEqual(len(first), 2)
        self.assertEqual(first[0].surface, Surface.COMMENT)
        self.assertEqual(first[0].thread, "item_1")
        # 第二轮不应再返回同样的评论
        self.assertEqual(len(ad.poll()), 0)

    def test_poll_survives_api_error(self):
        # 单条视频失败不能中断整轮，也不能抛到 Hub 外面
        routes = {"/video/comment/list/": {"data": {"error_code": 2100005,
                                                    "description": "no permission"}}}
        ad, _ = self._adapter(routes)
        self.assertEqual(ad.poll(), [])
        self.assertTrue(ad.last_errors, "错误未记录")

    def test_send_requires_thread(self):
        ad, _ = self._adapter({})
        ok = ad.send(Reply("douyin", "c1", "在的", thread=""))
        # thread 为空会被平台拒绝；这里只要求不崩，实际拦截在 Hub 层
        self.assertFalse(ok)

    def test_max_pages_limits_rounds(self):
        # has_more 恒为真时，翻页必须被 max_pages 截断
        paged = {"data": {"error_code": 0, "has_more": 1, "cursor": 1,
                          "list": [{"comment_id": "cx", "content": "在吗",
                                    "user": {"nickname": "X"}}]}}
        ad, client = self._adapter({"/video/comment/list/": paged})
        ad.max_pages = 2
        ad.poll()
        self.assertEqual(client.transport.count("/video/comment/list/"), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
