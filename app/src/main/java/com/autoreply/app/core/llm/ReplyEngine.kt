package com.autoreply.app.core.llm

import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.persona.Persona
import kotlin.random.Random

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
            /**
             * 这句已经是一条写好的完整消息，**不要再交给 SentenceSplitter 拆**。
             *
             * 只有固定开场白会置 true。库里的每一行是连着写的（铺垫 + 问句），
             * "做手工做到现在，你有上瘾的事没" 这种按逗号拆成两条发出去，
             * 用户只点一次发送就只发出前半句 —— 问句丢了，这句开场白等于白发。
             */
            val single: Boolean = false,
            /**
             * 已经切好的多条消息（目前只有「多发开场白」会用到）。
             *
             * 非空时 `text` 是这几条拼起来的全文（给人看），真正填字按这个列表
             * 一条一条来：先填第一条，剩下的进队列，发出去再填下一条。
             */
            val parts: List<String> = emptyList(),
            /**
             * 这句是**哪来的**：模型 / 话术库 / 开场白库 / 演练。
             *
             * 为什么要标：库和开场白这两条路**不调模型、不花 token**，
             * 但判定链原来一律写"第 1 次成稿"，用户看到"本次 0 token"就以为模型没接上。
             * 更糟的是他会拿库里的句子去和输入框里的模型输出对账 —— 永远对不上。
             */
            val source: String = "模型",
        ) : Result()
        /** 不该回：要么没有对方的消息，要么触了输入侧边界。 */
        data class Blocked(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 同步执行，会阻塞（网络请求）。调用方必须放在后台线程。
     *
     * @param peer 对方昵称，只给开场白的 `{昵称}` 槽位用。读不到就传空 ——
     *   带该槽位的那几条会被跳过，而不是把花括号发出去。
     * @param facts 「关于对方」的事实（"28 岁"、"在淄博"、"做美容"…），来自
     *   [com.autoreply.app.core.memory.SessionStore]。放这里而不是 persona 里：
     *   persona 是"我是谁"，这些是"她是谁"，而且按会话变 ——
     *   混进 persona 会让每换一个人都重建那份大提示词。
     */
    fun generate(
        turns: List<Turn>,
        peer: String = "",
        facts: List<String> = emptyList(),
    ): Result {
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

        // 还有一个条件：**对方先说的得是招呼**。
        //
        // 只看"我没说过话"是不够的 —— 心遇这类平台对方可能一上来就问
        // 「你多大」「哪的人」，那是具体问题，该正面答，
        // 拿模板开场白糊过去等于答非所问（对方会觉得你在敷衍）。
        val peerOpenedWithGreeting = turns.lastOrNull { it.fromOther }
            ?.let { isJustGreeting(it.text) } == true

        val echo = turns.lastOrNull { it.fromOther }?.text.orEmpty()
        if (config.greetingEnabled && !iSaidAlready && peerOpenedWithGreeting) {
            // ---- 多发开场白：用户自己写的第一/二/三条，按顺序一条一条填 ----
            //
            // 优先级高于开场白库：勾了「多发」就是明确要用这三条。
            // 依然遵守"一条一条来"—— 这里只决定要发哪几句，
            // 什么时候填第二条由 DraftQueue + 发送检测决定，绝不连发。
            if (config.greetingMulti) {
                val filled = config.greetingMultiLines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    // 槽位填不上（整条只有 {昵称}、而昵称没读到）就跳过这条，
                    // 和开场白库同一套规则 —— 绝不把花括号发出去
                    .mapNotNull { GreetingHooks.fill(it, peer, echo, timeWord()) }
                if (filled.isNotEmpty()) {
                    return Result.Draft(
                        text = filled.joinToString("\n"),
                        attempts = 1,
                        single = true,
                        // 只有一条时 parts 留空，走"单条"那条老路更简单
                        parts = if (filled.size > 1) filled else emptyList(),
                        source = "开场白（多条）",
                    )
                }
                // 三条全空或全填不上 → 往下走库 / 模型，别让用户对着空白卡住
            }

            // 用户自己配的库优先。
            //
            // 这一段的旧实现是 `chunked(3)` + 按「对方那句话的 hashCode」取模选组，
            // 两个后果都不好：所有说「你好」的人 hashCode 相同、永远同一组（轮换形同虚设）；
            // 而且对方还没回就连发三条，是整套行为里最像脚本的一种。
            // 现在按游标依次取，一条库项 = 一条消息。
            pickGreeting(greetings, peer, echo)?.let {
                return Result.Draft(it, 1, 0, 0, 0, single = true, source = "话术库")
            }

            // 库为空、但开着演练模式 → 用**内置钩子库**兜底。
            //
            // 不兜底会怎样：演练模式退去 few_shot 里查 `q=你好`，命中
            // 「突然有点想认识你」「在吗？」这类**开场型样例**，于是每开一个新会话
            // 都把同一句开场白再说一遍。用户清空开场白库的本意就是"别再发那句"，
            // 结果被样例接了回来 —— 这正是"改了设置还在发旧话术"的成因之一。
            if (greetings.isEmpty() && config.drillMode) {
                pickGreeting(GreetingHooks.DEFAULT, peer, echo)?.let {
                    return Result.Draft(it, 1, 0, 0, 0, single = true, source = "开场白库")
                }
            }
            // 配了却一条都填不上（比如整库带 {昵称}、而昵称没读到）→ 交给模型写。
            // 这一路会多花一次 token，但比发一条带花括号的话强。
        }

        // 长度跟着对方走：对方说了一大段，回一句 15 字会显得敷衍；
        // 对方就几个字，回 70 字又像在写小作文。
        // 这是真人最自然的节奏，也是"接不上话"和"啰嗦"两个毛病的同一个解。
        //
        // 分条（偶尔）：掷一次骰子，命中就让模型把它拆成 2~3 条短消息。
        // **掷骰子只发生在这一处**，所以同一次生成里重试的几次都保持同一策略，
        // 不会"第一次单条、重写时又变三条"。
        val burst = config.burstChance > 0 && Random.nextInt(100) < config.burstChance
        // system 里只放**稳定**的字数上限（config 的基准值）。
        // 自适应出来的那个数是随对方消息长度变的，放进 system 等于每次都换一份前缀 ——
        // 上游按字面命中缓存，一个字变了就整段重算，费用照样翻倍。
        // 自适应值改在 buildUser 里给（见"这一条的长度"那一段）。
        val system = buildSystem(persona, config.maxReplyChars)
        val baseUser = buildUser(turns, burst, facts)
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
                // 每生成一次把扰动 +1：同一条消息连点两次，取到的不是同一条。
                // 只靠 round（对方说过几句）不够 —— 同一条消息重复生成时它不变，
                // 于是三次都给同一句，输入框里还叠成「怎么说怎么说怎么说」。
                val nudge = config.drillNudge
                config.drillNudge = nudge + 1
                LlmClient.Result.Ok(
                    DrillReplier.reply(
                        persona, last.text, turns.count { it.fromOther }, nudge,
                        // 二次招呼不能再走"查表拿开场白"：样例里 q=你好 的答案是
                        // 「突然有点想认识你」，那是开场白，已经说过一遍了
                        alreadyTalking = iSaidAlready,
                        peerJustGreeted = peerOpenedWithGreeting,
                    )
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
            if (hit == null) {
                // 分条：模型按提示用换行分开的几句，各自是一条要发出去的消息。
                // 只有真的拆出 2 条以上才走 parts —— 只回了一条就和普通回复完全一样。
                if (burst) {
                    val parts = text.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(config.burstMax)
                    if (parts.size >= 2) {
                        return Result.Draft(
                            text = parts.joinToString("\n"),
                            attempts = attempt,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            estimatedInputTokens = estIn,
                            single = true,
                            parts = parts,
                            source = sourceLabel(),
                        )
                    }
                }
                return Result.Draft(text, attempt, inTokens, outTokens, estIn, source = sourceLabel())
            }
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
     * 这句来自哪里。判定链要显示它 ——
     * "本次 0 token" 只有配上"来源：话术库"才解释得通；
     * 否则用户会以为"接了模型但没生效"，然后拿库里的句子去和输入框对账。
     */
    private fun sourceLabel(): String = if (isMockModel) "演练" else "模型"

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

    /**
     * 从库里取一条**能填满槽位**的开场白，并把轮换游标前移。
     *
     * 槽位填不上就试下一条 —— 绝不能把「{昵称}，这名字谁给你起的」原样发出去，
     * 一次就够毁掉这个号。昵称是纯数字系统号时 [GreetingHooks.fill] 也会返回 null。
     *
     * @return null = 整库一条都填不上（那就交给模型，不硬发）
     */
    private fun pickGreeting(lines: List<String>, peer: String, echo: String): String? {
        if (lines.isEmpty()) return null
        val start = config.greetingCursor
        for (offset in lines.indices) {
            val i = (start + offset) % lines.size
            val filled = GreetingHooks.fill(lines[i], peer, echo, timeWord()) ?: continue
            config.greetingCursor = (i + 1) % lines.size
            return filled
        }
        return null
    }

    /**
     * 小时数 + 时段词。两处都要用（给模型的完整时间描述、开场白的 `{时段}` 槽位），
     * 拆出来是为了让"设置里手动指定时段"这件事只有一处实现 ——
     * 两份 `when` 早晚会走偏，那时候模型以为现在是晚上、开场白却写着"凌晨"。
     */
    private fun hourAndPeriod(): Pair<Int, String> {
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
        return hour to period
    }

    /** 只取时段词，给开场白的 `{时段}` 槽位。 */
    private fun timeWord(): String = hourAndPeriod().second

    /** 给模型看的"现在几点"，顺带把明显不合时宜的说法堵住。 */
    private fun nowDescription(): String {
        val cal = java.util.Calendar.getInstance()
        val (hour, period) = hourAndPeriod()
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
        // 说话风格。「自然」的 hint 是空串 —— 不加指令就是原来的行为，
        // 所以老用户升级上来提示词一个字都没变（前缀缓存也不用重建）。
        //
        // 位置在 persona 的 styleRules **之前**：后面那段里通常写着
        // 「每句不超过 X 字」这类硬约束，让它排在风格之后发声，风格就压不过长度。
        val styleHint = TalkStyle.hintOf(config.talkStyle)
        if (styleHint.isNotEmpty()) {
            appendLine()
            appendLine("# 这一次的语气（只影响怎么说，不影响说什么）")
            appendLine(styleHint)
        }

        appendLine()
        appendLine(persona.styleRules(maxChars))

        // 不重复提问。这条规则是固定的，不随对话变 —— 前缀缓存照样能命中。
        //
        // 真机表现（截图）：对方说「在家玩手机」→「看短剧」→「甜的」，
        // 回了两轮之后又问「那你平时刷手机都看啥」。信息全是它自己问出来的，
        // 却像没听过一样又绕回去 —— 这不是"没上下文"，是没把上下文用起来。
        appendLine()
        appendLine("# 别把聊过的话题当新话题")
        appendLine(
            "对方已经回答过的事，不要换个说法再问一遍（刚说了在看短剧，就别再问「平时看什么」）。" +
                "想接着聊，就顺着他的回答往下说一句自己的看法或细节，而不是重新提问。"
        )

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

    private fun buildUser(
        turns: List<Turn>,
        burst: Boolean = false,
        facts: List<String> = emptyList(),
    ): String = buildString {
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

        val shown = turns.takeLast(CONTEXT_TURNS)
        // 「关于对方」：从会话记忆库里读出来的事实，跨会话、跨重启都还在。
        //
        // 放在"更早聊过"之前：它是结论，比"聊过什么话题"重要得多。
        // 措辞上必须压住两件事 —— ① 别像查户口一样逐条复述；
        // ② 和这一屏对话冲突时以对话为准（数据库里可能存着三天前的旧值）。
        if (facts.isNotEmpty()) {
            appendLine("# 关于对方（之前记下的，可能不全）")
            appendLine(facts.joinToString("；"))
            appendLine(
                "和当前话题相关时才自然带一句，别逐条复述、别像查户口。" +
                    "如果最近的对话和上面不一致，以最近的对话为准。"
            )
            appendLine()
        }

        // 更早的内容压成一行提醒。
        //
        // 为什么不是"再多塞几条全文"：token 是每条消息都要付的，历史越长每条越贵。
        // 而"更早"这部分真正的作用只有一个 —— 别把聊过的话题当新话题再问一遍。
        // 所以每条只留 16 字，够认出话题，不够复述。
        val older = turns.dropLast(shown.size).takeLast(OLDER_TURNS)
        if (older.isNotEmpty()) {
            appendLine("# 更早聊过（只用来避免重复提问，不要复述、不要接着这个话题再问）")
            appendLine(
                older.joinToString("；") { t ->
                    (if (t.fromOther) "对方说" else "我说") +
                        "「" + t.text.replace("\n", " ").take(16) + "」"
                }
            )
            appendLine()
        }

        appendLine("# 最近的对话")
        shown.forEach { t ->
            appendLine((if (t.fromOther) "对方：" else "我：") + t.text.replace("\n", " "))
        }
        appendLine()

        // 长度跟着对方走。**放 user 不放 system** —— 这个数随对方消息长度变，
        // 塞进 system 会让前缀缓存每次都失效（按字面命中，一个字变了就整段重算）。
        // system 里留的是 config 的基准值，这里的数字与它不同时以这里为准。
        val cap = adaptiveMaxChars(turns, config.maxReplyChars)
        if (cap != config.maxReplyChars) {
            val otherLen = turns.lastOrNull { it.fromOther }?.text?.length ?: 0
            appendLine("# 这一条的长度")
            appendLine("对方刚说了 $otherLen 字，这条控制在 $cap 字以内（比系统里那个通用上限优先）。")
            appendLine()
        }

        // 偶尔分条。**放在 user 里，不放 system**。
        //
        // 这是按概率出现的指令：塞进 system 的话，命中分条的那 30% 调用会让
        // 整段 system 前缀变掉 —— 上游的前缀缓存全失效，那一次的费用按未命中算。
        // 放 user 的代价是每次都能省下缓存，多出来的几十字本来也每条都要发。
        if (burst) {
            appendLine("# 这一次可以分几条发")
            appendLine("如果这句话本来就有两三个小点，就拆成 2~3 条，每条单独一行：")
            appendLine("- 每条都是能独立成立的短句，不超过 20 字，别用「1.」「-」这类列表符号")
            appendLine("- 顺序按你想说的先后，第一行最要紧")
            appendLine("- 一句话就能说完的，就只回一行 —— 不要为了凑条数硬拆")
            appendLine()
        }

        // 结尾这句决定模型"回谁"。
        //
        // 最后一条如果是**我方**的（刚发完招呼、或用户手动点生成），
        // 写"请回最后这一条"会让它去回自己那句话 —— 真机上看到的就是
        // "刚打招呼，它又写一句，像在自言自语"。这种情况改成"接着说"。
        if (turns.lastOrNull()?.fromOther == true) {
            append("请回最后这一条。")
        } else {
            append("最后一句是我说的，对方还没回。不要回复自己这句话；如果必须写，就接着往下说一句。")
        }
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
         * 8 → 12（更早）→ 16（2026-11-27）：用户反馈"聊长了回复会有些对不上"。
         * 记忆能存 40 条，但只把最近 12 条原样给模型，中间的 20 多条只剩 16 字的压缩提示 ——
         * 于是就出现"刚说过的事又问一遍""接的是前面某句而不是最后一句"。
         * 12 → 16 每轮多约 80 token，按现在的模型价格是千分之几分钱，换接得上很划算。
         */
        const val CONTEXT_TURNS = 16

        /**
         * 「更早聊过」最多带几条（每条截到 16 字）。
         *
         * 6 → 10：这部分的唯一作用是"别把聊过的当新话题再问"，
         * 条数太少时，谈过的话题一多就全被挤掉，模型开始重复提问。
         * 10 条 × 约 20 字 ≈ 200 字，约 130 token。
         */
        const val OLDER_TURNS = 10
    }
}
