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

    /** 每小时最多处理几条。超过就停到下个小时，不做"攒着补发"。 */
    var hourlyCap: Int
        get() = sp.getInt(KEY_HOURLY_CAP, 6)
        set(value) = sp.edit().putInt(KEY_HOURLY_CAP, value).apply()

    /** 连续处理几条后强制长冷却。 */
    var burstLimit: Int
        get() = sp.getInt(KEY_BURST, 3)
        set(value) = sp.edit().putInt(KEY_BURST, value).apply()

    /** 长冷却时长（分钟），下限 10。 */
    var cooldownMinutes: Int
        get() = sp.getInt(KEY_COOLDOWN_MIN, 20)
        set(value) = sp.edit().putInt(KEY_COOLDOWN_MIN, value.coerceAtLeast(10)).apply()

    /** 每条之间的基础间隔（秒），实际会乘一个 0.7~1.8 的随机系数。 */
    var minGapSeconds: Int
        get() = sp.getInt(KEY_GAP_SEC, 45)
        set(value) = sp.edit().putInt(KEY_GAP_SEC, value.coerceAtLeast(30)).apply()

    /** 目标平台包名。写入后需要同步改 AndroidManifest 的 queries。 */
    var targetPackage: String
        get() = sp.getString(KEY_TARGET_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_TARGET_PKG, value.trim()).apply()

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

    var persona: String
        get() = sp.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(value) = sp.edit().putString(KEY_PERSONA, value).apply()

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
        private const val KEY_TARGET_PKG = "target_pkg"
        private const val KEY_LLM_URL = "llm_url"
        private const val KEY_LLM_KEY = "llm_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_PERSONA = "persona"
        private const val KEY_BANNED = "banned"
        private const val KEY_MAX_CHARS = "max_chars"
    }
}
