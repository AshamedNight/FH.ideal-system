package com.nexus.replyassist

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * SelectorProfile + NodeQuery — 界面适配层
 *
 * 这一层的存在解决了一个具体问题：每接一个新 App，界面结构都不一样。
 * 如果把查找逻辑写死在代码里，接第 5 个 App 时代码就没人敢动了。
 *
 * 做法是把「怎么找」抽成配置：新增一个 App 只需要往 profiles.json 里加一段，
 * 不用改 Kotlin。选择器按优先级依次尝试，任一命中即停。
 */

/** 单个选择器条件。by 支持 viewId / className / text / contentDesc。 */
data class Selector(
    val by: String,
    val value: String,
    /** first | last，用于同一类节点有多个时取哪一个 */
    val pick: String = "first"
) {
    fun matches(node: AccessibilityNodeInfo): Boolean = when (by) {
        "viewId" -> node.viewIdResourceName?.endsWith(value) == true
        "className" -> node.className?.toString() == value
        "text" -> node.text?.toString() == value
        "contentDesc" -> node.contentDescription?.toString() == value
        else -> false
    }
}

/**
 * 一个 App 的界面描述。
 *
 * input 允许多条候选，因为同一 App 的不同版本、不同页面（聊天页 / 评论区）
 * 输入框的标识常常不一样，写成候选列表比写死一条稳。
 */
data class SelectorProfile(
    val pkg: String,
    val input: List<Selector>,
    val send: List<Selector>,
    val message: List<Selector>,
    /** 消息容器。为空时退化为在整个树上找 message 选择器 */
    val messageList: Selector? = null
) {
    companion object {
        private val cache = HashMap<String, SelectorProfile?>()

        fun load(context: Context) {
            cache.clear()
            val json = context.assets.open("profiles.json")
                .bufferedReader().use { it.readText() }
            parse(json)
        }

        /** 允许运行时注入，方便测试与热更新配置。 */
        fun parse(json: String) {
            cache.clear()
            val root = JSONObject(json)
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                cache[key] = SelectorProfile(
                    pkg = key,
                    input = o.optJSONArray("input").toSelectors(),
                    send = o.optJSONArray("send").toSelectors(),
                    message = o.optJSONArray("message").toSelectors(),
                    messageList = o.optJSONArray("messageList")?.takeIf { it.length() > 0 }
                        ?.let { it.getJSONObject(0) }
                        ?.let { Selector(it.getString("by"), it.getString("value"), it.optString("pick", "first")) }
                )
            }
        }

        /** 没适配的 App 返回 null，调用方直接跳过，不抛异常。 */
        fun forPackage(pkg: String): SelectorProfile? = cache[pkg]

        fun supportedPackages(): Set<String> = cache.keys

        private fun org.json.JSONArray?.toSelectors(): List<Selector> {
            this ?: return emptyList()
            return (0 until length()).map { i ->
                val o = getJSONObject(i)
                Selector(o.getString("by"), o.getString("value"), o.optString("pick", "first"))
            }
        }
    }
}

/** 节点查找与操作。所有方法都是纯查询，不持有跨调用引用。 */
object NodeQuery {

    /** 深度优先遍历，返回命中条件的节点。最多下探 depth 层，防止极端页面栈溢出。 */
    fun collect(
        root: AccessibilityNodeInfo,
        hit: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 24
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

    private fun firstMatch(
        root: AccessibilityNodeInfo,
        selectors: List<Selector>
    ): AccessibilityNodeInfo? {
        for (sel in selectors) {
            val found = collect(root) { sel.matches(it) }
            if (found.isEmpty()) continue
            return if (sel.pick == "last") found.last() else found.first()
        }
        return null
    }

    /**
     * 取当前界面上可见的消息文本。
     *
     * 只回传文本节点，不做"是不是对方发的"判断 —— 那个判断需要气泡对齐方向或
     * 头像位置的语义，各 App 差异太大，放在 ReplyBridge 里用上下文判断更稳。
     */
    fun latestMessageTexts(root: AccessibilityNodeInfo, profile: SelectorProfile): List<String> {
        val scope = profile.messageList?.let { sel ->
            collect(root) { sel.matches(it) }.firstOrNull()
        } ?: root

        val out = ArrayList<String>()
        for (sel in profile.message) {
            val nodes = collect(scope) { sel.matches(it) }
            for (n in nodes) {
                val t = n.text?.toString()?.trim().orEmpty()
                // 过滤空文本与纯时间戳（形如 12:30 / 昨天 / 星期三）
                if (t.length < 1) continue
                if (t.matches(Regex("^\\d{1,2}:\\d{2}$"))) continue
                if (t.matches(Regex("^(昨天|今天|前天|星期[一二三四五六日])$"))) continue
                out.add(t)
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    /**
     * 把文本填进输入框并触发发送。
     *
     * 优先 ACTION_SET_TEXT（一次性写入，不产生逐字输入事件），
     * 失败再退回剪贴板粘贴 —— 部分 App 的自绘输入框不响应 SET_TEXT。
     */
    fun fillAndSend(
        root: AccessibilityNodeInfo,
        profile: SelectorProfile,
        text: String
    ): Boolean {
        val input = firstMatch(root, profile.input) ?: return false

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val set = input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
        )
        if (!set) {
            // 自绘输入框常见：只能通过点击后由输入法写入
            val cp = input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!cp) return false
            // 交给上层用输入法注入，这里不假装成功
            return false
        }

        // 重新取一次根节点：SET_TEXT 会引起界面刷新，旧引用可能已失效
        val freshRoot = ChatAccessibilityService.instance?.rootInActiveWindow ?: return false
        val send = firstMatch(freshRoot, profile.send) ?: return false
        return send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
