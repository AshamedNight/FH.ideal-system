package com.autoreply.app.platform

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 一个查找条件。按 by 分四种策略，配 [pick] 决定同类节点有多个时取第几个。
 *
 * 稳定性排序（从高到低）：
 *   viewId     语义化 id，最稳，但很多 App 做了资源混淆，变成 1-3 个字符的短名
 *   className  结构锚点，App 改版通常不动
 *   contentDesc 无障碍描述，视障适配好的 App 才有
 *   text       最脆，但也是最容易一眼认出来的
 */
data class Selector(
    val by: String,
    val value: String,
    val pick: String = "first",
) {
    fun matches(node: AccessibilityNodeInfo): Boolean = when (by) {
        // 抖音这类 App 的 viewIdResourceName 形如 com.ss.android.ugc.aweme:id/jbj，
        // 配置里只写短名，比较时取最后一段 —— 写全名的话换渠道包就失效。
        "viewId" -> node.viewIdResourceName?.substringAfterLast('/') == value
        "viewIdEnds" -> node.viewIdResourceName?.endsWith(value) == true
        "className" -> node.className?.toString() == value
        "text" -> node.text?.toString()?.trim() == value
        "textContains" -> node.text?.toString()?.contains(value) == true
        "contentDesc" -> node.contentDescription?.toString() == value
        else -> false
    }
}

/**
 * 一条消息。
 *
 * [fromOther] 决定"要不要回"：只有对方说的才触发回复。
 * 判定依据是节点在屏幕上的水平位置 —— 对方靠左、自己靠右。
 * 这是各聊天 App 最通用的信号，比找"头像在左还是右"稳得多。
 */
data class ChatMessage(
    val text: String,
    val fromOther: Boolean,
    val rect: Rect,
)

/**
 * 一个平台的界面描述。**全部来自真机 dump，没有一个值是猜的。**
 *
 * 新接一个平台 = 往 profiles.json 里加一段，不改 Kotlin。
 * 每个字段都是候选列表，按顺序试，任一命中即停 ——
 * 因为同一个 App 的不同版本、不同机型，锚点经常会变一到两个。
 */
data class PlatformProfile(
    val pkg: String,
    /** 消息列表容器。拿不到时退化为在整个窗口里找消息。 */
    val messageList: List<Selector>,
    /** 单条消息。在 messageList 的作用域内查找，避免抓到顶栏标题。 */
    val message: List<Selector>,
    /** 输入框。 */
    val input: List<Selector>,
    /** 只有命中这些才算"在聊天页"，用来挡掉"在首页也瞎读"的情况。 */
    val pageHint: List<Selector>,
) {
    val isConfigured: Boolean get() = input.isNotEmpty() || message.isNotEmpty()
}

/** 读取 assets/profiles.json。解析失败不抛异常，只是没有可用配置。 */
object Profiles {

    private val cache = HashMap<String, PlatformProfile>()
    private var loaded = false

    @Volatile
    var lastError: String = ""
        private set

    fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open("profiles.json")
                .bufferedReader().use { it.readText() }
            parse(json)
            loaded = true
        } catch (e: Exception) {
            lastError = "profiles.json 读取失败：${e.message}"
        }
    }

    fun parse(json: String) {
        cache.clear()
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                if (pkg.startsWith("_")) continue
                val o = root.optJSONObject(pkg) ?: continue
                cache[pkg] = PlatformProfile(
                    pkg = pkg,
                    messageList = o.selectors("messageList"),
                    message = o.selectors("message"),
                    input = o.selectors("input"),
                    pageHint = o.selectors("pageHint"),
                )
            }
        } catch (e: Exception) {
            lastError = "profiles.json 解析失败：${e.message}"
        }
    }

    fun forPackage(pkg: String?): PlatformProfile? = pkg?.let { cache[it] }

    fun supported(): Set<String> = cache.keys

    private fun JSONObject.selectors(key: String): List<Selector> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val by = o.optString("by", "")
            val value = o.optString("value", "")
            if (by.isEmpty() || value.isEmpty()) null
            else Selector(by, value, o.optString("pick", "first"))
        }
    }
}
