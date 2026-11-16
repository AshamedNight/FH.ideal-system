#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_deploy.py — 部署前必须具备的能力的确定性测试

跑法：python -m unittest test_deploy -v

覆盖六块：
  1. @ 识别（含截断、短昵称误判保护）
  2. 自回自答过滤（这是最危险的一类缺陷：不报错，只是无限回自己）
  3. 二级评论（用户在追问，是意向最强的一批）
  4. 作品自动发现（item_ids 手写死就不算后台）
  5. 持久化（重启后队列与额度还在）
  6. 调度与告警（退避、去重、恢复正常）

不依赖网络，也不依赖任何第三方包。
"""

import tempfile
import time
import unittest
from pathlib import Path

from autopilot import AutopilotConfig, Credit
from douyin import DouyinAdapter, SeenStore
from gateway import ChannelAdapter, Hub, Inbound, MockEngine, Persona, Reply, Surface
from scheduler import Scheduler
from store import Store
from works import WorkRegistry, detect_mention, strip_mention

PERSONA = Persona.load(Path(__file__).parent / "persona.json")
ME = "user_me"


# ---------------------------------------------------------------------------
# 假客户端：只实现适配器真正调用到的方法
# ---------------------------------------------------------------------------

class FakeClient:
    def __init__(self, comments=None, replies=None, works_pages=None):
        self.comments = comments or {}
        self.replies = replies or {}
        self.works_pages = works_pages or [[{"item_id": "item_1", "create_time": 1}]]
        self.sent = []

    def list_comments(self, item_id, cursor=0, count=20):
        return self.comments.get(item_id, []), cursor

    def list_replies(self, item_id, comment_id, cursor=0, count=20):
        return self.replies.get((item_id, comment_id), []), cursor

    def reply_comment(self, item_id, comment_id, content):
        cid = f"own_{len(self.sent) + 1}"
        self.sent.append((item_id, comment_id, content, cid))
        return cid

    def list_works(self, cursor=0, count=20):
        if cursor >= len(self.works_pages):
            return [], cursor
        page = self.works_pages[cursor]
        nxt = cursor + 1 if cursor + 1 < len(self.works_pages) else cursor
        return page, nxt


class FailingAdapter(ChannelAdapter):
    """发送永远失败的适配器。用来验证失败不会被当成已发出。"""
    name = "douyin"
    auto_send_allowed = True

    def poll(self):
        return []

    def send(self, reply):
        return False


def comment(cid, text, uid="u1", total=0, ts=1_700_000_000):
    return {
        "comment_id": cid, "content": text, "comment_user_id": uid,
        "create_time": ts, "reply_comment_total": total,
        "user": {"nickname": f"用户{cid}"},
    }


# ---------------------------------------------------------------------------
# 1. @ 识别
# ---------------------------------------------------------------------------

class TestMention(unittest.TestCase):

    NAMES = ["小陆的木工房"]

    def test_plain_mention(self):
        self.assertEqual(detect_mention("@小陆的木工房 这个承重多少", self.NAMES),
                         "小陆的木工房")

    def test_truncated_mention(self):
        # 抖音会截断长昵称显示，等值比较会漏
        self.assertEqual(detect_mention("@小陆的木工 这个承重多少", self.NAMES),
                         "小陆的木工房")

    def test_mention_in_middle(self):
        self.assertEqual(detect_mention("请问@小陆的木工房 有现货吗", self.NAMES),
                         "小陆的木工房")

    def test_no_mention(self):
        self.assertEqual(detect_mention("这个承重多少", self.NAMES), "")

    def test_single_char_name_ignored(self):
        # 一个字的名字双向包含几乎必然误判，代码里应该直接不匹配
        self.assertEqual(detect_mention("@陆家嘴 这个", ["陆"]), "")

    def test_strip_removes_at_fragment(self):
        # @ 片段会白送两个字的重合度，必须去掉再匹配语料
        self.assertEqual(strip_mention("@小陆的木工房 这个承重多少"), "这个承重多少")


# ---------------------------------------------------------------------------
# 2 & 3. 自回自答过滤 + 二级评论
# ---------------------------------------------------------------------------

class TestAdapterSafety(unittest.TestCase):

    def _adapter(self, client, **kw):
        d = Path(tempfile.mkdtemp())
        return DouyinAdapter(client, ["item_1"],
                             seen=SeenStore(d / "seen.jsonl"),
                             watch_replies=True, **kw)

    def test_skips_own_comment(self):
        client = FakeClient(comments={"item_1": [
            comment("c_me", "链接在主页置顶", uid=ME),
            comment("c_user", "这个在哪买"),
        ]})
        ad = self._adapter(client, own_user_ids=[ME])
        out = ad.poll()
        self.assertEqual([m.conv_id for m in out], ["c_user"])
        self.assertEqual(ad.skipped_own, 1)

    def test_skips_own_comment_by_recorded_id(self):
        # own_user_ids 没配时，靠发送记录兜底——第二道防线
        client = FakeClient(comments={"item_1": [comment("c_me", "我发的")]})
        ad = self._adapter(client)
        ad.remember_own("c_me")
        self.assertEqual(ad.poll(), [])

    def test_send_records_own_id(self):
        client = FakeClient(comments={"item_1": []})
        ad = self._adapter(client)
        ok = ad.send(Reply("douyin", "c_1", "回复内容", thread="item_1"))
        self.assertTrue(ok)
        self.assertIn("own_1", ad._own_comment_ids)

    def test_reply_scanned_as_new_message(self):
        client = FakeClient(
            comments={"item_1": [comment("c1", "有现货吗", total=2)]},
            replies={("item_1", "c1"): [
                comment("r1", "同问"), comment("r2", "我也想知道"),
            ]})
        out = self._adapter(client).poll()
        ids = [m.conv_id for m in out]
        self.assertIn("r1", ids)
        self.assertIn("r2", ids)
        self.assertEqual({m.parent_id for m in out if m.parent_id}, {"c1"})

    def test_reply_on_my_thread_is_mention(self):
        # 用户在我的回复下面追问 = 冲我来的，必须算被点名
        client = FakeClient(
            comments={"item_1": [comment("c_me", "链接在主页", uid=ME, total=1)]},
            replies={("item_1", "c_me"): [comment("r1", "看不到啊")]})
        out = self._adapter(client, own_user_ids=[ME]).poll()
        self.assertEqual(len(out), 1)
        self.assertTrue(out[0].is_mentioned)

    def test_third_party_thread_not_mention(self):
        # 两个路人在我评论区互相聊，不该插嘴
        client = FakeClient(
            comments={"item_1": [comment("c1", "好看", total=1)]},
            replies={("item_1", "c1"): [comment("r1", "是吧")]})
        out = self._adapter(client).poll()
        by_id = {m.conv_id: m for m in out}
        self.assertIn("r1", by_id)
        self.assertFalse(by_id["r1"].is_mentioned)

    def test_reply_rescan_only_when_count_changes(self):
        client = FakeClient(
            comments={"item_1": [comment("c1", "有货吗", total=1)]},
            replies={("item_1", "c1"): [comment("r1", "同问")]})
        ad = self._adapter(client)
        first = ad.poll()
        second = ad.poll()          # 回复数没变 → 不重扫
        self.assertEqual(len(first), 2)
        self.assertEqual(len(second), 0)

    def test_mention_in_comment_text(self):
        client = FakeClient(comments={"item_1": [
            comment("c1", "@小陆的木工房 这个承重多少")]})
        ad = self._adapter(client, my_names=["小陆的木工房"])
        out = ad.poll()
        self.assertTrue(out[0].is_mentioned)
        # 正文里的 @ 片段应该被去掉，否则会影响语料匹配
        self.assertNotIn("@", out[0].text)


# ---------------------------------------------------------------------------
# 4. 作品自动发现
# ---------------------------------------------------------------------------

class TestWorkRegistry(unittest.TestCase):

    def _reg(self, client, **kw):
        d = Path(tempfile.mkdtemp())
        return WorkRegistry(client, cache_path=d / "works.json", **kw)

    def test_discovers_works(self):
        client = FakeClient(works_pages=[[
            {"item_id": "a", "create_time": 1},
            {"item_id": "b", "create_time": 2},
        ]])
        reg = self._reg(client)
        added, removed = reg.refresh()
        self.assertEqual(reg.item_ids, ["b", "a"])   # 按发布时间倒序
        self.assertEqual(sorted(added), ["a", "b"])
        self.assertEqual(removed, [])

    def test_detects_new_and_removed(self):
        client = FakeClient(works_pages=[[
            {"item_id": "a", "create_time": 1},
            {"item_id": "b", "create_time": 2},
        ]])
        reg = self._reg(client, ttl=0)
        reg.refresh()

        client.works_pages = [[
            {"item_id": "b", "create_time": 2},
            {"item_id": "c", "create_time": 3},
        ]]
        added, removed = reg.refresh()
        self.assertEqual(added, ["c"])
        self.assertEqual(removed, ["a"])

    def test_truncates_to_platform_limit(self):
        # 平台最多 4 页 × 20 = 80 条，配置再大也夹住
        many = [{"item_id": f"i{n}", "create_time": n} for n in range(120)]
        client = FakeClient(works_pages=[many])   # 单页塞 120 条模拟超限
        reg = self._reg(client, max_works=999)
        reg.refresh()
        self.assertEqual(len(reg.item_ids), 80)
        self.assertTrue(reg.truncated)
        # 保留最新的 80 条
        self.assertEqual(reg.item_ids[0], "i119")

    def test_cache_survives_restart(self):
        d = Path(tempfile.mkdtemp())
        client = FakeClient(works_pages=[[{"item_id": "a", "create_time": 1}]])
        r1 = WorkRegistry(client, cache_path=d / "w.json")
        r1.refresh()
        r2 = WorkRegistry(client, cache_path=d / "w.json")
        self.assertEqual(r2.item_ids, ["a"])


# ---------------------------------------------------------------------------
# 5. 持久化
# ---------------------------------------------------------------------------

class TestPersistence(unittest.TestCase):

    def _hub(self, store):
        hub = Hub(PERSONA, MockEngine(), necessity=False,
                  autopilot=True, credit=Credit(),
                  auto_cfg=AutopilotConfig(), store=store)
        hub.register(MockAdapterStub(), channels=["douyin"])
        return hub

    def test_queue_survives_restart(self):
        d = Path(tempfile.mkdtemp())
        s1 = Store(d / "s.db")
        hub1 = self._hub(s1)
        hub1._enqueue(Reply("douyin", "c1", "草稿一", sender="小王",
                            incoming="求链接", thread="item_1"))
        s1.close()

        s2 = Store(d / "s.db")
        hub2 = self._hub(s2)
        self.assertEqual(len(hub2.pending), 1)
        self.assertEqual(hub2.pending[0].text, "草稿一")
        self.assertEqual(hub2.pending[0].sender, "小王")
        s2.close()

    def test_credit_survives_restart(self):
        d = Path(tempfile.mkdtemp())
        s1 = Store(d / "s.db")
        hub1 = self._hub(s1)
        hub1.credit.approvals = 7
        hub1.credit.rejections = 2
        hub1._save_credit()
        s1.close()

        s2 = Store(d / "s.db")
        hub2 = self._hub(s2)
        self.assertEqual(hub2.credit.approvals, 7)
        self.assertEqual(hub2.credit.rejections, 2)
        s2.close()

    def test_resolve_is_idempotent(self):
        # 两个线程同时 approve 同一条时，只应该成功一次
        d = Path(tempfile.mkdtemp())
        s = Store(d / "s.db")
        qid = s.enqueue(Reply("douyin", "c1", "x"))
        self.assertTrue(s.resolve(qid, "approved"))
        self.assertFalse(s.resolve(qid, "approved"))
        s.close()

    def test_sent_records_auto_flag(self):
        d = Path(tempfile.mkdtemp())
        s = Store(d / "s.db")
        s.record_sent(Reply("douyin", "c1", "自动的"), auto=True)
        s.record_sent(Reply("douyin", "c2", "人工的"), auto=False)
        stats = s.stats()
        self.assertEqual(stats["sent_auto"], 1)
        self.assertEqual(stats["sent_manual"], 1)
        s.close()

    def test_prune_seen(self):
        d = Path(tempfile.mkdtemp())
        s = Store(d / "s.db")
        s.mark_seen("old")
        # 手动把时间改老
        s._conn.execute("UPDATE seen SET ts = 0 WHERE id = 'old'")
        removed = s.prune_seen(keep_days=1)
        self.assertEqual(removed, 1)
        s.close()


class MockAdapterStub(ChannelAdapter):
    name = "douyin"
    auto_send_allowed = True

    def poll(self):
        return []

    def send(self, reply):
        return True


# ---------------------------------------------------------------------------
# 6. 发送失败与调度
# ---------------------------------------------------------------------------

class TestSendFailure(unittest.TestCase):

    def test_failed_send_goes_back_to_queue(self):
        """
        发送失败被当成已发出，是这类系统最典型的静默故障：
        日志显示发了，用户没收到，运营以为处理完了。
        """
        hub = Hub(PERSONA, MockEngine())
        hub.register(FailingAdapter(), channels=["douyin"])
        hub._enqueue(Reply("douyin", "c1", "草稿", sender="小王", thread="item_1"))
        ok = hub.approve(0)
        self.assertFalse(ok)
        self.assertEqual(len(hub.pending), 1, "发送失败的草稿被吞掉了")


class TestScheduler(unittest.TestCase):

    def test_backoff_grows_then_caps(self):
        def boom():
            raise RuntimeError("接口挂了")

        s = Scheduler(boom, interval=10, backoff_max=60)
        self.assertEqual(s.next_delay(), 10)
        s.tick()
        s.tick()
        self.assertGreater(s.next_delay(), 10)
        for _ in range(10):
            s.tick()
        self.assertEqual(s.next_delay(), 60)     # 封顶

    def test_failures_reset_after_success(self):
        state = {"fail": True}

        def runner():
            if state["fail"]:
                raise RuntimeError("挂了")
            return 3

        s = Scheduler(runner, interval=10)
        s.tick()
        s.tick()
        self.assertEqual(s.failures, 2)
        state["fail"] = False
        s.tick()
        self.assertEqual(s.failures, 0)
        self.assertEqual(s.next_delay(), 10)

    def test_alert_deduplicated(self):
        sent = []
        s = Scheduler(lambda: 0, interval=10, on_alert=sent.append)
        for _ in range(5):
            s.check(queue_depth=999)      # 每次都应该判到队列积压
        self.assertEqual(len(sent), 1, "同一个问题重复告警会训练人忽略告警")

    def test_token_days_from_adapter(self):
        now = time.time()
        token = type("T", (), {"expires_at": now + 3 * 86400})()
        client = type("C", (), {"token": token})()
        ad = type("A", (), {"client": client, "last_errors": []})()
        s = Scheduler(lambda: 0, adapters=(ad,), clock=lambda: now)
        self.assertAlmostEqual(s.token_days_left(), 3.0, places=1)

    def test_token_expiring_alerts(self):
        sent = []
        now = time.time()
        token = type("T", (), {"expires_at": now + 0.5 * 86400})()
        client = type("C", (), {"token": token})()
        ad = type("A", (), {"client": client, "last_errors": []})()
        s = Scheduler(lambda: 0, adapters=(ad,), clock=lambda: now,
                      on_alert=sent.append)
        s.check()
        self.assertTrue(any("到期" in a.message for a in sent))


if __name__ == "__main__":
    unittest.main(verbosity=2)
