package com.autoreply.app.core.llm

import com.autoreply.app.core.persona.Persona

/**
 * 演练模式的本地"生成"：不联网、不花钱，但**用你注入的问答样例拼回复**。
 *
 * ## 为什么不能只用固定几句话
 *
 * 固定输出（「哈哈 是」永远不变）只能验证"管道通不通"，
 * **验证不了续聊** —— 因为每一轮的回复和对方说了什么完全无关，
 * 看不出上下文到底有没有被用上。而续聊恰恰是最容易出问题的一环：
 * 对方问工作，回了个"哈哈是"；对方暗示，回了个"哈哈是"。
 *
 * 这里改成查表：拿对方最后一句，在你自己的 few_shot 里找最接近的一条，
 * 用它对应的答法。于是：
 *   - 内容来自你的话术，不是凭空编的
 *   - 不同的话会得到不同的回复 → **能看出"是不是真的接上了"**
 *   - 一分钱不花、一秒不用等
 *
 * 它当然不会像模型那样组合上下文，但演练模式测的是**流程**，不是文采。
 *
 * ## 会故意"多说一句"
 *
 * 开场和多轮的隔轮会各补一句（「在吗？」「你呢」），返回时用**换行**连接 ——
 * 外面 SentenceSplitter 把换行当硬拆点，于是能测到"发完第一条自动填第二条"
 * 这条链路。真人本来就很少一问一答地卡着发。
 */
object DrillReplier {

    /**
     * 低于这个相似度就认为样例里没有对应说法，改用追问。
     *
     * 0.25 而不是 0.34：0.34 时「今天好累啊」这种话十条有八条匹配不上，
     * 全走兜底的「你是指什么」—— 演练起来像在敷衍。宁可匹配得松一点，
     * 因为它的用途是"跑流程/看有没有接上"，不是产出最终话术。
     */
    private const val MATCH_THRESHOLD = 0.25

    /** 对方说的东西样例里没覆盖到时的接法 —— 照着「不明确就问」的准则来。 */
    private val FOLLOW_UP = listOf("怎么说", "然后呢", "你是指什么", "哦哦 具体点")

    /** 开场补的第二句。真人打招呼很少只说一句就等回音。 */
    private val OPENERS = listOf("在吗？", "你在忙不", "想问个事")

    /**
     * 对方说了一长段时的接法：表示在听，不再追问。
     *
     * 「哦哦 具体点」用在长段自述后面非常敷衍 —— 人家讲了半天，
     * 你回一句"具体点"，像没听。
     */
    private val ACK = listOf("这样啊", "那挺不容易的", "我懂你说的", "也是啊", "听你这么说")

    /** 多轮里隔一轮补的追问，免得整段像一问一答的客服。 */
    private val TAILS = listOf("你呢", "你平时忙不忙", "你做什么的")

    /**
     * @param round 当前会话里对方说过几句。用来在同一条样例有多个答法时轮换，
     *   否则同一个问题每次都给同一句话，看起来还是"卡住了"。
     */
    fun reply(persona: Persona, lastOther: String, round: Int): String {
        val target = lastOther.trim()
        if (target.isEmpty()) return "嗯"

        // **先查表**。当初把"短消息直接走应声池"写在前面，
        // 结果「你好」「在吗」这两个最常开场的两句被截在查表之前，
        // 一律回「咋了」「嗯？」—— 反而比原来更不像人。
        // 短消息同样可能有对应样例，顺序应该是"查不到再兜底"。
        val best = persona.fewShot
            .filter { it.q.length >= 2 }
            .maxByOrNull { coverage(target, it.q) }

        val score = best?.let { coverage(target, it.q) } ?: 0.0

        // 开场（对方说的第一句）：同一句话如果配了多条答法，**全都发出去**。
        // 「你好」在你的样例里既对应「突然有点想认识你」也对应「在吗？」——
        // 真人打招呼就是这样两条连着发，而不是挑一条等着。
        if (best != null && score >= MATCH_THRESHOLD && round <= 1) {
            val sameQ = persona.fewShot.filter { it.q == best.q }
            if (sameQ.size > 1) {
                // 换行分隔，不用句号 —— 答法本身可能已经以「。」「？」收尾，
                // 再补一个句号就成了「？。」（同一个坑，见 ReplyEngine 里的开场白）
                return sameQ.joinToString("\n") { it.a }
            }
        }

        val answer = if (best != null && score >= MATCH_THRESHOLD) {
            // 多轮里同一个问题可能配了好几种答法，轮换着用
            val sameQ = persona.fewShot.filter { it.q == best.q }
            sameQ[round % sameQ.size].a
        } else {
            null
        }

        if (answer == null) {
            return when {
                target.length <= 2 -> listOf("怎么了", "啥", "嗯？", "你说")[round % 4]
                // 对方说了一长段（倾诉、讲经历），回一句「哦哦 具体点」很敷衍 ——
                // 那种时候该表示在听，而不是继续追问。
                target.length > 12 -> ACK[round % ACK.size]
                else -> FOLLOW_UP[round % FOLLOW_UP.size]
            }
        }

        // ---- 让它像"真人连发两条"，而不是一问一答的客服 ----
        //
        // 两句之间放**换行**（不是句号），外面 SentenceSplitter 把换行当硬拆点、
        // 拆成 2 段，于是"填第一条 → 你点发送 → 自动填第二条"。整套断句机制直接复用。
        //
        // 为什么不用句号当连接符：答法自己可能就以「。」或「？」收尾，
        // 补一个句号会拼出「？。」这种双标点（固定开场白踩过同一个坑）。
        //
        // 只在两种场合补第二句：
        //   1. 开场（对方说的第一句）—— 真人打招呼很少只说一句就等回音
        //   2. 多轮里隔一轮 —— 每轮都补会显得刻意
        val hasQuestion = answer.contains("？") || answer.contains("?")
        val alreadyAsks = answer.contains("你")

        if (round <= 1 && !hasQuestion) {
            return answer + "\n" + OPENERS[round % OPENERS.size]
        }
        if (round % 2 == 0 && !hasQuestion && !alreadyAsks) {
            return answer + "\n" + TAILS[round % TAILS.size]
        }
        return answer
    }

    /**
     * 字符二元组覆盖率：判断两句话"说的是不是同一类事"。
     *
     * 和 NecessityJudge 里的同名逻辑一致 —— 那边用它判断"这句话人设该不该接"，
     * 这边用它找"最像的那条样例"。同一个度量，两处用途。
     */
    private fun coverage(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val grams = HashSet<String>()
        for (i in 0 until b.length - 1) grams.add(b.substring(i, i + 2))
        if (grams.isEmpty()) return 0.0
        var hit = 0
        for (i in 0 until a.length - 1) {
            if (grams.contains(a.substring(i, i + 2))) hit++
        }
        return hit.toDouble() / (a.length - 1).coerceAtLeast(1)
    }
}
