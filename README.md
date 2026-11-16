# AutoReply

Android 半自动回复：读消息 → 生成草稿 → 填进输入框 → **发送由人点**。

独立项目。与同目录下的求职自动化项目没有代码、数据或账号关联。

## 状态

v0.1 骨架。已完成：读屏服务、节点抓取、通知监听、节奏护栏、设置页。
未完成：平台适配器（等目标平台确定）、词表、人设组装、模型网关。

## 目录

```
app/src/main/java/com/autoreply/app/
├── core/
│   ├── AppConfig.kt          全部可调参数（SharedPreferences）
│   ├── guard/PacerGuard.kt   限流、冷却、熔断、抖动
│   ├── lexicon/              词表（待实现）
│   ├── persona/              人设（待实现）
│   └── llm/                  模型网关（待实现）
├── platform/
│   └── ReplySink.kt          草稿出口 —— 没有 send()
├── capture/
│   ├── CaptureService.kt     读屏服务
│   └── NodeDumper.kt         节点树拍平（调选择器用）
├── notify/
│   └── MessageNotificationListener.kt   L1 的触发器
└── ui/
    ├── MainActivity.kt
    └── SettingsActivity.kt
```

## 构建

本机不装 JDK/Android SDK，一律走 GitHub Actions：

```
git push        → 出 debug APK，在 Actions 页面的 artifacts 里下载
git tag v0.1.0  → 额外创建 Release 并附上 APK
```

本地有环境时也可以：`gradle assembleDebug`（需要 Gradle 8.2 + JDK 17 + SDK 34）。

### 签名

仓库 Secrets 里配 `KEYSTORE_B64`（.p12 的 base64）和 `KEYSTORE_PASS`。
未配置时 CI 会现场生成临时密钥——能出包，但每次签名不同，**无法覆盖安装**。

## 设计取舍

见 [`决策-架构与风控.md`](决策-架构与风控.md)。核心一句：
风险的大头是「自动切会话」而不是「自动填字」，所以默认档位每条只产生 1 次自动点击。

## 免责

自动化操作可能违反目标平台的服务条款，账号可能被限制或封禁。自行判断是否使用。
