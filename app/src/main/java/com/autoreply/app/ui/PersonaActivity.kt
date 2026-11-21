package com.autoreply.app.ui

import android.app.Activity
import android.os.Bundle
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
import com.autoreply.app.core.persona.PersonaStore

/**
 * 话术管理：让使用者自己调「问答样例」和「行为准则」，不用改代码重新打包。
 *
 * ## 为什么只开放这两项
 *
 * identity / tone / boundaries 也能改，但改坏的表现是"回复忽然变得很奇怪"，
 * 而用户几乎不可能反推出是哪一项导致的。这两项不一样 ——
 * 样例是"这句该这么答"，准则是"这种时候该这么接"，都是可以直接对着读的。
 *
 * ## 数据怎么存
 *
 * 内置那份在 assets/persona.json 当默认值，这里改的存到 filesDir 下单独一个
 * json（[PersonaStore]）。"恢复内置默认"就是删掉那个文件。
 */
class PersonaActivity : Activity() {

    private lateinit var shotList: LinearLayout
    private lateinit var ruleList: LinearLayout
    private lateinit var knowledgeList: LinearLayout
    private lateinit var titleView: TextView

    private class ShotRow(val q: EditText, val a: EditText, val container: LinearLayout)
    private class RuleRow(val text: EditText, val container: LinearLayout)

    private class KnowledgeRow(val topic: EditText, val answer: EditText, val container: LinearLayout)

    private val shots = ArrayList<ShotRow>()
    private val rules = ArrayList<RuleRow>()
    private val knowledge = ArrayList<KnowledgeRow>()

    /** 段落编号，和设置页一样自动递增。 */
    private var sectionIndex = 0

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
                    "只影响「问答样例」和「行为准则」两项；身份、语气、禁止词仍在设置页。"
            )
        )

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
                    "（禁止词在设置页）。\n" +
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
    }

    // ---------------------------------------------------------------- 数据

    private fun loadCurrent() {
        val persona = PersonaLoader.resolve(this, AppConfig(this)).persona

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
        val fewShot = shots.mapNotNull {
            val q = it.q.text.toString().trim()
            val a = it.a.text.toString().trim()
            if (q.isEmpty() || a.isEmpty()) null else FewShot(q, a)
        }
        val ruleTexts = rules.mapNotNull {
            it.text.text.toString().trim().ifEmpty { null }
        }

        // 两个都空 = 等于把回复能力清空了。允许，但要拦一下确认
        if (fewShot.isEmpty() && ruleTexts.isEmpty()) {
            Toast.makeText(this, "样例和准则都是空的，至少要留一条", Toast.LENGTH_LONG).show()
            return
        }

        val items = knowledge.mapNotNull {
            val topic = it.topic.text.toString().trim()
            val answer = it.answer.text.toString().trim()
            if (topic.isEmpty() || answer.isEmpty()) null else KnowledgeItem(topic, listOf(topic), answer)
        }

        val ok = PersonaStore.save(this, fewShot, ruleTexts, items)
        if (ok) {
            Toast.makeText(
                this,
                "已保存（${items.size} 条情况 / ${fewShot.size} 条样例 / ${ruleTexts.size} 条准则）",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        } else {
            Toast.makeText(this, "保存失败：写不进 filesDir", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetToDefault() {
        PersonaStore.clear(this)
        Toast.makeText(this, "已恢复内置默认", Toast.LENGTH_SHORT).show()
        loadCurrent()
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
