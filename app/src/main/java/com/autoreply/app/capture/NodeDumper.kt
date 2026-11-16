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

    fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder(8 * 1024)
        // 第一行输出包名：抓一次就能同时拿到「这是哪个 App」和「界面长什么样」，
        // 省掉让用户再去系统设置里翻包名这一步，也避免包名抄错一个字符。
        sb.append("# pkg=").append(root.packageName ?: "?").append('\n')
        val counter = intArrayOf(0)
        appendNode(root, 0, sb, counter)
        if (counter[0] >= MAX_NODES) {
            sb.append("\n…（节点超过 ").append(MAX_NODES).append(" 个，已截断）")
        }
        return sb.toString()
    }

    private fun appendNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        sb: StringBuilder,
        counter: IntArray,
    ) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        counter[0]++

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
        if (!node.isVisibleToUser) sb.append(" hidden")
        sb.append('\n')

        for (i in 0 until node.childCount) {
            appendNode(node.getChild(i), depth + 1, sb, counter)
        }
    }

    private fun clip(raw: String): String {
        val one = raw.replace('\n', ' ').trim()
        return if (one.length <= MAX_TEXT) one else one.substring(0, MAX_TEXT) + "…"
    }
}
