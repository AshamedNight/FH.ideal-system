package com.autoreply.app.core.guard

/**
 * 把一句回复拆成几条发，模仿真人"想到一句发一句"。
 *
 * ## 为什么这不是"追发"
 *
 * 「在张店这边。常来没。」真人就是这样发的 —— 一条一个念头，
 * 不会凑成一句完整的话再按回车。这种节奏本身就是真人特征，
 * 所以拆分不是增加风险，而是**减少**风险（一整段字正腔圆的长句反而更像模板）。
 *
 * ## 硬约束：绝不丢字
 *
 * 曾经踩过的坑：拆的时候按标点切、再把标点丢掉，结果某一段的最后一个字
 * 被算进了下一段 —— 用户看到的是"上一句少了个字，那个字跑到下一句开头了"。
 * 所以这里的实现是**逐字符搬运**：每个字符必然落进某一段，
 * 拼起来（去掉标点）和原文完全相等。
 */
object SentenceSplitter {

    /**
     * 硬拆点：句子结束了，必拆。
     */
    private val HARD = setOf('。', '！', '？', '…', '!', '?', '\n')

    /**
     * 软拆点：只是句中停顿，**两边都够长才拆**。
     *
     * 不加这个区分会把「电商运营，天天跟数据打交道」拆成两条，
     * 第一条只有「电商运营」—— 一句话被腰斩，读起来像说话卡住了。
     * 而「在张店这边。常来没。」这种句号分隔的该照拆。
     */
    private val SOFT = setOf('，', '；', '、', ',', ';')

    /** 软拆点两边各自至少这么多字才值得断句。 */
    private const val SOFT_MIN_CHARS = 5

    /** 旧的合并口径保留给「去段尾标点」使用。 */
    private val SPLITTERS = HARD + SOFT

    /**
     * 少于这个字数的段不值得单独发，并进相邻段。
     *
     * 2 而不是 4：「常来没」这种三个字的话，真人就是单独发一条的。
     * 门槛设成 4 会把「在张店这边。常来没。」整句并成一条，拆了等于没拆。
     * 设成 2 之后只有单字（"嗯。""好。"）会被合并 —— 那种确实不该单独发。
     */
    private const val MIN_SEGMENT_CHARS = 2

    /** 最多拆几条。拆太碎反而显得毛躁。 */
    private const val MAX_SEGMENTS = 3

    /**
     * @return 1~3 段。原文本身不够长、或没有可拆的标点时，原样返回一条。
     */
    fun split(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return listOf(text)

        // 逐字符搬运：遇到拆点就收一段，标点留在段尾（不丢）
        val pieces = ArrayList<String>(4)
        val sb = StringBuilder()
        for ((i, ch) in text.withIndex()) {
            sb.append(ch)
            when {
                ch in HARD -> {
                    pieces.add(sb.toString())
                    sb.setLength(0)
                }
                ch in SOFT -> {
                    // 软拆点要看两边：任何一边太短就并进同一段，只当普通字符
                    val head = contentLength(sb.toString())
                    val rest = text.substring(i + 1)
                    val nextHard = rest.indexOfFirst { it in HARD }
                    val tail = contentLength(if (nextHard < 0) rest else rest.substring(0, nextHard + 1))
                    if (head >= SOFT_MIN_CHARS && tail >= SOFT_MIN_CHARS) {
                        pieces.add(sb.toString())
                        sb.setLength(0)
                    }
                }
            }
        }
        if (sb.isNotEmpty()) pieces.add(sb.toString())

        if (pieces.size <= 1) return listOf(text)

        // 太短的段并进前一段。第一段短就留着（没有前一段可并，且它通常是有意义的开头）
        val merged = ArrayList<String>(pieces.size)
        for (p in pieces) {
            if (merged.isEmpty()) {
                merged.add(p)
            } else if (contentLength(p) < MIN_SEGMENT_CHARS) {
                merged[merged.size - 1] = merged.last() + p
            } else {
                merged.add(p)
            }
        }

        // 超过上限：后面的全部并进最后一段，而不是丢掉
        val result = if (merged.size > MAX_SEGMENTS) {
            merged.take(MAX_SEGMENTS - 1) + merged.drop(MAX_SEGMENTS - 1).joinToString("")
        } else {
            merged
        }

        // 段尾的逗号/分号要去掉：真人断句时不会留一个悬空的逗号
        //（「你好啊，」看起来像话没说完）。句号、问号、感叹号保留 ——
        // 那是句子收尾，留着更像随手打的。
        return result
            .map { seg ->
                val t = seg.trim()
                if (t.count { !it.isWhitespace() } > 1) {
                    t.trimEnd('，', ',', '；', ';', '、', ' ')
                } else {
                    t
                }
            }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
    }

    /** 只数实际字符，不算标点和空白 —— "好的。" 其实只有 2 个字。 */
    private fun contentLength(s: String): Int = s.count { it !in SPLITTERS && !it.isWhitespace() }
}
