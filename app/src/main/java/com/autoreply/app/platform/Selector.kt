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
        // 一条选择器匹配多个 id（逗号分隔）。
        //
        // 为什么需要：左右气泡常常是**不同的 id**（爱聊：对方 #left_iv_text /
        // #left_tv，自己 #right_tv）。而 readMessages 命中第一种选择器就停，
        // 拆成三条也只能拿到其中一侧 —— 拿不到"我发的"，
        // 发送确认（SentTracker 数我方条数）就永远为 0，配额不涨、断句不推进。
        // 合在一条里，一次遍历左右都收，方向仍由水平位置判断。
        "viewIdIn" -> {
            val name = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            value.split(',').any { name == it.trim() }
        }
        "className" -> node.className?.toString() == value
        "text" -> node.text?.toString()?.trim() == value
        "textContains" -> node.text?.toString()?.contains(value) == true
        "contentDesc" -> node.contentDescription?.toString() == value
        // 图标按钮的唯一线索常常只有 contentDescription（无障碍描述），
        // 比如那个悬浮的「下一个未读 >>」——图上是个纯图标，text 是空的。
        // 用 contains 而不是全等：各家写法不统一（"下一个未读"/"下一个未读会话"）。
        "contentDescContains" -> node.contentDescription?.toString()?.contains(value) == true
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
    /**
     * 给人看的名字（「心遇」「同城夜聊」），只出现在日志和提示里。
     *
     * 日志里写包名没人看得懂，而"到底是哪个 App 出了问题"恰恰是排查的第一句。
     */
    val label: String = "",
    /** 消息列表容器。拿不到时退化为在整个窗口里找消息。 */
    val messageList: List<Selector>,
    /** 单条消息。在 messageList 的作用域内查找，避免抓到顶栏标题。 */
    val message: List<Selector>,
    /** 输入框。 */
    val input: List<Selector>,
    /** 只有命中这些才算"在聊天页"，用来挡掉"在首页也瞎读"的情况。 */
    val pageHint: List<Selector>,
    /**
     * 系统提示文案，出现即丢弃。
     *
     * 抖音私信列表里混着「为保障用户沟通安全，未互相关注的陌生人违规消息可能会被处理…」
     * 这类系统提示，它和真实气泡在同一个 RecyclerView 里。不排除的话会被当成
     * "对方说的话"喂给模型，回复内容直接跑偏。
     */
    val exclude: List<String> = emptyList(),

    /**
     * 「下一个未读」按钮。
     *
     * 这是平台自己给用户做的入口（`#tv_next`），真人天天点，所以点它的行为模式本身
     * 不异常 —— 但它是全项目**唯一**会主动点击的东西，所以单独一个字段，
     * 配置里留空就等于这个平台没有这个能力。
     */
    val nextUnread: List<Selector> = emptyList(),

    /**
     * 对方昵称（聊天页顶栏的标题）。
     *
     * **只用于区分会话，不用于内容判断。** 为什么必须有它：
     * 交友平台大量新会话的第一句都是「你好」—— 如果去重只看消息文本，
     * 第二个会话的「你好」会被当成"已经处理过"直接锁掉，那条招呼就永远不回。
     * 加上昵称后指纹变成「谁 + 说了什么」，跨会话才不会互相干扰。
     *
     * 读不到时退回纯文本指纹（有误判风险，但比完全不能用强）。
     */
    val peerName: List<Selector> = emptyList(),

    /**
     * 这个平台**默认**走剪贴板交付。
     *
     * 用在「输入框对无障碍/输入法都不可见」或「整棵树被摘掉」的平台：
     * 爱聊、他趣这类。生成结果只进剪贴板，用户长按粘贴、自己点发送。
     * 它比全局开关更准 —— 只对确实写不进去的平台降级，同城/心遇照旧自动填入。
     */
    val clipboardPreferred: Boolean = false,

    /**
     * 读侧降级：节点树读空时，允许用「通知攒的几条」和「用户长按复制的原话」当上下文。
     *
     * **和 [clipboardPreferred] 分开的原因**（2026-11-25 拆的）：
     * 他趣整棵树被加固摘掉（dump 只有 1 个节点、noimp hidden），读只能靠通知/复制，
     * 但写走输入法 `commitText` **不依赖无障碍树** —— 一个开关管两头，
     * 会把本来能自动填的平台也一起退成手动粘贴。
     */
    val readFallback: Boolean = false,

    /**
     * 聊天内容区的上下边界（可选）。
     *
     * 心遇改版后消息 `TextView` 完全没有 id，只能按类型找；而按类型找会把
     * 顶栏昵称、底部快捷语、资料卡片一起捞进来。用「顶栏容器」和「输入区容器」
     * 这两个**有 id 的锚点**夹出中间那段，范围就干净了：
     * 只认整体落在 [areaTop.bottom, areaBottom.top] 之间的节点。
     *
     * 两个都留空 = 不做区域过滤（老平台完全不受影响）。
     */
    val areaTop: List<Selector> = emptyList(),
    val areaBottom: List<Selector> = emptyList(),
) {
    val isConfigured: Boolean
        get() = input.isNotEmpty() || message.isNotEmpty() || clipboardPreferred || readFallback
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
                    label = o.optString("label", ""),
                    messageList = o.selectors("messageList"),
                    message = o.selectors("message"),
                    input = o.selectors("input"),
                    pageHint = o.selectors("pageHint"),
                    exclude = o.strings("exclude"),
                    nextUnread = o.selectors("nextUnread"),
                    peerName = o.selectors("peerName"),
                    clipboardPreferred = o.optBoolean("clipboardPreferred", false),
                    readFallback = o.optBoolean("readFallback", false),
                    areaTop = o.selectors("areaTop"),
                    areaBottom = o.selectors("areaBottom"),
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

    /** 纯字符串数组，用于 exclude 这类"文案列表"。 */
    private fun JSONObject.strings(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotEmpty() }
        }
    }
}
