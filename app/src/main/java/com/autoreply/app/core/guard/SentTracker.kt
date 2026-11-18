package com.autoreply.app.core.guard

import android.content.Context
import com.autoreply.app.platform.ChatMessage

/**
 * 「这条到底发出去了没有」。
 *
 * ## 为什么需要一个专门的类
 *
 * 护栏按"每小时几条"限流，但那个数字应该盯**对外行为**（真发了几条），
 * 而不是内部动作（生成/填了几次）。生成十次一条没发，平台什么也看不到；
 * 生成一次发出去，平台就多收到一条消息。两者混在一起算，护栏就失真了。
 *
 * ## 判据为什么是「消息列表里出现了我发的那条」
 *
 * 最直觉的做法是"草稿写入后输入框变空了 → 已发送"，但那个信号有两个问题：
 *   1. 用户删掉内容也会变空，误判
 *   2. 输入框对无障碍不可见的平台（同城夜聊）根本读不到
 *
 * 而消息列表是**必然可读**的（不然整个回复流程都没得做），
 * 而且"出现了和自己草稿一模一样的、靠右的那条"几乎只有一个解释：发出去了。
 * 这是个既通用又准确的判据。
 */
class SentTracker(context: Context) {

    private val sp = context.getSharedPreferences("sent_tracker", Context.MODE_PRIVATE)

    data class Pending(val peer: String, val text: String, val at: Long)

    /** 已填但还没确认发出的那条。没有则返回 null。 */
    val pending: Pending?
        get() {
            val text = sp.getString(KEY_TEXT, "") ?: ""
            if (text.isEmpty()) return null
            return Pending(
                peer = sp.getString(KEY_PEER, "") ?: "",
                text = text,
                at = sp.getLong(KEY_AT, 0L),
            )
        }

    /** 草稿写进输入框之后调用。 */
    fun markPending(peer: String, text: String, now: Long) {
        sp.edit()
            .putString(KEY_PEER, peer)
            .putString(KEY_TEXT, text.trim())
            .putLong(KEY_AT, now)
            .apply()
    }

    /**
     * 上次**确认发出**的时间戳。从没发过返回 0。
     *
     * 判定链拿它算「我上一句是多久之前说的」（说太近会扣分、触发"先等等"）。
     * 这个值必须来自"真的发出去了"，不能拿"生成了草稿"充数 ——
     * 否则连着试几次生成（一条都没发），判定链会以为你刚跟对方说完话，
     * 于是把接下来每一条都判成"刚说过话，缓一缓"。
     */
    val lastConfirmedAt: Long
        get() = sp.getLong(KEY_LAST_SENT, 0L)

    /**
     * 判断是不是又发出了一条消息。
     *
     * ## 为什么不能用「pending 文本匹配」来计数
     *
     * 最初的做法是：填草稿时记下文本，之后在消息列表里找一模一样的。
     * 真机一测就不对 —— 用户可能**改了草稿的几个字**再发，或者**干脆自己手动打字**。
     * 那些都是真实发送，平台都看到了，可判据全都匹配不上，于是配额永远不涨。
     * dump 里 `#tv_content "你好啊"` 和 `"怎么了"` 都在右侧，读得到，是判据的锅。
     *
     * ## 现在的判据
     *
     * 「同一个会话里，最后一条我发的消息变了」= 又发了一条。
     * 不管内容来自草稿还是手打，不管改没改字，只要右侧多了一条新的就算。
     *
     * 换会话时**重建基线且不计数**：另一个会话的最后一条本来就不同，
     * 那不代表你刚发了消息。
     */
    fun detectNewSent(messages: List<ChatMessage>, peer: String, now: Long): Boolean {
        // 读不到昵称时，用「会话里第一条消息」当标识。
        //
        // 之前这里直接 return false，结果是**整个平台永远不推进**：
        // 心遇那条 peerName 选择器的字段名写成了 peer，昵称读不到 →
        // 发送检测全跳过 → 连点卡在第一个会话不动，日志里只显示
        // 「这个会话」这条已经处理过（"这个会话"是显示层的兜底）。
        //
        // 用第一条消息替代：同一个会话里它不变，换会话就变了，
        // 足以区分"同会话又发了一条"和"换了个会话"。
        val key = peer.ifEmpty {
            messages.firstOrNull()?.text?.take(12) ?: return false
        }

        val selfMsgs = messages.filter { !it.fromOther }
        val lastSelf = selfMsgs.lastOrNull() ?: return false
        val text = squash(lastSelf.text)
        if (text.isEmpty()) return false

        val basePeer = sp.getString(KEY_BASE_PEER, "") ?: ""
        val baseText = sp.getString(KEY_BASE_TEXT, "") ?: ""
        val baseCount = sp.getInt(KEY_BASE_COUNT, -1)

        // 换会话：重建基线，不计数
        if (key != basePeer) {
            sp.edit()
                .putString(KEY_BASE_PEER, key)
                .putString(KEY_BASE_TEXT, text)
                .putInt(KEY_BASE_COUNT, selfMsgs.size)
                .apply()
            return false
        }

        if (baseCount < 0) {
            sp.edit()
                .putString(KEY_BASE_TEXT, text)
                .putInt(KEY_BASE_COUNT, selfMsgs.size)
                .apply()
            return false
        }

        // ---- 关键：不能只看文本 ----
        //
        // 消息列表只读**可见区域**。对方发来新消息时列表会往下滚，
        // 我之前发的那条可能滚出屏幕，`lastSelf` 就变成更早的一条 ——
        // 文本"变了"，但根本没发新消息。只看文本就会误判成"刚发了一条"，
        // 然后连点以为这一轮做完了，切会话 + 清空草稿。
        // 真机表现：「对方说第 5 条时，第 4 条（我发的）又冒出来一遍，然后跳过」。
        //
        // 所以条数变少时**只更新文本基线，不降低条数基线、也不触发**。
        // 基线记住的是"见过的最多条数"，滚动不会让它退回去。
        if (selfMsgs.size < baseCount) {
            sp.edit().putString(KEY_BASE_TEXT, text).apply()
            return false
        }

        // 条数真的增加了 = 发出去一条
        if (selfMsgs.size > baseCount) {
            sp.edit()
                .putString(KEY_BASE_TEXT, text)
                .putInt(KEY_BASE_COUNT, selfMsgs.size)
                .putLong(KEY_LAST_SENT, now)
                .apply()
            return true
        }

        // 条数不变、文本变了：多半是 App 改写了展示，不算发送
        sp.edit().putString(KEY_BASE_TEXT, text).apply()
        return false
    }

    /**
     * 把「当前我已经发了几条」记为基线。
     *
     * **必须在填入草稿时调用**，这是修一个必然发生的漏检：
     *
     * 新会话里我一条都没发过 → `detectNewSent` 在 `lastSelf == null` 处直接返回，
     * 基线一直是"未初始化"。等我发出第一条，它才第一次跑初始化分支、
     * 把基线设成 1 —— 于是刚发的这条被当成"存量"，永远检测不到。
     * 表现就是：填了草稿、你发了，但既不计已发数、也不填第二段。
     *
     * 在填入时先把基线钉在当前条数（通常是 0），发送后就变成 1 > 0，正常触发。
     */
    fun markBaseline(peer: String, selfCount: Int) {
        sp.edit()
            .putString(KEY_BASE_PEER, peer)
            .putInt(KEY_BASE_COUNT, selfCount)
            .apply()
    }

    /**
     * 检查消息列表里有没有「我发的那条」（只判断 pending 草稿的命运）。
     *
     * @return true 表示确认已经发出去了（并清掉 pending）
     */
    fun confirmIfSent(messages: List<ChatMessage>, peer: String, now: Long): Boolean {
        val p = pending ?: return false

        // 换会话了就先不管：pending 属于上一个会话，
        // 在那边的列表里找不到就永远确认不了，靠超时清掉
        if (p.peer.isNotEmpty() && peer.isNotEmpty() && p.peer != peer) return false

        if (now - p.at > WINDOW_MS) {
            clear()
            return false
        }

        val target = squash(p.text)
        if (target.isEmpty()) {
            clear()
            return false
        }
        // 只认「靠右 + 内容一致」：机器人不会自己发自己的话，
        // 出现这样的气泡只可能是那条草稿被发出去了。
        val hit = messages.any { m -> !m.fromOther && squash(m.text) == target }
        if (hit) {
            sp.edit().putLong(KEY_LAST_SENT, now).apply()
            clear()
        }
        return hit
    }

    fun clear() {
        sp.edit().remove(KEY_TEXT).remove(KEY_PEER).remove(KEY_AT).apply()
    }

    /**
     * 去掉所有空白再比。
     *
     * 输入法上屏、App 二次格式化都可能加空格或换行，
     * 逐字符相等太脆；只去空白不做别的归一化，
     * 是因为标点差异往往意味着内容真的被改过。
     */
    private fun squash(s: String): String = s.replace(Regex("\\s+"), "")

    private companion object {
        const val KEY_PEER = "peer"
        const val KEY_TEXT = "text"
        const val KEY_AT = "at"
        const val KEY_LAST_SENT = "last_sent"
        const val KEY_BASE_PEER = "base_peer"
        const val KEY_BASE_TEXT = "base_text"
        const val KEY_BASE_COUNT = "base_count"

        /** 10 分钟：够用户从容地点发送，又不会把很久以前的残留当成本次。 */
        const val WINDOW_MS = 10 * 60_000L
    }
}
