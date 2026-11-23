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
import com.autoreply.app.notify.NotifyContext
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

        /**
         * 一次生成最多等多久。超了就强制复位、继续下一步。
         *
         * 20 秒而不是 40：模型卡住时（网络抖动、中转排队），
         * 40 秒里用户只能干看着，体感就是"不动了"。
         */
        private const val AUTO_STUCK_TIMEOUT_MS = 20_000L
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
    /** 写进首页日志（只留最近 4 条）。ReplyRunner 也用它在读空时留下诊断。 */
    fun logAuto(note: String) {
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
        // "看到了却没用上"的通知，也写进首页日志。
        // 没有这条，用户只能看到"没反应"，分不清是没收到、还是收到了被忽略。
        MessageNotificationListener.diag = { note -> logAuto(note) }
    }

    override fun onDestroy() {
        MessageNotificationListener.removeListener(hitListener)
        MessageNotificationListener.diag = null
        super.onDestroy()
    }

    private fun onNotification(hit: MessageNotificationListener.Hit) {
        // L0 档不自动：那个档位的定义就是"人自己停在页面里，程序只负责填"
        if ((config?.level ?: AppConfig.LEVEL_FILL_ONLY) < AppConfig.LEVEL_NOTIFY) return

        val key = "${hit.postedAt}|${hit.text.orEmpty()}"
        if (key == lastHitKey) return
        lastHitKey = key

        // 读不到聊天记录的平台（他趣：整棵树被 noimp 摘掉）只能靠通知。
        // 这里按会话攒最近几条，生成时一起当上下文 —— 只留最后一条的话，
        // 模型看到的永远是半句话，回出来也只能是半句。
        NotifyContext.record(
            hit.packageName,
            hit.title.orEmpty(),
            hit.text.orEmpty(),
            System.currentTimeMillis(),
        )

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
        if (batchActive) return
        if (autoRunning) return
        val hit = pendingHit ?: return

        val root = targetRoot() ?: return
        if (root.packageName?.toString() != hit.packageName) return
        val profile = Profiles.forPackage(hit.packageName) ?: return
        if (!NodeQuery.isChatPage(root, profile)) {
            // 不丢弃：pendingHit 还留着，等你点进那个会话时
            // onAccessibilityEvent 会再试一次。这里只写一句，
            // 免得用户以为"通知收到了但程序没反应"是坏了。
            logAuto("收到「${hit.title ?: hit.packageName}」的消息，但当前不在聊天页，等你点进去")
            return
        }

        pendingHit = null
        // 昵称随通知一起带下去：他趣没有 peerName 选择器，
        // 拿不到昵称就只能按"消息文本"去重 —— 十个人说「你好」会被当成同一条，
        // 后面九个全被 30 分钟窗口挡掉。
        runAuto(hit.text, notifyPeer = hit.title)
    }

    /**
     * @param manual true 表示用户主动发起（连点 / 点了按钮），此时精力和长冷却让路。
     * @param notifyPeer 通知里的会话标题（通常是对方昵称）。只给"读不到聊天页"的平台用。
     */
    @Synchronized
    private fun runAuto(
        notifyText: String?,
        manual: Boolean = false,
        notifyPeer: String? = null,
        onDone: (() -> Unit)? = null,
    ) {
        // 必须在这里再拦一次，不能只靠调用方检查。
        // "连点"和"界面变化的自动触发"是两条独立入口，都能走到这里；
        // 只在各自调用前检查的话，中间仍有窗口期 —— 真机表现就是
        // 输入框里被写了两遍同一句话（「你好啊你好啊」）。
        if (autoRunning) {
            if (SystemClock.elapsedRealtime() - autoStartedAt > AUTO_STUCK_TIMEOUT_MS) {
                autoRunning = false
                logAuto("上一步卡住了，已强制复位")
            } else {
                return
            }
        }

        val cfg = config ?: return
        autoRunning = true
        autoStartedAt = SystemClock.elapsedRealtime()
        ReplyRunner(this, cfg).runOnce(notifyText, manual, notifyPeer) { result ->
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

    /**
     * 最近一次真写进输入框的时刻。
     *
     * 填入会让输入框变化，那个变化本身又会触发一次 content_changed ——
     * 不拦的话，"填了"会被当成"有新消息"，又生成一遍同样的草稿。
     * `maybeAutoReplyOnChange` 用它跳过填入后的 5 秒。
     */
    @Volatile
    var lastFillAt: Long = 0L
        private set

    /** 草稿真的写进输入框时调用。 */
    fun noteFill() {
        lastFillAt = SystemClock.elapsedRealtime()
    }

    /**
     * 判定链说"先等等"时，把自动触发推迟到这个时刻之后。
     *
     * 不加这个的话，界面每变一次就重新判一次、又得一次"精力低，稍等"，
     * 日志里连刷好几条「等 N 分钟再回」—— 看起来像卡死，其实是同一件事判了四次。
     */
    @Volatile
    private var autoPostponedUntil = 0L

    fun postponeAuto(ms: Long) {
        autoPostponedUntil = SystemClock.elapsedRealtime() + ms.coerceAtLeast(0L)
    }

    private var batchWatchdog: Runnable? = null

    /** 连续几次发现"本应用在前台"。用来避免切不回去时无限重试。 */
    private var frontFailCount = 0

    /**
     * 连续切了几个平台都还没有未读。
     *
     * 用来终止"两个平台都没未读了，来回切"的死循环 ——
     * 没有它，程序会在两个 App 之间无限跳。
     */
    private var platformHops = 0

    /**
     * 连续几次发现"当前会话还有没接的话"。
     *
     * 用来避免一直等着不换台 —— 有些会话（比如只有一条系统招呼）
     * 永远轮不到"我最后说"这个状态。
     */
    private var platformWait = 0

    /**
     * 连续几次没找到「下一个未读」按钮。
     *
     * 用来把「按钮还没渲染出来」和「真的没有未读了」分开。
     * 原来这两种是同一个结果：一次找不到就判"所有平台都没未读"、直接收工 ——
     * 而刚发完消息那一下，列表正在重排，按钮经常还不在树上。
     */
    private var nextMiss = 0

    /** 找不到「下一个未读」时最多重试几次（每次间隔 1.2 秒，合计约 3.6 秒）。 */
    private val nextMissLimit = 3

    /**
     * 这一轮连点已经待过的平台。
     *
     * 存在的理由：真机出现过「心遇找不到下一个未读 → 切同城 → 同城也找不到 → 切回心遇」，
     * 在两个 App 之间来回弹。切换的本意是"这个平台确实没有未读了"，
     * 而"按钮没认出来"和"没有未读"在代码里长得一模一样 ——
     * 分不清的时候，**宁可停下来说清楚，也不要来回弹**（来回切前台本身就是异常行为）。
     */
    private val visitedPkgs = HashSet<String>()

    /** 按配置顺序找下一个平台。只有一个平台时返回 null。 */
    private fun nextPlatformPkg(): String? {
        val list = config?.targetPackages.orEmpty().filter { it.isNotBlank() }
        if (list.size < 2) return null
        val idx = list.indexOf(lastTargetPkg)
        return if (idx < 0) list.first() else list[(idx + 1) % list.size]
    }

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
        visitedPkgs.clear()
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
     * **只用 `startActivity`，绝不能用返回键。** 上一版用 `GLOBAL_ACTION_BACK`
     * 想"退出自己、回到上一个应用"，但 AutoReply 是从桌面图标启动的全屏
     * Activity —— 按 BACK 直接退到桌面，把用户的界面整个关掉了，
     * 而且并没有切到目标应用。这是实测出来的错误，别再改回去。
     *
     * `startActivity` 在 Android 10+ 后台会被限制，但**有悬浮窗
     * （SYSTEM_ALERT_WINDOW）权限就豁免**。没权限时它会失败，
     * 那就让用户自己切 —— 反正他本来就要回到目标应用里点发送。
     */
    private fun bringTargetToFront(prefer: String? = null): String? {
        val candidates = buildList {
            if (prefer != null) add(prefer)
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
            if (SystemClock.elapsedRealtime() - autoStartedAt > AUTO_STUCK_TIMEOUT_MS) {
                autoRunning = false
                logAuto("上一步超时，已强制复位")
            } else {
                return
            }
        }

        // 统一处理「看不到目标界面」的所有情况：
        //   1. AutoReply 自己在前台（手势会被它接走）
        //   2. 系统的「AutoReply 想要打开同城夜聊」确认框在最上层
        //   3. 应用切换动画还没结束
        //
        // **这三种都不该立刻 stop。** 之前只在第 1 种上等待，第 2 种
        // （那个确认框）会直接落到下面的 targetRoot()==null → 停止，
        // 日志表现就是"连点开始"后隔 1 秒"连点停止：目标界面不在前台"。
        val root = targetRoot()
        if (root == null) {
            frontFailCount++
            if (frontFailCount > 25) {
                stopBatch("连点停止：16 秒内看不到目标界面。请确认目标应用在前台，或检查设置里的包名")
                return
            }
            if (frontFailCount == 1) {
                val err = bringTargetToFront()
                logAuto(
                    when {
                        err == null -> "正在切到目标应用…（若弹出询问请选「始终允许」）"
                        else -> "请手动切到目标应用：$err"
                    }
                )
            } else if (frontFailCount == 8) {
                // 第 8 次（约 6 秒）还没看到，多半是那个确认框在等用户点
                logAuto("还看不到目标界面 —— 如果屏幕上有「想要打开同城夜聊」的询问，请点打开并勾选始终允许")
            } else if (frontFailCount % 8 == 0) {
                logAuto("等待目标界面…还剩约 ${(25 - frontFailCount) * 8 / 10} 秒")
            }
            mainHandler.postDelayed({ batchStep() }, 800L)
            return
        }
        frontFailCount = 0

        val profile = Profiles.forPackage(root.packageName?.toString() ?: "") ?: run {
            stopBatch("连点停止：这个平台没有配置")
            return
        }
        // 会话列表读不到的平台（他趣：整棵树被 noimp 摘掉）根本没有"下一个未读"可点，
        // 连点在这里只会白转三圈然后报"所有平台都没有未读了"。
        // 直接说清楚，比让它自己撞墙好。
        if (profile.message.isEmpty()) {
            stopBatch("这个平台读不到会话列表（他趣这类），连点用不了 —— 用通知触发或手动处理")
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
            runAuto(null, manual = true) { armBatchWatchdog() }
            return
        }

        // 点之前重新取一次根节点：上面 readMessages 会把列表滚到底，
        // 之前那个 root 可能已经过期（子节点被回收），在过期的树上找按钮会"找不到"。
        val clickRoot = targetRoot() ?: root
        // 用**所有**窗口去找那个按钮：它有时挂在同一个 App 的另一个窗口里
        // （浮层 / 悬浮按钮），只搜 targetRoot() 会"明明看得见却找不到"。
        val roots = allTargetRoots().ifEmpty { listOf(clickRoot) }
        val clicked = NodeQuery.clickNextUnread(
            roots, profile,
            tap = { n -> com.autoreply.app.platform.TextWriter.tapNode(this, n) },
            onNote = { note -> logAuto(note) },
        )
        if (!clicked) {
            // **「找不到下一个未读按钮」不等于「没有未读消息」** ——
            // 这类按钮通常只在"还有别的未读会话"时才出现，
            // 当前会话本身没处理完时它也是不显示的。
            // 不确认就换台，就会出现"这边还有话没接，程序已经跑去开另一个 App"。
            val lastIsOther = messages.lastOrNull()?.fromOther == true
            val alreadyHandled = com.autoreply.app.core.guard.DraftQueue.size() > 0 ||
                SystemClock.elapsedRealtime() - lastFillAt < 60_000L
            if (lastIsOther && !alreadyHandled && platformWait < 3) {
                platformWait++
                logAuto("当前会话还有没接的话，先不换台（第 $platformWait 次确认）")
                mainHandler.postDelayed({ batchStep() }, 3000L)
                return
            }
            platformWait = 0

            // 刚发完那一下，「下一个未读」按钮常常还没重新出现：
            // 列表在重排，或者这个按钮只在"确实还有别的未读"时才渲染出来。
            // 原来这里立刻判"没有未读了"→ 切平台 → 收工，
            // 于是连点在你点完发送的一两秒内就结束了 ——
            // 表现正是「我发了，它不往下走」。
            if (nextMiss < nextMissLimit) {
                nextMiss++
                logAuto("没看到「下一个未读」，稍等再看（第 $nextMiss 次）")
                mainHandler.postDelayed({ batchStep() }, 1_200L)
                return
            }
            nextMiss = 0

            // 这个平台没有未读了 —— 如果还配了别的平台，轮过去接着跑。
            // 用户的实际用法就是"同城打完打心遇"，让他手动切一次很别扭。
            val platforms = config?.targetPackages.orEmpty().filter { it.isNotBlank() }
            val here = root.packageName?.toString().orEmpty()
            if (here.isNotEmpty()) visitedPkgs.add(here)
            val next = nextPlatformPkg()
            platformHops++
            // 绕回来 = 两个平台都没认出按钮，再切下去就是来回弹了。
            // 这种情况几乎都是选择器过时（App 改版），说明白比继续切有用。
            if (next == null || platformHops > platforms.size || visitedPkgs.contains(next)) {
                stopBatch(
                    "连点停下：没找到「下一个未读」按钮。" +
                        "如果界面上明明有那个小箭头，说明选择器过时了 —— 抓一次界面发我。"
                )
                return
            }
            logAuto("本平台没有未读了，切到下一个平台继续（第 $platformHops 次换台）")
            // 换台等于换会话，没发完的断句必须丢掉
            com.autoreply.app.core.guard.DraftQueue.clear()
            bringTargetToFront(next)?.let { logAuto("切平台失败：$it") }
            // 4 秒而不是 2.2：真机出现过"切过去 1 秒就判定所有平台都没未读"，
            // 目标是冷启动的，界面根本还没渲染完
            mainHandler.postDelayed({ batchStep() }, 4000L)
            return
        }
        platformHops = 0
        nextMiss = 0

        // 换会话了，上一段没发完的断句必须丢掉，
        // 否则会把 A 会话的第二句发给 B
        com.autoreply.app.core.guard.DraftQueue.clear()

        // 等会话渲染完再读。太短会读到上一个会话的残留节点，
        // 800~1200ms 是实测能稳定读到新会话内容的区间。
        mainHandler.postDelayed({
            if (!batchActive) return@postDelayed
            // **先确认真的换会话了。**
            //
            // 「点了」和「切过去了」是两件事：同城那个 `#tv_next` 是自定义 View，
            // performAction 返回 true 但界面不动（真机踩过）。原来不验证的话，
            // 程序以为切走了，接着在同一个会话里再生成一遍 ——
            // 用户看到的就是"不往下走，还重复"。
            val fresh = targetRoot()
            val afterPeer = fresh?.let { NodeQuery.peerName(it, profile) }.orEmpty()
            if (afterPeer.isNotEmpty() && afterPeer == peer) {
                nextMiss++
                if (nextMiss > nextMissLimit) {
                    stopBatch("连点停下：点了「下一个未读」但还停在「$peer」，按钮没反应")
                } else {
                    logAuto("点了「下一个未读」但会话没换，重试（第 $nextMiss 次）")
                    mainHandler.postDelayed({ batchStep() }, 700L)
                }
                return@postDelayed
            }
            // 写一句"切过去了"，连点的每一步在首页都能看到 ——
            // 出错时才知道停在哪一步，而不是只有一句"已写入"反复出现。
            if (afterPeer.isNotEmpty()) logAuto("已切到「$afterPeer」")
            runAuto(null, manual = true) { armBatchWatchdog() }
        }, 800L + kotlin.random.Random.nextLong(400))
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

            // 这条日志很重要：没有它，用户只看到"发完就不动了"，
            // 完全不知道程序在等什么、等了多久。
            logAuto("等了 ${BATCH_SEND_TIMEOUT_MS / 1000} 秒没收到发送信号，这条跳过，看下一个会话")
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
        // 400~800ms：发完一条就该往下走，用户不会等着看对方回不回。
        // 仍然带随机 —— 固定 400 这种等距节奏本身就是特征。
        mainHandler.postDelayed({ batchStep() }, 400L + kotlin.random.Random.nextLong(400))
    }

    private var nextSegmentTask: Runnable? = null

    /**
     * 手动模式下的兜底：填入后 N 秒如果还没检测到发送，也把下一段填上。
     *
     * 连点模式有 [armBatchWatchdog] 兜底，但**手动点「生成并填入」时没有** ——
     * 那时一旦漏检（比如列表刷新慢），第二段就永远不出现，用户只能自己再点一次。
     * 这里给同样的兜底，只是时间更短（用户正盯着屏幕等）。
     */
    fun scheduleNextSegment() {
        if (com.autoreply.app.core.guard.DraftQueue.size() == 0) return
        if (batchActive) return // 连点有自己的看门狗，别重复
        nextSegmentTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val root = targetRoot() ?: return@Runnable
            val profile = Profiles.forPackage(root.packageName?.toString() ?: "") ?: return@Runnable
            fillNextSegment(root, profile)
        }
        nextSegmentTask = task
        mainHandler.postDelayed(task, 12_000L)
    }

    /**
     * 断句的下一段：填进去，不切会话。
     *
     * @return true 表示还有下一段要发，调用方应当停下（别急着切会话）
     */
    private fun fillNextSegment(root: AccessibilityNodeInfo, profile: PlatformProfile): Boolean {
        val next = com.autoreply.app.core.guard.DraftQueue.poll() ?: return false
        nextSegmentTask?.let { mainHandler.removeCallbacks(it) }
        nextSegmentTask = null
        // replaceExisting = true：上一段如果还挂在输入框里就先清掉再写
        val res = com.autoreply.app.platform.TextWriter.write(
            this, profile, next, replaceExisting = true,
        )
        // 走哪条通道决定提示怎么写 —— 没有输入法时这段是放剪贴板，
        // 用户需要知道"去粘一下"，而不是站在那儿等它自己出现在输入框里
        logAuto(
            when {
                res.written -> "上一句已发，接着填：${next.take(16)}"
                res.pending -> "上一句已发，已排队：${next.take(16)}"
                else -> "上一句已发，下一段已复制到剪贴板：${next.take(16)}"
            }
        )
        // 新的一段等于重新开始等发送，看门狗要重新计时
        if (batchActive) armBatchWatchdog()
        // 队列里还有就为它排兜底。
        //
        // 不排的话，第三段只能靠"检测到发送"这一条路 ——
        // 而检测一漏（列表刷新慢、刚好在看别的地方），第三段就永远不出现。
        // 实测三句开场白：第一段、第二段都出来了，第三段没了。
        if (com.autoreply.app.core.guard.DraftQueue.size() > 0) scheduleNextSegment()
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
        // 判定链刚说过"先等等" —— 到点之前别再判了
        if (SystemClock.elapsedRealtime() < autoPostponedUntil) return
        val cfg = config ?: return

        val root = targetRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val profile = Profiles.forPackage(pkg) ?: return
        // 读不到消息的平台（他趣：整棵树被 noimp 摘掉）只能等通知，
        // 而它们偏偏不发通知 —— 这类平台只能手动，或者靠输入法方案
        if (profile.message.isEmpty()) return
        if (!NodeQuery.isChatPage(root, profile)) return

        val messages = NodeQuery.readMessages(root, profile)
        val peer = NodeQuery.peerName(root, profile)

        // ★ 发送检测必须最先做，不受下面任何"要不要自动回"的条件约束。
        //
        // 这里踩过一次：把它放在防抖后面，结果"填完草稿 2 秒后点发送"
        // 这个最常见的情况永远检测不到 —— 已发计数不涨、连点不推进、
        // 断句的第二段也不出现，三个症状全都由这一个顺序错误引起。
        //
        // 判断依据是「同一个会话里，我发的最后一条变了」，不依赖草稿被原样发出
        //（用户可能改几个字，也可能自己手打，那些同样是真实发送）。
        val nowWall = System.currentTimeMillis()
        val tracker = SentTracker(this)
        val sentByCount = tracker.detectNewSent(messages, peer, nowWall)
        // 第二条判据：**填进去的那句话自己出现在右侧**。
        //
        // 条数判据有个已知盲区 —— 消息列表只读可见区域，发完之后旧气泡滚出屏幕、
        // 新气泡滚进来，条数可能**不变**，于是"明明发出去了"被判成"没发"：
        // 配额不涨、连点不推进、断句的第二段永远不填。三个症状同一个原因。
        //
        // 只在 90 秒内认：更早的 pending 本身就该作废，
        // 否则会和历史里一句同款的旧气泡混淆，把"还没发"误判成"发了"。
        val pendingRecent = tracker.pending?.let { nowWall - it.at < 90_000L } == true
        val sentByText = !sentByCount && pendingRecent &&
            tracker.confirmIfSent(messages, peer, nowWall)

        if (sentByCount || sentByText) {
            // 条数判据已经确认发出去了，pending 就没用了。
            // 不清的话，它会在 10 分钟窗口里继续挂着，
            // 之后填一句内容相同的新草稿时可能被误认成"已经发过"。
            if (sentByCount) tracker.clear()
            PacerGuard(this, cfg).recordSent(nowWall)
            // 断句：这句话是拆开发的，先接着填下一句 —— 不切会话
            if (fillNextSegment(root, profile)) return
            // 连点模式下，"你点了发送"就是推进信号
            onBatchSent()
        }

        // ★ 以下才是"要不要自动生成草稿"，三个门都只该管这件事
        //
        // 连点进行中：两条路都跑 runAuto 会互相踩（连点填完一句、自动触发又填一次
        // 就是「你好啊你好啊」），而且自动触发会被精力和冷却拦住刷出一堆日志。
        if (batchActive) return
        if (autoRunning) return
        // 刚填过字的 5 秒内不触发：填入让输入框变化，那个变化本身又会触发一次
        // content_changed —— 不拦的话"填了"会被当成"有新消息"。
        if (SystemClock.elapsedRealtime() - lastFillAt < 5_000L) return
        // L0 档不自动：那个档位的定义就是"人自己停在页面里，程序只负责填"
        if (cfg.level < AppConfig.LEVEL_NOTIFY) return

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

        // 界面驱动的自动触发。平时节流 900ms：读消息要遍历树，
        // 而列表滚动一次能发几十个事件，不节流会把主线程拖住。
        //
        // 但连点模式下（或还有断句没发完），"及时切下一个"比省这点 CPU 重要得多 ——
        // 发完一条就该走，没人会在那儿等着对方回复。所以这时收到 350ms。
        val elapsed = SystemClock.elapsedRealtime()
        val urgent = batchActive || com.autoreply.app.core.guard.DraftQueue.size() > 0
        // 200ms 而不是 350：断句的第二段要等"检测到发送 → 再填"，
        // 这个间隔直接叠加到用户感知的延迟上。再把每轮遍历树的成本压到
        // 可接受范围 —— 只读一次消息列表，不跑判定链。
        val interval = if (urgent) 200L else AUTO_CHECK_INTERVAL_MS
        if (elapsed - lastAutoCheckElapsed >= interval) {
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
        // 设置里没填 = 认所有在 profiles.json 里配过的平台。
        //
        // 原来写的是"没填就认任意包名"，结果是：用户切到任何一个 App，
        // 都可能被当成目标界面拿进来，然后在 forPackage 那里失败，
        // 报一句"这个平台没有配置" —— 但用户根本没让它去认那个 App。
        // 改成"只认配置过的"，新增平台只要加一段 json 就能直接用，
        // 不必再去设置页手填包名。
        val configured = Profiles.forPackage(pkg) != null
        if (list.isEmpty()) return configured
        return list.contains(pkg) || configured
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
    /**
     * 该 App 的所有窗口根。弹窗盖住时，输入框往往在"下面那个窗口"里。
     */
    fun allTargetRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(4)
        runCatching {
            rootInActiveWindow?.let { if (isTarget(it.packageName?.toString())) out.add(it) }
            windows?.forEach { w ->
                runCatching {
                    val r = w.root
                    if (r != null && isTarget(r.packageName?.toString())) out.add(r)
                }
            }
        }
        return out
    }

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
