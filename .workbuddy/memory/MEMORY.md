# 项目长期约定（AutoReply）

## 构建与验证

- **本机没有 JDK / Android SDK**，编译不了。出包只能走 GitHub Actions：
  `git push` → Actions artifacts。`git tag vX.Y.Z` → 额外创建 Release。
- **push 前必须跑 `bash tools/precheck.sh`**（现在是 9 步）：R 引用、layout 字符串/颜色、
  findViewById id、Manifest 类、assets JSON、漏 import、乱码/字符串闭合、addView 构造函数、重复声明。
  跑一次几秒钟，CI 一轮几分钟 —— 这个取舍很划算。
- 需要当场验算纯逻辑（断句、轮换、槽位填充）时，用 Python 复刻同一份算法，
  并且**直接从 .kt 源码里 regex 读常量**，不要手抄：
  `tools/verify_splitter.py`、`tools/verify_greeting_hooks.py` 是范例。
- push 本身要认证，Agent 跑会卡在 credential 弹窗 → 由用户在自己终端执行。
- **`git add -A` 不等于提交**（2026-11-26 用户连续踩两次）：`add` 只进暂存区，
  `push` 推的是 commit，所以会报 `Everything up-to-date`。
  正确三步：`git add -A` → `git commit -m "..."` → `git push`。
  PowerShell 5.1 不支持 `&&`，要么逐行敲，要么用 `;`。
  Agent 可以代跑 add + commit（本地不需要认证），只把 push 留给用户。

## 技术约束

- **零第三方依赖**：不引 androidx / Material / Compose / OkHttp / Gson。
  HTTP、JSON、Trie 都用系统 API + 手写。加依赖前先确有必要。
- UI 三页（MainActivity / SettingsActivity / PersonaActivity）中后两页是**纯代码搭的 UI**，
  没有 XML layout。视觉 token 统一走 `res/values/styles.xml` 和 `res/drawable/*.xml`。
- style 名字**不能带点**（`Text.Display` 会被 AAPT2 当成隐式父样式去解析 `Text`）。
- 自定义 Button 必须 `stateListAnimator=@null` + `minHeight/minWidth=0` + inset 全 0，
  否则系统默认尺寸会把高度撑回去。

## 已知教训

- 改函数名要全局搜：`label()` → `mono()` 漏了顶栏一处，CI 报 `Unresolved reference: label`。
  已由 `tools/check_local_calls.py`（precheck 第 8 步）兜住这类。
- 不要把标点当"拼接符"（`joinToString("。")`）—— 见 2026-11-20 的笔记。
