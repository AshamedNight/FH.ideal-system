#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_autopilot.py — 分级放权的判定测试

跑法：python -m unittest test_autopilot -v

这里锁的是三件事：
  1. 高风险话术无论多少历史数据都不自动发
  2. 信任额度不足时一律回队列
  3. 驳回的扣分高于确认的加分（非对称，防止"错很多次还能解锁"）
"""

import unittest
from pathlib import Path

from autopilot import (
    AUTO, HOLD, QUEUE, AutopilotConfig, Credit, classify_risk, decide, preview,
)
from gateway import Hub, Inbound, MockAdapter, MockEngine, Persona, Surface

HERE = Path(__file__).parent
PERSONA = Persona.load(HERE / "persona.json")


class TestRiskClassify(unittest.TestCase):
    def test_plain_info_is_low(self):
        for d in ["链接在主页置顶，也可以私信我发你。", "承重 30 公斤，桌面放卡式炉没问题。"]:
            risk, _ = classify_risk(d)
            self.assertEqual(risk, "low", f"误判为 {risk}：{d}")

    def test_timing_is_medium(self):
        risk, hits = classify_risk("今天下单后天到，我这边盯着发货。")
        self.assertEqual(risk, "medium")
        self.assertTrue(hits)

    def test_price_is_high(self):
        for d in ["这只 429 元", "两只 799 元", "¥429 包邮"]:
            risk, _ = classify_risk(d)
            self.assertEqual(risk, "high", f"价格未判为高风险：{d}")

    def test_commitment_is_high(self):
        for d in ["保证七天无理由退换", "一定给您最低价", "永久保修"]:
            risk, _ = classify_risk(d)
            self.assertEqual(risk, "high", f"承诺未判为高风险：{d}")

    def test_weight_is_not_money(self):
        # "30 公斤" 不能被当成金额，否则所有参数类回答都会被按住
        risk, hits = classify_risk("承重 30 公斤，净重 2.4 公斤")
        self.assertEqual(risk, "low", f"重量被误判为金额：{hits}")

    def test_empty_draft_is_low(self):
        self.assertEqual(classify_risk("")[0], "low")


class TestCredit(unittest.TestCase):
    def test_approve_adds_one(self):
        c = Credit()
        for _ in range(3):
            c.approve()
        self.assertEqual(c.score, 3)

    def test_reject_costs_two(self):
        c = Credit()
        for _ in range(5):
            c.approve()
        c.reject()
        self.assertEqual(c.score, 3, "驳回应扣 2 分")

    def test_rejections_can_drive_negative(self):
        c = Credit()
        c.reject()
        c.reject()
        self.assertEqual(c.score, -4)
        self.assertEqual(c.samples, 2)


class TestDecide(unittest.TestCase):
    def setUp(self):
        self.cfg = AutopilotConfig(min_confidence=0.80, unlock_after=10,
                                   allow_medium_after_unlock=True,
                                   max_auto_per_hour=5)
        self.rich = Credit(approvals=20)

    def test_high_risk_always_held(self):
        # 哪怕额度再高，价格与承诺类话术永远人工
        action, reasons = decide("这只 429 元", 0.99, self.rich, self.cfg)
        self.assertEqual(action, HOLD)
        self.assertIn("高风险", reasons[0])

    def test_low_confidence_queued(self):
        action, _ = decide("链接在主页置顶", 0.5, self.rich, self.cfg)
        self.assertEqual(action, QUEUE)

    def test_insufficient_credit_queued(self):
        action, reasons = decide("链接在主页置顶", 0.95, Credit(approvals=3), self.cfg)
        self.assertEqual(action, QUEUE)
        self.assertIn("信任额度", reasons[-1])

    def test_all_conditions_pass_auto(self):
        action, _ = decide("链接在主页置顶", 0.90, self.rich, self.cfg)
        self.assertEqual(action, AUTO)

    def test_medium_blocked_when_config_disallows(self):
        cfg = AutopilotConfig(min_confidence=0.80, unlock_after=10,
                              allow_medium_after_unlock=False)
        action, _ = decide("今天下单后天到", 0.90, self.rich, cfg)
        self.assertEqual(action, QUEUE)

    def test_hourly_cap(self):
        action, reasons = decide("链接在主页置顶", 0.90, self.rich, self.cfg,
                                 auto_sent_last_hour=5)
        self.assertEqual(action, QUEUE)
        self.assertIn("上限", reasons[-1])

    def test_empty_draft_has_confidence_gate(self):
        # 空草稿不该因为"低风险"就放行
        action, _ = decide("", 0.0, self.rich, self.cfg)
        self.assertEqual(action, QUEUE)


class TestPreview(unittest.TestCase):
    def test_groups_by_action(self):
        rows = [
            {"id": "a", "draft": "链接在主页置顶", "confidence": 0.9},
            {"id": "b", "draft": "这只 429 元", "confidence": 0.9},
            {"id": "c", "draft": "链接在主页置顶", "confidence": 0.3},
        ]
        out = preview(rows, Credit(approvals=20))
        self.assertEqual(out["summary"], {"auto": 1, "queue": 1, "hold": 1})
        self.assertEqual(out["hold"][0]["id"], "b")


class TestTimeAxis(unittest.TestCase):
    """
    刷屏判定用错了时间轴和粒度，会把一批正常评论集体降权。
    这两个用例锁的就是这件事——它们都是实跑时暴露的，不是想象的场景。
    """

    def _judge_all(self, msgs, send_first=False):
        kw = {}
        if send_first:
            # 只有真的发出去了才会记录"上次发言时间"，否则连发惩罚无从触发
            kw = {"autopilot": True, "credit": Credit(approvals=20)}
        hub = Hub(PERSONA, MockEngine(), necessity=True, **kw)
        hub.register(MockAdapter(msgs), channels=["douyin"])
        for _ in range(len(msgs) + 1):
            if not hub.step():
                break
        return hub.traces

    def test_different_threads_not_penalized(self):
        # 分属两个视频的评论，发布时间相同，也不该互相降权
        base = 1_700_000_000
        traces = self._judge_all([
            Inbound("douyin", "c1", "A", "这个在哪买", surface=Surface.COMMENT,
                    thread="item_1", ts=base),
            Inbound("douyin", "c2", "B", "这个在哪买", surface=Surface.COMMENT,
                    thread="item_2", ts=base),
        ])
        for t in traces:
            self.assertGreaterEqual(t["necessity"], 0.85,
                                    f"不同视频的评论被当成刷屏：{t['necessity']}")

    def test_batch_replay_uses_message_time(self):
        # 同一视频，两条评论发布间隔 10 分钟；一起处理时不该被判成连续轰炸
        base = 1_700_000_000
        traces = self._judge_all([
            Inbound("douyin", "c1", "A", "求链接", surface=Surface.COMMENT,
                    thread="item_1", ts=base),
            Inbound("douyin", "c2", "B", "求链接", surface=Surface.COMMENT,
                    thread="item_1", ts=base + 600),
        ])
        for t in traces:
            self.assertGreaterEqual(t["necessity"], 0.75,
                                    f"历史评论被当成实时轰炸：{t['necessity']}")

    def test_same_thread_back_to_back_still_penalized(self):
        # 真正连发的两条（间隔 5 秒）仍然要降权——修正不能变成完全不拦
        base = 1_700_000_000
        traces = self._judge_all([
            Inbound("douyin", "c1", "A", "求链接", surface=Surface.COMMENT,
                    thread="item_1", ts=base),
            Inbound("douyin", "c2", "B", "求链接", surface=Surface.COMMENT,
                    thread="item_1", ts=base + 5),
        ], send_first=True)
        self.assertLess(traces[1]["necessity"], traces[0]["necessity"],
                        "同视频连发未降权，修正过度")


class TestHubIntegration(unittest.TestCase):
    """
    Hub 层的关键行为：默认仍全部进队列。
    自动化必须是显式开启的，不能因为加了新功能就悄悄改变默认行为。
    """

    def _run(self, autopilot: bool, credit=None):
        ad = MockAdapter([Inbound("xhs", "n1", "用户C", "这个在哪买",
                                  surface=Surface.COMMENT, thread="note_1")])
        hub = Hub(PERSONA, MockEngine(), necessity=True, autopilot=autopilot,
                  credit=credit)
        hub.register(ad, channels=["xhs"])
        hub.step()
        return hub, ad

    def test_default_still_requires_confirmation(self):
        hub, ad = self._run(autopilot=False)
        self.assertEqual(len(hub.pending), 1)
        self.assertEqual(len(ad.sent), 0)

    def test_autopilot_without_credit_still_queues(self):
        hub, ad = self._run(autopilot=True, credit=Credit())
        self.assertEqual(len(hub.pending), 1)
        self.assertEqual(len(ad.sent), 0)

    def test_autopilot_with_credit_sends(self):
        hub, ad = self._run(autopilot=True, credit=Credit(approvals=20))
        self.assertEqual(len(ad.sent), 1, "额度足够时应当自动发出")
        self.assertEqual(len(hub.pending), 0)
        t = hub.traces[0]
        self.assertEqual(t["final"], "sent")
        self.assertTrue(t["auto_gate"]["allowed"])

    def test_approve_and_reject_move_credit(self):
        hub, _ = self._run(autopilot=True, credit=Credit())
        hub.approve(0)
        self.assertEqual(hub.credit.score, 1)
        hub2, _ = self._run(autopilot=True, credit=Credit())
        hub2.reject(0)
        self.assertEqual(hub2.credit.score, -2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
