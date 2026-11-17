package com.autoreply.app.platform

import android.provider.Settings
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.ime.AutoReplyImeService

/**
 * 把一句草稿写进目标 App 的输入框。**两条通道，按顺序试**。
 *
 *   1. 无障碍 `ACTION_SET_TEXT` —— 快、不点击，但要求输入框在无障碍树里
 *   2. 输入法 `commitText` —— 慢一点，要切默认输入法，但**绕开无障碍树**
 *
 * 为什么必须有第二条：同城夜聊这类 App 把输入框从无障碍树里摘掉了
 * （`#et_content_container` 下面没有任何 EditText），第一条必然失败。
 * 但它能弹系统键盘 —— 有 `InputConnection` —— 所以第二条能成。
 *
 * 这两条是**不同的通道**，不是"更强的无障碍"。混在一起谈会得出错误结论
 * （"这个 App 不支持自动化"），实际上是"支持，只是要走输入法"。
 */
object TextWriter {

    data class Outcome(
        /** 文本是否已经真的进了输入框。 */
        val written: Boolean,
        /** 是否走了输入法通道。 */
        val viaIme: Boolean,
        /** 给人看的一句话。 */
        val note: String,
        /** 输入法已挂起、等输入框获得焦点才写入。 */
        val pending: Boolean = false,
    )

    fun write(service: CaptureService, profile: PlatformProfile, text: String): Outcome {
        val sink = AccessibilityReplySink(service, profile)
        val fill = sink.fill(text)
        if (fill is FillResult.Ok) {
            return Outcome(true, false, "已写入输入框（未发送）")
        }

        // ---- 第二条通道：输入法 ----
        if (!isOurImeSelected(service)) {
            // 这一句要说清楚"为什么失败"，否则用户只会看到"填不进去"
            val why = when (fill) {
                is FillResult.Unavailable -> "无障碍填不进去（${fill.reason}）"
                is FillResult.Mismatch -> "无障碍写入被 App 覆盖（${fill.actual}）"
                else -> "无障碍填不进去"
            }
            return Outcome(
                false, false,
                "$why；当前默认输入法不是 AutoReply，切过去就能用输入法写入",
            )
        }

        val ime = AutoReplyImeService.instance
            ?: return Outcome(false, true, "输入法服务没在运行：去系统设置里重新勾选一次 AutoReply 输入法")

        return if (ime.commit(text)) {
            Outcome(true, true, "已通过输入法写入（未发送）")
        } else {
            // 没有绑定输入框（用户还没点输入框）：文本已排队，
            // 等 onStartInput 时自动写入 —— 所以这里不算失败
            Outcome(false, true, "已排队：点一下目标 App 的输入框，草稿会自动出现", pending = true)
        }
    }

    /** 当前默认输入法是不是我们。读这个值不需要额外权限。 */
    fun isOurImeSelected(context: android.content.Context): Boolean {
        val current = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        } catch (_: Exception) {
            null
        }.orEmpty()
        return current.startsWith(context.packageName)
    }
}
