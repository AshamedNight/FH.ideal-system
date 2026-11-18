package com.autoreply.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.persona.PersonaLoader
import com.autoreply.app.platform.AccessibilityReplySink
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

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = AppConfig(this)

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

        val messages = NodeQuery.readMessages(root, profile)
        if (messages.isEmpty()) {
            output.text = "没读到消息，选择器可能失效。"
            return
        }
        val lastOther = messages.lastOrNull { it.fromOther }
        if (lastOther == null) {
            output.text = "最后几条都不是对方发的，不生成。"
            return
        }

        val turns = messages.map { Turn(it.text, it.fromOther) }

        // ---- 去重：同一条消息 5 分钟内只处理一次 ----
        // 指纹带对方昵称 —— 交友平台大量新会话第一句都是「你好」，
        // 只按文本去重会把第二个会话直接锁死（这个坑踩过）。
        val now = System.currentTimeMillis()
        val peer = NodeQuery.peerName(root, profile)

        // 先看是不是又发出去了一条（同会话里我发的最后一条变了）。
        // 不依赖"草稿被原样发出"：改几个字、自己手打，都是真实发送。
        if (SentTracker(this).detectNewSent(messages, peer, now)) {
            PacerGuard(this, config).recordSent(now)
        }

        val dedup = ReplyDedup(this)
        if (dedup.isDuplicate(peer, lastOther.text, now)) {
            val who = peer.ifEmpty { "这个会话" }
            output.text = buildString {
                append("「$who」这条已经处理过（5 分钟内同一句），不重复生成。\n\n")
                append("内容：").append(lastOther.text.take(40))
                append("\n\n（要重来就等 5 分钟，或等对方发新消息）")
            }
            return
        }

        output.text = "正在判定…（对方最后一句：${lastOther.text.take(24)}）"

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
            val outcome = flow.decide(
                turns,
                selfSaidAgoSeconds = selfAgo,
                now = now,
                // 手动点击 = 用户明确要求。精力和长冷却都是拟人机制，此时该让路；
                // 每小时上限与熔断仍然生效（那是真风险护栏）。
                respectEnergy = false,
                respectCooldown = false,
            )
            // trace 必须在这里取：下一次 decide 会清空它
            val trace = flow.lastTrace
            runOnUiThread {
                // 判定要几秒，这期间用户可能已经退出页面
                if (isFinishing || isDestroyed) return@runOnUiThread

                val head = buildString {
                    append("判定链：\n")
                    trace.forEach { append("  ").append(it).append('\n') }
                }

                when (outcome) {
                    is ReplyFlow.Outcome.Fill -> {
                        val sink = AccessibilityReplySink(service, profile)
                        // 断句：拆成 2~3 条发，只填第一段，其余排队等你发完再填。
                        // 和 ReplyRunner 里是同一套逻辑 —— 手动/自动两条路必须一致，
                        // 否则"手动能拆、自动不拆"这种差异查起来最费劲。
                        val parts = SentenceSplitter.split(outcome.text)
                        DraftQueue.reset(parts.drop(1))

                        // 两条通道：无障碍优先，失败自动落到输入法
                        val write = TextWriter.write(service, profile, parts.first())
                        val copied = if (write.written || write.pending) {
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
                            // 填入会让界面变化，那个变化又会触发自动回复 —— 5 秒内跳过
                            service.noteFill()
                        }
                        if (!mockMode) config.recordUsage(outcome.inputTokens, outcome.outputTokens)

                        output.text = buildString {
                            append(head)
                            append("\n草稿：").append(outcome.text)
                            if (parts.size > 1) {
                                append("\n（拆成 ").append(parts.size)
                                append(" 段：发完这段会自动填下一段）")
                            }
                            append("\n\n填入：").append(write.note)
                            if (copied) {
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
                    }

                    is ReplyFlow.Outcome.Hold -> {
                        // 风险太高：草稿给你看，但不替你填。
                        // 填进输入框的话人多半顺手就发了 —— 那是用界面诱导犯错。
                        val sink = AccessibilityReplySink(service, profile)
                        val copied = sink.copyToClipboard(outcome.text)
                        if (!mockMode) config.recordUsage(outcome.inputTokens, outcome.outputTokens)
                        output.text = buildString {
                            append(head)
                            append("\n草稿（不自动填）：").append(outcome.text)
                            append("\n\n为什么没填：").append(outcome.reason)
                            if (copied) append("\n\n已复制到剪贴板，你要用就自己粘贴。")
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
                        append("现在切回目标 App，点一下输入框让光���留在里面。\n")
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
        val clicked = NodeQuery.clickNextUnread(root, profile) { node ->
            TextWriter.tapNode(service, node)
        }
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

    private fun refresh() {
        val accessibilityOn = CaptureService.instance != null
        statusService.text = getString(
            if (accessibilityOn) R.string.status_service_on else R.string.status_service_off
        )

        val notifyOn = isNotificationListenerEnabled()
        statusNotify.text = getString(
            if (notifyOn) R.string.status_notify_on else R.string.status_notify_off
        )

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
}
