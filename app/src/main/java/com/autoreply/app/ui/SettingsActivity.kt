package com.autoreply.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
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
import com.autoreply.app.core.llm.GreetingHooks

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
        root.addView(field("cooldownMinutes", "长冷却时长（分钟，≥3）", config.cooldownMinutes.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("minGapSeconds", "两条之间最短间隔（秒，≥30）", config.minGapSeconds.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("batchSize", "连点一次处理几个（≤20）", config.batchSize.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(note("实际间隔会在最短值上乘 0.7~1.8 的随机系数，不要设成整齐数字。\n这几个值只在「自动触发」时起作用；手动点「生成并填入」只受每小时上限约束，不会被冷却或精力拦住。\n平台报警后的熔断（30 分钟）任何时候都生效，那才是真正需要等的东西。"))
        root.addView(section("演练模式"))
        root.addView(android.widget.CheckBox(this).apply {
            text = "演练模式：不调模型、不限速（只测流程）"
            isChecked = config.drillMode
            setOnCheckedChangeListener { _, on -> config.drillMode = on }
        })
        root.addView(
            note(
                "开着它：回复按你在「话术管理」里配的问答样例拼出来，不联网、不花 Key 的钱；\n" +
                    "对方说什么，就从样例里找最接近的那条答 —— 不同的话得到不同的回复，\n" +
                    "所以能看出续聊到底有没有接上（固定几句话是测不出来的）。\n" +
                    "每小时上限、连续上限、冷却、精力全部不生效，连点一次最多可设 999 个。\n" +
                    "熔断仍然生效 —— 那是平台真的报警了。\n\n" +
                    "注意：这些内容是真的会发出去的（发送永远由你点），只能用于测试，测完记得关掉。"
            )
        )

        root.addView(Button(this).apply {
            text = "清除冷却（程序自己歇的那段）"
            setOnClickListener {
                com.autoreply.app.core.guard.PacerGuard(this@SettingsActivity, config).clearCooldown()
                Toast.makeText(this@SettingsActivity, "冷却已清除，可以继续了", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(section("模型"))
        root.addView(field("llmBaseUrl", "接口地址", config.llmBaseUrl, InputType.TYPE_TEXT_VARIATION_URI))
        root.addView(field("llmApiKey", "API Key", config.llmApiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD))
        root.addView(field("llmModel", "模型名", config.llmModel, InputType.TYPE_CLASS_TEXT))
        root.addView(field("llmProxy", "代理（可选）", config.llmProxy, InputType.TYPE_TEXT_VARIATION_URI))
        root.addView(note("代理只作用于「发往模型接口」的请求，目标 App 的流量不经过它 —— 所以不必为此打开手机的系统级 VPN。\n例：http://127.0.0.1:7890 或 socks5://127.0.0.1:1080（Termux 里跑的代理填这里）。走电脑中转时留空。"))
        root.addView(note("接口地址填 mock = 本地假模型，不联网、不用 Key，只能验证管道。\n若要连需要翻墙的模型，也可改成电脑中转：http://电脑内网IP:8787/v1 （见 tools/model-relay.py）"))

        root.addView(section("时段"))
        root.addView(
            note(
                "告诉模型「现在几点」，它会据此调整说话方式 —— 比如上午就不会说「刚下班」。\n" +
                    "留「自动」就跟系统时钟；也可以手动指定，方便测不同时段的效果。"
            )
        )
        val timeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val timeOptions = listOf(
            "" to "自动（跟系统时钟）",
            "morning" to "上午",
            "afternoon" to "下午",
            "evening" to "晚上",
            "night" to "深夜",
        )
        timeOptions.forEach { (value, label) ->
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                tag = value
                isChecked = config.timeOfDay == value
            }
            timeGroup.addView(rb)
        }
        timeGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            config.timeOfDay = (rb?.tag as? String).orEmpty()
        }
        // 没选中任何一个（首次进来且值为空）时，把"自动"选上
        if (timeGroup.checkedRadioButtonId == -1 && timeGroup.childCount > 0) {
            (timeGroup.getChildAt(0) as? RadioButton)?.isChecked = true
        }
        root.addView(timeGroup)

        root.addView(section("精力"))
        root.addView(android.widget.CheckBox(this).apply {
            text = "锁定精力（测试时用）"
            isChecked = config.energyLocked
            setOnCheckedChangeListener { _, on -> config.energyLocked = on }
        })
        root.addView(
            note(
                "开启后精力不再消耗、也不随时间恢复，永远等于下面这个值；\n" +
                    "判定链也不会因为\"累了\"压住你。关掉就恢复程序自己的疲劳曲线 ——\n" +
                    "已经设好的值不会丢，开关只决定用不用。"
            )
        )
        root.addView(
            field(
                "energyValue", "锁定值（0~1，如 0.85）",
                config.energyLockedValue.toString(),
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            )
        )

        root.addView(section("开场白（钩子库）"))
        root.addView(
            note(
                "一行 = 一条要发出去的消息。轮换着用：第 1 个会话用第 1 条，第 2 个用第 2 条，走到底再绕回来。\n" +
                    "填了就不调模型、不花 token；留空则交给模型写。\n\n" +
                    "支持三个槽位，填不上就自动跳过那一条（不会把花括号发出去）：\n" +
                    "　{昵称}　对方昵称，读不到就不发这条\n" +
                    "　{原话}　对方刚说的那句话\n" +
                    "　{时段}　凌晨 / 早上 / 上午 / 中午 / 下午 / 晚上 / 深夜\n\n" +
                    "写钩子的标准：具体、不奉承、留一个能接的话头。\n" +
                    "「你好漂亮」「在吗」这类不占坑位 —— 它们不产生任何信息，对方没有理由回。\n" +
                    "每句尽量 15 字以内，长了像小作文，跟真人打字节奏不符。"
            )
        )
        root.addView(android.widget.CheckBox(this).apply {
            text = "启用开场白"
            isChecked = config.greetingEnabled
            setOnCheckedChangeListener { _, on -> config.greetingEnabled = on }
        })
        root.addView(
            field(
                "greetingLines", "开场白库（一行一条，可留空）",
                config.greetingLines.joinToString("\n"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
        )
        // 20 条手打进手机太难受。内置库直接灌进来，改哪句再手动改。
        root.addView(Button(this).apply {
            text = "载入内置 20 条钩子模板"
            setOnClickListener {
                inputs["greetingLines"]?.setText(GreetingHooks.DEFAULT.joinToString("\n"))
                Toast.makeText(this@SettingsActivity, "已载入，点保存生效", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(section("人设"))

        // 话术管理单独一页：样例和准则会改得很频繁，塞进这个已经很长的页面
        // 会把其他设置冲散
        root.addView(Button(this).apply {
            text = "话术管理（问答样例 / 行为准则）"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, PersonaActivity::class.java))
            }
        })
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

        root.addView(section("输入法（可选，但某些平台必须）"))
        root.addView(Button(this).apply {
            text = "去切换默认输入法"
            setOnClickListener {
                // 直接跳到系统输入法设置页。没有公开 API 能"自动切"，
                // 那是 WRITE_SECURE_SETTINGS 才能做的事，普通 App 做不到。
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                }.onFailure {
                    Toast.makeText(
                        this@SettingsActivity,
                        "这台机器打不开输入法设置，手动去 设置 → 语言和输入法",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        })
        root.addView(note("只有「输入框对无障碍不可见」的平台才需要它（同城夜聊这类）。抖音、moyi 不需要。\n切过去之后，AutoReply 的草稿会通过输入法上屏 —— 和真键盘打字是同一条通道。\n平时想正常打字，用键盘上的地球图标即可切回你自己的输入法。"))

        root.addView(section("状态复位"))
        root.addView(Button(this).apply {
            text = "重置精力（我休息好了）"
            setOnClickListener {
                com.autoreply.app.core.guard.Energy(this@SettingsActivity).reset()
                Toast.makeText(this@SettingsActivity, "精力已回满", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(note("精力只影响「自动」回复的节奏：越低越拖，低到 0.30 以下就完全不自动动。连续测几次会掉到 0.28，那时自动触发会被压住 —— 点这个按钮相当于「我休息好了」。\n手动点「生成并填入」不受精力影响。"))

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
        config.batchSize = intOf("batchSize", config.batchSize)
        inputs["energyValue"]?.text?.toString()?.trim()?.toFloatOrNull()?.let {
            config.energyLockedValue = it
        }
        config.greetingLines = inputs["greetingLines"]?.text?.toString().orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
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

    private fun field(
        key: String,
        label: String,
        value: String,
        inputType: Int,
        singleLine: Boolean = true,
    ): ViewGroup {
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
            // 多行字段（开场白一行一句）必须放行换行，否则全被压成一行
            setSingleLine(singleLine)
            if (!singleLine) maxLines = 6
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
