package com.autoreply.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.app.Activity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.autoreply.app.R
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.guard.DraftQueue
import com.autoreply.app.core.guard.PacerGuard
import com.autoreply.app.core.guard.ReplyDedup
import com.autoreply.app.core.guard.ReplyFlow
import com.autoreply.app.core.guard.SentenceSplitter
import com.autoreply.app.core.guard.SentTracker
import com.autoreply.app.core.llm.ReplyEngine
import com.autoreply.app.core.llm.ConversationMemory
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.log.ChatLogStore
import com.autoreply.app.core.persona.PersonaLoader
import com.autoreply.app.core.persona.PersonaPresets
import com.autoreply.app.notify.NotifyContext
import com.autoreply.app.platform.AccessibilityReplySink
import com.autoreply.app.platform.ChatMessage
import com.autoreply.app.platform.ClipboardPeerText
import com.autoreply.app.platform.FillResult
import com.autoreply.app.platform.NodeQuery
import com.autoreply.app.platform.Profiles
import com.autoreply.app.platform.TextWriter
import kotlin.random.Random

/**
 * 首页。刻意只有状态 + 几个开关，不放任何"开始"主动作 ——
 * 因为本 App 没有"全自动跑"这个状态：它收到消息才动，且发送永远由人点。
 * 给它做一个大开关会让人误以为"点了就托管了"。
 */
class MainActivity : Activity() {

    private lateinit var config: AppConfig
    private lateinit var statusService: TextView
    private lateinit var statusNotify: TextView
    private lateinit var statusLevel: TextView
    private lateinit var output: TextView

    /** 麦克风权限的请求码（只有「说一句」用得上）。 */
    private val reqMic = 9101

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = AppConfig(this)

        // 内置人设（assets/personas）：只装一次、不激活。
        // 放在启动时做，这样输入法面板里的人设列表也能看到它，
        // 不用先绕去「话术管理」才出现。
        PersonaPresets.seedBundled(this)

        // 输入法面板里点「说一句」但还没授权麦克风 → 会带着这个 extra 跳过来要一次。
        // 权限必须由一个 Activity 要，输入法服务（Service）自己弹不出来。
        if (intent?.getBooleanExtra("ask_mic", false) == true) askMic()

        statusService = findViewById(R.id.status_service)
        statusNotify = findViewById(R.id.status_notify)
        statusLevel = findViewById(R.id.status_level)
        output = findViewById(R.id.output)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_notify).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btn_open_target).setOnClickListener {
            val targets = config.targetPackages
            if (targets.isEmpty()) {
                toast("先在设置里填目标应用包名")
                return@setOnClickListener
            }
            var opened = false
            for (pkg in targets) {
                val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
                startActivity(launch)
                opened = true
                break
            }
            if (!opened) {
                toast("没找到已安装的目标应用：${targets.joinToString(" / ")}")
            }
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 同城 / 他趣 的输入框对无障碍不可见，只能靠「简输入」这条通道写入。
        // 而大多数人的默认输入法是搜狗/百度 —— 填不进去时最费解的一句话就是
        // 「无障碍填不进去」，其实就是没切输入法。
        //
        // 系统没有公开 API 能"自动切"（那要 WRITE_SECURE_SETTINGS），
        // 但可以一步跳到选择器：用户点一下、选「简输入」就完事。
        findViewById<Button>(R.id.btn_ime).setOnClickListener {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val ok = runCatching { imm?.showInputMethodPicker() }.isSuccess
            if (!ok || imm == null) {
                output.text = "打不开输入法选择器 —— 去 设置 → 系统 → 语言和输入法 手动切到「简输入」"
            }
        }

        findViewById<Button>(R.id.btn_dump).setOnClickListener {
            val service = CaptureService.instance
            if (service == null) {
                toast("读屏没开：点「开启读屏」→ 找到 AutoReply → 打开")
                return@setOnClickListener
            }
            val snapshot = service.snapshot()
            if (snapshot == null) {
                // 抓不到时把窗口清单也一并显示：这样"为什么抓不到"有据可查，
                // 而不是只能看到一句提示。
                output.text = buildString {
                    append(getString(R.string.hint_no_dump))
                    append("\n\n# ---- 当前窗口（排查用）----\n")
                    append(service.describeWindows())
                }
                toast("没抓到目标界面，已列出当前窗口")
                return@setOnClickListener
            }
            output.text = snapshot.text
            toast("抓到 ${snapshot.text.lines().size} 行（${snapshot.source}）")
        }

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val content = output.text?.toString().orEmpty()
            if (content.isBlank()) {
                toast("还没有内容可复制")
                return@setOnClickListener
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("autoreply-dump", content))
            toast("已复制 ${content.length} 个字符")
        }

        findViewById<Button>(R.id.btn_probe).setOnClickListener { runProbe() }
        findViewById<Button>(R.id.btn_generate).setOnClickListener { runGenerate() }
        findViewById<Button>(R.id.btn_next).setOnClickListener { runNextAndFill() }
        findViewById<Button>(R.id.btn_delayed).setOnClickListener { runDelayedFill() }
        findViewById<Button>(R.id.btn_batch).setOnClickListener { toggleBatch() }
    }

    /**
     * 真实流程：读消息 → 调模型生成 → 填进输入框。
     *
     * 这里有一条跨线程的链：网络请求必须在后台线程（主线程会抛
     * NetworkOnMainThreadException），而无障碍操作必须在主线程
     * （AccessibilityNodeInfo 不是线程安全的）。所以是"后台生成、主线程填"。
     */
    private fun runGenerate() {
        val service = CaptureService.instance
        if (service == null) {
            toast("读屏没开")
            return
        }
        // 前置检查放在最前面：没 Key 时让网络请求先跑一遍再失败，
        // 报错会被埋在"生成失败"里，看起来像模型坏了。
        // 而且这解释了另一个高频误解 —— 没配 Key 时「试填」只能复读原文，
        // 那是它本来的行为，不是生成坏了。
        //
        // 例外：接口地址填 mock 时走本地假回复，不需要 Key（离线验证管道用）。
        val mockMode = config.llmBaseUrl.trim().equals("mock", ignoreCase = true)
        if (!mockMode && config.llmApiKey.isBlank()) {
            output.text = buildString {
                append("还没配 API Key，所以只能「试填（复读原文）」。\n\n")
                append("去 设置 → 模型，填三项：\n")
                append("  接口地址  https://api.deepseek.com/v1\n")
                append("  API Key   你的 key\n")
                append("  模型名    deepseek-chat\n\n")
                append("想先离线验证整条链路，把接口地址改成 mock（Key 留空也行），\n")
                append("它会用固定的几句假话回答 —— 能验证「判定 → 生成 → 清洗 → 填字」，\n")
                append("但验证不了语气，那个只有真模型能给。")
            }
            return
        }
        val root = service.targetRoot()
        if (root == null) {
            output.text = "目标界面不在前台。先切到聊天页停留 2 秒，再切回来点这个按钮。"
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val profile = Profiles.forPackage(pkg)
        if (profile == null) {
            output.text = "「$pkg」还没有界面配置。"
            return
        }

        // 读侧降级（全局开关 / 平台 readFallback / 整条剪贴板）。
        // 与 ReplyRunner.runOnce 的兜底共用 [ClipboardPeerText]：
        // 页面抓不到时，用户长按复制的对方原话就是上下文。
        val readFallback = config.clipboardOnly || profile.readFallback || profile.clipboardPreferred
        // 写侧降级只影响交付方式，不影响"上下文从哪来" —— 他趣读不到，但能填。
        val writeToClipboard = config.clipboardOnly || profile.clipboardPreferred
        // 昵称提前取：通知攒的上下文按「平台+昵称」存，兜底要用它
        val peer = NodeQuery.peerName(root, profile)
        var messages = NodeQuery.readMessages(root, profile)
        // 读空先重读一次：面板刚弹出时目标 App 的列表节点会短暂缺失（心遇真机），
        // 等一拍、换一份新根再读，比直接报"没读到"有用得多。
        if (messages.isEmpty() && !readFallback) {
            SystemClock.sleep(400)
            val fresh = service.targetRoot() ?: root
            messages = NodeQuery.readMessages(fresh, profile)
        }
        if (messages.isEmpty()) {
            // 通知攒的兜底（他趣：树被摘，只有通知栏还能看到"对方说了什么"）。
            // peer 读不到时用「最近来通知的那个人」—— 只在他趣这类平台才有意义。
            val lines = NotifyContext.recent(
                pkg,
                peer.ifEmpty { NotifyContext.lastPeer(pkg, System.currentTimeMillis()) },
                System.currentTimeMillis(),
            )
            if (lines.isNotEmpty()) {
                messages = lines.map { ChatMessage(it, fromOther = true, rect = Rect()) }
            }
        }
        if (readFallback) {
            ClipboardPeerText.read(this, config)?.let { clip ->
                // 复制的那句放最末当最新一条；和通知/树里的重复就不重复加
                if (messages.none { it.text == clip }) {
                    messages = messages + ChatMessage(clip, fromOther = true, rect = Rect())
                }
            }
        }
        if (messages.isEmpty()) {
            // 先分清"还没渲染"和"选择器失效"：前者让人去改配置就是纯浪费
            val loading = NodeQuery.loadingHint(root)
            output.text = when {
                readFallback -> "没拿到对方的话：先在聊天页长按对方消息复制，再回来点生成。"
                loading != null -> "$loading。等 1~2 秒再点一次。"
                else -> "没读到消息，选择器可能失效。\n" + NodeQuery.emptyDiagnosis(root, profile)
            }
            return
        }
        val lastOther = messages.lastOrNull { it.fromOther }
        if (lastOther == null) {
            output.text = "最后几条都不是对方发的，不生成。"
            return
        }

        // ---- 去重：同一条消息 30 分钟内只处理一次 ----
        // 指纹带对方昵称 —— 交友平台大量新会话第一句都是「你好」，
        // 只按文本去重会把第二个会话直接锁死（这个坑踩过）。
        val now = System.currentTimeMillis()

        // 和 ReplyRunner 同一条规则：屏幕只能看到可见区，滚上去的靠会话记忆补回来。
        // （必须放在 peer 之后 —— 记忆是按「平台+昵称」分桶的。）
        // 名字为空时不碰记忆：空 key 会把所有人的对话挤进一个桶。
        val named = peer.isNotBlank()
        val turns = if (named) {
            ConversationMemory.merge(
                ConversationMemory.keyOf(pkg, peer),
                messages.map { Turn(it.text, it.fromOther) },
            )
        } else {
            messages.map { Turn(it.text, it.fromOther) }
        }
        // 记忆判定"换人了"→ 输入法那边挂着的后续分段不能留给下一个人
        if (named && ConversationMemory.lastReset) {
            com.autoreply.app.core.guard.DraftQueue.clear()
        }

        // 聊天记录（默认关）：记「读到的对话」。和 ReplyRunner 是同一条路径的另一半 ——
        // 首页按钮不经过 ReplyRunner，这里漏了就会「自动触发有记录、手动点击没有」。
        val platformLabel = profile.label.ifEmpty { pkg }
        if (config.chatLogEnabled) {
            ChatLogStore.record(
                this,
                platformLabel,
                peer,
                messages.map { ChatLogStore.Entry(it.text, it.fromOther) },
            )
        }

        // 先看是不是又发出去了一条（同会话里我发的最后一条变了）。
        // 不依赖"草稿被原样发出"：改几个字、自己手打，都是真实发送。
        if (SentTracker(this).detectNewSent(messages, peer, now)) {
            PacerGuard(this, config).recordSent(now)
        }

        val dedup = ReplyDedup(this)
        // **手动点击越过去重窗口。**
        //
        // 去重是给"自动触发"用的：界面一变就重新生成，同一条消息会被回好几遍。
        // 但手动点「生成并填入」是用户明确要求重来 —— 卡住不动的时候，
        // 这是唯一能自救的动作。原来这里直接 return，于是"卡住"就真的没救了：
        // 只能等满 30 分钟，或者等对方再发一句。
        //
        // 代价是连点两次会得到两条意思相近的草稿；手动本来就是一次一击，换得来。
        val wasHandled = dedup.isDuplicate(peer, lastOther.text, now)

        output.text = buildString {
            if (wasHandled) {
                append("（这条 30 分钟内处理过，这次是你手动要求重来）\n")
            }
            append("正在判定…（对方最后一句：").append(lastOther.text.take(24)).append("）")
        }

        // 「我上一句是多久之前说的」只认**确认发出**的时间。
        // 之前用的是"上次生成草稿"的时间，导致连点几次测试（一条都没发）就被判成
        // 「20 秒前刚说过话 -0.25」，分数一路往下掉，最后每条都判"不回"。
        val lastSent = SentTracker(this).lastConfirmedAt
        val selfAgo = if (lastSent <= 0L) {
            Int.MAX_VALUE
        } else {
            ((now - lastSent) / 1000L).toInt().coerceAtLeast(0)
        }

        Thread {
            // ReplyFlow 构造时会读 assets/persona.json，属于 IO，放主线程是 StrictMode 隐患。
            // 而且每点一次都重建，改完人设能立刻生效（不用重启 App）。
            val flow = ReplyFlow(this@MainActivity, config)
            // 会话记忆：读"她是谁"、写回这一轮抽到的事实。和 ReplyRunner 同一条规则 ——
            // 首页按钮不经过 ReplyRunner，这里漏了就会"自动触发记得、手动点击忘"。
            val facts = com.autoreply.app.core.memory.SessionStore
                .digest(this@MainActivity, pkg, peer)
            com.autoreply.app.core.memory.SessionStore
                .observe(this@MainActivity, pkg, peer, turns)
            val outcome = flow.decide(
                turns,
                selfSaidAgoSeconds = selfAgo,
                now = now,
                // 手动点击 = 用户明确要求。精力和长冷却都是拟人机制，此时该让路；
                // 每小时上限与熔断仍然生效（那是真风险护栏）。
                respectEnergy = false,
                respectCooldown = false,
                // 手动点击也越过了「想回但先等」那一层 ——
                // 原来只放开精力和冷却，对方回个「在」被判 0.28 分时，
                // 手动点也不动，界面上看着像程序坏了
                manual = true,
                peer = peer,
                facts = facts,
            )
            // trace 必须在这里取：下一次 decide 会清空它
            val trace = flow.lastTrace
            runOnUiThread {
                // 判定要几秒，这期间用户可能已经退出页面
                if (isFinishing || isDestroyed) return@runOnUiThread

                val head = buildString {
                    // 带时间戳：这块是"这次试填的结果"，不会跟着输入法面板的动作刷新。
                    // 用户拿它去和面板下方的「草稿」行对照时，两张纸其实来自不同时刻 ——
                    // 时间戳至少能让人一眼看出"这是上一次的"。
                    append("判定链（试填 ")
                    append(android.text.format.DateFormat.format("HH:mm:ss", now))
                    append("）：\n")
                    trace.forEach { append("  ").append(it).append('\n') }
                }

                when (outcome) {
                    is ReplyFlow.Outcome.Fill -> {
                        // ---- 剪贴板交付：与 ReplyRunner.deliver 同一套早返回 ----
                        // 只有**写不进输入框**的平台走这条（或全局开了剪贴板模式）：
                        // 整段**不断句**（断句队列靠"检测到发送→自动填下一段"推进，
                        // 这里没有输入框可填，队列只会卡住）、只进剪贴板、不碰输入框。
                        if (writeToClipboard) {
                            DraftQueue.clear()
                            val sink = AccessibilityReplySink(service, profile)
                            val copied = sink.copyToClipboard(outcome.text)
                            config.lastClipboardDraft = if (copied) outcome.text else ""
                            // 去重/防抖照旧：不 mark 的话界面每变一次就重生成一遍
                            dedup.mark(peer, lastOther.text, now)
                            service.noteFill()
                            if (!mockMode) config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                            output.text = buildString {
                                append(head)
                                append("\n草稿（已进剪贴板）：").append(outcome.text)
                                if (copied) {
                                    append("\n\n回聊天页 → 点输入框 → 长按 → 粘贴 → 自己点发送。")
                                    if (outcome.parts.size > 1) {
                                        append("\n（共 ${outcome.parts.size} 条，按行分开发）")
                                    }
                                } else {
                                    append("\n\n剪贴板没写进去（被别的 App 占用？），请手动复制上面这段。")
                                }
                                if (!mockMode) {
                                    appendUsage(
                                        outcome.inputTokens,
                                        outcome.outputTokens,
                                        outcome.estimatedInputTokens,
                                    )
                                }
                            }
                            if (config.chatLogEnabled) {
                                ChatLogStore.draft(
                                    this@MainActivity, platformLabel, peer, outcome.text,
                                    if (copied) "已复制到剪贴板" else "未交付",
                                )
                            }
                            return@runOnUiThread
                        }
                        val sink = AccessibilityReplySink(service, profile)
                        // 断句：拆成 2~3 条发，只填第一段，其余排队等你发完再填。
                        // 和 ReplyRunner 里是同一套逻辑 —— 手动/自动两条路必须一致，
                        // 否则"手动能拆、自动不拆"这种差异查起来最费劲。
                        // 三种来源：多发开场白的 parts → 整句 single → 按标点拆。
                        val rawParts = when {
                            outcome.parts.isNotEmpty() -> outcome.parts
                            outcome.single -> listOf(outcome.text)
                            else -> SentenceSplitter.split(outcome.text)
                        }
                        // 读不到我方消息的平台（他趣）确认不了"上一段已发出"，
                        // 排队只会停在第二段 —— 合成一条填，人自己决定分不分。
                        // 和 ReplyRunner 同一条规则，手动/自动不能有差异。
                        val canAdvance = profile.message.isNotEmpty()
                        val parts = if (!canAdvance && rawParts.size > 1) {
                            listOf(rawParts.joinToString("\n"))
                        } else {
                            rawParts
                        }
                        DraftQueue.reset(parts.drop(1))

                        // 两条通道：无障碍优先，失败自动落到输入法
                        val write = TextWriter.write(service, profile, parts.first())
                        // 只要没真写进输入框就留一份在剪贴板 —— 包括"输入法挂起等焦点"
                        // 的情况：那条路可能等不到人点，手里有份可粘的总比没有强。
                        // 与 ReplyRunner.deliver 保持一致（那边一直是这么做的）。
                        val copied = if (write.written) {
                            false
                        } else {
                            // 只放第一段：整段塞进剪贴板，用户粘一次就发完了，
                            // 分段就没意义了
                            sink.copyToClipboard(parts.first())
                        }
                        // 只有真进了输入框才记"已处理"
                        if (write.written) {
                            dedup.mark(peer, lastOther.text, now)
                            // 记下"已填、待确认发出"：消息列表里出现这条时才计配额
                            SentTracker(this@MainActivity).markPending(peer, parts.first(), now)
                            // 把"我现在发了几条"钉成基线 —— 否则新会话的第一条永远漏检
                            SentTracker(this@MainActivity).markBaseline(
                                peer.ifEmpty { messages.firstOrNull()?.text?.take(12).orEmpty() },
                                messages.count { !it.fromOther },
                            )
                            // 填入会让界面变化，那个变化又会触发自动回复 —— 5 秒内跳过
                            service.noteFill()
                            // 手动模式也给"下一段"排个兜底
                            service.scheduleNextSegment()
                        } else {
                            // 剪贴板/排队路径：一样要等用户去发，兜底不能缺 ——
                            // 否则没切输入法时，第二段之后就再无保底
                            dedup.mark(peer, lastOther.text, now)
                            service.noteFill()
                            service.scheduleNextSegment()
                        }
                        if (!mockMode) config.recordUsage(outcome.inputTokens, outcome.outputTokens)

                        output.text = buildString {
                            append(head)
                            append("\n草稿：").append(outcome.text)
                            if (parts.size > 1) {
                                append("\n（拆成 ").append(parts.size)
                                append(" 段：发完这段会自动填下一段）")
                            } else if (rawParts.size > 1) {
                                append("\n（").append(rawParts.size)
                                append(" 段合在一起，发的时候可以分开）")
                            }
                            append("\n\n填入：").append(write.note)
                            if (copied && write.pending) {
                                append("\n也已复制：点一下输入框草稿会自动出现，万一没出现就长按粘贴")
                            } else if (copied) {
                                append("\n已复制到剪贴板：点一下输入框 → 长按 → 粘贴")
                            }
                            if (!write.written && !write.pending) {
                                val diagRoot = service.targetRoot() ?: root
                                append("\n\n").append(NodeQuery.diagnoseInput(diagRoot, profile))
                            }
                            if (!mockMode) {
                                appendUsage(
                                    outcome.inputTokens,
                                    outcome.outputTokens,
                                    outcome.estimatedInputTokens,
                                )
                            }
                        }
                        if (config.chatLogEnabled) {
                            ChatLogStore.draft(
                                this@MainActivity, platformLabel, peer, outcome.text,
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
                        // 风险太高：草稿给你看，但不替你填。
                        // 填进输入框的话人多半顺手就发了 —— 那是用界面诱导犯错。
                        val sink = AccessibilityReplySink(service, profile)
                        val copied = sink.copyToClipboard(outcome.text)
                        // 和 ReplyRunner 的 Hold 分支一致：记下自己写的草稿，
                        // 剪贴板模式下才不会把它当成对方的新消息（自己回自己）
                        if (copied) config.lastClipboardDraft = outcome.text
                        if (!mockMode) config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                        output.text = buildString {
                            append(head)
                            append("\n草稿（不自动填）：").append(outcome.text)
                            append("\n\n为什么没填：").append(outcome.reason)
                            if (copied) append("\n\n已复制到剪贴板，你要用就自己粘贴。")
                        }
                        if (config.chatLogEnabled) {
                            ChatLogStore.draft(
                                this@MainActivity, platformLabel, peer, outcome.text,
                                "仅草稿，不建议发送",
                            )
                        }
                    }

                    is ReplyFlow.Outcome.Wait -> {
                        val minutes = (outcome.afterMs / 60_000L).coerceAtLeast(1L)
                        output.text = buildString {
                            append(head)
                            append("\n等 $minutes 分钟再回：").append(outcome.reason)
                            append("\n\n（现在填也不合适 —— 判定链认为时机不对）")
                        }
                    }

                    is ReplyFlow.Outcome.Silent -> {
                        output.text = buildString {
                            append(head)
                            append("\n这条不参与：").append(outcome.reason)
                        }
                    }

                    is ReplyFlow.Outcome.Error -> {
                        output.text = buildString {
                            append(head)
                            append("\n生成失败：").append(outcome.reason)
                        }
                    }
                }
            }
        }.start()
    }

    /**
     * 用量三行。抽出来是因为「生成并填入」的几个分支都要写同一段，
     * 而"服务端口径 vs 本地估算"的对照以后可能还要调。
     */
    private fun StringBuilder.appendUsage(
        inputTokens: Int,
        outputTokens: Int,
        estimatedInputTokens: Int,
    ) {
        append("\n\n本次：输入 $inputTokens / 输出 $outputTokens token（服务端口径）")
        append("\n本地估算：约 $estimatedInputTokens token 输入")
        // 差三倍以上基本可以确定不是我们发的东西变多了，
        // 而是接口这一层往请求里加料（自带的 system、工具定义），
        // 或者直接改写了 usage —— 那种数字不能用来估成本。
        if (estimatedInputTokens > 0 && inputTokens > estimatedInputTokens * 3 + 50) {
            append("\n⚠ 两者差很多：多半是接口/中转改写了 usage，或往请求里加了自带的提示词。")
            append("算钱时以「本地估算」为准。")
        }
        append("\n累计：${config.llmCalls} 次调用，输入 ${config.llmTokensIn} / 输出 ${config.llmTokensOut} token")
    }

    /**
     * 连点：切下一个未读 → 填草稿 → 你点发送 → 自动切下一个。
     *
     * **刻意不做成"自动发送"。** 发送是这条链上唯一有对外后果、且不可撤销的动作，
     * 必须留在人手里；连点只自动化"切会话 + 写草稿"这些没有后果的部分。
     *
     * 也不用定时器：每一步的推进信号是"你真的发出去了"，
     * 间隔来自你的手速，天然不规则 —— 固定间隔的循环是最容易被识别的行为。
     */
    private fun toggleBatch() {
        val service = CaptureService.instance
        if (service == null) {
            toast("读屏没开")
            return
        }
        if (service.batchActive) {
            service.stopBatch()
            output.text = "连点已停止。"
        } else {
            val n = config.batchSize
            service.startBatch(n)
            output.text = buildString {
                append("连点开始：$n 个\n\n")
                append("流程：切到下一个未读 → 生成草稿 → 等你点发送 → 自动切下一个\n")
                append("（当前会话如果还有没回的，会先处理它）\n")
                append("45 秒没发就跳过这个。随时可以点「停止连点」。")
            }
        }
        refresh()
    }

    /**
     * 倒计时 5 秒后执行「读 → 生成 → 填」。
     *
     * 存在的理由很具体：点按钮必须切回本 App，而一旦本 App 回到前台，
     * 目标 App 的输入焦点就丢了 —— 焦点一丢，那个本来就不在无障碍树里的输入框
     * 更无从下手。所以给它 5 秒：用户切回去、点开输入框，程序在后台跑。
     *
     * 这也是最接近最终形态的用法：人停在会话里，程序在后台把草稿准备好。
     */
    private fun runDelayedFill() {
        if (CaptureService.instance == null) {
            toast("读屏没开")
            return
        }
        var left = 5
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    toast("$left 秒后执行：切回目标 App，点一下输入框")
                    output.text = buildString {
                        append("倒计时 $left 秒\n\n")
                        append("现在切回目标 App，点一下输入框让光标留在里面。\n")
                        append("执行结果会写在这里（完成后切回本 App 查看）。")
                    }
                    left--
                    uiHandler.postDelayed(this, 1000)
                } else {
                    // 读不到消息的平台（他趣：整棵树被 noimp 摘掉）在这里会走不动正式流程。
                    // 但"填字能不能成"是独立的问题，值得单独验一次 ——
                    // 否则这类平台连"能不能做"都判断不了。
                    val svc = CaptureService.instance
                    val root = svc?.targetRoot()
                    val prof = root?.let { Profiles.forPackage(it.packageName?.toString()) }
                    val msgs = if (root != null && prof != null) {
                        NodeQuery.readMessages(root, prof)
                    } else {
                        emptyList()
                    }

                    if (svc != null && root != null && prof != null && msgs.isEmpty()) {
                        val probeSink = AccessibilityReplySink(svc, prof)
                        val probeText = "测试填写"
                        val fr = probeSink.fill(probeText)
                        output.text = buildString {
                            append("读不到消息（整棵树被隐藏），改测填字。\n\n")
                            append("写入「$probeText」：")
                            append(
                                when (fr) {
                                    is FillResult.Ok -> "成功 ✓ 这个平台能填，只差消息来源"
                                    is FillResult.Unavailable -> "失败 — ${fr.reason}"
                                    is FillResult.Mismatch -> "回读不一致 — ${fr.actual}"
                                }
                            )
                            append("\n\n").append(NodeQuery.diagnoseInput(root, prof))
                            append("\n\n（切回去把输入框里那句删掉）")
                        }
                    } else {
                        output.text = "执行中…"
                        runGenerate()
                    }
                }
            }
        }
        uiHandler.postDelayed(tick, 0)
    }

    /**
     * 点平台自带的「下一个未读」，等界面换过去，再走一遍完整的生成流程。
     *
     * **刻意做成一次一步，不做循环。** 节奏由人按，程序不负责"连续处理 N 个会话"：
     * 一旦写成循环 + 定时器，行为就从"人点了一下"变成"机器按固定间隔跑"，
     * 那是所有自动化特征里最容易被识别的一种。点多少个、隔多久，交给人决定。
     */
    private fun runNextAndFill() {
        val service = CaptureService.instance
        if (service == null) {
            toast("读屏没开")
            return
        }
        val root = service.targetRoot()
        if (root == null) {
            output.text = "目标界面不在前台。先切到聊天页，再点这个按钮。"
            return
        }
        val profile = Profiles.forPackage(root.packageName?.toString())
        if (profile == null) {
            output.text = "「${root.packageName}」还没有界面配置。"
            return
        }
        if (profile.nextUnread.isEmpty()) {
            output.text = "这个平台没有配置「下一个未读」按钮，只能手动切会话。\n" +
                "（不同平台入口不同，有的在会话列表里，没有统一名字）"
            return
        }
        // 先 ACTION_CLICK；无效就退回坐标手势 —— 这类按钮常是自定义 View，
        // ACTION_CLICK 会返回 true 但界面不动（"点了没反应"就是这个原因）
        //
        // **必须写参数名**：函数现在多了第 4 个参数（onNote），
        // 尾随 lambda 会绑到最后一个参数上，而不是 tap —— 不写名字就会是
        // 「String 不能赋给 AccessibilityNodeInfo」这种莫名其妙的编译错误。
        // 按钮有时挂在同一个 App 的另一个窗口里，只搜当前根会"看得见却找不到"
        val clicked = NodeQuery.clickNextUnread(
            service.allTargetRoots().ifEmpty { listOf(root) }, profile,
            tap = { node -> TextWriter.tapNode(service, node) },
        )
        if (!clicked) {
            output.text = "没点到「下一个未读」。可能被弹窗挡住，或当前不在聊天页。"
            return
        }

        output.text = "已切到下一个会话，等界面加载后自动生成…"
        // 固定延迟会让"点开 → 填字"的间隔像定时器，加一点随机更像人在操作
        val delay = 1400L + Random.nextLong(800)
        uiHandler.postDelayed({ runGenerate() }, delay)
    }

    /**
     * 链路自检：读消息 → 拼一句测试草稿 → 填进输入框 → 回读校验。
     *
     * 刻意不接模型：这一步要证明的是「能不能读到、能不能写进去」，
     * 模型接进来只会让失败原因变得模糊 —— 到底是读错了、写错了，还是模型答错了。
     * 链路通了再接模型，出问题一眼知道该查哪。
     */
    private fun runProbe() {
        val service = CaptureService.instance
        if (service == null) {
            toast("读屏没开")
            return
        }
        val root = service.targetRoot()
        if (root == null) {
            output.text = buildString {
                append("目标界面不在前台。\n")
                append("先切到目标 App 的聊天页，停留 2 秒，再切回来点这个按钮。\n\n")
                append("# ---- 当前窗口 ----\n")
                append(service.describeWindows())
            }
            return
        }

        val pkg = root.packageName?.toString().orEmpty()
        val profile = Profiles.forPackage(pkg)
        if (profile == null) {
            val known = Profiles.supported().joinToString("\n").ifEmpty { "（一个都没有）" }
            output.text = "「$pkg」还没有界面配置。\n\n已配置的平台：\n$known\n\n" +
                "把这个 App 的 dump 发给我，加一段配置就能用。"
            return
        }

        if (!NodeQuery.isChatPage(root, profile)) {
            output.text = "当前界面不像是聊天页（pageHint 没命中）。已配的选择器对不上现在这一页。"
            return
        }

        val messages = NodeQuery.readMessages(root, profile)
        if (messages.isEmpty()) {
            // 整棵树被 noimp 摘掉的平台（他趣：根节点 View [0,0,0]，全树 1 个节点），
            // 消息读不到，但输入焦点可能仍拿得到那个 EditText。
            // 这种情况仍然值得单独验一次填字：能填就说明只差"从哪拿消息"这一步。
            val probeSink = AccessibilityReplySink(service, profile)
            val probeDraft = "测试填写"
            val probeFill = probeSink.fill(probeDraft)
            output.text = buildString {
                append("没读到消息 —— 整棵树可能被 importantForAccessibility=no 摘掉了。\n\n")
                append("单独试一次填字（写入「$probeDraft」，切回去记得删掉）：\n  ")
                append(
                    when (probeFill) {
                        is FillResult.Ok -> "成功 ✓ 这个平台能做，只差消息来源"
                        is FillResult.Unavailable -> "失败 — ${probeFill.reason}"
                        is FillResult.Mismatch -> "回读不一致 — ${probeFill.actual}"
                    }
                )
                append("\n\n").append(NodeQuery.diagnoseInput(root, profile))
            }
            return
        }

        val sb = StringBuilder()
        sb.append("读到 ${messages.size} 条（左=对方 / 右=我）：\n\n")
        messages.forEach { m ->
            sb.append(if (m.fromOther) "[对方] " else "[我]   ")
            sb.append(m.text.replace("\n", " "))
            sb.append("   x=").append(m.rect.centerX())
            sb.append('\n')
        }

        val lastOther = messages.lastOrNull { it.fromOther }
        if (lastOther == null) {
            sb.append("\n最后几条都不是对方发的，不生成草稿。")
            output.text = sb.toString()
            return
        }

        // 这个按钮的定位是「验链路」，不是「生成回复」：
        // 草稿就是原文复读，用来证明读到的文本能原样写进输入框。
        //
        // 早先加了「【测试】收到：」前缀防误发，但链路已经验收通过，
        // 前缀留着反而碍事 —— 真要发的时候还得先手动删掉。
        // 所以前缀去掉，改由按钮文案自己说明它是复读。
        val draft = lastOther.text
        // 走和正式流程同一条路（无障碍 → 输入法 → 自动点输入框），
        // 否则试填只能验无障碍那一条，验不出用户在正式流程里会看到什么。
        val write = TextWriter.write(service, profile, draft)
        sb.append("\n草稿：").append(draft)
        sb.append("\n（原文复读，只验链路。要模型生成请点右侧「生成并填入」）")
        sb.append("\n填字：").append(write.note)
        if (write.pending) {
            sb.append("\n（输入法会把草稿挂起，切回去点一下输入框就会写入）")
        }

        // 输入框诊断：这个项目的填字失败几乎全集中在这一层。
        if (!write.written) {
            sb.append("\n\n").append(NodeQuery.diagnoseInput(root, profile))
        }

        // 人设到底有没有进提示词，是这个项目最容易"以为改了其实没生效"的地方。
        // 所以试填顺带把来源、条数、提示词开头都打出来 —— 不配 Key、不联网也能自查。
        val loaded = PersonaLoader.resolve(this, config)
        val p = loaded.persona
        sb.append("\n\n人设：knowledge ${p.knowledge.size} 条 / few_shot ${p.fewShot.size} 条")
        sb.append(" / 边界 ${p.boundaries.size} 个 / 禁忌 ${p.tabooWords.size} 个")
        sb.append("\n来源：").append(loaded.source)

        val probeEngine = ReplyEngine(config, p)
        // 提示词现在带全部样例，比 900 字更容易超，所以把总长度一起写出来 ——
        // 否则用户看到结尾被截断，会以为样例没进提示词。
        val system = probeEngine.previewSystem()
        sb.append("\n\n---- 发给模型的提示词（共 ${system.length} 字，显示前 1200 字）----\n")
        sb.append(system.take(1200))
        if (probeEngine.isMockModel) {
            sb.append("\n\n[本地假模型：接口地址填的是 mock，不会联网，回复是固定的几句话]")
        }

        output.text = sb.toString()

        // 回读校验：ACTION_SET_TEXT 返回 true 不代表界面真的变了，
        // 部分自绘输入框会在下一帧被 App 覆盖掉。
        uiHandler.postDelayed({
            val fresh = service.targetRoot()
            if (fresh == null) {
                output.append("\n校验：已不在目标界面，跳过")
                return@postDelayed
            }
            val actual = NodeQuery.inputText(fresh, profile)
            val note = when {
                actual == draft -> "✓ 回读一致"
                actual.isNullOrEmpty() -> "✗ 回读为空（App 把内容清掉了）"
                else -> "⚠ 回读是「$actual」"
            }
            output.append("\n校验：$note")
        }, 320L)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        // 延迟回读的 runnable 必须撤掉：Activity 销毁之后再碰 View 会崩，
        // 而且是那种"偶尔才崩一次"的类型，很难复现。
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 状态胶囊的配色：连上 = 浅绿底，没连 = 纸底描边。
     *
     * 只改外观，文字仍然由 [refresh] 里的 getString 决定 —— 判定逻辑一处都没动。
     * 为什么值得为这八行单开一个函数：状态原来是一行灰字，得逐字读才知道好没好；
     * 换成颜色之后扫一眼就够。这是"像不像成品"最便宜的差别。
     */
    private fun styleChip(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        v.setTextColor(if (on) getColor(R.color.chip_on_text) else getColor(R.color.ink_soft))
    }

    private fun refresh() {
        val accessibilityOn = CaptureService.instance != null
        statusService.text = getString(
            if (accessibilityOn) R.string.status_service_on else R.string.status_service_off
        )
        styleChip(statusService, accessibilityOn)

        val notifyOn = isNotificationListenerEnabled()
        statusNotify.text = getString(
            if (notifyOn) R.string.status_notify_on else R.string.status_notify_off
        )
        styleChip(statusNotify, notifyOn)

        statusLevel.text = when (config.level) {
            AppConfig.LEVEL_FILL_ONLY -> getString(R.string.level_0)
            AppConfig.LEVEL_POLL -> getString(R.string.level_2)
            else -> getString(R.string.level_1)
        }

        // 自动处理的记录写在服务里（它跑在后台，界面上没人看），
        // 回到首页时带出最近几条 —— 只留一条时用户反复说"卡住了"，
        // 而界面上只有最后那句，看不出它停在哪一步。留 4 条就能看出来。
        val notes = CaptureService.instance?.recentNotes().orEmpty()
        val imeLine = if (TextWriter.isOurImeSelected(this)) {
            "输入法：AutoReply（可写入被隐藏的输入框）"
        } else {
            "输入法：未切换（输入框不可见的平台会填不进去）"
        }
        // 只统计"对方真收到"的条数：生成和填字都不算，
        // 否则反复调人设试十次就把额度用光了，而对方一条都没收到。
        val ps = PacerGuard(this, config).status()
        val sendLine = "已发 ${ps.usedThisHour}/${ps.hourlyCap} 条 · 连续 ${ps.burstCount}/${ps.burstLimit}"

        // 连点按钮的文字要跟着服务里的状态走 —— 用户切回来看时得知道还在不在跑
        val batchActive = CaptureService.instance?.batchActive == true
        findViewById<Button>(R.id.btn_batch).text = if (batchActive) {
            getString(R.string.btn_batch_stop)
        } else {
            getString(R.string.btn_batch, config.batchSize)
        }

        // 版本号必须露出来：用户报问题时，"装的是哪一版"是第一个要确认的。
        // dev.N 里的 N 是 CI 的 run_number，两版对比直接看数字大小。
        //
        // 用 packageManager 读而不是 BuildConfig：AGP 8 默认关掉 buildConfig 特性，
        // 直接引 BuildConfig 会让 CI 编译失败（还得额外开 buildFeatures）。
        // 读 PackageInfo 反而更贴排查场景 —— 它反映的是"真正装上的那一版"。
        val versionLine = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                pi.versionCode.toLong()
            }
            "版本：${pi.versionName}（#$code）"
        }.getOrNull() ?: "版本：未知"
        statusLevel.text = buildString {
            append(statusLevel.text)
            append('\n').append(imeLine)
            append('\n').append(sendLine)
            if (batchActive) {
                append(" · 连点剩 ")
                append(CaptureService.instance?.batchLeft ?: 0)
                append(" 个")
            }
            append('\n').append(versionLine)
            if (config.drillMode) {
                // 必须显眼：否则用户会以为这些回复是模型写的
                append("\n[演练模式] 不调模型、不限速 —— 回复按你的问答样例拼，发送仍由你点")
            }
            if (notes.isNotEmpty()) {
                append("\n最近处理：")
                notes.forEach { append("\n  ").append(it) }
            }
        }
    }

    /**
     * 判断通知使用权是否已授予。
     *
     * 不用 NotificationManagerCompat（要引 androidx），直接读系统设置里的
     * 已授权服务列表。这是系统写进去的，稳定可用。
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 要麦克风权限（只有「说一句」用得上）。
     *
     * 语音识别用系统自带的 SpeechRecognizer：音频只在系统服务里处理，
     * 我们不录音、不落盘、不发给自己的服务器。
     */
    private fun askMic() {
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            toast("麦克风已授权 —— 回输入法点「说一句」")
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), reqMic)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqMic) return
        val ok = grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        toast(
            if (ok) "已授权 —— 回输入法点「说一句」"
            else "没授权就用不了语音；也可以点「换键盘」自己打"
        )
    }
}
