#!/usr/bin/env python3
"""检查「调用了本文件里根本没定义的构造函数」。

## 为什么需要它

首页 / 设置页 / 话术管理页都是用代码搭的 UI，长得像这样：

```kotlin
root.addView(mono("SETTINGS · 设置"))
root.addView(section("风险档位"))
root.addView(note("..."))
```

也就是说 **`addView(...)` 里的第一个标识符必然是本文件里的一个私有构造函数**。
一旦改了函数名而漏改调用处（`label()` 改成 `mono()` 但顶栏那行没跟着改），
Kotlin 才会报 `Unresolved reference` —— 而那已经是 CI 上跑几分钟之后的事了。

`tools/check_imports.py` 查的是"用了别的包的类但没 import"，管不到这种情况：
这里的函数就在同一个文件里，只是名字对不上。

## 只查这一种

不做完整名字解析 —— 只查 `addView( xxx(` / `setContentView( xxx(` 里的 xxx。
这类调用 100% 是本地构造函数，误报几乎为零，而且正好覆盖上面这个坑。
"""
import glob
import os
import re
import sys

ROOT = "app/src/main/java"

# 这些是作用域函数，不是本地构造函数，addView(apply{...}) 之类要放行
SCOPE_FUNCS = {"apply", "run", "let", "also", "with", "takeIf", "runCatching"}

CALL_SITES = [
    re.compile(r"addView\(\s*([a-z][A-Za-z0-9_]*)\("),
    re.compile(r"setContentView\(\s*([a-z][A-Za-z0-9_]*)\("),
]


def check(path: str, src: str) -> list:
    defined = set(re.findall(r"\bfun\s+(\w+)\s*\(", src))
    problems = []
    for pattern in CALL_SITES:
        for m in pattern.finditer(src):
            name = m.group(1)
            if name in SCOPE_FUNCS or name in defined:
                continue
            line = src[: m.start()].count("\n") + 1
            problems.append(f"  {os.path.basename(path)}:{line} 调用了未定义的 {name}()")
    return problems


def main() -> int:
    files = sorted(glob.glob(f"{ROOT}/**/*.kt", recursive=True))
    problems = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            problems += check(f, fh.read())

    if problems:
        print("  调用了未定义的构造函数：")
        print("\n".join(problems))
        return 1
    print("  ✓ 通过")
    return 0


if __name__ == "__main__":
    # 也支持单个文件，方便在修完某处后只验一个
    if len(sys.argv) > 1:
        bad = []
        for f in sys.argv[1:]:
            with open(f, encoding="utf-8") as fh:
                bad += check(f, fh.read())
        print("\n".join(bad) if bad else "  ✓ 通过")
        sys.exit(1 if bad else 0)
    sys.exit(main())
