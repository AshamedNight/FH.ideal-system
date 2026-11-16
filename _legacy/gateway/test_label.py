#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_label.py — M1 标注工具的确定性测试

跑法：python -m unittest test_label -v

这个工具的价值全在数字准不准。所以测试集中在三处：
  · 解析别切错（评论里本来就有逗号）
  · 混淆矩阵别算反（该回没回 和 不该回却回了 是两种错，代价不同）
  · 盲标别漏（标注阶段不能把系统判定带出去）
"""

import json
import tempfile
import unittest
from pathlib import Path

from label import Session, evaluate, parse_pasted, score, wilson_interval


class TestParse(unittest.TestCase):

    def test_plain_lines(self):
        rows = parse_pasted("求链接\n这个多少钱\n\n好看")
        self.assertEqual([r["text"] for r in rows],
                         ["求链接", "这个多少钱", "好看"])
        self.assertTrue(all(r["sender"] for r in rows))

    def test_tab_separated(self):
        rows = parse_pasted("小王\t求链接\n小李\t这个多少钱")
        self.assertEqual(rows[0]["sender"], "小王")
        self.assertEqual(rows[0]["text"], "求链接")

    def test_numbered_prefix_stripped(self):
        rows = parse_pasted("1. 求链接\n2. 这个多少钱\n10. 好看")
        self.assertEqual([r["text"] for r in rows],
                         ["求链接", "这个多少钱", "好看"])

    def test_comma_is_not_a_separator(self):
        # 「好看，多少钱」是一条评论。按逗号切会变成两条，而且不会报错。
        rows = parse_pasted("好看，多少钱")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["text"], "好看，多少钱")

    def test_empty(self):
        self.assertEqual(parse_pasted(""), [])
        self.assertEqual(parse_pasted("\n \n\t\n"), [])


class TestScore(unittest.TestCase):

    def _items(self):
        return [
            {"text": "a", "label": "reply", "system": "reply", "draft": "x"},
            {"text": "b", "label": "skip", "system": "silent", "draft": ""},
            {"text": "c", "label": "reply", "system": "silent", "draft": ""},
            {"text": "d", "label": "skip", "system": "reply", "draft": "y"},
            {"text": "e", "label": "unsure", "system": "reply", "draft": "z"},
            {"text": "f", "label": None, "system": "reply", "draft": "w"},
        ]

    def test_confusion_matrix(self):
        s = score(self._items())
        self.assertEqual(s["matrix"], {"tp": 1, "tn": 1, "fn": 1, "fp": 1})
        self.assertEqual(s["valid"], 4)
        self.assertEqual(s["agree"], 2)
        self.assertAlmostEqual(s["rate"], 0.5)

    def test_unsure_excluded_from_denominator(self):
        s = score(self._items())
        self.assertEqual(s["unsure"], 1)
        # 分母是 4（tp+tn+fn+fp），不是 5
        self.assertEqual(s["valid"], 4)

    def test_unlabeled_counted_separately(self):
        self.assertEqual(score(self._items())["todo"], 1)

    def test_enough_flag(self):
        self.assertFalse(score(self._items())["enough"])
        many = [{"text": str(i), "label": "reply", "system": "reply"}
                for i in range(100)]
        self.assertTrue(score(many)["enough"])

    def test_empty(self):
        s = score([])
        self.assertEqual(s["valid"], 0)
        self.assertEqual(s["rate"], 0.0)

    def test_disagreements_listed(self):
        s = score(self._items())
        self.assertEqual([d["text"] for d in s["false_negative"]], ["c"])
        self.assertEqual([d["text"] for d in s["false_positive"]], ["d"])
        self.assertEqual(s["false_positive"][0]["draft"], "y")


class TestEvaluate(unittest.TestCase):

    def test_system_verdict_matches_pipeline(self):
        rows = [
            {"sender": "A", "text": "求链接"},
            {"sender": "B", "text": "这个好看"},
            {"sender": "C", "text": "能便宜点吗"},
        ]
        out = evaluate(rows)
        self.assertEqual(len(out), 3)
        self.assertEqual(out[0]["system"], "reply")
        # 夸赞没有购买意图 → 不接
        self.assertEqual(out[1]["system"], "silent")
        # 议价被风控拦下，用户拿不到回复 → 归为「不回」
        self.assertEqual(out[2]["system"], "silent")
        self.assertEqual(out[2]["system_detail"], "blocked")

    def test_every_item_has_verdict(self):
        out = evaluate([{"sender": "A", "text": "有现货吗"}])
        self.assertIn(out[0]["system"], ("reply", "silent"))
        self.assertTrue(out[0]["system_detail"])


class TestSession(unittest.TestCase):

    def _session(self):
        d = Path(tempfile.mkdtemp())
        return Session(d / "set.json"), d / "set.json"

    def test_import_then_label(self):
        s, path = self._session()
        n = s.import_text("求链接\n好看")
        self.assertEqual(n, 2)
        self.assertTrue(s.label(0, "reply"))
        st = s.state()
        self.assertEqual(st["labeled"], 1)
        self.assertEqual(st["total"], 2)

    def test_clear_label_for_undo(self):
        # 标错一条要能撤，否则只能整批重来
        s, _ = self._session()
        s.import_text("求链接")
        s.label(0, "reply")
        self.assertTrue(s.label(0, ""))
        self.assertEqual(s.state()["labeled"], 0)

    def test_invalid_label_rejected(self):
        s, _ = self._session()
        s.import_text("求链接")
        self.assertFalse(s.label(0, "maybe"))

    def test_blind_state_hides_system(self):
        """
        盲标的核心保证：标注阶段后端不下发 system / draft。
        前端拿不到，就不可能无意泄露给标注的人。
        """
        s, _ = self._session()
        s.import_text("求链接")
        st = s.state()
        self.assertFalse(st["revealed"])
        for it in st["items"]:
            self.assertNotIn("system", it)
            self.assertNotIn("draft", it)

    def test_reveal_returns_system(self):
        s, _ = self._session()
        s.import_text("求链接\n这个好看")
        s.label(0, "reply")
        s.label(1, "skip")
        sc = s.reveal()
        self.assertEqual(sc["valid"], 2)
        self.assertEqual(s.state()["items"][0]["system"], "reply")

    def test_persists_across_restart(self):
        s, path = self._session()
        s.import_text("求链接")
        s.label(0, "reply")
        s2 = Session(path)
        self.assertEqual(s2.state()["labeled"], 1)
        self.assertEqual(s2.items[0]["text"], "求链接")

    def test_reset(self):
        s, path = self._session()
        s.import_text("求链接")
        s.reset()
        self.assertEqual(Session(path).state()["total"], 0)

    def test_import_file(self):
        s, _ = self._session()
        rec = Path(tempfile.mkdtemp()) / "rec.json"
        rec.write_text(json.dumps({"items": [
            {"sender": "A", "text": "求链接"},
            {"sender": "B", "text": "承重多少"},
        ]}, ensure_ascii=False), encoding="utf-8")
        self.assertEqual(s.import_file(rec), 2)
        self.assertEqual(s.state()["total"], 2)

    def test_import_tags_source(self):
        s, _ = self._session()
        s.import_text("求链接", source="similar")
        self.assertEqual(s.items[0]["source"], "similar")

    def test_append_keeps_previous_batch(self):
        s, _ = self._session()
        s.import_text("求链接", source="own")
        s.import_text("好看", source="similar", replace=False)
        self.assertEqual(len(s.items), 2)
        self.assertEqual(s.items[1]["source"], "similar")


class TestWilson(unittest.TestCase):
    """
    置信区间是「样本不足时别急着宣布达标」的依据。
    这组用例锁的是区间的方向性，不是精确数值。
    """

    def test_wider_with_smaller_sample(self):
        lo60, hi60 = wilson_interval(0.8, 60)
        lo200, hi200 = wilson_interval(0.8, 200)
        self.assertGreater(hi60 - lo60, hi200 - lo200)

    def test_zero_sample(self):
        self.assertEqual(wilson_interval(0.5, 0), (0.0, 1.0))

    def test_bounds_clamped(self):
        lo, hi = wilson_interval(1.0, 5)
        self.assertGreaterEqual(lo, 0.0)
        self.assertLessEqual(hi, 1.0)
        self.assertLessEqual(lo, hi)

    def test_80_at_60_is_not_conclusive(self):
        # 60 条上算出 80%，下界只有 0.68 左右——不能据此宣布达标
        lo, _ = wilson_interval(0.8, 60)
        self.assertLess(lo, 0.80)

    def test_larger_sample_becomes_conclusive(self):
        lo, _ = wilson_interval(0.85, 400)
        self.assertGreater(lo, 0.80)


class TestGroups(unittest.TestCase):

    def test_groups_split_by_source(self):
        items = [
            {"text": "a", "label": "reply", "system": "reply", "source": "own"},
            {"text": "b", "label": "skip", "system": "silent", "source": "own"},
            {"text": "c", "label": "skip", "system": "reply", "source": "similar"},
        ]
        s = score(items)
        self.assertEqual(s["groups"]["own"]["valid"], 2)
        self.assertEqual(s["groups"]["similar"]["valid"], 1)
        self.assertEqual(s["groups"]["similar"]["matrix"]["fp"], 1)

    def test_missing_source_defaults_to_own(self):
        items = [{"text": "a", "label": "reply", "system": "reply"}]
        self.assertIn("own", score(items)["groups"])

    def test_group_metrics_carry_interval(self):
        items = [{"text": str(i), "label": "reply", "system": "reply",
                  "source": "own"} for i in range(10)]
        g = score(items)["groups"]["own"]
        self.assertEqual(len(g["ci95"]), 2)
        self.assertLessEqual(g["ci95"][0], g["rate"] + 1e-9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
