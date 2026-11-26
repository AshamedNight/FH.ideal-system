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

## 类名不再手抄（2026-11-26 改）

原来这里有一张手写的 KNOWN 表，规则是"新增类时记得加进来"。
实际结果是：`NotifyContext` 一直没进表 —— `MainActivity` 里用了它、没 import，
本地 9 步全过，CI 报 `Unresolved reference: NotifyContext`，白跑一轮。
所以改成**扫源码自动发现**顶层声明（第 0 列开始的 class/object/interface/typealias），
新增类不用再维护任何清单。
"""
import os
import re
import sys

ROOT = "app/src/main/java"

# 顶层声明：必须从第 0 列开始。这条约束就是"顶层 vs 嵌套"的判据 ——
# 项目里的嵌套类都缩进 4 格，所以不会误收录（`FillResult.Ok` 这类）。
DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|value|inline|final)\s+)*"
    r"(?:class|object|interface|typealias)\s+([A-Z]\w*)",
    re.M,
)


def discover() -> dict:
    """
    简单名 → 全限定名。

    同名跨包的直接丢掉（置 None 再过滤）：那种情况没法只凭简单名判断，
    报出来是误报，会让人开始怀疑这个检查，比漏报更糟。
    """
    found = {}
    for dirpath, _, files in os.walk(ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            raw = open(os.path.join(dirpath, name), encoding="utf-8").read()
            pkg_m = re.search(r"^package\s+([\w.]+)", raw, re.M)
            pkg = pkg_m.group(1) if pkg_m else ""
            for simple in DECL.findall(raw):
                fq = f"{pkg}.{simple}" if pkg else simple
                if simple in found and found[simple] != fq:
                    found[simple] = None
                else:
                    found[simple] = fq
    return {k: v for k, v in found.items() if v}


def strip_noise(src: str) -> str:
    """
    去掉注释和字符串字面量，避免把注释里的类名当成真实引用。

    **必须按字符扫描，不能分几条正则依次替换**（2026-11-26 踩到的坑）：
    原来先删块注释、再删 `//` 行注释、再删字符串。而 `MainActivity` 里有一行
    `"接口地址  https://api.deepseek.com/v1"` —— 行注释那条正则从 `//` 一路删到行尾，
    把字符串的收尾引号也删了，于是后面成了奇数个引号；再跑"删字符串"的正则，
    它把第 174 行到几百行之后的引号配成一对，中间整段代码被吞掉。
    结果：`NotifyContext` 的使用位置在 src 里根本不存在，检查器报"通过"，
    CI 照样 `Unresolved reference`。

    扫描器同时处理 Kotlin 的**嵌套块注释**，并且遇到换行还没闭合的字符串就停下，
    宁可少删也不吞代码 —— 少删最多误报，吞代码就是漏报，而漏报是致命的。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                out.append('""')
                continue
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                if src[i] == "\n":
                    break
                i += 1
            out.append('""')
            continue
        if c == "'":
            # 字符字面量：'a'、'\n'、'\''。跳不过去就当成普通字符，别吞代码。
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and src[j] == "'":
                out.append("''")
                i = j + 1
                continue
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == "/":
                j = src.find("\n", i)
                i = n if j < 0 else j
                continue
            if nxt == "*":
                depth, i = 1, i + 2
                while i < n and depth > 0:
                    if src.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif src.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        i += 1
                continue
        out.append(c)
        i += 1
    return "".join(out)


def main() -> int:
    known = discover()
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

            for simple, fq in known.items():
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

    print(f"  ✓ 通过（检查了 {len(known)} 个项目内类）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
