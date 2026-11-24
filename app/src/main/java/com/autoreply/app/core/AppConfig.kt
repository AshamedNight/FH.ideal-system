package com.autoreply.app.core

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * 全部可调参数集中在这里。
 *
 * 为什么不用 config.json：Android 的私有目录读写要处理权限和路径，
 * 而 SharedPreferences 天生是原子的、跨进程安全、卸载才清。
 * 配置项不多，没必要引入序列化依赖。
 */
class AppConfig(context: Context) {

    private val appContext = context.applicationContext

    private val sp = appContext
        .getSharedPreferences("autoreply", Context.MODE_PRIVATE)

    /**
     * 分发配置（assets/distribution.json）。**文件不存在或 enabled=false 时整份忽略**，
     * 这时 App 跟开发版完全一样：设置页手填接口和 Key。
     *
     * 为什么放 assets 而不是 BuildConfig：不用动 build.gradle.kts，CI 零风险；
     * 而且 precheck 第 5 步本来就会校验 assets 里每个 JSON 是否合法。
     *
     * （这里原本写的是「assets 斜杠星号点 json」。那一对字符是块注释的开头，
     *   而 Kotlin 的块注释支持嵌套 —— 于是又开了一层，后面整个文件都被吞掉，
     *   编译器报 `Missing '}'` + 满屏 Unresolved reference。注释里别写那个组合。）
     */
    private val dist: JSONObject? by lazy {
        try {
            JSONObject(appContext.assets.open("distribution.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 是否进入「分发模式」。
     *
     * 三个条件必须同时成立才启用 —— 尤其后两个不能省：分发方漏填 relayUrl 或
     * sharedKey 时，如果照样隐藏设置页，用户就既连不上、又改不了，彻底卡死。
     */
    val distributionEnabled: Boolean
        get() = dist?.optBoolean("enabled", false) == true &&
            (dist?.optString("relayUrl", "") ?: "").isNotBlank() &&
            (dist?.optString("sharedKey", "") ?: "").isNotBlank()

    /**
     * 本机设备编号。首次取用时随机生成并持久化，之后不变。
     *
     * 它不是密钥，只是中转用来分账的 id：每台设备一条额度记录，本机 master
     * 按这个 id 单独调额（relay 的 /admin/devices 会列出来）。
     * 格式对齐服务端 `[A-Za-z0-9_-]{6,32}`。
     *
     * 界面上可以显示（供 master 认领设备），但**不可修改** —— 改了就等于换一台
     * 设备、白拿一份新额度，所以没有 setter。
     */
    val deviceId: String
        get() {
            val saved = sp.getString(KEY_DEVICE_ID, "") ?: ""
            if (saved.isNotBlank()) return saved
            val fresh = "d" + UUID.randomUUID().toString().replace("-", "").take(15)
            sp.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    /** 风险档位。0=L0 只填不发，1=L1 通知驱动，2=L2 列表轮询。默认 1。 */
    var level: Int
        get() = sp.getInt(KEY_LEVEL, LEVEL_NOTIFY)
        set(value) = sp.edit().putInt(KEY_LEVEL, value).apply()

    /**
     * 每小时最多处理几条。超过就停到下个小时，不做"攒着补发"。
     *
     * 15 而不是最初的 6：交友平台的活跃时段，一个人一小时回十几条是常态
     * （刚匹配一批人，逐个打招呼）。按 6 算等于把正常使用判成异常，
     * 一被拦就是几十分钟，反而不像真人 —— 真人不会因为回了 6 条就消失。
     */
    var hourlyCap: Int
        get() = sp.getInt(KEY_HOURLY_CAP, 15)
        // 下限 1：用户把输入框清空时 intOf 会得到 0，界面上就显示「本小时 0/0」
        // 并且永久拦截 —— 看起来像程序坏了，其实只是那个字段空着。
        set(value) = sp.edit().putInt(KEY_HOURLY_CAP, value.coerceAtLeast(1)).apply()

    /**
     * 连续处理几条后强制长冷却。
     *
     * 8 而不是 3：3 条就断，用起来就是"点三下被锁 20 分钟"。
     * 8 条之后再歇，更接近"聊了一阵子，放下手机喝口水"。
     */
    var burstLimit: Int
        get() = sp.getInt(KEY_BURST, 8)
        set(value) = sp.edit().putInt(KEY_BURST, value).apply()

    /**
     * 长冷却时长（分钟），下限 3。
     *
     * 下限从 10 降到 3：下限 10 意味着哪怕只想歇一下也要停十分钟，
     * 测试时基本用不下去。真正需要长停的场景是平台提示限流，
     * 那是 [PacerGuard.trip] 的整段熔断（默认 30 分钟），不用这个值。
     */
    var cooldownMinutes: Int
        get() = sp.getInt(KEY_COOLDOWN_MIN, 5)
        set(value) = sp.edit().putInt(KEY_COOLDOWN_MIN, value.coerceAtLeast(3)).apply()

    /** 每条之间的基础间隔（秒），实际会乘一个 0.7~1.8 的随机系数。 */
    var minGapSeconds: Int
        get() = sp.getInt(KEY_GAP_SEC, 45)
        set(value) = sp.edit().putInt(KEY_GAP_SEC, value.coerceAtLeast(30)).apply()

    /**
     * 演练模式：只跑流程，不调真模型、不限速。
     *
     * 用来做"批量发送会不会被封"的实测 —— 那种测试需要短时间内发几十上百条，
     * 而且完全不需要模型参与（内容不重要）。开着它：
     *   - 回复由本机假模型生成，**不花一分钱、不联网**
     *   - 每小时上限 / 连续上限 / 冷却 / 精力全部不生效
     *   - **熔断仍然生效**：那是平台真的报警了，必须停
     *
     * 注意：这些内容是**真的会发出去**的（发送永远由人点），所以只能用于测试。
     */
    var drillMode: Boolean
        get() = sp.getBoolean(KEY_DRILL, false)
        set(value) = sp.edit().putBoolean(KEY_DRILL, value).apply()

    /**
     * 演练模式的取词扰动：每生成一次 +1。
     *
     * 为什么需要：演练模式的取词种子原来是「对方说了哪句话 + 对方说过几句」。
     * 同一条消息连点三次，这两个都不变 —— 于是三次给出**完全一样**的话，
     * 输入框里还会因为插入式写入叠成「怎么说怎么说怎么说」。
     *
     * 有了这个计数器，连点会依次取到池子里的不同条目。
     * 真实模型不需要它（每次本来就不一样），所以只在演练分支用。
     */
    var drillNudge: Int
        get() = sp.getInt(KEY_DRILL_NUDGE, 0)
        set(value) = sp.edit().putInt(KEY_DRILL_NUDGE, value).apply()

    /**
     * 输入法面板是否展开。
     *
     * 默认折叠（一行高度）：展开态约 300dp，占屏幕下半三分之一，
     * 会把聊天内容顶上去。大多数时候用户只是在打字/看消息，
     * 不该一调起输入法就吃掉半屏 —— 需要时点一下把手展开。
     *
     * 状态要持久化：想用的人希望它记住上次的选择，而不是每次重新展开。
     */
    var imePanelExpanded: Boolean
        get() = sp.getBoolean(KEY_IME_EXPANDED, false)
        set(value) = sp.edit().putBoolean(KEY_IME_EXPANDED, value).apply()

    /**
     * 输入法面板停在哪一页：0 = 回复动作，1 = 模式/风格。
     *
     * 记住它是因为"我正想调风格"这种事会连着发生几次；
     * 每次都滑回去很烦。**只在换页时写一次**（不是拖拽每一帧都写），
     * 免得手指还没松手就刷了几十次 SharedPreferences。
     */
    var imePage: Int
        get() = sp.getInt(KEY_IME_PAGE, 0)
        set(value) = sp.edit().putInt(KEY_IME_PAGE, value.coerceIn(0, 1)).apply()

    /**
     * 一次「连点」最多处理几个会话。
     *
     * 上限 20：连点是效率工具，但它同时是这套东西里最接近"批量操作"的行为。
     * 限制单轮数量，等于强制你在每轮之间回来看一眼 —— 那一眼就是人的判断。
     */
    var batchSize: Int
        get() = sp.getInt(KEY_BATCH_SIZE, 5)
        // 演练模式放开到 999：那个模式存在的意义就是"一次发很多条"。
        // 平时仍限制 20 —— 连点是效率工具，不是批量轰炸机。
        set(value) = sp.edit()
            .putInt(KEY_BATCH_SIZE, value.coerceIn(1, if (drillMode) 999 else 20))
            .apply()

    /**
     * 手动指定「现在是什么时候」。空 = 跟系统时钟。
     *
     * 用途：测试不同时段的说话方式（上午 / 下午 / 晚上 / 深夜），
     * 以及系统时间不对、或者你想让它是"晚上"的时候。
     * 取值：""(自动) / "morning" / "afternoon" / "evening" / "night"
     */
    var timeOfDay: String
        get() = sp.getString(KEY_TIME_OF_DAY, "") ?: ""
        set(value) = sp.edit().putString(KEY_TIME_OF_DAY, value).apply()

    /**
     * 说话风格，取值见 [com.autoreply.app.core.llm.TalkStyle.ALL]。
     *
     * 和 [timeOfDay] 一样是"这一阵子怎么说话"的开关，区别是它管**语气**而不是时机。
     * 只在真模型路径生效 —— 演练模式的假模型不读提示词。
     */
    var talkStyle: String
        get() = sp.getString(KEY_TALK_STYLE, "natural") ?: "natural"
        set(value) = sp.edit().putString(KEY_TALK_STYLE, value).apply()

    /**
     * 精力锁：锁定后不再消耗、也不随时间恢复，永远等于 [energyLockedValue]。
     *
     * 用途很实际：连着测十几条时，程序自己的疲劳曲线会把后面全压住，
     * 而人明明刚坐下。锁上就"永远精神"，测完再关掉。
     * **关掉不会丢你已经设好的值** —— 开关只决定用不用。
     */
    var energyLocked: Boolean
        get() = sp.getBoolean(KEY_ENERGY_LOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_ENERGY_LOCKED, value).apply()

    /** 锁定时的精力值（0~1）。只在 [energyLocked] 为真时生效。 */
    var energyLockedValue: Float
        get() = sp.getFloat(KEY_ENERGY_VALUE, 0.85f)
        set(value) = sp.edit().putFloat(KEY_ENERGY_VALUE, value.coerceIn(0f, 1f)).apply()

    /**
     * 固定开场白是否启用。
     *
     * 关掉只是"不用它开口"，**已经填的那些不会删** —— 下次打开还在。
     */
    var greetingEnabled: Boolean
        get() = sp.getBoolean(KEY_GREETING_ON, true)
        set(value) = sp.edit().putBoolean(KEY_GREETING_ON, value).apply()

    /**
     * 固定的开场白，一行一句。**留空则交给模型写。**
     *
     * 为什么需要这个：开场白是最不需要"智能"的一句 —— 它只是一声招呼，
     * 而且同一批人收到同一套招呼，比每人一段风格飘忽的开场更像同一个人。
     * 另外它不消耗 token：一天打几十个招呼，这一项能省掉几十次调用。
     *
     * **一行 = 一条要发出去的消息**（不再三句一组合并连发）。
     * 支持 {昵称} {原话} {时段} 三个槽位，见 [com.autoreply.app.core.llm.GreetingHooks]。
     */
    var greetingLines: List<String>
        get() = (sp.getString(KEY_GREETINGS, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = sp.edit()
            .putString(KEY_GREETINGS, value.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            .apply()

    /**
     * 开场白库的轮换游标。
     *
     * 存在的理由：原来是拿「对方那句话的 hashCode」取模选一条，结果所有说
     * 「你好」的人 hashCode 一样、永远落到同一条 —— 库再长也只有一两条被用到，
     * 等于没有轮换。改成游标之后，第 1 个会话用第 1 条、第 2 个用第 2 条，
     * 走到底再绕回来，20 条就是真的 20 条。
     *
     * 持久化是必要的：不存的话每次重启都从第一条开始，
     * 而用户通常是一次开一晚上 —— 那反而变成"每晚都用同一条开头"。
     */
    var greetingCursor: Int
        get() = sp.getInt(KEY_GREETING_CURSOR, 0)
        set(value) = sp.edit().putInt(KEY_GREETING_CURSOR, value).apply()

    /**
     * 目标平台包名，可多个（一行一个）。
     *
     * 这个产品不是只服务一个平台，所以包名从一开始就是集合而不是单值。
     * 每接一个新平台，在这里加一行包名 + 在 profiles.json 加一段选择器，
     * 不需要改任何 Kotlin 代码。
     */
    var targetPackages: List<String>
        get() = (sp.getString(KEY_TARGET_PKGS, "") ?: "")
            .split('\n', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        set(value) = sp.edit()
            .putString(KEY_TARGET_PKGS, value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))
            .apply()

    /** true 时才允许程序点开别的会话（L1/L2）。L0 下恒为 false。 */
    val allowsOpeningConversation: Boolean
        get() = level >= LEVEL_NOTIFY

    /**
     * 接口地址。分发模式下**强制**用内置的穿透域名，忽略设置页里的一切输入。
     *
     * 这是「其他设备上的 key 隐藏且不可复制」的落点：真 Key 在中转上，成员设备
     * 拿到的只是一枚假 key；连地址都不让改，改成别的中转也换不到真 Key。
     * getter 覆盖而不是改调用处，是因为 LlmClient / 首页 Key 检查 / 设置页
     * 三处都读它 —— 在一个地方收口，比改三个地方更不容易漏。
     */
    var llmBaseUrl: String
        get() = if (distributionEnabled) {
            dist!!.optString("relayUrl").trim()
        } else {
            sp.getString(KEY_LLM_URL, "https://api.deepseek.com/v1") ?: ""
        }
        set(value) = sp.edit().putString(KEY_LLM_URL, value.trim()).apply()

    /**
     * API Key 字段。分发模式下返回 `<deviceId>.<固定假 key>`，正是 relay 的
     * pinned 模式认的令牌格式。
     *
     * 注意它**不是**上游真 Key，只是「入场券」。反编译 APK 拿到它也只能连到
     * 中转、按 deviceId 领一份额度，换不出 sk-xxx。
     */
    var llmApiKey: String
        get() = if (distributionEnabled) {
            "${deviceId}.${dist!!.optString("sharedKey").trim()}"
        } else {
            sp.getString(KEY_LLM_KEY, "") ?: ""
        }
        set(value) = sp.edit().putString(KEY_LLM_KEY, value.trim()).apply()

    /**
     * 模型名。分发模式下如果 distribution.json 里填了 `model`，就用它 ——
     * 成员改成一个上游不认的名字，报错是「模型不存在」，看起来像中转坏了。
     * 没填则沿用默认值（此时成员仍可改，属于有意留的余地）。
     */
    var llmModel: String
        get() {
            if (distributionEnabled) {
                val fixed = dist!!.optString("model").trim()
                if (fixed.isNotEmpty()) return fixed
            }
            return sp.getString(KEY_LLM_MODEL, "deepseek-chat") ?: ""
        }
        set(value) = sp.edit().putString(KEY_LLM_MODEL, value.trim()).apply()

    /**
     * 只给「模型请求」用的代理，例：`http://127.0.0.1:7890` 或 `socks5://127.0.0.1:1080`。
     *
     * 和「手机开系统级 VPN」的区别很大：系统级 VPN 会让**所有 App** 都从代理出去，
     * 目标平台看到的是境外 IP；而这里只有我们发往模型接口的那一次请求走代理，
     * 目标 App 的流量完全不经过它。留空则直连。
     *
     * **分发模式下强制清空**：成员设备的手机不该为了连中转而挂任何代理 ——
     * 那正是「中转方案-手机不挂代理.md」里权重最高的风控信号。中转本身在公网上，
     * 直连即可；留着代理字段反而可能把模型请求导到别人的机器上。
     */
    var llmProxy: String
        get() = if (distributionEnabled) "" else sp.getString(KEY_LLM_PROXY, "") ?: ""
        set(value) = sp.edit().putString(KEY_LLM_PROXY, value.trim()).apply()

    /**
     * 剪贴板交付模式：生成结果**只**放进剪贴板，绝不尝试写输入框。
     *
     * 用于输入框对无障碍/输入法都不可见的平台（爱聊这类）。用户多一步
     * 「长按粘贴 + 自己点发送」，换来的是不依赖任何写入通道 —— 抓不到输入框
     * 也能用。默认关；profiles.json 里标了 preferClipboard 的平台会单独生效。
     */
    var clipboardOnly: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD_ONLY, false)
        set(value) = sp.edit().putBoolean(KEY_CLIPBOARD_ONLY, value).apply()

    /**
     * 是否把聊天记录落到本机（`filesDir/chatlog/日期.txt`）。
     *
     * **默认关**：聊天内容是明文，落盘就等于在手机上留了一份可被翻到的记录。
     * 需要复盘话术效果时再打开，用完可以关掉并清空。
     */
    var chatLogEnabled: Boolean
        get() = sp.getBoolean(KEY_CHAT_LOG, false)
        set(value) = sp.edit().putBoolean(KEY_CHAT_LOG, value).apply()

    /**
     * 上一次由我们写进剪贴板的草稿。
     *
     * 剪贴板模式下还要从剪贴板读「对方说了什么」当上下文。不记下自己刚写的
     * 那句，下一轮就会把它当成对方的新消息，自己回自己 —— 无限循环。
     */
    var lastClipboardDraft: String
        get() = sp.getString(KEY_LAST_CLIP_DRAFT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_CLIP_DRAFT, value).apply()

    /** 模型调用累计次数。只增不清 —— 用来回答"这么测下去要花多少钱"。 */
    val llmCalls: Int
        get() = sp.getInt(KEY_LLM_CALLS, 0)

    /** 累计输入 token（含重写那几次）。 */
    val llmTokensIn: Int
        get() = sp.getInt(KEY_LLM_TOKENS_IN, 0)

    /** 累计输出 token。 */
    val llmTokensOut: Int
        get() = sp.getInt(KEY_LLM_TOKENS_OUT, 0)

    /**
     * 记一次用量。
     *
     * 数值来自服务端返回的 usage，不是估算 —— 估算在"到底花多少"这件事上
     * 误差能有一倍，而免费额度/余额是实打实扣的。
     */
    fun recordUsage(inputTokens: Int, outputTokens: Int) {
        sp.edit()
            .putInt(KEY_LLM_CALLS, llmCalls + 1)
            .putInt(KEY_LLM_TOKENS_IN, llmTokensIn + inputTokens)
            .putInt(KEY_LLM_TOKENS_OUT, llmTokensOut + outputTokens)
            .apply()
    }

    var persona: String
        get() = sp.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(value) = sp.edit().putString(KEY_PERSONA, value).apply()

    /**
     * 设置页粘贴的人设 JSON，**优先级高于 assets/persona.json**。
     *
     * 留空就用内置那份；填了就覆盖。这样调人设不用重新打包 ——
     * 调语气是个反复试的过程，改一次等一次 CI（6 分钟）谁都受不了。
     */
    var personaJson: String
        get() = sp.getString(KEY_PERSONA_JSON, "") ?: ""
        set(value) = sp.edit().putString(KEY_PERSONA_JSON, value.trim()).apply()

    /** 用户自定义的禁止词，一行一个，与内置词表合并。 */
    var bannedWords: String
        get() = sp.getString(KEY_BANNED, "") ?: ""
        set(value) = sp.edit().putString(KEY_BANNED, value).apply()

    /**
     * 回复长度上限（字）。太长不像人打的，也更容易被判定为机器生成。
     *
     * **默认 35 而不是 80。** 80 字会被断句切成 2~3 段，
     * 意味着每处理一个会话要点 2~3 次发送 —— 体感就是"连点不流畅、老得手动"。
     * 而真人聊天一条本来也就十几个字。演示模式之所以"顺"，
     * 是因为它回的都是三五个字的短句，一次就发完。
     *
     * 老版本存的是 80（当时的默认值），这里做一次性迁移 ——
     * 用户几乎不可能主动去动这个数字，80 基本都是没改过的。
     */
    var maxReplyChars: Int
        get() {
            val v = sp.getInt(KEY_MAX_CHARS, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) return 35
            if (v == 80 && !sp.getBoolean(KEY_MAX_CHARS_MIGRATED, false)) {
                sp.edit()
                    .putInt(KEY_MAX_CHARS, 35)
                    .putBoolean(KEY_MAX_CHARS_MIGRATED, true)
                    .apply()
                return 35
            }
            return v
        }
        set(value) = sp.edit()
            .putInt(KEY_MAX_CHARS, value.coerceIn(20, 300))
            .putBoolean(KEY_MAX_CHARS_MIGRATED, true)
            .apply()

    companion object {
        const val LEVEL_FILL_ONLY = 0
        const val LEVEL_NOTIFY = 1
        const val LEVEL_POLL = 2

        const val DEFAULT_PERSONA = """你是一个真实的人在手机上聊天。

说话要求：
- 短句，一次只说一件事，不要分点罗列
- 不用书面语，不用"首先/其次/综上"这类词
- 不用感叹号堆情绪，不卖萌，不用表情符号
- 不说客套话，不重复对方刚说过的话
- 不确定的事就说不知道，绝不编造
- 只输出对方会看到的那句话，不要任何解释、引号或前缀"""

        private const val KEY_LEVEL = "level"
        private const val KEY_HOURLY_CAP = "hourly_cap"
        private const val KEY_BURST = "burst"
        private const val KEY_COOLDOWN_MIN = "cooldown_min"
        private const val KEY_GAP_SEC = "gap_sec"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_DRILL = "drill_mode"
        private const val KEY_DRILL_NUDGE = "drill_nudge"
        private const val KEY_IME_EXPANDED = "ime_expanded"
        private const val KEY_IME_PAGE = "ime_page"
        private const val KEY_TALK_STYLE = "talk_style"
        private const val KEY_GREETINGS = "greeting_lines"
        private const val KEY_GREETING_ON = "greeting_enabled"
        private const val KEY_GREETING_CURSOR = "greeting_cursor"
        private const val KEY_ENERGY_LOCKED = "energy_locked"
        private const val KEY_TIME_OF_DAY = "time_of_day"
        private const val KEY_ENERGY_VALUE = "energy_locked_value"
        private const val KEY_TARGET_PKGS = "target_pkgs"
        private const val KEY_LLM_URL = "llm_url"
        private const val KEY_LLM_KEY = "llm_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_PROXY = "llm_proxy"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CLIPBOARD_ONLY = "clipboard_only"
        private const val KEY_CHAT_LOG = "chat_log_enabled"
        private const val KEY_LAST_CLIP_DRAFT = "last_clip_draft"
        private const val KEY_LLM_CALLS = "llm_calls"
        private const val KEY_LLM_TOKENS_IN = "llm_tok_in"
        private const val KEY_LLM_TOKENS_OUT = "llm_tok_out"
        private const val KEY_PERSONA = "persona"
        private const val KEY_PERSONA_JSON = "persona_json"
        private const val KEY_BANNED = "banned"
        private const val KEY_MAX_CHARS = "max_chars"
        private const val KEY_MAX_CHARS_MIGRATED = "max_chars_migrated"
    }
}
