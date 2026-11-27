package com.autoreply.app.platform

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.guard.DraftQueue
import com.autoreply.app.core.guard.ReplyDedup
import com.autoreply.app.core.guard.ReplyFlow
import com.autoreply.app.core.guard.SentenceSplitter
import com.autoreply.app.core.guard.SentTracker
import com.autoreply.app.core.llm.ConversationMemory
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.log.ChatLogStore
import com.autoreply.app.notify.NotifyContext

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
        /** 填不进去的原因是"默认输入法不是简输入" —— 连点遇到它会直接停下，不空等。 */
        val needsIme: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param notifyText 通知里带的消息内容。**只在节点树读不到消息时才用** ——
     *   他趣那种整棵树被 `importantForAccessibility=no` 摘掉的平台，
     *   树里一个节点都没有，但通知里有「谁说了什么」，够生成一句回复。
     * @param notifyPeer 通知标题，通常是对方昵称。读不到聊天页的平台靠它区分会话 ——
     *   不然十个人都说「你好」，去重会把后面九个全锁死。
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
        notifyPeer: String? = null,
        onDone: ((Result) -> Unit)? = null,
    ) {
        // var：读空重读时要换一份新的根节点（旧的那份子节点可能已经被回收）
        var root = service.targetRoot()
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

        // 昵称：界面读得到就用界面的（它更准，跟着会话走），读不到才用通知标题。
        val peer = NodeQuery.peerName(root, profile)
            .ifEmpty { notifyPeer.orEmpty().trim() }

        // 读侧降级：全局剪贴板模式、平台标了 readFallback（他趣），或整条走剪贴板。
        // 抓不到页面时，用户复制的对方原话就是上下文。
        //
        // 2026-11-25 把「读」和「写」拆开了：他趣的树被加固摘光，读只能靠通知/复制，
        // 但它的输入框能用输入法 commitText 写 —— 一个开关管两头，会把能自动填的
        // 平台一起退成手动粘贴。
        val readFallback = config.clipboardOnly || profile.readFallback || profile.clipboardPreferred

        var messages = NodeQuery.readMessages(root, profile)
        // 读空先重读一次，别急着下结论。
        //
        // 真机现象（心遇）：IME 面板刚弹出来时，目标 App 的列表节点会短暂缺失，
        // 读一次是空的，状态行就报"还在加载"—— 而屏幕上明明有消息，
        // 再点一次又好了。窗口切换/焦点变化那一两百毫秒就是这样。
        // 这里等一拍、换一份新的根节点再读，把这类瞬时缺失挡掉。
        // 只在**手动**点击时重读。自动触发是事件驱动的，界面一变还会再来一次，
        // 为了它在这睡 400 毫秒只会拖慢主线程；而手动点击是一次性的，
        // 用户正盯着屏幕等结果，这一拍值得。
        if (messages.isEmpty() && manual) {
            SystemClock.sleep(400)
            root = service.targetRoot() ?: root
            messages = NodeQuery.readMessages(root, profile)
            if (messages.isNotEmpty()) {
                service.logAuto("第一次没读到，重读成功（${messages.size} 条）")
            }
        }
        // 上下文来源计数（重读之后再记，否则算的是第一次那份）
        val fromTree = messages.size
        var fromNotify = 0
        var fromClip = 0
        if (messages.isEmpty()) {
            // 通知兜底。他趣这类平台唯一还能看到"对方说了什么"的就是通知栏：
            // 攒的是同一会话最近几条（30 分钟内），有就整段给；
            // 只回最后一条的话，模型看到的是半句话。
            //
            // 注意**不再要求 notifyText 非空**：手动点「生成下一条」时一样该用它 ——
            // 通知是系统投的，不依赖这次点击是怎么触发的。
            val ctxPeer = notifyPeer.orEmpty().ifEmpty { peer }
                .ifEmpty { NotifyContext.lastPeer(pkg, System.currentTimeMillis()) }
            val lines = NotifyContext.recent(pkg, ctxPeer, System.currentTimeMillis())
                .ifEmpty {
                    if (notifyText.isNullOrBlank()) emptyList() else listOf(notifyText.trim())
                }
            if (lines.isNotEmpty()) {
                messages = lines.map { ChatMessage(it, fromOther = true, rect = Rect()) }
                fromNotify = lines.size
                if (lines.size > 1) {
                    service.logAuto("上下文来自通知攒的 ${lines.size} 条")
                }
            }
        }
        // 第三层兜底：节点树和通知都没有时，用「用户事先复制到剪贴板的对方原话」。
        // 这是剪贴板模式真正的价值 —— 连页面完全抓不到的平台也能用：
        // 长按对方消息复制 → 点生成 → 回复进剪贴板 → 长按粘贴 → 自己点发送。
        if (readFallback) {
            ClipboardPeerText.read(service, config)?.let { clip ->
                // 用户复制的一般是"对方刚说的那句"，放在最末当最新一条；
                // 和树/通知里的重复就不重复加
                if (messages.none { it.text == clip }) {
                    messages = messages + ChatMessage(clip, fromOther = true, rect = Rect())
                    fromClip = 1
                    service.logAuto("上下文补了剪贴板：${clip.take(16)}")
                }
            }
        }
        // 读不到树的平台上，"模型到底看到了几句话"是用户唯一能判断草稿靠不靠谱的依据。
        // 正常平台读树就够了，不打印这行（避免日志噪音）。
        if (messages.isNotEmpty() && (fromNotify > 0 || fromClip > 0)) {
            service.logAuto("上下文：树 $fromTree 条 / 通知 $fromNotify 条 / 剪贴板 $fromClip 条")
        }
        if (messages.isEmpty()) {
            if (readFallback) {
                // 剪贴板模式下三种上下文都没拿到，给一条明确的行动指令，
                // 而不是让人对着"没读到消息"猜哪里坏了。
                finish(
                    emptyList(), "",
                    "没拿到对方的话：先在聊天页长按对方消息复制，再点生成",
                    false, false, onDone,
                )
                return
            }
            if (profile.message.isEmpty()) {
                // 他趣这类：App 把整棵树摘掉了，读消息在设计上就不可能。
                // 说清楚"这不是坏了"，比留一句"没读到"有用得多 ——
                // 否则用户看着屏幕上明明有「你好」，只会以为程序瞎了。
                finish(
                    emptyList(), "",
                    "这个平台读不到聊天记录 —— 只能等对方发消息时由通知触发",
                    false, false, onDone,
                )
                return
            }
            // 界面明明有消息、程序说没有：这是最难自己查的失败。
            // **诊断一定要先写进日志**（含命中/排除/坐标各几条），
            // 否则只剩一句"还在加载"，下次又只能靠猜。
            val diag = NodeQuery.emptyDiagnosis(root, profile)
            service.logAuto("读不到消息 · $diag")
            val loading = NodeQuery.loadingHint(root)
            if (loading != null) {
                // 还在加载 → 这次不算"读不到消息"，1.5 秒后让界面事件再触发一轮。
                service.postponeAuto(1_500)
                finish(emptyList(), "", "$loading，稍等再试", false, false, onDone)
                return
            }
            finish(emptyList(), "", "没读到消息 —— 首页日志里有诊断", false, false, onDone)
            return
        }

        val lastOther = messages.lastOrNull { it.fromOther }
        if (lastOther == null) {
            finish(emptyList(), "", "最后一条不是对方发的", false, false, onDone)
            return
        }

        // 聊天记录（默认关）：记「读到的对话」，位置在去重之前 ——
        // 这条消息就算 30 分钟内处理过、不再生成，它确实是对方说过的话。
        if (config.chatLogEnabled) {
            ChatLogStore.record(
                service,
                platformLabel(profile),
                peer,
                messages.map { ChatLogStore.Entry(it.text, it.fromOther) },
            )
        }

        val now = System.currentTimeMillis()
        val dedup = ReplyDedup(service)
        // 指纹带昵称：交友平台大量新会话第一句都是「你好」，
        // 只按文本去重会把第二个会话直接锁死。
        // peer 在上面就取好了（界面 → 通知标题），这里不再重复读树。

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

        // 屏幕只能看到"当前可见"的那几条，滚上去的就没了 ——
        // 用会话记忆把之前真正读到过的接回来，否则会出现"刚聊过又重问一遍"。
        val turns = ConversationMemory.merge(
            ConversationMemory.keyOf(pkg, peer),
            messages.map { Turn(it.text, it.fromOther) },
        )
        // 面板和日志都要知道"这次模型看到了几句"。草稿不对时这是第一判据：
        // 3 条说明读少了（通知/剪贴板没攒到），11~12 条说明读到了，问题在别处。
        CaptureService.lastContextTurns = turns.size
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
        // 写侧降级：只对**真的写不进输入框**的平台退成剪贴板。
        // 读侧降级（readFallback）不影响这里 —— 他趣读不到树，但输入法通道能填。
        val writeToClipboard = config.clipboardOnly || profile.clipboardPreferred

        // ---- 剪贴板交付：抓不到输入框的平台走这一条 ----
        //
        // 刻意**不断句**：断句队列靠"检测到你点发送 → 自动填下一段"推进，
        // 而剪贴板模式下没有输入框可自动填，队列只会卡住或粘错。
        // 整段一次给全，由人自己决定粘多少、分几次发。
        if (writeToClipboard && outcome is ReplyFlow.Outcome.Fill) {
            DraftQueue.clear()
            val copied = sink.copyToClipboard(outcome.text)
            config.lastClipboardDraft = if (copied) outcome.text else ""
            // 去重/防抖照旧：写剪贴板不改界面，但窗口事件仍会来。
            // 不 mark 的话界面每变一次就重生成一遍，剪贴板被覆盖成最后一句，
            // 用户粘到的就不是刚看过的那条（这个坑在自动填入路径上踩过）。
            dedup.mark(peer, messageText, now)
            service.noteFill()
            config.recordUsage(outcome.inputTokens, outcome.outputTokens)
            val note = if (copied) {
                // 多发开场白在剪贴板模式下是整段复制的（没有输入框可自动填），
                // 得说清楚"这几行是分开发的"，否则用户会一次全粘成一条。
                val howMany = if (outcome.parts.size > 1) {
                    "（共 ${outcome.parts.size} 条，按行分开发）"
                } else {
                    ""
                }
                "已复制$howMany · 回聊天页长按粘贴，自己点发送"
            } else {
                "剪贴板写不进去（被别的 App 占用？）"
            }
            finish(trace, outcome.text, tag + note, false, copied, onDone)
            if (config.chatLogEnabled) {
                ChatLogStore.draft(
                    service, platformLabel(profile), peer, outcome.text,
                    if (copied) "已复制到剪贴板" else "未交付",
                )
            }
            return
        }

        when (outcome) {
            is ReplyFlow.Outcome.Fill -> {
                // 断句：一句话拆成 2~3 条发，模仿真人"想到一句发一句"。
                // 只填第一段，剩下的排队 —— 每段都要你单独点一次发送。
                //
                // 三种来源，优先级从高到低：
                //   ① outcome.parts —— 多发开场白：用户自己写的几条整句，绝不能拆
                //   ② single=true   —— 库里的一行也是一整句（拆开会丢问句）
                //   ③ 其余          —— 交给 SentenceSplitter 按标点拆
                val rawParts = when {
                    outcome.parts.isNotEmpty() -> outcome.parts
                    outcome.single -> listOf(outcome.text)
                    else -> SentenceSplitter.split(outcome.text)
                }
                // 分段推进靠「读到我方消息条数变了」确认上一段真的发出去了。
                // 树被摘的平台（他趣）永远读不到 —— 排队只会停在第二段，
                // 用户看到的就是"只回了一句"。
                // 所以这类平台把多段**合成一条**填出去，发的时候人自己决定分不分。
                val canAdvance = profile.message.isNotEmpty()
                val parts = if (!canAdvance && rawParts.size > 1) {
                    listOf(rawParts.joinToString("\n"))
                } else {
                    rawParts
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
                // 多段被合成一条（读不到树的平台）：说清楚，否则用户以为只回了一半
                val mergedHint = if (rawParts.size > 1 && parts.size == 1) {
                    "（${rawParts.size} 段合在一起，发的时候可以分开）"
                } else {
                    ""
                }
                // write.note 里已经带了"已复制到剪贴板"这句（TextWriter 写的），
                // 这里再拼一遍就变成「已复制到剪贴板（已复制到剪贴板）」
                val note = when {
                    copied && parts.size > 1 ->
                        "${write.note}；第 1 段在上面，剩下 ${parts.size - 1} 段发完自动复制"
                    // 输入法已挂起、等焦点：剪贴板里也有一份，两条路都能走
                    copied && write.pending -> "点一下输入框草稿会出现；也已复制" + mergedHint
                    copied -> write.note + mergedHint
                    else -> write.note + segHint + mergedHint
                }
                finish(
                    trace, outcome.text, tag + note, write.written, copied, onDone,
                    needsIme = write.needsIme,
                )
                if (config.chatLogEnabled) {
                    ChatLogStore.draft(
                        service, platformLabel(profile), peer, outcome.text,
                        when {
                            write.written -> "已填入输入框"
                            write.pending -> "已挂起：点一下输入框即填入"
                            copied -> "已复制到剪贴板"
                            else -> "未交付"
                        },
                    )
                }
            }

            is ReplyFlow.Outcome.Hold -> {
                // 风险太高：不替你填，但把草稿放到剪贴板，你要用自己粘贴
                val copied = sink.copyToClipboard(outcome.text)
                if (copied) config.lastClipboardDraft = outcome.text
                config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                finish(trace, outcome.text, tag + outcome.reason, false, copied, onDone)
                if (config.chatLogEnabled) {
                    ChatLogStore.draft(
                        service, platformLabel(profile), peer, outcome.text, "仅草稿，不建议发送",
                    )
                }
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

    // 读取剪贴板对方原话的过滤规则已抽到 [ClipboardPeerText] ——
    // 首页「生成并填入」按钮也要用它，两处共用一份，避免规则漂移。

    /** 日志/记录里显示的名字：优先中文名，没有就用包名。 */
    private fun platformLabel(profile: PlatformProfile): String =
        profile.label.ifEmpty { profile.pkg }

    private fun finish(
        trace: List<String>,
        draft: String,
        note: String,
        filled: Boolean,
        copied: Boolean,
        onDone: ((Result) -> Unit)?,
        needsIme: Boolean = false,
    ) {
        onDone?.invoke(Result(trace, draft, note, filled, copied, needsIme))
    }
}
