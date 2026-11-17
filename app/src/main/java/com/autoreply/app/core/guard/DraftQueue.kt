package com.autoreply.app.core.guard

/**
 * 断句后还没发出去的那几句。
 *
 * 存在的理由：一条回复可能被拆成 2~3 条（见 [SentenceSplitter]），
 * 但每次只能填一句 —— 填进去等你发，发完再填下一句。
 * 所以"还没轮到的那几句"必须有个地方暂存。
 *
 * 用单例 + synchronized 而不是传对象：它只有一份队列，
 * 内容在每次生成草稿时被整体重置（[reset]），不存在多会话并存的情况 ——
 * 换会话时会先 [clear]，避免把这句发给上一个会话的人。
 */
object DraftQueue {

    private val queue = ArrayDeque<String>()

    /** 一次生成后，把「还没发的」放进来。会覆盖上一次的残留。 */
    @Synchronized
    fun reset(remaining: List<String>) {
        queue.clear()
        queue.addAll(remaining.filter { it.isNotBlank() })
    }

    /** 取下一条待发的。没有就返回 null。 */
    @Synchronized
    fun poll(): String? = queue.removeFirstOrNull()

    /** 换会话 / 停止连点时清空，绝不把上一段的第二句发给下一个人。 */
    @Synchronized
    fun clear() {
        queue.clear()
    }

    @Synchronized
    fun size(): Int = queue.size
}
