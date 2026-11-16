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
 * 不做任何点击 —— 点击是 [com.autoreply.app.platform.PlatformAdapter] 的事，
 * 这样"读"和"动"在代码里是两处，出问题能立刻定位是哪一侧。
 *
 * 缓存的必要性：用户切回本 App 时，前台窗口已经是我们自己，
 * rootInActiveWindow 返回的是自己的界面。所以抓取必须发生在目标 App 在前台的那一刻，
 * 由无障碍事件驱动、节流后存内存，主界面只是把缓存拿出来看。
 * 这个坑不踩一遍很难想到，但它是"点了抓取却抓到自己按钮"的全部原因。
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

    private var lastDumpElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = AppConfig(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val target = targetPackage()
        // 还没配置目标包名时，任何界面都值得缓存 —— 方便先用抓取工具摸结构。
        if (target.isNotEmpty() && pkg != target) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpElapsed < MIN_DUMP_INTERVAL_MS) return

        val root = targetRoot() ?: return
        lastDumpElapsed = now
        cachedDump = NodeDumper.dump(root)
        cachedAtWall = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cachedDump = ""
        return super.onUnbind(intent)
    }

    /**
     * 目标 App 的根节点。
     *
     * **不要用 rootInActiveWindow 直接判"目标在不在"**：
     * 用户开分屏/小窗时，目标 App 明明就在屏幕上，rootInActiveWindow
     * 返回的却可能是我方或另一个窗口。
     * 正确做法是先看活动窗口，不是就遍历 windows 按包名找。
     */
    fun targetRoot(): AccessibilityNodeInfo? {
        val target = targetPackage()
        val active = rootInActiveWindow
        if (target.isEmpty()) return active
        if (active?.packageName?.toString() == target) return active

        val list = windows ?: return null
        for (w in list) {
            val r = w?.root ?: continue
            if (r.packageName?.toString() == target) return r
        }
        return null
    }

    /** 目标 App 窗口在屏幕上的位置。分屏时它只占半屏，任何区域计算都要按这块算。 */
    fun targetWindowBounds(): Rect? {
        val target = targetPackage().ifEmpty { return null }
        val list = windows ?: return null
        for (w in list) {
            val r = w?.root ?: continue
            if (r.packageName?.toString() != target) continue
            val rect = Rect()
            w.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
        }
        return null
    }

    /** 前台是不是目标 App。L0 档要靠它决定能不能动作。 */
    fun isTargetForeground(): Boolean {
        val target = targetPackage().ifEmpty { return false }
        return rootInActiveWindow?.packageName?.toString() == target
    }

    data class Snapshot(val text: String, val source: String)

    /** 前台就是目标时抓实时；否则退回最近一次缓存。都没有返回 null。 */
    fun snapshot(): Snapshot? {
        val root = targetRoot()
        val target = targetPackage()
        if (root != null && (target.isEmpty() || root.packageName?.toString() == target)) {
            val text = NodeDumper.dump(root)
            cachedDump = text
            cachedAtWall = System.currentTimeMillis()
            return Snapshot(text, "实时")
        }
        if (cachedDump.isNotEmpty()) {
            return Snapshot(cachedDump, "缓存于 ${agoText(cachedAtWall)}")
        }
        return null
    }

    fun targetPackage(): String = config?.targetPackage.orEmpty()

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
