#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
demo_run.py — 无平台授权时的完整回放演示

这个脚本解决一个具体问题：抖音权限还没批，怎么把系统跑给别人看。

做法是把链路的起点从「平台接口」换成「评论快照」，终点从「抖音评论区」
换成「你自己能收到的信道」。中间四段——判定、风控、生成、投递——全都是真的。

  平台接口  →  评论快照（recordings/*.json）
  判定生成  →  不变
  抖音评论区 →  本地文件 / 企业微信群 / QQ 群

用法
----
  python demo_run.py                                   # 投到本地文件
  python demo_run.py --reasons                         # 同时打印判定依据
  python demo_run.py --webhook https://qyapi.weixin...  # 投到企微群
  python demo_run.py --webhook https://oapi.dingtalk... --style dingtalk
  python demo_run.py --config demo_outlet.json          # 从配置读出口
  python demo_run.py --dry                             # 只看不发

演示时最有用的一条：把消息投到自己的群，手机上能看到。
这比在终端打印十行日志有说服力——它是真的发出去了，只是终点换了地方。

依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from gateway import Hub, Inbound, MockEngine, Persona, Surface
from outlet import LocalOutlet, build_outlet

HERE = Path(__file__).parent
DEFAULT_RECORDING = HERE / "recordings" / "demo_comments.json"


def load_recording(path: Path) -> tuple[str, str, list[Inbound]]:
    """读评论快照，转成统一消息模型。"""
    data = json.loads(path.read_text(encoding="utf-8"))
    item_id = str(data.get("item_id") or "item_demo")
    base = time.time() - 7200          # 假设这批评论是过去两小时里的

    items: list[Inbound] = []
    for i, row in enumerate(data.get("items", [])):
        items.append(Inbound(
            channel="douyin",
            conv_id=f"demo_{i:03d}",
            sender=str(row.get("sender") or "匿名"),
            text=str(row.get("text") or ""),
            # 每条间隔 3 分钟，避免被刷屏规则降权
            ts=base + float(row.get("ts_offset", i * 180)),
            surface=Surface(row.get("surface") or "comment"),
            thread=item_id,
            is_mentioned=bool(row.get("is_mentioned")),
            parent_id=str(row.get("parent_id") or ""),
            raw=row,
        ))
    return str(data.get("name") or path.stem), item_id, items


def run(recording: Path, outlet, show_reasons: bool = False,
        dry: bool = False) -> int:
    name, item_id, items = load_recording(recording)
    persona = Persona.load(HERE / "persona.json")

    hub = Hub(persona, MockEngine(), rate_limit=30, necessity=True)
    from gateway import MockAdapter
    hub.register(MockAdapter(items), channels=["douyin"])

    # 一次 step 处理一条，跑到没有新消息为止
    for _ in range(len(items) + 1):
        if not hub.step():
            break

    width = 78
    print("=" * width)
    print(f"回放演示 · {name}")
    print(f"评论 {len(items)} 条 · 作品 {item_id} · 出口 {outlet.name}")
    print("=" * width)

    # 结局按处理顺序取
    traces = hub.traces
    replies = {i: r for i, r in enumerate(hub.pending)}

    delivered = 0
    failed = 0
    for i, t in enumerate(traces, 1):
        mark = "@" if t.get("surface") == "comment" and t.get("thread") else " "
        final = t["final"]
        label = {
            "sent": "已发送", "pending": "待确认",
            "silent": "沉默", "blocked": "拦截", "no_draft": "无草稿",
        }.get(final, final)

        print(f"\n{i:>2}. {t['sender']}：{t['text']}")

        if final == "pending" and t.get("draft"):
            if show_reasons:
                print(f"    判定 {t.get('necessity')}｜" +
                      " / ".join(t.get("necessity_reasons") or ["—"]))
            if dry:
                print(f"    草稿（未投递）：{t['draft']}")
                continue
            d = outlet.deliver(t["draft"], {
                "sender": t["sender"], "incoming": t["text"],
                "channel": t["channel"], "item_id": t.get("thread", ""),
            })
            if d.ok:
                delivered += 1
                print(f"    → {label}｜已投递：{t['draft']}")
            else:
                failed += 1
                print(f"    → {label}｜投递失败：{d.detail}")
        elif final == "pending":
            print(f"    → {label}｜引擎给不出草稿，留给人工")
        elif final == "silent":
            if show_reasons:
                print(f"    判定 {t.get('necessity')}｜" +
                      " / ".join(t.get("necessity_reasons") or ["—"]))
            print(f"    → {label}（不参与对话）")
        elif final == "blocked":
            print(f"    → {label}｜{t.get('guard_reason', '')}")
        else:
            print(f"    → {label}")

    counts: dict[str, int] = {}
    for t in traces:
        counts[t["final"]] = counts.get(t["final"], 0) + 1

    print("\n" + "-" * width)
    print("结局分布：" + "｜".join(
        f"{k} {v}" for k, v in sorted(counts.items())) or "—")
    print(f"投递：成功 {delivered} 条" + (f"，失败 {failed} 条" if failed else ""))

    # 自检：投递数必须等于有草稿的待确认数，否则就是漏发了
    expect = sum(1 for t in traces if t["final"] == "pending" and t.get("draft"))
    if not dry:
        ok = (delivered + failed) == expect
        print("自检：" + ("通过（每条草稿都有投递结果）" if ok
                        else f"不通过（草稿 {expect} 条，结果 {delivered + failed} 条）"))
        return 0 if ok and failed == 0 else 1
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="无授权回放演示")
    ap.add_argument("--recording", type=Path, default=DEFAULT_RECORDING)
    ap.add_argument("--webhook", default="", help="群机器人 URL")
    ap.add_argument("--style", default="wecom",
                    choices=["wecom", "dingtalk", "feishu"])
    ap.add_argument("--config", type=Path, default=None,
                    help="出口配置 JSON")
    ap.add_argument("--reasons", action="store_true", help="打印判定依据")
    ap.add_argument("--dry", action="store_true", help="只看不发")
    args = ap.parse_args(argv)

    if args.config:
        outlet = build_outlet(json.loads(args.config.read_text(encoding="utf-8")))
    elif args.webhook:
        outlet = build_outlet({"kind": "webhook", "url": args.webhook,
                               "style": args.style, "prefix": "【演示】"})
    else:
        outlet = LocalOutlet(HERE / "demo_outbox.jsonl")

    if not args.recording.exists():
        print(f"找不到评论快照：{args.recording}", file=sys.stderr)
        return 2
    return run(args.recording, outlet, args.reasons, args.dry)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
