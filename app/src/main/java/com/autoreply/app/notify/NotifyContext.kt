package com.autoreply.app.notify

/**
 * 通知里那几行字，按会话攒着。
 *
 * ## 为什么需要它
 *
 * 他趣（`com.xingjiabi.shengsheng`）把整棵无障碍树标成了
 * `importantForAccessibility=no` —— 真机 dump 只有 1 个节点，聊天记录一条都读不到。
 * 唯一还能看到"对方说了什么"的地方就是通知栏。
 *
 * 但通知是**一次一条**的：只拿最后一条去生成，等于每次都回一句孤零零的话，
 * 前文全靠模型编。这里按「包名 + 对方昵称」攒最近几条，生成时一起给它。
 *
 * ## 边界
 *
 * - **只在内存里**，且只留 10 分钟：这是"刚才聊了什么"的临时记忆。
 *   别人的话没有落盘的必要，落盘只多一份要清理的隐私。
 * - 攒的是**原文**，不做任何拼接或改写 —— 拼错了模型会顺着错的理解往下说。
 * - 同一条重复投递（系统会这样）只记一次。
 */
object NotifyContext {

    /**
     * 攒最近几条。3 → 6：真机里对方经常连发 2~3 句（「在吗」「照片呢」「怎么不理人」），
     * 只留 3 条时上一段话题已经被挤出去了；6 条大致覆盖一个来回。
     * 再多也不留 —— 通知攒的本来就没有时间序以外的结构，长了反而稀释最新那条。
     */
    private const val MAX_LINES = 6

    /**
     * 攒多久。10 分钟 → 30 分钟（2026-11-26）。
     *
     * 只对"读不到树、全靠通知"的平台有感（他趣）：对方问一句「在吗」，你过二十分钟
     * 才回来点生成，10 分钟的窗口已经过期 —— 上下文直接归零，模型只能凭空接一句。
     * 30 分钟的代价只是内存里多留几条；错配风险由"按昵称分桶 + 取最新"兜着。
     */
    private const val TTL_MS = 30 * 60_000L

    /** 键的组成部分用 U+241F 分隔：昵称里可能出现竖线，撞上会把两个会话粘成一个。 */
    private const val SEP = "\u241F"

    private val map = HashMap<String, ArrayDeque<Pair<String, Long>>>()

    @Synchronized
    fun record(pkg: String, peer: String, text: String, now: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        val q = map.getOrPut("$pkg$SEP$peer") { ArrayDeque() }
        // 系统会把同一条通知重投几次，别记成好几句
        if (q.lastOrNull()?.first == t) return
        q.addLast(t to now)
        while (q.size > MAX_LINES) q.removeFirst()
    }

    /** 最近的几条，按时间从早到晚。过期的先丢掉。 */
    @Synchronized
    fun recent(pkg: String, peer: String, now: Long): List<String> {
        val key = "$pkg$SEP$peer"
        val q = map[key] ?: return emptyList()
        while (q.isNotEmpty() && now - q.first().second > TTL_MS) q.removeFirst()
        return q.map { it.first }
    }

    /**
     * 这个平台最近来通知的是谁。
     *
     * 手动点「生成下一条」时没有 notifyPeer 可用，而树被摘的平台（他趣）从页面上
     * 也读不到昵称 —— 不这么推就永远匹配不上攒下的上下文。取"最近一次来通知"的那个，
     * 并且只在 TTL 内：远了就当没有，宁可没有上下文，也不把 A 的话算到 B 头上。
     *
     * 有多个会话都在活跃时，它挑的是最新的一个 —— 生成出来的草稿先给人看，
     * 人要粘了再发，错配能被看见。
     */
    @Synchronized
    fun lastPeer(pkg: String, now: Long): String {
        val prefix = "$pkg$SEP"
        var bestPeer = ""
        var bestAt = 0L
        for ((key, q) in map) {
            if (!key.startsWith(prefix)) continue
            val at = q.lastOrNull()?.second ?: continue
            if (now - at > TTL_MS) continue
            if (at >= bestAt) {
                bestAt = at
                bestPeer = key.removePrefix(prefix)
            }
        }
        return bestPeer
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
