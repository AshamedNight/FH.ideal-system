#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_gateway.py — 骨架的确定性测试

跑法：python -m unittest test_gateway -v
覆盖：风控纯函数、OneBot 事件归一化、Hub 审批队列、回复必要性判定。
不依赖网络，也不依赖任何第三方包。
"""

import unittest
from pathlib import Path

from gateway import (
    DECISION_AUTO, DECISION_BLOCK, DECISION_REVIEW,
    Hub, Inbound, MockAdapter, MockEngine, OneBotAdapter, Persona, Surface, check,
)
from necessity import Context, judge

HERE = Path(__file__).parent
PERSONA = Persona.load(HERE / "persona.json")


class TestGuard(unittest.TestCase):
    """风控闸门是纯函数，边界必须精确。"""

    def test_normal_passes(self):
        d, _ = check("这个桌子承重多少", PERSONA, recent=0, limit=6)
        self.assertEqual(d, DECISION_AUTO)

    def test_bargaining_blocked(self):
        # "给我打个折" 不在词表里，靠宽松匹配（间隔≤2字）命中 "打折"。
        # 这个用例是实际跑出来的漏判，不是补的想象场景。
        for text in ["能便宜点吗", "有优惠吗", "给我打个折", "少点吧", "能再便宜一点不"]:
            d, reason = check(text, PERSONA, recent=0, limit=6)
            self.assertEqual(d, DECISION_BLOCK, f"未拦截：{text}")
            self.assertIn("边界", reason)

    def test_loose_match_does_not_overreach(self):
        # 宽松匹配不能变成"见到这两个字就算"：间隔超过 2 字就不该命中。
        d, _ = check("我今天打了电话", PERSONA, recent=0, limit=6)
        self.assertEqual(d, DECISION_AUTO, "宽松匹配过度拦截")

    def test_refund_blocked(self):
        for text in ["我要退款", "能退货吗", "我要投诉"]:
            d, _ = check(text, PERSONA, recent=0, limit=6)
            self.assertEqual(d, DECISION_BLOCK, f"未拦截：{text}")

    def test_rate_limit_forces_review(self):
        d, reason = check("这个桌子承重多少", PERSONA, recent=6, limit=6)
        self.assertEqual(d, DECISION_REVIEW)
        self.assertIn("频控", reason)

    def test_taboo_word_blocked(self):
        d, _ = check("加微信聊吧", PERSONA, recent=0, limit=6)
        self.assertEqual(d, DECISION_BLOCK)

    def test_boundary_beats_rate_limit_order(self):
        # 频控与边界同时命中时，应当先报频控（转人工而非拦截），
        # 这个顺序决定了用户看到的是"稍后处理"还是"不能回"。
        d, _ = check("能便宜点吗", PERSONA, recent=99, limit=6)
        self.assertEqual(d, DECISION_REVIEW)


class TestOneBotNormalize(unittest.TestCase):
    """OneBot 事件形态不止一种，字符串与消息段数组都要能归一。"""

    def setUp(self):
        self.ad = OneBotAdapter("http://127.0.0.1:3000")

    def test_plain_string_message(self):
        self.ad.push_event({
            "post_type": "message", "group_id": 88347621,
            "sender": {"nickname": "阿哲"}, "raw_message": "承重多少",
        })
        msgs = self.ad.poll()
        self.assertEqual(len(msgs), 1)
        self.assertEqual(msgs[0].channel, "qq")
        self.assertEqual(msgs[0].conv_id, "88347621")
        self.assertEqual(msgs[0].sender, "阿哲")
        self.assertEqual(msgs[0].text, "承重多少")

    def test_segment_array_message(self):
        # NapCat / Lagrange 在 array 格式下会把文本拆成多个 segment
        self.ad.push_event({
            "post_type": "message", "user_id": 10001,
            "sender": {"nickname": "米粒"},
            "message": [
                {"type": "text", "data": {"text": "在"}},
                {"type": "text", "data": {"text": "吗"}},
            ],
        })
        msgs = self.ad.poll()
        self.assertEqual(len(msgs), 1)
        self.assertEqual(msgs[0].text, "在吗")
        self.assertEqual(msgs[0].conv_id, "10001")

    def test_non_message_event_ignored(self):
        self.ad.push_event({"post_type": "notice", "notice_type": "group_increase"})
        self.assertEqual(self.ad.poll(), [])

    def test_poll_drains(self):
        self.ad.push_event({"post_type": "message", "user_id": 1,
                            "sender": {"nickname": "x"}, "raw_message": "a"})
        self.assertEqual(len(self.ad.poll()), 1)
        self.assertEqual(self.ad.poll(), [])


class TestHubRouting(unittest.TestCase):
    """审批链路是最容易静默失败的地方——草稿被吞掉不会有任何报错。"""

    def _hub(self, auto: bool):
        ad = MockAdapter([Inbound("wechat", "wxid_x", "测试", "在吗")])
        ad.auto_send_allowed = auto
        hub = Hub(PERSONA, MockEngine(), rate_limit=6, human_delay=(0, 0))
        hub.register(ad, channels=["wechat"])
        return hub, ad

    def test_auto_send_path(self):
        hub, ad = self._hub(auto=True)
        hub.step()
        self.assertEqual(len(ad.sent), 1)
        self.assertEqual(len(hub.pending), 0)

    def test_manual_queue_then_approve(self):
        # 之前这里有个 bug：适配器名与渠道名不一致，approve 查不到适配器
        # 导致草稿被静默丢弃（队列被 pop 掉但没发出去）。此用例锁死该行为。
        hub, ad = self._hub(auto=False)
        hub.step()
        self.assertEqual(len(hub.pending), 1, "未进入待确认队列")
        self.assertTrue(hub.approve(0), "审批失败")
        self.assertEqual(len(ad.sent), 1, "审批通过后未发送")
        self.assertEqual(len(hub.pending), 0)

    def test_approve_bad_index_is_safe(self):
        hub, ad = self._hub(auto=False)
        hub.step()
        self.assertFalse(hub.approve(99))
        self.assertEqual(len(hub.pending), 1, "越界审批不应吞掉草稿")

    def test_blocked_never_queued(self):
        ad = MockAdapter([Inbound("wechat", "wxid_x", "测试", "我要退款")])
        hub = Hub(PERSONA, MockEngine(), rate_limit=6)
        hub.register(ad, channels=["wechat"])
        hub.step()
        self.assertEqual(len(hub.blocked), 1)
        self.assertEqual(len(hub.pending), 0)
        self.assertEqual(len(ad.sent), 0)


class TestNecessity(unittest.TestCase):
    """
    回复必要性判定。这里锁的是"什么时候不该说话"——
    上一版 Gateway 每条都回，是真实聊天里最明显的破绽。
    """

    def _judge(self, text, surface="group", ago=1e9, burst=0, mentioned=False):
        msg = Inbound("qq", "g1", "测试", text, surface=Surface(surface))
        ctx = Context(surface=surface, self_said_ago=ago, group_burst=burst,
                      is_mentioned=mentioned)
        return judge(msg, ctx, PERSONA)

    def test_private_summon_always_replies(self):
        # 一对一来找你了，装看不见没有道理
        j = self._judge("在吗", surface="private")
        self.assertEqual(j.action, "reply")

    def test_name_call_replies_with_high_score(self):
        # 被叫名字是高优先，但不再封顶 1.0——封顶会让「@我 6」也发出去。
        # 这里锁的是：跨过阈值，同时保留「可能被其它信号拉低」的余地。
        j = self._judge("小陆在不在")
        self.assertEqual(j.action, "reply")
        self.assertGreaterEqual(j.score, 0.60)
        self.assertLess(j.score, 1.0)

    def test_mention_low_content_still_holds(self):
        # 「@小陆 6」这种被点名但没内容的，应该落在观望而不是回复。
        # 这是「尽量回复」和「一律回复」的分界线。
        j = self._judge("6", surface="comment", mentioned=True)
        self.assertNotEqual(j.action, "reply", f"无内容却被回复：{j.score}")

    def test_mention_short_but_meaningful_replies(self):
        # 「@小陆 好看」被点名但没有问句：仍然要回，因为是冲你来的
        j = self._judge("好看", surface="comment", mentioned=True)
        self.assertEqual(j.action, "reply", f"被点名却沉默：{j.score}")

    def test_group_relevant_question_replies(self):
        j = self._judge("这个桌子承重多少")
        self.assertEqual(j.action, "reply")

    def test_group_relevant_without_mention_still_replies(self):
        # 曾经这里因为"群聊未被点名"扣分掉到观望，属明显失职。
        # 强相关（命中知识或与语料高度重合）时必须豁免该扣分。
        j = self._judge("女孩子一个人搬得动吗")
        self.assertEqual(j.action, "reply", f"强相关问题被误判为 {j.action}")

    def test_recent_texts_context_does_not_crash(self):
        # recent_texts 分支里用了 _lcs_len，但那行从来没导入过。
        # 现有调用方都没传这个参数，所以它一直没暴露；
        # 一旦接入真实数据开始用上下文，第一周就会 NameError。
        msg = Inbound("qq", "g1", "测试", "这个桌子承重多少")
        ctx = Context(surface="group",
                      recent_texts=("今天天气不错", "吃饭了吗", "哈哈哈"))
        j = judge(msg, ctx, PERSONA)
        self.assertIn(j.action, ("reply", "wait", "silent"))

    def test_pure_filler_is_silent(self):
        for t in ["嗯", "哦", "哈哈哈", "收到"]:
            j = self._judge(t)
            self.assertEqual(j.action, "silent", f"语气词未沉默：{t}")

    def test_unrelated_chat_is_silent(self):
        j = self._judge("今天外面下雨了")
        self.assertNotEqual(j.action, "reply")

    def test_private_not_penalized_for_recent_speech(self):
        # 一问一答本来就是连续的，私聊里"刚说过话"不该成为不回复的理由
        j = self._judge("多久能到", surface="private", ago=20)
        self.assertEqual(j.action, "reply", "私聊因刚说过话而漏答")

    def test_private_buying_intent_replies(self):
        # 实测漏答：私信里"求链接"只拿到 0.55 被判观望。
        # 一对一说"求链接"是最强的购买信号，不回应是明显失职。
        j = self._judge("求链接", surface="private")
        self.assertEqual(j.action, "reply", f"私信求链接被漏答，分数 {j.score}")

    def test_group_penalized_for_recent_speech(self):
        recent = self._judge("能带炉子吗", ago=25)
        fresh = self._judge("能带炉子吗", ago=9999)
        self.assertLess(recent.score, fresh.score, "群聊抢话惩罚未生效")

    def test_score_bounded(self):
        for t in ["这个桌子承重多少女孩子一个人搬得动吗能不能带炉子", "嗯", "在吗"]:
            j = self._judge(t)
            self.assertGreaterEqual(j.score, 0.0)
            self.assertLessEqual(j.score, 1.0)

    def test_always_gives_reason(self):
        # 判定必须可解释，否则线上出问题时无法定位是哪条规则生效
        for t in ["这个桌子承重多少", "嗯", "今天天气不错"]:
            j = self._judge(t)
            self.assertTrue(j.reasons, f"无判定理由：{t}")


class TestCommentSurface(unittest.TestCase):
    """
    公开评论区。和私信/群聊最大的区别有两点：
      1. 评论大多在评价内容，不一定冲你来的 → 默认更克制
      2. 回复是公开的，所有人都看得见 → 风控默认强制人工确认
    """

    def test_comment_defaults_to_review(self):
        # 不出网：没显式打开 allow_public_auto 时，评论回复一律进人工队列
        d, reason = check("求链接", PERSONA, recent=0, limit=6,
                          surface=Surface.COMMENT)
        self.assertEqual(d, DECISION_REVIEW)
        self.assertIn("公开", reason)

    def test_comment_can_auto_when_explicitly_allowed(self):
        d, _ = check("求链接", PERSONA, recent=0, limit=6,
                     surface=Surface.COMMENT, allow_public_auto=True)
        self.assertEqual(d, DECISION_AUTO)

    def test_boundary_still_wins_over_public_setting(self):
        # 即使允许公开自动，议价消息也必须被拦下——风控优先级高于渠道设置
        d, _ = check("能便宜点吗", PERSONA, recent=0, limit=6,
                     surface=Surface.COMMENT, allow_public_auto=True)
        self.assertEqual(d, DECISION_BLOCK)

    def test_buying_intent_triggers_reply(self):
        for text in ["求链接", "这个在哪买", "怎么买啊"]:
            msg = Inbound("xhs", "note_1", "用户", text, surface=Surface.COMMENT)
            j = judge(msg, Context(surface="comment"), PERSONA)
            self.assertEqual(j.action, "reply", f"购买意图未接住：{text}")

    def test_praise_is_silent(self):
        for text in ["好看", "这个好看", "哈哈哈"]:
            msg = Inbound("xhs", "note_1", "用户", text, surface=Surface.COMMENT)
            j = judge(msg, Context(surface="comment"), PERSONA)
            self.assertNotEqual(j.action, "reply", f"夸赞不该接话：{text}")

    def test_comment_penalized_for_recent_reply(self):
        # 同一条笔记下连着回两条，是明显的刷评特征
        msg = Inbound("xhs", "note_1", "用户", "求链接", surface=Surface.COMMENT)
        recent = judge(msg, Context(surface="comment", self_said_ago=10), PERSONA)
        fresh = judge(msg, Context(surface="comment", self_said_ago=9999), PERSONA)
        self.assertLess(recent.score, fresh.score, "评论区连发未降权")


class TestEngineMatching(unittest.TestCase):
    """
    回复匹配。这里锁的缺陷是"答非所问"——
    它比不回复更糟：用户问在哪买，你回承重多少，直接被当成机器人。
    """

    def setUp(self):
        self.engine = MockEngine()

    def test_intent_not_matched_to_unrelated_faq(self):
        # 曾经"这个在哪买"匹配到"这个桌子承重多少"，回复出承重参数。
        # 根因是 LCS 用绝对值：中文"这个"两个字就能凑够 2 分。
        msg = Inbound("xhs", "n1", "用户", "这个在哪买", surface=Surface.COMMENT)
        out = self.engine.generate(msg, PERSONA)
        self.assertIn("链接", out, f"答非所问：{out}")
        self.assertNotIn("承重", out, f"答非所问：{out}")

    def test_short_utterance_exact_match(self):
        # 3 字以内的语料要求精确命中，否则会被长句抢走
        msg = Inbound("wechat", "w1", "米粒", "在吗")
        self.assertEqual(self.engine.generate(msg, PERSONA), "在的，你说。")

    def test_no_match_returns_empty_for_human(self):
        # 匹配不上就返回空串交给人工，不许硬凑一句不相干的。
        # 用一条和产品完全无关的消息：加「好看」语料之后，
        # 「这个好看」已经是能对上的（夸赞），不再适合当无匹配样本。
        msg = Inbound("xhs", "n1", "用户", "你家猫叫什么名字", surface=Surface.COMMENT)
        self.assertEqual(self.engine.generate(msg, PERSONA), "")

    def test_praise_has_answer_after_adding_corpus(self):
        # 被点名夸一句，应该有话可回，而不是「给不出草稿」
        msg = Inbound("xhs", "n1", "用户", "好看", surface=Surface.COMMENT)
        out = self.engine.generate(msg, PERSONA)
        self.assertTrue(out, "夸赞没有对口语料")
        self.assertNotIn("承重", out, f"答非所问：{out}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
