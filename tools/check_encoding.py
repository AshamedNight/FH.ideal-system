#!/usr/bin/env python3
"""
三项编码/语法卫生检查，CU 要跑几分钟才发现的问题，本地零点几秒。

## 1. 乱码字符（U+FFFD）
批量替换文本时最容易留下这个：某个中文字被截断成半个码点，
写回去就成了 `光���`。编译能过，运行时界面上就是个问号方块，
而且只在那一屏某一行出现，很容易一直没人发现。

## 2. 字符串没闭合就换行
```kotlin
"一行字
" +
```
Kotlin 报 `Expecting '"'`，而且一报就是四五条，位置还都对不上真实原因。
成因几乎都是**脚本里写 `\\n` 忘了转义** —— Python 三引号里的 `\\n` 是真换行，
写进 .kt 就把字符串劈成了两半。

## 3. 块注释里出现了 `/*`（Kotlin 的块注释可嵌套）
文档里写 "assets/*.json 是否合法" 这种句子，那个 `/*` 会**再开一层注释**，
后面整个文件都被吞掉，编译器报：
```
AppConfig.kt:19:65 Missing '}'
AppConfig.kt:484:1 Unclosed comment
+ 所有调用点满屏 Unresolved reference
```
真实原因在第 26 行的一句说明文字里，离报错位置几百行。
（这个坑真踩过：先怀疑括号、再怀疑重复声明，都不是。）

只做这三件事，不做 Kotlin 语法分析（那件事用 Python 做不可靠，会淹没在误报里）。
"""
import os
import sys

ROOTS = ["app/src/main"]
SUFFIXES = (".kt", ".xml", ".json")


def scan_comments(raw):
    """按 Kotlin 规则扫块注释：`/*` 可嵌套；字符串里的不算。"""
    problems = []
    depth = 0
    in_str = in_raw = in_line = False
    i = 0
    line = 1
    while i < len(raw):
        ch = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if ch == "\n":
            line += 1
            in_line = False
            i += 1
            continue
        if in_line:
            i += 1
            continue
        if in_raw:
            if raw[i:i + 3] == '"""':
                in_raw = False
                i += 3
            else:
                i += 1
            continue
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if raw[i:i + 3] == '"""':
            in_raw = True
            i += 3
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            depth += 1
            if depth > 1:
                snippet = raw[max(0, i - 24):i + 24].replace("\n", " ").strip()
                problems.append(f"{line} 注释里又出现 /*（嵌套）→ …{snippet}…")
            i += 2
            continue
        if ch == "*" and nxt == "/":
            if depth == 0:
                problems.append(f"{line} 多余的 */（前面没有 /*）")
            else:
                depth -= 1
            i += 2
            continue
        i += 1
    if depth > 0:
        problems.append(f"文件结束还有 {depth} 层 /* 没关闭")
    return problems


def main() -> int:
    bad_chars = []
    bad_strings = []
    bad_comments = []

    for root in ROOTS:
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(SUFFIXES):
                    continue
                path = os.path.join(dirpath, name)
                raw = open(path, encoding="utf-8", errors="replace").read()

                for i, ln in enumerate(raw.split("\n"), 1):
                    if "\ufffd" in ln:
                        bad_chars.append(f"{path}:{i} 含乱码字符 → {ln.strip()[:70]}")

                if not name.endswith(".kt"):
                    continue
                for msg in scan_comments(raw):
                    bad_comments.append(f"{path}:{msg}")
                # 引号数奇数的行 = 这行字符串没闭合（跳过注释和 KDoc 起始行）
                for i, ln in enumerate(raw.split("\n"), 1):
                    st = ln.strip()
                    if st.startswith("//") or st.startswith("*") or st.startswith("/*"):
                        continue
                    if '"""' in ln:
                        continue
                    if ln.count('"') % 2 == 1:
                        bad_strings.append(f"{path}:{i} 这行引号没闭合 → {st[:70]}")

    ok = True
    if bad_chars:
        ok = False
        print("  ✗ 有乱码字符：")
        for b in bad_chars:
            print("     " + b)
    if bad_strings:
        ok = False
        print("  ✗ 有未闭合的字符串：")
        for b in bad_strings:
            print("     " + b)
    if bad_comments:
        ok = False
        print("  ✗ 块注释有问题（Kotlin 的 /* */ 可以嵌套，多半是文档里写了 /*）：")
        for b in bad_comments:
            print("     " + b)
    if ok:
        print("  ✓ 通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
