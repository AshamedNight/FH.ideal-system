#!/usr/bin/env python3
"""
检查「用了某个项目内的类，但既没 import、也不在同一个包」。

为什么需要它：`tools/precheck.sh` 只检查"必现失败"级别的结构问题
（字符串 ID 存在、Manifest 类文件存在、JSON 合法），类型错误完全靠 CI 才发现。
而"漏 import"是最常见的一种 —— CI 上跑一轮几分钟，本地几毫秒就能查出来。

只做一件事：对每个 .kt 文件，如果正文里出现了某个已知项目类的简单名，
就要求它满足以下任一条件：
  1. 在同一个包里
  2. 有 import（同名或通配）
  3. 用的是全限定名（`com.autoreply.app.xxx.ClassName`）

刻意不做完整的类型推断 —— 只覆盖"漏 import"这一类，误报少、跑得快。
"""
import os
import re
import sys

ROOT = "app/src/main/java"

# 项目内的类 → 全限定名。新增类时记得加到这里，否则不会被检查。
KNOWN = {
    # core
    "AppConfig": "com.autoreply.app.core.AppConfig",
    # core/guard
    "Energy": "com.autoreply.app.core.guard.Energy",
    "PacerGuard": "com.autoreply.app.core.guard.PacerGuard",
    "DraftRisk": "com.autoreply.app.core.guard.DraftRisk",
    "ReplyFlow": "com.autoreply.app.core.guard.ReplyFlow",
    "ReplyDedup": "com.autoreply.app.core.guard.ReplyDedup",
    "SentTracker": "com.autoreply.app.core.guard.SentTracker",
    "DraftQueue": "com.autoreply.app.core.guard.DraftQueue",
    "SentenceSplitter": "com.autoreply.app.core.guard.SentenceSplitter",
    # core/llm
    "ReplyEngine": "com.autoreply.app.core.llm.ReplyEngine",
    "Turn": "com.autoreply.app.core.llm.Turn",
    "LlmClient": "com.autoreply.app.core.llm.LlmClient",
    # core/persona
    "PersonaLoader": "com.autoreply.app.core.persona.PersonaLoader",
    "PersonaStore": "com.autoreply.app.core.persona.PersonaStore",
    "FewShot": "com.autoreply.app.core.persona.FewShot",
    "KnowledgeItem": "com.autoreply.app.core.persona.KnowledgeItem",
    "Tone": "com.autoreply.app.core.persona.Tone",
    "NecessityJudge": "com.autoreply.app.core.persona.NecessityJudge",
    "Persona": "com.autoreply.app.core.persona.Persona",
    # platform
    "Selector": "com.autoreply.app.platform.Selector",
    "PlatformProfile": "com.autoreply.app.platform.PlatformProfile",
    "Profiles": "com.autoreply.app.platform.Profiles",
    "ChatMessage": "com.autoreply.app.platform.ChatMessage",
    "NodeQuery": "com.autoreply.app.platform.NodeQuery",
    "AccessibilityReplySink": "com.autoreply.app.platform.AccessibilityReplySink",
    "FillResult": "com.autoreply.app.platform.FillResult",
    "TextWriter": "com.autoreply.app.platform.TextWriter",
    "ReplyRunner": "com.autoreply.app.platform.ReplyRunner",
    "ReplySink": "com.autoreply.app.platform.ReplySink",
    # capture / notify / ime
    "CaptureService": "com.autoreply.app.capture.CaptureService",
    "NodeDumper": "com.autoreply.app.capture.NodeDumper",
    "MessageNotificationListener": "com.autoreply.app.notify.MessageNotificationListener",
    "AutoReplyImeService": "com.autoreply.app.ime.AutoReplyImeService",
    # ui
    "MainActivity": "com.autoreply.app.ui.MainActivity",
    "SettingsActivity": "com.autoreply.app.ui.SettingsActivity",
    "PersonaActivity": "com.autoreply.app.ui.PersonaActivity",
}


def strip_noise(src: str) -> str:
    """去掉注释和多行字符串，避免把注释里的类名当成真实引用。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    return src


def main() -> int:
    problems = []
    for dirpath, _, files in os.walk(ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            raw = open(path, encoding="utf-8").read()

            pkg_m = re.search(r"^package\s+([\w.]+)", raw, re.M)
            pkg = pkg_m.group(1) if pkg_m else ""

            src = strip_noise(raw)

            imported = set(re.findall(r"^import\s+([\w.]+)", src, re.M))
            imported_simple = {i.rsplit(".", 1)[-1] for i in imported}

            for simple, fq in KNOWN.items():
                # 只认"作为类型出现在代码里"的情况：前面不是点（排除 a.b.Class）、后面不是字母
                if not re.search(r"(?<![\w.])" + simple + r"(?![\w])", src):
                    continue
                if fq == f"{pkg}.{simple}":
                    continue
                if simple in imported_simple:
                    continue
                # 全限定名直接用了，就不需要 import
                if fq in src:
                    continue
                problems.append(f"{path}: 用了 {simple} 但没有 import（应 import {fq}）")

    if problems:
        print("  ✗ 缺少 import：")
        for p in problems:
            print("     " + p)
        return 1

    print("  ✓ 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
