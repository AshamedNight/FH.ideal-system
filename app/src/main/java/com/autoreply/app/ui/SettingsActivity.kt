package com.autoreply.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import com.autoreply.app.core.llm.TalkStyle
import com.autoreply.app.core.log.ChatLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出聊天记录的系统「保存到文件」请求码。 */
private const val REQ_EXPORT_CHATLOG = 4001

/**
 * 设置页。
 *
 * 全部用代码搭 UI 而不是 XML：这些字段会随需求频繁增删，
 * 集中在一个文件里改比在 layout 和代码之间来回跳更快，也不会漏改 id。
 *
 * 视觉与首页共用同一套 token（[R.style.TextDisplay] / [R.style.TextNote] /
 * [R.style.PrimaryButton] / [R.style.OutlineButton] / [R.drawable.bg_input]）。
 * 想让两页不像两个 App，就必须共用同一组地基，而不是各写各的 setTextSize。
 */
class SettingsActivity : Activity() {

    private lateinit var config: AppConfig
    private val inputs = LinkedHashMap<String, EditText>()
    private lateinit var levelGroup: RadioGroup

    /** 导出时先把全文拿在手里，等系统对话框回来再写给用户选的 URI。 */
    private var pendingExport = ""

    /**
     * 段落编号。这一页有十几段，编号比加粗更能让人知道"读到哪儿了"，
     * 也是这套纸感设计里唯一的序号系统 —— 不用在调用处手写 01/02。
     */
    private var sectionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }

        // 顶栏：和首页同一套骨架（等宽小标签 + 一条深线 + 27sp 标题）
        root.addView(mono("SETTINGS · 设置"))
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8) }
            setBackgroundColor(getColor(R.color.ink))
        })
        root.addView(TextView(this).apply {
            text = "设置"
            setTextAppearance(R.style.TextDisplay)
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "改完点最下面的「保存」。只存在本机，不上传。"
            setTextAppearance(R.style.TextNote)
            setPadding(0, dp(8), 0, 0)
        })

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
        root.addView(
            check("演练模式：不调模型、不限速（只测流程）", config.drillMode) { config.drillMode = it }
        )
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

        root.addView(action("清除冷却（程序自己歇的那段）") {
            com.autoreply.app.core.guard.PacerGuard(this@SettingsActivity, config).clearCooldown()
            Toast.makeText(this@SettingsActivity, "冷却已清除，可以继续了", Toast.LENGTH_SHORT).show()
        })

        root.addView(section("模型"))
        // 分发版：接口与凭据由 assets/distribution.json 内置，界面上不给看、不给改、不给复制。
        // 只显示一个只读的设备编号 —— master 调额时要靠它认领这台机器。
        if (config.distributionEnabled) {
            root.addView(note("这一版已内置接口地址和授权，无需填写，也不能查看或修改。\n真实 Key 在中转服务器上，本机没有。"))
            root.addView(note("本机编号（给分发方调每日额度用）：\n${config.deviceId}"))
            root.addView(note("分发版不开代理：成员手机不挂任何 VPN 或代理，直接连公网中转。"))
        } else {
            root.addView(field("llmBaseUrl", "接口地址", config.llmBaseUrl, InputType.TYPE_TEXT_VARIATION_URI))
            root.addView(field("llmApiKey", "API Key", config.llmApiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD))
            root.addView(field("llmProxy", "代理（可选）", config.llmProxy, InputType.TYPE_TEXT_VARIATION_URI))
            root.addView(note("代理只作用于「发往模型接口」的请求，目标 App 的流量不经过它 —— 所以不必为此打开手机的系统级 VPN。\n例：http://127.0.0.1:7890 或 socks5://127.0.0.1:1080（Termux 里跑的代理填这里）。走电脑中转时留空。"))
            root.addView(note("接口地址填 mock = 本地假模型，不联网、不用 Key，只能验证管道。\n若要连需要翻墙的模型，也可改成电脑中转：http://电脑内网IP:8787/v1 （见 tools/model-relay.py）"))
            // 模型名也只给开发版改：分发包里成员改成一个上游不认的名字，
            // 报错是「模型不存在」，看着像中转坏了。固定值在 distribution.json 的 model。
            root.addView(field("llmModel", "模型名", config.llmModel, InputType.TYPE_CLASS_TEXT))
        }

        root.addView(section("剪贴板交付（抓不到输入框时用）"))
        root.addView(
            check("只生成到剪贴板，不写输入框", config.clipboardOnly) { config.clipboardOnly = it }
        )
        root.addView(note(
            "通用兜底：读上下文 → 生成 → 放进剪贴板，你回聊天页长按粘贴、再自己点发送。" +
                "比自动填入多一步，但完全不依赖输入框对无障碍/输入法是否可见 —— " +
                "爱聊、他趣这类抓不到输入框或整棵树被摘掉的平台靠它也能用。\n\n" +
                "上下文按顺序取：① 聊天页节点 ② 新消息通知 ③ 剪贴板里你刚复制的对方原话。" +
                "三者都读不到时不会瞎编，会明确告诉你缺什么。\n" +
                "开着它时不会切输入法、不会连点换会话；发送永远是你自己点。"
        ))

        root.addView(section("聊天记录"))
        root.addView(
            check("把读到的对话记在本机（默认关）", config.chatLogEnabled) {
                config.chatLogEnabled = it
            }
        )
        root.addView(note(
            "只记「程序真正读到过」的内容：你打开过、并触发了读取的会话。" +
                "没打开过的会话没有；语音/图片/表情没有文字节点，记不到；" +
                "时间戳是「程序首次读到」的时间，不是消息真实发送时间。\n\n" +
                "存成明文放在 App 私有目录（filesDir/chatlog/日期.txt），保留 30 天自动清理。" +
                "导出由你点下面的按钮、自己选保存位置，不需要存储权限。"
        ))
        val logFiles = ChatLogStore.files(this)
        val logSize = ChatLogStore.sizeBytes(this)
        if (logFiles.isEmpty()) {
            root.addView(note("本机还没有记录。"))
        } else {
            root.addView(note("本机已有 ${logFiles.size} 个文件，共 ${logSize / 1024} KB（${logFiles.first().name.removeSuffix(".txt")} 起）。"))
            root.addView(action("导出为 txt（选择保存位置）") { exportChatLog() })
            root.addView(action("清空本机记录") {
                ChatLogStore.clear(this)
                Toast.makeText(this, "已清空聊天记录", Toast.LENGTH_SHORT).show()
                recreate()
            })
        }

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
                textSize = 13f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(6), 0, dp(6))
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

        root.addView(section("说话风格"))
        root.addView(
            note(
                "只改语气，不改长度和底线。选项来自一张表（TalkStyle），加风格不用改这里。\n" +
                    "「自然」等于不加任何指令 —— 和你之前的表现完全一致。\n" +
                    "演练模式下不生效：那个模式的假模型按样例拼句，不读提示词。"
            )
        )
        val styleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        TalkStyle.ALL.forEach { item ->
            val rb = RadioButton(this).apply {
                text = item.label
                textSize = 11f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(6), dp(6), dp(6))
                id = View.generateViewId()
                tag = item.id
                isChecked = config.talkStyle == item.id
            }
            styleGroup.addView(rb)
        }
        styleGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            (rb?.tag as? String)?.let { config.talkStyle = it }
        }
        // 值认不出来（手改过配置）时，把默认风格选上，让界面和实际生效的一致
        if (styleGroup.checkedRadioButtonId == -1 && styleGroup.childCount > 0) {
            (styleGroup.getChildAt(0) as? RadioButton)?.isChecked = true
        }
        root.addView(styleGroup)

        root.addView(section("精力"))
        root.addView(
            check("锁定精力（测试时用）", config.energyLocked) { config.energyLocked = it }
        )
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
        root.addView(
            check("启用开场白", config.greetingEnabled) { config.greetingEnabled = it }
        )
        root.addView(
            field(
                "greetingLines", "开场白库（一行一条，可留空）",
                config.greetingLines.joinToString("\n"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
        )

        // ---- 多发开场白：勾了就用下面三条顶掉库，按顺序一条一条填 ----
        val multiOn = config.greetingMulti
        val multiLines = config.greetingMultiLines
        root.addView(
            check("开场白发多条（用下面三条代替上方的库）", multiOn) {
                config.greetingMulti = it
            }
        )
        root.addView(note(
            "按顺序发：第一条填进输入框，**你发出去了才会填第二条** —— 不会自己连发。" +
                "对方还没回就连甩三条，是整套行为里最像脚本的一种。\n" +
                "三条都支持 {昵称} {原话} {时段}；某条填不上（比如只写了 {昵称} 而昵称没读到）就跳过它。" +
                "留空的行不发，且**保留位置** —— 只填第三条就只发那一条，不会顶到第一条去。"
        ))
        root.addView(field("greetingM1", "第一条", multiLines.getOrElse(0) { "" }, InputType.TYPE_CLASS_TEXT))
        root.addView(field("greetingM2", "第二条（可留空）", multiLines.getOrElse(1) { "" }, InputType.TYPE_CLASS_TEXT))
        root.addView(field("greetingM3", "第三条（可留空）", multiLines.getOrElse(2) { "" }, InputType.TYPE_CLASS_TEXT))
        // 20 条手打进手机太难受。内置库直接灌进来，改哪句再手动改。
        root.addView(action("载入内置 20 条钩子模板") {
            inputs["greetingLines"]?.setText(GreetingHooks.DEFAULT.joinToString("\n"))
            // **立刻落盘**，不等下面的「保存」。
            //
            // 这个按钮的语义是"改用内置库"，本身就是一次明确的决定，不是"改个草稿"。
            // 之前只填输入框，而「保存」在这一页最底部（13 段之后，要滚很久），
            // 用户载入完就退回去了 —— config 里还是老话术，
            // 表现就是"载入了新库，发出去的还是旧的那几句"。
            config.greetingLines = GreetingHooks.DEFAULT
            Toast.makeText(
                this@SettingsActivity,
                "已载入 20 条并立即生效（其他项仍需点保存）",
                Toast.LENGTH_SHORT,
            ).show()
        })

        root.addView(section("人设"))

        // 话术管理单独一页：样例和准则会改得很频繁，塞进这个已经很长的页面
        // 会把其他设置冲散
        root.addView(action("话术管理（问答样例 / 行为准则）") {
            startActivity(Intent(this@SettingsActivity, PersonaActivity::class.java))
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
        root.addView(field("burstChance", "偶尔分条：概率 %（0 = 关闭）", config.burstChance.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(field("burstMax", "最多分几条（2~3）", config.burstMax.toString(), InputType.TYPE_CLASS_NUMBER))
        root.addView(note(
            "真人不是每句一回合 —— 想到一句发一句、再补一句解释都很常见。" +
                "这里的概率决定「这次生成要不要拆成 2~3 条短消息」，默认 30%。\n" +
                "拆出来的仍然**一条一条填**：第一条进输入框，你发出去了才填下一条。" +
                "模型也会判断：一句话能说完就只回一条，不为了凑条数硬拆。" +
                "设成 0 关掉。"
        ))

        root.addView(section("输入法（可选，但某些平台必须）"))
        root.addView(action("去切换默认输入法") {
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
        })
        root.addView(note("只有「输入框对无障碍不可见」的平台才需要它（同城夜聊这类）。抖音、moyi 不需要。\n切过去之后，AutoReply 的草稿会通过输入法上屏 —— 和真键盘打字是同一条通道。\n平时想正常打字，用键盘上的地球图标即可切回你自己的输入法。"))

        root.addView(section("状态复位"))
        root.addView(action("重置精力（我休息好了）") {
            com.autoreply.app.core.guard.Energy(this@SettingsActivity).reset()
            Toast.makeText(this@SettingsActivity, "精力已回满", Toast.LENGTH_SHORT).show()
        })
        root.addView(note("精力只影响「自动」回复的节奏：越低越拖，低到 0.30 以下就完全不自动动。连续测几次会掉到 0.28，那时自动触发会被压住 —— 点这个按钮相当于「我休息好了」。\n手动点「生成并填入」不受精力影响。"))

        // 全页唯一的实心按钮：保存。上面那一堆都是改，只有这一步是"落盘"。
        root.addView(primary("保存") { save() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.paper))
            addView(root)
        })
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
        // 三条按下标存，空串保留位置（"只填第三条"= 只发第三条，不能顶到前面去）
        config.greetingMultiLines = listOf(
            textOf("greetingM1"),
            textOf("greetingM2"),
            textOf("greetingM3"),
        )
        // 分发模式下这三个 field 根本没建，textOf 会返回空串。
        // 写进去虽不影响 getter（它优先返回内置值），但会污染 sp ——
        // 万一哪天关掉分发模式，开发版就变成一套空配置。所以只在非分发版写。
        if (!config.distributionEnabled) {
            config.llmBaseUrl = textOf("llmBaseUrl")
            config.llmApiKey = textOf("llmApiKey")
            config.llmProxy = textOf("llmProxy")
            // 这三个输入框在分发模式下根本没建，textOf 会返回空串 ——
            // 无条件写回去等于把配置清空。所以整段一起跳过。
            config.llmModel = textOf("llmModel")
        }
        config.persona = textOf("persona")
        config.personaJson = textOf("personaJson")
        config.bannedWords = textOf("bannedWords")
        config.maxReplyChars = intOf("maxReplyChars", config.maxReplyChars)
        config.burstChance = intOf("burstChance", config.burstChance)
        config.burstMax = intOf("burstMax", config.burstMax)

        // 身份 / 语气（personaJson）也是"人设"的一部分，而人设是按预设切换的。
        // 存完必须同步进当前预设 —— 否则下次切走再切回来，
        // 会把刚改的身份悄悄还原成旧预设里那份。
        com.autoreply.app.core.persona.PersonaPresets.snapshotActive(this)

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun textOf(key: String): String = inputs[key]?.text?.toString().orEmpty()

    private fun intOf(key: String, fallback: Int): Int =
        inputs[key]?.text?.toString()?.trim()?.toIntOrNull() ?: fallback

    // ------------------------------------------------------- 聊天记录导出

    /**
     * 导出走系统的「保存到文件」（SAF）：用户自己选目录和文件名，
     * 我们只拿到一个一次性可写的 URI —— 不需要任何存储权限，也不需要
     * 把文件先拷到公共目录再让用户去找。
     */
    private fun exportChatLog() {
        val text = ChatLogStore.exportText(this)
        if (text.isBlank()) {
            Toast.makeText(this, "还没有可导出的记录", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = text
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "AutoReply-chatlog-$stamp.txt")
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT_CHATLOG) }
            .onFailure {
                pendingExport = ""
                Toast.makeText(this, "打不开保存对话框", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_CHATLOG) return
        val text = pendingExport
        pendingExport = ""
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 刻意分步写，不用 `?.use { ... } != null` 这种一行流：
        // `}` 之后换行再接 `!=` 会被 Kotlin 当成两条语句的边界，
        // 编译报的是「Expecting an element」，而报错位置指在 `!=` 上（踩过）。
        val written = runCatching {
            val out = contentResolver.openOutputStream(uri)
            if (out == null) {
                false
            } else {
                out.use { it.write(bytes) }
                true
            }
        }.getOrDefault(false)
        val msg = if (written) "已导出 ${text.length} 个字符" else "导出失败：那个位置写不进去"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ UI 小工具

    private fun addLevel(value: Int, title: String, desc: String) {
        val rb = RadioButton(this).apply {
            id = when (value) {
                AppConfig.LEVEL_FILL_ONLY -> R.id.rb_level_0
                AppConfig.LEVEL_POLL -> R.id.rb_level_2
                else -> R.id.rb_level_1
            }
            text = title
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(8), 0, 0)
            isChecked = config.level == value
        }
        levelGroup.addView(rb)
        levelGroup.addView(note("　$desc"))
    }

    private fun section(title: String): ViewGroup {
        sectionIndex++
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(28), 0, dp(10))
            addView(mono("%02d".format(sectionIndex)).apply {
                setTextColor(getColor(R.color.ink))
            })
            addView(View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                }
                setBackgroundColor(getColor(R.color.rule))
            })
            addView(mono(title))
        }
    }

    /** 等宽小标签：11sp + 0.12 字距。中文没有大小写，层级只能靠这两样做出来。 */
    private fun mono(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(getColor(R.color.ink_faint))
        includeFontPadding = false
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.ink_soft))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(8))
    }

    /** 勾选框统一样式。原来直接 new CheckBox，字号颜色跟着系统主题走，和别处不搭。 */
    private fun check(text: String, checked: Boolean, onToggle: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(8), 0, dp(8))
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }

    /** 描边按钮：次要操作（清除冷却、切输入法、重置精力、载入模板…）。 */
    private fun action(text: String, onClick: () -> Unit): Button =
        Button(this, null, 0, R.style.OutlineButton).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    /** 实心按钮：全页只留给「保存」。上面那一堆都是改，只有这一步是落盘。 */
    private fun primary(text: String, onClick: () -> Unit): Button =
        Button(this, null, 0, R.style.PrimaryButton).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(32) }
            setOnClickListener { onClick() }
        }

    /** 输入框底样式：白底 + 1px 描边，聚焦时描边转墨黑（选择器在 bg_input.xml）。 */
    private fun editBase(value: String, inputType: Int, lines: Int): EditText =
        EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink_faint))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            if (lines > 1) minLines = lines
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
            setPadding(0, dp(6), 0, dp(6))
        }
        // 字段名做成等宽小标签，和段落头同一套语气；值才是 13sp 的正文
        box.addView(mono(label).apply { setPadding(0, 0, 0, dp(6)) })
        val edit = editBase(value, inputType, 1).apply {
            // 多行字段（开场白一行一句）必须放行换行，否则全被压成一行
            setSingleLine(singleLine)
            if (!singleLine) maxLines = 6
        }
        inputs[key] = edit
        box.addView(edit)
        return box
    }

    private fun multi(key: String, value: String, lines: Int): EditText {
        val edit = editBase(
            value,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            lines,
        ).apply {
            // 多行块从顶部开始写，否则光标停在中间看着像坏了
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        inputs[key] = edit
        return edit
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
