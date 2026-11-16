#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_persona.py — 人设配置层的确定性测试

跑法：python -m unittest test_persona -v

重点测三件事：
  · 旧配置能自动迁移，不会因为缺 profile 而崩
  · 配置矛盾能被 validate 抓出来（卖货却没购买词表 / 不卖货却有）
  · 换配置之后判定真的变了——这是「通用」这个词的全部含义
"""

import json
import tempfile
import unittest
from pathlib import Path

from gateway import Inbound, Persona, Surface
from necessity import Context, judge
from persona_schema import (GENERIC_INTENTS, Fallback, PersonaProfile,
                            detect_intent, load_profile, new_persona)


def _persona(profile: PersonaProfile, name: str = "测试") -> Persona:
    return Persona(name=name, identity="", tone={}, knowledge=[],
                   boundaries=[], few_shot=[], taboo_words=[], profile=profile)


def _judge(text: str, profile: PersonaProfile, mentioned: bool = False):
    msg = Inbound("douyin", "c1", "u", text, surface=Surface.COMMENT)
    return judge(msg, Context(surface="comment", is_mentioned=mentioned),
                 _persona(profile))


class TestMigration(unittest.TestCase):
    """旧配置没有 profile 字段，必须能用。"""

    def test_legacy_gets_default_intents(self):
        data = {"name": "小陆", "identity": "卖桌子的",
                "knowledge": [], "boundaries": ["便宜"], "few_shot": []}
        profile = PersonaProfile.from_legacy(data)
        self.assertEqual(profile.offering, "product")
        self.assertIn("purchase", profile.active_intents)
        self.assertIn("小陆", profile.aliases)

    def test_load_fills_profile_for_legacy_file(self):
        d = Path(tempfile.mkdtemp())
        p = d / "old.json"
        p.write_text(json.dumps({
            "name": "小陆", "identity": "卖桌子",
            "knowledge": [], "boundaries": [], "few_shot": [], "taboo_words": [],
        }, ensure_ascii=False), encoding="utf-8")
        data, profile = load_profile(p)
        self.assertTrue(data["profile"])       # 回填了
        self.assertTrue(profile.intents)

    def test_load_respects_existing_profile(self):
        d = Path(tempfile.mkdtemp())
        p = d / "new.json"
        p.write_text(json.dumps({
            "name": "阿木", "identity": "博主",
            "profile": {"role": "做视频", "offering": "content",
                        "intents": {"inquiry": ["怎么做的"]}},
        }, ensure_ascii=False), encoding="utf-8")
        _, profile = load_profile(p)
        self.assertEqual(profile.offering, "content")
        self.assertEqual(profile.intents["inquiry"], ["怎么做的"])


class TestValidate(unittest.TestCase):

    def test_product_without_purchase_is_error(self):
        p = PersonaProfile(offering="product",
                           intents={"purchase": [], "inquiry": ["怎么用"]})
        problems = p.validate()
        self.assertTrue(any("purchase" in x for x in problems))

    def test_content_with_purchase_is_error(self):
        # 内容号配了购买词表，会把「求链接」当商机——方向就错了
        p = PersonaProfile(offering="content",
                           intents={"purchase": ["求链接"], "inquiry": ["怎么做的"]})
        self.assertTrue(any("误判" in x for x in p.validate()))

    def test_unknown_intent_rejected(self):
        p = PersonaProfile(intents={"随便写的": ["x"], "purchase": ["求链接"]})
        self.assertTrue(any("未知意图" in x for x in p.validate()))

    def test_bad_fallback(self):
        p = PersonaProfile(intents={"purchase": ["求链接"]},
                           fallback=Fallback(action="后面再说"))
        self.assertTrue(any("fallback" in x for x in p.validate()))

    def test_template_requires_text(self):
        p = PersonaProfile(intents={"purchase": ["求链接"]},
                           fallback=Fallback(action="template", template=""))
        self.assertTrue(any("template" in x for x in p.validate()))

    def test_valid_config_passes(self):
        p = PersonaProfile(offering="product",
                           intents={"purchase": ["求链接"]},
                           fallback=Fallback(action="handoff"))
        self.assertEqual(p.validate(), [])


class TestDetectIntent(unittest.TestCase):

    def test_basic(self):
        p = PersonaProfile(intents={"purchase": ["求链接"], "inquiry": ["怎么用"]})
        self.assertEqual(detect_intent("求链接", p), ("purchase", "求链接"))

    def test_highest_weight_wins(self):
        # 同一个词同时出现在两个意图里，取权重高的那个。
        # 这里用 inquiry(0.35) 对 social(0.15)，不依赖具体数值——
        # 权重表会调，但「高者胜」这条规则不该跟着变。
        p = PersonaProfile(intents={"inquiry": ["好看"], "social": ["好看"]})
        intent, _ = detect_intent("好看", p)
        self.assertEqual(intent, "inquiry")
        self.assertGreater(p.weight_of("inquiry"), p.weight_of("social"))

    def test_no_match(self):
        p = PersonaProfile(intents={"purchase": ["求链接"]})
        self.assertEqual(detect_intent("今天天气不错", p), ("", ""))

    def test_empty_wordlist_means_disabled(self):
        # 内容号的 purchase 是空表——「求链接」不该被识别成购买意图
        p = PersonaProfile(offering="content",
                           intents={"purchase": [], "inquiry": ["怎么做的"]})
        self.assertEqual(detect_intent("求链接", p), ("", ""))

    def test_negative_is_detected(self):
        p = PersonaProfile(intents={"negative": ["骗"]})
        self.assertEqual(detect_intent("骗人的吧", p), ("negative", "骗"))


class TestBehaviorDiffers(unittest.TestCase):
    """
    这组用例是「通用配置」成立与否的证明。
    如果两份完全不同行业的人设对同一句话给出相同判定，那配置层就是装饰。
    """

    def setUp(self):
        self.ecom = PersonaProfile(
            offering="product",
            intents={"purchase": ["求链接", "多少钱"],
                     "inquiry": ["承重"],
                     "social": ["好看"]})
        self.creator = PersonaProfile(
            offering="content",
            intents={"purchase": [],
                     "inquiry": ["怎么做的", "什么设备"],
                     "social": ["好看"]})

    def test_buying_intent_replies_for_shops(self):
        j = _judge("求链接", self.ecom)
        self.assertEqual(j.action, "reply")

    def test_buying_intent_ignored_for_creators(self):
        # 同一条评论，内容号不该当商机接
        j = _judge("求链接", self.creator)
        self.assertNotEqual(j.action, "reply", f"内容号误判为商机：{j.score}")

    def test_inquiry_differs_by_vertical(self):
        # 「承重」是产品参数，内容号不关心
        self.assertEqual(_judge("承重多少", self.ecom).action, "reply")
        self.assertNotEqual(_judge("承重多少", self.creator).action, "reply")

    def test_shared_intent_behaves_same(self):
        # 「好看」两边都该接——社交意图是通用的
        self.assertEqual(_judge("好看", self.ecom, mentioned=True).action,
                         _judge("好看", self.creator, mentioned=True).action)

    def test_negative_never_replies(self):
        p = PersonaProfile(intents={"negative": ["骗"], "purchase": ["求链接"]})
        j = _judge("骗人的，求链接", p)
        self.assertEqual(j.action, "silent")
        self.assertTrue(any("负面" in r for r in j.reasons))


class TestNewPersona(unittest.TestCase):

    def test_creator_template_disables_purchase(self):
        data = new_persona("creator", "阿木", "做视频")
        profile = PersonaProfile.from_dict(data["profile"])
        self.assertEqual(profile.offering, "content")
        self.assertEqual(profile.intents["purchase"], [])

    def test_ecommerce_template_keeps_purchase(self):
        data = new_persona("ecommerce", "小陆", "卖桌子")
        profile = PersonaProfile.from_dict(data["profile"])
        self.assertTrue(profile.intents["purchase"])

    def test_generated_config_validates(self):
        for tpl in ("ecommerce", "creator", "consulting", "personal"):
            data = new_persona(tpl, "某人", "某身份")
            profile = PersonaProfile.from_dict(data["profile"])
            self.assertEqual(profile.validate(), [], f"{tpl} 模板自身不合法")

    def test_all_intents_known(self):
        # 模板里出现的意图必须都在固定集合内，否则 validate 会漏
        data = new_persona("consulting", "陈工", "咨询")
        for k in data["profile"]["intents"]:
            self.assertIn(k, GENERIC_INTENTS)


if __name__ == "__main__":
    unittest.main(verbosity=2)
