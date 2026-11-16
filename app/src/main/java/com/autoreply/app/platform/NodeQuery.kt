package com.autoreply.app.platform

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点查找与读取。全部是纯查询，**不持有跨调用的节点引用** ——
 * AccessibilityNodeInfo 在窗口切换后会失效，缓存它是崩溃的高发点。
 */
object NodeQuery {

    private const val MAX_DEPTH = 24

    /**
     * 深度优先遍历。最多下探 [maxDepth] 层，防止极端页面把栈打爆。
     *
     * 参数顺序有讲究：[maxDepth] 必须排在 [hit] 之前。
     * Kotlin 的 trailing lambda 只认最后一个参数，如果写成 (root, hit, maxDepth)，
     * 调用处 `collect(root) { ... }` 会把这个 lambda 当成 maxDepth（要 Int），
     * 报 "Type mismatch: inferred type is () -> Boolean but Int was expected"。
     */
    fun collect(
        root: AccessibilityNodeInfo,
        maxDepth: Int = MAX_DEPTH,
        hit: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (hit(node)) out.add(node)
            if (depth >= maxDepth) continue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
        return out
    }

    fun firstMatch(root: AccessibilityNodeInfo, selectors: List<Selector>): AccessibilityNodeInfo? {
        for (sel in selectors) {
            val found = collect(root) { sel.matches(it) }
            if (found.isEmpty()) continue
            return if (sel.pick == "last") found.last() else found.first()
        }
        return null
    }

    /** 页面是不是聊天页。pageHint 为空时视为"不做判断"。 */
    fun isChatPage(root: AccessibilityNodeInfo, profile: PlatformProfile): Boolean {
        if (profile.pageHint.isEmpty()) return true
        return firstMatch(root, profile.pageHint) != null
    }

    fun messageScope(root: AccessibilityNodeInfo, profile: PlatformProfile): AccessibilityNodeInfo =
        firstMatch(root, profile.messageList) ?: root

    /**
     * 读出当前屏幕上的消息，按从上到下的顺序。
     *
     * 只读"可见的"：聊天记录是列表，滑上去的历史不在这里 —— 这符合我们的用法，
     * 因为需要回的是**最新一条**，翻历史反而会把旧消息当新的。
     *
     * 方向判定用水平位置：气泡靠左 = 对方，靠右 = 自己。
     * 这是各聊天 App 最通用的信号，比"找头像在左还是右"稳 ——
     * 头像节点经常被 App 复用或隐藏，而气泡的横向对齐不会变。
     */
    fun readMessages(root: AccessibilityNodeInfo, profile: PlatformProfile): List<ChatMessage> {
        if (profile.message.isEmpty()) return emptyList()

        val scope = messageScope(root, profile)
        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val midX = rootRect.centerX()

        val raw = ArrayList<ChatMessage>()
        for (sel in profile.message) {
            val nodes = collect(scope) { sel.matches(it) }
            for (n in nodes) {
                val t = textOf(n)
                if (!isRealMessage(t)) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) continue
                raw.add(ChatMessage(t, r.centerX() < midX, r))
            }
            // 第一种选择器命中就够了；多条候选是给版本差异用的，不是用来叠加的
            if (raw.isNotEmpty()) break
        }

        // DFS 顺序不保证等于视觉顺序，按 top 排一次
        raw.sortBy { it.rect.top }
        return raw
    }

    /**
     * 取一个有意义的文本。
     *
     * 消息的锚点常常是**容器**而不是 TextView（豆包页的消息节点是 LinearLayout #dyl，
     * 它自己的 text 是 null，文字在它的子节点里）。所以命中容器时往下找，
     * 但只找 4 层 —— 再深就可能捞到别的东西。
     */
    private fun textOf(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = node.text?.toString()?.trim().orEmpty()
        if (own.isNotEmpty()) return own
        if (depth >= 4) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = textOf(child, depth + 1)
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    private val TIME_ONLY = Regex("^\\d{1,2}:\\d{2}$")
    private val DATE_ONLY = Regex("^(昨天|今天|前天|星期[一二三四五六日]|\\d{1,2}月\\d{1,2}日)$")

    private fun isRealMessage(text: String): Boolean {
        if (text.isEmpty()) return false
        // 纯时间戳不是消息：几乎每个聊天页都会把时间插在列表里
        if (TIME_ONLY.matches(text)) return false
        if (DATE_ONLY.matches(text)) return false
        return true
    }

    /**
     * 把草稿写进输入框，**不点发送**。
     *
     * 三个刻意的行为：
     * 1. 输入框已经有内容时直接拒绝 —— 那是用户自己打的字，覆盖它是不可接受的
     * 2. 只做一次 ACTION_SET_TEXT，不重试、不硬写
     * 3. 不做任何"找不到发送按钮就怎么办"的处理，因为根本不去找
     */
    fun fillOnly(
        root: AccessibilityNodeInfo,
        profile: PlatformProfile,
        text: String,
    ): FillResult {
        val input = firstMatch(root, profile.input)
            ?: return FillResult.Unavailable("找不到输入框（选择器可能失效）")

        val existing = input.text?.toString()?.trim().orEmpty()
        // 提示文案（"发消息..."）不算用户内容：EditText 的 hint 在部分实现里
        // 会出现在 text 上，所以内容与提示完全相同时也视为空
        val hint = input.hintText?.toString()?.trim().orEmpty()
        if (existing.isNotEmpty() && existing != hint) {
            return FillResult.Unavailable("输入框已有内容，不覆盖")
        }

        val ok = input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
        )
        return if (ok) FillResult.Ok else FillResult.Unavailable("ACTION_SET_TEXT 被拒绝")
    }

    /** 回读输入框当前内容，用于填完之后校验。 */
    fun inputText(root: AccessibilityNodeInfo, profile: PlatformProfile): String? =
        firstMatch(root, profile.input)?.text?.toString()
}
