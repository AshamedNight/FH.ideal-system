package com.autoreply.app.core.guard

import android.content.Context
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.llm.ReplyEngine
import com.autoreply.app.core.llm.Turn
import com.autoreply.app.core.persona.NecessityJudge
import com.autoreply.app.core.persona.PersonaLoader
import kotlin.random.Random

/**
 * 把五个判定串成一条决策链。
 *
 * 顺序不能换，每一步都在拦不同性质的问题：
 *
 *   1. PacerGuard   平台风险：这一小时还能不能再动
 *   2. Necessity    该不该开口：这条值不值得回
 *   3. Energy       现在累不累：要不要拖着
 *   4. ReplyEngine  写什么，顺便过输入边界和禁忌词
 *   5. DraftRisk    这句话说错了代价大不大：能不能自动填
 *
 * 前四步是"能不能做"，第五步是"该不该替人做决定"。
 * 这五步分开写而不是塞进一个大函数，是为了出问题时能一眼看出是哪一层拦的。
 *
 * [decide] 会阻塞（内部调模型），调用方必须放在后台线程。
 */
class ReplyFlow(context: Context, private val config: AppConfig) {

    private val pacer = PacerGuard(context, config)
    private val energy = Energy(context)

    /** 人设只加载一次，整条链共用同一份。 */
    private val persona = PersonaLoader.load(context, config)
    private val engine = ReplyEngine(config, persona)

    sealed class Outcome {
        /** 可以填。risk 只用于界面标注，不影响填充。 */
        data class Fill(val text: String, val risk: DraftRisk.Verdict) : Outcome()

        /** 生成了，但风险太高不自动填。只把内容摆出来让人自己决定。 */
        data class Hold(val text: String, val risk: DraftRisk.Verdict, val reason: String) : Outcome()

        /** 现在不该动，等 afterMs 之后再说。 */
        data class Wait(val afterMs: Long, val reason: String) : Outcome()

        /** 这条不参与。 */
        data class Silent(val reason: String) : Outcome()

        data class Error(val reason: String) : Outcome()
    }

    fun decide(
        turns: List<Turn>,
        selfSaidAgoSeconds: Int = Int.MAX_VALUE,
        now: Long = System.currentTimeMillis(),
    ): Outcome {

        // ---- 1. 平台风险闸门 ----
        when (val d = pacer.check(now)) {
            is PacerGuard.Decision.Block -> return Outcome.Wait(d.retryAfterMs, d.reason)
            is PacerGuard.Decision.Allow -> Unit
        }

        val last = turns.lastOrNull { it.fromOther }
            ?: return Outcome.Silent("没有对方发来的消息")

        // ---- 2. 该不该开口 ----
        val judgment = NecessityJudge.judge(
            text = last.text,
            persona = persona,
            isPrivate = true,
            selfSaidAgoSeconds = selfSaidAgoSeconds,
        )
        when (judgment.action) {
            NecessityJudge.Action.SILENT -> return Outcome.Silent(
                judgment.reasons.joinToString("；")
            )
            NecessityJudge.Action.WAIT -> return Outcome.Wait(
                delayMs(baseMinutes = 12, now = now),
                "想回但先等等：" + judgment.reasons.joinToString("；"),
            )
            NecessityJudge.Action.REPLY -> Unit
        }

        // ---- 3. 累不累 ----
        if (energy.isTired(now)) {
            return Outcome.Wait(
                delayMs(baseMinutes = 8, now = now),
                "精力低（${"%.2f".format(energy.current(now))}），压一压再回",
            )
        }

        // ---- 4. 生成（内部已过输入边界与禁忌词） ----
        val result = engine.generate(turns)
        val draft = when (result) {
            is ReplyEngine.Result.Blocked -> return Outcome.Silent(result.reason)
            is ReplyEngine.Result.Failed -> return Outcome.Error(result.reason)
            is ReplyEngine.Result.Draft -> result.text
        }

        // 生成过一次就算消耗：无论后面填不填，模型调用和判断都发生了
        pacer.recordAction(now)
        energy.consume(now)

        // ---- 5. 这句话说错代价大不大 ----
        val risk = DraftRisk.classify(draft)
        return if (risk.fillable) {
            Outcome.Fill(draft, risk)
        } else {
            Outcome.Hold(
                draft,
                risk,
                "涉及" + risk.hits.joinToString("、") + "，这类话不替你填，你看着改",
            )
        }
    }

    fun status(now: Long = System.currentTimeMillis()): String {
        val s = pacer.status(now)
        return "本小时 ${s.usedThisHour}/${s.hourlyCap} · 连续 ${s.burstCount}/${s.burstLimit} " +
            "· 精力 ${"%.2f".format(energy.current(now))}"
    }

    /**
     * 等待时长 = 基准分钟 × 精力倍数 × [0.8, 1.3] 抖动。
     *
     * 三层乘法缺一不可：基准是业务节奏，精力让疲惫时拖更久，
     * 抖动保证两次等待不会一模一样（固定值本身就是指纹）。
     */
    private fun delayMs(baseMinutes: Int, now: Long): Long {
        val minutes = baseMinutes * energy.delayMultiplier(now)
        return (minutes * 60_000L * Random.nextDouble(0.8, 1.3)).toLong()
    }
}
