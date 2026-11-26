package com.autoreply.app.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把当前界面的无障碍节点树拍平成可读文本。
 *
 * 这是整个项目最基础的工具：所有选择器都必须从真实 dump 里找，
 * 靠猜 viewId 写死，目标 App 一次改版就全废。
 * 稳定性排序：带 id 的 > className + 相对位置 > 文字内容。
 */
object NodeDumper {

    private const val MAX_DEPTH = 25
    private const val MAX_NODES = 2000
    private const val MAX_TEXT = 40

    fun dump(root: AccessibilityNodeInfo, appLabel: String = ""): String {
        val sb = StringBuilder(8 * 1024)
        // 第一行输出包名 + 应用名。
        // 包名决定配置能不能命中；应用名决定"这到底是哪个 App" ——
        // 用户装了几个同类交友软件时，光看包名根本分不清，
        // 而去系统设置里翻应用信息又要多跑一趟。
        sb.append("# pkg=").append(root.packageName ?: "?")
        if (appLabel.isNotEmpty()) sb.append("   应用名=").append(appLabel)
        sb.append('\n')
        val counter = intArrayOf(0)
        val stats = intArrayOf(0, 0, 0)   // 可编辑 / 可点击 / 被标不重要
        appendNode(root, 0, sb, counter, stats)
        if (counter[0] >= MAX_NODES) {
            sb.append("\n…（节点超过 ").append(MAX_NODES).append(" 个，已截断）")
        }
        // 统计段：一眼就能回答"这个界面能不能自动化"
        sb.append("\n# ---- 统计 ----\n")
        sb.append("# 节点 ").append(counter[0])
        sb.append("，可编辑 ").append(stats[0])
        sb.append("，可点击 ").append(stats[1])
        sb.append("，被标不重要 ").append(stats[2])
        sb.append('\n')
        if (stats[0] == 0) {
            sb.append("# 全树没有可编辑节点 —— 输入框对无障碍不可见，写只能走输入法通道（或粘贴）\n")
        }
        appendFocus(root, sb)
        return sb.toString()
    }

    /**
     * 单独把「当前输入焦点」打出来。
     *
     * 意义在于：输入框经常对无障碍不可见（自绘、或设了 importantForAccessibility=no），
     * 常规遍历里一个 edit 节点都没有 —— 但屏幕上明明能打字。
     * 这时 findFocus 是唯一可能拿到它的途径，所以它的输出要单独看。
     *
     * 三种情况：
     *   有焦点 + editable=true  → 能填
     *   有焦点 + editable=false → 拿到的是外层容器，填字会静默失败
     *   无焦点                  → 输入框对无障碍完全不可见，只能靠剪贴板粘贴
     */
    private fun appendFocus(root: AccessibilityNodeInfo, sb: StringBuilder) {
        sb.append("\n# ---- 输入焦点 ----\n")
        val f = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (f == null) {
            // 「无」有两种完全不同的原因，混在一起会误导判断（2026-11-25 他趣实测）：
            // 抓取面板本身是个 Activity，它盖在目标 App 上时，焦点在**我们自己**身上，
            // 于是 findFocus 必然返回 null —— 这不代表目标 App 的输入框不可见。
            // 看下面窗口列表里带 active focused 的那一行是谁即可分辨。
            sb.append("# 无（两种可能：①输入框对无障碍不可见；②本面板正盖在上面占了焦点）\n")
            sb.append("# 分辨方法：看窗口列表里 active focused 是不是 ").append("com.autoreply.app")
            sb.append("；是的话先收起面板再抓一次\n")
            return
        }
        val cls = f.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = f.viewIdResourceName?.substringAfterLast('/').orEmpty()
        sb.append("# ").append(cls)
        if (id.isNotEmpty()) sb.append(" #").append(id)
        sb.append(" editable=").append(f.isEditable)
        sb.append(" text=\"").append(clip(f.text?.toString().orEmpty())).append("\"\n")
    }

    private fun appendNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        sb: StringBuilder,
        counter: IntArray,
        stats: IntArray,
    ) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        counter[0]++
        if (node.isEditable) stats[0]++
        if (node.isClickable) stats[1]++
        if (!node.isImportantForAccessibility) stats[2]++

        repeat(depth) { sb.append("  ") }

        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
        sb.append(cls)

        val id = node.viewIdResourceName
        if (!id.isNullOrEmpty()) sb.append(" #").append(id.substringAfterLast('/'))

        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) sb.append(" \"").append(clip(text)).append('"')

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty()) sb.append(" desc=\"").append(clip(desc)).append('"')

        val rect = Rect()
        node.getBoundsInScreen(rect)
        sb.append(" [").append(rect.left).append(',').append(rect.top).append(',')
            .append(rect.right).append(',').append(rect.bottom).append(']')

        if (node.isClickable) sb.append(" click")
        if (node.isEditable) sb.append(" edit")
        if (!node.isImportantForAccessibility) sb.append(" noimp")
        if (!node.isVisibleToUser) sb.append(" hidden")
        sb.append('\n')

        for (i in 0 until node.childCount) {
            appendNode(node.getChild(i), depth + 1, sb, counter, stats)
        }
    }

    private fun clip(raw: String): String {
        val one = raw.replace('\n', ' ').trim()
        return if (one.length <= MAX_TEXT) one else one.substring(0, MAX_TEXT) + "…"
    }
}
