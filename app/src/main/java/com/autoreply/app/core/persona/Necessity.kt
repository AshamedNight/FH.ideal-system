package com.autoreply.app.core.persona

/**
 * 该不该开口。
 *
 * 这一层解决的是"要不要回"，不是"回什么"。它的价值不在回复质量，而在**沉默**：
 * 没有任何真人会 24 小时秒回每一条消息，而"每条都回"恰恰是最好识别的机器特征。
 *
 * 三档输出：
 *   REPLY  现在回
 *   WAIT   想回，但稍后（交给精力状态决定延后多久）
 *   SILENT 这条不参与，连草稿都不生成
 *
 * 纯函数，不碰 Android、不碰网络，可以直接单测 —— 判定错一次就是一次不该发的话被发出去。
 */
object NecessityJudge {

    enum class Action { REPLY, WAIT, SILENT }

    data class Judgment(
        val score: Double,
        val action: Action,
        val reasons: List<String>,
    )

    /**
     * 阈值为什么是 0.45 / 0.25 而不是 0.60 / 0.30：
     *
     * 「私聊 +0.40」和「有一定长度 +0.10」加起来正好 0.50 —— 这是最常见的场景
     * （对方在私聊里说了一句正常话）。按 0.60 的门槛它永远回不了，只能一直"先等等"。
     * 真机实测就是这样：对方说「约一下放松放松」，总分 0.50，被判"等 34 分钟"。
     *
     * 该沉默的情况不靠这条线拦：「嗯」「哦」这类纯语气词在上面就短路了，
     * 单字另有 -0.20。阈值只负责决定"一句正常话要不要回"。
     */
    private const val THRESHOLD_REPLY = 0.45
    private const val THRESHOLD_WAIT = 0.25

    /** 一对一场景里，对方叫你就必须应声。 */
    private val SUMMON = listOf("在吗", "在不在", "在么", "hello", "hi", "你好", "有人吗", "说句话")

    /**
     * 纯语气词。**优先级最高，被叫到也照样沉默。**
     * 「@你 6」这种如果回了，是最典型的机器人行为。
     */
    private val FILLER = Regex(
        "^\\s*(嗯+|哦+|噢+|喔+|好+|行|可以|收到|ok|OK|okay|哈哈+|hhh+|嘿嘿+|额+|啊这|草|6+|1+)[。.!！~～…\\s]*$"
    )

    private val QUESTION = Regex("[?？]|吗|呢|多少|怎么|咋|能不能|可不可以|有没有|是不是|为什么|多久|几个|几种|哪儿|哪里")

    /**
     * 负面内容不接。骂人的消息回什么都像在吵架，
     * 而且一旦被截图传出去，比不回难看得多。
     */
    private val NEGATIVE = listOf(
        "傻逼", "傻B", "煞笔", "滚", "去死", "废物", "垃圾", "脑残", "贱人", "你妈",
    )

    private val TIME_ONLY = Regex("^\\d{1,2}:\\d{2}$")

    /**
     * 给一条消息打激活度分数。
     *
     * @param isPrivate 私聊场景加 0.40 —— 一对一被找来，装看不见没有道理
     * @param selfSaidAgoSeconds 距自己上次发言多少秒；<60 且不是被叫到时扣分（抢话惩罚）
     */
    fun judge(
        text: String,
        persona: Persona,
        isPrivate: Boolean = true,
        selfSaidAgoSeconds: Int = Int.MAX_VALUE,
    ): Judgment {
        val t = text.trim()
        val reasons = ArrayList<String>()

        if (t.isEmpty()) return Judgment(0.0, Action.SILENT, listOf("空消息"))
        if (TIME_ONLY.matches(t)) return Judgment(0.0, Action.SILENT, listOf("时间戳"))

        // ---- 1. 纯语气词：直接沉默，被叫到也一样 ----
        if (FILLER.matches(t)) {
            return Judgment(0.0, Action.SILENT, listOf("纯语气词，没有信息量"))
        }

        // ---- 2. 负面内容：不接 ----
        val negative = NEGATIVE.firstOrNull { t.contains(it) }
        if (negative != null) {
            return Judgment(0.0, Action.SILENT, listOf("负面内容（$negative），不接"))
        }

        // ---- 3. 私聊里被叫到：短路回复 ----
        val summoned = SUMMON.any { t.lowercase().contains(it) }
        if (isPrivate && summoned) {
            return Judgment(1.0, Action.REPLY, listOf("私聊呼唤，必答"))
        }

        // ---- 4. 基础分 ----
        var score = 0.0

        if (QUESTION.containsMatchIn(t)) {
            score += 0.35
            reasons.add("含问句 +0.35")
        }

        // 命中人设知识库的条目 = 对方在问"人设该管的事"
        val kbHit = persona.knowledge.firstOrNull { item ->
            item.keys.any { k -> k.isNotEmpty() && t.contains(k) }
        }
        if (kbHit != null) {
            score += 0.30
            reasons.add("命中知识「${kbHit.topic}」+0.30")
        }

        // 与少样本语料相近 = 这句话人设本来就该接。2 字以内的语料不参与（覆盖率虚高）
        val bestCoverage = persona.fewShot
            .filter { it.q.length >= 3 }
            .maxOfOrNull { coverage(t, it.q) } ?: 0.0
        if (bestCoverage >= 0.55) {
            score += 0.25
            reasons.add("与示例相近（覆盖 ${(bestCoverage * 100).toInt()}%）+0.25")
        }

        if (t.length >= 5) {
            score += 0.10
            reasons.add("有一定长度 +0.10")
        }

        // ---- 5. 场景分 ----
        if (isPrivate) {
            // 0.48 而不是 0.40：单聊里对方刚说完话，本来就该接。
            // 0.40 时"那挺好啊"这种正常接话只有 0.40 分，差 0.05 不够 REPLY 阈值(0.45)，
            // 被判成"想回但先等" —— 一串对话就断在这里，表现就是"太保守"。
            // 单字（-0.20）仍然会压到 WAIT 以下，该沉默的场合不受影响。
            score += 0.48
            reasons.add("私聊 +0.48")
        }

        // **单字不再统一 -0.20。**
        //
        // 原来一律扣分，真机踩到：对方回一个「在」，0.48-0.20=0.28，
        // 差 REPLY 阈值 0.45 一截，被判"想回但先等 4 分钟" ——
        // 人家明明应了你一声，程序反而不回了，这是最反直觉的一种沉默。
        //
        // 而且真正的敷衍（嗯/哦/好/行/哈哈/草）在上面 FILLER 那一步
        // 已经短路成沉默了。能走到这里的单字基本是「在」「是」「对」，
        // 那些都是应答，罚它们没有道理。

        // 抢话惩罚：只罚"极端连发"。
        //
        // 原来是 60 秒内 -0.25，误伤严重：对方回你一句、你六秒前刚发过，
        // 就直接被判成"先等等"，可这明明是一问一答该接的时候。
        // 真要防重复，dedup 已经在"同一条消息 30 分钟内不重复处理"那层挡住了，
        // 这里只保留 10 秒这个真正像刷屏的窗口。
        if (!summoned && selfSaidAgoSeconds < 10) {
            score -= 0.15
            reasons.add("${selfSaidAgoSeconds} 秒前刚说过话 -0.15")
        }

        val final = score.coerceIn(0.0, 1.0)
        val action = when {
            final >= THRESHOLD_REPLY -> Action.REPLY
            final >= THRESHOLD_WAIT -> Action.WAIT
            else -> Action.SILENT
        }
        reasons.add("总分 ${"%.2f".format(final)}")
        return Judgment(final, action, reasons)
    }

    /** 字符二元组覆盖率：判断两句话"说的是不是同一类事"。 */
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
