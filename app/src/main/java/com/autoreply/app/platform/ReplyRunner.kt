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

        // **去重只拦自动触发，手动点击一律放行。**
        //
        // 去重存在的理由是"填草稿会改变界面，而界面变化又会触发一次自动回复"——
        // 不拦就是自己喂自己，一条消息能刷出四五份草稿。
        // 但人明确点了一下（输入法面板的「生成下一条 / 换一句」、App 里的按钮），
        // 那一下的语义就是"我现在就要它再出一句"，跟"界面恰好变了"是两件事。
        //
        // 踩过的坑：这里原来不分手动自动，于是**卡住时唯一能自救的按钮也是死的**——
        // 面板上直接显示「这条已经处理过（30 分钟内不重复）」，
        // 而这个会话你其实什么都没发出去。手动重来会多花一次 token，
        // 那是用户自己选的，比"点了没反应"便宜得多。
        val repeated = dedup.isDuplicate(peer, lastOther.text, now)
        if (repeated && !manual) {
            val who = peer.ifEmpty { "这个会话" }
            finish(emptyList(), "", "「$who」这条已经处理过（30 分钟内不重复）", false, false, onDone)
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
                    // 手动/连点/演练模式 → 拟人机制让路；自动触发 → 照常约束。
                    // 演练模式必须在这里也让路：精力判断在判定链里，不在 PacerGuard，
                    // 只放开护栏是不够的 —— 连发到第 16 条还是会卡在"精力低"。
                    respectEnergy = !(manual || config.drillMode),
                    respectCooldown = !(manual || config.drillMode),
                    // 连点 / 演练也算"人要求现在就做"：第②层的"先等几分钟"同样要让路。
                    // 不让路的话连点一圈全是「想回但先等」，界面上看起来就是卡死。
                    manual = manual || config.drillMode,
                    // 昵称只给开场白的 {昵称} 槽位用。读不到就空着 ——
                    // 带该槽位的那几条会被跳过，不会把花括号发出去
                    peer = peer,
                )
                val trace = flow.lastTrace
                mainHandler.post {
                    deliver(
                        outcome, trace, profile, peer, lastOther.text, now, dedup,
                        selfCount = messages.count { !it.fromOther },
                        // 和 detectNewSent 的 key 同一套算法
                        sessionKey = peer.ifEmpty {
                            messages.firstOrNull()?.text?.take(12).orEmpty()
                        },
                        onDone = onDone,
                        // 这一条本来在去重窗口里，是用户手动要求再来一次
                        replay = repeated,
                    )
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
        selfCount: Int,
        /** 会话标识，和 detectNewSent 里算的 key 保持一致（昵称读不到时用会话首条消息） */
        sessionKey: String,
        onDone: ((Result) -> Unit)?,
        /** 这条本来在去重窗口里，是用户手动要求重来。状态行前面标一下，别让人以为是新消息。 */
        replay: Boolean = false,
    ) {
        val sink = AccessibilityReplySink(service, profile)
        // 输入法面板的状态行只有 28 个字，所以标记要短 ——
        // 放前面是为了让人一眼看出"这次是重来"，而不是"又来了一条新消息"
        val tag = if (replay) "手动重来 · " else ""

        when (outcome) {
            is ReplyFlow.Outcome.Fill -> {
                // 断句：一句话拆成 2~3 条发，模仿真人"想到一句发一句"。
                // 只填第一段，剩下的排队 —— 每段都要你单独点一次发送。
                //
                // 固定开场白例外（single=true）：库里每一行都是写好的一整句，
                // 按逗号拆开会把问句甩到第二条，用户只点一次就丢钩子。
                val parts = if (outcome.single) {
                    listOf(outcome.text)
                } else {
                    SentenceSplitter.split(outcome.text)
                }
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
                    sink.copyToClipboard(parts.first())
                }
                // 只有真进了输入框才算"处理过"：排队和复制都还要用户再动一下手
                //
                // **但「这条草稿等着你发」这件事，三条通道是一样的。**
                // 基线（markBaseline / markPending）原来只在 written 分支写，
                // 走输入法排队或剪贴板时就不写 —— 后果是那条消息发出去之后
                // detectNewSent 没有基线可比：首次会走"初始化"分支直接返回 false，
                // 于是配额不涨、连点不推进、下一段不填。
                // 面板上看起来就是「我发了，它没反应」。
                val tracker = SentTracker(service)
                tracker.markPending(peer, parts.first(), now)
                tracker.markBaseline(sessionKey, selfCount)

                if (write.written) {
                    dedup.mark(peer, messageText, now)
                    // 填入会让界面变化，那个变化又会触发一次自动回复 ——
                    // 记一下时刻，让自动触发在这 5 秒内跳过
                    service.noteFill()
                    // 手动模式下也给"下一段"排个兜底（连点有自己的看门狗）
                    service.scheduleNextSegment()
                } else {
                    // **没写进输入框也要算"处理过"**。
                    //
                    // 之前只在 written 时 mark，剪贴板这条路径就完全不设防：
                    // 界面每变一次 → 自动触发 → 重新生成 → 覆盖剪贴板。
                    // 实测同一会话连着生成了 4 次（剪贴板里四条不同草稿，
                    // 19:38:00 一秒内三条日志），用户根本不知道该粘哪一条。
                    //
                    // 交付方式不同（输入框 / 剪贴板 / 排队），
                    // 但"这条消息已经被处理"这件事是一样的。
                    dedup.mark(peer, messageText, now)
                    // 同理，5 秒防抖也要生效，否则界面变化还会继续触发
                    service.noteFill()
                    // 剪贴板/排队一样要等用户去发 —— 兜底不能只在 written 分支排。
                    // 不排的话，没切输入法时第二段之后就再也没有任何保底。
                    service.scheduleNextSegment()
                }
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)

                val segHint = if (parts.size > 1) {
                    "（拆成 ${parts.size} 段，发完这段自动填下一段）"
                } else {
                    ""
                }
                // write.note 里已经带了"已复制到剪贴板"这句（TextWriter 写的），
                // 这里再拼一遍就变成「已复制到剪贴板（已复制到剪贴板）」
                val note = when {
                    copied && parts.size > 1 ->
                        "${write.note}；第 1 段在上面，剩下 ${parts.size - 1} 段发完自动复制"
                    copied -> write.note
                    else -> write.note + segHint
                }
                finish(trace, outcome.text, tag + note, write.written, copied, onDone)
            }

            is ReplyFlow.Outcome.Hold -> {
                // 风险太高：不替你填，但把草稿放到剪贴板，你要用自己粘贴
                val copied = sink.copyToClipboard(outcome.text)
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                finish(trace, outcome.text, tag + outcome.reason, false, copied, onDone)
            }

            is ReplyFlow.Outcome.Wait -> {
                // 记下"到点之前别再判了"。不这么做的话，界面每变一次就重新判一次，
                // 又得一次"精力低，稍等"，日志里连刷四五条看起来像卡死。
                service.postponeAuto(outcome.afterMs)
                val minutes = (outcome.afterMs / 60_000L).coerceAtLeast(1L)
                finish(trace, "", tag + "等 ${minutes} 分钟再回：${outcome.reason}", false, false, onDone)
            }

            is ReplyFlow.Outcome.Silent -> {
                finish(trace, "", tag + "这条不参与：${outcome.reason}", false, false, onDone)
            }

            is ReplyFlow.Outcome.Error -> {
                finish(trace, "", tag + "生成失败：${outcome.reason}", false, false, onDone)
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
