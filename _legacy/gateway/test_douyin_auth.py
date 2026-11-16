#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_douyin_auth.py — 授权工具回归测试

锁四件事：
  1. 授权链接参数正确、redirect_uri 做了编码（漏编码会直接 redirect_uri not allowed）
  2. 用户粘贴什么都能取到 code（裸码 / 整条 URL / 带引号）
  3. 错误码翻译成人话，尤其是 10007（10 分钟过期且只能用一次）
  4. 令牌落盘能读回来，重启后不用重新授权

跑法：python -m unittest test_douyin_auth -v
"""

from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
import urllib.parse
import urllib.request
from pathlib import Path

from douyin import TokenStore
from douyin_auth import (
    AuthError, CallbackServer, build_auth_url, describe, exchange_code,
    extract_code, save_token,
)


class FakeTransport:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def __call__(self, method, url, params):
        self.calls.append((method, url, dict(params)))
        return self.payload


def ok_payload(expires_in=1296000):
    return {"data": {
        "access_token": "act.fake_token_value",
        "refresh_token": "rft.fake_refresh_value",
        "open_id": "b9b71865-fake-open-id",
        "expires_in": expires_in,
        "refresh_expires_in": 2592000,
        "scope": "user_info,item.comment",
        "error_code": 0,
    }, "message": "success"}


class TestAuthUrl(unittest.TestCase):

    def test_contains_all_params(self):
        url = build_auth_url("tt123", "https://a.com/cb", "user_info,item.comment")
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        self.assertEqual(q["client_key"], ["tt123"])
        self.assertEqual(q["response_type"], ["code"])
        self.assertEqual(q["scope"], ["user_info,item.comment"])
        self.assertEqual(q["redirect_uri"], ["https://a.com/cb"])
        self.assertIn("state", q)

    def test_redirect_uri_encoded(self):
        # 不编码的话，回调里的 ? 和 & 会把后面的参数截断，平台直接报参数错误
        url = build_auth_url("tt123", "https://a.com/cb?x=1&y=2", "user_info")
        self.assertNotIn("redirect_uri=https://a.com/cb?x=1&y=2&", url)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        self.assertEqual(q["redirect_uri"], ["https://a.com/cb?x=1&y=2"])

    def test_empty_client_key_rejected(self):
        with self.assertRaises(ValueError):
            build_auth_url("", "https://a.com/cb", "user_info")

    def test_empty_redirect_rejected(self):
        with self.assertRaises(ValueError):
            build_auth_url("tt123", "", "user_info")


class TestExtractCode(unittest.TestCase):

    def test_plain_code(self):
        self.assertEqual(extract_code("ffab5ec26cd958fd"), "ffab5ec26cd958fd")

    def test_full_url(self):
        raw = "https://example.com/cb?code=ffab5ec26cd958fd&state=s1"
        self.assertEqual(extract_code(raw), "ffab5ec26cd958fd")

    def test_url_with_quotes_and_spaces(self):
        raw = '  "https://example.com/cb?code=ffab5ec2&state=s1"  '
        self.assertEqual(extract_code(raw), "ffab5ec2")

    def test_url_with_other_params_first(self):
        raw = "https://x/cb?state=s1&code=abc12345&from=app"
        self.assertEqual(extract_code(raw), "abc12345")

    def test_weird_input_falls_back_to_first_token(self):
        self.assertEqual(extract_code("  abcdefgh extra  "), "abcdefgh")

    def test_empty(self):
        self.assertEqual(extract_code(""), "")
        self.assertEqual(extract_code("   "), "")


class TestExchange(unittest.TestCase):

    def test_success(self):
        t = FakeTransport(ok_payload())
        token = exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        self.assertEqual(token.access_token, "act.fake_token_value")
        self.assertEqual(token.refresh_token, "rft.fake_refresh_value")
        self.assertEqual(token.open_id, "b9b71865-fake-open-id")
        self.assertGreater(token.expires_at, time.time())

    def test_request_shape(self):
        # grant_type 必须是 authorization_code，漏了会报参数错误
        t = FakeTransport(ok_payload())
        exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        method, url, params = t.calls[0]
        self.assertEqual(method, "POST")
        self.assertIn("/oauth/access_token/", url)
        self.assertEqual(params["grant_type"], "authorization_code")
        self.assertEqual(params["client_key"], "tt123")
        self.assertEqual(params["client_secret"], "sec")

    def test_code_from_url_accepted(self):
        # 用户直接把整条 URL 传进来也要能用
        t = FakeTransport(ok_payload())
        token = exchange_code("tt123", "sec",
                              "https://a.com/cb?code=ffab5ec26cd958fd", transport=t)
        self.assertEqual(t.calls[0][2]["code"], "ffab5ec26cd958fd")
        self.assertTrue(token.access_token)

    def test_expired_code_gives_hint(self):
        t = FakeTransport({"data": {"error_code": 10007,
                                    "description": "authorization code expired"}})
        with self.assertRaises(AuthError) as ctx:
            exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        self.assertEqual(ctx.exception.code, 10007)
        self.assertIn("10 分钟", ctx.exception.hint,
                      "过期错误必须提示有效期，否则用户只会反复重试")

    def test_param_error_gives_hint(self):
        t = FakeTransport({"data": {"error_code": 10002,
                                    "description": "Parameter error"}})
        with self.assertRaises(AuthError) as ctx:
            exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        self.assertIn("client_key", ctx.exception.hint)

    def test_missing_access_token(self):
        t = FakeTransport({"data": {"error_code": 0}})
        with self.assertRaises(AuthError) as ctx:
            exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        self.assertIn("access_token", ctx.exception.message)

    def test_network_error(self):
        t = FakeTransport({"_error": "connection refused"})
        with self.assertRaises(AuthError) as ctx:
            exchange_code("tt123", "sec", "ffab5ec26cd958fd", transport=t)
        self.assertIn("网络", ctx.exception.message)

    def test_too_short_code_rejected_before_request(self):
        # 粘错内容时不该浪费一次请求，也不该把无效内容发给平台
        t = FakeTransport(ok_payload())
        with self.assertRaises(AuthError):
            exchange_code("tt123", "sec", "ab", transport=t)
        self.assertEqual(t.calls, [])


class TestTokenPersistence(unittest.TestCase):

    def test_save_and_reload(self):
        tmp = Path(tempfile.mkdtemp()) / "token.json"
        token = exchange_code("tt123", "sec", "ffab5ec26cd958fd",
                              transport=FakeTransport(ok_payload()))
        save_token(token, tmp)
        self.assertTrue(tmp.exists())
        back = TokenStore(tmp).load()
        self.assertEqual(back.access_token, token.access_token)
        self.assertEqual(back.open_id, token.open_id)

    def test_corrupt_file_returns_none(self):
        tmp = Path(tempfile.mkdtemp()) / "token.json"
        tmp.write_text("{不是 json", encoding="utf-8")
        self.assertIsNone(TokenStore(tmp).load())


class TestDescribe(unittest.TestCase):

    def test_days_left(self):
        token = exchange_code("tt123", "sec", "ffab5ec26cd958fd",
                              transport=FakeTransport(ok_payload(expires_in=15 * 86400)))
        self.assertIn("天", describe(token))

    def test_expired(self):
        token = exchange_code("tt123", "sec", "ffab5ec26cd958fd",
                              transport=FakeTransport(ok_payload(expires_in=-10)))
        self.assertIn("过期", describe(token))


class TestCallbackServer(unittest.TestCase):
    """本地回调：起服务、发一次带 code 的 GET，看能否取到。"""

    def test_receives_code(self):
        server = CallbackServer("/douyin/callback", 0)
        server.start()
        got: dict = {}

        def hit():
            time.sleep(0.15)
            url = f"{server.base}/douyin/callback?code=ffab5ec26&state=s1"
            try:
                with urllib.request.urlopen(url, timeout=5) as r:
                    got["body"] = r.read().decode("utf-8")
            except Exception as exc:              # noqa: BLE001
                got["error"] = str(exc)

        threading.Thread(target=hit, daemon=True).start()
        try:
            code = server.wait(timeout=5)
        finally:
            server.close()
        self.assertEqual(code, "ffab5ec26")
        self.assertIn("授权码已收到", got.get("body", ""))

    def test_wrong_path_ignored(self):
        server = CallbackServer("/douyin/callback", 0)
        server.start()
        try:
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(f"{server.base}/other?code=x", timeout=5)
            self.assertEqual(ctx.exception.code, 404)
        finally:
            server.close()

    def test_timeout_returns_empty(self):
        server = CallbackServer("/cb", 0)
        server.start()
        try:
            self.assertEqual(server.wait(timeout=0.3), "")
        finally:
            server.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
