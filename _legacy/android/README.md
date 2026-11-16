# android — 无障碍回复助手骨架

## 状态说明（先看这段）

**未在真机编译验证。** 这里没有 Android SDK，也跑不了 Gradle。

- ✅ 逻辑与边界处理是完整的，可以直接粘进 Android Studio 工程
- ✅ `profiles.json` 是合法 JSON，Python 端已校验
- ❌ 缺 4 个依赖文件，需要你自己补：`DraftOverlay`（悬浮窗）、`Mode`（配置）、`ReplyBridge`（生成）、`GuardBridge`（风控）
- ❌ 所有选择器都必须在真机上 dump 后校准

后 4 个缺失文件里，`ReplyBridge` 和 `GuardBridge` 的逻辑在 `../gateway/` 里已经用 Python 写好并通过测试，移植过来即可，不要在 Kotlin 里重写一套规则，否则两端规则会漂移。

## 文件

| 文件 | 作用 |
|---|---|
| `ChatAccessibilityService.kt` | 服务主体：监听窗口变化 → 取消息 → 判定 → 填回复 |
| `SelectorProfile.kt` | 界面适配层：声明式选择器 + 节点查找 + 填入发送 |
| `profiles.json` | 每个 App 一份配置，新增 App 只改这里 |

## 为什么要把选择器抽成 JSON

接第 2 个 App 时你会想改代码；接第 5 个时，改动会互相踩。

把「怎么找输入框、怎么找发送键」抽出来后，新增一个 App 是纯配置工作：

```json
"com.example.app": {
  "input":  [{ "by": "className", "value": "android.widget.EditText", "pick": "last" }],
  "send":   [{ "by": "text", "value": "发送" }],
  "message":[{ "by": "className", "value": "android.widget.TextView" }]
}
```

选择器支持四种匹配方式，按顺序回退：

| by | 说明 | 稳定性 |
|---|---|---|
| `viewId` | `resource-id` 后缀匹配 | 最高，但版本升级可能变 |
| `className` | 控件类名 | 通用，但同类控件多，要配 `pick: last` |
| `text` | 可见文字 | 适合"发送"这类固定文案，换语言会失效 |
| `contentDesc` | 无障碍描述 | 适合纯图标按钮 |

## 校准一个 App 的流程

```bash
# 1. 停在目标 App 的聊天页
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml

# 2. 在 ui.xml 里搜 EditText，抄它的 resource-id
# 3. 搜发送按钮的 text 或 content-desc
# 4. 把值填进 profiles.json，重装
```

注意 dump 出来的 `resource-id` 形如 `com.tencent.mobileqq:id/input`，配置里填 `input` 即可（代码用的是 `endsWith`）。

## 三个必须知道的限制

**1. 目标 App 能发现你的无障碍服务开着。**

这不是猜测。`AccessibilityManager.getEnabledAccessibilityServiceList()` 会返回所有已启用的服务及其包名，
任何一个 App 都能调。所以「伪装包名」没有意义——包名本身就在列表里。

真正的隐藏只有 root + LSPosed 这类方案，但那会把账号风险抬到另一个量级。

**2. 默认不自动发送，这是刻意的。**

`Mode.autoSend = false` 时只弹悬浮窗给你看草稿，点不点由你决定。原因有两个：

- 合规上，最终动作由人完成，性质是"辅助"而不是"代替"
- 风控上，逐条自动发送必然产生规律的发送间隔和固定节奏，这是最容易命中的特征

要打开全自动，就等于接受账号随时可能没了。

**3. 节点引用会失效。**

`AccessibilityNodeInfo` 在界面刷新后立即失效，缓存它必崩。
代码里 `fillAndSend()` 在 `SET_TEXT` 之后重新取了 `rootInActiveWindow`，就是为了这个。

## 关于交友类 App

心遇、他趣这类平台的自动化风险显著高于普通 IM，原因写在 `docs/03` 里。
简单说：它们的收入模型依赖真人互动，机器人检测是核心能力而不是附加功能，
所以能稳定跑通的概率更低。建议只做"草稿 + 人工确认"，不做全自动。
