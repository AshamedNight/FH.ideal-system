# gateway — 多渠道回复网关骨架

一个不依赖任何第三方包的最小骨架，把「平台连接」和「回复逻辑」拆开。

## 跑起来

```bash
python gateway.py demo    # 完整链路：边界拦截 / 自动发送 / 人工审批
python gateway.py flow    # 回复必要性判定：哪些该回、哪些该闭嘴
```

`demo` 输出：

```
[已发送] qq/阿哲 → 承重 30 公斤，桌面放个卡式炉完全没问题。
[拦截] qq/阿哲：命中人设边界：便宜
审批链路：入队 1 条｜放行 成功｜放行后发送 1 条
自检：边界拦截 ✓｜自动发送 ✓｜人工审批 ✓
```

`flow` 输出每条消息的激活度分数、决策与逐条理由：

```
1. [群] 阿哲：这个桌子承重多少
   → 0.85 / 回复
     · 含问句特征 +0.35
     · 命中知识条目（产品参数）+0.30
```

两个命令退出码为 0 表示对应路径全部验证通过。

## 本地控制台（推荐从这里开始）

```bash
python console.py
# 浏览器会自动打开 http://127.0.0.1:8770
```

**不需要 VS Code，不需要装任何东西，不需要平台资质。** 有 Python 就能跑。

这是一个本地网页，连的是假数据，能做的事：

| 操作 | 效果 |
|---|---|
| 点「跑一轮」 | 模拟抖音拉取评论，第一轮 4 条，第二轮 4 条，第三轮 0 条（去重） |
| 看每条消息 | 发送者、原文、最终结局（待确认 / 沉默 / 拦截 / 无草稿） |
| 点「为什么这样处理」 | 展开风控结论、激活度分数、逐条加减分理由 |
| 待确认队列 | 每条带草稿，可以「发送」或「驳回」，也能一次全部发送 |
| 看顶栏 | 令牌刷新次数、去重条数、已跑轮数 |

它要验证的不是技术，是两件技术验证不了的事：

1. **你看得懂每条消息为什么被这样处理吗** —— 所有判定都带理由，不是黑箱
2. **你愿意把生成的草稿直接发出去吗** —— 侧栏的「发送」按钮就是这个测试

第二点决定项目成败。如果十次里你有三次想改措辞，说明语料还不够，不是判定逻辑的问题。

改完 `persona.json` 后点「重置」，新语料立刻生效，不需要重启服务。

### 假数据和真实环境的差别

只有一处：网络层。`console.py` 里的 `FakeDouyin` 换成 `DouyinClient` 的默认 transport 就是真接口，
其余代码一行不用改。这也是为什么资质没下来也能先把功能开发完。

## 回复必要性（借鉴 MaiBot 的 HeartFlow）

默认关闭。打开后 Hub 会先判断"这条消息值不值得回"，再决定是否生成草稿：

```python
hub = Hub(persona, engine, necessity=True)
```

三档决策：`reply` 直接生成、`wait` 暂不回复、`silent` 连草稿都不生成。

| 信号 | 加减分 |
|---|---|
| 被 @ 或被叫名字 | 强制回复，不看分数 |
| 私聊呼唤 | 强制回复 |
| 含问句特征 | +0.35 |
| 命中知识条目 | +0.30 |
| 与示范语料相近 | +0.25 |
| 私聊场景 | +0.30 |
| 群聊未被点名（强相关时豁免） | −0.15 |
| 60 秒内刚在群里说过话 | −0.25 |
| 纯语气词（嗯 / 哈哈 / 收到） | 直接沉默 |

**为什么需要这一层：** 真实聊天里最大的破绽不是语气，是"每条都秒回"。
一个人对群消息的回复率通常远低于 50%，100% 都回更像客服机器人而不是真人。

## 评论回复（抖音 / 小红书）

```bash
python adapters.py
```

四条评论跑出四种结局：

```
[待确认] xhs/用户A → 链接在主页置顶，或者我私发你。
[沉默]   xhs/用户B 分数 0.0：公开评论区且无明确购买意图 -0.10
[待确认] xhs/用户C → 链接在主页置顶，也可以私信我发你。
[拦截]   xhs/用户D：命中人设边界：便宜
```

评论和私信共用同一条流水线，但多了 `surface` 字段：

```python
Inbound("xhs", "comment_id", "用户A", "求链接",
        surface=Surface.COMMENT,
        thread="note_id")     # 回复要挂在这个笔记下面
```

三档 `surface` 的行为差异：

| surface | 判定倾向 | 发送策略 |
|---|---|---|
| `PRIVATE` 私信 | 一对一必答，不吃「刚说过话」惩罚 | 可自动 |
| `GROUP` 群聊 | 未被点名则克制，强相关才接 | 可自动 |
| `COMMENT` 评论 | 只接购买意图（求链接/在哪买/怎么买） | **默认强制人工确认** |

公开评论区要全自动，必须显式打开：

```python
hub = Hub(persona, engine, allow_public_auto=True)
```

**不建议改这个默认值。** 私信回错影响一个人，评论回错所有人都看得见。

### 两个平台的通道不一样

| | 抖音 | 小红书 |
|---|---|---|
| 接口 | 官方 `/video/comment/list/`、`/reply/` | 无公开评论接口 |
| 前置 | 企业主体 + 互动管理权限 | — |
| 行为 | 真发 | 草稿写入 `outbox/xhs_drafts.jsonl`，人工粘贴 |

`XhsCommentAdapter` 不假装自己有接口。它把草稿落盘，你复制粘贴。
链路依然是完整的（读意图 → 生成 → 审核 → 发布），只是最后一步由人完成。

## 分级放权（autopilot.py）

原来的 `allow_public_auto` 是个全局开关：关着就全要人工，打开就全放开。中间没档位。
autopilot 补上中间这段——让系统**自己挣取信任**。

```python
from autopilot import Credit, AutopilotConfig

hub = Hub(persona, engine, necessity=True,
          autopilot=True,                 # 开启分级放权
          credit=Credit(),                # 初始额度 0，只能人工确认
          auto_cfg=AutopilotConfig())     # 默认：门槛 0.75、解锁需 10 次确认
```

### 三层判定

| 层 | 判什么 | 不过会怎样 |
|---|---|---|
| 话术风险 | 这句回复本身是否涉及价格 / 承诺 / 售后 | 永久人工，不参与解锁 |
| 置信度 | 这条消息有多明确在问价或求链接 | 进队列 |
| 信任额度 | 你对系统的历史采纳率 | 进队列 |

三者都过了才自动发。

### 话术为什么要单独分级

「链接在主页置顶」和「保证七天无理由退换」是两种性质。前者错了顶多信息滞后，
后者错了是法律承诺。放在同一个开关下，等于用最坏的情况绑架最好的情况。

- **高风险**（价格、保证、退款、永久）→ 永远人工
- **中风险**（今天、发货、现货、几小时内）→ 额度够了可自动
- **低风险**（产品参数、链接位置）→ 额度够了可自动

### 信任额度是非对称的

```
确认 +1 分，驳回 −2 分
额度 ≥ 10 才解锁
```

驳回的权重更高，因为它说明"系统判断错了"，比"判断对了"更能反映问题。
效果是：你确认 20 条它才敢自己动，但驳回 5 次就退回全人工。

### 在控制台里试

打开页面右上角的「分级放权」开关，然后：

1. 额度 0 时，四条评论全部进队列
2. 点「发送」四次（或「全部发送」），额度涨到演示门槛 4
3. 再点「重置」（额度保留），再跑一轮

你会看到三条自动发出、一条仍要确认。分界线是置信度 0.75：
「求链接 0.75 / 这个在哪买 0.85 / 多久能到 0.90」过关，
「质量怎么样 0.70」留下——泛问确实该人工看一眼。

**演示环境把解锁门槛降到 4**，否则一次会话攒不满 10 次确认。生产默认 10。

## 抖音完整链路（douyin.py）

`adapters.py` 里的抖音适配器只够演示。真跑要用 `douyin.py`，它多处理了四件不加就跑不下去的事：

```bash
python douyin.py                  # 不联网演示：令牌刷新 / 去重 / 四种结局 / 过期识别
python -m unittest test_douyin    # 20 项，全部走注入的假 transport
```

| 问题 | 不处理的后果 | 处理方式 |
|---|---|---|
| access_token 只有 15 天 | 第 16 天起整条链路静默停发 | 过期前自动刷新，结果落盘 |
| 同一条评论会被反复拉到 | 同一个人收到多条相同回复 | comment_id 去重，默认保留 30 天 |
| 接口有 QPS 限制 | 被频控 | 最小间隔限流，默认 1 秒 |
| 错误码分多种 | 分不清「能自愈」和「必须人工」 | 10008/2190008 刷新重试，10010 要求重新授权 |

### 令牌生命周期

```
access_token   15 天    过期 → 用 refresh_token 换新的
refresh_token  30 天    刷新 access_token 不会延长它
renew_refresh  5 次     需 client_key 具备 renew_refresh_token 权限
最长          195 天    15 + 30 + 30×5，之后必须用户重新授权
```

**不做刷新，第 16 天断；只做刷新不做续期，第 45 天断。** `ensure_token()` 在每次调用前检查，
`TokenStore` 把令牌写进本地文件，进程重启不丢。

### 接入准备

1. 企业主体入驻抖音开放平台（个人主体申请「互动管理」基本过不了）
2. 创建网站应用并完成上线转正
3. 申请权限 `video.comment`
4. 走 OAuth 拿 code，换 access_token / refresh_token
5. 把要监听的视频 `item_id` 填进 `DouyinAdapter(client, item_ids)`

`item_id` 可以手填（演示用），也可以交给 `WorkRegistry` 自动发现（见下一节）。
自动发现要额外申请 `video.list` 权限，但省掉的是"每发一条视频改一次代码"。

### 先跑 dry_run

`allow_public_auto` 保持默认 False，评论回复全部进人工队列。
先跑一周只看草稿，确认文案质量和自己的采纳率，再考虑放开。

## 抖音授权（douyin_auth.py）

OAuth 那一步常被当成最大障碍，其实它只是第四难。真正的障碍在权限，见下面那张表。

### redirect_uri 是「浏览器跳去哪」，不是「回调服务器」

平台授权成功后让浏览器跳到一个地址，把 `code` 拼在参数里。
**它不要求那个地址现在能响应**——打不开、404、域名不存在都行，
因为 code 已经出现在地址栏里了。

所以不需要公网服务器，也不需要备案。

```bash
# 1. 生成授权链接
python douyin_auth.py url --client-key tt1234567890 \
    --redirect https://example.com/douyin/callback \
    --scope user_info,item.comment

# 2. 授权完，把地址栏整条 URL 粘进去（裸 code 也行）
python douyin_auth.py paste --client-key tt... --secret abc... \
    --code "https://example.com/douyin/callback?code=ffab5ec2&state=s1"

# 3. 看令牌剩余时间
python douyin_auth.py status
```

`paste` 能识别三种粘法：裸 code、整条回调 URL、带引号和空格的 URL。
这件事单独做了，因为实际复制时人不会「只挑 code 那一段」。

错误码翻译成人话，尤其是 `10007`（授权码 10 分钟过期且只能用一次）——
不提示这个，用户会反复重试同一条链接，而那条链接已经失效了。

### 另外两种方式

```bash
python douyin_auth.py serve --client-key tt... --secret abc... --port 9527
# 起本地回调，配合 ngrok / cpolar 映射成 https
# 前提是后台接受未备案域名

python douyin_auth.py mini --client-key tt... --secret abc... --ticket "..."
# 小程序 tt.showDouyinOpenAuth 返回的 ticket，直接当 code 用
# 完全不存在 redirect_uri
```

### 但权限才是真卡点

抖音 2025 年发过《移动/网站应用部分能力权限下线/回收公告》：

```
2025-02-13 起停止接收移动、网站应用关于开通以下能力权限的申请
2025-03-13 起分批回收历史已开通的移动、网站应用的相应权限

受影响：video.comment、item.comment、data.external.item
```

公告已生效一年半。**新建的移动应用和网站应用申请不到评论权限**，
域名问题解决了也走不通。

「视频评论管理」能力详情页仍写「暂无准入条件，任一应用均可申请」，
和公告矛盾。以后台实际入口为准——控制台的「申请开通」按钮能不能点，一分钟能看出结果。

小程序侧有独立接口（`/api/apps/v1/item/list_comment/`，scope `ma.item.comment`），
授权走 `tt.showDouyinOpenAuth`，不需要 `redirect_uri`。
但能力被标为「高级能力」，且官方 FAQ 有一句「企业小程序和个人小程序目前均不支持
获取粉丝数、评论数据相关数据信息」，字面读不出指的是统计还是内容列表。

**结论是不确定，只能在控制台点一次。** 没权限时把小程序接口写出来也跑不通，
所以这部分代码等审核结果再做。

## 部署必备的四块（works / store / scheduler）

前面几节是"能跑通"，这一节是"能一直跑"。
四块都属于"不补就不报错地停下来"的类型。

### works.py — 作品自动发现

原来 `item_ids` 手写死，每发一条视频要有人改代码。`WorkRegistry` 改成
全量拉取 + 差集比较：新作品自动纳入，删除/下架/私密化的自动移出。

需要 `video.list` 权限。**硬上限 80 条**（4 页 × 20），
超过 80 条的作品其评论永远拉不到——这是接口限制，代码里已经夹死并标记 `truncated`。

```python
from works import WorkRegistry

registry = WorkRegistry(client, cache_path="works_cache.json", ttl=3600)
adapter = DouyinAdapter(client, registry=registry,
                        own_user_ids=["你的 comment_user_id"],
                        my_names=["你的抖音昵称"])
```

### 自回自答过滤（douyin.py 内）

自己发的回复也会出现在评论列表里。不过滤就是自己回自己，能无限循环，
而且日志看起来完全正常。两道防线：

1. `own_user_ids` —— 比对 `comment_user_id`（抖音评论没有 `is_author` 字段）
2. 发送记录 —— `send()` 成功后把新 comment_id 记进 `own_cache`，重启也还在

### 二级评论

用户在你回复下面追问「看不到啊」，这批人意向最强，但顶级评论列表里看不到。
现在按 `reply_comment_total` 变化触发拉取 `/video/comment/reply/list/`，
并且只在「回复的是我发的评论」时才标记 `is_mentioned=True`——
两个路人在你评论区互相聊，不该插嘴。

### store.py — 持久化

队列、已发送记录、信任额度、去重表全部落 SQLite。重启后队列还在。

```python
from store import Store, StoreSeen

store = Store("gateway.db")
hub = Hub(persona, engine, necessity=True, store=store)
adapter = DouyinAdapter(client, registry=registry, seen=StoreSeen(store))
```

实测：4 条待确认 + 额度 4，杀进程重启后完整恢复。

### scheduler.py — 调度与告警

```python
from scheduler import Scheduler

sched = Scheduler(hub.step, adapters=[adapter], interval=180,
                  on_alert=lambda a: print(a))   # 生产换成企微/钉钉 webhook
sched.run_forever(queue_depth=lambda: len(hub.pending))
```

- 指数退避，封顶 30 分钟
- 令牌剩余 < 7 天预警，< 1 天严重告警
- 队列积压超过 50 条预警
- 同一个问题一小时只喊一次（按 code 去重，不按文案）

### 发送失败不再被当成已发出

`approve()` 原来不看 `send()` 的返回值，接口报错时草稿照样被移出队列。
结果是"日志显示已发送、用户没收到、运营以为处理完了"。
现在失败会退回队列并留在控制台可见。

## 无审核演示（demo_run.py + outlet.py）

平台权限没批下来，也要能把系统跑给人看。

做法是把链条两端的其中一端换掉：起点从「平台接口」换成「评论快照」，
终点从「抖音评论区」换成「你自己能收到的信道」。中间判定、风控、生成全是真的。

```
python demo_run.py --reasons          # 本地文件接收，打印每条判定依据
python demo_run.py --webhook <URL>    # 投到企业微信 / 钉钉群机器人
python demo_run.py --webhook <URL> --style feishu   # 飞书
python demo_run.py --dry              # 只看不发
```

出口在 `outlet.py` 里，三种：

| 出口 | 准备成本 | 用途 |
|---|---|---|
| `LocalOutlet` | 0 | 写 JSONL，终端可看，演示兜底 |
| `WebhookOutlet` | 建群加机器人，3 分钟 | 手机能收到，最有说服力 |
| `OneBotOutlet` | 起 NapCat + 小号 | 发到 QQ 群，最像真实聊天 |

```python
from outlet import build_outlet

outlet = build_outlet({"kind": "webhook",
                       "url": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...",
                       "style": "wecom",
                       "prefix": "【演示】"})
outlet.deliver("回复内容", {"sender": "小王"})
```

**不做的事**：不往任何真实抖音用户那里发消息。没有审核就等于没有发送权限，
绕过去发是违反平台规则，不是技术问题。

### 一份快照长什么样

`recordings/demo_comments.json`，字段与 `/video/comment/list/` 的返回结构对应：

```json
{"sender": "小王", "text": "这个多少钱", "surface": "comment",
 "is_mentioned": true, "parent_id": "", "ts_offset": 0}
```

`ts_offset` 是相对秒数，用来还原评论发出的先后间隔——
全填 0 会被刷屏规则判成连续轰炸，那是测试环境的假象。

## M1：把一致率量出来（label.py）

M0 只能证明「跑得起来」，证明不了「判得对」。M1 要的是一个数字：
系统判「该回 / 不该回」，和你判的差多少。这个数字不到 80%，接真接口没有意义。

### 第一步：评论从哪来

**不能随便找一个平台的 100 条评论。** 判定是被「账号卖什么、怎么说话」决定的：
同一条「求链接」，在卖木工桌的账号下是转化机会，在搞笑视频下是无意义噪声。
换一个无关账号的评论，测出来的是另一个分布，数字没有意义。

手里评论不够 100 条时，用**三层抽样**凑，但**分组统计、不合并**：

| 层 | 来源 | 补什么 | 数量建议 |
|---|---|---|---|
| **own** | 自己账号的评论（B 站也行） | 真实场景，最可信 | 全部，60 条就够 |
| **similar** | 同品类账号的公开评论 | 购买意图样本（「求链接」「多少钱」） | 20–30 条 |
| **unrelated** | 无关内容的评论区 | 验证「不该回时不误伤」 | 20–30 条 |

三批加起来过 100。报告里每组各自算一致率、各自带置信区间——
合并成一个大数字是假的，因为三批分布本来就不同。

采集路径（都不需要接口权限）：

| 来源 | 路径 |
|---|---|
| 抖音 | 电脑端 `creator.douyin.com` → 互动管理 → 评论管理；手机端 消息 → 评论 |
| B 站 | `member.bilibili.com` → 评论管理；或公开视频评论区直接复制 |
| 小红书 / 其他 | 打开作品页，评论区直接框选复制 |

复制时不用整理格式，一行一条。从表格复制出的「昵称 + Tab + 评论」也能识别。

### 为什么 B 站的评论也能用

判定逻辑是跨平台通用的：问句、购买意图、议价、退款、夸赞，这些信号与平台无关。
不通用的是**语料和阈值**——卖什么、怎么说话，是账号特定的。

所以 B 站数据能验证「判定方法是否合理」，不能验证「对这个抖音账号是否准确」。
等抖音账号到位后，用同一套人设重跑一次，两次结果对比就知道迁移损失有多大。

### 样本不够时，数字长什么样

7 条全对，一致率 100%，看起来完美。但 95% 置信区间是 **64.6% – 100%**——
下界离 80% 还差得远，这个结果什么都证明不了。

工具会把这个区间直接印在报告上，并且在区间下界没到 80% 时，不会给出「达标」的结论。

### 第二步：导入并盲标

```bash
cd app/gateway
python label.py
```

浏览器打开 `127.0.0.1:8771`，粘贴评论 → 导入 → 一条一条点「该回 / 不该回 / 拿不准」。
键盘 `1` / `2` / `3`，`Backspace` 回退。

**标注时看不到系统判定。** 这不是界面上藏了，是后端根本不返回——
先看见结论再标，一致率会虚高，那个数字就没用了。

### 第三步：揭晓

标完点「揭晓结果」，得到三样东西：

- 一致率
- 混淆矩阵：一致 / 该回没回 / 不该回却回了
- 两类分歧的具体条目（附系统草稿）

两类错误分开列，因为代价不一样：

| 分歧类型 | 代价 |
|---|---|
| **该回没回** | 错过一次转化机会 |
| **不该回却回了** | 公开场合说错话，所有人都看得见 |

### 判定标准

| 条件 | 结论 |
|---|---|
| 样本 ≥ 100 且一致率 ≥ 80% | 达到 M1 门槛，进 M2 |
| 样本 ≥ 100 但一致率 < 80% | 调语料和权重，不要接真接口 |
| 样本 < 100 | 只当参考，继续补 |

置信区间不参与判定，它回答的是另一个问题：**这个数字有多不精确**。

一致率为 80% 时，不同样本量的 95% 区间（实测值）：

| 样本 | 区间 |
|---|---|
| 30 | 63% – 90% |
| 60 | 68% – 88% |
| 100 | 71% – 87% |
| 200 | 74% – 85% |

注意 100 条那一行：下界只有 71%。如果要求「下界 ≥ 80%」才算达标，
100 条得做到 88% 一致率——那个门槛对首轮校准不现实。

所以判定还是按 80% 卡，区间用来看两件事：现在这个数字能信到什么程度，
以及补样本之后不确定性收窄了多少。

## 接入企业微信（wecom.py / wecom_server.py）

微信生态里能合法做自动回复的只有两个入口：企业微信自建应用、公众号客服。
个人微信号被腾讯安全中心的外挂公告明确列出（「微信机器人：定时群发、自动回复、
自动聊天」），封的是登录账号本身，所以这条不做。

企业微信不需要应用审核，注册主体当天就能建应用——这是它比抖音快的地方。

### 和抖音的结构差异

| | 抖音 | 企业微信 |
|---|---|---|
| 消息怎么来 | 我轮询 `/video/comment/list/` | 平台 POST 到我的回调 URL |
| 适配器的 poll | 发 HTTP 请求 | 只从内存队列取 |
| 额外要处理 | OAuth、15 天令牌 | AES 解密、5 秒响应窗口 |

### 四步

```bash
# 1. 复制配置，填四个值（都在应用详情页）
cp wecom.example.json wecom.json

# 2. 先只收不回，确认回调通
python wecom_server.py --config wecom.json --dry

# 3. 把打印出来的地址填进「应用管理 → 接收消息 → 设置API接收」
#    企业微信会立刻发一次验证请求，通过就说明加解密没问题

# 4. 关掉 --dry，按闸门决定是否回复
python wecom_server.py --config wecom.json
```

### 加解密为什么自己写

Python 标准库没有 AES，而这个项目一直零第三方依赖。所以 `wecom_crypto.py`
实现了一份 AES-256-CBC，并用两组向量确认正确：

- **FIPS-197** 的 AES-256 单块向量（验证 AES 本身）
- **企业微信官方文档**里的完整样例：签名算出 `477715d1…f3e6`，解密出的明文与文档展示一致

第二组尤其关键。AES 错一位通常表现为「大部分消息能解、偶尔出乱码」，
不报错，只出脏数据。对齐官方向量是一次性确认的唯一办法。

### 5 秒窗口怎么绕开

平台 5 秒内收不到响应会断开并重试三次。判定 + 生成可能超时，所以：

```
回调：验签 → 解密 → 入队 → 立刻返回空串（表示不被动回复）
后台线程：每 2 秒跑一次 Hub，处理完再主动调 message/send
```

这样回复不受 5 秒限制，也能走「先挂起、人工确认后再发」——和抖音那条链路一致。

### 三个限制要先知道

- **只能发给企业成员。** 自建应用的消息对象是内部成员，不是外部客户。
  要触达外部客户得开通「微信客服」，那是另一套接口。
- **回调必须公网可达。** 本机 127.0.0.1 收不到推送，要反代或内网穿透。
- **先只收不回跑一周。** 真实聊天的语态和评论区差别很大，语料基本要重写。

验证方式建议用两个号对聊：一个当客服、一个当顾客，
流程和真实场景完全一样，不需要外部客户，也不需要额外权限。

### 没有资质怎么测（wecom_sim.py）

不需要注册企业、不需要公网地址、不需要联网。一条命令：

```bash
python wecom_sim.py
```

它做三件事：

1. 本地起一个 HTTP 服务冒充 `qyapi.weixin.qq.com`（gettoken + message/send），
   把收到的每条消息原样记下来
2. 扮演平台，把场景文件里的消息按真实协议**加密、签名**后 POST 到回调服务
3. 跑完自己验收六项：URL 验证、消息到达、确实发出、边界拦截、令牌缓存、重复去重

关键在于它**不替换 transport**。替换 transport 能测业务逻辑，但测不到 URL 编码、
CDATA、Content-Type、端口绑定——这些恰恰是真实部署第一天会出错的地方。

```bash
python wecom_sim.py --scenario recordings/wecom_chat.json \
                    --persona personas/wecom_chat.json
python wecom_sim.py --keep      # 跑完不退出，留 /health 可查
python wecom_sim.py --batch     # 一次性灌入，用于对照刷屏判定
```

场景文件格式就是一串对话，换成你自己的真实聊天记录即可：

```json
{
  "name": "企业微信客服 · 售前咨询",
  "turns": [
    { "from": "zhangsan", "text": "在吗", "gap": 0 },
    { "from": "zhangsan", "text": "这个桌子多少钱", "gap": 2 }
  ]
}
```

`gap` 是相对上一条的分钟数。它影响刷屏判定——连续追问和隔天再问，
结论应该不同。

### 想看它自己动起来（wecom_live.py）

`wecom_sim.py` 是「跑一遍就退出」，证明链路通。但它看不出这是自动的——
一次性批处理，和人工逐条处理在观感上没区别。

`wecom_live.py` 是个常驻的聊天窗口：

```bash
python wecom_live.py
# 自动打开 http://127.0.0.1:8790，打一句话，几百毫秒后自动回复
```

| 你说 | 结局 | 界面表现 |
|---|---|---|
| 在吗 | 自动回复 | 蓝色气泡 + `已自动回复 · 745ms · 私聊一对一，边界没命中` |
| 能便宜点吗 | 拦截 | **不产生气泡**，一条居中提示「拦截 · 命中人设边界：便宜点」 |
| 嗯 | 不回复 | 居中提示「不回复 · 纯语气词，无信息量」 |

第二行最能说明问题：**它不会回你**。会拒绝，才像有边界的东西。

它走的是真协议：AES 加密 → SHA1 签名 → HTTP POST 到本地回调 → 验签解密 →
判定 → 生成 → 闸门 → 调 `message/send` → 假平台收下 → 界面显示。
界面上的耗时是真实处理时间，不是写死的动画。

`--dry` 只生成草稿不发送，用来看「如果不开自动发送，会攒下多少待确认」。
`--persona personas/xxx.json` 换人设，同一句话会得到完全不同的回答。

### 回调地址没有域名怎么办

企业微信要求回调域名与企业认证主体一致（备案主体相同）。没有营业执照就过不了。

官方对未认证企业给的做法是：**直接填 IP 形式的回调 URL**——
`http://1.2.3.4:8780/wecom/callback`，再用同一个 IP 去配「企业可信 IP」。

这条路能走通，前提是有一台公网 IP 的机器。本机没有时可以用内网穿透，
但穿透服务给的通常也是域名，未必过得了主体校验，先试 IP。

抖音那边没有这个退路——它的 OAuth 回调必须是已备案域名。这是两边门槛的真正差别。

## 接真实的 QQ（NapCat / Lagrange / LLOneBot）

这三个都实现了 OneBot v11 协议，所以同一份适配器代码可以通用。

```bash
# 1. 先起 NapCat（Docker 方式，Linux 可无头运行）
docker run -d --name napcat -p 3000:3000 -p 3001:3001 mlikiowa/napcat-docker
# 扫码登录后，OneBot HTTP 服务在 3000

# 2. 配置环境变量
set ONEBOT_API=http://127.0.0.1:3000
set ONEBOT_TOKEN=你的token

# 3. 启动（默认 dry_run，只出草稿不发消息）
python gateway.py onebot
```

要真正发出消息，把 `run_onebot()` 里的 `dry_run=True` 改掉，**并确认你接受封号风险**。
OneBot 是社区协议实现，不是 QQ 官方接口。

实际接入时应当用 WebSocket 收事件而不是轮询，把事件喂给 `adapter.push_event(event)`：

```python
adapter.push_event({
    "post_type": "message",
    "group_id": 88347621,
    "sender": {"nickname": "阿哲"},
    "raw_message": "这个桌子承重多少",
})
```

## 换模型

```bash
set LLM_BASE=https://api.openai.com/v1
set LLM_KEY=sk-xxx
set LLM_MODEL=gpt-4o-mini
```

不配 `LLM_KEY` 时自动降级到 `MockEngine`——它只靠 `persona.json` 里的 12 条示范语料做检索，
不调任何模型也能给出贴着人设的回复。这不是降级方案，而是用来验证「示范语料权重高于形容词」这件事。

## 加一个渠道

以抖音评论为例，只需要实现两个方法：

```python
class DouyinAdapter(ChannelAdapter):
    name = "douyin"
    # 只回答「技术上能不能发」，不回答「该不该自己发」。
    # 后者由 Hub 的三层闸门 + autopilot 决定。在这里写 False 会让
    # autopilot 永远没机会判断，结果是永远全人工。
    auto_send_allowed = True

    def poll(self) -> list[Inbound]:
        # 调 /video/comment/list/ 拿新评论，转成 Inbound
        ...

    def send(self, reply: Reply) -> bool:
        # 调 /video/comment/reply/ 发回复
        ...
```

然后一行注册，核心代码不用动：

```python
hub.register(DouyinAdapter(...), channels=["douyin"])
```

## 人设

改 `persona.json` 即可，六个部分：

| 字段 | 作用 |
|---|---|
| `identity` | 身份与背景，决定 AI 的立场 |
| `tone` | 正式度 / 句长 / emoji，驱动语气 |
| `knowledge` | 结构化事实条目，防编造 |
| `boundaries` | 命中就不生成草稿，直接转人工 |
| `taboo_words` | 硬拦截词 |
| `few_shot` | 示范语料，**决定像不像人的关键是这一栏** |

## 人设配置层：让判定与商品解耦（persona_schema.py）

前面那一节说的是「素材」。这一节说的是「逻辑」——两者现在分开了。

### 原来的问题

`necessity.py` 里硬编码着 `COMMENT_INTENT = ("求链接", "在哪买", "价格", ...)`。
这等于把判定逻辑绑在电商上：

- 换商品 A→B：勉强能用，但语料全要重写
- 换赛道（做内容、做咨询、不卖货）：**直接罢工**——它还在把「求链接」当商机
- 换账号：整套推倒重来

### 拆成两层

| | 意图层（跨行业固定） | 素材层（每个账号一份） |
|---|---|---|
| 内容 | purchase / inquiry / after_sales / social / negative | 词表、答案库、风格样本、边界 |
| 谁改 | 不改代码 | 改 JSON |
| 换商品 | 不动 | 改 `knowledge` + `few_shot` |
| 换赛道 | 清空 `purchase` 词表 | 改 `profile.intents` |
| 换账号 | 不动 | 整份配置换掉 |

判定只认意图标签，不认商品。所以代码里再也找不到「求链接」这三个字。

### profile 字段

```json
"profile": {
  "role": "卖实木折叠露营桌",
  "offering": "product",
  "aliases": ["小陆", "木工房"],
  "intents": {
    "purchase":    ["求链接", "在哪买", "多少钱"],
    "inquiry":     ["承重", "尺寸", "多久"],
    "after_sales": ["退款", "坏了"],
    "social":      ["好看", "喜欢"],
    "negative":    ["骗", "垃圾"]
  },
  "fallback": {"action": "handoff", "template": ""}
}
```

`offering` 有四种：`product` / `content` / `service` / `none`。
它不只是标签，`validate` 会用它检查配置矛盾——比如 `content` 却配了 purchase 词表，
说明你会把普通评论误判成商机。

`fallback.action` 是答不上来时怎么办：`handoff`（转人工）/ `silent`（不回）/ `template`（发固定话术）。
原来这个行为是隐式的（没草稿就进队列），现在显式写出来。

### 意图权重

| 意图 | 权重 | 为什么是这个值 |
|---|---|---|
| purchase | 0.65 | 单独能过阈值。新账号还没语料时，遇到「求链接」必须接住 |
| after_sales | 0.65 | 同样要能单独成立，退款类消息必须留下记录 |
| inquiry | 0.35 | 单独不够，要配合问句特征——「多久」不一定是在问你 |
| social | 0.15 | 刻意压低，否则每条「好看」都要回一遍 |
| negative | — | 前置判断，命中就沉默，不参与权重比较 |

`negative` 特殊处理的原因：它的权重必须是 0（不该加分），但 0 又让它永远比不过别的意图。
「骗人的吧，求链接」按权重比会判成 purchase，方向完全反了。

### 四个预设模板

`personas/` 下有四份完整配置：

| 文件 | 形态 | 特点 |
|---|---|---|
| `persona.json` | product | 电商卖货，购买意图权重生效 |
| `personas/creator.json` | content | 内容博主不卖货，purchase 清空 |
| `personas/consulting.json` | service | 咨询服务，问的是怎么约而不是怎么买 |
| `personas/personal.json` | none | 个人号，什么都不卖，兜底是沉默 |

### 验证「真的通用」的方法是做对比

```bash
python persona_cli.py validate personas/creator.json
python persona_cli.py diff --a persona.json --b personas/creator.json
```

同一批评论、两份配置，看处理结果差多少。实测 11 条评论里 7 条结果不同：

| 评论 | 电商（小陆） | 内容号（阿木） |
|---|---|---|
| 求链接 | 回复 0.90 | 沉默 0.00 |
| 承重多少 | 回复 0.90 | 无稿 0.60 |
| 这个多少钱 | 「价格我私信你说」 | 「我不卖东西哈，纯拍着玩」 |

第三行是关键：同一个问题，两份配置给出完全不同的回答，而**代码一行没改**。

如果两份配置给出完全一样的判定，`diff` 会直接报「大概率是意图词表没生效」——
那是配置写错了，不是通用能力强。

### 新建一份人设

```bash
python persona_cli.py new --template creator --name 阿木 --role "做木工视频" \
    --out personas/amu.json
```

模板只给结构，不给成品话术。生成出来能跑判定，但给不出草稿——
`knowledge` 和 `few_shot` 必须由你填，那部分没法通用。

### 仍然不能复用的东西

说清楚边界，免得以为配置能解决一切：

- **语料**：每个账号的说话方式不同，`few_shot` 必须重写。它决定回复像不像人。
- **知识库**：产品参数、服务流程，天然是账号特定的。
- **阈值**：跨行业可以沿用，但一致率要重新测（M1 那一套）。
- **平台**：抖音和 B 站的评论语态不同，判定可迁移，不代表分布一样。

能复用的只有「什么样的评论值得回」这个判断逻辑。这已经是最大的一块了。

## 已验证

```bash
python -m unittest test_gateway test_douyin test_douyin_auth test_autopilot \
    test_deploy test_outlet test_label test_persona test_wecom \
    test_wecom_sim test_wecom_live -v
```

269 项测试全部通过（网关 39 + 抖音 20 + 抖音授权 25 + 分级放权 24 + 部署 29 +
投递 18 + 标注 31 + 人设 23 + 企业微信 26 + 无资质模拟 16 + 交互演示 18），
覆盖十五条路径：

- **边界拦截**：议价、退款、违禁词均被拦下，不生成草稿
- **自动发送**：允许自动发送的渠道正常发出
- **人工审批**：不允许自动发送的渠道正确入队，`approve()` 后发出
- **回复必要性**：该回的回了、语气词沉默、私聊不漏答、评论接住购买意图
- **引擎匹配**：不答非所问，匹配不上就交给人工
- **抖音链路**：令牌刷新、错误码分类、评论去重、翻页上限、空标识拒绝
- **分级放权**：话术风险分级、非对称额度、小时上限、刷新粒度与时间轴
- **部署能力**：@ 识别、自回自答过滤、二级评论、作品发现、持久化、调度告警
- **演示投递**：本地 / 群机器人 / QQ 三种出口，失败不会被报成成功
- **判定校准**：导入解析不切错、混淆矩阵不算反、盲标不漏系统判定
- **人设配置**：旧配置自动迁移、矛盾配置被拦下、换配置后判定真的变
- **企业微信**：AES 对齐两组官方向量、回调验签、非文本忽略、重复推送去重
- **无资质模拟**：真实 HTTP 全链路、发送失败回队列、跨场合语料差异
- **交互演示**：拦截不留气泡但留原因、连发同一句不被去重吞掉、增量拉取不重不漏
- **抖音授权**：redirect_uri 编码、三种粘贴格式、10007 过期提示、令牌落盘

其中十二项是真实缺陷的回归锁：

1. `test_manual_queue_then_approve` —— 适配器名与渠道名不一致，草稿被静默丢弃
2. `test_bargaining_blocked` —— 「打个折」这类中文变形导致漏判
3. `test_private_not_penalized_for_recent_speech` —— 私聊因「刚说过话」而漏答
4. `test_group_relevant_without_mention_still_replies` —— 强相关问题因「未被 @」被误判为观望
5. `test_intent_not_matched_to_unrelated_faq` —— 「这个在哪买」匹配到「这个桌子承重多少」，答非所问
6. `test_private_buying_intent_replies` —— 私信「求链接」被判观望，最强购买信号被忽略
7. `test_send_requires_thread` —— item_id 为空仍发请求，会挂到错误位置
8. `test_skips_own_comment_by_recorded_id` —— 自己的回复被当成新评论，会自回自答
9. `test_failed_send_goes_back_to_queue` —— 发送失败被当成已发出，草稿被吞
10. `test_third_party_thread_not_mention` —— 路人互相回复被误判成"冲我来的"
11. `test_recent_texts_context_does_not_crash` —— `necessity.py` 用了未导入的 `_lcs_len`，传上下文即 NameError
12. `test_platform_error_code_is_failure` —— 群机器人 HTTP 200 但 errcode 非 0，被当成发送成功

第 7 项的根因值得一提：`seen or SeenStore(...)` 这个写法，在 `SeenStore` 定义了 `__len__` 之后会出问题——
空对象在布尔判断里是 False，传入的实例被悄悄丢掉，退回默认路径。测试之间互相污染，且不报任何错。

## 一个已知局限

`check()` 里的边界判定是**关键词匹配**，不是意图识别。
用户说「能不能少点」「这个价有点高」「我朋友上月买好像便宜些」，词表永远补不全。

现在的 `_loose_match()` 允许关键词各字之间隔 2 个字，能覆盖大部分口语变形，
但这只是兜底。生产环境应该在它前面加一层意图分类（小模型或 LLM 判断「是否涉及议价/退款/投诉」），
关键词只负责最后一道保险。

判断原则：**漏判的代价是把不该发的话发出去，误判的代价只是多一条转人工。**
两者后果不对等，所以宁可多拦。

第二个局限在语料里：**价格写死在 `few_shot` 里，不是从 `knowledge` 读的。**
改价格必须同时改语料，否则系统会稳定地报旧价。
上线前把 `persona.json` 里所有含数字的语料搜一遍——价格、时效、尺寸都在里面。
