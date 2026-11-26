package com.autoreply.app.core.persona

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户在 App 里自己改的「问答样例」和「行为准则」。
 *
 * ## 为什么单独存一份，而不是让人去改 assets/persona.json
 *
 * assets 是打包进 APK 的，改它要重新编译 —— 对使用者等于改不了。
 * 所以内置的那份当**默认值**，用户改的这份当**覆盖**，
 * 存到 filesDir 下一个 json 里，随时可改、可清空、可恢复默认。
 *
 * 只覆盖「我是谁 / 情况 / 样例 / 准则」四项，不动 tone / boundaries / 禁止词 ——
 * 那几项改坏了会让整个回复跑偏，而且用户很难自己看出来改坏了什么。
 *
 * 身份原来不在这份里，2026-11-26 挪了进来：人设预设搬的就是这个文件，
 * 身份留在外层的话，切预设换的只是"怎么说话"，"我是谁"没变。
 */
object PersonaStore {

    private const val FILE_NAME = "user_persona.json"

    /**
     * 用户的覆盖。字段为 null 表示"这一项没改过，用内置的"。
     *
     * 用 null 而不是空列表：空列表可能是用户真的删光了，
     * 也可能是没设置 —— 两者要区分开。
     */
    data class Override(
        val fewShot: List<FewShot>?,
        val rules: List<String>?,
        val knowledge: List<KnowledgeItem>?,
        /**
         * 「我是谁」。null = 没改过，用外层（设置页 JSON / 内置 assets）。
         *
         * 为什么要放进覆盖文件：人设预设只搬这一份。
         * 身份留在外层的话，切预设换的只是"怎么说话"，名字、年龄、职业没变 ——
         * 而多号之间最容易露馅的恰恰是这些（2026-11-26 用户实测反馈）。
         */
        val identity: String? = null,
    )

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun hasOverride(context: Context): Boolean = file(context).isFile

    fun load(context: Context): Override? {
        val f = file(context)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val shots = o.optJSONArray("few_shot")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val q = item.optString("q", "").trim()
                    val a = item.optString("a", "").trim()
                    if (q.isEmpty() || a.isEmpty()) null else FewShot(q, a)
                }
            }
            val rules = o.optJSONArray("rules")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().ifEmpty { null }
                }
            }
            // 关键词不单独让用户填：用它的话题名当关键词就够判断
            // 「对方是不是在问这件事」，多一个输入框只会多一处要维护的东西。
            val knowledge = o.optJSONArray("knowledge")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val topic = item.optString("topic", "").trim()
                    val answer = item.optString("answer", "").trim()
                    if (topic.isEmpty() || answer.isEmpty()) null
                    else KnowledgeItem(topic, listOf(topic), answer)
                }
            }
            // 空串 = 没覆盖（回到外层那份）。区分"改成空"和"没改过"没意义：
            // 一个没有人设的账号本来也不成立，所以清空输入框就当作回落。
            val identity = o.optString("identity", "").trim().ifEmpty { null }
            Override(shots, rules, knowledge, identity)
        } catch (_: Exception) {
            // 文件坏了就当没改过，不要让整个回复流程因为一个坏 json 起不来
            null
        }
    }

    /** @return 是否写成功。写不进去要让用户知道，不能默默失败。 */
    fun save(
        context: Context,
        fewShot: List<FewShot>,
        rules: List<String>,
        knowledge: List<KnowledgeItem> = emptyList(),
        /**
         * 「我是谁」。传空串 = 不覆盖，回到外层那份。
         * 调用方负责判断"用户到底改没改"（见 PersonaActivity.persist）。
         */
        identity: String = "",
    ): Boolean = try {
        val o = JSONObject()
        o.put("identity", identity.trim())
        o.put("few_shot", JSONArray().apply {
            fewShot.forEach {
                put(JSONObject().put("q", it.q).put("a", it.a))
            }
        })
        o.put("rules", JSONArray().apply {
            rules.forEach { put(it) }
        })
        o.put("knowledge", JSONArray().apply {
            knowledge.forEach {
                put(JSONObject().put("topic", it.topic).put("answer", it.answer))
            }
        })
        file(context).writeText(o.toString(2))
        true
    } catch (_: Exception) {
        false
    }

    /** 恢复内置默认：直接删掉覆盖文件。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * 原样读出覆盖内容。
     *
     * 给「人设预设」用：预设之间搬的是**整份**覆盖。
     * 解析成对象再序列化回去会丢掉将来新增的字段，原样搬最稳。
     */
    fun readRaw(context: Context): JSONObject? = try {
        val f = file(context)
        if (!f.isFile) null else JSONObject(f.readText())
    } catch (_: Exception) {
        null
    }

    /**
     * 原样写回。
     *
     * `{}` 是合法写法，表示"这份预设什么也没覆盖，全用内置默认"。
     * 不能靠删文件来表达：删了就分不清"这份预设是空的"和"根本没有预设"。
     */
    fun writeRaw(context: Context, body: JSONObject): Boolean = try {
        file(context).writeText(body.toString(2))
        true
    } catch (_: Exception) {
        false
    }
}
