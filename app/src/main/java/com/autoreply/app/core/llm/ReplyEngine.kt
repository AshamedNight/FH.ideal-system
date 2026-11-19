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
        /**
         * [inputTokens]/[outputTokens] 是这一次**累计**（重写也算钱），来自服务端 usage，网关不返回时为 0。
         *
         * [estimatedInputTokens] 是本地按字符估的，只用来和服务端的数字做对照 ——
         * 中转/网关有可能改写 usage（把自带的 system prompt、工具定义一起算进去），
         * 那时 4301 这种数字就跟"我们发了多少"没关系了，成本判断也就失真。
         */
        data class Draft(
            val text: String,
            val attempts: Int,
            val inputTokens: Int = 0,
            val outputTokens: Int = 0,
            val estimatedInputTokens: Int = 0,
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

        // 固定开场白：这个会话我还没说过话，而你配了开场白 —— 直接用，不调模型。
        // 省 token 只是顺带的好处，主要价值是"同一批人收到同一套招呼"，
        // 比每人一段风格飘忽的开场更像同一个人。
        val iSaidAlready = turns.any { !it.fromOther }
        val greetings = config.greetingLines
        if (config.greetingEnabled && !iSaidAlready && greetings.isNotEmpty()) {
            // **两句一组**：你填「突然想认识你」「在吗？」就是要连发这两句，
            // 不是从里面挑一句。用「。」连起来，外面会自动拆成两条。
            // 填了 4 句就分成 2 组，按昵称取模选一组 —— 同一个人始终同一组。
            val groups = greetings.chunked(2)
            val idx = kotlin.math.abs(
                turns.lastOrNull { it.fromOther }?.text.orEmpty().hashCode()
            ) % groups.size
            return Result.Draft(groups[idx].joinToString("。"), 1, 0, 0, 0)
        }

        // 长度跟着对方走：对方说了一大段，回一句 15 字会显得敷衍；
        // 对方就几个字，回 70 字又像在写小作文。
        // 这是真人最自然的节奏，也是"接不上话"和"啰嗦"两个毛病的同一个解。
        val system = buildSystem(persona, adaptiveMaxChars(turns, config.maxReplyChars))
        val baseUser = buildUser(turns)
        val client = LlmClient(effectiveBaseUrl, config.llmApiKey, config.llmModel, config.llmProxy)

        var lastHit = ""
        var inTokens = 0
        var outTokens = 0
        var estIn = 0
        for (attempt in 1..MAX_ATTEMPTS) {
            val user = if (attempt == 1) {
                baseUser
            } else {
                baseUser + "\n\n# 上一次的回答有问题\n" +
                    "上一次用了「$lastHit」这种不该出现的表达。换一种说法重写，不要重复同样的内容。"
            }

            // 每轮都要把 system 一起发，所以估算也每轮累加
            estIn += estimateTokens(system) + estimateTokens(user)

            // 演练模式：不联网，用你注入的问答样例拼回复。
            //
            // 为什么不是固定几句话：固定输出验证不了续聊 —— 每轮回复都跟对方说了
            // 什么无关，看不出上下文有没有被用上。查表拼出来的回复会随对方的话变，
            // 于是"有没有真的接上"一眼就能看出来。
            //
            // 后面的禁止词检查照常走：那也是流程的一部分，跳过就测不全。
            val resp = if (config.drillMode) {
                LlmClient.Result.Ok(
                    DrillReplier.reply(persona, last.text, turns.count { it.fromOther })
                )
            } else {
                client.chat(system, user)
            }
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
            if (hit == null) return Result.Draft(text, attempt, inTokens, outTokens, estIn)
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
    /**
     * 实际用的接口地址。演练模式下强制 "mock"（本地假模型，不联网不花钱），
     * 这样用户不用改设置里的地址，关掉开关就恢复。
     */
    private val effectiveBaseUrl: String
        get() = if (config.drillMode) "mock" else config.llmBaseUrl

    val isMockModel: Boolean
        get() = effectiveBaseUrl.trim().equals("mock", ignoreCase = true)

    /** 演练模式标记。UI 必须显著提示，否则用户会以为这些是模型写的。 */
    val isDrill: Boolean
        get() = config.drillMode

    /**
     * 按对方这条消息的长度决定回复上限。
     *
     * 固定 35 字的问题是两头不讨好：
     * 对方倾诉了一大段（"不是的妹妹，我和你说的是真的…"），回一句「这样啊」像没听；
     * 对方只回一个「嗯」，回 35 字又太热情。
     */
    private fun adaptiveMaxChars(turns: List<Turn>, base: Int): Int {
        val len = turns.lastOrNull { it.fromOther }?.text?.length ?: 0
        return when {
            len >= 40 -> (base * 2).coerceIn(40, 90)
            len >= 15 -> base
            len >= 5 -> (base * 0.8).toInt().coerceAtLeast(15)
            else -> (base * 0.6).toInt().coerceAtLeast(10)
        }
    }

    /** 给模型看的"现在几点"，顺带把明显不合时宜的说法堵住。 */
    private fun nowDescription(): String {
        val cal = java.util.Calendar.getInstance()

        // 设置里手动指定了时段就用那个，否则跟系统时钟。
        // 有些场景系统时间不可信（或者你想让它是"晚上"），手动开关比改系统时间省事。
        val forced = when (config.timeOfDay) {
            "morning" -> "上午" to 10
            "afternoon" -> "下午" to 15
            "evening" -> "晚上" to 20
            "night" -> "深夜" to 23
            else -> null
        }
        val hour = forced?.second ?: cal.get(java.util.Calendar.HOUR_OF_DAY)
        val period = forced?.first ?: when (hour) {
            in 0..4 -> "凌晨"
            in 5..8 -> "早上"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        val weekend = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY -> "，周末"
            else -> ""
        }
        val ban = when (hour) {
            in 9..16 -> "别说\"刚下班\"\"还没下班\"\"下班回家\"这类话，这个点人在上班或午休。"
            in 17..19 -> "可以说下班、路上这类话。"
            in 22..23, in 0..4 -> "可以说准备睡了、熬夜这类话，别说早上好。"
            else -> ""
        }
        return "${period}${hour}点${weekend}。$ban".trim()
    }

    private fun buildSystem(persona: Persona, maxChars: Int): String = buildString {
        appendLine(persona.identity.trim())

        // 注：「现在几点」放在 buildUser 里，不放这里。
        //
        // system 提示词是**按前缀命中缓存**的，里面只要有一个字随时间变
        //（哪怕只是"10点"→"11点"），整段都要按未命中重算，费用翻倍。
        // 挪到 user 消息里：user 本来就每条都不同，缓存损失为零，
        // 而 system 完全稳定 —— 一天下来能省掉几十次全量重算。
        appendLine()
        appendLine(persona.styleRules(maxChars))

        if (persona.knowledge.isNotEmpty()) {
            appendLine()
            appendLine("# 你的情况（只能从这里取事实，没有的就说不清楚）")
            persona.knowledge.forEach {
                appendLine("- ${it.topic}：${it.answer}")
            }
        }

        // 行为准则放在样例**之前**：样例管"说什么"，准则管"怎么接"。
        // 先给准则，模型读样例时会带着这个姿态去模仿；
        // 反过来放在样例后面，它容易被当成"补充说明"而忽略。
        if (persona.rules.isNotEmpty()) {
            appendLine()
            appendLine("# 这几条是这条线上必须守的")
            persona.rules.forEach { appendLine("- $it") }
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
        // 这一条是实测出来的：对方说「你好」，模型十次有八次回「你好啊」——
        // 语义上没错，但放出去就是模板感，而且十个会话的回复一模一样。
        // 明确禁止复述开场白，并给出「往下走的动作」，比调 temperature 有效。
        appendLine("不要复述对方刚说的话。对方说「你好」，就别只回「你好」「你好啊」，")
        appendLine("带一点自己的信息，或者问一个具体的小问题，让话能往下聊。")
        append("只输出你要说的那一句话。不要解释，不要加引号，不要说\"回复：\"。")
    }

    /**
     * 只给最近几条。
     *
     * 全量塞进去有两个问题：token 变多、模型会去回很早之前的话。
     * 8 条足够覆盖"刚才在聊什么"，再多是噪音。
     */
    /**
     * 这句话是不是"纯招呼"。
     *
     * 超过 12 字基本不可能是纯招呼（"你好，我想问问你平时喜欢干什么"是有效内容）。
     */
    private fun isJustGreeting(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        return GREETING_WORDS.any { t.contains(it) }
    }

    private fun buildUser(turns: List<Turn>): String = buildString {
        // 现在几点。放这里而不是 system：system 变动会让整段缓存失效（见上面的注释）。
        // 模型自己没有时钟，不说的话它早上九点也在讲"刚下班"。
        appendLine("# 现在")
        appendLine(nowDescription())
        appendLine()

        // 二次招呼：你们已经在聊了，对方（或者平台的系统招呼）又来一句"你好"。
        //
        // **不跳过，换个方式接**。跳过等于不理人；而回「你好啊」等于重新自我介绍 ——
        // 真人这时候一般会说"刚看到""在呢""刚忙完"，然后接着刚才的话头。
        // 这件事只能靠提示词告诉模型，因为它从样例里学不到这种分寸。
        val lastOther = turns.lastOrNull { it.fromOther }
        val iSaid = turns.any { !it.fromOther }
        if (lastOther != null && iSaid && isJustGreeting(lastOther.text)) {
            appendLine("# 注意")
            appendLine(
                "你们已经在聊了，对方刚又发了一句招呼（「${lastOther.text.take(12)}」）。" +
                    "不要重新打招呼、不要自我介绍，像接着说那样回 —— " +
                    "「刚看到」「在呢」这类，或者直接接着刚才的话题。"
            )
            appendLine()
        }

        appendLine("# 最近的对话")
        turns.takeLast(CONTEXT_TURNS).forEach { t ->
            appendLine((if (t.fromOther) "对方：" else "我：") + t.text.replace("\n", " "))
        }
        appendLine()
        append("请回最后这一条。")
    }

    // ------------------------------------------------------------------ 用量

    /**
     * 粗估 token：中文按 0.6、其余按 0.3（DeepSeek 口径下一个汉字约 0.6 token）。
     *
     * 纯粹用来和服务端返回的数字做对照 —— 它不参与计费，也不该被当成精确值。
     * 但"我们明明只发了 500 token，网关却报 4301"这种落差，只有估算能看出来。
     */
    private fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF) cjk++ else other++
        }
        return (cjk * 0.6 + other * 0.3).toInt()
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

    private val GREETING_WORDS = listOf(
        "你好", "在吗", "在不在", "嗨", "哈喽", "hello", "hi",
        "缘分小助手", "缘分天使", "小助手",
    )

    private companion object {
        const val MAX_ATTEMPTS = 2
        /**
         * 给模型看最近几条。
         *
         * 8 → 12：对方发一条长消息时，8 条里可能大半是「嗯」「好」这类碎片，
         * 真正有用的信息被挤出去了，模型只能靠猜 ——
         * 表现就是"接不上话""回复很模板化"。
         */
        const val CONTEXT_TURNS = 12
    }
}
