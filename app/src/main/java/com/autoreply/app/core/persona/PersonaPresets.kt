package com.autoreply.app.core.persona

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.autoreply.app.core.AppConfig

/**
 * 多份人设，同一时间只有一份生效。
 *
 * ## 一份预设是什么
 *
 * 「人设」在两个地方：设置页那段 `persona_json`（身份 / 语气 / 禁忌词，
 * 留空则用 APK 内置的 assets），以及 [PersonaStore] 那三项覆盖
 * （情况 / 样例 / 准则）。只切后者的话，换的只是"怎么说话"，"我是谁"没变 ——
 * 多个号之间最容易露馅的恰恰是身份（名字、年龄、职业）。
 *
 * 所以一份预设**两份都存**：
 * ```
 * { "personaJson": "...", "override": { "few_shot": [...], ... } }
 * ```
 * 切换 = 把这两份一起写回去。生成链路不用改任何一行 —— 它本来就只读这两个地方。
 *
 * ## 有一条硬约束
 *
 * **同时只能有一个 active**。这不是靠界面上的单选框保证的（界面只是它的投影），
 * 而是靠 JSON 里单个 `active` 字段：重进页面时按它重新画。
 */
object PersonaPresets {

    private const val FILE_NAME = "persona_presets.json"
    private const val DEFAULT_NAME = "默认人设"

    data class Preset(val id: String, val name: String)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun bodyOf(personaJson: String, override: JSONObject): JSONObject =
        JSONObject().put("personaJson", personaJson).put("override", override)

    private fun bodyFrom(item: JSONObject): JSONObject =
        item.optJSONObject("body") ?: JSONObject()

    /**
     * 读档案柜；不存在或读坏了就**就地建一份**。
     *
     * 建的时候把当前生效的两个人设来源原样包进"默认人设"——
     * 老用户升级上来，生效内容一个字都不变，只是多了一个条目。
     */
    private fun load(context: Context): JSONObject {
        val f = file(context)
        if (f.isFile) {
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (o != null && (o.optJSONArray("presets")?.length() ?: 0) > 0) return o
        }
        val id = newId(System.currentTimeMillis())
        val o = JSONObject()
            .put("active", id)
            .put(
                "presets",
                JSONArray().put(
                    JSONObject()
                        .put("id", id)
                        .put("name", DEFAULT_NAME)
                        .put(
                            "body",
                            bodyOf(
                                AppConfig(context).personaJson,
                                PersonaStore.readRaw(context) ?: JSONObject(),
                            )
                        )
                )
            )
        persist(context, o)
        return o
    }

    private fun persist(context: Context, o: JSONObject): Boolean = try {
        file(context).writeText(o.toString(2))
        true
    } catch (_: Exception) {
        false
    }

    private fun newId(seed: Long): String =
        "p" + (if (seed > 0) seed else System.currentTimeMillis())

    private fun findById(arr: JSONArray, id: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id", "") == id) return item
        }
        return null
    }

    fun list(context: Context): List<Preset> {
        val arr = load(context).optJSONArray("presets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "")
            if (id.isEmpty()) null
            else Preset(id, item.optString("name", "").ifEmpty { "未命名" })
        }
    }

    /** 当前生效的那份。读不出来就返回第一份 —— 界面永远要有东西可以选中。 */
    fun activeId(context: Context): String {
        val o = load(context)
        val id = o.optString("active", "")
        val arr = o.optJSONArray("presets") ?: return ""
        if (id.isNotEmpty() && findById(arr, id) != null) return id
        return arr.optJSONObject(0)?.optString("id", "").orEmpty()
    }

    /**
     * 切换：把目标预设的两个人设来源一起写回去。
     *
     * 调用方必须先把当前页的改动落盘（见 PersonaActivity 的 persist），
     * 否则"切换"看起来像把刚改的东西吞掉了。
     */
    fun activate(context: Context, id: String): Boolean {
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return false
        val target = findById(arr, id) ?: return false
        val body = bodyFrom(target)

        if (!PersonaStore.writeRaw(context, body.optJSONObject("override") ?: JSONObject())) {
            return false
        }
        AppConfig(context).personaJson = body.optString("personaJson", "")
        o.put("active", id)
        return persist(context, o)
    }

    /**
     * 把当前的两个人设来源存回 active 那一份。
     *
     * 每次保存话术管理页都要调 —— 不调的话档案柜里留的一直是切换那一刻的快照，
     * 下次切回来会把中间的改动全部还原。
     */
    fun snapshotActive(context: Context): Boolean {
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return false
        val target = findById(arr, activeId(context)) ?: return false
        target.put(
            "body",
            bodyOf(
                AppConfig(context).personaJson,
                PersonaStore.readRaw(context) ?: JSONObject(),
            )
        )
        return persist(context, o)
    }

    /**
     * 新建一份，内容是**当前生效这份的副本**。
     *
     * 复制而不是空白：多数场景是"在这份基础上改两句"，
     * 从零开始要重填十来条情况。要空白就把内容删掉。
     */
    fun createFromCurrent(context: Context, name: String): String? {
        snapshotActive(context)
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return null
        val body = findById(arr, activeId(context))?.let { bodyFrom(it) } ?: JSONObject()
        // 同一毫秒连建两次会撞 id，用现有条数错开
        val id = newId(System.currentTimeMillis() + arr.length())
        if (findById(arr, id) != null) return null
        arr.put(
            JSONObject()
                .put("id", id)
                .put("name", name.trim().ifEmpty { "未命名" })
                .put("body", body)
        )
        o.put("active", id)
        return if (persist(context, o)) id else null
    }

    /**
     * 直接插一份预设（内容由调用方给）。
     *
     * 和 [createFromCurrent] 的区别：那个复制"当前这份"，这个接受任意内容 ——
     * 从文件导入、把内置人设装进来，都走这里。
     *
     * [activate] = false 时**不动当前生效的人设**（内置那几份就靠这个，
     * 悄悄把用户的号换掉是最不该做的事）。重名自动加「（2）」，不覆盖已有预设。
     */
    fun insert(
        context: Context,
        name: String,
        personaJson: String,
        override: JSONObject,
        activate: Boolean,
    ): String? {
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return null
        val base = name.trim().ifEmpty { "未命名" }
        val used = list(context).map { it.name }.toMutableSet()
        var finalName = base
        var k = 2
        while (used.contains(finalName)) {
            finalName = "$base（$k）"
            k++
        }
        val id = newId(System.currentTimeMillis() + arr.length())
        if (findById(arr, id) != null) return null
        arr.put(
            JSONObject()
                .put("id", id)
                .put("name", finalName)
                .put("body", bodyOf(personaJson, override))
        )
        if (activate) o.put("active", id)
        if (!persist(context, o)) return null
        if (activate) {
            // 两份一起写：身份/语气在 personaJson，样例/准则/情况在 override
            if (!PersonaStore.writeRaw(context, override)) return null
            AppConfig(context).personaJson = personaJson
        }
        return id
    }

    /**
     * 解析一份"人设 JSON"文本。
     *
     * 支持两种形态：
     *   ① 完整 persona —— name / identity / tone / knowledge / few_shot / rules；
     *   ② 预设 body —— `{ personaJson, override }`，也就是从预设里搬出来的那份。
     * 两种都认，免得"导出再导入"走样。
     *
     * 返回 null 表示里面没有任何可用内容 —— 宁可让人看到"这个文件不行"，
     * 也不要装进去一份空人设，那会让回复变成没头没脑的通用句子。
     */
    fun parsePersonaText(raw: String): JSONObject? {
        val text = raw.removePrefix("\uFEFF").trim()
        if (!text.startsWith("{")) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val inner = if (o.has("personaJson")) {
            runCatching { JSONObject(o.optString("personaJson")) }.getOrNull() ?: return null
        } else {
            o
        }
        // 去掉下划线开头的说明字段（_note / _README）：它们只对人有用，
        // 留着会跟着 personaJson 一起进提示词估算
        val keys = inner.keys()
        val drop = ArrayList<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("_")) drop.add(key)
        }
        drop.forEach { inner.remove(it) }

        val hasIdentity = inner.optString("identity", "").trim().isNotEmpty()
        val shots = inner.optJSONArray("few_shot")?.length() ?: 0
        val knowledge = inner.optJSONArray("knowledge")?.length() ?: 0
        val rules = inner.optJSONArray("rules")?.length() ?: 0
        if (!hasIdentity && shots == 0 && knowledge == 0 && rules == 0) return null
        return inner
    }

    /**
     * 把 assets 的 personas 目录里那几份内置人设装成预设。
     *
     * 只装一次（root 上的 seeded 标记），而且**不激活** ——
     * 装完用户的号还是原来那个，只是列表里多出可以点的一份。
     * 这就是"装完就有小陈大夫可直接选"的实现方式。
     */
    fun seedBundled(context: Context): Int {
        if (load(context).optBoolean("seeded", false)) return 0
        val files = runCatching { context.assets.list("personas") }.getOrNull().orEmpty()
        var added = 0
        for (f in files) {
            if (!f.endsWith(".json")) continue
            val text = runCatching {
                context.assets.open("personas/$f").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val persona = parsePersonaText(text) ?: continue
            val name = persona.optString("name", "").trim().ifEmpty { f.removeSuffix(".json") }
            if (list(context).any { it.name == name }) continue
            if (insert(context, name, persona.toString(2), JSONObject(), activate = false) != null) {
                added++
            }
        }
        val fresh = load(context)
        fresh.put("seeded", true)
        persist(context, fresh)
        return added
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return false
        val target = findById(arr, id) ?: return false
        target.put("name", name.trim().ifEmpty { "未命名" })
        return persist(context, o)
    }

    /**
     * 删除。**最后一份不能删** —— 没有预设就等于没有生效人设。
     * 删的正好是 active 时，自动切到剩下的第一份（并把内容写回去）。
     */
    fun remove(context: Context, id: String): Boolean {
        val o = load(context)
        val arr = o.optJSONArray("presets") ?: return false
        if (arr.length() <= 1) return false
        if (findById(arr, id) == null) return false

        val rest = JSONArray()
        var fallback: JSONObject? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id", "") == id) continue
            if (fallback == null) fallback = item
            rest.put(item)
        }
        o.put("presets", rest)
        val fb = fallback
        if (fb != null && o.optString("active", "") == id) {
            val body = bodyFrom(fb)
            PersonaStore.writeRaw(context, body.optJSONObject("override") ?: JSONObject())
            AppConfig(context).personaJson = body.optString("personaJson", "")
            o.put("active", fb.optString("id", ""))
        }
        return persist(context, o)
    }
}
