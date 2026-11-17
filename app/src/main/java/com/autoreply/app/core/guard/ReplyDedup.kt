package com.autoreply.app.core.guard

import android.content.Context

/**
 * 同一条消息只处理一次。
 *
 * 没有这一层，同一条「在吗」你点两次按钮就生成两次草稿；
 * 等接了自动触发，就变成对同一条消息反复回 —— 那比什么都不做更像机器人，
 * 而且是最容易被对面察觉的一种（真人不会连回三遍同一句）。
 *
 * **为什么用「文本 + 时间窗」而不是会话 id**：
 * 聊天页的节点树里拿不到会话标识（每个 App 的 id 各不相同，有的干脆没有），
 * 而"同一句话在 5 分钟内重复出现"在真实对话里几乎只可能是同一条消息。
 * 代价是对方 5 分钟内连发两句一模一样的「哈哈」会被漏掉一次 ——
 * 这个代价可以接受，反过来（对同一条消息回两次）不可接受。
 */
class ReplyDedup(context: Context) {

    private val sp = context.getSharedPreferences("reply_dedup", Context.MODE_PRIVATE)

    /** 上次真正处理的时间戳，用来估算「我上一句是多久之前说的」。 */
    val lastHandledAt: Long
        get() = sp.getLong(KEY_AT, 0L)

    fun isDuplicate(text: String, now: Long): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val last = sp.getString(KEY_TEXT, "") ?: ""
        return last == normalized && now - lastHandledAt < WINDOW_MS
    }

    fun mark(text: String, now: Long) {
        sp.edit()
            .putString(KEY_TEXT, text.trim())
            .putLong(KEY_AT, now)
            .apply()
    }

    private companion object {
        const val KEY_TEXT = "last_text"
        const val KEY_AT = "last_at"

        /** 5 分钟。更短会漏掉真人重复的情况，更长会把正常的新消息当旧消息。 */
        const val WINDOW_MS = 5 * 60_000L
    }
}
