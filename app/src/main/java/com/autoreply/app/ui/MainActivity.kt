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
import com.autoreply.app.core.llm.ReplyEngine
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.persona.PersonaLoader
import com.autoreply.app.platform.AccessibilityReplySink
import com.autoreply.app.platform.FillResult
import com.autoreply.app.platform.NodeQuery
import com.autoreply.app.platform.Profiles

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
                        output.text = buildString {
                            append("草稿（第 ${result.attempts} 次生成）：\n")
                            append(result.text)
                            append("\n\n填入：")
                            append(
                                when (fill) {
                                    is FillResult.Ok -> "成功，已写入输入框（未发送）"
                                    is FillResult.Unavailable -> "失败 — ${fill.reason}"
                                    is FillResult.Mismatch -> "回读不一致 — ${fill.actual}"
                                }
                            )
                        }
                    }
                }
            }
        }.start()
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

        // 测试草稿故意带标记：万一手滑点了发送，对面也能看出是测试，不会造成误会
        val draft = "【测试】收到：${lastOther.text.take(12)}"
        val sink = AccessibilityReplySink(service, profile)
        val result = sink.fill(draft)
        sb.append("\n草稿：").append(draft)
        sb.append("\n填字：").append(
            when (result) {
                is FillResult.Ok -> "成功，已写入输入框（未发送）"
                is FillResult.Unavailable -> "失败 — ${result.reason}"
                is FillResult.Mismatch -> "回读不一致 — ${result.actual}"
            }
        )
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
