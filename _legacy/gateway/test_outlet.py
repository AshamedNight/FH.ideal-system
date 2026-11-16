#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_outlet.py — 演示投递层的确定性测试

跑法：python -m unittest test_outlet -v

重点不是"能不能发出去"，而是"发失败时会不会被当成成功"。
演示场景里最坏的情况是：屏幕上显示"已投递"，手机上什么都没收到。
"""

import json
import tempfile
import unittest
from pathlib import Path

from outlet import (CompositeOutlet, LocalOutlet, OneBotOutlet, Outlet,
                    WebhookOutlet, build_outlet)


class TestLocalOutlet(unittest.TestCase):

    def test_writes_jsonl(self):
        d = Path(tempfile.mkdtemp())
        o = LocalOutlet(d / "out.jsonl")
        r = o.deliver("你好", {"sender": "小王"})
        self.assertTrue(r.ok)
        lines = (d / "out.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 1)
        rec = json.loads(lines[0])
        self.assertEqual(rec["text"], "你好")
        self.assertEqual(rec["sender"], "小王")


class TestWebhookPayload(unittest.TestCase):

    def test_wecom_payload(self):
        o = WebhookOutlet("http://x", style="wecom", prefix="【演示】",
                          transport=lambda u, p: {"errcode": 0})
        p = o.build_payload("回复内容")
        self.assertEqual(p["msgtype"], "text")
        self.assertEqual(p["text"]["content"], "【演示】回复内容")

    def test_dingtalk_payload(self):
        o = WebhookOutlet("http://x", style="dingtalk",
                          transport=lambda u, p: {"errcode": 0})
        self.assertIn("msgtype", o.build_payload("hi"))

    def test_feishu_payload(self):
        # 飞书的字段名和其它两家不同，写错会被拒
        o = WebhookOutlet("http://x", style="feishu",
                          transport=lambda u, p: {"code": 0})
        p = o.build_payload("hi")
        self.assertEqual(p["msg_type"], "text")
        self.assertEqual(p["content"]["text"], "hi")

    def test_bad_style_rejected(self):
        with self.assertRaises(ValueError):
            WebhookOutlet("http://x", style="telegram")


class TestWebhookErrors(unittest.TestCase):
    """这些用例锁的是「失败不能被报成成功」。"""

    def test_network_error_is_failure(self):
        o = WebhookOutlet("http://x", transport=lambda u, p: {"_error": "timeout"})
        r = o.deliver("hi")
        self.assertFalse(r.ok)
        self.assertIn("timeout", r.detail)

    def test_platform_error_code_is_failure(self):
        # 企微 token 失效时回 93000，但 HTTP 是 200。
        # 只看 HTTP 状态会把这种情况当成成功。
        o = WebhookOutlet("http://x",
                          transport=lambda u, p: {"errcode": 93000,
                                                  "errmsg": "invalid webhook url"})
        r = o.deliver("hi")
        self.assertFalse(r.ok)
        self.assertIn("93000", r.detail)

    def test_feishu_code_is_failure(self):
        o = WebhookOutlet("http://x", style="feishu",
                          transport=lambda u, p: {"code": 19001, "msg": "param error"})
        self.assertFalse(o.deliver("hi").ok)

    def test_success(self):
        o = WebhookOutlet("http://x", transport=lambda u, p: {"errcode": 0})
        self.assertTrue(o.deliver("hi").ok)

    def test_empty_text_not_sent(self):
        called = []
        o = WebhookOutlet("http://x",
                          transport=lambda u, p: called.append(1) or {"errcode": 0})
        self.assertFalse(o.deliver("   ").ok)
        self.assertEqual(called, [], "空消息不应该发请求")


class TestOneBotOutlet(unittest.TestCase):

    def test_success(self):
        o = OneBotOutlet("http://127.0.0.1:3000", "123456",
                         transport=lambda u, p: {"status": "ok", "retcode": 0})
        r = o.deliver("hi")
        self.assertTrue(r.ok)

    def test_failed_retcode(self):
        o = OneBotOutlet("http://127.0.0.1:3000", "123456",
                         transport=lambda u, p: {"status": "failed", "retcode": 1404})
        self.assertFalse(o.deliver("hi").ok)

    def test_sends_to_group(self):
        captured = {}

        def cap(url, payload):
            captured["url"] = url
            captured["payload"] = payload
            return {"status": "ok", "retcode": 0}

        o = OneBotOutlet("http://h:3000/", "9988", transport=cap)
        o.deliver("内容")
        self.assertTrue(captured["url"].endswith("/send_group_msg"))
        self.assertEqual(captured["payload"]["group_id"], "9988")


class TestComposite(unittest.TestCase):

    def test_one_success_is_enough(self):
        class Bad(Outlet):
            name = "bad"

            def deliver(self, text, meta=None):
                from outlet import Delivery
                return Delivery(False, "bad", "挂了")

        class Good(Outlet):
            name = "good"

            def deliver(self, text, meta=None):
                from outlet import Delivery
                return Delivery(True, "good", "ok")

        r = CompositeOutlet([Bad(), Good()]).deliver("x")
        self.assertTrue(r.ok, "一个出口成功就该算成功")
        self.assertIn("bad", r.detail, "失败的出口也要留在明细里")

    def test_all_failed(self):
        class Bad(Outlet):
            name = "bad"

            def deliver(self, text, meta=None):
                from outlet import Delivery
                return Delivery(False, "bad", "挂了")

        self.assertFalse(CompositeOutlet([Bad(), Bad()]).deliver("x").ok)


class TestBuildOutlet(unittest.TestCase):

    def test_defaults_to_local(self):
        # 配置缺失时不能崩，否则演示还没开始就结束了
        self.assertIsInstance(build_outlet({}), LocalOutlet)
        self.assertIsInstance(build_outlet(None), LocalOutlet)

    def test_webhook(self):
        o = build_outlet({"kind": "webhook", "url": "http://x", "style": "dingtalk"})
        self.assertIsInstance(o, WebhookOutlet)
        self.assertEqual(o.style, "dingtalk")

    def test_composite(self):
        o = build_outlet({"kind": "composite", "outlets": [
            {"kind": "local", "path": "a.jsonl"},
            {"kind": "webhook", "url": "http://x"},
        ]})
        self.assertIsInstance(o, CompositeOutlet)
        self.assertEqual(len(o.outlets), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
