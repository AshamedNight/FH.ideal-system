package com.autoreply.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.guard.PacerGuard
import com.autoreply.app.core.guard.SentTracker
import com.autoreply.app.notify.MessageNotificationListener
import com.autoreply.app.platform.NodeQuery
import com.autoreply.app.platform.PlatformProfile
import com.autoreply.app.platform.Profiles
import com.autoreply.app.platform.ReplyRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 读屏服务。
 *
 * 它只做两件事：把当前界面缓存下来，以及把目标 App 的根节点交出去。
 * 不做任何点击 —— 点击是 platform 层的事，这样"读"和"动"在代码里是两处，
 * 出问题能立刻定位是哪一侧。
 *
 * 支持**多个目标平台**：包名是一组，不是单个。没配置任何包名时，
 * 任何不是自己的前台界面都算目标 —— 这样第一次出包就能直接抓任意界面摸结构。
 *
 * 缓存的必要性：用户切回本 App 时，前台窗口已经是我们自己，
 * rootInActiveWindow 返回的是自己的界面。所以抓取必须发生在目标 App 在前台的那一刻，
 * 由无障碍事件驱动、节流后存内存，主界面只是把缓存拿出来看。
 */
class CaptureService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureService? = null

        /** 事件风暴很密（列表滚动一次几十个事件），1.2 秒一次足够。 */
        private const val MIN_DUMP_INTERVAL_MS = 1200L

        /** 自动触发的检查间隔。比 dump 更密一点，但仍然远低于事件频率。 */
        private const val AUTO_CHECK_INTERVAL_MS = 900L

        /**
         * 连点时等用户点发送的时限。
         *
         * 20 秒而不是 45：实测"发完第一段、第二段迟迟不出现"是用户最难受的状态，
         * 而这一条超时后会先检查断句队列（见 [armBatchWatchdog]），
         * 所以缩短等于让"检测漏了"的情况更快自愈。
         */
        private const val BATCH_SEND_TIMEOUT_MS = 20_000L
    }

    private var config: AppConfig? = null

    @Volatile
    private var cachedDump: String = ""

    @Volatile
    private var cachedAtWall: Long = 0L

    @Volatile
    private var cachedPkg: String = ""

    private var lastDumpElapsed: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 收到了通知，但当时目标 App 不在聊天页 —— 先挂着，等用户点进会话再处理。
     *
     * 这是 L1 档唯一的"待办"。不做自动点开会话：那是一次主动点击，
     * 而"人自己点开、程序准备草稿"这个形态里根本不需要它。
     */
    private var pendingHit: MessageNotificationListener.Hit? = null

    /** 一次只跑一个判定，避免通知连发时并发调模型。 */
    private var autoRunning = false

    /**
     * [autoRunning] 的开始时刻。
     *
     * 兜底用：判定链的回调万一丢了（异常、线程被系统冻结），
     * autoRunning 会永远停在 true，而连点就靠它来防重入 ——
     * 结果就是界面上一直显示"连点剩 N 个"却再也不动。
     */
    private var autoStartedAt = 0L

    /** 同一条通知可能被系统更新多次（已读回执、时间刷新），用它去重。 */
    private var lastHitKey: String = ""

    /**
     * 上一次处理过的「最后一条对方消息」的指纹。
     *
     * 这是**不依赖通知**的触发依据：交友类 App 大多不发系统通知
     * （未读只在 App 内显示红点），所以通知驱动对它们不成立。
     * 但界面会变 —— 消息一到，RecyclerView 就多一条，无障碍事件必然触发。
     * 用「条数 + 文本」当指纹，条数变化能覆盖"切了会话但最后一句碰巧一样"。
     */
    private var lastSeenFingerprint: String = ""

    /** 自动检查的节流。读消息要遍历树，不必每个事件都读。 */
    private var lastAutoCheckElapsed: Long = 0L

    /** 最近一次自动处理的结果，首页会显示。 */
    @Volatile
    var lastAutoNote: String = ""
        private set

    /**
     * 最近几条自动处理的记录（新的在前）。
     *
     * 只留一条时，用户反复说"卡住了"，但界面上只有最后那句话 ——
     * 而"卡住"恰恰是一个过程：读了、判了、填了、然后就没了。
     * 留 4 条就能看出它停在哪一步。
     */
    private val autoLog = ArrayDeque<String>(5)

    @Synchronized
    fun recentNotes(): List<String> = autoLog.toList()

    @Synchronized
    private fun logAuto(note: String) {
        val hhmm = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        autoLog.addFirst("[$hhmm] $note")
        while (autoLog.size > 4) autoLog.removeLast()
        lastAutoNote = autoLog.first()
    }

    /**
     * 监听器要留引用：`removeListener { }` 传的是个新 lambda，
     * 起不到解除作用，会在服务重建后叠加多份回调。
     */
    private val hitListener: (MessageNotificationListener.Hit) -> Unit = { hit ->
        onNotification(hit)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = AppConfig(this)
        // 界面配置在服务连上时读一次即可：改了 profiles.json 需要重装包，
        // 而改动配置本来就是发版行为，不做热加载。
        Profiles.load(this)
        instance = this

        // 通知 → 自动处理的接线。listener 只负责"听到"，判断和动作都在这里。
        MessageNotificationListener.addListener(hitListener)
    }

    override fun onDestroy() {
        MessageNotificationListener.removeListener(hitListener)
        super.onDestroy()
    }

    private fun onNotification(hit: MessageNotificationListener.Hit) {
        // L0 档不自动：那个档位的定义就是"人自己停在页面里，程序只负责填"
        if ((config?.level ?: AppConfig.LEVEL_FILL_ONLY) < AppConfig.LEVEL_NOTIFY) return

        val key = "${hit.postedAt}|${hit.text.orEmpty()}"
        if (key == lastHitKey) return
        lastHitKey = key

        pendingHit = hit
        mainHandler.post { tryAutoReply() }
    }

    /**
     * 尝试把挂起的通知处理掉。
     *
     * **只在"目标 App 就在前台、而且停在聊天页"时动手。** 否则什么也不做，
     * 等用户自己点进去 —— 那样的话这一次处理就完全发生在后台，
     * 用户看到的只是"草稿已经在了"。
     */
    private fun tryAutoReply() {
        if (autoRunning) return
        val hit = pendingHit ?: return

        val root = targetRoot() ?: return
        if (root.packageName?.toString() != hit.packageName) return
        val profile = Profiles.forPackage(hit.packageName) ?: return
        if (!NodeQuery.isChatPage(root, profile)) return

        pendingHit = null
        runAuto(hit.text)
    }

    private fun runAuto(notifyText: String?, onDone: (() -> Unit)? = null) {
        val cfg = config ?: return
        autoRunning = true
        autoStartedAt = SystemClock.elapsedRealtime()
        ReplyRunner(this, cfg).runOnce(notifyText) { result ->
            autoRunning = false
            logAuto(result.note)
            onDone?.invoke()
        }
    }

    // ---- 连点：切下一个未读 → 填草稿 → 你点发送 → 再切下一个 ----
    //
    // 刻意**不用定时器**。每一步都由"你点了发送"驱动，间隔来自你的手速，
    // 天然不规则。固定间隔的循环是这套东西里最容易被识别的行为：
    // 一个人在半小时里以 20 秒的等距节奏给 40 个人发消息，没有第二种解释。

    @Volatile
    var batchActive: Boolean = false
        private set

    @Volatile
    var batchLeft: Int = 0
        private set

    private var batchWatchdog: Runnable? = null

    /** 连续几次发现"本应用在前台"。用来避免切不回去时无限重试。 */
    private var frontFailCount = 0

    /**
     * 最近一次成功读到的目标包名。
     *
     * 兜底用：用户很可能在设置里填了应用名（「同城夜聊」）而不是包名
     * （`com.huwei.fox`），那时 `getLaunchIntentForPackage` 直接返回 null，
     * 回退到"上一个能读到的目标"往往就是对的。
     */
    @Volatile
    private var lastTargetPkg: String? = null

    fun startBatch(count: Int = config?.batchSize ?: 5) {
        if (batchActive) return
        batchActive = true
        batchLeft = count
        logAuto("连点开始：$count 个，正在切到目标应用…")

        // **必须先把目标应用拉到前台。**
        // 无障碍能拿到目标 App 的节点（它在 windows 列表里，哪怕是分屏的另一半），
        // 但 dispatchGesture 的手势会落在**有焦点的那个窗口**上。
        // AutoReply 自己在前台时，每一记点击都被自己接走了 ——
        // 表现就是"在本应用里点按钮完全没反应，切到目标 App 里才有用"。
        bringTargetToFront()?.let { logAuto("切到目标应用失败：$it") }

        // 给系统一点时间切过去，切完再开始第一步
        mainHandler.postDelayed({ batchStep() }, 1200L)
    }

    /**
     * 把目标应用弄到前台。返回 null 表示成功，否则是失败原因。
     *
     * 两条路，按可靠性排序：
     *
     *  1. **按返回键退出本应用**（首选）。用户是从目标 App 切过来点按钮的，
     *     按一次 BACK 就回到那个 App —— 不需要权限，也不会被系统拦。
     *  2. `startActivity` 显式拉起。这条在 Android 10+ 上**大概率被拒**：
     *     后台应用不能启动 Activity，无障碍服务不算"前台"。
     *     所以它只是兜底，不能当主路径。
     */
    private fun bringTargetToFront(): String? {
        if (rootInActiveWindow?.packageName?.toString() == packageName) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) return null
        }

        val candidates = buildList {
            addAll(config?.targetPackages.orEmpty())
            lastTargetPkg?.let { add(it) }
        }.distinct().filter { it.isNotBlank() }

        if (candidates.isEmpty()) return "设置里没填目标应用包名"

        for (pkg in candidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return try {
                startActivity(intent)
                null
            } catch (e: Exception) {
                "启动 $pkg 失败：${e.javaClass.simpleName}"
            }
        }
        // 一个都启动不了 —— 最常见的原因是设置里填的是应用名而不是包名
        return "启动不了 ${candidates.joinToString("/")}：设置里要填包名（如 com.huwei.fox），不是应用名"
    }

    fun stopBatch(note: String = "连点已停止") {
        batchActive = false
        batchLeft = 0
        batchWatchdog?.let { mainHandler.removeCallbacks(it) }
        batchWatchdog = null
        // 没发完的断句一起丢掉：停下来了就不该再往任何会话里填东西
        com.autoreply.app.core.guard.DraftQueue.clear()
        logAuto(note)
    }

    private fun batchStep() {
        if (!batchActive) return
        val cfg = config ?: return
        if (batchLeft <= 0) {
            stopBatch("连点结束：这一轮处理完了")
            return
        }
        if (autoRunning) {
            // 回调丢了就强制复位，别让整条连点死在这里
            if (SystemClock.elapsedRealtime() - autoStartedAt > 40_000L) {
                autoRunning = false
                logAuto("上一步超时，已强制复位")
            } else {
                return
            }
        }

        // 本应用在前台时，手势全落在自己身上，对目标 App 的操作必然无效。
        // 这里主动切回去；连续切不回去（系统限制了后台启动）就停下来明说。
        if (rootInActiveWindow?.packageName?.toString() == packageName) {
            frontFailCount++
            if (frontFailCount > 3) {
                stopBatch("连点停止：切不回目标应用（系统可能限制了后台启动）")
                return
            }
            val err = bringTargetToFront()
            logAuto(if (err == null) "正在切回目标应用" else "切回失败：$err")
            mainHandler.postDelayed({ batchStep() }, 1500L)
            return
        }
        frontFailCount = 0

        val root = targetRoot() ?: run {
            stopBatch("连点停止：目标界面不在前台")
            return
        }
        val profile = Profiles.forPackage(root.packageName?.toString() ?: "") ?: run {
            stopBatch("连点停止：这个平台没有配置")
            return
        }
        val dedup = com.autoreply.app.core.guard.ReplyDedup(this)

        // 当前会话如果还有对方没被回过的消息，先处理它，别急着切走 ——
        // 否则一按连点就把眼前这条跳过去了
        val messages = NodeQuery.readMessages(root, profile)
        val peer = NodeQuery.peerName(root, profile)
        val lastOther = messages.lastOrNull { it.fromOther }
        val pendingHere = lastOther != null &&
            !dedup.isDuplicate(peer, lastOther.text, System.currentTimeMillis())

        if (pendingHere) {
            runAuto(null) { armBatchWatchdog() }
            return
        }

        val clicked = NodeQuery.clickNextUnread(root, profile) { n ->
            com.autoreply.app.platform.TextWriter.tapNode(this, n)
        }
        if (!clicked) {
            stopBatch("连点结束：没有更多未读了")
            return
        }

        // 换会话了，上一段没发完的断句必须丢掉，
        // 否则会把 A 会话的第二句发给 B
        com.autoreply.app.core.guard.DraftQueue.clear()

        // 等会话渲染完再读。太短会读到上一个会话的残留节点
        mainHandler.postDelayed({
            if (!batchActive) return@postDelayed
            runAuto(null) { armBatchWatchdog() }
        }, 1000L + kotlin.random.Random.nextLong(700))
    }

    /**
     * 等用户点发送。
     *
     * 45 秒还没发，就当他不想发这一条（可能觉得内容不合适），跳到下一个。
     * 没有这个看门狗，连点会永远卡在某一步 —— 而用户根本不知道卡在哪。
     */
    private fun armBatchWatchdog() {
        batchWatchdog?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!batchActive) return@Runnable
            // 队列里还有没发的段 → 说明用户其实发了，只是"发送检测"没命中
            //（消息列表刷新有延迟，或者 App 把内容改写了一点）。
            // 这时直接填下一段，别让整轮卡死在这里。
            val root = targetRoot()
            val profile = root?.let { Profiles.forPackage(it.packageName?.toString() ?: "") }
            if (root != null && profile != null && fillNextSegment(root, profile)) return@Runnable

            batchLeft--
            batchStep()
        }
        batchWatchdog = task
        mainHandler.postDelayed(task, BATCH_SEND_TIMEOUT_MS)
    }

    /** 检测到真的发了新消息时推进连点。 */
    private fun onBatchSent() {
        if (!batchActive) return
        batchWatchdog?.let { mainHandler.removeCallbacks(it) }
        batchWatchdog = null
        batchLeft--
        // 等新气泡渲染完再切，否则读到的还是上一屏。
        // 600~1300ms 而不是原来的 1200~2100：实测那个间隔让人明显感觉"卡了一下"，
        // 而渲染其实几百毫秒就完成了。
        mainHandler.postDelayed({ batchStep() }, 600L + kotlin.random.Random.nextLong(700))
    }

    /**
     * 断句的下一段：填进去，不切会话。
     *
     * @return true 表示还有下一段要发，调用方应当停下（别急着切会话）
     */
    private fun fillNextSegment(root: AccessibilityNodeInfo, profile: PlatformProfile): Boolean {
        val next = com.autoreply.app.core.guard.DraftQueue.poll() ?: return false
        com.autoreply.app.platform.TextWriter.write(this, profile, next)
        logAuto("上一句已发，接着填：${next.take(16)}")
        // 新的一段等于重新开始等发送，看门狗要重新计时
        if (batchActive) armBatchWatchdog()
        return true
    }

    /**
     * 界面驱动的自动触发 —— 不依赖通知。
     *
     * 为什么需要这个：同城夜聊和他趣都**不发系统通知**，未读只在 App 内显示红点
     * （用户实测两个平台都收不到通知）。但消息一到，列表就多一条，
     * 无障碍事件必然触发 —— 这是比通知更可靠的信号。
     *
     * 而且它比通知信息更全：通知只有一句摘要，这里能拿到整段对话。
     */
    private fun maybeAutoReplyOnChange() {
        if (autoRunning) return
        val cfg = config ?: return
        // L0 档不自动：那个档位的定义就是"人自己停在页面里，程序只负责填"
        if (cfg.level < AppConfig.LEVEL_NOTIFY) return

        val root = targetRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val profile = Profiles.forPackage(pkg) ?: return
        // 读不到消息的平台（他趣：整棵树被 noimp 摘掉）只能等通知，
        // 而它们偏偏不发通知 —— 这类平台只能手动，或者靠输入法方案
        if (profile.message.isEmpty()) return
        if (!NodeQuery.isChatPage(root, profile)) return

        val messages = NodeQuery.readMessages(root, profile)
        val peer = NodeQuery.peerName(root, profile)

        // 先看是不是又发出去了一条。判据是「同一个会话里，我发的最后一条变了」——
        // 不依赖"草稿被原样发出"，因为用户可能改几个字再发，也可能干脆自己手打。
        // 那些都是真实发送，平台都看到了，都该计入配额。
        val nowWall = System.currentTimeMillis()
        if (SentTracker(this).detectNewSent(messages, peer, nowWall)) {
            PacerGuard(this, cfg).recordSent(nowWall)
            // 断句：这句话如果是拆开发的，先接着填下一句 —— 不切会话
            if (fillNextSegment(root, profile)) return
            // 连点模式下，"你点了发送"就是推进信号
            onBatchSent()
        }

        val last = messages.lastOrNull { it.fromOther } ?: return
        // 指纹必须带昵称。交友平台大量新会话第一句都是「你好」，
        // 只按「条数 + 文本」判断的话，从 A 会话切到 B 会话，
        // 两边都是 1 条「你好」→ 指纹相同 → 判定成"消息没变"，那条招呼就被漏掉。
        //
        // 分隔符用 U+241F：昵称或消息里都可能出现竖线，撞上会把两个会话粘成一个。
        val fingerprint = "$peer\u241F${messages.size}\u241F${last.text.trim()}"
        if (fingerprint == lastSeenFingerprint) return
        lastSeenFingerprint = fingerprint

        runAuto(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // 自己的界面永不缓存。少了这一行，用户切回来点「抓取」，
        // 缓存里就是自己这一页的节点树 —— 看起来像抓取坏了，其实是抓错对象。
        if (pkg == packageName) return

        if (!isTarget(pkg)) return

        // 有挂起的通知时，目标 App 的每一次界面变化都试一次。
        // 用户点开会话的那一刻就是这个时机，抓不到就只能等他再点。
        if (pendingHit != null) tryAutoReply()

        // 界面驱动的自动触发。节流 900ms：读消息要遍历树，
        // 而列表滚动一次能发几十个事件，不节流会把主线程拖住。
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastAutoCheckElapsed >= AUTO_CHECK_INTERVAL_MS) {
            lastAutoCheckElapsed = elapsed
            maybeAutoReplyOnChange()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpElapsed < MIN_DUMP_INTERVAL_MS) return

        val root = targetRoot() ?: return
        lastDumpElapsed = now
        cachedDump = NodeDumper.dump(root, labelOf(pkg))
        cachedAtWall = System.currentTimeMillis()
        cachedPkg = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cachedDump = ""
        return super.onUnbind(intent)
    }

    /** 配置的目标包名集合。空表示"没指定，任何非系统界面都算"。 */
    fun targets(): List<String> = config?.targetPackages ?: emptyList()

    /**
     * 系统界面判断 —— 这些永远不是要抓的对象。
     *
     * 这里踩过两次坑，两次都表现为"抓取看起来能跑，但抓到的不是目标"：
     *
     * 1. 包名留空时规则是"非本 App 即目标"，而导航栏、状态栏、桌面、输入法
     *    统统满足"非本 App" → 抓到 com.android.systemui 的 navigation_bar_frame。
     *    更糟的是 systemui 的窗口内容事件极频繁（状态栏时间每秒刷新），
     *    缓存一直被它覆盖，表现为"换几个 App 都抓到同一份"。
     *
     * 2. 分屏 / 自由窗口模式下，窗口容器的包名是 `android`（不是 systemui），
     *    抓到的是 #flexible_task_caption_view_root 这种标题栏（最小化/关闭按钮）。
     *    所以必须单独排除 `android` 这个包名。
     *
     * 用关键字匹配而不是枚举全部包名：各家 ROM 的 launcher / 输入法包名太多，
     * 穷举必然漏。
     */
    private fun isSystemUi(pkg: String): Boolean {
        if (pkg == packageName) return true
        // 系统框架自身的窗口：分屏/自由窗口的标题栏、Toast、音量条都在这里
        if (pkg == "android") return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.startsWith("com.android.settings")) return true
        if (pkg.contains("launcher", ignoreCase = true)) return true
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("permissioncontroller", ignoreCase = true)) return true
        if (pkg.startsWith("com.android.packageinstaller")) return true
        return false
    }

    /**
     * 包名 → 应用名。拿不到就返回空，不影响 dump 的其他部分。
     *
     * 存在的理由很实际：用户装了多个同类 App 时，光看 `com.huwei.fox`
     * 根本分不清是哪个软件，而去系统设置里翻应用信息又要多跑一趟。
     * 抓一次就把「这是谁」和「界面长什么样」一起给出来。
     */
    private fun labelOf(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun isTarget(pkg: String?): Boolean {
        if (pkg == null) return false
        if (isSystemUi(pkg)) return false
        val list = targets()
        return list.isEmpty() || list.contains(pkg)
    }

    /**
     * 只接受「应用窗口」。
     *
     * 单看包名不够：TYPE_SYSTEM 的窗口可以属于任何包名，
     * 而 TYPE_APPLICATION 才是真正承载 App 界面的那一层。
     * 分屏时有多个 TYPE_APPLICATION 窗口，按 z-order 取第一个命中目标的。
     */
    private fun isAppWindow(w: android.view.accessibility.AccessibilityWindowInfo): Boolean =
        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION

    /**
     * 目标 App 的根节点。
     *
     * **不要用 rootInActiveWindow 直接判"目标在不在"**：
     * 用户开分屏/小窗时，目标 App 明明就在屏幕上，rootInActiveWindow
     * 返回的却可能是标题栏或另一个窗口。先看活动窗口，不是就遍历 windows 按包名找。
     */
    fun targetRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString()
        if (isTarget(activePkg)) {
            lastTargetPkg = activePkg
            return active
        }

        val list = windows ?: return null
        for (w in list) {
            if (w == null) continue
            if (!isAppWindow(w)) continue
            val r = w.root ?: continue
            val pkg = r.packageName?.toString()
            if (isTarget(pkg)) {
                lastTargetPkg = pkg
                return r
            }
        }
        return null
    }

    /** 目标 App 窗口在屏幕上的位置。分屏时它只占半屏，任何区域计算都要按这块算。 */
    fun targetWindowBounds(): Rect? {
        val list = windows ?: return null
        for (w in list) {
            if (w == null) continue
            if (!isAppWindow(w)) continue
            val r = w.root ?: continue
            if (!isTarget(r.packageName?.toString())) continue
            val rect = Rect()
            w.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
        }
        return null
    }

    /**
     * 当前所有窗口的概览，供排查用。
     *
     * 抓错东西时，光看抓到的节点树无法判断"为什么抓到了它"；
     * 附上窗口清单就能一眼看出：当时屏幕上有哪些窗口、都是什么类型、谁在前。
     */
    fun describeWindows(): String {
        val list = windows ?: return "（拿不到窗口列表：无障碍服务可能未连接）"
        if (list.isEmpty()) return "（窗口列表为空）"
        val sb = StringBuilder()
        for ((i, w) in list.withIndex()) {
            if (w == null) {
                sb.append("# $i null\n")
                continue
            }
            val pkg = w.root?.packageName?.toString() ?: "?"
            val type = when (w.type) {
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
                else -> "OTHER(${w.type})"
            }
            val rect = Rect()
            w.getBoundsInScreen(rect)
            sb.append("# $i $type $pkg ")
                .append("[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                .append(if (w.isActive) " active" else "")
                .append(if (w.isFocused) " focused" else "")
                .append('\n')
        }
        return sb.toString()
    }

    /** 前台是不是目标 App。L0 档要靠它决定能不能动作。 */
    fun isTargetForeground(): Boolean = isTarget(rootInActiveWindow?.packageName?.toString())

    data class Snapshot(val text: String, val source: String)

    /**
     * 前台就是目标时抓实时；否则退回最近一次缓存。都没有返回 null。
     *
     * 不管走哪条路，末尾都附一份「当前窗口」清单 —— 抓错对象时，
     * 光看节点树看不出为什么抓到了它；有窗口清单就能直接看到
     * 当时屏幕上有哪些窗口、什么类型、谁在前。
     */
    fun snapshot(): Snapshot? {
        val body: String
        val source: String
        val root = targetRoot()
        if (root != null) {
            body = NodeDumper.dump(root, labelOf(root.packageName?.toString()))
            cachedDump = body
            cachedAtWall = System.currentTimeMillis()
            cachedPkg = root.packageName?.toString() ?: ""
            source = "实时"
        } else if (cachedDump.isNotEmpty()) {
            body = cachedDump
            val who = if (cachedPkg.isEmpty()) "" else "（$cachedPkg）"
            source = "缓存于 ${agoText(cachedAtWall)}$who"
        } else {
            return null
        }
        val text = body + "\n\n# ---- 当前窗口（排查用）----\n" + describeWindows()
        return Snapshot(text, source)
    }

    private fun agoText(at: Long): String {
        if (at <= 0L) return "未知时间"
        val seconds = (System.currentTimeMillis() - at) / 1000
        return when {
            seconds < 60 -> "${seconds} 秒前"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            else -> "${seconds / 3600} 小时前"
        }
    }
}
