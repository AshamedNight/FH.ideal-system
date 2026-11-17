package com.autoreply.app.core

import android.content.Context

/**
 * 全部可调参数集中在这里。
 *
 * 为什么不用 config.json：Android 的私有目录读写要处理权限和路径，
 * 而 SharedPreferences 天生是原子的、跨进程安全、卸载才清。
 * 配置项不多，没必要引入序列化依赖。
 */
class AppConfig(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("autoreply", Context.MODE_PRIVATE)

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
        set(value) = sp.edit().putInt(KEY_HOURLY_CAP, value).apply()

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

    var llmBaseUrl: String
        get() = sp.getString(KEY_LLM_URL, "https://api.deepseek.com/v1") ?: ""
        set(value) = sp.edit().putString(KEY_LLM_URL, value.trim()).apply()

    var llmApiKey: String
        get() = sp.getString(KEY_LLM_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_LLM_KEY, value.trim()).apply()

    var llmModel: String
        get() = sp.getString(KEY_LLM_MODEL, "deepseek-chat") ?: ""
        set(value) = sp.edit().putString(KEY_LLM_MODEL, value.trim()).apply()

    /**
     * 只给「模型请求」用的代理，例：`http://127.0.0.1:7890` 或 `socks5://127.0.0.1:1080`。
     *
     * 和「手机开系统级 VPN」的区别很大：系统级 VPN 会让**所有 App** 都从代理出去，
     * 目标平台看到的是境外 IP；而这里只有我们发往模型接口的那一次请求走代理，
     * 目标 App 的流量完全不经过它。
     *
     * 留空则直连（走电脑中转时留空即可 —— 中转本身在局域网里）。
     */
    var llmProxy: String
        get() = sp.getString(KEY_LLM_PROXY, "") ?: ""
        set(value) = sp.edit().putString(KEY_LLM_PROXY, value.trim()).apply()

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

    /** 回复长度上限（字）。太长不像人打的，也更容易被判定为机器生成。 */
    var maxReplyChars: Int
        get() = sp.getInt(KEY_MAX_CHARS, 80)
        set(value) = sp.edit().putInt(KEY_MAX_CHARS, value.coerceIn(20, 300)).apply()

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
        private const val KEY_TARGET_PKGS = "target_pkgs"
        private const val KEY_LLM_URL = "llm_url"
        private const val KEY_LLM_KEY = "llm_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_PROXY = "llm_proxy"
        private const val KEY_LLM_CALLS = "llm_calls"
        private const val KEY_LLM_TOKENS_IN = "llm_tok_in"
        private const val KEY_LLM_TOKENS_OUT = "llm_tok_out"
        private const val KEY_PERSONA = "persona"
        private const val KEY_PERSONA_JSON = "persona_json"
        private const val KEY_BANNED = "banned"
        private const val KEY_MAX_CHARS = "max_chars"
    }
}
