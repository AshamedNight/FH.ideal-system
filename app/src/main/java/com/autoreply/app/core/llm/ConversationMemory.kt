package com.autoreply.app.core.llm

import java.util.LinkedHashMap

/**
 * 会话记忆：解决"滚出屏幕就忘了"。
 *
 * ## 为什么需要它
 *
 * 上下文原来只有一个来源 —— 屏幕上**当前可见**的消息。而聊天页一滚，
 * 上面那几句就没了。真机表现（心遇/同城都见过）：对方五分钟前说过「想加个微信」，
 * 列表滚过去之后再生成，模型看不见这句话，于是又问一遍「你平时刷手机都看啥」——
 * 前后两句接不上，像换了个人在聊。
 *
 * ## 只放内存，不落盘
 *
 * 它和 [com.autoreply.app.core.log.ChatLogStore] 不同：那个是用户主动打开的记录，
 * 默认关；这个是让"回复接得上"的运行期状态，必须默认生效。
 * 要默认生效就不能留隐私把柄 —— 所以**只存在内存里**，App 一退出就没了。
 * 代价是重启后要靠屏幕重新攒；可以接受，因为连续聊天时它一直在。
 *
 * ## 宁丢不错
 *
 * 换了会话（或者界面刷新导致读到的和缓存完全对不上）时**直接重置**，
 * 绝不把上一个会话的话接给下一个人 —— 那是这套代码里最严重的一类错误。
 */
object ConversationMemory {

    /** 每个会话最多记多少条。40 条够覆盖一次完整对话，也压得住内存。 */
    private const val MAX_TURNS = 40

    /** 只留最近 32 个会话，聊过的人多了不至于一直涨。 */
    private const val MAX_SESSIONS = 32

    /** 按访问顺序淘汰的 LRU。key = 包名 + 会话昵称。 */
    private val sessions = LinkedHashMap<String, MutableList<Turn>>(16, 0.75f, true)

    /**
     * 把"屏幕上看到的"并进记忆，返回这一段完整可用的对话。
     *
     * @param key 用 [keyOf] 生成（包名 + 昵称），不要只传昵称 ——
     *   两个平台都有叫「小雅」的人，撞了会把 A 平台的对话接给 B 平台。
     */
    @Synchronized
    fun merge(key: String, visible: List<Turn>): List<Turn> {
        val fresh = visible.filter { it.text.isNotBlank() }
        if (fresh.isEmpty()) return sessions[key]?.toList() ?: emptyList()

        val cached = sessions.getOrPut(key) { ArrayList() }
        if (cached.isEmpty()) {
            cached.addAll(fresh.takeLast(MAX_TURNS))
            trim()
            return cached.toList()
        }

        // 找"接缝"：缓存里的最后一条，在刚读到的列表里出现在哪。
        // 找到 → 只追加它后面的，避免重复；找不到 → 认为换会话了，整段重置。
        val anchor = cached.last().text.trim()
        var at = -1
        for (i in fresh.indices.reversed()) {
            if (fresh[i].text.trim() == anchor && fresh[i].fromOther == cached.last().fromOther) {
                at = i
                break
            }
        }
        if (at < 0) {
            // 读到的和记忆对不上：新会话 / 界面刷新 / 消息被清理。
            // **宁可丢记忆，也不能接错人。**
            cached.clear()
            cached.addAll(fresh.takeLast(MAX_TURNS))
            trim()
            return cached.toList()
        }
        for (i in (at + 1) until fresh.size) cached.add(fresh[i])
        while (cached.size > MAX_TURNS) cached.removeAt(0)
        trim()
        return cached.toList()
    }

    /** 主动忘记某个会话（比如用户换了人聊、或判定不可靠时）。 */
    @Synchronized
    fun forget(key: String) {
        sessions.remove(key)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    /** 会话键：同一个昵称在不同平台是两个人。 */
    fun keyOf(pkg: String, peer: String): String = "$pkg\u241F${peer.trim()}"

    private fun trim() {
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull() ?: return
            sessions.remove(oldest)
        }
    }
}
