#!/usr/bin/env python3
"""
查「用了 Android 平台类却没 import」。

为什么需要单独一步：现有的 check_imports.py 只查**项目内**的类（com.autoreply.*），
平台类（ScrollView / AlertDialog / ClipboardManager…）漏 import 时那边看不见，
本机又不编译，只能等 CI —— 2026-11-28 就因为这个白跑了一轮（AutoReplyImeService:440）。

判据：
- 去掉注释和字符串字面量后，出现**不带前缀**的类名（前面不是 `.`，排除 `android.widget.ScrollView`）；
- 文件里没有对应的 `import ...ScrollView`；
- 文件里也没自己声明 `class/object/interface ScrollView`。

宁可漏报也不要误报：只查一份人工维护的常用清单，清单外的类不管。
"""
import os
import re
import sys

# 这个项目里真的会用到的平台类。加新 UI 时把类名补进来。
PLATFORM_CLASSES = [
    # widget
    "View", "ViewGroup", "LinearLayout", "FrameLayout", "RelativeLayout", "ScrollView",
    "HorizontalScrollView", "TextView", "EditText", "Button", "CheckBox", "RadioButton",
    "RadioGroup", "ImageView", "ProgressBar", "Toast", "PopupWindow", "Switch", "Spinner",
    # view 基础设施
    "Gravity", "MotionEvent", "KeyEvent", "LayoutInflater", "WindowManager",
    # app / os
    "Activity", "AlertDialog", "Dialog", "Service", "Bundle", "Handler", "Looper",
    "SystemClock", "Build", "Intent", "Uri", "PendingIntent",
    # content / pm / net
    "Context", "SharedPreferences", "ContentValues", "Cursor", "ClipboardManager", "ClipData",
    "PackageManager", "HttpURLConnection", "URL", "URI", "InetSocketAddress", "Proxy", "Socket",
    # graphics / text
    "Color", "Bitmap", "Canvas", "Paint", "Typeface", "TextUtils", "SpannableString",
    # 无障碍 / 输入法
    "AccessibilityService", "AccessibilityEvent", "AccessibilityNodeInfo", "AccessibilityNodeInfoCompat",
    "InputMethodService", "InputConnection", "EditorInfo", "InputMethodManager",
]

IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)\.(\w+)\s*$", re.M)
DECL_RE = re.compile(r"\b(?:class|object|interface|enum class)\s+(\w+)")

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LIT = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"', re.S)
CHAR_LIT = re.compile(r"'(?:\\.|[^'\\\n])'")


def strip_noise(src: str) -> str:
    """注释和字符串里的类名不算使用；换行保留，保证行号还能对上。"""
    out = BLOCK_COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), src)
    out = LINE_COMMENT.sub("", out)
    out = STRING_LIT.sub(lambda m: '"' + "\n" * m.group(0).count("\n") + '"', out)
    out = CHAR_LIT.sub("''", out)
    return out


def check_file(path: str):
    src = open(path, encoding="utf-8", errors="replace").read()
    body = strip_noise(src)

    imported = {m.group(2) for m in IMPORT_RE.finditer(src)}
    declared = set(DECL_RE.findall(body))

    problems = []
    for name in PLATFORM_CLASSES:
        if name in imported or name in declared:
            continue
        # 前面不能是 `.`（排除全限定写法），也不能是标识符字符（排除 MyScrollView）
        pat = re.compile(r"(?<![\w.])" + re.escape(name) + r"\b(?!\s*::)")
        m = pat.search(body)
        if m:
            line = body.count("\n", 0, m.start()) + 1
            problems.append((line, name))
    return problems


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"
    if not os.path.isdir(root):
        print(f"  （找不到 {root}，跳过）")
        return 0

    total = 0
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            p = os.path.join(dirpath, f)
            for line, name in check_file(p):
                rel = p.replace(os.sep, "/")
                print(f"  ✗ {rel}:{line} 用了 {name} 但没有 import")
                total += 1

    if total == 0:
        print("  ✓ 通过")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
