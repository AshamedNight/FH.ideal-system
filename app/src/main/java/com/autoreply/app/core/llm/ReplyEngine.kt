package com.autoreply.app.core.llm

import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.persona.Persona

/**
 * 一轮对话。**故意不直接用 platform 的 ChatMessage** ——
 * 那个类带着 Rect（Android 类型），拉进来会把 core 绑在 Android 上，
 * 单测就跑不了了。platform 层负责把 ChatMessage 映射成这个。
 */
data class Turn(val text: String, val fromOther: Boolean)

/**
 * 回复生成。
 *
 * 一次调用做四件事，顺序不能换：
 *   1. 输入侧边界检查 —— 对方提到借钱/报警这类，直接不生成
 *   2. 组装提示词 —— 人设 + 知识库 + 最近几轮
 *   3. 生成，然后清洗（去引号、去前缀、只留一句）
 *   4. 输出侧禁忌词检查 —— 命中就带着"上次错在哪"重写，最多两次
 *
 * 第 1 步和第 4 步是两个方向，不能合并：前者决定"这条不回"，
 * 后者决定"这句不能说"。合成一个词表会导致该沉默的时候回了、该重写的时候沉默了。
 */
class ReplyEngine(
    private val config: AppConfig,
    /**
     * 人设由外部传入，不在这里加载 —— 这样这个类不依赖 Context，
     * 判定逻辑可以脱离 Android 单测。
     */
    private val persona: Persona,
) {

    sealed class Result {
        /** [inputTokens]/[outputTokens] 是这一条**累计**（重写也算钱），网关不返回时为 0。 */
        data class Draft(
            val text: String,
            val attempts: Int,
            val inputTokens: Int = 0,
            val outputTokens: Int = 0,
        ) : Result()
        /** 不该回：要么没有对方的消息，要么触了输入侧边界。 */
        data class Blocked(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 同步执行，会阻塞（网络请求）。调用方必须放在后台线程。
     */
    fun generate(turns: List<Turn>): Result {
        val last = turns.lastOrNull { it.fromOther }
            ?: return Result.Blocked("没有对方发来的消息")

        // 输入侧边界：命中就整条转人工，不生成
        val boundary = persona.boundaries.firstOrNull { last.text.contains(it) }
        if (boundary != null) {
            return Result.Blocked("对方提到「$boundary」，这类话题交给人工")
        }

        val userBanned = config.bannedWords.split('\n', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val system = buildSystem(persona, config.maxReplyChars)
        val baseUser = buildUser(turns)
        val client = LlmClient(config.llmBaseUrl, config.llmApiKey, config.llmModel, config.llmProxy)

        var lastHit = ""
        var inTokens = 0
        var outTokens = 0
        for (attempt in 1..MAX_ATTEMPTS) {
            val user = if (attempt == 1) {
                baseUser
            } else {
                baseUser + "\n\n# 上一次的回答有问题\n" +
                    "上一次用了「$lastHit」这种不该出现的表达。换一种说法重写，不要重复同样的内容。"
            }

            val resp = client.chat(system, user)
            if (resp is LlmClient.Result.Err) return Result.Failed(resp.reason)
            val ok = resp as LlmClient.Result.Ok
            // 重写也要算钱，所以累加而不是覆盖
            inTokens += ok.inputTokens
            outTokens += ok.outputTokens

            val text = clear(ok.text)
            if (text.isEmpty()) {
                lastHit = "空回复"
                continue
            }

            val hit = (persona.tabooWords + userBanned).firstOrNull { text.contains(it) }
            if (hit == null) return Result.Draft(text, attempt, inTokens, outTokens)
            lastHit = hit
        }

        return Result.Failed("连续 $MAX_ATTEMPTS 次都命中禁止内容（最后一次：$lastHit）")
    }

    // ------------------------------------------------------------------ 提示词

    /**
     * 把真正要发出去的提示词原样取出来，用于在没有 Key / 没有网络时检查
     * 「人设到底有没有生效」。
     *
     * 这一步比接模型更能说明问题：如果 few_shot 不在提示词里，
     * 换任何模型都写不出你要的语气，而这一点靠看输出是反推不出来的。
     */
    fun previewSystem(): String = buildSystem(persona, config.maxReplyChars)

    fun previewUser(turns: List<Turn>): String = buildUser(turns)

    /** 假模型标记，UI 用来提示"这不是真模型写的"。 */
    val isMockModel: Boolean
        get() = config.llmBaseUrl.trim().equals("mock", ignoreCase = true)

    private fun buildSystem(persona: Persona, maxChars: Int): String = buildString {
        appendLine(persona.identity.trim())
        appendLine()
        appendLine(persona.styleRules(maxChars))

        if (persona.knowledge.isNotEmpty()) {
            appendLine()
            appendLine("# 你的情况（只能从这里取事实，没有的就说不清楚）")
            persona.knowledge.forEach {
                appendLine("- ${it.topic}：${it.answer}")
            }
        }

        if (persona.fewShot.isNotEmpty()) {
            appendLine()
            appendLine("# 你平时说话的样例（模仿语感，不要照抄内容）")
            // 全部给，**顺序固定**。
            //
            // 原先是 take(8)：用户填了 15 条，只有前 8 条生效 ——
            // 排在后面的（「加个微信吧」「有对象吗」）一次都用不上，
            // 而按顺序补充样例几乎是所有人都会踩的坑。
            //
            // 曾经改成 shuffled() 想让语气别太死板，后来撤回：
            // 大模型的上下文缓存是**按前缀匹配**的，system 提示词只要变一个字，
            // 整段都得按未命中重算 —— 打乱顺序等于每次主动放弃缓存，
            // 输入成本翻好几倍。语气的变化交给 temperature 就够。
            persona.fewShot.forEach {
                appendLine("对方：${it.q}")
                appendLine("你：${it.a}")
            }
        }

        appendLine()
        append("只输出你要说的那一句话。不要解释，不要加引号，不要说\"回复：\"。")
    }

    /**
     * 只给最近几条。
     *
     * 全量塞进去有两个问题：token 变多、模型会去回很早之前的话。
     * 8 条足够覆盖"刚才在聊什么"，再多是噪音。
     */
    private fun buildUser(turns: List<Turn>): String = buildString {
        appendLine("# 最近的对话")
        turns.takeLast(CONTEXT_TURNS).forEach { t ->
            appendLine((if (t.fromOther) "对方：" else "我：") + t.text.replace("\n", " "))
        }
        appendLine()
        append("请回最后这一条。")
    }

    // ------------------------------------------------------------------ 清洗

    /**
     * 模型很容易带这些东西出来：整句被引号包着、"回复："前缀、一口气写三段。
     * 不清洗直接填进输入框，人一眼就能看出是机器写的。
     */
    private fun clear(raw: String): String {
        var t = raw.trim()

        // 整体被引号包着：去掉最外层
        val pairs = listOf('"' to '"', '“' to '”', '「' to '」', '\'' to '\'')
        for ((open, close) in pairs) {
            if (t.length >= 2 && t.first() == open && t.last() == close) {
                t = t.substring(1, t.length - 1).trim()
            }
        }

        // "回复：xxx" / "回答：xxx"
        t = t.replace(Regex("^(回复|回答|答复|消息)[:：]\\s*"), "")

        // 空段落之后的内容不要：真人不会一次发一整篇
        if (t.contains("\n\n")) t = t.substringBefore("\n\n")

        // 多行但都是短句时保留（有人习惯分行发），太长才截
        if (t.length > config.maxReplyChars * 2) {
            t = t.substring(0, config.maxReplyChars * 2)
        }

        return t.trim()
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val CONTEXT_TURNS = 8
    }
}
