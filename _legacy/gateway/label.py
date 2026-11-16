#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
label.py — M1 判定校准：导入真实评论 → 盲标 → 算一致率

为什么需要这个工具
------------------
M1 要回答的问题只有一个：**系统判「该回 / 不该回」，和人判的差多少？**
这个数字达不到 80%，后面接真接口、开自动全是白费。

但没有工具的话，这件事做不下去：
  · 100 条评论在 Excel 里翻来翻去，标到第 30 条就乱了
  · 标注时看见系统判定，会被锚定，算出来的一致率虚高
  · 算一致率靠手数，数错了没人知道

所以这个工具做三件事，每一件都对应上面一个坑：
  1. 粘贴导入，自动切成一条一条
  2. **盲标**：标注阶段前端拿不到系统判定，标完才揭晓
  3. 自动算混淆矩阵，把「该回没回」和「不该回却回了」分开列

盲标为什么要紧
--------------
如果先看到「系统说该回」，人会有两种反应：要么认同（一致率虚高），
要么较劲（只盯着分歧）。两种都让这个数字失去意义。
先标完再看，得到的才是「人工标准 vs 系统判断」的真实差距。

跑法
----
  python label.py            # 启动，浏览器打开 127.0.0.1:8771
  python label.py --port 9000

依赖：仅标准库。
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from gateway import Hub, Inbound, MockAdapter, MockEngine, Persona, Surface

HERE = Path(__file__).parent
DEFAULT_SET = HERE / "recordings" / "labeling_set.json"
UI_FILE = HERE / "label_ui.html"

# 系统判定 → 二分类。blocked 也算「没回出去」：
# 用户最终没收到回复，和沉默的结果一样。
OPEN_FINALS = {"sent", "pending"}
CLOSED_FINALS = {"silent", "blocked", "no_draft"}

LABELS = {"reply", "skip", "unsure"}


# ---------------------------------------------------------------------------
# 导入
# ---------------------------------------------------------------------------

def parse_pasted(text: str) -> list[dict]:
    """
    把粘贴的文本切成评论列表。

    支持两种格式：
      每行一条评论
      昵称<TAB>评论        （从表格里复制出来通常是这样）

    刻意不支持逗号分隔：评论里本来就有逗号，
    按逗号切会把「好看，多少钱」切成两条，而且不报错。
    宁可少支持一种格式，也不要静默切错。
    """
    out: list[dict] = []
    for raw in (text or "").splitlines():
        line = raw.strip()
        if not line:
            continue
        # 去掉常见的行首装饰：「1. 」「- 」「· 」
        for prefix in ("- ", "· ", "* "):
            if line.startswith(prefix):
                line = line[len(prefix):].strip()
                break
        if len(line) > 3 and line[0].isdigit():
            head = line.split(".", 1)[0]
            if head.isdigit() and line[len(head):len(head) + 1] == ".":
                line = line[len(head) + 1:].strip()
        if not line:
            continue

        if "\t" in line:
            sender, _, body = line.partition("\t")
            sender, body = sender.strip(), body.strip()
        else:
            sender, body = "", line

        if not body:
            continue
        out.append({"sender": sender or f"用户{len(out) + 1}", "text": body})
    return out


# ---------------------------------------------------------------------------
# 用现有判定链路跑一遍，得到「系统会怎么处理」
# ---------------------------------------------------------------------------

def evaluate(items: list[dict], persona: Persona | None = None) -> list[dict]:
    """
    对每条评论跑完整判定，返回带 system 字段的列表。

    用 Hub 而不是直接调 judge()：这样跑的是真实链路（风控 → 必要性 → 生成），
    而不是只测其中一层。评估要评的是「系统最终会不会回」，不是「打分多少」。

    时间戳按 3 分钟递增，避免「刚回复过」的扣分跨条污染——
    评估要的是每条独立的判断，不是一次运行里的相互影响。
    """
    persona = persona or Persona.load(HERE / "persona.json")
    base = time.time() - len(items) * 180

    inbox = [
        Inbound(
            channel="douyin",
            conv_id=f"eval_{i:04d}",
            sender=it.get("sender") or f"用户{i + 1}",
            text=it["text"],
            ts=base + i * 180,
            surface=Surface("comment"),
            thread="eval_item",
            is_mentioned=bool(it.get("is_mentioned")),
        )
        for i, it in enumerate(items)
    ]

    hub = Hub(persona, MockEngine(), rate_limit=len(items) + 10, necessity=True)
    hub.register(MockAdapter(inbox), channels=["douyin"])
    for _ in range(len(inbox) + 1):
        if not hub.step():
            break

    # traces 里没有 conv_id，按处理顺序对齐。Hub 的 step 是「poll 一条、处理一条」，
    # 所以第 i 条消息对应第 i 个 trace。
    traces = hub.traces
    out: list[dict] = []
    for i, it in enumerate(items):
        t = traces[i] if i < len(traces) else {}
        final = t.get("final") or "no_draft"
        out.append({
            **it,
            "system": "reply" if final in OPEN_FINALS else "silent",
            "system_detail": final,
            "system_score": t.get("necessity"),
            "system_reason": (t.get("guard_reason")
                              or (" / ".join(t.get("necessity_reasons") or [])[:80])),
            "draft": t.get("draft", ""),
            "label": None,
        })
    return out


# ---------------------------------------------------------------------------
# 打分
# ---------------------------------------------------------------------------

def wilson_interval(p: float, n: int, z: float = 1.96) -> tuple[float, float]:
    """
    比例的 95% 置信区间（Wilson 区间）。

    为什么不能只报一个一致率
    ------------------------
    60 条样本上算出 80%，听起来达标。但 80% 的真实含义是
    「真值有 95% 的概率落在 70%–90% 之间」——它并不能证明「≥80%」。

    小样本用正态近似会给出负数下界，Wilson 不会。样本量本来就小的时候，
    这个差别会直接影响结论。
    """
    if n <= 0:
        return (0.0, 1.0)
    p = max(0.0, min(1.0, p))
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, center - half), min(1.0, center + half))


def _metrics(items: list[dict]) -> dict:
    """一组评论的核心指标。score() 和分组统计共用这一段。"""
    valid = [it for it in items if it.get("label") in ("reply", "skip")]
    unsure = [it for it in items if it.get("label") == "unsure"]
    todo = [it for it in items if not it.get("label")]

    tp = [it for it in valid if it["label"] == "reply" and it["system"] == "reply"]
    tn = [it for it in valid if it["label"] == "skip" and it["system"] == "silent"]
    # 该回没回：错过商机，代价是一次转化机会
    fn = [it for it in valid if it["label"] == "reply" and it["system"] == "silent"]
    # 不该回却回了：公开场合说错话，代价是一次可被所有人看见的失误
    fp = [it for it in valid if it["label"] == "skip" and it["system"] == "reply"]

    agree = len(tp) + len(tn)
    total = len(valid)
    rate = agree / total if total else 0.0
    lo, hi = wilson_interval(rate, total)

    return {
        "total": len(items),
        "valid": total,
        "unsure": len(unsure),
        "todo": len(todo),
        "agree": agree,
        "rate": round(rate, 3),
        "ci95": [round(lo, 3), round(hi, 3)],
        "matrix": {"tp": len(tp), "tn": len(tn), "fn": len(fn), "fp": len(fp)},
        "false_negative": [{"text": it["text"], "draft": it.get("draft", "")}
                           for it in fn],
        "false_positive": [{"text": it["text"], "draft": it.get("draft", "")}
                           for it in fp],
        "unsure_items": [{"text": it["text"]} for it in unsure],
    }


def score(items: list[dict]) -> dict:
    """
    算一致率，并按来源分组。

    为什么要分组
    ------------
    手里只有 B 站账号的评论，数量不够。补样本有两条路：
    同品类的公开评论（补购买意图）、无关内容的评论（验证不误伤）。
    但这三批的分布不一样，混在一起算一个数字是假的。

    分组之后每组各自报数，各自带置信区间。
    「自有账号 62 条 78%」和「同品类 30 条 83%」是两个事实，
    合成「92 条 80%」就不是任何一个事实了。
    """
    out = _metrics(items)
    groups: dict[str, dict] = {}
    for src in ("own", "similar", "unrelated"):
        sub = [it for it in items if (it.get("source") or "own") == src]
        if sub:
            groups[src] = _metrics(sub)
    out["groups"] = {k: v for k, v in groups.items()
                     if k != "own" or v["total"] > 0}
    out["enough"] = out["valid"] >= 100
    return out


# ---------------------------------------------------------------------------
# 会话
# ---------------------------------------------------------------------------

class Session:
    """标注集的状态。单用户使用，一把锁足够。"""

    def __init__(self, path: Path = DEFAULT_SET):
        self.path = path
        self.lock = threading.RLock()
        self.items: list[dict] = []
        self.revealed = False
        self._load()

    # ---------- 持久化 ----------

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            d = json.loads(self.path.read_text(encoding="utf-8"))
            self.items = d.get("items") or []
            self.revealed = bool(d.get("revealed"))
        except (json.JSONDecodeError, OSError, TypeError):
            self.items, self.revealed = [], False

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps({
            "updated_at": time.time(),
            "revealed": self.revealed,
            "items": self.items,
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---------- 操作 ----------

    def import_text(self, text: str, source: str = "own",
                    replace: bool = True) -> int:
        """
        导入一批评论。

        source 标记这批评论的来源，报告按来源分组统计：
          own       自有账号的评论（真实场景，最可信）
          similar   同品类的公开评论（补购买意图样本）
          unrelated 无关内容的评论（验证不误伤）
        三批分布不同，混算一个数字没有意义，所以必须分组。
        """
        rows = parse_pasted(text)
        if not rows:
            return 0
        scored = evaluate(rows)
        for it in scored:
            it["source"] = source or "own"
        with self.lock:
            if replace:
                self.items = scored
            else:
                self.items.extend(scored)
            self.revealed = False
            self._save()
        return len(scored)

    def import_file(self, path: Path, source: str = "own") -> int:
        """导入评论快照（recordings/*.json）。"""
        d = json.loads(path.read_text(encoding="utf-8"))
        rows = [{"sender": r.get("sender", ""), "text": r.get("text", ""),
                 "is_mentioned": bool(r.get("is_mentioned"))}
                for r in d.get("items", []) if r.get("text")]
        if not rows:
            return 0
        scored = evaluate(rows)
        for it in scored:
            it["source"] = source or "own"
        with self.lock:
            self.items = scored
            self.revealed = False
            self._save()
        return len(scored)

    def label(self, index: int, label: str) -> bool:
        """
        给第 index 条打标。传空串表示清除标注（回退用）。

        允许空串是有意的：标错一条要能撤，否则只能整批重来——
        100 条的批次里标错一次就重来，没人愿意做第二遍。
        """
        if label and label not in LABELS:
            return False
        with self.lock:
            if not 0 <= index < len(self.items):
                return False
            self.items[index]["label"] = label or None
            self._save()
        return True

    def reveal(self) -> dict:
        with self.lock:
            self.revealed = True
            self._save()
            return score(self.items)

    def hide(self) -> None:
        with self.lock:
            self.revealed = False
            self._save()

    def reset(self) -> None:
        with self.lock:
            self.items = []
            self.revealed = False
            self._save()

    # ---------- 视图 ----------

    def state(self) -> dict:
        """
        返回前端需要的状态。

        盲标阶段刻意不下发 system / draft / system_reason：
        前端拿不到，就不会（哪怕无意地）泄露给标注的人。
        「不下发」比「下发了但界面上不显示」可靠得多。
        """
        with self.lock:
            if self.revealed:
                items = self.items
            else:
                items = [{"sender": it.get("sender", ""),
                          "text": it.get("text", ""),
                          "label": it.get("label")} for it in self.items]
            labeled = sum(1 for it in self.items if it.get("label"))
            return {
                "revealed": self.revealed,
                "total": len(self.items),
                "labeled": labeled,
                "items": items,
                "score": score(self.items) if self.revealed else None,
            }


SESSION = Session()


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):   # 静音访问日志
        pass

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def do_GET(self) -> None:
        if self.path in ("/", "/index.html"):
            if not UI_FILE.exists():
                self._send(500, "label_ui.html 缺失".encode("utf-8"),
                           "text/plain; charset=utf-8")
                return
            self._send(200, UI_FILE.read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/state":
            self._json(SESSION.state())
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._json({"error": "bad json"}, 400)
            return

        if self.path == "/api/import":
            n = SESSION.import_text(
                str(body.get("text") or ""),
                source=str(body.get("source") or "own"),
                replace=bool(body.get("replace", True)))
            self._json({"ok": True, "count": n, "total": SESSION.state()["total"]})
        elif self.path == "/api/import_file":
            raw = str(body.get("path") or "")
            p = Path(raw)
            # 相对路径按代码目录解析，不按进程工作目录——
            # 从别的目录启动服务时，相对路径会指到别处去。
            if not p.is_absolute():
                p = HERE / p
            if not p.exists():
                self._json({"error": f"找不到 {p}"}, 400)
                return
            try:
                n = SESSION.import_file(p)
            except (json.JSONDecodeError, OSError) as exc:
                self._json({"error": str(exc)}, 400)
                return
            self._json({"ok": True, "count": n})
        elif self.path == "/api/label":
            ok = SESSION.label(int(body.get("index", -1)),
                               str(body.get("label") or ""))
            self._json({"ok": ok})
        elif self.path == "/api/reveal":
            self._json(SESSION.reveal())
        elif self.path == "/api/hide":
            SESSION.hide()
            self._json({"ok": True})
        elif self.path == "/api/reset":
            SESSION.reset()
            self._json({"ok": True})
        else:
            self._json({"error": "not found"}, 404)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="M1 判定校准标注工具")
    ap.add_argument("--port", type=int, default=8771)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)

    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{args.port}"
    print(f"标注工具已启动：{url}")
    print(f"数据集：{SESSION.path}（{len(SESSION.items)} 条）")
    if not args.no_browser:
        try:
            webbrowser.open(url)
        except Exception:      # noqa: BLE001
            pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
