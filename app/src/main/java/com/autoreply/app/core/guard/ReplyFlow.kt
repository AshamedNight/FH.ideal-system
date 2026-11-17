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
        /**
         * 可以填。risk 只用于界面标注，不影响填充。
         *
         * token 字段一路带出来，是为了让界面还能显示真实用量 ——
         * 判定链包了一层，不该把"花了多少钱"这个信息吃掉。
         */
        data class Fill(
            val text: String,
            val risk: DraftRisk.Verdict,
            val attempts: Int = 1,
            val inputTokens: Int = 0,
            val outputTokens: Int = 0,
            val estimatedInputTokens: Int = 0,
        ) : Outcome()

        /** 生成了，但风险太高不自动填。只把内容摆出来让人自己决定。 */
        data class Hold(
            val text: String,
            val risk: DraftRisk.Verdict,
            val reason: String,
            val inputTokens: Int = 0,
            val outputTokens: Int = 0,
        ) : Outcome()

        /** 现在不该动，等 afterMs 之后再说。 */
        data class Wait(val afterMs: Long, val reason: String) : Outcome()

        /** 这条不参与。 */
        data class Silent(val reason: String) : Outcome()

        data class Error(val reason: String) : Outcome()
    }

    /**
     * 最近一次 [decide] 的逐层判定记录。
     *
     * 存在的理由：五层链任何一层都能拦下这条消息，但用户只看到"没填"。
     * 没有这份记录，"为什么没填"就只能靠猜 —— 是平台限流、还是判定不该回、
     * 还是精力低、还是风险太高？四种原因的处置完全不同。
     */
    private val traceBuffer = ArrayList<String>(6)
    val lastTrace: List<String> get() = traceBuffer.toList()

    private fun step(line: String) {
        traceBuffer.add(line)
    }

    /**
     * @param respectEnergy 是否让「精力」这一层拦人。**手动点按钮时传 false。**
     *
     *   精力模型存在的意义是让*自动*回复别太像机器（不秒回、会累、会拖）。
     *   但用户明确点了「生成并填入」就是他的意志，此时程序再替他判断"你累了"
     *   就变成了碍事 —— 连点两次就被压 27 分钟，而人自己明明清醒。
     *
     * @param respectCooldown 是否理会「程序自己歇的那段」长冷却。**手动时同样传 false。**
     *   同理：冷却属于拟人，不属于平台风险。
     *
     *   **两个开关都不影响平台闸门的核心部分**（每小时上限、熔断）—— 那才是真护栏。
     */
    fun decide(
        turns: List<Turn>,
        selfSaidAgoSeconds: Int = Int.MAX_VALUE,
        now: Long = System.currentTimeMillis(),
        respectEnergy: Boolean = true,
        respectCooldown: Boolean = true,
    ): Outcome {
        traceBuffer.clear()

        // ---- 1. 平台风险闸门 ----
        when (val d = pacer.check(now, respectCooldown)) {
            is PacerGuard.Decision.Block -> {
                step("① 平台闸门：拦截 — ${d.reason}")
                return Outcome.Wait(d.retryAfterMs, d.reason)
            }
            is PacerGuard.Decision.Allow -> {
                val st = pacer.status(now)
                step("① 平台闸门：通过（本小时 ${st.usedThisHour}/${st.hourlyCap}）")
            }
        }

        val last = turns.lastOrNull { it.fromOther }
            ?: run {
                step("② 该不该回：不参与（没有对方发来的消息）")
                return Outcome.Silent("没有对方发来的消息")
            }

        // ---- 2. 该不该开口 ----
        val judgment = NecessityJudge.judge(
            text = last.text,
            persona = persona,
            isPrivate = true,
            selfSaidAgoSeconds = selfSaidAgoSeconds,
        )
        when (judgment.action) {
            NecessityJudge.Action.SILENT -> {
                step("② 该不该回：沉默（${judgment.reasons.joinToString("；")}）")
                return Outcome.Silent(judgment.reasons.joinToString("；"))
            }
            NecessityJudge.Action.WAIT -> {
                step("② 该不该回：想回但先等（${judgment.reasons.joinToString("；")}）")
                return Outcome.Wait(
                    delayMs(baseMinutes = 12, now = now),
                    "想回但先等等：" + judgment.reasons.joinToString("；"),
                )
            }
            NecessityJudge.Action.REPLY -> step(
                "② 该不该回：该回（${"%.2f".format(judgment.score)}）" +
                    judgment.reasons.take(3).joinToString(" ")
            )
        }

        // ---- 3. 累不累 ----
        val tired = energy.isTired(now)
        val energyNow = "%.2f".format(energy.current(now))
        if (tired && respectEnergy) {
            step("③ 精力：$energyNow 偏低，压一压")
            return Outcome.Wait(
                delayMs(baseMinutes = 8, now = now),
                "精力低（$energyNow），压一压再回",
            )
        }
        if (tired) {
            // 手动放行：说出来而不是悄悄放过，用户才知道"如果这是自动的，它本来会等"
            step("③ 精力：$energyNow 偏低，但你是手动点的，不拦")
        } else {
            step("③ 精力：$energyNow 正常")
        }

        // ---- 4. 生成（内部已过输入边界与禁忌词） ----
        val result = engine.generate(turns)
        val draftRes = when (result) {
            is ReplyEngine.Result.Blocked -> {
                step("④ 生成：拦下 — ${result.reason}")
                return Outcome.Silent(result.reason)
            }
            is ReplyEngine.Result.Failed -> {
                step("④ 生成：失败 — ${result.reason.take(60)}")
                return Outcome.Error(result.reason)
            }
            is ReplyEngine.Result.Draft -> {
                step("④ 生成：第 ${result.attempts} 次成稿")
                result
            }
        }

        // 生成过一次就算消耗：无论后面填不填，模型调用和判断都发生了
        pacer.recordAction(now)
        energy.consume(now)

        // ---- 5. 这句话说错代价大不大 ----
        val draft = draftRes.text
        val risk = DraftRisk.classify(draft)
        return if (risk.fillable) {
            step("⑤ 风险：${risk.level} → 可填")
            Outcome.Fill(
                draft, risk,
                attempts = draftRes.attempts,
                inputTokens = draftRes.inputTokens,
                outputTokens = draftRes.outputTokens,
                estimatedInputTokens = draftRes.estimatedInputTokens,
            )
        } else {
            step("⑤ 风险：${risk.level}（${risk.hits.joinToString("、")}）→ 不填")
            Outcome.Hold(
                draft,
                risk,
                "涉及" + risk.hits.joinToString("、") + "，这类话不替你填，你看着改",
                inputTokens = draftRes.inputTokens,
                outputTokens = draftRes.outputTokens,
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
