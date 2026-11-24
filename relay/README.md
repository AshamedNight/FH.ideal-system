# relay —— 分发用的模型中转

一个文件、零依赖（只用 Node 内置模块）、跑在哪都行。

## 它解决什么

分发出去的 APK 里**没有真实 Key**，只有一枚"设备令牌"。真正调模型接口的是中转，
Key 只在中转所在的机器上。所以：

| 你的要求 | 谁保证 | 成员设备能绕过吗 |
|---|---|---|
| 超额当天停用 | 中转的账本 | 不能（不在同一台机器） |
| Key 隐藏且不可复制 | 中转持有 Key | 不能（设备上压根没有） |
| 本机决定是否上调额度 | 本机调 `/admin/quota` | 不能（要 `ADMIN_TOKEN` 口令） |
| 换令牌 = 全员旧包失效 | 换 `SHARED_KEY` 即可 | 不能（旧 key 服务端直接拒） |

## 跑起来（先在本机验）

```bash
cd relay
node selftest.js                 # 39 项断言，不联网、不花钱
node relay.js keygen             # 生成一把 SHARED_KEY
UPSTREAM_KEY=sk-xxx node relay.js
curl http://127.0.0.1:8021/healthz   # 只回 {"ok":true}，不泄露任何信息
```

`selftest.js` 覆盖：无令牌、伪造令牌、正常放行、上游失败不计数、额度用尽 429、
另一台设备不受影响、调额后立刻恢复、**pinned 假 key 模式**、**穿透转发但无口令被拒**、
签名模式签发与校验。改中转之后先跑它。

## 端口：为什么是 8021

之前的内网穿透就是按 `0.0.0.0:8021` 起的，relay 默认端口对齐它，省得每次改。
`0.0.0.0` 是**服务端监听地址**（"谁都可以连进来"），不是别人手机里填的地址 ——
成员设备填的是穿透后的**公网域名**（或局域网下手机的 IP），这点最容易搞混。

WiFi 和流量两种网络都能用：穿透隧道在服务端，客户端设备不感知网络切换。

## 手机（Termux）

```bash
pkg install nodejs
# 把 relay/ 整个目录拷到手机，改 start-termux.sh 里的四个值
bash start-termux.sh
```

`start-termux.sh` 默认就是分发形态：`PORT=8021`、`AUTH_MODE=pinned`，
`SHARED_KEY` / `ADMIN_TOKEN` / `UPSTREAM_KEY` 三项要填。

**装在 Termux 里的坑**（都踩过）：

- Android 12+ 必须用 [F-Droid](https://f-droid.org/packages/com.termux/) 或 GitHub 版。
  Play 商店那个有未修复的崩溃，不是配置问题。
- 监听要写 `0.0.0.0`（脚本里已经是），只写 `127.0.0.1` 别人连不进来。
- `termux-wake-lock` 只管 CPU 不休眠，**不管 ROM 清后台**。还要去电池设置里给 Termux
  开"不受限制"，并在多任务界面锁住它。

## 怎么让别的手机访问到

手机在运营商 NAT 后面，没有公网入口，必须穿透。**已经跑通的方案：你现有的
`0.0.0.0:8021` 穿透**，relay 直接对齐这个形态，不用换工具。

其他路线（备用）：

| 方式 | 成员端要装东西 | 国内可达 | 副作用 | 成本 |
|---|---|---|---|---|
| 同一 WiFi 直连 | 否 | 仅局域网 | —— | 0 |
| ngrok | 否 | 可用 | 免费版域名每次变 | 免费 |
| 花生壳 / natapp | 否 | 稳 | 免费版限速限流量 | 免费起 |
| frp | 否 | 稳 | 需要一台 VPS | 一台机器 |
| Tailscale | **是，每台都要装** | 稳 | ⚠️ 系统出现 VPN，交友 App 可能读到并标记 | 免费 |

> 成员手机**不装任何东西、不连 VPN** 是硬要求 —— 所以上表里需要客户端装软件的方案
> 只在紧急时用。

**先从局域网验**：`curl http://<手机IP>:8021/healthz`。这一步通了，说明中转本身没问题，
剩下的只是"怎么把端口暴露出去"。

## 分发模式（pinned：固定假 key）——推荐

成员设备的界面上**看不到任何 key、也没有可填 key 的输入框**。做法：

1. 中转端：`AUTH_MODE=pinned`，`SHARED_KEY` 设一把随机串（`node relay.js keygen`）
2. 打 APK 前：把穿透后的公网地址和 SHARED_KEY 填进
   `app/src/main/assets/distribution.json` 的 `relayUrl` / `sharedKey`，`enabled` 改 `true`；
   可选再填 `model`（例如 `deepseek-chat`）—— 填了成员就改不了模型名，
   免得被改成上游不认的名字、报错看起来像中转坏了
3. 装到成员手机后：

   - 设置页模型段变成三行只读说明（中转地址、设备编号、额度），**没有输入框可改**
   - App 自动生成一个 16 位 `deviceId`（藏在内部，界面上只显示后四位性质的信息）
   - 实际发给中转的令牌 = `deviceId.SHARED_KEY`，成员看到"已由组织统一接入"即可

4. 管理端：

   ```bash
   node relay.js devices                     # 看每台设备今天用了多少（按 deviceId 区分）
   curl -X POST http://127.0.0.1:8021/admin/quota \
     -H 'Content-Type: application/json' \
     -H "X-Admin-Token: <ADMIN_TOKEN>" \
     -d '{"id":"d1a2b3c4d5e6f7","limit":60,"note":"老张"}'
   ```

5. **一键吊销**：换一把 `SHARED_KEY` 重启中转，所有旧包同时失效；新包填新 key。

防呆设计：`distribution.json` 里 `enabled`、`relayUrl`、`sharedKey` **三项同时填齐**
才进入分发模式，漏填一项就回退到普通模式（不然装上就是"连不上又不让填"的死局）。

## 客户端怎么配（非分发模式）

App 里：

- 接口地址 → `https://<穿透域名>/v1`
- API Key → 设备令牌

`LlmClient` 不用改，它本来就是"OpenAI 兼容 + Bearer"。

## 管理

```bash
node relay.js devices                                   # 每台设备今天用了多少
curl -X POST http://127.0.0.1:8021/admin/quota \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Token: 你的口令' \
  -d '{"id":"a1b2c3d4e5f6","limit":60,"note":"老张"}'
```

管理接口的鉴权：**设了 `ADMIN_TOKEN` 就只认请求头**（不管请求从哪个 IP 来）；
没设才退回"只认 127.0.0.1"。这个先后顺序很重要 —— 穿透隧道转发进来的请求，
到达 relay 时源地址是 `127.0.0.1`，"只认本机"在穿透场景下会被整个打穿，
所以公网部署**必须设 `ADMIN_TOKEN`**（启动时缺了会打警告）。

## 三种授权模式

- `AUTH_MODE=pinned`（分发推荐）：令牌 = `<deviceId>.<SHARED_KEY>`，key 固定在
  中转端，设备界面不显示、不可改。换 key = 全员吊销。
- `AUTH_MODE=open`（默认）：设备自己生成 id 就能用，自动建档、按默认额度发。
  朋友间分发够用；代价是改包能刷出新 id。
- `AUTH_MODE=signed`：id 必须带上 `HMAC(SECRET, id)`，由你签发：
  ```bash
  SECRET=xxx node relay.js issue a1b2c3d4e5f6
  ```
  改包刷不出有效令牌（SECRET 不在成员设备上），但每台设备要你手动发一次令牌。

## 计费真相

deepseek-chat 一条回复约 1000 token ≈ ¥0.002。30 条/天/设备 ≈ 每天 5 分钱，
100 台 ≈ ¥150/月。所以这道锁的意义是**授权与控制**，不是省钱。
