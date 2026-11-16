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
        val input = NodeQuery.firstMatch(root, profile.input) ?: return BoxState.MISSING
        val text = input.text?.toString()?.trim().orEmpty()
        val hint = input.hintText?.toString()?.trim().orEmpty()
        return if (text.isEmpty() || text == hint) BoxState.EMPTY else BoxState.HAS_TEXT
    }

    override fun fill(text: String): FillResult {
        val root = service.targetRoot()
            ?: return FillResult.Unavailable("目标界面不在前台")
        return NodeQuery.fillOnly(root, profile, text)
    }

    override fun copyToClipboard(text: String) {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("autoreply-draft", text))
    }
}
