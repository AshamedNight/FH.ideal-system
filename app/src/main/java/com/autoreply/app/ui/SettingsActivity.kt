package com.autoreply.app.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoreply.app.R
import com.autoreply.app.core.AppConfig

/**
 * 设置页。
 *
 * 全部用代码搭 UI 而不是 XML：这些字段会随需求频繁增删，
 * 集中在一个文件里改比在 layout 和代码之间来回跳更快，也不会漏改 id。
 */
class SettingsActivity : Activity() {

    private lateinit var config: AppConfig
    private val inputs = LinkedHashMap<String, EditText>()
    private lateinit var levelGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(section("风险档位"))
        root.addView(note("档位决定程序能自动做到哪一步。发送永远由你点，任何档位都不会替你发。"))

        levelGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        addLevel(AppConfig.LEVEL_FILL_ONLY, "L0 只填不发",
            "你自己停在聊天页，程序把草稿填进输入框。零自动点击。")
        addLevel(AppConfig.LEVEL_NOTIFY, "L1 通知驱动（推荐）",
            "收到新消息通知时才点开那一个会话，填好草稿等你发送。每条 1 次自动点击。")
        addLevel(AppConfig.LEVEL_POLL, "L2 列表轮询（不建议）",
            "主动扫会话列表找未读，每条 3~5 次点击。自动化特征最明显，风险显著更高。")
        root.addView(levelGroup)

        root.addView(section("目标应用"))
        root.addView(multi("targetPackages", config.targetPackages.joinToString("\n"), 4))
        root.addView(note("一行一个包名，可填多个平台。留空表示抓取时不过滤（任何非本 App 界面都抓），方便第一次摸结构。"))
        root.addView(note("改完包名要同步修改 AndroidManifest.xml 里 queries 的 package，否则打不开目标应用。"))

        root.addView(section("节奏护栏"))
        root.addView(field("hourlyCap", "每小时最多处理（条）", config.hourlyCap.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("burstLimit", "连续处理多少条后长冷却", config.burstLimit.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("cooldownMinutes", "长冷却时长（分钟，≥10）", config.cooldownMinutes.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("minGapSeconds", "两条之间最短间隔（秒，≥30）", config.minGapSeconds.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(note("实际间隔会在最短值上乘 0.7~1.8 的随机系数，不要设成整齐数字。"))

        root.addView(section("模型"))
        root.addView(field("llmBaseUrl", "接口地址", config.llmBaseUrl, InputType.TYPE_TEXT_VARIATION_URI))
        root.addView(field("llmApiKey", "API Key", config.llmApiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD))
        root.addView(field("llmModel", "模型名", config.llmModel, InputType.TYPE_CLASS_TEXT))
        root.addView(field("llmProxy", "代理（可选）", config.llmProxy, InputType.TYPE_TEXT_VARIATION_URI))
        root.addView(note("代理只作用于「发往模型接口」的请求，目标 App 的流量不经过它 —— 所以不必为此打开手机的系统级 VPN。\n例：http://127.0.0.1:7890 或 socks5://127.0.0.1:1080（Termux 里跑的代理填这里）。走电脑中转时留空。"))
        root.addView(note("接口地址填 mock = 本地假模型，不联网、不用 Key，只能验证管道。\n若要连需要翻墙的模型，也可改成电脑中转：http://电脑内网IP:8787/v1 （见 tools/model-relay.py）"))

        root.addView(section("人设"))
        root.addView(multi("persona", config.persona, 8))
        root.addView(note("用「你是……」开头描述这个人是什么身份、怎么说话、绝不说什么。不要写「请帮我生成回复」，那是任务不是人设。"))

        root.addView(section("人设 JSON（进阶，可选）"))
        root.addView(multi("personaJson", config.personaJson, 6))
        root.addView(note("留空则用内置那份。填了就覆盖它，改完保存立刻生效，不用重新打包。"))
        root.addView(note("这里能填 few_shot（对方说→我回的样例）——治「回复单一」最有效的就是它，比写一堆形容词管用。格式照仓库里的 assets/persona.json。"))

        root.addView(section("禁止出现的内容"))
        root.addView(multi("bannedWords", config.bannedWords, 5))
        root.addView(note("一行一个词或短语。命中的草稿会被丢弃并重新生成，两次仍命中的话只显示给你看，不填入。"))
        root.addView(field("maxReplyChars", "回复最长字数", config.maxReplyChars.toString(), InputType.TYPE_CLASS_NUMBER))

        root.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        val level = when (levelGroup.checkedRadioButtonId) {
            R.id.rb_level_0 -> AppConfig.LEVEL_FILL_ONLY
            R.id.rb_level_2 -> AppConfig.LEVEL_POLL
            else -> AppConfig.LEVEL_NOTIFY
        }
        config.level = level
        config.targetPackages = textOf("targetPackages").split('\n')
        config.hourlyCap = intOf("hourlyCap", config.hourlyCap)
        config.burstLimit = intOf("burstLimit", config.burstLimit).coerceAtLeast(1)
        config.cooldownMinutes = intOf("cooldownMinutes", config.cooldownMinutes)
        config.minGapSeconds = intOf("minGapSeconds", config.minGapSeconds)
        config.llmBaseUrl = textOf("llmBaseUrl")
        config.llmApiKey = textOf("llmApiKey")
        config.llmModel = textOf("llmModel")
        config.llmProxy = textOf("llmProxy")
        config.persona = textOf("persona")
        config.personaJson = textOf("personaJson")
        config.bannedWords = textOf("bannedWords")
        config.maxReplyChars = intOf("maxReplyChars", config.maxReplyChars)

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun textOf(key: String): String = inputs[key]?.text?.toString().orEmpty()

    private fun intOf(key: String, fallback: Int): Int =
        inputs[key]?.text?.toString()?.trim()?.toIntOrNull() ?: fallback

    // ------------------------------------------------------------ UI 小工具

    private fun addLevel(value: Int, title: String, desc: String) {
        val rb = RadioButton(this).apply {
            id = when (value) {
                AppConfig.LEVEL_FILL_ONLY -> R.id.rb_level_0
                AppConfig.LEVEL_POLL -> R.id.rb_level_2
                else -> R.id.rb_level_1
            }
            text = title
            isChecked = config.level == value
        }
        levelGroup.addView(rb)
        levelGroup.addView(note("　$desc"))
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 15f
        setPadding(0, dp(18), 0, dp(4))
        setTextColor(0xFF0F6E56.toInt())
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF6B6B6B.toInt())
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun field(key: String, label: String, value: String, inputType: Int): ViewGroup {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF1A1A1A.toInt())
        })
        val edit = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 13f
            setSingleLine(true)
        }
        inputs[key] = edit
        box.addView(edit)
        return box
    }

    private fun multi(key: String, value: String, lines: Int): EditText {
        val edit = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 13f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = lines
        }
        inputs[key] = edit
        return edit
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
