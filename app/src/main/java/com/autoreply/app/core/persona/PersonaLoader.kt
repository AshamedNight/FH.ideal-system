package com.autoreply.app.core.persona

import android.content.Context
import com.autoreply.app.core.AppConfig

/**
 * 人设加载。三级回退，每一级都比上一级更"兜底"：
 *
 *   1. 设置页粘贴的 JSON  —— 用户主动覆盖，优先级最高（改完立刻生效，不用重新打包）
 *   2. assets/persona.json —— 内置示例，仓库里就能改
 *   3. 设置页的自由文本    —— 最早的方式，只剩一段 identity
 *
 * 无论走哪一级，[Persona.boundaries] 和 [Persona.tabooWords] 为空时都会补上默认值 ——
 * 这两项空了等于护栏没了，而不能指望用户记得填。
 */
object PersonaLoader {

    /** 人设 + 它从哪来的。来源要能看见，否则"改了没生效"永远查不出原因。 */
    data class Loaded(val persona: Persona, val source: String)

    fun load(context: Context, config: AppConfig): Persona = resolve(context, config).persona

    /** 只算"外层那份"（设置页 JSON → 内置 → 自由文本），不含本地编辑。 */
    fun base(context: Context, config: AppConfig): Persona = resolveBuiltin(context, config).persona

    fun resolve(context: Context, config: AppConfig): Loaded {
        val builtin = resolveBuiltin(context, config)

        // 用户自己在 App 里改的「我是谁 / 问答样例 / 行为准则 / 情况」覆盖内置的那几项。
        // 其余（语气、边界、禁止词）保持原样 ——
        // 把整个 persona 交给用户编辑，改坏了他也看不出哪里坏了。
        val ov = PersonaStore.load(context) ?: return builtin
        val merged = builtin.persona.copy(
            // 身份必须跟着预设走：多个号之间最容易露馅的就是"我是谁"。
            identity = ov.identity ?: builtin.persona.identity,
            fewShot = ov.fewShot ?: builtin.persona.fewShot,
            rules = ov.rules ?: builtin.persona.rules,
            knowledge = ov.knowledge ?: builtin.persona.knowledge,
        )
        return Loaded(merged, builtin.source + " + 本地编辑")
    }

    private fun resolveBuiltin(context: Context, config: AppConfig): Loaded {
        val pasted = config.personaJson
        if (pasted.isNotEmpty()) {
            Persona.fromJson(pasted)?.let {
                // 这份 JSON 现在由预设维护（导入 / 「高级」弹窗），设置页早已不放这个框了。
                // 标签写"当前预设"，用户在首页「试填」里看到它就知道切没切成功。
                return Loaded(withGuardrails(it), "当前预设的人设 JSON")
            }
        }

        try {
            val builtin = context.assets.open("persona.json")
                .bufferedReader().use { it.readText() }
            Persona.fromJson(builtin)?.let {
                return Loaded(withGuardrails(it), "内置 assets/persona.json")
            }
        } catch (_: Exception) {
            // assets 缺失/损坏都不算致命，继续往下回退
        }

        // 旧版本只有一个自由文本框，这里保留是为了不让老用户的人设凭空消失。
        return Loaded(withGuardrails(Persona.fromFreeText(config.persona)), "旧版自由文本")
    }

    private fun withGuardrails(p: Persona): Persona = p.copy(
        boundaries = p.boundaries.ifEmpty { Persona.DEFAULT_BOUNDARIES },
        tabooWords = p.tabooWords.ifEmpty { Persona.DEFAULT_TABOO },
    )
}
