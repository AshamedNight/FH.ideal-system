#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
persona_cli.py — 人设配置的校验、生成与跨人设对比

三个子命令
----------
  validate  检查配置有没有「必然会出错」的问题
  new       按模板生成一份最小可用人设
  diff      同一批评论在两份人设下的判定对比

diff 是这个工具的重点
---------------------
它回答的问题只有一个：**换人设之后，行为真的变了吗？**

如果两份完全不同行业的人设，对同一批评论给出一样的判定，
那说明判定根本没在用人设——所谓的「通用配置」是假的。
所以要有一个能一眼看出来的对照表。

用法
----
  python persona_cli.py validate personas/creator.json
  python persona_cli.py new --template creator --name 阿木 --role "做木工视频" \\
      --out personas/amu.json
  python persona_cli.py diff --a persona.json --b personas/creator.json
  python persona_cli.py diff --a persona.json --b personas/consulting.json \\
      --recording recordings/demo_comments.json

依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from demo_run import DEFAULT_RECORDING, load_recording
from gateway import Hub, MockAdapter, MockEngine, Persona
from persona_schema import (GENERIC_INTENTS, TEMPLATES, PersonaProfile,
                            load_profile, new_persona, save_profile)

HERE = Path(__file__).parent

ACTION_MARK = {"sent": "发出", "pending": "回复", "silent": "沉默",
               "blocked": "拦截", "no_draft": "无稿"}


def cmd_validate(args: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="validate")
    ap.add_argument("path", type=Path)
    ns = ap.parse_args(args)

    data, profile = load_profile(ns.path)
    problems = profile.validate()
    if not problems:
        print(f"通过 · {ns.path}")
        print(f"  身份：{profile.role or '（未填）'}")
        print(f"  形态：{profile.offering}")
        print(f"  意图：{', '.join(profile.active_intents) or '（全空）'}")
        print(f"  兜底：{profile.fallback.action}")
        return 0

    print(f"发现 {len(problems)} 个问题 · {ns.path}")
    for p in problems:
        print(f"  - {p}")
    return 1


def cmd_new(args: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="new")
    ap.add_argument("--template", required=True, choices=list(TEMPLATES))
    ap.add_argument("--name", required=True)
    ap.add_argument("--role", required=True, help="一句话身份")
    ap.add_argument("--out", type=Path, required=True)
    ns = ap.parse_args(args)

    if ns.out.exists():
        print(f"{ns.out} 已存在，不覆盖", file=sys.stderr)
        return 1
    data = new_persona(ns.template, ns.name, ns.role)
    ns.out.parent.mkdir(parents=True, exist_ok=True)
    ns.out.write_text(json.dumps(data, ensure_ascii=False, indent=2),
                      encoding="utf-8")
    print(f"已生成 {ns.out}（模板：{ns.template}）")
    print(f"  {TEMPLATES[ns.template]}")
    print("下一步：补 knowledge 和 few_shot——判定能用，但给不出草稿。")
    return 0


def _run(persona_path: Path, recording: Path) -> list[dict]:
    """
    用指定人设跑一遍，返回每条的处理结果。

    每次都重新读一遍评论：MockAdapter 会 pop 掉列表，
    复用同一个 items 会让第二次跑拿到空数据——而且不报错，
    结果就是 B 人设「全部没反应」，看起来像配置没生效。
    """
    _, _, items = load_recording(recording)
    persona = Persona.load(persona_path)
    hub = Hub(persona, MockEngine(), rate_limit=len(items) + 10, necessity=True)
    hub.register(MockAdapter(items), channels=["douyin"])
    for _ in range(len(items) + 1):
        if not hub.step():
            break
    return hub.traces


def cmd_diff(args: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="diff")
    ap.add_argument("--a", type=Path, required=True)
    ap.add_argument("--b", type=Path, required=True)
    ap.add_argument("--recording", type=Path, default=DEFAULT_RECORDING)
    ap.add_argument("--show-draft", action="store_true")
    ns = ap.parse_args(args)

    name, _, items = load_recording(ns.recording)
    pa, pb = Persona.load(ns.a), Persona.load(ns.b)
    ta = _run(ns.a, ns.recording)
    tb = _run(ns.b, ns.recording)

    w = 20
    print("=" * 76)
    print(f"跨人设对比 · {name} · {len(items)} 条")
    print(f"  A：{pa.name}（{pa.profile.offering}）　{ns.a}")
    print(f"  B：{pb.name}（{pb.profile.offering}）　{ns.b}")
    print("=" * 76)
    print(f"{'评论':<{w}} {'A':<16} {'B':<16} 变化")
    print("-" * 76)

    changed = 0
    for i, t in enumerate(ta):
        txt = t["text"][:w - 2] + ("…" if len(t["text"]) > w - 2 else "")
        a_final = t["final"]
        b = tb[i] if i < len(tb) else {}
        b_final = b.get("final", "?")
        a_lbl = ACTION_MARK.get(a_final, a_final)
        b_lbl = ACTION_MARK.get(b_final, b_final)
        a_s = t.get("necessity")
        b_s = b.get("necessity")
        a_cell = f"{a_lbl} {a_s if a_s is not None else '-'}"
        b_cell = f"{b_lbl} {b_s if b_s is not None else '-'}"
        diff = "≠" if a_final != b_final else ""
        if diff:
            changed += 1
        print(f"{txt:<{w}} {a_cell:<16} {b_cell:<16} {diff}")

        if ns.show_draft:
            da = (t.get("draft") or "（无）")[:60]
            db = (b.get("draft") or "（无）")[:60]
            print(f"{'':<{w}} A稿：{da}")
            print(f"{'':<{w}} B稿：{db}")

    print("-" * 76)
    print(f"{len(ta)} 条里有 {changed} 条处理结果不同")
    if changed == 0:
        print("两份人设给出完全一样的判定——大概率是意图词表没生效，"
              "先跑 validate 看看。")
        return 1
    print("换人设后行为确实变了，说明判定走的是配置，不是硬编码。")
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    table = {"validate": cmd_validate, "new": cmd_new, "diff": cmd_diff}
    if cmd not in table:
        print(f"未知子命令：{cmd}", file=sys.stderr)
        return 2
    return table[cmd](rest)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
