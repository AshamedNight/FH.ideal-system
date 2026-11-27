package com.autoreply.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoreply.app.R
import com.autoreply.app.core.memory.FactExtractor
import com.autoreply.app.core.memory.SessionStore
import com.autoreply.app.platform.Profiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话记忆：把库里"记着谁、记了什么"摊开给人看，并且可以改、可以删。
 *
 * ## 为什么必须能改
 *
 * 抽事实是规则干的（年龄/城市/职业…），规则一定会看错 —— 比如「我妹今年28」
 * 会被记成"她 28 岁"。如果这类错误只能靠"清空全部"来修，那这个功能就废了：
 * 用户会宁可关掉它。所以要能做到"看到、点一下、改成对的"。
 *
 * ## 为什么不做"新增"
 *
 * 事实是程序从对话里看到的，人本来就能在聊天里纠正。这里只提供"改"和"删"，
 * 不提供凭空新增 —— 记一条对话里从没出现过的事，模型下次会拿它当真话说出去。
 */
class MemoryActivity : Activity() {

    private lateinit var root: LinearLayout
    private var sectionIndex = 0

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }
        render()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.paper))
            addView(root)
        })
    }

    /** 改完/删完整页重画：这一页的状态就是数据库本身，重建最省事也最不容易出现脏界面。 */
    private fun render() {
        root.removeAllViews()
        sectionIndex = 0

        root.addView(mono("MEMORY · 会话记忆"))
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8) }
            setBackgroundColor(getColor(R.color.ink))
        })
        root.addView(TextView(this).apply {
            text = "会话记忆"
            setTextAppearance(R.style.TextDisplay)
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(note(
            "这些是从对话里抽出来的事实，生成时会作为「关于对方」发给模型。\n" +
                "**只存事实，不存聊天原文**；读不到昵称的平台不记（宁可不记，不能串人）。\n" +
                "抽错了点那一条直接改；改成空的等于删掉。"
        ))

        val list = SessionStore.sessions(this)
        if (list.isEmpty()) {
            root.addView(section("还没有记录"))
            root.addView(note(
                "正常聊过几轮就会自动出现。看不到可能有三种原因：\n" +
                    "① 这个平台读不到对方昵称（他趣、爱聊这类）；\n" +
                    "② 对方还没说过能抽的话（年龄、城市、职业…）；\n" +
                    "③ 刚刚清空过。"
            ))
            return
        }

        root.addView(section("记着的 ${list.size} 个人"))
        list.forEach { s -> root.addView(personCard(s)) }

        root.addView(Button(this, null, 0, R.style.OutlineButton).apply {
            text = "清空全部会话记忆"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            ).apply { topMargin = dp(20) }
            setOnClickListener { confirmClearAll() }
        })
    }

    /** 一个人一张卡：昵称 + 平台 + 最近时间 + 事实列表 + 删除这个人。 */
    private fun personCard(s: SessionStore.Session): ViewGroup {
        val facts = SessionStore.facts(this, s.pkg, s.peer)
        val platform = Profiles.forPackage(s.pkg)?.label.orEmpty().ifEmpty { s.pkg }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }

            addView(LinearLayout(this@MemoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@MemoryActivity).apply {
                    text = s.peer.ifEmpty { "（没有昵称）" }
                    textSize = 14f
                    setTextColor(getColor(R.color.ink))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(inlineLink("删除") { confirmForget(s) })
            })
            addView(mono("$platform · ${timeFmt.format(Date(s.lastActive))} · ${s.msgCount} 条"))
            if (facts.isEmpty()) {
                addView(note("还没抽到事实（她可能还没说过年龄、城市这类）。"))
            } else {
                facts.forEach { f ->
                    addView(View(this@MemoryActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                        ).apply { topMargin = dp(8) }
                        setBackgroundColor(getColor(R.color.rule))
                    })
                    addView(factRow(s, f))
                }
            }
        }
    }

    private fun factRow(s: SessionStore.Session, f: SessionStore.FactRow): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener { editFact(s, f) }
            addView(TextView(this@MemoryActivity).apply {
                text = FactExtractor.labelOf(f.k)
                textSize = 11f
                setTextColor(getColor(R.color.ink_faint))
                width = dp(52)
            })
            addView(TextView(this@MemoryActivity).apply {
                text = f.v
                textSize = 13f
                setTextColor(getColor(R.color.ink))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (f.confidence < FactExtractor.MIN_CONFIDENCE) {
                // 低置信不进提示词，但要让人看见"它记了这么一条、而且不太确定"
                addView(TextView(this@MemoryActivity).apply {
                    text = "不确定"
                    textSize = 10f
                    setTextColor(getColor(R.color.ink_faint))
                })
            }
        }

    private fun editFact(s: SessionStore.Session, f: SessionStore.FactRow) {
        val input = EditText(this).apply {
            setText(f.v)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("改「${FactExtractor.labelOf(f.k)}」")
            .setMessage("留空 = 删掉这条。改过的会直接进提示词，置信度按「人确认过」算。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                SessionStore.putFact(this, s.pkg, s.peer, f.k, input.text.toString())
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmForget(s: SessionStore.Session) {
        AlertDialog.Builder(this)
            .setTitle("忘掉「${s.peer}」？")
            .setMessage("会删掉这个会话记下的全部事实。下次聊起来会重新开始认识她。")
            .setPositiveButton("删掉") { _, _ ->
                SessionStore.forget(this, s.pkg, s.peer)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清空全部会话记忆？")
            .setMessage("所有会话的事实都会删掉。聊天记录（另一份）不受影响。")
            .setPositiveButton("清空") { _, _ ->
                SessionStore.clear(this)
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- UI 小工具

    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(getColor(R.color.ink_faint))
        includeFontPadding = false
    }

    private fun section(t: String): ViewGroup {
        sectionIndex++
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(26), 0, dp(10))
            addView(mono("%02d".format(sectionIndex)).apply {
                setTextColor(getColor(R.color.ink))
            })
            addView(View(this@MemoryActivity).apply {
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

    private fun inlineLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.ink_soft))
        setPadding(dp(12), dp(8), 0, dp(8))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
