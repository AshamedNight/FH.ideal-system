package com.autoreply.app.core.guard

import android.content.Context

/**
 * 同一条消息只处理一次。
 *
 * 没有这一层，同一条「在吗」你点两次按钮就生成两次草稿；
 * 等接了自动触发，就变成对同一条消息反复回 —— 那比什么都不做更像机器人。
 *
 * ## 指纹为什么要带对方昵称
 *
 * 最初只按消息文本去重，**在交友平台上直接踩坑**：这类平台大量新会话的
 * 第一句都是「你好」。第一个会话回完之后，第二个会话的「你好」会被判成
 * "已经处理过"，那条招呼就永远不回 —— 而这恰恰是最该回的一条。
 *
 * 所以指纹是「谁 + 说了什么」。昵称读不到时退回纯文本
 * （有误判风险，但总比整条链不能用强）。
 *
 * ## 为什么用昵称而不是会话 id
 *
 * 聊天页的节点树里没有会话 id（每个 App 的 id 各不相同，有的干脆没有）。
 * 昵称是能拿到的最接近"会话标识"的东西，而且它就在顶栏，位置稳定。
 * 时间窗则兜住"昵称读错或为空"的情况。
 */
class ReplyDedup(context: Context) {

    private val sp = context.getSharedPreferences("reply_dedup", Context.MODE_PRIVATE)

    /** 上次真正处理的时间戳，用来估算「我上一句是多久之前说的」。 */
    val lastHandledAt: Long
        get() = sp.getLong(KEY_AT, 0L)

    fun isDuplicate(peer: String, text: String, now: Long): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val last = sp.getString(KEY_TEXT, "") ?: ""
        return last == fingerprint(peer, normalized) && now - lastHandledAt < WINDOW_MS
    }

    fun mark(peer: String, text: String, now: Long) {
        sp.edit()
            .putString(KEY_TEXT, fingerprint(peer, text.trim()))
            .putLong(KEY_AT, now)
            .apply()
    }

    /**
     * 分隔符用 U+241F（␟，符号"单元分隔符"）而不是竖线：
     * 昵称和消息里都可能出现 `|`，一旦撞上就会把两个不同的会话粘成同一个指纹。
     */
    private fun fingerprint(peer: String, text: String): String =
        if (peer.isEmpty()) text else "$peer\u241F$text"

    private companion object {
        const val KEY_TEXT = "last_text"
        const val KEY_AT = "last_at"

        /** 5 分钟。更短会漏掉真人重复的情况，更长会把正常的新消息当旧消息。 */
        /**
         * 同一条消息多久内不重复生成。
         *
         * 5 分钟 → 30 分钟：用户生成完没马上发（在看、在犹豫），
         * 过几分钟界面一变就又生成一条意思相近的，剪贴板被连着覆盖 ——
         * 实测出现过「打字就行呗」「打字不挺好嘛」这种同义变种。
         * 对方真发新消息时 dedup 不会命中（指纹带消息文本），
         * 所以延长窗口不会把新消息挡掉。
         */
        const val WINDOW_MS = 30 * 60_000L
    }
}
