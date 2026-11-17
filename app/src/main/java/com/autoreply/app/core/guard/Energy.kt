package com.autoreply.app.core.guard

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * 精力状态机。
 *
 * 解决的是"节奏像不像人"。固定间隔（哪怕加了随机系数）统计上仍然是均匀分布，
 * 而真人的节奏是有疲劳曲线的：聊几句会慢下来，隔久了又恢复。
 *
 * 它**不替代** [PacerGuard]：
 *   PacerGuard 管的是平台风险（每小时条数、熔断），是安全阀
 *   Energy     管的是拟人度（疲劳曲线）
 * 混在一起会让安全阀变得"可以被疲劳绕过"，那是错的。
 *
 * 值域 [MIN, MAX]，落盘保存，按时间流逝自动恢复，跨日重置。
 */
class Energy(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("autoreply_energy", Context.MODE_PRIVATE)

    /** 现在有多少精力。 */
    fun current(now: Long = System.currentTimeMillis()): Double {
        refresh(now)
        return sp.getFloat(KEY_VALUE, DEFAULT_START).toDouble()
    }

    /** 消耗一次（每次填入草稿后调用）。消耗量与当前精力成正比 —— 越累越提不起劲。 */
    fun consume(now: Long = System.currentTimeMillis()): Double {
        refresh(now)
        val v = sp.getFloat(KEY_VALUE, DEFAULT_START).toDouble()
        val next = max(MIN, v - DECAY)
        sp.edit().putFloat(KEY_VALUE, next.toFloat()).apply()
        return next
    }

    /**
     * 把精力回满。语义是「我休息好了」，不是「作弊」——
     * 连续测几次就会掉到 0.28，之后连自动触发都被压住，
     * 而用户明明刚坐下、精神很好。给一个明确的复位入口比让他等半小时合理。
     */
    fun reset(now: Long = System.currentTimeMillis()) {
        sp.edit()
            .putFloat(KEY_VALUE, MAX.toFloat())
            .putLong(KEY_LAST_AT, now)
            .apply()
    }

    /**
     * 精力对应的等待倍数。
     *
     * 精力高 → 1.0（按基础间隔走）
     * 精力低 → 3.0（同样一条消息，等三倍时间才准备草稿）
     *
     * 这个倍数不是拍脑袋：低精力时"拖着不回"正是真人的样子，
     * 而机器人在深夜和凌晨的响应速度完全一样。
     */
    fun delayMultiplier(now: Long = System.currentTimeMillis()): Double = when {
        current(now) >= 0.80 -> 1.0
        current(now) >= 0.60 -> 1.4
        current(now) >= 0.40 -> 2.0
        else -> 3.0
    }

    /** 精力是否低到"只该回必须回的"。 */
    fun isTired(now: Long = System.currentTimeMillis()): Boolean = current(now) < 0.30

    // ------------------------------------------------------------------ 内部

    /** 按流逝时间恢复，跨日重置。每次读写前都刷一遍，不需要后台定时器。 */
    private fun refresh(now: Long) {
        val lastAt = sp.getLong(KEY_LAST_AT, 0L)
        val lastDay = sp.getString(KEY_DAY, "") ?: ""
        val today = java.time.LocalDate.now().toString()

        if (lastDay != today) {
            // 跨日重置：睡一觉起来精力恢复，但不到满格
            sp.edit()
                .putFloat(KEY_VALUE, DAY_RESET)
                .putLong(KEY_LAST_AT, now)
                .putString(KEY_DAY, today)
                .apply()
            return
        }

        if (lastAt <= 0L) {
            sp.edit().putLong(KEY_LAST_AT, now).putString(KEY_DAY, today).apply()
            return
        }

        val hours = (now - lastAt).coerceAtLeast(0L) / 3_600_000.0
        if (hours <= 0.0) return

        val v = sp.getFloat(KEY_VALUE, DEFAULT_START).toDouble()
        val next = min(MAX, v + hours * RECOVER_PER_HOUR)
        sp.edit()
            .putFloat(KEY_VALUE, next.toFloat())
            .putLong(KEY_LAST_AT, now)
            .putString(KEY_DAY, today)
            .apply()
    }

    private companion object {
        const val KEY_VALUE = "value"
        const val KEY_LAST_AT = "last_at"
        const val KEY_DAY = "day"

        const val MIN = 0.15
        const val MAX = 1.0

        /** 每天第一次打开时的精力：不是满格，接近满格。 */
        const val DEFAULT_START = 0.85f
        const val DAY_RESET = 0.85f

        /**
         * 每填一次草稿消耗多少。
         *
         * 0.06 而不是 0.18：0.18 意味着连续 5 次就耗光，而实际用法是
         * 「一连串聊十几个人」。按 0.18 算，第三条开始就在被拦，
         * 恢复又极慢（见下），等于半天只能用三次。
         */
        const val DECAY = 0.06

        /**
         * 不动作时每小时恢复多少。
         *
         * 0.20 而不是 0.06：0.06 时恢复一次的消耗要 3 小时，
         * 从 0.15 回满要近 12 小时 —— 那不是"累了"，是"废了"。
         * 现在 18 分钟回一次消耗的量，符合"歇一会儿就缓过来"。
         */
        const val RECOVER_PER_HOUR = 0.20
    }
}
