package com.autoreply.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoreply.app.core.AppConfig

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
    }

    private var config: AppConfig? = null

    @Volatile
    private var cachedDump: String = ""

    @Volatile
    private var cachedAtWall: Long = 0L

    @Volatile
    private var cachedPkg: String = ""

    private var lastDumpElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = AppConfig(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // 自己的界面永不缓存。少了这一行，用户切回来点「抓取」，
        // 缓存里就是自己这一页的节点树 —— 看起来像抓取坏了，其实是抓错对象。
        if (pkg == packageName) return

        if (!isTarget(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpElapsed < MIN_DUMP_INTERVAL_MS) return

        val root = targetRoot() ?: return
        lastDumpElapsed = now
        cachedDump = NodeDumper.dump(root)
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
        if (isTarget(activePkg)) return active

        val list = windows ?: return null
        for (w in list) {
            if (w == null) continue
            if (!isAppWindow(w)) continue
            val r = w.root ?: continue
            if (isTarget(r.packageName?.toString())) return r
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
            body = NodeDumper.dump(root)
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
