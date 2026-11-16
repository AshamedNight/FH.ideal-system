#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_wecom_sim.py — 无资质全链路模拟的回归测试

重点不是"能跑"，而是锁住三个容易退化的东西：
  1. 协议细节（CDATA、签名、echostr 原样回显）
  2. 去重（平台重试三次不能回三遍）
  3. 跨场合语料的差异（评论语料在聊天里说不出人话）

第 3 条最容易被忽略：它证明「用评论数据校准聊天场景」只能校准一半。

跑法：python -m unittest test_wecom_sim -v
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from gateway import Inbound, MockEngine, Persona, Surface
from necessity import Context, judge
from wecom_crypto import WeComCrypto
from wecom_server import parse_xml
from wecom_sim import (
    SIM_AES_KEY, SIM_CORP_ID, SIM_TOKEN,
    Simulator, build_xml,
)

HERE = Path(__file__).parent
CHAT_PERSONA = HERE / "personas" / "wecom_chat.json"
COMMENT_PERSONA = HERE / "persona.json"
SCENARIO = HERE / "recordings" / "wecom_chat.json"


class TestXmlShape(unittest.TestCase):
    """XML 拼装必须带 CDATA，否则中文和特殊字符会被平台拒收。"""

    def test_cdata_wrapped(self):
        xml = build_xml({"FromUserName": "zhangsan", "Content": "在吗 & <测试>"})
        self.assertIn("<![CDATA[zhangsan]]>", xml)
        self.assertIn("<![CDATA[在吗 & <测试>]]>", xml)
        self.assertTrue(xml.startswith("<xml>"))
        self.assertTrue(xml.endswith("</xml>"))

    def test_roundtrip_through_parser(self):
        # 自己拼的 XML 必须能被服务端的解析器读回来
        fields = {"FromUserName": "u1", "Content": "多少钱", "MsgType": "text"}
        self.assertEqual(parse_xml(build_xml(fields)), fields)


class TestEndToEnd(unittest.TestCase):
    """起真实的两个 HTTP 服务，跑真实加密与真实推送。"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="wecom_sim_"))
        cls.scenario = json.loads(SCENARIO.read_text(encoding="utf-8"))
        cls.sim = Simulator(cls.scenario, CHAT_PERSONA,
                            cls.tmp / "sim.db", dry_run=False)
        cls.sim.start()
        cls.url_ok, cls.echo = cls.sim.verify_url()
        cls.result = cls.sim.run(step_each=True)

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def test_url_verification(self):
        # 平台点「保存」时做的事：加密 echostr 必须原样解回
        self.assertTrue(self.url_ok)
        self.assertTrue(self.echo.startswith("echo-"))

    def test_all_pushed_messages_arrive(self):
        self.assertEqual(self.result["pushed"], len(self.scenario["turns"]))
        self.assertEqual(self.result["accepted"], self.result["pushed"])

    def test_something_actually_sent(self):
        # 关键：不是"记在本地算发过"，而是假平台真的收到了 JSON 请求
        self.assertGreater(len(self.result["sent"]), 0)
        for item in self.result["sent"]:
            self.assertTrue(item["to"])
            self.assertTrue(item["content"].strip())
            self.assertEqual(str(item["agentid"]), "1000002")

    def test_boundary_blocks_and_does_not_send(self):
        blocked = self.result["blocked"]
        self.assertGreaterEqual(len(blocked), 2)
        self.assertTrue(any("便宜" in r for r in blocked))
        self.assertTrue(any("退款" in r for r in blocked))
        # 被拦下的内容一句都不许出现在已发列表里
        sent_text = " ".join(s["content"] for s in self.result["sent"])
        self.assertNotIn("便宜点", sent_text)

    def test_token_fetched_once(self):
        # 每次都取令牌会被限流（45009），缓存必须生效
        self.assertEqual(self.result["token_calls"], 1)

    def test_repeat_push_deduped(self):
        before = self.sim.dispatcher.adapter.accepted
        fields = {
            "ToUserName": SIM_CORP_ID, "FromUserName": "zhangsan",
            "CreateTime": "1794400000", "MsgType": "text",
            "Content": "重复测试", "MsgId": "7300000088888888888",
            "AgentID": "1000002",
        }
        for _ in range(3):
            self.sim.push(fields)
        self.assertEqual(self.sim.dispatcher.adapter.accepted - before, 1,
                         "同一 MsgId 推三次应只入队一次")

    def test_silent_message_not_sent(self):
        # 「嗯」这种纯语气词不该触发回复
        trace = next(t for t in self.result["traces"] if t["text"] == "嗯")
        self.assertEqual(trace["final"], "silent")


class TestSendFailure(unittest.TestCase):
    """发送接口返回错误码时，草稿必须回到队列，不能被当成已发出。"""

    def test_failure_returns_to_queue(self):
        tmp = Path(tempfile.mkdtemp(prefix="wecom_fail_"))
        scenario = {"name": "失败测试", "turns": [
            {"from": "zhangsan", "text": "在吗", "gap": 1},
        ]}
        sim = Simulator(scenario, CHAT_PERSONA, tmp / "fail.db", dry_run=False)
        sim.start()
        try:
            sim.platform.fail_send = True       # 平台返回 45009
            result = sim.run(step_each=True)
            self.assertEqual(len(result["sent"]), 0, "失败却算作已发送")
            # 草稿要么留在队列，要么被标成未发出，不能凭空消失
            finals = {t["final"] for t in result["traces"]}
            self.assertNotIn("sent", finals, "发送失败被判成了 sent")
        finally:
            sim.stop()


class TestCrossSurfaceReuse(unittest.TestCase):
    """
    用评论数据校准聊天场景，能迁移多少？

    这组数字是实测的，不是估计：
      · 判定一致 8/10 —— 意图层确实跨场合通用
      · 草稿可用 4/7 —— 生成层不可迁移，评论语料答不上来聊天问题

    结论：评论数据能校准「该不该回」，校不了「回得好不好」。
    所以 M1（判定一致率）可以用评论做，M2（采纳率）必须用目标场合的真实数据。
    """

    @classmethod
    def setUpClass(cls):
        cls.chat = Persona.load(CHAT_PERSONA)
        cls.comment = Persona.load(COMMENT_PERSONA)
        cls.engine = MockEngine()
        turns = json.loads(SCENARIO.read_text(encoding="utf-8"))["turns"]
        cls.texts = [t["text"] for t in turns]

    def _both(self, text: str):
        msg = Inbound("wecom", "u1", "u1", text, surface=Surface.PRIVATE)
        ja = judge(msg, Context(surface="private"), self.chat)
        jb = judge(msg, Context(surface="private"), self.comment)
        da = self.engine.generate(msg, self.chat)
        db = self.engine.generate(msg, self.comment)
        return ja.action, jb.action, da, db

    def test_judgement_mostly_transfers(self):
        actions = [self._both(t) for t in self.texts]
        agree = sum(1 for a, b, _, _ in actions if a == b)
        self.assertGreaterEqual(
            agree, 8,
            f"意图层跨场合一致率下降到 {agree}/{len(self.texts)}，"
            f"说明通用意图词表被改坏了")

    def test_draft_does_not_transfer(self):
        rows = [self._both(t) for t in self.texts]
        chat_usable = sum(1 for _, _, d, _ in rows if d.strip())
        comment_usable = sum(1 for _, _, _, d in rows if d.strip())
        self.assertLess(
            comment_usable, chat_usable,
            f"两套语料给出的草稿数一样（{comment_usable} vs {chat_usable}），"
            f"这组用例已无法说明场合语料的差异")

    def test_comment_corpus_says_wrong_thing_in_private(self):
        # 最典型的一种错：在私聊里说「价格我私信你说」——用户已经在私聊里了
        _, _, _, draft = self._both("这个桌子多少钱")
        self.assertIn("私信", draft,
                      "这条用例原本靠评论语料的场合错位来说明问题")

    def test_chat_corpus_answers_same_question_differently(self):
        _, _, chat_draft, comment_draft = self._both("这个桌子多少钱")
        self.assertNotEqual(chat_draft, comment_draft)
        self.assertNotIn("评论区", chat_draft,
                         "聊天语料里出现了评论区措辞，说明语料抄错了场合")


class TestCryptoWithSimCredentials(unittest.TestCase):
    """模拟器用的假凭据必须满足真实协议要求，否则测不出问题。"""

    def test_roundtrip(self):
        c = WeComCrypto(SIM_TOKEN, SIM_AES_KEY, SIM_CORP_ID)
        plain = "<xml><Content><![CDATA[在吗]]></Content></xml>"
        self.assertEqual(c.decrypt(c.encrypt(plain)), plain)

    def test_wrong_corp_rejected(self):
        c = WeComCrypto(SIM_TOKEN, SIM_AES_KEY, "otherowner")
        enc = c.encrypt("x" * 30)
        mine = WeComCrypto(SIM_TOKEN, SIM_AES_KEY, SIM_CORP_ID)
        with self.assertRaises(ValueError):
            mine.decrypt(enc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
