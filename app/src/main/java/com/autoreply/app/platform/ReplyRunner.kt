package com.autoreply.app.platform

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.guard.DraftQueue
import com.autoreply.app.core.guard.ReplyDedup
import com.autoreply.app.core.guard.ReplyFlow
import com.autoreply.app.core.guard.SentenceSplitter
import com.autoreply.app.core.guard.SentTracker
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
    /**
     * @param manual true 表示这是用户主动发起的（点了连点/生成按钮），
     *   此时精力和长冷却都该让路 —— 它们是拟人机制，不是风险护栏。
     *   连点模式必须传 true：用户点一次要处理好几个会话，
     *   按"每会话扣一次精力"算，第三个开始就全被拦了。
     */
    fun runOnce(
        notifyText: String? = null,
        manual: Boolean = false,
        onDone: ((Result) -> Unit)? = null,
    ) {
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
        // 指纹带昵称：交友平台大量新会话第一句都是「你好」，
        // 只按文本去重会把第二个会话直接锁死
        val peer = NodeQuery.peerName(root, profile)
        if (dedup.isDuplicate(peer, lastOther.text, now)) {
            val who = peer.ifEmpty { "这个会话" }
            finish(emptyList(), "", "「$who」这条已经处理过（5 分钟内同一句）", false, false, onDone)
            return
        }

        val turns = messages.map { Turn(it.text, it.fromOther) }
        // 「我上一句是多久之前说的」只认**确认发出**的时间。
        // 之前用的是"上次生成草稿"的时间，后果是：连着试几次生成（一条都没真发出去），
        // 判定链就以为你刚跟对方说完话，每条都扣 0.25 分、一路掉进「先等等」。
        val lastSent = SentTracker(service).lastConfirmedAt
        val selfAgo = if (lastSent <= 0L) {
            Int.MAX_VALUE
        } else {
            ((now - lastSent) / 1000L).toInt().coerceAtLeast(0)
        }

        Thread {
            // 整段包 try：这里抛任何异常都会让 onDone 永远不回调，
            // 而连点的推进就靠 onDone —— 一次网络抖动就能把整条连点卡死，
            // 界面上还显示"连点剩 5 个"，用户完全不知道卡在哪。
            try {
                // 构造要读 assets/persona.json，属于 IO；每次新建还能让改完人设立即生效
                val flow = ReplyFlow(service, config)
                val outcome = flow.decide(
                    turns,
                    selfSaidAgoSeconds = selfAgo,
                    now = now,
                    // 手动/连点 → 拟人机制让路；自动触发 → 照常约束
                    respectEnergy = !manual,
                    respectCooldown = !manual,
                )
                val trace = flow.lastTrace
                mainHandler.post {
                    deliver(outcome, trace, profile, peer, lastOther.text, now, dedup, onDone)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    finish(
                        emptyList(), "",
                        "判定过程出错：${e.message?.take(60) ?: e.javaClass.simpleName}",
                        false, false, onDone,
                    )
                }
            }
        }.start()
    }

    /** 回到主线程之后：该填的填、该复制的复制。 */
    private fun deliver(
        outcome: ReplyFlow.Outcome,
        trace: List<String>,
        profile: PlatformProfile,
        peer: String,
        messageText: String,
        now: Long,
        dedup: ReplyDedup,
        onDone: ((Result) -> Unit)?,
    ) {
        val sink = AccessibilityReplySink(service, profile)

        when (outcome) {
            is ReplyFlow.Outcome.Fill -> {
                // 断句：一句话拆成 2~3 条发，模仿真人"想到一句发一句"。
                // 只填第一段，剩下的排队 —— 每段都要你单独点一次发送。
                val parts = SentenceSplitter.split(outcome.text)
                DraftQueue.reset(parts.drop(1))

                // 两条通道按顺序试：无障碍 → 输入法 → 复制到剪贴板
                val write = TextWriter.write(service, profile, parts.first())
                // 只要没真写进去，就把草稿放剪贴板兜底 ——
                // 包括"排队中"的情况：排队可能因为超时被丢弃，
                // 那时用户手里至少还有一份可以粘。
                // 剪贴板也只放**第一段**。整段塞进去的话，用户粘一次就发完了，
                // 后面几段就再也没机会分开说 —— 而"分两条发"正是要的效果。
                val copied = if (write.written) {
                    false
                } else {
                    runCatching { sink.copyToClipboard(parts.first()) }.isSuccess
                }
                // 只有真进了输入框才算"处理过"：排队和复制都还要用户再动一下手
                if (write.written) {
                    dedup.mark(peer, messageText, now)
                    // 记下"已填、待确认发出"。之后消息列表里只要出现这条，
                    // 就说明用户点了发送 —— 那时才计一次平台配额。
                    SentTracker(service).markPending(peer, parts.first(), now)
                    // 填入会让界面变化，那个变化又会触发一次自动回复 ——
                    // 记一下时刻，让自动触发在这 5 秒内跳过
                    service.noteFill()
                }
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)

                val segHint = if (parts.size > 1) {
                    "（拆成 ${parts.size} 段，发完这段自动填下一段）"
                } else {
                    ""
                }
                val note = when {
                    copied && parts.size > 1 ->
                        "${write.note}（已复制第 1 段，剩下 ${parts.size - 1} 段发完自动复制）"
                    copied -> "${write.note}（草稿已复制到剪贴板）"
                    else -> write.note + segHint
                }
                finish(trace, outcome.text, note, write.written, copied, onDone)
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
