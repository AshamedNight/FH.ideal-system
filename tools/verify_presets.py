"""人设预设（PersonaPresets）的验算。

两部分：
  1. **源码核对** —— 关键不变量在代码里真的写了（不是靠界面保证）
  2. **逻辑复刻** —— 用 Python 按同一套规则跑一遍，验证
     「同时只有一份生效」「删掉生效那份会回落」「最后一份不能删」这些边界

复刻是复刻，不是 Kotlin 的单测：它能抓出规则写错，抓不出类型错误。
跑法：
    python tools/verify_presets.py
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PRESETS_KT = ROOT / "app/src/main/java/com/autoreply/app/core/persona/PersonaPresets.kt"
PERSONA_UI = ROOT / "app/src/main/java/com/autoreply/app/ui/PersonaActivity.kt"
SETTINGS_UI = ROOT / "app/src/main/java/com/autoreply/app/ui/SettingsActivity.kt"

fails = []


def check(what, ok, detail=""):
    if not ok:
        fails.append(f"{what} {detail}".strip())
    print(f"  {'✓' if ok else '✗'} {what}" + (f"  {detail}" if detail else ""))


src = PRESETS_KT.read_text(encoding="utf-8")

# ── 1. 源码核对 ────────────────────────────────────────────────
print("【1】不变量写在代码里，不是靠界面")
check("一份预设同时存 personaJson 和 override", 'put("personaJson"' in src and 'put("override"' in src)
check("切换时写回 personaJson", 'personaJson = body.optString("personaJson"' in src)
check("切换时写回覆盖内容", 'PersonaStore.writeRaw(' in src)
check("同一时间只有一个 active（单个 active 字段）", 'put("active", ' in src)
check("最后一份不能删", "arr.length() <= 1" in src)
check("删的正好是生效那份时回落", "fallback" in src and 'o.put("active", fb.optString' in src)
check("快照包含身份与话术两部分", src.count("bodyOf(") >= 3, f"bodyOf 出现 {src.count('bodyOf(')} 次")

print("\n【2】谁在什么时候落盘")
check("保存话术页 → 存进当前预设", "PersonaPresets.snapshotActive(this)" in PERSONA_UI.read_text(encoding="utf-8"))
check("保存设置页 → 也存进当前预设（身份改了不能丢）",
      "PersonaPresets.snapshotActive(this)" in SETTINGS_UI.read_text(encoding="utf-8"))
ui = PERSONA_UI.read_text(encoding="utf-8")
for fn in ("activate(", "createFromCurrent(", "rename(", "remove("):
    check(f"界面调用 {fn}…", f"PersonaPresets.{fn}" in ui)

# ── 3. 逻辑复刻 ────────────────────────────────────────────────
print("\n【3】复刻一遍规则，跑边界")


class Cabinet:
    """按 PersonaPresets 的规则复刻：档案柜 + 一份生效内容（personaJson / override）。"""

    def __init__(self, persona_json="", override=None):
        self.store = {"personaJson": persona_json, "override": override or {}}
        self.data = None

    def load(self):
        if self.data and self.data["presets"]:
            return self.data
        pid = "p1"
        self.data = {
            "active": pid,
            "presets": [{"id": pid, "name": "默认人设", "body": dict(self.store)}],
        }
        return self.data

    def list(self):
        return [(p["id"], p["name"]) for p in self.load()["presets"]]

    def active(self):
        d = self.load()
        ids = [p["id"] for p in d["presets"]]
        return d["active"] if d["active"] in ids else ids[0]

    def _find(self, pid):
        for p in self.load()["presets"]:
            if p["id"] == pid:
                return p
        return None

    def activate(self, pid):
        p = self._find(pid)
        if not p:
            return False
        self.store = dict(p["body"])
        self.data["active"] = pid
        return True

    def snapshot(self):
        p = self._find(self.active())
        if not p:
            return False
        p["body"] = dict(self.store)
        return True

    def create(self, name):
        self.snapshot()
        d = self.load()
        body = dict(self._find(self.active())["body"])
        pid = f"p{len(d['presets']) + 1}"
        d["presets"].append({"id": pid, "name": name, "body": body})
        d["active"] = pid
        return pid

    def rename(self, pid, name):
        p = self._find(pid)
        if not p:
            return False
        p["name"] = name
        return True

    def remove(self, pid):
        d = self.load()
        if len(d["presets"]) <= 1 or not self._find(pid):
            return False
        rest = [p for p in d["presets"] if p["id"] != pid]
        d["presets"] = rest
        if d["active"] == pid:
            self.store = dict(rest[0]["body"])
            d["active"] = rest[0]["id"]
        return True


c = Cabinet("{\"identity\":\"A\"}", {"few_shot": [{"q": "你好", "a": "在"}]})
c.load()
check("首次进来自动建一份", len(c.list()) == 1)
check("生效的正是这一份", c.active() == c.list()[0][0])

first = c.list()[0][0]
# 新建一份（会先把当前内容快照进第一份），再只改新的那份
b = c.create("第二份")
check("新建后共两份", len(c.list()) == 2)
check("新建的那份自动生效", c.active() == b)
check("副本内容跟着当前这份", c.store["personaJson"] == "{\"identity\":\"A\"}")

c.store["personaJson"] = "{\"identity\":\"C\"}"
c.snapshot()

# 切回第一份：拿到的必须是它自己的 A，不能被第二份的 C 污染
c.activate(first)
check("切回第一份拿到 A（两份内容互不污染）", c.store["personaJson"] == "{\"identity\":\"A\"}")
check("同一时间只有一份生效（active 唯一）", sum(1 for i, _ in c.list() if i == c.active()) == 1)

c.activate(b)
check("再切到第二份拿到 C", c.store["personaJson"] == "{\"identity\":\"C\"}")

# 删掉生效那份
c.remove(b)
check("删掉生效那份后还剩一份", len(c.list()) == 1)
check("生效自动落到剩下那份", c.active() == c.list()[0][0])
check("内容跟着回落到 A", c.store["personaJson"] == "{\"identity\":\"A\"}")

# 最后一份不能删
check("最后一份删不掉", c.remove(c.active()) is False)

# 重命名不影响生效
c.rename(c.active(), "改个名")
check("重命名不改生效", c.active() == c.list()[0][0])
check("名字真的改了", c.list()[0][1] == "改个名")

print()
if fails:
    print(f"✗ {len(fails)} 项不通过：")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("全部通过。")
