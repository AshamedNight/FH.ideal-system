package com.autoreply.app.platform

import android.content.ClipboardManager
import com.autoreply.app.core.AppConfig

/**
 * 从剪贴板读「用户复制的对方原话」，当生成上下文。
 *
 * 抽成独立工具是因为它有两个调用方：ReplyRunner（自动/输入法面板路径）
 * 和 MainActivity 的「生成并填入」按钮（首页手动路径）。
 * 过滤规则各写一份必然漂移 —— 那种"首页能用、面板不能用"的差异最难查。
 *
 * 三种内容必须排除，否则会自己回自己或把一大坨调试信息喂给模型：
 *   1. 我们刚写进去的草稿（[AppConfig.lastClipboardDraft]）—— 用户没来得及粘时
 *      再点一次生成，剪贴板里还是上一句回复
 *   2. 抓取工具的 dump 文本（以 `# pkg=` 开头、带节点树）
 *   3. 太短（一个「嗯」）或太长（整段聊天记录）—— 不像一句对方原话
 */
object ClipboardPeerText {

    fun read(context: android.content.Context, config: AppConfig): String? {
        val cm = try {
            context.getSystemService(ClipboardManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return null
        val text = try {
            cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            return null
        }
        if (text.isEmpty() || text.length < 2 || text.length > 300) return null
        if (text == config.lastClipboardDraft) return null
        if (text.startsWith("# pkg=") || text.contains("当前窗口（排查用）")) return null
        // 节点树 dump 里的典型行，例如 "TextView #tv_content [..]" —— 一整份 dump 不该进模型
        if (text.lines().size > 3) return null
        return text
    }
}
