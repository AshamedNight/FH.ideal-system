#!/usr/bin/env python3
"""检查同一作用域里「重复声明」。

## 为什么需要它

改动一个已有属性时，很容易**新写一份、忘了删旧的**。Kotlin 的报错完全指不到
真正的位置：`var llmProxy` 重复声明，编译器报的却是

```
e: AppConfig.kt:19:65 Missing '}'
e: CaptureService.kt:176:41 Unresolved reference: LEVEL_FILL_ONLY
e: ...（所有 AppConfig 的属性全变 Unresolved）
```

它们都是"文件解析失败"的连带后果，真正的原因要往文件中间翻。
CI 因此白跑一轮，而 precheck 原有 8 步一条都查不到：括号是平衡的、
import 是全的、字符串也是闭合的 —— 只是同一个名字声明了两次。

## 查什么、不查什么

- 只查**类 / 对象体里的成员声明**（`val` / `var` / `fun`），这是漏删最常发生的位置
- `fun` 只在参数表完全相同才报（不同参数是真重载）
- 函数体内的局部变量、`when` 分支里的同名 val **一律不管** ——
  它们本来就允许在不同分支/不同块里重名，查了全是误报

保守优先：宁可漏报，也不要让人开始忽略这个检查。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DECL = re.compile(
    r'^\s+(?:(?:private|public|internal|protected|override|lateinit|const|open|suspend|inline|operator)\s+)*'
    r'(?P<kind>val|var|fun)\s+(?P<name>[A-Za-z_]\w*)'
)
TYPE_DECL = re.compile(r'\b(?:class|object|interface)\s+(?P<name>[A-Za-z_]\w*)')
COMPANION = re.compile(r'^\s*companion\s+object')


def strip_noise(line: str) -> str:
    """去掉行尾注释（字符串里的 // 这个项目没有，简单处理即可）。"""
    out = []
    in_str = False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == '"' and (i == 0 or line[i - 1] != '\\'):
            in_str = not in_str
            out.append(ch)
        elif not in_str and ch == '/' and i + 1 < len(line) and line[i + 1] == '/':
            break
        else:
            out.append(ch)
        i += 1
    return ''.join(out)


class Frame:
    __slots__ = ("kind", "depth", "seq")

    def __init__(self, kind: str, depth: int, seq: int):
        self.kind = kind
        self.depth = depth
        self.seq = seq


def check(path: str, src: str):
    problems = []
    frames = [Frame("file", 0, 0)]
    depth = 0
    seq = 0
    in_block_comment = False
    seen = {}  # (scope seq, kind, name, params) -> 行号
    pending = None  # 本行 { 打开时该帧的类型
    # 主构造器作用域：`data class Fill(` 这种没有 { 的声明，
    # 它的 val text / val risk 属于 Fill，不属于外层 sealed class。
    # 不单独开作用域的话，Fill 和 Hold 的 `val text` 会被当成同一个作用域里的重复声明。
    ctor = None  # [scope_seq, 括号余额]

    for no, raw in enumerate(src.splitlines(), 1):
        line = raw
        if in_block_comment:
            if '*/' in line:
                line = line.split('*/', 1)[1]
                in_block_comment = False
            else:
                depth += line.count('{') - line.count('}')
                continue
        while '/*' in line:
            start = line.index('/*')
            if '*/' in line[start:]:
                line = line[:start] + line[line.index('*/', start) + 2:]
            else:
                line = line[:start]
                in_block_comment = True
                break

        s = strip_noise(line)
        opens = s.count('{')
        closes = s.count('}')

        # 这一行声明了什么容器？它的 { 打开的帧就按这个类型记
        type_hit = TYPE_DECL.search(s)
        if COMPANION.search(s):
            pending = "object"
        elif type_hit:
            pending = "class"
            head = s[type_hit.end():]
            # 主构造器（名字后紧跟括号、且本行没有 {）：单独开一个作用域
            if '(' in head and '{' not in head.split('(', 1)[1]:
                seq += 1
                ctor = [seq, 0]
        elif re.search(r'\bfun\s+[A-Za-z_]\w*\s*\(', s):
            pending = "fun"

        # 构造器里的属性归它自己
        if ctor is not None:
            decl = DECL.match(s)
            if decl and decl.group('kind') in ('val', 'var'):
                key = (ctor[0], 'var', decl.group('name'), "")
                if key in seen:
                    problems.append(
                        f"{os.path.relpath(path, ROOT)}:{no} 重复声明 {decl.group('kind')} "
                        f"{decl.group('name')}（同一作用域第 {seen[key]} 行已声明）"
                    )
                else:
                    seen[key] = no
            ctor[1] += s.count('(') - s.count(')')
            if ctor[1] <= 0:
                ctor = None

        top = frames[-1]
        decl = DECL.match(s)
        if (ctor is None and decl and top.kind in ("class", "object")
                and depth == top.depth + 1):
            kind = decl.group('kind')
            name = decl.group('name')
            params = ""
            if kind == 'fun':
                tail = s[decl.end():]
                if '(' in tail:
                    raw_params = tail[tail.index('('):]
                    # 参数表跨行时无法比较（两个重载会被误判成同一个）——
                    # 这种情况直接按行号区分，宁可不查
                    if raw_params.count('(') == 0 or raw_params.count(')') == 0:
                        params = f"@{no}"
                    else:
                        params = re.sub(r'\s+', '', raw_params)
            key = (top.seq, kind, name, params if kind == 'fun' else "")
            if key in seen:
                problems.append(
                    f"{os.path.relpath(path, ROOT)}:{no} 重复声明 {kind} {name}"
                    f"（同一作用域第 {seen[key]} 行已声明）"
                )
            else:
                seen[key] = no

        for _ in range(opens):
            seq += 1
            frames.append(Frame(pending or "block", depth, seq))
            depth += 1
            pending = None
        for _ in range(closes):
            depth -= 1
            if len(frames) > 1 and frames[-1].depth >= depth:
                frames.pop()

    return problems


def main() -> int:
    args = sys.argv[1:]
    files = args if args else sorted(
        glob.glob(f"{ROOT}/app/src/main/java/**/*.kt", recursive=True)
    )
    problems = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            problems += check(f, fh.read())

    if problems:
        print("  同一作用域里重复声明：")
        print("\n".join(problems))
        return 1
    print("  ✓ 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
