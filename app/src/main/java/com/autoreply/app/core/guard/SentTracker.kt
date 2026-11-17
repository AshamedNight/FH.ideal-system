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
     * 检查消息列表里有没有「我发的那条」。
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
        if (hit) clear()
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

        /** 10 分钟：够用户从容地点发送，又不会把很久以前的残留当成本次。 */
        const val WINDOW_MS = 10 * 60_000L
    }
}
