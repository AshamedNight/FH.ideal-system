package com.autoreply.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoreply.app.R
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.persona.FewShot
import com.autoreply.app.core.persona.KnowledgeItem
import com.autoreply.app.core.persona.PersonaLoader
import com.autoreply.app.core.persona.PersonaPresets
import com.autoreply.app.core.persona.PersonaStore
import org.json.JSONObject

/**
 * 话术管理：把「我是谁 / 我的情况 / 问答样例 / 行为准则」放在一页里改，
 * 四样一起跟着「人设预设」走，切换预设 = 换一整套人。
 *
 * ## 现在这一页就是人设的全部入口
 *
 * 2026-11-26 把设置页那两个大输入框（自由文本 + 人设 JSON）收掉了：它们改的和这里是
 * 同一份数据，分两处只会让人以为"改这边那边会变"。语气 / 边界 / 禁忌词留在
 * 「高级：人设 JSON」弹窗里 —— 偶尔改一次，不值得常驻半屏。
 *
 * ## 数据怎么存
 *
 * 内置那份在 assets/persona.json 当默认值，这里的覆盖存到 filesDir 下单独的
 * json（[PersonaStore]）。"恢复内置默认"就是删掉那个文件。
 */
class PersonaActivity : Activity() {

    private lateinit var shotList: LinearLayout
    private lateinit var ruleList: LinearLayout
    private lateinit var knowledgeList: LinearLayout
    private lateinit var presetList: LinearLayout
    private lateinit var identityInput: EditText
    private lateinit var titleView: TextView

    private class ShotRow(val q: EditText, val a: EditText, val container: LinearLayout)
    private class RuleRow(val text: EditText, val container: LinearLayout)

    private class KnowledgeRow(val topic: EditText, val answer: EditText, val container: LinearLayout)

    private val shots = ArrayList<ShotRow>()
    private val rules = ArrayList<RuleRow>()
    private val knowledge = ArrayList<KnowledgeRow>()

    /** 段落编号，和设置页一样自动递增。 */
    private var sectionIndex = 0

    /** 选人设文件（SAF）。数字随便取，只要和别的请求码不撞。 */
    private val reqImport = 7011

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }

        // 顶栏与首页、设置页共用同一套骨架
        content.addView(mono("PERSONA · 话术管理"))
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8) }
            setBackgroundColor(getColor(R.color.ink))
        })
        titleView = title("话术管理")
        content.addView(titleView)
        content.addView(
            note(
                "这里改的东西立刻生效（下次生成就用），不用重新打包。\n" +
                    "「我是谁 / 我的情况 / 问答样例 / 行为准则」都是一份预设的一部分 —— " +
                    "换预设，这四样一起换。语气参数和禁忌词在下面的「高级」里，同样跟着预设走。"
            )
        )

        // ---- 人设预设 ----
        //
        // 放在最上面：切换预设是"换一个人"，比调某一条样例更靠前。
        // 同一时间只有一份生效 —— 界面上那个实心圆点画的就是"当前生效的那份"，
        // 真相存在档案柜里（PersonaPresets 的 active 字段），不是画在这里。
        content.addView(section("人设预设"))
        content.addView(
            note(
                "同一时间只有一份生效。切换会把那一份的「我是谁 / 情况 / 样例 / 准则」整份换上来 ——\n" +
                    "多个号、或者同一个人设换种性格，靠它切，不用每次手改。\n" +
                    "切换前会先保存当前这一页的改动，所以改完直接点另一份就行。"
            )
        )
        presetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(presetList)
        content.addView(ghostButton("＋ 新建（复制当前这份）") { newPreset() })
        // 导入：一份 JSON 直接变成一份可用的预设，不用再回设置页粘。
        // 走 SAF，不申请存储权限 —— 和「导出聊天记录」同一套做法。
        content.addView(ghostButton("＋ 从文件导入 JSON") { pickPersonaFile() })

        // ---- 我是谁 ----
        //
        // 放在预设下面、情况上面：换预设时第一件被换掉的就是"我是谁"。
        // 这一项原来只能去设置页粘 JSON，切预设换不掉 —— 而名字、年龄、职业
        // 恰恰是多个号之间最容易露馅的地方。
        content.addView(section("我是谁（跟着预设走）"))
        content.addView(
            note(
                "这段会原样进提示词最前面：叫什么、多大、哪人、在做什么、什么姿态说话。\n" +
                    "别写成业务介绍 —— 自我介绍和主动推销是两件事，后者更招人烦。\n" +
                    "填了以这里为准；清空并保存 = 回到这份预设的 JSON（或内置那份）。"
            )
        )
        identityInput = EditText(this).apply {
            hint = "你叫……，多大，哪人，在做什么，说话什么风格"
            textSize = 13f
            gravity = Gravity.TOP
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink_faint))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // 12 行：导入的人设常有三段（身份 / 性格 / 说话方式），
            // 8 行会把最后一段藏起来 —— 看着像"没导全"，实际是全的。
            maxLines = 12
            minHeight = dp(112)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        content.addView(identityInput)
        // 语气（正式度 / 长度 / 表情 / 口头禅）、边界、禁忌词只存在 personaJson 里，
        // 没有单独输入框 —— 用这个弹窗改，避免又出现一个占半屏的大框。
        content.addView(ghostButton("高级：语气 / 边界 / 禁忌（人设 JSON）") { editPersonaJson() })

        // ---- 我的情况 ----
        content.addView(section("我的情况（模型能说的事实）"))
        content.addView(
            note(
                "模型只能用这里写的事实回话。这里太少，问什么它都只能绕着那几句 —— " +
                    "也就是「回复模板化」「总是拿同一个借口敷衍」。\n" +
                    "建议 8~12 条：工作、住哪、作息、爱吃什么、养不养宠物、最近在追什么、" +
                    "周末一般干嘛、家里人…\n" +
                    "写你真实的情况就行，不用编 —— 编的聊深了会露。"
            )
        )
        knowledgeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(knowledgeList)
        content.addView(ghostButton("＋ 加一条情况") { addKnowledgeRow("", "") })

        // ---- 问答样例 ----
        content.addView(section("问答样例"))
        content.addView(
            note(
                "「对方说」配「我回」。同一句话可以有多条不同答法 —— " +
                    "模型会按语境挑一种，比一条死答案自然得多。\n" +
                    "样例管的是语气和内容，别写太长，一两句就够。"
            )
        )
        shotList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(shotList)
        content.addView(ghostButton("＋ 加一条问答") { addShotRow("", "") })

        // ---- 行为准则 ----
        content.addView(section("行为准则"))
        content.addView(
            note(
                "一句话一条，直接进提示词。写的是「该怎么做」，不是「不能说什么」" +
                    "（禁忌词在「高级：人设 JSON」里）。\n" +
                    "比如「对方说得不清楚就问一句，别猜」——这种姿态靠样例很难教会，写一条最有效。"
            )
        )
        ruleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ruleList)
        content.addView(ghostButton("＋ 加一条准则") { addRuleRow("") })

        // ---- 底部操作 ----
        content.addView(space(12))
        // 顺序：破坏性的「恢复默认」在上面，落盘的「保存」在最后（拇指最容易够到）
        content.addView(ghostButton("恢复内置默认") { resetToDefault() })
        content.addView(primary("保存") { save() })
        content.addView(
            note(
                "「恢复内置默认」会删掉你所有改动，回到 APK 里带的那份。\n" +
                    "保存后回到首页点「生成并填入」，能看提示词里有没有你新加的内容。"
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.paper))
            addView(content)
        })

        loadCurrent()
        // 把 assets/personas 里的内置人设装成预设。只装一次，而且不激活 ——
        // 装完列表里多几份可以点的，当前是谁还是谁。
        PersonaPresets.seedBundled(this)
        refreshPresets()
    }

    // ---------------------------------------------------------------- 数据

    private fun loadCurrent() {
        val persona = PersonaLoader.resolve(this, AppConfig(this)).persona

        // 显示的是**当前生效**的身份（可能来自覆盖、设置页 JSON 或内置）。
        // 保存时会和"外层那份"比对，一样就不写覆盖 —— 见 persist。
        identityInput.setText(persona.identity)

        knowledge.clear()
        knowledgeList.removeAllViews()
        persona.knowledge.forEach { addKnowledgeRow(it.topic, it.answer) }

        shots.clear()
        shotList.removeAllViews()
        persona.fewShot.forEach { addShotRow(it.q, it.a) }
        if (persona.fewShot.isEmpty()) addShotRow("", "")

        rules.clear()
        ruleList.removeAllViews()
        persona.rules.forEach { addRuleRow(it) }

        titleView.text = "话术管理（${persona.knowledge.size} 条情况 / ${persona.fewShot.size} 条样例 / ${persona.rules.size} 条准则）"
    }

    private fun save() {
        persist(exitAfter = true)
    }

    /**
     * 把这一页写盘。
     *
     * @param exitAfter true 表示是用户点「保存」；false 表示是"切换预设 / 新建"之前的顺手保存 ——
     *   那种时候不该弹"已保存"然后关页面，否则切个预设要闪两下。
     * @return 是否写成功。失败时调用方要**停下来**，不能接着切预设。
     */
    private fun persist(exitAfter: Boolean): Boolean {
        val fewShot = shots.mapNotNull {
            val q = it.q.text.toString().trim()
            val a = it.a.text.toString().trim()
            if (q.isEmpty() || a.isEmpty()) null else FewShot(q, a)
        }
        val ruleTexts = rules.mapNotNull {
            it.text.text.toString().trim().ifEmpty { null }
        }
        val identityText = identityInput.text.toString().trim()

        // 三样全空 = 等于把这个人清空了，拦一下。
        //
        // 原来只看到「样例 + 准则」，于是 **只有身份的人设（导入的 JSON 常见）会被判成空**：
        // 点保存失败、点另一个预设也失败 —— 表现就是"卡在这一份上，换不走"。
        // 身份也是人设的一部分，填了就说明这份是有效的。
        if (fewShot.isEmpty() && ruleTexts.isEmpty() && identityText.isEmpty()) {
            Toast.makeText(this, "身份、样例、准则都是空的，至少要留一样", Toast.LENGTH_LONG).show()
            return false
        }

        val items = knowledge.mapNotNull {
            val topic = it.topic.text.toString().trim()
            val answer = it.answer.text.toString().trim()
            if (topic.isEmpty() || answer.isEmpty()) null else KnowledgeItem(topic, listOf(topic), answer)
        }

        // 身份：只有**和外面不一样**才写进覆盖文件。
        //
        // 不比的话，第一次保存就把外层 JSON 的身份固化成本地覆盖，
        // 之后改设置页那份再也不生效 —— 用户会以为"设置页坏了"。
        // 清空输入框 = 回到外层那份（空串在 Override 里就是"没覆盖"）。
        val baseIdentity = PersonaLoader.base(this, AppConfig(this)).identity.trim()
        val identityOverride = if (identityText == baseIdentity) "" else identityText

        val ok = PersonaStore.save(this, fewShot, ruleTexts, items, identityOverride)
        if (!ok) {
            Toast.makeText(this, "保存失败：写不进 filesDir", Toast.LENGTH_LONG).show()
            return false
        }
        // 存进"当前预设"。不存的话档案柜里留的还是切换那一刻的快照，
        // 下次切回来会把这几轮改动全部还原。
        PersonaPresets.snapshotActive(this)

        if (exitAfter) {
            Toast.makeText(
                this,
                "已保存（${items.size} 条情况 / ${fewShot.size} 条样例 / ${ruleTexts.size} 条准则）",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
        return true
    }

    private fun resetToDefault() {
        PersonaStore.clear(this)
        // 当前预设也跟着清空：否则下次切走再切回来，又会把改动"恢复"出来
        PersonaPresets.snapshotActive(this)
        Toast.makeText(this, "已恢复内置默认", Toast.LENGTH_SHORT).show()
        loadCurrent()
    }

    // ---------------------------------------------------------------- 人设预设

    /**
     * 重画预设列表。
     *
     * 用「● / ○」而不是 RadioButton：RadioButton 在点击时会自己翻转选中态，
     * 而我们希望"点当前生效那份"什么也不发生 —— 那一下翻转反倒会让界面和真相不一致。
     * 真相只有一个地方：PersonaPresets 里的 active 字段。
     */
    private fun refreshPresets() {
        presetList.removeAllViews()
        val list = PersonaPresets.list(this)
        val active = PersonaPresets.activeId(this)
        list.forEach { p ->
            val on = p.id == active
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = (if (on) "● " else "○ ") + p.name
                textSize = 13f
                setTextColor(getColor(if (on) R.color.ink else R.color.ink_soft))
                typeface = if (on) {
                    android.graphics.Typeface.create(
                        "sans-serif-medium", android.graphics.Typeface.NORMAL
                    )
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { switchTo(p) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(inlineLink("重命名") { renamePreset(p) })
            row.addView(inlineLink("删除") { deletePreset(p) })
            presetList.addView(row)
        }
    }

    private fun switchTo(p: PersonaPresets.Preset) {
        if (p.id == PersonaPresets.activeId(this)) {
            refreshPresets()
            return
        }
        // 先落盘再切：不然"我刚改的东西"会在切换瞬间消失
        if (!persist(exitAfter = false)) return
        if (!PersonaPresets.activate(this, p.id)) {
            Toast.makeText(this, "切换失败：写不进 filesDir", Toast.LENGTH_LONG).show()
            return
        }
        // 重画整页最省事：样例/准则/情况三块都要换成另一份，逐个刷新容易漏
        recreate()
    }

    private fun newPreset() {
        val list = PersonaPresets.list(this)
        val input = EditText(this).apply {
            setText("人设 ${list.size + 1}")
            textSize = 14f
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("新建人设预设")
            .setMessage("内容是当前这份的副本，建好后再改。")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                if (!persist(exitAfter = false)) return@setPositiveButton
                val name = input.text.toString().trim().ifEmpty { "未命名" }
                if (PersonaPresets.createFromCurrent(this, name) == null) {
                    Toast.makeText(this, "新建失败：写不进 filesDir", Toast.LENGTH_LONG).show()
                } else {
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 选一份人设 JSON。
     *
     * 用 ACTION_OPEN_DOCUMENT（SAF）：用户自己选文件，不给存储权限 ——
     * 和「导出聊天记录」是同一套做法。mime 只做优先提示不做硬过滤，
     * 手机上的文件管理器对 .json 的标注很随意，硬过滤常常让人选不到自己的文件。
     */
    private fun pickPersonaFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "text/*"))
        }
        if (runCatching { startActivityForResult(intent, reqImport) }.isFailure) {
            Toast.makeText(this, "这台设备没有文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "读不到文件内容（可能没给访问权限）", Toast.LENGTH_LONG).show()
            return
        }
        // 人设是纯文本，超过 200KB 基本是选错文件；先拦，别把主线程读死
        if (text.length > 200_000) {
            Toast.makeText(this, "文件太大（>200KB），这不像人设 JSON", Toast.LENGTH_LONG).show()
            return
        }
        val persona = PersonaPresets.parsePersonaText(text)
        if (persona == null) {
            Toast.makeText(
                this,
                "这份文件里没有可用人设：identity / few_shot / knowledge / rules 至少要有一样",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        // 先把这一页的改动存回当前预设。**不看返回值**：导入是整份替换，
        // 真存不下去的情况只有"整页是空的"，那种内容本来也没什么可保的；
        // 拦在这里只会让人看到"选了文件却什么都没发生"。
        persist(exitAfter = false)

        val name = persona.optString("name", "").trim().ifEmpty { "导入的人设" }
        // 覆盖层清空：这份 JSON 自己带着样例 / 准则 / 情况，留着旧的会串味
        val id = PersonaPresets.insert(this, name, persona.toString(2), JSONObject(), activate = true)
        if (id == null) {
            Toast.makeText(this, "导入失败：写不进 filesDir", Toast.LENGTH_LONG).show()
            return
        }
        // 把条数一起报出来：这是"导入成功、内容进对地方"最直接的证据 ——
        // 光一句"已导入"，用户还得自己去翻页面数条目才敢信。
        val kn = persona.optJSONArray("knowledge")?.length() ?: 0
        val sh = persona.optJSONArray("few_shot")?.length() ?: 0
        val ru = persona.optJSONArray("rules")?.length() ?: 0
        Toast.makeText(
            this,
            "已存成预设「$name」：$kn 条情况 / $sh 条样例 / $ru 条准则，现在是当前人设",
            Toast.LENGTH_LONG,
        ).show()
        recreate()
    }

    /**
     * 编辑「当前预设」的整份人设 JSON。
     *
     * 为什么还要留这个入口：语气（formal / length / emoji / 口头禅）、边界、禁忌词
     * 在 personaJson 里，本页没有为它们各做一个控件 —— 但也不能因为设置页隐藏了
     * 那个框就没有入口。用弹窗而不是常驻大框：这些是偶尔改一次的东西。
     *
     * 校验只要求"是合法 JSON"：允许只写 tone（比如只想换口头禅），
     * 不像导入那样强制要有 identity / 样例。
     */
    private fun editPersonaJson() {
        val et = EditText(this).apply {
            setText(AppConfig(this@PersonaActivity).personaJson)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            maxLines = 16
            minHeight = dp(320)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("人设 JSON（当前预设）")
            .setMessage("留空 = 回到内置那份。身份 / 情况 / 样例 / 准则请用上面的输入框，这里重复写会被上面的覆盖。")
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("保存") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty() && runCatching { JSONObject(text) }.isFailure) {
                    Toast.makeText(this, "这段不是合法 JSON，没有保存", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                AppConfig(this).personaJson = text
                // 当前预设也跟着更新，否则切走再切回来会还原成旧的那份
                persist(exitAfter = false)
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePreset(p: PersonaPresets.Preset) {
        val input = EditText(this).apply {
            setText(p.name)
            textSize = 14f
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "未命名" }
                if (PersonaPresets.rename(this, p.id, name)) refreshPresets()
                else Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePreset(p: PersonaPresets.Preset) {
        if (PersonaPresets.list(this).size <= 1) {
            Toast.makeText(this, "至少保留一份人设", Toast.LENGTH_SHORT).show()
            return
        }
        val wasActive = p.id == PersonaPresets.activeId(this)
        AlertDialog.Builder(this)
            .setTitle("删除「${p.name}」")
            .setMessage(
                if (wasActive) "它是当前生效的那份，删掉后会自动切到另一份。"
                else "删掉后不可恢复。"
            )
            .setPositiveButton("删除") { _, _ ->
                if (!PersonaPresets.remove(this, p.id)) {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                } else if (wasActive) {
                    // 生效内容变了，整页得重读
                    recreate()
                } else {
                    refreshPresets()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 行

    private fun addShotRow(q: String, a: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val qEt = rowInput(q, "对方说")
        val aEt = rowInput(a, "我回")
        val del = deleteButton {
            shots.firstOrNull { it.q === qEt }?.let { shots.remove(it) }
            shotList.removeView(row)
        }
        row.addView(qEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(aEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        row.addView(del, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        shotList.addView(row)
        shots.add(ShotRow(qEt, aEt, row))
    }

    private fun addKnowledgeRow(topic: String, answer: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val topicEt = rowInput(topic, "哪方面（工作/住哪/爱吃）")
        val answerEt = rowInput(answer, "具体怎么说")
        val del = deleteButton {
            knowledge.firstOrNull { it.topic === topicEt }?.let { knowledge.remove(it) }
            knowledgeList.removeView(row)
        }
        row.addView(topicEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(answerEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginStart = dp(6) })
        row.addView(del, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        knowledgeList.addView(row)
        knowledge.add(KnowledgeRow(topicEt, answerEt, row))
    }

    private fun addRuleRow(rule: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val et = rowInput(rule, "一句话一条", single = false)
        val del = deleteButton {
            rules.firstOrNull { it.text === et }?.let { rules.remove(it) }
            ruleList.removeView(row)
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(del, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        ruleList.addView(row)
        rules.add(RuleRow(et, row))
    }

    // ---------------------------------------------------------------- UI 小工具
    //
    // 视觉 token 全部复用首页 / 设置页那一套（TextDisplay / TextNote /
    // PrimaryButton / OutlineButton / bg_input）。三页共用一组地基才像一个 App。

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextAppearance(R.style.TextDisplay)
        setPadding(0, dp(16), 0, dp(8))
    }

    /** 等宽小标签：11sp + 0.12 字距，和设置页的字段名同一套语气。 */
    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(getColor(R.color.ink_faint))
        includeFontPadding = false
    }

    /** 段落头：01 ──── 标题。编号自动递增。 */
    private fun section(t: String): ViewGroup {
        sectionIndex++
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(28), 0, dp(10))
            addView(mono("%02d".format(sectionIndex)).apply {
                setTextColor(getColor(R.color.ink))
            })
            addView(View(this@PersonaActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                }
                setBackgroundColor(getColor(R.color.rule))
            })
            addView(mono(t))
        }
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(getColor(R.color.ink_soft))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(8))
    }

    /** 名字沿用历史（原来是 borderless），视觉上已经是描边按钮。 */
    private fun ghostButton(label: String, onClick: () -> Unit) =
        Button(this, null, 0, R.style.OutlineButton).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    /** 实心按钮：整页只有「保存」。 */
    private fun primary(label: String, onClick: () -> Unit) =
        Button(this, null, 0, R.style.PrimaryButton).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(24) }
            setOnClickListener { onClick() }
        }

    /** 删除：纸感体系里最弱的一级，纯文字，不给它按钮的重量。 */
    private fun deleteButton(onClick: () -> Unit) = TextView(this).apply {
        text = "×"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.ink_faint))
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { onClick() }
    }

    /** 行内文字链（重命名 / 删除）：比按钮轻，但还有明确的可点区域。 */
    private fun inlineLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.ink_soft))
        setPadding(dp(12), dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    /** 行内输入框：和设置页同一个底样式，否则来回切两页一眼就看出是两个人的活。 */
    private fun rowInput(value: String, hintText: String, single: Boolean = true) =
        EditText(this).apply {
            setText(value)
            hint = hintText
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink_faint))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (single) setSingleLine() else maxLines = 3
        }

    private fun space(h: Int) = TextView(this).apply { height = dp(h) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
