#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_wecom_live.py — 交互式演示的回归测试

锁四件事：
  1. 一句话进来，真的会产生一条回复（不是只记日志）
  2. 拦截和沉默不产生气泡，但必须留下原因（否则界面上看起来像丢了消息）
  3. 连续发同一句话不会被去重吞掉（MsgId 每次不同）
  4. 增量拉取（since）不重复、不遗漏

跑法：python -m unittest test_wecom_live -v
"""

from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from wecom_live import LiveSession, make_ui_handler

HERE = Path(__file__).parent
PERSONA = HERE / "personas" / "wecom_chat.json"


class LiveFixture(unittest.TestCase):
    """每个用例一套干净的服务，手动推进处理，不依赖后台线程的时序。"""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="wecom_live_"))
        self.session = LiveSession(PERSONA, self.tmp / "live.db", dry_run=False)
        # 不启动后台线程：用例里手动 run_once，避免 0.6 秒的等待和竞态
        self.session.url_ok = self.session.sim.verify_url()

    def tearDown(self):
        self.session.stop()

    def say_and_step(self, text: str, rounds: int = 1):
        self.session.say(text)
        for _ in range(rounds):
            self.session.sim.dispatcher.run_once()
        return self.session.state(0)

    def last_bot(self, state: dict) -> dict:
        bots = [x for x in state["items"] if x["role"] == "bot"]
        self.assertTrue(bots, "没有产生任何回复记录")
        return bots[-1]


class TestBasicReply(LiveFixture):

    def test_callback_url_verifies(self):
        self.assertTrue(self.session.url_ok,
                        "协议层没通，后面所有消息都不会到达")

    def test_greeting_gets_reply(self):
        state = self.say_and_step("在吗")
        bot = self.last_bot(state)
        self.assertEqual(bot["final"], "sent")
        self.assertTrue(bot["text"].strip(), "已发送却没有内容")
        self.assertEqual(bot["replies_to"], "在吗")

    def test_reply_text_comes_from_persona(self):
        # 内容必须来自人设语料，不能是硬编码在界面里的占位文案
        state = self.say_and_step("这个桌子多少钱")
        bot = self.last_bot(state)
        self.assertIn("399", bot["text"],
                      f"回复没走人设语料，实际是：{bot['text']}")

    def test_user_message_recorded_before_reply(self):
        state = self.say_and_step("在吗")
        roles = [x["role"] for x in state["items"]]
        self.assertEqual(roles[:2], ["user", "bot"],
                         "时间线顺序错了，回复不该出现在提问之前")

    def test_elapsed_time_measured(self):
        self.session.say("在吗")
        time.sleep(0.05)
        self.session.sim.dispatcher.run_once()
        bot = self.last_bot(self.session.state(0))
        self.assertIsNotNone(bot["ms"], "没有记录耗时")
        self.assertGreaterEqual(bot["ms"], 0)


class TestBlockedAndSilent(LiveFixture):

    def test_bargaining_blocked_with_reason(self):
        state = self.say_and_step("能便宜点吗")
        bot = self.last_bot(state)
        self.assertEqual(bot["final"], "blocked")
        self.assertIn("便宜", bot["why"])
        # 拦截的话一个字都不该发出去
        self.assertEqual(bot["text"], "")

    def test_refund_blocked(self):
        state = self.say_and_step("我要退款")
        bot = self.last_bot(state)
        self.assertEqual(bot["final"], "blocked")
        self.assertIn("退款", bot["why"])

    def test_filler_silent(self):
        state = self.say_and_step("嗯")
        bot = self.last_bot(state)
        self.assertEqual(bot["final"], "silent")
        self.assertTrue(bot["why"].strip(), "沉默也要说明原因")

    def test_blocked_not_counted_as_sent(self):
        self.say_and_step("能便宜点吗")
        stats = self.session.state(0)["stats"]
        self.assertEqual(stats["sent"], 0)
        self.assertEqual(stats["blocked"], 1)


class TestDedupe(LiveFixture):

    def test_same_text_twice_not_deduped(self):
        """
        同一个人在界面上连发两次「在吗」，必须回两次。

        MsgId 如果按内容生成，第二次会被平台重试的去重逻辑吞掉——
        界面上表现为「发了两遍只回一遍」，很难判断是引擎问题还是去重问题。
        """
        self.say_and_step("在吗")
        state = self.say_and_step("在吗")
        bots = [x for x in state["items"] if x["role"] == "bot"]
        self.assertEqual(len(bots), 2, f"连发两次只回了 {len(bots)} 次")

    def test_incremental_fetch_no_duplicate(self):
        self.say_and_step("在吗")
        first = self.session.state(0)
        seq = first["seq"]
        self.say_and_step("多少钱")
        later = self.session.state(seq)
        # 增量只应包含新增的两条（user + bot）
        self.assertEqual(len(later["items"]), 2)
        self.assertEqual([x["role"] for x in later["items"]], ["user", "bot"])


class TestReset(LiveFixture):

    def test_reset_clears_everything(self):
        self.say_and_step("在吗")
        self.say_and_step("能便宜点吗")
        self.session.reset()
        state = self.session.state(0)
        self.assertEqual(state["items"], [])
        self.assertEqual(state["seq"], 0)
        self.assertEqual(state["stats"]["user"], 0)

    def test_usable_after_reset(self):
        self.say_and_step("在吗")
        self.session.reset()
        state = self.say_and_step("有现货吗")
        bot = self.last_bot(state)
        self.assertEqual(bot["final"], "sent")


class TestHttpLayer(unittest.TestCase):
    """把界面用的那套 HTTP 接口跑一遍，确认契约没变。"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="wecom_live_http_"))
        cls.session = LiveSession(PERSONA, cls.tmp / "http.db", dry_run=False)
        cls.session.url_ok = cls.session.sim.verify_url()
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0),
                                      make_ui_handler(cls.session))
        cls.port = cls.srv.server_address[1]
        cls.thread = threading.Thread(target=cls.srv.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        cls.session.stop()

    def _get(self, path: str) -> dict:
        with urllib.request.urlopen(self.base + path, timeout=8) as r:
            return json.loads(r.read().decode())

    def _post(self, path: str, body: dict) -> dict:
        req = urllib.request.Request(
            self.base + path, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.loads(r.read().decode())

    def test_index_serves_html(self):
        with urllib.request.urlopen(self.base + "/", timeout=8) as r:
            html = r.read().decode("utf-8")
        self.assertIn("<!DOCTYPE html", html)
        self.assertIn("/api/say", html, "页面里没有调用发送接口")

    def test_health(self):
        data = self._get("/health")
        self.assertTrue(data["ok"])
        self.assertTrue(data["url_verified"])

    def test_say_then_state(self):
        self._post("/api/reset", {})
        self._post("/api/say", {"text": "在吗"})
        self.session.sim.dispatcher.run_once()
        state = self._get("/api/state")
        roles = [x["role"] for x in state["items"]]
        self.assertEqual(roles, ["user", "bot"])

    def test_empty_message_rejected(self):
        data = self._post("/api/say", {"text": "   "})
        self.assertFalse(data["ok"])

    def test_unknown_path_404(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(self.base + "/nope", timeout=8)
        self.assertEqual(ctx.exception.code, 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)
