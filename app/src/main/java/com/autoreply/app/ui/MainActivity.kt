package com.autoreply.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.app.Activity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.autoreply.app.R
import com.autoreply.app.capture.CaptureService
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.llm.ReplyEngine
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.persona.PersonaLoader
import com.autoreply.app.platform.AccessibilityReplySink
import com.autoreply.app.platform.FillResult
import com.autoreply.app.platform.NodeQuery
import com.autoreply.app.platform.Profiles
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
        output.text = "正在生成…（对方最后一句：${lastOther.text.take(24)}）"

        val engine = ReplyEngine(config, PersonaLoader.load(this, config))
        Thread {
            val result = engine.generate(turns)
            runOnUiThread {
                // 生成要几秒，这期间用户可能已经退出页面
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ReplyEngine.Result.Blocked -> {
                        output.text = "不回复：${result.reason}"
                    }
                    is ReplyEngine.Result.Failed -> {
                        output.text = "生成失败：${result.reason}"
                    }
                    is ReplyEngine.Result.Draft -> {
                        val sink = AccessibilityReplySink(service, profile)
                        val fill = sink.fill(result.text)
                        // 填不进去时不能只报一句"失败" —— 把草稿放进剪贴板，
                        // 长按输入框粘贴即可。这是"输入框对无障碍不可见"的平台上
                        // 唯一可行的路，而且比自动填更安全：全程零自动点击。
                        val copied = if (fill is FillResult.Ok) {
                            false
                        } else {
                            runCatching { sink.copyToClipboard(result.text) }.isSuccess
                        }
                        // 假模型不计入用量，否则统计会被"测管道"的那几次污染
                        if (!mockMode) config.recordUsage(result.inputTokens, result.outputTokens)
                        output.text = buildString {
                            // 假模型必须标出来，否则会被当成"模型就写成这样"，
                            // 转头去调人设 —— 那是白费功夫。
                            append(if (mockMode) "草稿（本地假模型，非真实生成）：\n" else "草稿（第 ${result.attempts} 次生成）：\n")
                            append(result.text)
                            append("\n\n填入：")
                            val tail = if (copied) "\n已复制到剪贴板：点一下输入框 → 长按 → 粘贴" else ""
                            append(
                                when (fill) {
                                    is FillResult.Ok -> "成功，已写入输入框（未发送）"
                                    is FillResult.Unavailable -> "失败 — ${fill.reason}$tail"
                                    is FillResult.Mismatch -> "回读不一致 — ${fill.actual}$tail"
                                }
                            )
                            if (!mockMode) {
                                // 真实用量：估算在"到底花多少钱"上能差一倍，
                                // 而余额是实扣的，所以只认服务端返回的 usage。
                                append("\n\n本次：输入 ${result.inputTokens} / 输出 ${result.outputTokens} token（服务端口径）")
                                append("\n本地估算：约 ${result.estimatedInputTokens} token 输入")
                                // 差三倍以上基本可以确定不是我们发的东西变多了，
                                // 而是接口这一层往请求里加料（自带的 system、工具定义）
                                // 或者直接改写了 usage —— 那种数字不能用来估成本。
                                if (result.estimatedInputTokens > 0 &&
                                    result.inputTokens > result.estimatedInputTokens * 3 + 50
                                ) {
                                    append("\n⚠ 两者差很多：多半是接口/中转改写了 usage，或往请求里加了自带的提示词。")
                                    append("算钱时以「本地估算」为准。")
                                }
                                append("\n累计：${config.llmCalls} 次调用，输入 ${config.llmTokensIn} / 输出 ${config.llmTokensOut} token")
                            }
                        }
                    }
                }
            }
        }.start()
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
                    output.text = "执行中…"
                    runGenerate()
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
        if (!NodeQuery.clickNextUnread(root, profile)) {
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
            output.text = buildString {
                append("没读到消息，消息选择器可能失效了。\n\n")
                append("# ---- 当前窗口 ----\n")
                append(service.describeWindows())
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
        val sink = AccessibilityReplySink(service, profile)
        val result = sink.fill(draft)
        sb.append("\n草稿：").append(draft)
        sb.append("\n（原文复读，只验链路。要模型生成请点右侧「生成并填入」）")
        sb.append("\n填字：").append(
            when (result) {
                is FillResult.Ok -> "成功，已写入输入框（未发送）"
                is FillResult.Unavailable -> "失败 — ${result.reason}"
                is FillResult.Mismatch -> "回读不一致 — ${result.actual}"
            }
        )

        // 输入框诊断：这个项目的填字失败几乎全集中在这一层，
        // 把「树里有没有可编辑节点」「当前焦点在哪」直接打出来，
        // 就不必再抓一次 dump 才能判断该走哪条路。
        sb.append("\n\n输入框诊断：")
        val editable = NodeQuery.firstEditable(root, profile.input)
        if (editable == null) {
            sb.append("\n  可编辑节点：无（树里找不到，SET_TEXT 无从下手）")
        } else {
            sb.append("\n  可编辑节点：")
            sb.append(editable.className?.toString()?.substringAfterLast('.') ?: "?")
            sb.append(" editable=").append(editable.isEditable)
        }
        val focusNode = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        sb.append("\n  当前输入焦点：")
        if (focusNode == null) {
            sb.append("无（切回本 App 会丢焦点，这是测试方式的限制）")
        } else {
            sb.append(focusNode.className?.toString()?.substringAfterLast('.') ?: "?")
            sb.append(" editable=").append(focusNode.isEditable)
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
