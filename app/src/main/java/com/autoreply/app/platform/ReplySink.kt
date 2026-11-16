package com.autoreply.app.platform

/**
 * 输入框当前状态。
 *
 * [HAS_TEXT] 是最重要的一项：用户自己已经打了一半字，这时绝不能覆盖 ——
 * 覆盖不只是"丢了他的话"，更说明程序在替人做决定。
 */
enum class BoxState {
    /** 空，或只显示提示文案。 */
    EMPTY,

    /** 用户（或上一步的我们）已经写了内容。 */
    HAS_TEXT,

    /** 找不到输入框：可能不在聊天页，也可能界面结构变了。 */
    MISSING,
}

sealed class FillResult {
    /** 把 [text] 原样写进去，并回读确认过。 */
    object Ok : FillResult()

    /** 写了但回读不一致。 */
    data class Mismatch(val actual: String?) : FillResult()

    /** 输入框不存在或不可编辑。 */
    data class Unavailable(val reason: String) : FillResult()
}

/**
 * 回复草稿的唯一出口。
 *
 * ⚠️ 这个接口**没有 send()**，而且故意不会加。
 *
 * 这不是"我们记得别调用发送"，是类型系统层面不给这个能力：
 * 任何持有 [ReplySink] 的代码，能做的事只有填字和复制。
 * 想发送就必须绕过整个模块去拿 AccessibilityNodeInfo 自己找发送按钮，
 * 而那条路上没有任何现成函数，写出来一眼就能在 review 里看到。
 *
 * 之所以用接口而不是"函数名约定"，是因为约定会被遗忘，接口不会。
 */
interface ReplySink {

    fun boxState(): BoxState

    /**
     * 把草稿写进输入框。
     *
     * 实现方必须：写入后延时回读确认；不一致时最多重试一次；
     * 仍然失败就退化成写剪贴板（用户长按粘贴），绝不能反复硬写。
     */
    fun fill(text: String): FillResult

    /** 填不进去时的兜底：放进剪贴板，由用户自己粘。 */
    fun copyToClipboard(text: String)
}

/**
 * 一次"有新消息"的判定结果。
 *
 * [UNKNOWN] 不是错误，是常态：刚进会话、界面还没稳定、结构变了，都会落到这里。
 * 调用方看到 UNKNOWN 只应该等待，不应该猜。
 */
enum class NewMessage {
    /** 第一次看这个会话，基线。不触发回复。 */
    BASELINE,

    /** 对方发来新消息 —— 唯一会触发回复的状态。 */
    FROM_OTHER,

    /** 自己发的最后一条（包括人工点发送后），说明已经处理完。 */
    FROM_ME,

    /** 没有新内容：滑动看历史、界面重绘，都不算新。 */
    NOTHING_NEW,

    /** 读不出来或无法判断。 */
    UNKNOWN,
}
