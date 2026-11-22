"""说话风格（TalkStyle）的静态验算。

本机没有 JDK，编不了 Kotlin，所以这里用正则**直接从源码里读**风格表，
不手抄 —— 一旦源码改了而这份脚本没跟上，它会立刻报出来，而不是悄悄给出旧结论。

跑法：
    python tools/verify_talk_style.py
"""

import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
STYLE_KT = ROOT / "app/src/main/java/com/autoreply/app/core/llm/TalkStyle.kt"
ENGINE_KT = ROOT / "app/src/main/java/com/autoreply/app/core/llm/ReplyEngine.kt"
CONFIG_KT = ROOT / "app/src/main/java/com/autoreply/app/core/AppConfig.kt"
IME_KT = ROOT / "app/src/main/java/com/autoreply/app/ime/AutoReplyImeService.kt"

fails = []


def read(p):
    return p.read_text(encoding="utf-8")


def expect(what, got, want):
    ok = got == want
    if not ok:
        fails.append(f"{what}: 实际 {got!r}，期望 {want!r}")
    print(f"  {'✓' if ok else '✗'} {what}: {got!r}" + ("" if ok else f"  期望 {want!r}"))


def check(what, ok, detail=""):
    if not ok:
        fails.append(f"{what} {detail}".strip())
    print(f"  {'✓' if ok else '✗'} {what}" + (f"  {detail}" if detail else ""))


src = read(STYLE_KT)

# ── 1. 读出风格表 ────────────────────────────────────────────────
# Item("id", "label", "hint")，hint 可能是空串
items = re.findall(
    r'Item\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(?:""|"((?:[^"\\]|\\.)*)")\s*,?\s*\)',
    src,
)
print(f"【1】从 TalkStyle.kt 读出 {len(items)} 种风格")
for sid, label, hint in items:
    print(f"   {sid:<8} {label:<3} hint {len(hint)} 字")

check("至少 2 种风格", len(items) >= 2)
check("id 不重复", len({i[0] for i in items}) == len(items))
check("label 不重复", len({i[1] for i in items}) == len(items))
check("label 不超过 3 字（面板一行 5 格，长了会挤）", all(len(l) <= 3 for _, l, _ in items))

# ── 2. 默认值与回落 ─────────────────────────────────────────────
default_id = re.search(r'const val DEFAULT_ID = "([^"]+)"', src).group(1)
print(f"\n【2】默认风格 {default_id}")
expect("DEFAULT_ID 在表里", any(i[0] == default_id for i in items), True)
expect("DEFAULT_ID 是表里第一个（byId 的回落就是取 [0]）", items[0][0], default_id)

cfg_default = re.search(r'getString\(KEY_TALK_STYLE, "([^"]*)"\)', read(CONFIG_KT))
print(f"\n【3】AppConfig 默认值 {cfg_default.group(1) if cfg_default else '（没读到）'}")
check("AppConfig 读到了默认值", cfg_default is not None)
if cfg_default:
    expect("AppConfig 的默认值和 DEFAULT_ID 一致", cfg_default.group(1), default_id)

# ── 4. 空 hint 的意义 ───────────────────────────────────────────
# 「自然」是基线：hint 为空 = system 提示词一个字都不加 = 老用户升级后行为完全不变。
print("\n【4】hint 长度")
natural = [i for i in items if i[0] == default_id][0]
expect("默认风格的 hint 为空（不加指令＝保持原样）", natural[2], "")
for sid, label, hint in items:
    if sid == default_id:
        continue
    check(f"  {label} 的 hint 非空", hint.strip() != "")
    check(f"  {label} 的 hint 不超过 80 字（每轮都要随 system 发一次）", len(hint) <= 80, f"实际 {len(hint)}")

# 风格片段不该出现"长度/字数"这类硬约束 —— 那是 persona.styleRules 的职责，
# 两边都写就会互相打架，而且用户看到的是哪一条生效完全看模型心情。
for sid, label, hint in items:
    bad = [w for w in ("不超过", "字以内", "最多", "字数") if w in hint]
    check(f"  {label} 不插手长度约束", not bad, f"含 {bad}" if bad else "")

# ── 5. 注入点 ───────────────────────────────────────────────────
eng = read(ENGINE_KT)
print("\n【5】system 提示词里的注入点")
check("ReplyEngine 引用了 TalkStyle.hintOf", "TalkStyle.hintOf(" in eng)
check("有「hint 为空就不加」的判断", "if (styleHint.isNotEmpty())" in eng)

i_style = eng.find("TalkStyle.hintOf(")
i_rules = eng.find("persona.styleRules(maxChars)")
if i_style < 0 or i_rules < 0:
    fails.append("找不到风格注入点或 styleRules，检查 ReplyEngine.buildSystem")
    print("  ✗ 定位失败")
else:
    check("在 buildSystem 里", eng.find("private fun buildSystem") < i_style)
    # 顺序很关键：styleRules 里通常写着「每句不超过 X 字」，
    # 让它排在风格之后发声，风格才压不过长度。
    check("排在 styleRules 之前（长度等硬约束后置生效）", i_style < i_rules)

# ── 6. 面板是否真的用这张表 ─────────────────────────────────────
ime = read(IME_KT)
print("\n【6】输入法面板")
check("第 2 页按 TalkStyle.ALL 生成（加风格不用改面板）", "TalkStyle.ALL.map" in ime)
check("没有写死风格名（写死就会和表脱节）",
      not any(f'chip("{l}"' in ime for _, l, _ in items if l != "自动时段"))

print()
if fails:
    print(f"✗ {len(fails)} 项不通过：")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("全部通过。")
