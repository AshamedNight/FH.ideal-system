package com.autoreply.app.platform

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
        /** 因为"默认输入法不是简输入"而写不进去 —— 这是用户可修的一步，不是随机失败。 */
        val needsIme: Boolean = false,
    )

    /**
     * @param replaceExisting 写入前先清空输入框。断句的第二段必须传 true ——
     *   走输入法时 `commitText` 是插入不是覆盖，不清会变成两段粘一起。
     */
    fun write(
        service: CaptureService,
        profile: PlatformProfile,
        text: String,
        replaceExisting: Boolean = false,
    ): Outcome {
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
                "$why；当前默认输入法不是「简输入」—— 回 App 点「切换输入法」选它，这条通道就能用",
                needsIme = true,
            )
        }

        val ime = AutoReplyImeService.instance
            ?: return Outcome(false, true, "输入法服务没在运行：去系统设置里重新勾选一次 AutoReply 输入法")

        // expectedPkg：只往"你正在看的那个 App"里写。绑在别处时会被挂起，不会硬写。
        if (ime.commit(text, replaceFirst = replaceExisting, expectedPkg = profile.pkg)) {
            return Outcome(true, true, "已通过输入法写入（未发送）")
        }

        // 绑在别的 App 的输入框上：这不是"没点到输入框"，是"焦点压根不在这个 App"。
        // 草稿已经挂起了（commit 里做的），这里只给一句准确的指引 ——
        // 否则用户会看到"已写入"，回聊天页却发现框里还是旧内容，日志和界面对不上。
        val bound = ime.boundPackage()
        if (bound.isNotEmpty() && bound != profile.pkg) {
            val who = profile.label.ifEmpty { "目标 App" }
            return Outcome(
                false, true,
                "输入法正连在别的输入框上 —— 先点一下${who}的输入框，草稿会自动出现",
                pending = true,
            )
        }

        // ---- 第三条：替用户点一下输入框，把焦点建立起来 ----
        //
        // 为什么必须点：输入法只能往「当前有焦点的输入框」写字，
        // 没有焦点就没有 InputConnection，系统不允许输入法凭空上屏。
        // 但这一下**可以由程序来点** —— `dispatchGesture` 是无障碍服务的
        // 合法能力；点的是输入框本身，不改任何状态、不发送、不通知对方。
        //
        // 所以用起来的效果是：你一次都不用点。
        if (tapInputBox(service, profile)) {
            return Outcome(
                false, true,
                "正在替你点一下输入框，草稿随即写入（焦点会闪一下）",
                pending = true,
            )
        }

        return Outcome(
            false, true,
            "已排队，但没点到输入框：手动点一下输入框，草稿会自动出现",
            pending = true,
        )
    }

    /**
     * 点一下输入框区域，让它获得焦点。
     *
     * 点的节点是**容器**（`#et_content_container` 这类），不是 EditText ——
     * 因为 EditText 本来就不在无障碍树里。容器有 id、有尺寸，够定位了。
     *
     * 用 `dispatchGesture` 而不是 `performAction(ACTION_CLICK)`：
     * 容器的点击事件未必转给内部的编辑控件，而一次真实的按下-抬起
     * 会走完整的触摸分发，效果和用户手指点一下一样。
     */
    private fun tapInputBox(service: CaptureService, profile: PlatformProfile): Boolean {
        val root = service.targetRoot() ?: return false
        val node = NodeQuery.firstMatch(root, profile.input) ?: return false
        return tapNode(service, node)
    }

    /**
     * 用坐标手势点一个节点。
     *
     * 为什么需要它：`performAction(ACTION_CLICK)` 只对「声明了可点击、而且真的
     * 处理了点击事件」的 View 有效。很多 App 的自定义控件（输入框容器、
     * 列表里的"下一个未读"按钮）用自己的触摸逻辑，`ACTION_CLICK` 会**返回 true
     * 但界面上什么都没发生** —— 表现就是"点了没反应"，而且日志里看不出问题。
     * 一次真实的按下-抬起走完整触摸分发，这类控件才认。
     */
    fun tapNode(
        service: CaptureService,
        node: android.view.accessibility.AccessibilityNodeInfo,
    ): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false

        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return try {
            service.dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
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
