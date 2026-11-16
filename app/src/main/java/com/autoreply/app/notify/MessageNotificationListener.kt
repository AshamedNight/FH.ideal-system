package com.autoreply.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoreply.app.core.AppConfig

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
        val target = AppConfig(this).targetPackage
        if (target.isEmpty()) return
        if (sbn.packageName != target) return

        // 自己的常驻通知（如果有）不算。
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            )

        // 群通知（多条合并）通常没有明确的会话对象，直接跳过，
        // 避免"点开一个不确定的会话"这种既没用又显眼的行为。
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.size > 1) return

        val hit = Hit(sbn.packageName, title, text, sbn.postTime)
        listeners.forEach { it.invoke(hit) }
    }

    companion object {
        /** 内核注册在这里。用 CopyOnWrite 避免遍历时被修改。 */
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Hit) -> Unit>()

        fun addListener(l: (Hit) -> Unit) {
            listeners.add(l)
        }

        fun removeListener(l: (Hit) -> Unit) {
            listeners.remove(l)
        }
    }
}
