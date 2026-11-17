# AutoReply

Android 半自动回复：读消息 → 生成草稿 → 填进输入框 → **发送由人点**。

独立项目。与同目录下的求职自动化项目没有代码、数据或账号关联。

## 状态

**v0.1 可用**。完整闭环已跑通：

```
界面出现新消息 → 五层判定 → 生成 → 填入草稿 → 你点发送 → 系统确认已发送 → 等下一条
```

已有：读屏与节点抓取、多平台选择器、人设与样例、话术分级、节奏护栏、
消息去重、发送确认、输入法写入通道（能写「输入框对无障碍不可见」的 App）。

用户可见的能力边界见 [`RELEASE_NOTES.md`](RELEASE_NOTES.md)。

## 目录

```
app/src/main/java/com/autoreply/app/
├── core/
│   ├── AppConfig.kt              全部可调参数（SharedPreferences）
│   ├── guard/
│   │   ├── PacerGuard.kt         限流、冷却、熔断、抖动
│   │   ├── Energy.kt             精力状态机（拟人：会累、会拖）
│   │   ├── DraftRisk.kt          话术分级（价格/承诺类不自动填）
│   │   ├── ReplyFlow.kt          五层判定链，每层写一行 trace
│   │   ├── ReplyDedup.kt         同一条消息只处理一次（指纹带对方昵称）
│   │   └── SentTracker.kt        确认"真的发出去了"（看消息列表有没有我发的那条）
│   ├── llm/                      模型网关（零依赖 HTTP + 假模型）
│   └── persona/                  人设加载、必要性判定
├── platform/
│   ├── Selector.kt               平台配置结构（选择器）
│   ├── Profiles.kt               profiles.json 解析
│   ├── NodeQuery.kt              读消息、定位输入框、识别聊天页
│   ├── AccessibilityReplySink.kt 无障碍填字（ACTION_SET_TEXT）
│   ├── TextWriter.kt             两条写入通道：无障碍 → 输入法
│   ├── ReplyRunner.kt            一次完整的「读 → 判定 → 填」
│   └── ReplySink.kt              草稿出口 —— 没有 send()
├── ime/
│   └── AutoReplyImeService.kt    输入法写入通道（commitText）
├── capture/
│   ├── CaptureService.kt         读屏 + 界面驱动自动触发
│   └── NodeDumper.kt             节点树拍平（调选择器用）
├── notify/
│   └── MessageNotificationListener.kt   通知触发（部分平台不发通知）
└── ui/
    ├── MainActivity.kt
    └── SettingsActivity.kt
```

## 构建

本机不装 JDK/Android SDK，一律走 GitHub Actions：

```
git push        → 出 release APK，在 Actions 页面的 artifacts 里下载
git tag v0.1.0  → 额外创建 Release 并附上 APK 和 RELEASE_NOTES.md
```

本地有环境时：`gradle assembleRelease`（需要 Gradle 8.2 + JDK 17 + SDK 34）。

出 release 而不是 debug：debug 包带 `android:debuggable`，别人装上等于给了
一个可挂调试器的进程。混淆是关的（`isMinifyEnabled = false`），崩溃栈依然可读。

### 签名

仓库 Secrets 里配 `KEYSTORE_B64`（.p12 的 base64）和 `KEYSTORE_PASS`。
未配置时 CI 会现场生成临时密钥——能出包，但每次签名不同，**无法覆盖安装**。

每次构建会把签名指纹写进 Actions 的 Step Summary。**验证覆盖安装时对比它**：
一致说明能升级，不一致一眼看出是哪次开始变的（签名不一致时手机只会说
"已安装"然后失败，报错信息完全不提签名）。

## 设计取舍

见 [`决策-架构与风控.md`](决策-架构与风控.md) 和 [`架构规划.md`](架构规划.md)。核心两条：

- 风险大头是「自动切会话」而不是「自动填字」，所以默认档位每条只产生 1 次自动点击
- **发送按钮永远不碰**：`ReplySink` 接口里就没有 `send()`

## 免责

自动化操作可能违反目标平台的服务条款，账号可能被限制或封禁。自行判断是否使用。
