package com.autoreply.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.autoreply.app.capture.CaptureService

/**
 * [ReplySink] 的无障碍实现。
 *
 * 注意这个类里**没有任何方法能碰到发送按钮** —— 它只做两件事：
 * 看输入框状态、往里写字。想发送必须绕过整个 platform 包，
 * 自己去 NodeQuery 之外找节点，那条路上没有任何现成函数。
 */
class AccessibilityReplySink(
    private val service: CaptureService,
    private val profile: PlatformProfile,
) : ReplySink {

    override fun boxState(): BoxState {
        val root = service.targetRoot() ?: return BoxState.MISSING
        val input = NodeQuery.firstEditable(root, profile.input) ?: return BoxState.MISSING
        val text = input.text?.toString()?.trim().orEmpty()
        val hint = input.hintText?.toString()?.trim().orEmpty()
        return if (text.isEmpty() || text == hint) BoxState.EMPTY else BoxState.HAS_TEXT
    }

    override fun fill(text: String): FillResult {
        // 把所有窗口都交给 fillOnly：平台弹浮层时 rootInActiveWindow 是那层浮层，
        // 里面没有输入框；真正的聊天窗口在它下面，仍在这个 App 的窗口列表里。
        val roots = service.allTargetRoots()
        if (roots.isEmpty()) return FillResult.Unavailable("目标界面不在前台")
        return NodeQuery.fillOnly(roots, profile, text)
    }

    override fun copyToClipboard(text: String): Boolean = try {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("autoreply-draft", text))
        // 写完立刻回读。Android 10+ 会限制后台应用写剪贴板，
        // 而失败时**不抛异常**——只判断"没报错"就会把失败当成功，
        // 用户看到"已复制"，去粘贴发现是空的。
        val back = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
        back == text
    } catch (_: Exception) {
        false
    }
}
