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

    /** 配置的目标包名集合。空表示"没指定，任何非自身界面都算"。 */
    fun targets(): List<String> = config?.targetPackages ?: emptyList()

    fun isTarget(pkg: String?): Boolean {
        if (pkg == null || pkg == packageName) return false
        val list = targets()
        return list.isEmpty() || list.contains(pkg)
    }

    /**
     * 目标 App 的根节点。
     *
     * **不要用 rootInActiveWindow 直接判"目标在不在"**：
     * 用户开分屏/小窗时，目标 App 明明就在屏幕上，rootInActiveWindow
     * 返回的却可能是我方或另一个窗口。先看活动窗口，不是就遍历 windows 按包名找。
     */
    fun targetRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString()
        if (isTarget(activePkg)) return active

        val list = windows ?: return null
        for (w in list) {
            val r = w?.root ?: continue
            if (isTarget(r.packageName?.toString())) return r
        }
        return null
    }

    /** 目标 App 窗口在屏幕上的位置。分屏时它只占半屏，任何区域计算都要按这块算。 */
    fun targetWindowBounds(): Rect? {
        val list = windows ?: return null
        for (w in list) {
            val r = w?.root ?: continue
            if (!isTarget(r.packageName?.toString())) continue
            val rect = Rect()
            w.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
        }
        return null
    }

    /** 前台是不是目标 App。L0 档要靠它决定能不能动作。 */
    fun isTargetForeground(): Boolean = isTarget(rootInActiveWindow?.packageName?.toString())

    data class Snapshot(val text: String, val source: String)

    /** 前台就是目标时抓实时；否则退回最近一次缓存。都没有返回 null。 */
    fun snapshot(): Snapshot? {
        val root = targetRoot()
        if (root != null) {
            val text = NodeDumper.dump(root)
            cachedDump = text
            cachedAtWall = System.currentTimeMillis()
            cachedPkg = root.packageName?.toString() ?: ""
            return Snapshot(text, "实时")
        }
        if (cachedDump.isNotEmpty()) {
            val who = if (cachedPkg.isEmpty()) "" else "（$cachedPkg）"
            return Snapshot(cachedDump, "缓存于 ${agoText(cachedAtWall)}$who")
        }
        return null
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
