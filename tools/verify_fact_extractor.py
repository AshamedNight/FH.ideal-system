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

CITIES = ["淄博", "济南", "青岛", "潍坊", "烟台", "临沂", "济宁", "泰安", "威海",
          "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州", "成都", "重庆", "武汉"]

JOBS = ["美容", "美甲", "护士", "医生", "老师", "销售", "客服", "会计", "服务员",
        "工厂", "快递", "外卖", "电商", "主播", "保险", "房产", "银行", "餐饮", "文员"]

AGE = re.compile(r"(\d{1,2})\s*岁")
AGE_ALT = re.compile(r"今年\s*(\d{1,2})")

AVOID = re.compile(r"(讨厌|不喜欢|受不了|别叫|别问|反感)([\u4e00-\u9fa5A-Za-z]{1,6})")


def extract(said: str) -> dict:
    out = {}

    m = AGE.search(said) or AGE_ALT.search(said)
    if m:
        age = int(m.group(1))
        if 14 <= age <= 60:            # "3 岁"多半是孩子，不是她
            out["age"] = f"{age} 岁"

    for c in CITIES:
        if c in said:
            out["city"] = c
            break

    for j in JOBS:
        if j in said:
            out["job"] = "做" + j
            break

    if "离婚" in said or "离异" in said:
        out["marriage"] = "离异"
    elif "已婚" in said or "结过婚" in said:
        out["marriage"] = "已婚"
    elif "单身" in said or "没对象" in said:
        out["marriage"] = "单身"

    for kid in ["女儿", "儿子", "孩子", "娃"]:
        if kid in said:
            km = re.search(kid + r"[^。！\n]{0,6}?(\d{1,2})\s*岁", said)
            out["kid"] = f"有{kid} {km.group(1)} 岁" if km else f"有{kid}"
            break

    if any(w in said for w in ["找对象", "找老公", "找老婆", "结婚"]):
        out["want"] = "想找对象"
    elif any(w in said for w in ["无聊", "寂寞", "找人聊", "陪聊"]):
        out["want"] = "想找人聊天"

    am = AVOID.search(said)
    if am:
        out["avoid"] = f"不喜欢「{am.group(2)}」"

    return out


CASES = [
    ("我今年28岁，离异，有个女儿3岁",
     {"age": "28 岁", "marriage": "离异", "kid": "有女儿 3 岁"}),
    ("我在淄博做美容，早九晚九",
     {"city": "淄博", "job": "做美容"}),
    ("讨厌被叫美女，别问体重",
     {"avoid": "不喜欢「被叫美女」"}),
    ("平时挺无聊的，想找人聊聊天",
     {"want": "想找人聊天"}),
    ("我妹今年28", {"age": "28 岁"}),                 # 会误记成她 —— 已知取舍
    ("哈哈好的", {}),                                  # 无信息，不该抽
    ("我3岁", {}),                                     # 孩子年龄不能被当成本人
]

if __name__ == "__main__":
    ok = True
    for said, want in CASES:
        got = extract(said)
        good = all(got.get(k) == v for k, v in want.items()) and \
            all(k in want or k not in got for k in got if k not in want)
        print(("  ✓ " if good else "  ✗ ") + said)
        if not good:
            print("      期望:", want)
            print("      实际:", got)
            ok = False
    print()
    print("全部通过" if ok else "有样例不符，先修规则")
    raise SystemExit(0 if ok else 1)
