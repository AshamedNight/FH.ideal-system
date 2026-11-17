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

    /**
     * 找**真正能接收文字**的节点。
     *
     * 输入框在无障碍树里常常是"套娃"：外层 LinearLayout 有 id、有尺寸
     * （`#et_content_container`、`#all_et_content_container` 就是这种），
     * 真正的 EditText 在里面，或者干脆没暴露出来。
     *
     * 直接对外层容器 `ACTION_SET_TEXT` 会返回 true 但什么也不发生；
     * 更糟的是某些实现返回 false，让人以为"App 禁止写入"。
     * 所以命中后一律往里找可编辑节点，找不到才退回原节点。
     */
    fun firstEditable(root: AccessibilityNodeInfo, selectors: List<Selector>): AccessibilityNodeInfo? {
        for (sel in selectors) {
            val hits = collect(root) { sel.matches(it) }
            for (hit in hits) {
                if (hit.isEditable) return hit
                val inner = collect(hit, maxDepth = 8) {
                    it.isEditable || it.className?.toString() == "android.widget.EditText"
                }
                if (inner.isNotEmpty()) return inner.first()
            }
        }
        // 最后一道兜底：输入框常常对无障碍不可见（自绘，或 importantForAccessibility=no），
        // 常规遍历里一个 edit 节点都没有，但屏幕上确实能打字。
        // 这时"当前输入焦点"往往还拿得到 —— 用户点开过输入框就说明焦点在那儿。
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (focused != null && focused.isEditable) return focused
        return null
    }

    /** 页面是不是聊天页。pageHint 为空时视为"不做判断"。 */
    fun isChatPage(root: AccessibilityNodeInfo, profile: PlatformProfile): Boolean {
        if (profile.pageHint.isEmpty()) return true
        if (firstMatch(root, profile.pageHint) != null) return true
        // 有的平台把整棵树标成 importantForAccessibility=no（他趣就是这样，
        // 根节点 View [0,0,0] noimp，全树只剩 1 个节点），pageHint 永远找不到。
        // 但输入焦点仍能拿到那个 EditText —— 能拿到就说明在聊天页。
        return firstEditable(root, profile.input) != null
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
        // 同一个 id 常常同时打在容器和文本节点上（抖音私信页的 #content_layout
        // 就是 LinearLayout 与 TextView 同名），两层都命中会重复。
        // 按「位置 + 文本」去重，只留一条。
        val seen = HashSet<String>()
        for (sel in profile.message) {
            val nodes = collect(scope) { sel.matches(it) }
            for (n in nodes) {
                val t = textOf(n)
                if (!isRealMessage(t)) continue
                // 平台的安全提示混在同一个列表里，不排除会被当成"对方说的话"
                if (profile.exclude.any { t.contains(it) }) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) continue
                val dedupKey = "${r.left},${r.top},${r.right},${r.bottom}|$t"
                if (!seen.add(dedupKey)) continue
                // 「位置+文本」只能挡住 id 完全相同的两个节点。
                // 更常见的是外层容器和内层文本各命中一次：文本一样，
                // 但容器 bounds 含内边距，位置差几十像素，去重键就不同了 ——
                // 表现是同一条消息被读成两条（x=408 / x=480）。
                // 再按「文本相同 且 垂直区间重叠过半」挡一次：
                // 真人连发两条一样的话（「哈哈」「好」）不会垂直重叠，不会误合并。
                val duplicated = raw.any { prev ->
                    prev.text == t &&
                        minOf(prev.rect.bottom, r.bottom) - maxOf(prev.rect.top, r.top) >
                        maxOf(1, r.height() / 2)
                }
                if (duplicated) continue
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
        val input = firstEditable(root, profile.input)
            ?: return FillResult.Unavailable(
                "找不到可写入的输入框（命中的节点是容器，里面没有 EditText）"
            )

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
        firstEditable(root, profile.input)?.text?.toString()

    /**
     * 当前会话的对方昵称。
     *
     * 用来区分"同一句文本在不同会话里" —— 交友平台大量新会话第一句都是「你好」，
     * 只按文本去重会把第二个会话直接锁死。读不到就返回空串，
     * 调用方要能接受（退回纯文本指纹）。
     */
    fun peerName(root: AccessibilityNodeInfo, profile: PlatformProfile): String {
        if (profile.peerName.isEmpty()) return ""
        val node = firstMatch(root, profile.peerName) ?: return ""
        return node.text?.toString()?.trim().orEmpty()
    }

    /**
     * 输入框不听话时，用一句话说清「卡在哪」。
     *
     * 填字失败有且只有三种原因，对应的处置完全不同，混在一起就会瞎试：
     *   1. 树里没有可编辑节点  → 输入框被排除在无障碍树外，只能走输入法或粘贴
     *   2. 有节点但焦点拿不到  → 切前台丢了焦点，需要"人停在页面里"的时机
     *   3. 有节点也有焦点      → 只是 SET_TEXT 不被接受，改用粘贴/输入法
     */
    fun diagnoseInput(root: AccessibilityNodeInfo, profile: PlatformProfile): String = buildString {
        append("输入框诊断")
        val editable = firstEditable(root, profile.input)
        if (editable == null) {
            append("\n  可编辑节点：无（整棵树里找不到，SET_TEXT 无从下手）")
        } else {
            append("\n  可编辑节点：")
            append(editable.className?.toString()?.substringAfterLast('.') ?: "?")
            append(" editable=").append(editable.isEditable)
        }
        val focus = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        append("\n  当前输入焦点：")
        if (focus == null) {
            append("无（本 App 在前台时焦点会丢，这是测法的限制）")
        } else {
            append(focus.className?.toString()?.substringAfterLast('.') ?: "?")
            append(" editable=").append(focus.isEditable)
        }
    }

    /**
     * 点一下平台自带的「下一个未读」。
     *
     * **这是整个项目里唯一会主动点击的方法。** 刻意单独放在这里、命名直白，
     * 就是为了让"哪里有自动点击"这件事一眼可见，不需要翻整棵树才发现。
     *
     * 用 `ACTION_CLICK` 而不是 `dispatchGesture`：前者不产生触摸事件流，
     * 在系统层面是可访问性操作；后者要模拟完整手势，更像"注入"。
     * 两者都有痕迹，但前者与本 App 的身份（无障碍工具）一致。
     */
    fun clickNextUnread(root: AccessibilityNodeInfo, profile: PlatformProfile): Boolean {
        if (profile.nextUnread.isEmpty()) return false
        val node = firstMatch(root, profile.nextUnread) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
