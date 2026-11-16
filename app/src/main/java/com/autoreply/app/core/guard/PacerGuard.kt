package com.autoreply.app.core.guard

import android.content.Context
import com.autoreply.app.core.AppConfig
import kotlin.random.Random

/**
 * 节奏与熔断。
 *
 * 这个类不负责"怎么点"，只负责回答一个问题：**现在允许动作吗**。
 * 把"能不能"和"怎么做"分开，是因为护栏必须能被单独审计 ——
 * 出问题的时候，先看这里的判定，不用在业务代码里捞。
 *
 * 它坚持的四条：
 *  1. 按小时硬限流，超了就是超了，不排队、不补发。
 *  2. 连续动作达到上限后强制长冷却，不靠"看起来间隔够了"这种主观判断。
 *  3. 触发平台提示就整段熔断，且熔断状态落盘 —— 重启 App 不能绕过。
 *  4. 间隔乘随机系数，避免任何固定周期。
 */
class PacerGuard(context: Context, private val config: AppConfig) {

    private val sp = context.applicationContext
        .getSharedPreferences("autoreply_pacer", Context.MODE_PRIVATE)

    sealed class Decision {
        object Allow : Decision()
        data class Block(val reason: String, val retryAfterMs: Long) : Decision()
    }

    data class Status(
        val usedThisHour: Int,
        val hourlyCap: Int,
        val burstCount: Int,
        val burstLimit: Int,
        val coolingUntil: Long,
        val haltedUntil: Long,
    )

    fun check(now: Long = System.currentTimeMillis()): Decision {
        val halted = haltedUntil()
        if (halted > now) {
            return Decision.Block("已熔断：${minutesOf(halted - now)} 分钟后恢复", halted - now)
        }

        val cooling = coolingUntil()
        if (cooling > now) {
            return Decision.Block("长冷却中：${minutesOf(cooling - now)} 分钟后恢复", cooling - now)
        }

        val used = usedInCurrentHour(now)
        if (used >= config.hourlyCap) {
            val nextHour = nextHourStart(now)
            return Decision.Block("本小时已达上限 ${config.hourlyCap} 条", nextHour - now)
        }

        return Decision.Allow
    }

    /** 每次真正执行完一个动作（读+填，不含发送）后调用。 */
    fun recordAction(now: Long = System.currentTimeMillis()) {
        rollHourIfNeeded(now)
        sp.edit().putInt(KEY_USED, sp.getInt(KEY_USED, 0) + 1).apply()

        val burst = sp.getInt(KEY_BURST_COUNT, 0) + 1
        if (burst >= config.burstLimit) {
            // 达到连续上限 → 清零并进入长冷却。冷却时长带 ±30% 随机，
            // 固定 20 分钟这种整齐数字本身就是特征。
            val jitter = (config.cooldownMinutes * Random.nextDouble(0.7, 1.3)).toLong()
            val until = now + jitter * 60_000L
            sp.edit()
                .putInt(KEY_BURST_COUNT, 0)
                .putLong(KEY_COOLING_UNTIL, until)
                .apply()
        } else {
            sp.edit().putInt(KEY_BURST_COUNT, burst).apply()
        }
    }

    /**
     * 界面上出现"操作频繁 / 请稍后再试 / 需要验证"这类字样时调用。
     * 这里是整段熔断，不是"跳过这一条"。
     */
    fun trip(reason: String, minutes: Int = 30, now: Long = System.currentTimeMillis()) {
        val jitter = (minutes * Random.nextDouble(0.85, 1.4)).toLong()
        sp.edit()
            .putLong(KEY_HALTED_UNTIL, now + jitter * 60_000L)
            .putInt(KEY_BURST_COUNT, 0)
            .putString(KEY_HALT_REASON, reason)
            .apply()
    }

    /** 用户手动解除熔断。只在确认平台已经恢复正常后使用。 */
    fun clearHalt() {
        sp.edit().putLong(KEY_HALTED_UNTIL, 0L).remove(KEY_HALT_REASON).apply()
    }

    /** 下一条动作建议等多久：基础间隔 × [0.7, 1.8]。 */
    fun randomizedGapMs(): Long {
        val base = config.minGapSeconds * 1000L
        return (base * Random.nextDouble(0.7, 1.8)).toLong()
    }

    fun status(now: Long = System.currentTimeMillis()): Status = Status(
        usedThisHour = usedInCurrentHour(now),
        hourlyCap = config.hourlyCap,
        burstCount = sp.getInt(KEY_BURST_COUNT, 0),
        burstLimit = config.burstLimit,
        coolingUntil = coolingUntil(),
        haltedUntil = haltedUntil(),
    )

    fun haltReason(): String = sp.getString(KEY_HALT_REASON, "") ?: ""

    // ---------------------------------------------------------------- 内部

    private fun haltedUntil(): Long = sp.getLong(KEY_HALTED_UNTIL, 0L)

    private fun coolingUntil(): Long = sp.getLong(KEY_COOLING_UNTIL, 0L)

    /** 以自然小时为桶，跨小时自动归零。 */
    private fun usedInCurrentHour(now: Long): Int {
        rollHourIfNeeded(now)
        return sp.getInt(KEY_USED, 0)
    }

    private fun rollHourIfNeeded(now: Long) {
        val bucket = now / 3_600_000L
        if (sp.getLong(KEY_HOUR_BUCKET, -1L) != bucket) {
            sp.edit().putLong(KEY_HOUR_BUCKET, bucket).putInt(KEY_USED, 0).apply()
        }
    }

    private fun nextHourStart(now: Long): Long = (now / 3_600_000L + 1) * 3_600_000L

    private fun minutesOf(ms: Long): Long = (ms / 60_000L).coerceAtLeast(1L)

    private companion object {
        const val KEY_HOUR_BUCKET = "hour_bucket"
        const val KEY_USED = "used"
        const val KEY_BURST_COUNT = "burst_count"
        const val KEY_COOLING_UNTIL = "cooling_until"
        const val KEY_HALTED_UNTIL = "halted_until"
        const val KEY_HALT_REASON = "halt_reason"
    }
}
