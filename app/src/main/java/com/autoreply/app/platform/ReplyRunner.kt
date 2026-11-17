package com.autoreply.app.platform

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.guard.ReplyDedup
import com.autoreply.app.core.guard.ReplyFlow
import com.autoreply.app.core.llm.Turn

/**
 * 一次完整的「读 → 判定 → 填」。
 *
 * 抽出来是因为它有两个调用方：主界面的按钮、无障碍服务的通知触发。
 * 两边各写一遍的话，判定链、去重、填字降级很容易出现细微差异 ——
 * 那种差异最难查，因为它只在某一条路径上出现。
 *
 * **线程约定写死在这里**：主线程读节点、后台调模型、再回主线程填字。
 * `AccessibilityNodeInfo` 不是线程安全的，网络请求又不能在主线程，
 * 所以只能来回切；调用方不用操心这件事。
 */
class ReplyRunner(
    private val service: CaptureService,
    private val config: AppConfig,
) {

    /**
     * @param note 给人看的一句话结论（会被首页/日志引用）
     * @param draft 生成的草稿（没生成时为空）
     */
    data class Result(
        val trace: List<String>,
        val draft: String,
        val note: String,
        val filled: Boolean,
        val copied: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param notifyText 通知里带的消息内容。**只在节点树读不到消息时才用** ——
     *   他趣那种整棵树被 `importantForAccessibility=no` 摘掉的平台，
     *   树里一个节点都没有，但通知里有「谁说了什么」，够生成一句回复。
     */
    fun runOnce(notifyText: String? = null, onDone: ((Result) -> Unit)? = null) {
        val root = service.targetRoot()
        if (root == null) {
            finish(emptyList(), "", "目标界面不在前台", false, false, onDone)
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val profile = Profiles.forPackage(pkg)
        if (profile == null) {
            finish(emptyList(), "", "「$pkg」没有界面配置", false, false, onDone)
            return
        }

        var messages = NodeQuery.readMessages(root, profile)
        if (messages.isEmpty() && !notifyText.isNullOrBlank()) {
            // 退而用通知内容当上下文：只有一条，信息很薄，但比放弃强。
            messages = listOf(ChatMessage(notifyText.trim(), fromOther = true, rect = Rect()))
        }
        if (messages.isEmpty()) {
            finish(emptyList(), "", "没读到消息，也没有通知内容可用", false, false, onDone)
            return
        }

        val lastOther = messages.lastOrNull { it.fromOther }
        if (lastOther == null) {
            finish(emptyList(), "", "最后一条不是对方发的", false, false, onDone)
            return
        }

        val now = System.currentTimeMillis()
        val dedup = ReplyDedup(service)
        if (dedup.isDuplicate(lastOther.text, now)) {
            finish(emptyList(), "", "这条已经处理过（5 分钟内同一句）", false, false, onDone)
            return
        }

        val turns = messages.map { Turn(it.text, it.fromOther) }
        val lastAt = dedup.lastHandledAt
        val selfAgo = if (lastAt <= 0L) {
            Int.MAX_VALUE
        } else {
            ((now - lastAt) / 1000L).toInt().coerceAtLeast(0)
        }

        Thread {
            // 构造要读 assets/persona.json，属于 IO；每次新建还能让改完人设立即生效
            val flow = ReplyFlow(service, config)
            val outcome = flow.decide(turns, selfSaidAgoSeconds = selfAgo, now = now)
            val trace = flow.lastTrace
            mainHandler.post {
                deliver(outcome, trace, profile, lastOther.text, now, dedup, onDone)
            }
        }.start()
    }

    /** 回到主线程之后：该填的填、该复制的复制。 */
    private fun deliver(
        outcome: ReplyFlow.Outcome,
        trace: List<String>,
        profile: PlatformProfile,
        messageText: String,
        now: Long,
        dedup: ReplyDedup,
        onDone: ((Result) -> Unit)?,
    ) {
        val sink = AccessibilityReplySink(service, profile)

        when (outcome) {
            is ReplyFlow.Outcome.Fill -> {
                val fill = sink.fill(outcome.text)
                val copied = if (fill is FillResult.Ok) {
                    false
                } else {
                    runCatching { sink.copyToClipboard(outcome.text) }.isSuccess
                }
                // 只有真填进去才算"处理过"：填失败了用户还要自己动手
                if (fill is FillResult.Ok) dedup.mark(messageText, now)
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)

                val note = when (fill) {
                    is FillResult.Ok -> "草稿已填好，点发送即可"
                    is FillResult.Unavailable -> if (copied) "填不进去，草稿已复制到剪贴板" else "填不进去：${fill.reason}"
                    is FillResult.Mismatch -> if (copied) "回读不一致，草稿已复制" else "回读不一致：${fill.actual}"
                }
                finish(trace, outcome.text, note, fill is FillResult.Ok, copied, onDone)
            }

            is ReplyFlow.Outcome.Hold -> {
                // 风险太高：不替你填，但把草稿放到剪贴板，你要用自己粘贴
                val copied = runCatching { sink.copyToClipboard(outcome.text) }.isSuccess
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                finish(trace, outcome.text, outcome.reason, false, copied, onDone)
            }

            is ReplyFlow.Outcome.Wait -> {
                val minutes = (outcome.afterMs / 60_000L).coerceAtLeast(1L)
                finish(trace, "", "等 ${minutes} 分钟再回：${outcome.reason}", false, false, onDone)
            }

            is ReplyFlow.Outcome.Silent -> {
                finish(trace, "", "这条不参与：${outcome.reason}", false, false, onDone)
            }

            is ReplyFlow.Outcome.Error -> {
                finish(trace, "", "生成失败：${outcome.reason}", false, false, onDone)
            }
        }
    }

    private fun finish(
        trace: List<String>,
        draft: String,
        note: String,
        filled: Boolean,
        copied: Boolean,
        onDone: ((Result) -> Unit)?,
    ) {
        onDone?.invoke(Result(trace, draft, note, filled, copied))
    }
}
