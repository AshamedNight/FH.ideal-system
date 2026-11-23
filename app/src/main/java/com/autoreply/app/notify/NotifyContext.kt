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

    private const val MAX_LINES = 3
    private const val TTL_MS = 10 * 60_000L

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

    @Synchronized
    fun clear() {
        map.clear()
    }
}
