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

        /** 每填一次草稿消耗多少。约 4~5 条就从满格掉到疲劳区。 */
        const val DECAY = 0.18

        /** 不动作时每小时恢复多少。一晚上大概回满。 */
        const val RECOVER_PER_HOUR = 0.06
    }
}
