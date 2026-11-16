package com.autoreply.app.core.guard

/**
 * 话术风险分级。
 *
 * 判据只看**回复本身**，不看对方说了什么 —— 对方问价格，不代表可以报价。
 *
 * 出口不是"能不能自动发送"（我们永远不自动发送），而是"能不能自动填"：
 *   HIGH   → 不填，只提示"建议你手动回"。草稿一旦进了输入框，人多半顺手就点了发送，
 *            自动填高风险话术等于用界面设计诱导人犯错。
 *   MEDIUM → 填，但标注出来
 *   LOW    → 正常填
 */
object DraftRisk {

    enum class Level { HIGH, MEDIUM, LOW }

    data class Verdict(val level: Level, val hits: List<String>) {
        val fillable: Boolean get() = level != Level.HIGH
    }

    /** 说错了代价不对等：法律承诺、价格、赔付。 */
    private val HIGH = listOf(
        "便宜", "优惠", "折扣", "打折", "特价", "秒杀", "价格", "多少钱", "报价",
        "保证", "一定", "绝对", "肯定", "包退", "包换", "包邮", "无条件",
        "退款", "退货", "赔偿", "承诺", "永久", "终身", "随时可以退",
    )

    /** 说错了通常还能补救：时效、流程、库存。 */
    private val MEDIUM = listOf(
        "今天", "明天", "后天", "当天", "次日", "小时内", "尽快", "马上", "立刻",
        "发货", "到货", "快递", "库存", "现货", "有货", "预售", "补货", "预约",
    )

    /** 金额形态：429 / 429元 / ¥429 / 429.00 */
    private val MONEY = Regex("(?:[¥￥]\\s*\\d+)|(?:\\d+\\s*(?:元|块|rmb|RMB))|(?:\\d+\\.\\d{2})")

    fun classify(draft: String): Verdict {
        val text = draft.trim()
        if (text.isEmpty()) return Verdict(Level.LOW, emptyList())

        val high = HIGH.filter { text.contains(it) }
        if (high.isNotEmpty()) return Verdict(Level.HIGH, high)

        // 出现具体金额就是高风险，哪怕一个字的风险词都没有
        if (MONEY.containsMatchIn(text)) return Verdict(Level.HIGH, listOf("具体金额"))

        val medium = MEDIUM.filter { text.contains(it) }
        if (medium.isNotEmpty()) return Verdict(Level.MEDIUM, medium)

        return Verdict(Level.LOW, emptyList())
    }
}
