#!/usr/bin/env python3
"""
两项编码/语法卫生检查，CU 要跑几分钟才发现的问题，本地零点几秒。

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

只做这两件事，不做 Kotlin 语法分析（那件事用 Python 做不可靠，会淹没在误报里）。
"""
import os
import sys

ROOTS = ["app/src/main"]
SUFFIXES = (".kt", ".xml", ".json")


def main() -> int:
    bad_chars = []
    bad_strings = []

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
    if ok:
        print("  ✓ 通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
