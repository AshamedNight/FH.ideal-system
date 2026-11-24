# 本轮交付总览（2026-11-23）：8021 穿透中转 + 隐藏假 key + 爱聊/他趣剪贴板模式

## 做了什么

### 1. 中转对齐已有穿透形态（0.0.0.0:8021）
- `relay/relay.js` 默认端口 8787 → **8021**；成员手机**不装任何东西、不连 VPN**，
  填的是穿透后的公网域名（WiFi / 流量都可用的关键在服务端隧道，与设备网络无关）。
- 澄清：`0.0.0.0` 是服务端监听地址，不是别人手机里填的地址。

### 2. 隐藏式假 key（pinned 模式）+ 分发包零 Key
- relay 新增 `AUTH_MODE=pinned`：令牌 = `<deviceId>.<SHARED_KEY>`，常量时间比较。
- App 新增 `assets/distribution.json` 分发配置（enabled / relayUrl / sharedKey
  **三项同时填齐才生效**，防漏填死局）；`AppConfig.distributionEnabled` + 自动生成
  16 位 `deviceId`（隐藏持久化）。
- 分发模式下设置页模型段变**只读说明，没有可填 key 的输入框**；save() 不再污染 sp。
- 管理：`ADMIN_TOKEN` 口令调额（穿透转发源地址是 127.0.0.1，原"只认本机"会被打穿，
  公网部署必须设口令）；换 SHARED_KEY = 一键吊销全部旧包。

### 3. 新增平台：爱聊（com.mosheng）—— 完整支持
两次真机 dump（226 节点，可编辑 1）确认它全可读可写，**不需要剪贴板降级**：

| 用途 | 真机证据 |
|---|---|
| 消息区 | `ListView #list`；对方 `#left_iv_text`（旧版 `#left_tv`），我方 `#right_iv_text`（旧版 `#right_tv`） |
| 输入框 | `EditText #editText "请输入消息..."`，`edit` 属性 → SET_TEXT 可写 |
| 昵称 | `#textTitle "心泽"` |
| 下一个未读 | `#unread_msg_view`（内含 `#tv_tips "下一个未读"`）→ 连点可用 |

为此加了两条通用能力：
- `Selector` 新增 `viewIdIn`（逗号分隔多 id）—— `readMessages` 命中首个选择器就停，
  左右 id 不同时拆三条只能收一侧，拿不到"我发的"就数不准发送确认
- `readMessages` 方向判定改为 **id 前缀优先于坐标**：实测我方气泡 `#right_iv_text`
  左边缘是 229，小于三分点 360 —— 纯坐标会把"我发的"判成"对方发的"
- 抓取按钮加兜底：未登记包名的 App 也能抓一份（面积最大的别的 App 窗口），
  接新平台不必先改设置
- 他趣保持 `clipboardPreferred`（整树被摘掉，确实只能靠通知 / 剪贴板）
- Manifest queries 已加 `com.mosheng`

### 4. 剪贴板交付模式（抓不到输入框/聊天区的平台）
- 读上下文三级兜底：节点树 → 通知 → **用户长按复制的对方原话**
  （过滤：排除自己刚生成的草稿、dump 文本、过长过短）。
- 写：生成结果**整段进剪贴板、不断句**（断句队列在剪贴板模式下只会卡住），
  提示"回聊天页长按粘贴，自己点发送"。
- 开关：设置页「剪贴板交付」+ 输入法面板第二页「剪贴板」chip。

### 5. 收尾补的最后一条链路（本轮新改）
MainActivity 首页「生成并填入」是独立于 ReplyRunner 的生成路径，之前没接剪贴板模式：
- 剪贴板读取过滤抽成 `platform/ClipboardPeerText.kt`，两条路径共用一份规则；
- MainActivity 读取端加剪贴板兜底（读不到给明确行动指令）；
- Fill 分支加剪贴板早返回（与 ReplyRunner.deliver 同构）；
- Hold 分支补 `lastClipboardDraft` 记录（防自反馈）。

## 验证
- `tools/precheck.sh` **9 步**全过（新增第 9 步：同一作用域重复声明）。
- `relay/selftest.js` **39 项全过**（新增 pinned、穿透无口令被拒、调额需口令等）。
- 爱聊：`profiles.json` 引用的每个 viewId 都在 dump 里存在（脚本核对，缺 0 个）；
  用真机 bounds 复刻方向判定（「你好」→ 对方、我方气泡 → 我方、最后一条是我方 → 不生成）。
- 本机无 JDK，编译仍走 GitHub Actions；push 由用户在交互终端执行。

### 第 9 步的由来

首轮 CI 挂在 `AppConfig.kt:19:65 Missing '}'` + 满屏 `Unresolved reference`。
真因是加分发模式时新写了一个 `llmProxy`、忘了删旧的 —— **同一作用域声明两次**，
整个文件解析失败，所有调用点跟着报错。前 8 步全都查不出来（括号平衡、import 齐全、
字符串闭合）。`tools/check_dupes.py` 只查类/对象体成员，用坏提交验证过能精确报出
`354 重复声明 var llmProxy（第 320 行已声明）`，全量跑零误报。

## 文档
- `relay/README.md` 重写：8021 / pinned / ADMIN_TOKEN / 分发步骤 / 吊销。
- `README.md`：平台表爱聊改为 ✅/✅、剪贴板交付与中转分发章节、目录补 ClipboardPeerText。
- `RELEASE_NOTES.md`：新增 v0.1.2（待发）记录。

## 分发前只剩这些（确认过的清单）

| 端 | 位置 | 填什么 |
|---|---|---|
| 服务器 | Termux `start-termux.sh` | `UPSTREAM_KEY`（真 key）、`SHARED_KEY`、`ADMIN_TOKEN` |
| APK | `app/src/main/assets/distribution.json` | `enabled:true`、`relayUrl`（穿透域名 + `/v1`）、`sharedKey`（= SHARED_KEY）、可选 `model`（钉死模型名） |
| 环境 | 穿透工具 | 指向 `127.0.0.1:8021` |

已核过没有隐藏坑：manifest 有 `usesCleartextTraffic="true"`（免费 http 域名可用）；
relay 只认 `/v1/chat/completions`，`LlmClient` 拼的正是这个；三项 key 填对即可启用，
不用再改任何 Kotlin。换 `SHARED_KEY` 需重打包（key 编译进 assets）。

## 待办 / 下一步
- 爱聊配置已用「双方都有消息」的 dump 验证过（对方「你好」/我方「你觉得你性格内向还是外向」
  分别判对，最后一条是我方 → 不生成，符合预期）。
- 首次分发自测三步：`curl http://127.0.0.1:8021/healthz` → 穿透域名 `/healthz` →
  装一台生成一条，中转日志/`/admin/devices` 里出现 deviceId。
- 可选后续：客户端显示剩余额度（`/v1/quota`）、额度用尽优雅降级到演练模式。
