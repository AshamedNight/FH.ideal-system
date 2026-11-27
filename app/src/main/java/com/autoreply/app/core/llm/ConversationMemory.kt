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

    /**
     * 尾部对齐最多比几条。
     *
     * 为什么是 6 而不是"全部"：长对话里同一句话（"在吗""怎么说"）会被说好几次，
     * 比得越长越容易对齐到更早的那一次，于是中间的几条被当成"已经有了"漏掉。
     */
    private const val OVERLAP_MAX = 6

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

        // ① 尾部重叠：记忆的最后 k 条 == 这一屏的前 k 条。
        //    正常续聊就是这种形状（新消息把旧消息顶上去），最可靠、优先认它。
        val k = tailOverlap(cached, fresh)
        if (k > 0) {
            for (i in k until fresh.size) cached.add(fresh[i])
            cap(cached)
            trim()
            return cached.toList()
        }

        // ② 往上翻历史：这一屏的内容在记忆里**按顺序全都能找到**，
        //    说明用户滚上去看旧消息了，这一屏整体是"过去"。
        //
        //    这里绝对不能像原来那样"对不上就重置" —— 那会把记忆倒退回十分钟前，
        //    接着生成的回复就答非所问（2026-11-27 用户反馈的"聊长了就对不上"）。
        //    正确处理是：忽略这一屏，继续用记忆里更新的事实。
        if (isOlderWindow(fresh, cached)) return cached.toList()

        // ③ 锚点：记忆最后一条出现在这一屏的中间（屏幕窗口一半旧一半新）。
        val anchor = cached.last()
        var at = -1
        for (i in fresh.indices.reversed()) {
            if (sameAs(fresh[i], anchor)) {
                at = i
                break
            }
        }
        if (at >= 0) {
            for (i in (at + 1) until fresh.size) cached.add(fresh[i])
            cap(cached)
            trim()
            return cached.toList()
        }

        // ④ 三条都不成立：真的换人了 / 消息被清理 / 界面整块刷新。
        //    **宁可丢记忆，也不能接错人。**
        cached.clear()
        cached.addAll(fresh.takeLast(MAX_TURNS))
        trim()
        return cached.toList()
    }

    /** 两条消息是不是"同一条"：内容 + 方向都要一致（对方说的和我说的，同文不同义）。 */
    private fun sameAs(a: Turn, b: Turn): Boolean =
        a.fromOther == b.fromOther && a.text.trim() == b.text.trim()

    /**
     * 记忆尾部与这一屏开头的最大重叠长度。
     *
     * 只比最近 [OVERLAP_MAX] 条：长对话里同一句话可能被说过好几次，
     * 比太长反而更容易"对齐到更早的那次"，把中间的几条漏掉。
     */
    private fun tailOverlap(cached: List<Turn>, fresh: List<Turn>): Int {
        val max = minOf(OVERLAP_MAX, cached.size, fresh.size)
        for (k in max downTo 1) {
            var ok = true
            for (i in 0 until k) {
                if (!sameAs(cached[cached.size - k + i], fresh[i])) {
                    ok = false
                    break
                }
            }
            if (ok) return k
        }
        return 0
    }

    /**
     * 这一屏是不是"更早的一段历史"：每一条都能在记忆里按顺序找到。
     *
     * 判据刻意宽松（允许跳过记忆里的条目）：用户滚动时屏幕上的消息本来就可能跳着显示
     * （中间有图片/语音这类读不到文字的气泡）。只要顺序对得上，就认定是旧窗口。
     */
    private fun isOlderWindow(fresh: List<Turn>, cached: List<Turn>): Boolean {
        if (fresh.size > cached.size) return false
        var j = 0
        for (t in fresh) {
            while (j < cached.size && !sameAs(cached[j], t)) j++
            if (j >= cached.size) return false
            j++
        }
        return true
    }

    private fun cap(list: MutableList<Turn>) {
        while (list.size > MAX_TURNS) list.removeAt(0)
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
