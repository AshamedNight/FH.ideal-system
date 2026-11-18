#!/usr/bin/env bash
# 提交前自检：只查「必现失败」的五类问题。
#
# 本机没有 JDK / Android SDK，编译验证只能靠 CI（一次 1~6 分钟）。
# 但有几类错误完全不需要编译器就能查出来，而且每一类都已经让 CI 白跑过一轮：
#
#   1. 用了 R. 却没 import com.autoreply.app.R      （跨包引用不会自动可见）
#   2. layout 引用了不存在的 @string / @color
#   3. findViewById 的 id 没在 layout 或 ids.xml 里声明
#   4. Manifest 声明的类没有对应文件
#   5. assets 里的 JSON 不合法（运行时才炸，最隐蔽）
#
# 用法：bash tools/precheck.sh
# 退出码非 0 就别 push。

set -u
cd "$(dirname "$0")/.." || exit 1

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf '  ✗ %s\n' "$*"; fail=1; }

say "== 1. R 引用需要 import =="
r_fail=0
while IFS= read -r f; do
    uses=$(grep -c 'R\.\(id\|string\|layout\|drawable\|mipmap\|color\|style\)\.' "$f" || true)
    has=$(grep -c '^import com.autoreply.app.R$' "$f" || true)
    if [ "$uses" -gt 0 ] && [ "$has" -eq 0 ]; then
        bad "$f 用了 R. 但没有 import com.autoreply.app.R"
        r_fail=1
    fi
done < <(find app/src/main/java -name '*.kt')
[ "$r_fail" -eq 0 ] && say "  ✓ 通过"

say "== 2. layout 引用的 string / color =="
layout_fail=0
while IFS= read -r s; do
    grep -q "name=\"$s\"" app/src/main/res/values/strings.xml || { bad "缺少 string: $s"; layout_fail=1; }
done < <(grep -oh '@string/[a-z_0-9]*' app/src/main/res/layout/*.xml 2>/dev/null | sed 's|@string/||' | sort -u)
while IFS= read -r c; do
    grep -q "name=\"$c\"" app/src/main/res/values/colors.xml || { bad "缺少 color: $c"; layout_fail=1; }
done < <(grep -oh '@color/[a-z_0-9]*' app/src/main/res/layout/*.xml 2>/dev/null | sed 's|@color/||' | sort -u)
[ "$layout_fail" -eq 0 ] && say "  ✓ 通过"

say "== 3. findViewById 的 id 是否声明 =="
id_fail=0
while IFS= read -r i; do
    if grep -q "@+id/$i" app/src/main/res/layout/*.xml 2>/dev/null; then continue; fi
    if grep -q "name=\"$i\"" app/src/main/res/values/ids.xml 2>/dev/null; then continue; fi
    bad "id 未声明: $i（既不在 layout 也不在 ids.xml）"
    id_fail=1
done < <(grep -oh 'R\.id\.[a-zA-Z_0-9]*' app/src/main/java/com/autoreply/app/ui/*.kt 2>/dev/null | sed 's/R\.id\.//' | sort -u)
[ "$id_fail" -eq 0 ] && say "  ✓ 通过"

say "== 4. Manifest 声明的类 =="
man_fail=0
while IFS= read -r c; do
    p="app/src/main/java/com/autoreply/app/$(printf '%s' "$c" | tr '.' '/').kt"
    [ -f "$p" ] || { bad "Manifest 声明了 $c，但 $p 不存在"; man_fail=1; }
done < <(grep -o 'android:name="\.[a-zA-Z.]*"' app/src/main/AndroidManifest.xml | sed 's/android:name="\.//;s/"//')
[ "$man_fail" -eq 0 ] && say "  ✓ 通过"

say "== 5. assets 里的 JSON =="
json_fail=0
for f in app/src/main/assets/*.json; do
    [ -f "$f" ] || continue
    if command -v python >/dev/null 2>&1; then
        python -c "import json,sys; json.load(open(sys.argv[1],encoding='utf-8'))" "$f" 2>/dev/null \
            || { bad "$f 不是合法 JSON"; json_fail=1; }
    fi
done
[ "$json_fail" -eq 0 ] && say "  ✓ 通过"

say "== 6. 项目内的类有没有漏 import =="
# 这一项是补上"类型错误只能靠 CI"的缺口里最容易命中的那类：
# 用了 PlatformProfile 却没 import，本地零成本可查，CI 上要跑几分钟才发现。
if command -v python >/dev/null 2>&1; then
    python tools/check_imports.py || fail=1
else
    say "  （没有 python，跳过）"
fi

say "== 7. 乱码字符 / 字符串闭合 =="
# 这两项是 CI 报错但又极难看懂的两种：乱码在界面上是个方块，
# 字符串没闭合则一次报四五条 "Expecting '"'" 且位置对不上真实原因。
if command -v python >/dev/null 2>&1; then
    python tools/check_encoding.py || fail=1
else
    say "  （没有 python，跳过）"
fi

echo
if [ $fail -ne 0 ]; then
    say "自检未通过 —— 修掉再 push，否则 CI 会白跑一轮。"
    exit 1
fi
say "自检全部通过。注意：这只覆盖「必现失败」和「漏 import」，其余类型错误仍只能靠 CI。"
