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

    fun load(context: Context, config: AppConfig): Persona {
        val pasted = config.personaJson
        if (pasted.isNotEmpty()) {
            Persona.fromJson(pasted)?.let { return withGuardrails(it) }
        }

        try {
            val builtin = context.assets.open("persona.json")
                .bufferedReader().use { it.readText() }
            Persona.fromJson(builtin)?.let { return withGuardrails(it) }
        } catch (_: Exception) {
            // assets 缺失/损坏都不算致命，继续往下回退
        }

        return withGuardrails(Persona.fromFreeText(config.persona))
    }

    private fun withGuardrails(p: Persona): Persona = p.copy(
        boundaries = p.boundaries.ifEmpty { Persona.DEFAULT_BOUNDARIES },
        tabooWords = p.tabooWords.ifEmpty { Persona.DEFAULT_TABOO },
    )
}
