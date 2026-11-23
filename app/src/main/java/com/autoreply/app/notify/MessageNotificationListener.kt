package com.autoreply.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoreply.app.core.AppConfig
import com.autoreply.app.platform.Profiles

/**
 * 通知监听 —— L1 档的触发器。
 *
 * 为什么用通知而不是定时扫会话列表：
 * 扫列表要「回列表 → 逐个找未读 → 点开」，每条消息产生 3~5 次 performAction 点击，
 * 而且是不带触摸事件的点击，这是自动化特征里最明显的一种。
 * 通知驱动下：没有新消息时程序完全静默（0 动作），有消息时只点开那一个会话（1 次），
 * 且动作时机由对方决定，天然不存在固定周期。
 *
 * 这个类只负责"听到"，不负责"去做"。做不做、什么时候做，由内核按护栏判定。
 */
class MessageNotificationListener : NotificationListenerService() {

    /** 一条被识别出的新消息通知。字段可能为空，交给上层判断。 */
    data class Hit(
        val packageName: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // 诊断用：可能是本服务先起来、读屏服务还没连上
        Profiles.load(this)
        val targets = AppConfig(this).targetPackages
        val label = Profiles.forPackage(sbn.packageName)?.label?.takeIf { it.isNotEmpty() }

        // ---- 三种"看到了却没用上"的情况，各写一句，别混在一起猜 ----
        //
        // 只说我们支持的平台（label 非空）：微信、短信不该占首页那 4 行日志。
        // 反过来，**如果这三种日志一条都没出现、消息却来了**，
        // 那就是这个 App 压根没发通知 —— 这是另一回事，改代码没用。
        if (label != null) {
            if (targets.isEmpty()) {
                diag?.invoke("收到「$label」的通知，但设置里的「目标应用包名」是空的 —— 先填包名")
                return
            }
            if (!targets.contains(sbn.packageName)) {
                diag?.invoke("收到「$label」的通知，但它不在「目标应用包名」里，已忽略")
                return
            }
        }

        // 通知监听必须显式配置包名：没有目标清单时，任何一条聊天通知都会被当成信号，
        // 那是误触发的源头。
        if (targets.isEmpty()) return
        if (!targets.contains(sbn.packageName)) return

        // 自己的常驻通知（如果有）不算。
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = sbn.notification.extras

        // 群通知（多条合并）通常没有明确的会话对象，直接跳过，
        // 避免"点开一个不确定的会话"这种既没用又显眼的行为。
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.size > 1) return

        // 收起的通知栏只给一行**预览**，长消息会被截断 ——
        // 他趣就长这样：「做研发的都很聪明，你平时是不是特…」，后面那截没了。
        // 全文通常在这两个地方：展开态的 BIG_TEXT，或 MessagingStyle 的 messages 数组。
        val style = messagingStyle(sbn.notification)
        val candidates = listOfNotNull(
            style?.first,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        // 取**最长**的那个：宁可多，不要少 —— 短的那个正是被截断的预览。
        val text = candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.length }

        // 昵称优先用 MessagingStyle 的 sender（那才是真的发送者），
        // 其次才是通知标题 —— 有些 App 的标题写的是应用名。
        val title = style?.second
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val hit = Hit(sbn.packageName, title, text, sbn.postTime)
        // 有通知但没有正文：要么是平台只推"你收到一条消息"，要么是系统把内容隐藏了。
        // 这两种都做不出回复，但用户需要知道"卡在哪"，而不是盯着空白草稿发呆。
        if (text.isNullOrBlank()) {
            diag?.invoke("「${title ?: label ?: sbn.packageName}」的通知里没有正文，没法当上下文")
        }
        listeners.forEach { it.invoke(hit) }
    }

    /**
     * MessagingStyle 里的最后一条消息（完整文本 + 发送者）。
     *
     * 聊天类通知大量用这个样式：完整内容放在 `android.messages` 数组里，
     * 收起的标题/正文只是它的截断预览。读这个数组是拿到全文的唯一机会 ——
     * 没有它，长消息永远只剩前十几个字，回出来自然答非所问。
     */
    @Suppress("DEPRECATION")
    private fun messagingStyle(n: Notification): Pair<String, String?>? {
        val arr = runCatching {
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull() ?: return null
        val last = arr.lastOrNull() as? android.os.Bundle ?: return null
        val text = last.getCharSequence("text")?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val sender = last.getCharSequence("sender")?.toString()?.trim().orEmpty()
        return text to sender.ifEmpty { null }
    }

    companion object {
        /** 内核注册在这里。用 CopyOnWrite 避免遍历时被修改。 */
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Hit) -> Unit>()

        /**
         * 诊断出口：通知"看到了但没用上"时写一行给人看。
         *
         * 不接这个回调也照样工作，只是少了那条线索 ——
         * 而"他趣没反应"最难的地方就是分不清"没收到通知"和"收到了但被忽略"。
         */
        @Volatile
        var diag: ((String) -> Unit)? = null

        fun addListener(l: (Hit) -> Unit) {
            listeners.add(l)
        }

        fun removeListener(l: (Hit) -> Unit) {
            listeners.remove(l)
        }
    }
}
