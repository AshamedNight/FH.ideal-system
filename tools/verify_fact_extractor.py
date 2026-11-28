#!/usr/bin/env python3
"""
FactExtractor 的规则复刻 + 样例验证。

为什么需要：抽事实是"错了也不报错"的那类逻辑 —— 抽成"她 3 岁"、
或者把"我妹 28"记成"她 28"，界面上完全看不出来，只会让模型一本正经地说错话。
本机没有 JDK 跑不了单元测试，所以照项目惯例用 Python 复刻同一套正则，
拿几种真实会出现的句子钉住行为。

对应源码：app/src/main/java/com/autoreply/app/core/memory/FactExtractor.kt
改那边的正则，必须同步改这里，否则这个脚本只是"假通过"。
"""

import re

MIN_CONFIDENCE = 0.7

CITIES = ["淄博", "济南", "青岛", "潍坊", "烟台", "临沂", "济宁", "泰安", "威海",
          "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州", "成都", "重庆", "武汉"]

JOBS = ["美容", "美甲", "护士", "医生", "老师", "销售", "客服", "会计", "服务员",
        "工厂", "快递", "外卖", "电商", "主播", "保险", "房产", "银行", "餐饮", "文员"]

AGE = re.compile(r"(\d{1,2})\s*岁")
AGE_ALT = re.compile(r"今年\s*(\d{1,2})")
AVOID = re.compile(r"(讨厌|不喜欢|受不了|别叫|别问|反感)([\u4e00-\u9fa5A-Za-z]{1,6})")


class Turn:
    def __init__(self, text, from_other):
        self.text = text
        self.from_other = from_other


def extract(turns, self_identity=""):
    """返回 {key: (value, confidence, src)}。"""
    mine = self_identity.replace("\n", " ")
    out = {}

    def put(key, value, conf, src):
        if not value.strip() or key in out:
            return
        out[key] = (value, conf, src[:30])

    for t in turns:
        if not t.from_other:
            continue
        s = t.text

        if "age" not in out:
            m = AGE.search(s) or AGE_ALT.search(s)
            if m:
                age = int(m.group(1))
                if 14 <= age <= 60:
                    put("age", f"{age} 岁", 0.9, s)

        if "city" not in out:
            for c in CITIES:
                if c in s:
                    put("city", c, 0.8, s)
                    break

        if "job" not in out:
            for j in JOBS:
                if j in s:
                    put("job", "做" + j, 0.7, s)
                    break

        if "marriage" not in out:
            if "离婚" in s or "离异" in s:
                put("marriage", "离异", 0.9, s)
            elif "已婚" in s or "结过婚" in s:
                put("marriage", "已婚", 0.9, s)
            elif "单身" in s or "没对象" in s:
                put("marriage", "单身", 0.7, s)

        if "kid" not in out:
            for kid in ["女儿", "儿子", "孩子", "娃"]:
                if kid in s:
                    km = re.search(kid + r"[^。！\n]{0,6}?(\d{1,2})\s*岁", s)
                    put("kid", f"有{kid} {km.group(1)} 岁" if km else f"有{kid}", 0.8, s)
                    break

        if "want" not in out:
            if any(w in s for w in ["找对象", "找老公", "找老婆", "结婚"]):
                put("want", "想找对象", 0.8, s)
            elif any(w in s for w in ["无聊", "寂寞", "找人聊", "陪聊"]):
                put("want", "想找人聊天", 0.7, s)

        if "avoid" not in out:
            am = AVOID.search(s)
            if am:
                put("avoid", f"不喜欢「{am.group(2)}」", 0.8, s)

    # 命中人设的城市/职业 → 降成 0.5（不进提示词，但留在库里标"不确定"）
    def tainted(v):
        core = v[1:] if v.startswith("做") else v
        return bool(core.strip()) and core.strip() in mine

    for k in ("city", "job"):
        if k in out and tainted(out[k][0]):
            v, _conf, src = out[k]
            out[k] = (v, 0.5, src)
    return out


IDENTITY = "我叫小陈，32 岁，在淄博做美容，早九晚九。"

CASES = [
    # (说明, 句子(我=对方), 期望 {key: (value, conf)}, 身份)
    ("自介三连", "我今年28岁，离异，有个女儿3岁",
     {"age": ("28 岁", 0.9), "marriage": ("离异", 0.9), "kid": ("有女儿 3 岁", 0.8)}, ""),
    ("同城同行要说的话 → 降置信", "我在淄博做美容",
     {"city": ("淄博", 0.5), "job": ("做美容", 0.5)}, IDENTITY),
    ("不同城 → 正常置信", "我在济南做护士",
     {"city": ("济南", 0.8), "job": ("做护士", 0.7)}, IDENTITY),
    ("雷区", "讨厌被叫美女", {"avoid": ("不喜欢「被叫美女」", 0.8)}, ""),
    ("诉求", "平时挺无聊的，想找人聊", {"want": ("想找人聊天", 0.7)}, ""),
    ("孩子的年龄不能算成她本人", "我3岁", {}, ""),
]


def main():
    ok = True
    for desc, text, want, identity in CASES:
        got = extract([Turn(text, True)], identity)
        good = True
        for k, (v, conf) in want.items():
            if k not in got or got[k][0] != v or abs(got[k][1] - conf) > 1e-6:
                good = False
        # 期望表里没提到的高置信事实，不该冒出来
        for k, (v, conf, _s) in got.items():
            if k not in want and conf >= MIN_CONFIDENCE:
                good = False
        print(("  ✓ " if good else "  ✗ ") + f"{desc}：{text}")
        if not good:
            print("      期望:", want)
            print("      实际:", got)
            ok = False

    # 我方说的话不参与抽取（from_other=False）
    mine = extract([Turn("我在淄博做美容", False)], IDENTITY)
    good = not mine
    print(("  ✓ " if good else "  ✗ ") + "我方气泡不参与抽取")
    if not good:
        print("      实际:", mine)
    ok = ok and good

    print()
    print("全部通过" if ok else "有样例不符，先修规则")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
