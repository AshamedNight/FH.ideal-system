package com.nexus.replyassist

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ChatAccessibilityService — 安卓无障碍读消息 / 填回复 / 点发送
 *
 * 未在真机编译验证。这是一份可直接放进 Android Studio 工程的结构骨架，
 * 逻辑与边界处理是完整的，但具体 viewId 必须用真机 dump 后填进 profiles.json。
 *
 * 设计约束（很重要）
 * -----------------
 * 1. 本服务不做任何网络请求。生成回复走 ReplyBridge，由外部注入实现，
 *    这样测试时可以换成假实现，也不必在无障碍服务里持有网络权限。
 * 2. 所有判定都基于「当前窗口的节点树」，不缓存跨窗口的节点引用 ——
 *    AccessibilityNodeInfo 在窗口切换后会失效，缓存是崩溃的高发点。
 * 3. 默认不自动发送。AUTO_SEND 为 false 时只在悬浮窗展示草稿，
 *    最终点发送由人来做。这不只是产品选择，也是唯一能降低风控命中的做法。
 */
class ChatAccessibilityService : AccessibilityService() {

    /** 上一次处理时间，用于防抖。聊天列表会连续触发几十次内容变化。 */
    private var lastHandleAt = 0L

    /** 同一窗口内的最短处理间隔。太短会导致同一屏消息被重复回复。 */
    private val debounceMs = 800L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SelectorProfile.load(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // 只关心内容变化与窗口切换。TYPE_VIEW_SCROLLED 也会触发但太频繁，
        // 交给防抖处理反而更省电。
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            else -> return
        }

        val pkg = e.packageName?.toString() ?: return
        val profile = SelectorProfile.forPackage(pkg) ?: return

        val root = rootInActiveWindow ?: return
        // 事件来自 A 应用但当前窗口是 B 应用时直接丢弃，避免把消息填错窗口
        if (root.packageName?.toString() != pkg) return

        val now = SystemClock.uptimeMillis()
        if (now - lastHandleAt < debounceMs) return

        val texts = NodeQuery.latestMessageTexts(root, profile)
        if (texts.isEmpty()) return

        lastHandleAt = now
        handle(pkg, profile, root, texts)
    }

    private fun handle(
        pkg: String,
        profile: SelectorProfile,
        root: AccessibilityNodeInfo,
        texts: List<String>
    ) {
        if (!Mode.enabled) return

        // 只处理对方发来的最后一条，历史消息交给上层做上下文
        val incoming = texts.lastOrNull() ?: return
        if (incoming.isBlank()) return

        // 关键词命中边界时不上屏，交给人。这一层和 PC 端 Gateway 用同一套规则。
        val decision = GuardBridge.evaluate(incoming)
        if (decision == GuardDecision.BLOCK) {
            DraftOverlay.toast(this, "已拦截：命中边界规则")
            return
        }

        val draft = ReplyBridge.suggest(pkg, texts) ?: return
        if (draft.isBlank()) return

        if (!Mode.autoSend || decision == GuardDecision.REVIEW) {
            // 默认路径：只把草稿给你看，点不点发送由你决定
            DraftOverlay.show(this, draft) { accepted ->
                if (accepted) {
                    val ok = NodeQuery.fillAndSend(root, profile, draft)
                    DraftOverlay.toast(this, if (ok) "已填入" else "填入失败，节点未找到")
                }
            }
            return
        }

        val ok = NodeQuery.fillAndSend(root, profile, draft)
        if (!ok) DraftOverlay.toast(this, "自动填入失败：输入框选择器失效")
    }

    override fun onInterrupt() {
        lastHandleAt = 0L
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 供悬浮窗与设置页读取，判断服务是否在运行。 */
        var instance: ChatAccessibilityService? = null
            private set
    }
}
