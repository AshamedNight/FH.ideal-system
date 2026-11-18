package com.autoreply.app.core.persona

import org.json.JSONObject

/**
 * 语气参数。不是用来"调 prompt 形容词"的，是转成具体指令用的 ——
 * 写"正式度 30"比写"语气随意一点"可校准得多。
 */
data class Tone(
    /** 0=完全口语，100=书面公文 */
    val formal: Int = 25,
    /** 0=极短，100=长段落。会换算成字数上限 */
    val length: Int = 25,
    /** 0=不用表情符号 */
    val emoji: Int = 0,
    val catchphrases: List<String> = emptyList(),
)

data class KnowledgeItem(
    val topic: String,
    /** 命中这些关键词就认为对方在问这个话题 */
    val keys: List<String>,
    val answer: String,
)

data class FewShot(val q: String, val a: String)

/**
 * 人设。**结构化，不是一段 prompt。**
 *
 * 这么拆的理由：一段 prompt 没法校验、没法局部改、出错时不知道是哪句导致的。
 * 拆成字段之后，「不许提价格」是 [boundaries]，「回复里不能出现脏话」是 [tabooWords]，
 * 两者作用方向相反，混在一起等于既不知道该沉默也不知道该重写。
 */
data class Persona(
    val name: String,
    val identity: String,
    val tone: Tone,
    val knowledge: List<KnowledgeItem>,
    /** 输入侧：对方说的话命中这里 → 整条不回复（转人工） */
    val boundaries: List<String>,
    /** 输出侧：生成的草稿命中这里 → 丢弃重写 */
    val tabooWords: List<String>,
    val fewShot: List<FewShot>,
    /**
     * 行为准则：一句话一条，直接进提示词。
     *
     * 和 [boundaries] 方向不同 —— boundaries 是「对方说到这些就不回」，
     * 这里是「该用什么姿态接」。比如「对方说得不清楚就问一句，别猜」，
     * 既不是禁止也不是内容，而是一种做法。
     *
     * 单独成字段（而不是写死在代码里）是为了让用户在 App 里自己改，
     * 不用改代码重新打包。
     */
    val rules: List<String> = emptyList(),
) {

    /** 把语气参数换算成给模型的具体要求。 */
    fun styleRules(maxChars: Int): String = buildString {
        appendLine("说话要求：")
        appendLine("- 每条不超过 $maxChars 个字")
        when {
            tone.formal <= 20 -> appendLine("- 完全口语，像微信打字，可以有语气词")
            tone.formal <= 50 -> appendLine("- 口语为主，别用书面语和成语")
            else -> appendLine("- 用词克制、完整，不要网络用语")
        }
        appendLine("- 不用「首先/其次/综上」这类连接词，不分点罗列")
        appendLine("- 不用感叹号堆情绪，不重复对方刚说过的话")
        if (tone.emoji <= 10) appendLine("- 不用表情符号")
        if (tone.catchphrases.isNotEmpty()) {
            appendLine("- 偶尔可以用这些口头禅：" + tone.catchphrases.joinToString("、"))
        }
        append("- 不确定的事就说不知道，绝不编造")
    }

    companion object {

        /**
         * 从设置页的自由文本构造人设。
         *
         * 设置页目前只让用户写一段"你是谁、怎么说话"，其余字段走默认 ——
         * 先让链路跑起来，结构化编辑（知识库、少样本）等有真实使用反馈再加。
         */
        fun fromFreeText(text: String, name: String = ""): Persona {
            val trimmed = text.trim().ifEmpty { DEFAULT_IDENTITY }
            return Persona(
                name = name,
                identity = trimmed,
                tone = Tone(),
                knowledge = emptyList(),
                boundaries = DEFAULT_BOUNDARIES,
                tabooWords = DEFAULT_TABOO,
                fewShot = emptyList(),
                rules = emptyList(),
            )
        }

        /** 从 assets/persona.json 读取完整人设。解析失败返回 null，由调用方决定回退。 */
        fun fromJson(json: String): Persona? = try {
            val o = JSONObject(json)
            val t = o.optJSONObject("tone")
            Persona(
                name = o.optString("name", ""),
                identity = o.optString("identity", DEFAULT_IDENTITY),
                tone = Tone(
                    formal = t?.optInt("formal", 25) ?: 25,
                    length = t?.optInt("length", 25) ?: 25,
                    emoji = t?.optInt("emoji", 0) ?: 0,
                    catchphrases = t?.optJSONArray("catchphrases").toStringList(),
                ),
                knowledge = o.optJSONArray("knowledge").toKnowledge(),
                boundaries = o.optJSONArray("boundaries").toStringList(),
                tabooWords = o.optJSONArray("taboo_words").toStringList(),
                fewShot = o.optJSONArray("few_shot").toFewShot(),
                rules = o.optJSONArray("rules").toStringList(),
            )
        } catch (e: Exception) {
            null
        }

        private fun org.json.JSONArray?.toStringList(): List<String> {
            this ?: return emptyList()
            return (0 until length()).mapNotNull { i ->
                optString(i).takeIf { it.isNotEmpty() }
            }
        }

        private fun org.json.JSONArray?.toKnowledge(): List<KnowledgeItem> {
            this ?: return emptyList()
            return (0 until length()).mapNotNull { i ->
                val o = optJSONObject(i) ?: return@mapNotNull null
                KnowledgeItem(
                    topic = o.optString("topic", ""),
                    keys = o.optJSONArray("keys").toStringList(),
                    answer = o.optString("answer", ""),
                )
            }
        }

        private fun org.json.JSONArray?.toFewShot(): List<FewShot> {
            this ?: return emptyList()
            return (0 until length()).mapNotNull { i ->
                val o = optJSONObject(i) ?: return@mapNotNull null
                FewShot(o.optString("q", ""), o.optString("a", ""))
            }
        }

        private const val DEFAULT_IDENTITY = """你是一个真实的人在手机上聊天。

短句，一次只说一件事，不罗列。不用书面语。不说客套话。"""

        /**
         * 输入侧默认边界。对方提到这些话题就整条不回复。
         *
         * 这不是"禁止回复"，是"交给人工" —— 涉及钱、法律、人身安全的对话，
         * 自动化插一句的代价远大于漏回一条。
         */
        val DEFAULT_BOUNDARIES = listOf(
            "借钱", "转账", "汇款", "打款", "收款码", "银行卡", "验证码",
            "报警", "起诉", "律师函",
            "自杀", "轻生", "不想活",
        )

        /** 输出侧默认禁忌词。生成的草稿命中就丢弃重写。 */
        val DEFAULT_TABOO = listOf(
            // 辱骂
            "傻逼", "傻B", "滚", "去死", "废物", "垃圾", "脑残", "贱",
            // 色情
            "约炮", "开房", "上床", "裸", "性交", "口交", "包养",
            // 自曝是机器
            "作为AI", "作为人工智能", "我是语言模型", "AI助手", "机器人",
            // 引号与模板残留
            "好的，以下", "以下是",
        )
    }
}
