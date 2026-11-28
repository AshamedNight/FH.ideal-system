package com.autoreply.app.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import android.widget.LinearLayout
import com.autoreply.app.R
import com.autoreply.app.core.AppConfig
import com.autoreply.app.core.llm.TalkStyle
import com.autoreply.app.core.llm.TopicBank
import com.autoreply.app.core.persona.PersonaPresets

/**
 * 一个只做写入的输入法。
 *
 * ## 为什么非要有它
 *
 * 「输入框对无障碍不可见」这类平台（同城夜聊），`ACTION_SET_TEXT` 拿不到节点，
 * `findFocus` 也拿不到可编辑对象。但**它能弹出系统键盘** —— 说明这个输入框
 * 有 `InputConnection`。输入法正是通过这条通道和输入框通信的，
 * 而这条通道**不经过无障碍树**。
 *
 * 也就是说：无障碍看不见的输入框，输入法能看见。这是两条不同的路，
 * 不是"更高级的无障碍"。
 *
 * ## 它的形态
 *
 * 刻意做成一块极窄的条，不提供按键 —— 这里不需要打字，只需要把
 * 现成的一句话送进去。真正的键盘由用户自己的输入法提供。
 *
 * 所以它的定位是"**只在需要写入草稿时占用默认输入法**"，
 * 平时用键盘上那个"切换键盘"按钮切回用户自己的输入法。
 * （想做到全自动切换需要 `WRITE_SECURE_SETTINGS`，普通 App 拿不到，
 * 只能靠一次 adb 授权 —— 见 README 里的说明。）
 */
class AutoReplyImeService : InputMethodService() {

    private var statusView: TextView? = null

    /**
     * 上一次**我们自己**写进输入框的那段话。
     *
     * 存在的理由：`commitText` 是**在光标处插入**，不是覆盖。
     * 连点三次「生成并填入」而每次内容又一样，输入框里就变成
     * 「怎么说怎么说怎么说」—— 真机截图确认过。
     *
     * 只有记住"上次写的是哪段"，才能在下次写入前精准地把它擦掉；
     * 直接 `deleteSurroundingText(120,120)` 会连用户自己打的字一起删，
     * 那是不可接受的（设计上明确不覆盖用户的输入）。
     */
    private var lastCommitted: String? = null

    /** 上一次 [replaceAll] 覆盖前，是否把原来的内容备份进了剪贴板（状态行要说清）。 */
    private var lastFillBackedUp = false

    /**
     * 当前绑定的输入框属于哪个 App。
     *
     * 为什么需要：`commitText` 只会写进"当前绑定的那个 InputConnection"。
     * 我们的面板 / 首页浮在目标 App 上面时，这个连接可能还连着**上一个**输入框 ——
     * 于是草稿写进了别处，目标 App 的框里还是旧内容，
     * 而日志一路报"已写入"。用户看到的就是"生成和下层日志对不上"。
     *
     * 空串 = 还不知道（没收到 onStartInputView），这时不做校验，保持旧行为。
     */
    @Volatile
    private var boundPkg: String = ""

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        instance = null
        super.onDestroy()
    }

    /**
     * 键盘视图：一条提示 + 一个"切换键盘"按钮，没有字母键。
     *
     * 为什么必须给切换按钮：这个输入法不提供打字能力，用户一旦把它设为默认，
     * 打开任何 App 想打字时都会看到这条空洞的横条。没有切换入口的话，
     * 他只能去系统设置里改回来 —— 那等于把这个功能变成一次性用品。
     *
     * 也不做成全透明/零高度：用户需要知道"现在用的是哪个输入法"，
     * 否则只会觉得键盘坏了。
     */
    override fun onCreateInputView(): View {
        phraseSig = AppConfig(this).quickPhrases.joinToString("\u241F")
        return buildPanel()
    }

    /**
     * 上一次建面板时用的是哪一组常用短语。
     *
     * 面板是建一次反复用的，不像 Activity 会重建 —— 用户在设置里加了短语，
     * 不重建的话要等系统把输入法杀掉才看得到，表现为"改了没生效"。
     */
    private var phraseSig: String = ""

    /**
     * 输入法面板。
     *
     * 折叠态只有一行（跟原来一样高）。展开态是**两页**，可以点上面的页签，
     * 也可以左右滑（见 [SlidePager]）：
     *   - 第 1 页「回复」：左边是「生成什么」，右边是「对输入框做什么」——
     *     这个分区是参考图里最值得学的一点，拇指不用在两种语义之间来回找
     *   - 第 2 页「风格」：演练模式 / 开场白库 / 说话风格 / 时段
     *
     * 刻意**没有**的东西（不是漏了）：
     *   - 发送按钮：**用各 App 自带的发送**。项目铁律是"发送永远由人点"，
     *     而且放在面板上会把"写入→发送"的时间差压到两次相邻点击，
     *     人来不及看对方刚说了什么
     *   - 连点 / 切换会话：跨会话批量动作是"脚本"最典型的形状，
     *     不能因为"顺手"就把它搬到输入法上
     *   - 风险档位：改档位要重启读屏服务才彻底生效，放在这里点一下没反应，
     *     比没有这个按钮更糟
     */
    private fun buildPanel(): View {
        val cfg = AppConfig(this)
        val expanded = cfg.imePanelExpanded
        // 每次重建都重新收集第 2 页的可选项，否则会给已经不在界面上的旧 View 着色
        chips.clear()
        chipOn.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.paper))
        }

        // ── 把手行：状态 + 展开/收起 + 切换键盘 ──
        val status = TextView(this).apply {
            text = if (expanded) "AutoReply" else "AutoReply 输入"
            textSize = 11f
            setTextColor(getColor(R.color.ink_soft))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(6), dp(9))
                addView(
                    status,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(linkText(if (expanded) "收起" else "展开") { togglePanel() })
                addView(linkText("键盘") { pickAnotherKeyboard() })
            }
        )

        if (!expanded) return root

        // 人设：只在展开时读一次。
        // 切换时不重新读文件（activePersonaId 就地更新）——
        // 否则每点一次面板都要读一遍 persona_presets.json，白费 IO。
        val presets = PersonaPresets.list(this)
        activePersonaId = PersonaPresets.activeId(this)
        personaRow.clear()
        if (presets.size <= 4) {
            personaRow.addAll(presets)
        } else {
            // 超了 4 个：优先保住当前生效那个，再按顺序补到 4 个。
            // 不这么做的话，切到第 6 个人设后，面板上连"我现在是谁"都看不出来。
            presets.firstOrNull { it.id == activePersonaId }?.let { personaRow.add(it) }
            presets.asSequence()
                .filter { it.id != activePersonaId }
                .take(4 - personaRow.size)
                .forEach { personaRow.add(it) }
        }

        // ── 常用短语：一排可横滑的小格，点一下直接替换输入框内容 ──
        //
        // 这是"我自己有话说"最快的入口，比语音还快：不用说话、不用等识别、
        // 不上传任何东西。放在草稿行上面，因为它是"我来说"，
        // 下面的草稿行是"模型说了什么"，两件事。
        val quick = AppConfig(this).quickPhrases
        if (quick.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            quick.take(12).forEach { p -> strip.addView(phraseChip(p) { insertPhrase(p) }) }
            root.addView(
                android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                }
            )
        }

        // ── 草稿预览：让用户知道框里现在是什么，不用切回聊天页确认 ──
        //
        // 右边那个「待发 N 段」是这一轮加的**真话**：断句后没发完的句子存在
        // DraftQueue 里，发完当前这条才填下一段。但界面上原来完全看不出队列是空的
        // 还是卡住了 —— 用户看到"发完没有下一条"，第一反应就是"又坏了"。
        val draft = TextView(this).apply {
            text = draftPreview()
            textSize = 11f
            setTextColor(getColor(R.color.ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        draftLine = draft
        val queueBadge = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.ink_faint))
            setPadding(dp(8), 0, 0, 0)
        }
        queueLine = queueBadge
        refreshDraftLine()
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(getColor(R.color.surface))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(
                    TextView(this@AutoReplyImeService).apply {
                        text = "草稿 "
                        textSize = 11f
                        letterSpacing = 0.08f
                        setTextColor(getColor(R.color.ink_faint))
                    }
                )
                addView(
                    draft,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(queueBadge)
            }
        )

        // ── 页签：回复 / 风格。点得动，也滑得动 ──
        //
        // 只放文字页签、不做胶囊按钮：面板本来就只有 300dp，
        // 再加一排有底色的按钮会跟下面的网格抢注意力。
        val tabReply = tabLabel("回复") { showPage(0) }
        val tabStyle = tabLabel("风格") { showPage(1) }
        val tabTopic = tabLabel("话题") { showPage(2) }
        tabs = listOf(tabReply, tabStyle, tabTopic)
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    tabReply,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    tabStyle,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    tabTopic,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        )
        root.addView(
            View(this).apply {
                setBackgroundResource(R.drawable.divider_solid)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                }
            }
        )

        // ── 第一页：左网格（生成什么） + 右列（对输入框做什么） ──
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowOf(
                panelCard("生成下一条") { generate(clearFirst = false) },
                panelCard("换一句") { generate(clearFirst = true) },
            ))
            addView(
                rowOf(
                    panelCard("用开场白") { useGreetingHook() },
                    panelCard("复制草稿") { copyCurrentDraft() },
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
            // 第三行两格：一个回 App 看判定链，一个手动换平台。
            //
            // 换平台从"连点自动切"改成"人点这一下" —— 切前台是整套流程里最显眼的动作，
            // 而"真的没有未读"和"按钮没认出来"代码里分不清，判错就会当着人来回弹。
            addView(
                rowOf(
                    panelCard("打开 App") { openApp() },
                    panelCard("切下一个平台") { switchPlatform() },
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
            // 第四行两格，解决"模型没写对、我自己有话说"那个场景：
            //
            // 我们的输入法没有键位，想自己打字就得切回系统键盘 —— 而切一次要来回两趟。
            // 「换键盘」一键切过去（回来按地球图标）；「说一句」用系统语音识别，
            // 不离开这个输入框，说的话直接落进草稿位置。
            addView(
                rowOf(
                    panelCard("换键盘") { switchToOtherKeyboard() },
                    panelCard("说一句") { startVoiceInput() },
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
        }

        // 右列三格用自然高度：三格 × (10+10+行高) + 两道 8dp 间隙 ≈ 118dp，
        // 正好和左边三行网格等高。用 weight 或 MATCH_PARENT 反而有风险 ——
        // 父容器是 wrap_content，权重高度可能被算成 0（整列看不见）。
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(
            keyText("粘贴") { pasteFromClipboard() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        right.addView(
            keyText("⌫") { backspace() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        right.addView(
            keyText("清空") { clearAll() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        // 右侧固定 52dp：比网格窄、比文字宽，拇指边缘刚好够到
        val rightLp = LinearLayout.LayoutParams(
            dp(52),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rightLp.marginStart = dp(8)

        val pageReply = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            addView(
                left,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(right, rightLp)
        }

        // ── 第二页：模式 / 风格 / 时段 ──
        //
        // 刻意凑成**三行**，和第一页同高：两页高度不一样的话，
        // 滑动时面板会整体跳一下，看起来像坏了。
        // 也因此这里不写小标题（"模式""风格"），靠选项本身和页签名认出来。
        val pageStyle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            addView(
                rowOf(
                    chip("演练模式", { cfg.drillMode }) { cfg.drillMode = !cfg.drillMode },
                    chip("开场白库", { cfg.greetingEnabled }) {
                        cfg.greetingEnabled = !cfg.greetingEnabled
                    },
                    // 连输入法都写不进去的场合，在这里一键切到剪贴板交付。
                    // 面板上放这个开关，是为了不用来回切回 App 设置页。
                    // 注意他趣不需要开：它读降级、写仍走输入法（点一下输入框就填）。
                    chip("剪贴板", { cfg.clipboardOnly }) { cfg.clipboardOnly = !cfg.clipboardOnly },
                )
            )
            addView(
                // 风格是 5 个，数量由 TalkStyle.ALL 决定 —— 不写死，加一种风格不用改这里
                rowOf(TalkStyle.ALL.map { s ->
                    chip(s.label, { cfg.talkStyle == s.id }) { cfg.talkStyle = s.id }
                }).apply { setPadding(0, dp(8), 0, 0) }
            )
            addView(
                rowOf(
                    chip("自动时段", { cfg.timeOfDay.isEmpty() }) { cfg.timeOfDay = "" },
                    chip("上午", { cfg.timeOfDay == "morning" }) { cfg.timeOfDay = "morning" },
                    chip("下午", { cfg.timeOfDay == "afternoon" }) {
                        cfg.timeOfDay = "afternoon"
                    },
                    chip("晚上", { cfg.timeOfDay == "evening" }) { cfg.timeOfDay = "evening" },
                    chip("深夜", { cfg.timeOfDay == "night" }) { cfg.timeOfDay = "night" },
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
            // 人设：多个号、或者同一个人设换种性格，切换不该要求"先退出对话、回 App"。
            //
            // 最多显示 4 个：一行 5 格已是极限，再多字就挤没了。
            // 超出的话优先保住**当前生效的那个**，其余取前三个 ——
            // 否则切到第 6 个人设之后，面板上连"我现在是谁"都看不出来。
            if (personaRow.isNotEmpty()) {
                addView(
                    rowOf(personaRow.map { p ->
                        chip(
                            shortName(p.name),
                            { activePersonaId == p.id },
                        ) { switchPersona(p) }
                    }).apply { setPadding(0, dp(8), 0, 0) }
                )
            }
        }

        // ── 第三页：找话题 ──
        //
        // 卡住的往往不是"不会回"，而是"没话可说"（对方回个「嗯」）。
        // 这一页给的是**能直接发出去的提问**，按类分好；点一下就走 replaceAll，
        // 和常用短语同一套语义（整框替换 + 覆盖前备份）。
        var topicCat = TopicBank.CATS.first()
        val topicList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fillTopics() {
            topicList.removeAllViews()
            TopicBank.of(topicCat).forEach { line -> topicList.addView(topicRow(line.text)) }
        }
        val pageTopics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            addView(
                rowOf(
                    TopicBank.CATS.map { c ->
                        chip(c, { topicCat == c }) {
                            topicCat = c
                            fillTopics()
                        }
                    }
                )
            )
            // 固定高度 + 内部滚动：这一页的行数不固定，跟着内容变会让面板上下跳。
            addView(
                ScrollView(this@AutoReplyImeService).apply {
                    isVerticalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(118),
                    ).apply { topMargin = dp(6) }
                    addView(topicList)
                }
            )
        }
        fillTopics()

        // ── 三页装进可滑动容器 ──
        val p = SlidePager(this).apply {
            addView(
                pageReply,
                FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                pageStyle,
                FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                pageTopics,
                FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            onPage = { idx ->
                currentPage = idx
                cfg.imePage = idx
                updateTabs()
                updateHint()
            }
        }
        pager = p
        root.addView(
            p,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ── 底部：一句提醒。文案跟着页走，不额外占一行按钮位 ──
        val hint = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.ink_faint))
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        hintView = hint
        root.addView(hint)

        currentPage = cfg.imePage.coerceIn(0, 2)
        p.select(currentPage, animate = false)
        updateTabs()
        updateHint()

        return root
    }

    // ---------------------------------------------------------------- 面板小件

    /** 一行等宽的可点文字（展开 / 收起 / 键盘 / 打开 App）。 */
    private fun linkText(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(getColor(R.color.ink))
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { onClick() }
    }

    /** 网格里的一格：白底细线。 */
    private fun panelCard(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.bg_ime_card)
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    /** 右侧操作列的一格：米底无边。 */
    private fun keyText(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.bg_ime_key)
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    /** 等宽横排，每一格均分。 */
    private fun rowOf(vararg cells: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, v) in cells.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(8)
            addView(v, lp)
        }
    }

    /**
     * 同上，但入口是 List。
     *
     * 变长的那一行（风格）数量由数据决定，用 vararg 得先 `toTypedArray()` 再 spread，
     * 多一层容易写错；`List<View>` 还能让 `List<TextView>` 直接传进来（协变）。
     */
    private fun rowOf(cells: List<View>): LinearLayout = rowOf(*cells.toTypedArray())

    /** 顶部页签：只有文字，选中态靠字重和颜色。 */
    private fun tabLabel(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        letterSpacing = 0.1f
        setTextColor(getColor(R.color.ink_faint))
        setPadding(0, dp(7), 0, dp(7))
        setOnClickListener { onClick() }
    }

    /**
     * 第 2 页的一个可选项。
     *
     * @param isOn 读**当前的真值**，不是点下那一刻的快照 ——
     *   所以点完只要整体重刷一遍就够，不用逐个手动维护状态。
     */
    private fun chip(label: String, isOn: () -> Boolean, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener {
                onClick()
                restyleChips()
            }
        }
        chips.add(v)
        chipOn.add(isOn)
        applyChipStyle(v, isOn())
        return v
    }

    /** 选中 = 白底细线（像一张卡），未选 = 米底无边（像一块纸）。 */
    private fun applyChipStyle(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.bg_ime_card else R.drawable.bg_ime_key)
        v.setTextColor(getColor(if (on) R.color.ink else R.color.ink_soft))
        v.typeface = if (on) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        } else {
            android.graphics.Typeface.DEFAULT
        }
    }

    /**
     * 把所有可选项重新着色。
     *
     * 用两个平行的 List（View + 取值函数）而不是往 `View.tag` 里塞 lambda：
     * tag 是 `Any?`，取出来要强转，写错类型只会在运行时爆；平行 List 是编译期对齐的。
     */
    private fun restyleChips() {
        for (i in chips.indices) {
            applyChipStyle(chips[i], chipOn.getOrNull(i)?.invoke() ?: false)
        }
        // 演练模式一开，风格就不生效了 —— 提示语要立刻改口
        updateHint()
    }

    private fun updateTabs() {
        tabs.forEachIndexed { i, t ->
            val on = i == currentPage
            t.setTextColor(getColor(if (on) R.color.ink else R.color.ink_faint))
            t.typeface = if (on) {
                android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun updateHint() {
        val h = hintView ?: return
        val cfg = AppConfig(this)
        h.text = when {
            // 剪贴板模式下草稿根本不进输入框，提示语必须改口，
            // 否则用户会盯着空输入框等它自己出现（这个坑在 IME 通道踩过多次）。
            cfg.clipboardOnly && currentPage == 0 ->
                "草稿只进剪贴板 · 回聊天页长按输入框粘贴，自己点发送"
            // 面板上没有发送键，这句话是告诉用户"下一步去哪儿"
            currentPage == 0 -> "发送用 App 自带的按钮"
            currentPage == 2 -> "点一句就填进输入框（会替换掉框里现有的）"
            cfg.clipboardOnly -> "剪贴板模式：不写输入框，抓不到输入框的平台用它"
            // 演练模式读的是样例表，所以**人设生效、风格不生效** —— 这两件事别混着说
            cfg.drillMode -> "演练模式：风格不生效（人设仍然生效）"
            else -> "人设是整份替换，风格只改语气"
        }
    }

    private fun showPage(page: Int) {
        pager?.select(page, animate = true)
    }

    /** 面板上只放得下 4 个字，长的截断（完整名字在 App 里改）。 */
    private fun shortName(name: String): String =
        if (name.length <= 4) name else name.take(4)

    /**
     * 切人设。
     *
     * 这一下是**真的换人**：身份/语气和话术三件套一起换（见 [PersonaPresets]）。
     * 所以切完给一句状态，别让人以为只是换了个标签 ——
     * 之后生成的内容会明显不一样。
     */
    private fun switchPersona(p: PersonaPresets.Preset) {
        if (p.id == activePersonaId) return
        if (!PersonaPresets.activate(this, p.id)) {
            setStatus("切换人设失败：写不进 filesDir")
            return
        }
        activePersonaId = p.id
        setStatus("人设 → ${p.name}")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var draftLine: TextView? = null
    /** 草稿行右侧的「待发 N 段」。队列空了就留白。 */
    private var queueLine: TextView? = null

    // ── 分页：0 = 回复动作，1 = 模式 / 风格 ──
    private var pager: SlidePager? = null
    private var tabs: List<TextView> = emptyList()
    /** 第 2 页所有可选项。点一下要整体重新着色，所以得留引用。 */
    private val chips = ArrayList<TextView>()
    /** 与 [chips] 一一对应的"我现在选不选中"，读的是配置当前值。 */
    private val chipOn = ArrayList<() -> Boolean>()
    private var hintView: TextView? = null
    private var currentPage = 0

    /** 第 2 页要显示的人设（最多 4 个）。只在展开时填。 */
    private val personaRow = ArrayList<PersonaPresets.Preset>()

    /** 当前生效的人设 id。就地更新，避免每点一次面板都读一遍档案柜。 */
    private var activePersonaId = ""

    /** 展开 / 收起。换掉整个 input view —— IME 没有"局部刷新"这种说法。 */
    private fun togglePanel() {
        val cfg = AppConfig(this)
        cfg.imePanelExpanded = !cfg.imePanelExpanded
        setInputView(buildPanel())
    }

    private fun draftPreview(): String {
        val t = try {
            currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        return t.ifEmpty { "（空）" }
    }

    private fun refreshDraftLine() {
        draftLine?.text = draftPreview()
        // 队列里有几句还没发，直接写出来。空着就留白 ——
        // 常驻一个「待发 0 段」比不显示更烦人。
        queueLine?.text = com.autoreply.app.core.guard.DraftQueue.size()
            .let { if (it > 0) "待发 $it 段" else "" }
    }

    /**
     * 输入框内容变了就刷新草稿预览。
     *
     * 为什么需要：以前只在「我们自己做了个动作」之后刷新。你点了发送、
     * App 把输入框清空、断句的下一段被填进来 —— 这些都不是我们的动作，
     * 面板上的草稿行就一直停在旧内容上。看起来就像"发完了没有下一条"。
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        refreshDraftLine()
    }

    private fun setStatus(s: String) {
        statusView?.text = s
    }

    // ---------------------------------------------------------------- 面板动作

    /**
     * 生成草稿。
     *
     * @param clearFirst 「换一句」先把我们上次写的那段擦掉，否则会叠成
     *   「怎么说怎么说」—— 和自动写入走同一个 [clearOwnDraft]，不另写一套。
     */
    private fun generate(clearFirst: Boolean) {
        val svc = com.autoreply.app.capture.CaptureService.instance
        if (svc == null) {
            setStatus("读屏服务没开，读不到对话")
            return
        }
        if (clearFirst) currentInputConnection?.let { clearOwnDraft(it) }
        setStatus(if (clearFirst) "换一句…" else "生成中…")

        val cfg = AppConfig(this)
        com.autoreply.app.platform.ReplyRunner(svc, cfg).runOnce(manual = true) { r ->
            // 回调可能在后台线程，碰 View 必须回主线程
            mainHandler.post {
                // 末尾带上"上下文 N 条"。草稿不对时，这是第一个要看的数：
                // 3 条 = 读少了（先长按复制对方那句再换一句）；12 条 = 读到了，问题不在上下文。
                val ctx = com.autoreply.app.capture.CaptureService.lastContextTurns
                val note = r.note.take(28).ifEmpty { "完成" }
                setStatus(if (ctx > 0) "$note · 上下文 $ctx 条" else note)
                refreshDraftLine()
                // commitText 的 selection 回调有时比我们的回调晚一拍，
                // 立刻刷会读到写入前的内容（面板写着旧草稿、框里其实是新的）。
                // 再补刷一次，成本接近零。
                mainHandler.postDelayed({ refreshDraftLine() }, 250L)
            }
        }
    }

    // ---------------------------------------------------------------- 自己打字 / 说话
    //
    // 这两个入口解决的是同一个场景：**模型写的不对，但我自己心里有句话**。
    // 我们这个输入法没有键位，原来的唯一出路是切回系统键盘、打完再切回来 ——
    // 一来一回两次切换，比打字本身还累。

    /**
     * 「话题」页的一行：整宽、可点。
     *
     * 和小格不同 —— 这些是完整句子（十来个字），横排会被截成"平时刷手机都…"，
     * 用户看不出要发什么，所以一列一行，宁可少显示几条。
     */
    private fun topicRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.bg_input)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        setOnClickListener { insertPhrase(text) }
    }

    /** 常用短语的小格子：和设置页输入框同一个底，长句自动截断，不撑破一行。 */
    private fun phraseChip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.bg_input)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
    }

    /**
     * 把一条常用短语插进输入框。
     *
     * 语义是**替换**，不是追加：这句话就是对"模型写的那句"的否决，
     * 接着写会变成「模型那句 + 我这句」连在一起 —— 那是最糟的结果。
     * `commit()` 内部会先擦掉我们自己上一次写的草稿，正好符合这个语义。
     */
    private fun insertPhrase(text: String) {
        val ok = replaceAll(text)
        setStatus(
            when {
                !ok -> "先点一下输入框，再点这句"
                lastFillBackedUp -> "已替换（原内容在剪贴板）：${text.take(10)}"
                else -> "已插入：${text.take(14)}"
            }
        )
        refreshDraftLine()
        mainHandler.postDelayed({ refreshDraftLine() }, 250L)
    }

    /**
     * 切到系统里**另一个**输入法（也就是用户自己的键盘）。
     *
     * 用 [InputMethodService.switchInputMethod]（API 28+）直接切，不再弹选择框；
     * 27 及以下没有这个 API，退回系统选择器 —— 多一步，总比没有强。
     *
     * 回来怎么办：系统键盘上有地球/切换键，点了就能回到我们这儿（我们已注册为可用的输入法）。
     */
    private fun switchToOtherKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val other = imm?.enabledInputMethodList?.firstOrNull { it.packageName != packageName }
        if (other == null) {
            setStatus("系统里没有别的键盘 —— 先装一个，或去设置里启用")
            return
        }
        val label = runCatching { other.loadLabel(packageManager).toString() }
            .getOrDefault("系统键盘")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { switchInputMethod(other.id) }
                .onFailure { imm.showInputMethodPicker() }
        } else {
            imm.showInputMethodPicker()
        }
        setStatus("已切到 $label —— 打完按地球图标切回来")
    }

    /** 只在用户点「说一句」时创建；用系统语音识别，不发音频给任何第三方。 */
    private var recognizer: android.speech.SpeechRecognizer? = null

    private val voiceListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            setStatus("听着… 说完自动填进输入框")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            setStatus("识别中…")
        }

        override fun onError(error: Int) {
            // 5 = 没听清，7 = 超时，9 = 缺权限。都给同一句可操作的提示，
            // 因为用户能做的只有"再说一次"或者"换键盘自己打"。
            setStatus("没听清（$error）—— 再说一次，或点「换键盘」自己打")
        }

        override fun onResults(results: android.os.Bundle?) {
            val text = results
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) {
                setStatus("没听清，再说一次")
                return
            }
            // 用户说的这句就是要替换掉框里原有的内容（包括模型那句），
            // 所以走 replaceAll 而不是"接着写"。
            val ok = replaceAll(text)
            setStatus(if (ok) "已填入：${text.take(16)}" else "先点一下输入框，再说一次")
            refreshDraftLine()
            mainHandler.postDelayed({ refreshDraftLine() }, 250L)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {}

        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    /**
     * 语音转文字，直接落进输入框。
     *
     * 为什么用 SpeechRecognizer 而不是系统那个识别界面（ACTION_RECOGNIZE_SPEECH）：
     * 后者会拉起一个 Activity，把焦点从聊天框抢走 —— 输入法这时候已经没有
     * InputConnection 了，识别完根本写不回去，只能挂起等用户再点一次。
     * SpeechRecognizer 是我们自己起、自己收，不切界面，说话期间焦点一直在。
     *
     * 代价：要 RECORD_AUDIO 权限。没授权就跳去 App 里要一次，回来再点。
     */
    private fun startVoiceInput() {
        val need = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (need) {
            setStatus("语音需要麦克风权限 —— 去 App 里点一下授权")
            runCatching {
                startActivity(
                    android.content.Intent(this, com.autoreply.app.ui.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("ask_mic", true)
                )
            }
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            // 常见原因（跟机型强相关）：国产 ROM 往往只有"键盘自带的语音"，
            // 而没有系统级的识别服务 —— 键盘那个是自己接的云端，帮不了我们。
            // 所以给的办法不是"去装一个"，而是"借键盘的语音"：切过去说完再切回来。
            setStatus("没有系统语音服务 —— 点「换键盘」，用键盘自带的语音，说完切回来")
            return
        }
        val r = recognizer ?: runCatching {
            android.speech.SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceListener)
                recognizer = it
            }
        }.getOrNull()
        if (r == null) {
            setStatus("语音启动失败 —— 换键盘自己打吧")
            return
        }
        setStatus("正在启动语音…")
        runCatching {
            r.startListening(
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        }.onFailure { setStatus("语音启动失败 —— 换键盘自己打吧") }
    }

    /**
     * 「用开场白」：写一条开场白进输入框。
     *
     * 开了「多发开场白」时写第一条，并把后两条放进队列 ——
     * 你发出第一条，CaptureService 检测到之后自动填第二条，依此类推。
     * 仍然是"一条一条来"，不会一次全灌进输入框。
     */
    private fun useGreetingHook() {
        val cfg = AppConfig(this)
        // 面板里拿不到昵称/原话，带槽位的那几条填不上 → 只挑纯文本的
        val multi = if (cfg.greetingMulti) {
            cfg.greetingMultiLines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("{") }
        } else {
            emptyList()
        }
        if (multi.isNotEmpty()) {
            commitGreeting(multi.first())
            val rest = multi.drop(1)
            if (rest.isEmpty()) return
            com.autoreply.app.core.guard.DraftQueue.reset(rest)
            com.autoreply.app.capture.CaptureService.instance?.scheduleNextSegment()
            setStatus("已写入第 1 条，发出后自动填下一条（共 ${multi.size} 条）")
            refreshDraftLine()
            return
        }

        val lines = cfg.greetingLines.ifEmpty {
            com.autoreply.app.core.llm.GreetingHooks.DEFAULT
        }
        val pick = lines.filter { !it.contains("{") }.randomOrNull()
        if (pick == null) {
            setStatus(
                if (cfg.greetingMulti) {
                    "多发的三条都带槽位或为空 —— 面板里填不上，改用单条库"
                } else {
                    "开场白库里没有可用的纯文本条目"
                }
            )
            return
        }
        commitGreeting(pick)
    }

    /** 清掉我们自己的草稿再写入，避免和上一次的句子叠在一起。 */
    private fun commitGreeting(text: String) {
        // 整框替换：点「用开场白」就是要发这条，框里原有的内容留在前面会变成口头禅叠句
        if (!replaceAll(text)) {
            setStatus("输入框没绑上，先点一下输入框")
            return
        }
        setStatus("已写入：${text.take(12)}")
        refreshDraftLine()
        mainHandler.postDelayed({ refreshDraftLine() }, 250L)
    }

    /** 「复制草稿」：把框里现有的内容放进剪贴板（带回读校验）。 */
    private fun copyCurrentDraft() {
        val t = try {
            currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (t.isEmpty()) {
            setStatus("框里是空的")
            return
        }
        setStatus(if (copyDraft(t)) "已复制：${t.take(12)}" else "剪贴板没写进去")
    }

    private fun pasteFromClipboard() {
        val ic = currentInputConnection ?: return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            .orEmpty()
        if (text.isEmpty()) {
            setStatus("剪贴板是空的")
            return
        }
        clearOwnDraft(ic)
        ic.commitText(text, 1)
        lastCommitted = text
        setStatus("已粘贴：${text.take(12)}")
        refreshDraftLine()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = try {
            ic.getSelectedText(0)
        } catch (_: Exception) {
            null
        }
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        refreshDraftLine()
    }

    /** 「清空」：全清。这是用户明确点的，不区分"是不是我们写的"。 */
    private fun clearAll() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(200, 200)
        lastCommitted = null
        setStatus("已清空")
        refreshDraftLine()
    }

    /** 手动切到另一个平台（同城 ↔ 心遇）。一次点一下，不做自动轮转。 */
    private fun switchPlatform() {
        val svc = com.autoreply.app.capture.CaptureService.instance
        if (svc == null) {
            setStatus("读屏服务没开，切不了")
            return
        }
        setStatus(svc.switchToNextPlatform())
    }

    private fun openApp() {
        runCatching {
            startActivity(
                android.content.Intent().apply {
                    setClassName(packageName, "com.autoreply.app.ui.MainActivity")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { setStatus("打不开主界面") }
    }


    /**
     * 让用户换一个键盘。
     *
     * **不能只用 `switchToNextInputMethod`**：它依赖"系统里确实还存在下一个已启用的
     * 输入法"，条件不成立时返回 false 且什么也不做 —— 表现就是"点了没反应"。
     *
     * 所以先试切换，失败就弹系统的输入法选择器。选择器是系统 UI，
     * 只要系统里启用了两个以上输入法就一定列得出来，比"切下一个"可靠得多。
     */
    private fun pickAnotherKeyboard() {
        val switched = try {
            switchToNextInputMethod(false)
        } catch (_: Exception) {
            false
        }
        if (switched) return

        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {
            // 连选择器都弹不出来（极少数 ROM），至少给用户一句解释
            statusView?.text = "这台机器不允许程序切换输入法，请去系统设置里改"
        }
    }

    /**
     * 输入框获得焦点时系统会调这里。
     *
     * **这是最关键的时机**：用户点一下输入框 → 系统绑定输入法 → 这里被调用 →
     * 我们把挂起的草稿写进去。整个过程不需要任何点击注入。
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 延时 350ms 再写，不是立刻写。
        // 有些 App 在输入框获得焦点的同一帧里会先清空内容（`setText("")`），
        // 立刻 commit 进去的内容会被那次清空吞掉 —— 表现就是
        // 界面提示"已写入"，但输入框里空的，偶尔发生、很难复现。
        mainHandler.postDelayed({ flushPending() }, 350L)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 先记下"现在连的是哪个 App 的输入框"，commit 前要拿它做校验
        boundPkg = info?.packageName?.toString().orEmpty()
        // 设置里改过常用短语 → 重建面板，否则要等输入法被系统重启才看得到
        val sig = AppConfig(this).quickPhrases.joinToString("\u241F")
        if (sig != phraseSig) {
            phraseSig = sig
            runCatching { setInputView(buildPanel()) }
        }
        flushPending()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 收键盘就当断开了：下一次绑定之前不该再往里写字
        if (finishingInput) boundPkg = ""
    }

    /** 当前绑定的包名（可能是空串 = 未知）。给 TextWriter 判断"是不是写错地方了"。 */
    fun boundPackage(): String = boundPkg

    /**
     * 把挂起的文本写进当前输入框。
     *
     * 用 `commitText` 而不是按键事件：对 App 来说这就是"输入法上屏了一个词"，
     * 和用户自己打字完全同一条路径 —— 这是它能绕过"输入框不可见"的原因，
     * 也是它比 `ACTION_SET_TEXT` 更像真人的原因。
     */
    private fun flushPending() {
        val pending = pendingText ?: return

        // 过期就丢弃。这一行修的是一个真实事故：
        // 会话 A 里排了一份草稿但输入框没绑上；用户切到会话 B 点输入框，
        // B 的 onStartInput 一触发就把 A 的话写了进去 ——
        // 看起来像"粘贴到了另一个会话"，实际上是残留的 pending。
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) {
            pendingText = null
            pendingPkg = ""
            statusView?.text = "草稿已过期（换过会话），丢弃"
            return
        }

        // 挂在 A 的草稿，不能因为 B 的输入框抢到焦点就写进 B。
        // 只有当两边都知道包名、而且对不上时才等 —— 未知时保持旧行为（照写）。
        val want = pendingPkg
        if (want.isNotEmpty() && boundPkg.isNotEmpty() && want != boundPkg) return

        val ic = currentInputConnection ?: return
        clearOwnDraft(ic)
        ic.commitText(pending, 1)
        lastCommitted = pending
        pendingText = null
        pendingPkg = ""
        statusView?.text = "已写入：${pending.take(16)}"
        verifyWritten(pending)
    }

    /**
     * 写完 250ms 回读一次，确认内容真的留在输入框里。
     *
     * `commitText` 不抛异常只代表"调用成功"，不代表内容留住了 ——
     * 有些 App 在焦点建立的那一帧会清空输入框，我们写进去的就被吞掉。
     * 界面显示"已写入"但框里空的，就是这么来的。
     *
     * 回读用 `getTextBeforeCursor`：输入法这条通道**能读到自己写的内容**，
     * 这是它比无障碍强的地方（那边输入框压根不在无障碍树里）。
     *
     * 确认被清空时把草稿放进剪贴板 —— 至少用户手里有一份能粘。
     */
    private fun verifyWritten(text: String) {
        mainHandler.postDelayed({
            val ic = currentInputConnection ?: return@postDelayed
            val before = try {
                ic.getTextBeforeCursor(text.length + 4, 0)?.toString()
            } catch (_: Exception) {
                null
            }
            when {
                // null = 这个输入框不支持回读，没法验证。不报错，只说明情况
                before == null -> statusView?.text = "已写入（无法回读验证）"
                before.isEmpty() -> {
                    // 框里已经没了：我们记的那份也失效了，
                    // 不清掉的话下次会拿它去比对、白删
                    lastCommitted = null
                    val ok = copyDraft(text)
                    // 必须带上草稿开头：用户唯一能判断"这是不是刚写的那条"的依据。
                    // 原来只说"草稿已放剪贴板"，粘出来是旧话术时完全没法分辨
                    // 是没写进剪贴板、还是自己在历史里点错了。
                    statusView?.text = if (ok) {
                        "被 App 清空了 · 剪贴板：${text.take(12)}"
                    } else {
                        "被 App 清空了 · 剪贴板没写进去（被别的 App 占用？）"
                    }
                }
                else -> statusView?.text = "已写入 ${text.take(12)}"
            }
        }, 250L)
    }

    /**
     * 写剪贴板并**回读校验**。
     *
     * 原来这里直接 `setPrimaryClip` 就当成功了。但失败是不抛异常的：
     * Android 10+ 限制后台应用写剪贴板；部分 ROM 的剪贴板历史满（150 条）后也会拒收。
     * 结果就是界面写着"草稿已放剪贴板"，用户长按粘出来的却是上一条旧话术 ——
     * 看起来像"改了设置不生效"，实际是这条草稿压根没进去。
     */
    private fun copyDraft(text: String): Boolean {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return false
        return try {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AutoReply", text))
            val back = cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
            back == text
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 直接写入。返回是否成功。
     *
     * 如果当前没有绑定输入框（`currentInputConnection` 为空），
     * 就只把文本挂起，等 `onStartInput` 时再写 —— 也就是等用户点输入框。
     */
    /**
     * @param replaceFirst 先清空再写。
     *
     * 断句的第二段必须用它：`commitText` 是**在光标处插入**，不覆盖。
     * 上一次那段如果还留在输入框里（App 没清、或者清了但没刷新），
     * 直接 commit 就会变成「上一段 + 这一段」连在一起 ——
     * 真机现象：输入框里出现「突然有点想认识你。在吗？」，但上面已经发过一条了。
     */
    fun commit(text: String, replaceFirst: Boolean = false, expectedPkg: String? = null): Boolean {
        val ic = currentInputConnection
        // 绑定校验：只往"该写的那个 App"里写。
        // 绑在别处时**挂起而不是硬写** —— 写进去等于污染另一个输入框，
        // 而且目标框仍是旧内容，日志却报成功，这是最难自查的一类错。
        if (!expectedPkg.isNullOrEmpty() && boundPkg.isNotEmpty() && boundPkg != expectedPkg) {
            pendingText = text
            pendingPkg = expectedPkg
            pendingAt = System.currentTimeMillis()
            return false
        }
        if (ic == null) {
            pendingText = text
            pendingPkg = expectedPkg.orEmpty()
            pendingAt = System.currentTimeMillis()
            return false
        }
        // 只删光标两侧各 120 字符：草稿本来就短，
        // 这个范围足够清掉上一段，又不会误删更早的对话内容
        if (replaceFirst) ic.deleteSurroundingText(120, 120) else clearOwnDraft(ic)
        ic.commitText(text, 1)
        lastCommitted = text
        pendingText = null
        pendingPkg = ""
        verifyWritten(text)
        return true
    }

    /**
     * 整框替换：不管框里原来是什么，都换成 [text]。
     *
     * 什么时候用：**用户亲手点的动作** —— 点常用短语、点「用开场白」、说一句。
     * 那一刻"我要发的是这句"是明确的，旧内容要是留在前面，就变成
     * 「11先聊两句吧，我这边不急」（真机截图确认过）。
     *
     * 什么时候**不能**用：自动填入 / 自动触发。那条路上框里可能是用户自己
     * 打了一半的话（或者平台预填的招呼），替他清掉是不可接受的 ——
     * 自动那条路仍然只走 [clearOwnDraft]。
     *
     * 覆盖前把原内容放进剪贴板：误点一下至少还能粘回来。
     */
    fun replaceAll(text: String): Boolean {
        val ic = currentInputConnection ?: run {
            pendingText = text
            pendingPkg = ""
            pendingAt = System.currentTimeMillis()
            return false
        }
        val before = try {
            ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val after = try {
            ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val total = before.length + after.length
        // 原来的内容不是我们写的那段 → 先备份再覆盖（是我们自己的草稿就不用备份）
        val ours = lastCommitted?.let { before.endsWith(it) } == true
        lastFillBackedUp = total > 0 && !ours && copyDraft(before + after)
        if (total > 0) {
            runCatching { ic.setSelection(0, total) }
                .onFailure { ic.deleteSurroundingText(before.length, after.length) }
        }
        ic.commitText(text, 1)
        lastCommitted = text
        pendingText = null
        pendingPkg = ""
        verifyWritten(text)
        return true
    }

    /**
     * 擦掉**上一次我们自己写的那段**，如果它还在框里。
     *
     * 判定刻意克制：光标前的内容必须以 [lastCommitted] 结尾才删。
     * 用户点了发送（App 把输入框清空）、或者他自己接着打了字，都不满足这个条件 ——
     * 那时候一个字都不会动。设计上的底线是"绝不覆盖用户自己打的字"，
     * 这条规则同时满足了它和"连点不要叠加"。
     */
    private fun clearOwnDraft(ic: android.view.inputmethod.InputConnection) {
        val last = lastCommitted?.takeIf { it.isNotEmpty() } ?: return
        val before = try {
            ic.getTextBeforeCursor(last.length + 8, 0)?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        if (!before.endsWith(last)) return
        ic.deleteSurroundingText(last.length, 0)
    }

    companion object {
        @Volatile
        var instance: AutoReplyImeService? = null
            private set

        /** 挂起待写入的文本。没有绑定输入框时先放这里。 */
        @Volatile
        var pendingText: String? = null

        /**
         * 这份 pending 是给哪个 App 的（空 = 不限制，保持旧行为）。
         *
         * 有了它，flushPending 才能拒绝"写进刚抢到焦点的另一个 App"——
         * 输入框之间串话比丢一条草稿严重得多。
         */
        @Volatile
        var pendingPkg: String = ""

        /** 挂起时刻。用来判断这份草稿是不是已经属于上一个会话了。 */
        @Volatile
        var pendingAt: Long = 0L

        /**
         * 挂起草稿的有效期。
         *
         * 90 秒足够用户从"切回目标 App"走到"点开输入框"；
         * 再久基本可以断定他换了会话，那份草稿不该再写出来。
         */
        private const val PENDING_TTL_MS = 90_000L

        fun hasPending(): Boolean = pendingText != null
    }
}

/**
 * 两页的横向滑动容器（零依赖的 ViewPager 替代品）。
 *
 * 为什么不用 `HorizontalScrollView`：它的页宽得自己按屏幕宽算，
 * 分屏 / 横屏下就对不齐，会露出下一页的一条边；而且它不会"吸附"到整页。
 * 这里每页就是 `MATCH_PARENT`，宽度由父容器实测，天然对齐。
 *
 * 为什么不做跟手位移：面板只有两页，跟手滑动给不了额外信息，
 * 反而要在每个 ACTION_MOVE 里改一次布局。松手换页足够。
 */
private class SlidePager(context: android.content.Context) : FrameLayout(context) {

    /** 换页后回调（页签着色、底部提示语都挂在这上面）。 */
    var onPage: ((Int) -> Unit)? = null

    /** 当前页号。外部只读 —— 换页必须走 [select]，否则动画和页签会脱节。 */
    var index: Int = 0
        private set

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var stealing = false

    fun select(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, maxOf(childCount - 1, 0))
        if (target == index) {
            applyNow()
            return
        }
        val from = index
        index = target
        onPage?.invoke(target)
        // 还没测过宽度（第一次布局前）就没法算位移，直接切
        if (!animate || width == 0) {
            applyNow()
            return
        }
        val incoming = getChildAt(target)
        val outgoing = getChildAt(from)
        if (incoming == null || outgoing == null) {
            applyNow()
            return
        }
        val w = width * 0.55f
        val forward = target > from

        incoming.animate().cancel()
        outgoing.animate().cancel()

        incoming.translationX = if (forward) w else -w
        incoming.alpha = 0f
        incoming.visibility = VISIBLE
        incoming.animate().translationX(0f).alpha(1f).setDuration(160L).start()

        outgoing.animate()
            .translationX(if (forward) -w else w)
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                outgoing.visibility = GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()
    }

    /** 无动画地把当前页摆好（也是"两页只留一页可见"的唯一保证）。 */
    private fun applyNow() {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            v.animate().cancel()
            v.visibility = if (i == index) VISIBLE else GONE
            v.translationX = 0f
            v.alpha = 1f
        }
    }

    /**
     * 高度取**两页里更高的那个**，不管当前显示哪一页。
     *
     * 两页的行数不一样（第 2 页多一行「人设」）。按默认实现，切页时容器高度会跟着变，
     * 整个面板跟着跳一下 —— 手指下面的按钮也会移位，很容易点错。
     * 这里把隐藏的那页也量一遍，取较大值当自己的高度。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val childW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        var maxH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i) ?: continue
            val was = c.visibility
            if (was != VISIBLE) c.visibility = VISIBLE
            c.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    childW, android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED,
                ),
            )
            if (c.measuredHeight > maxH) maxH = c.measuredHeight
            if (was != VISIBLE) c.visibility = was
        }
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(maxH + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                stealing = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 只在「明显横向」时才接管。竖划、斜着点、长按，
                // 一律还给子 View —— 否则网格里的按钮会变得点不动。
                if (!stealing &&
                    kotlin.math.abs(dx) > slop * 1.5f &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                ) {
                    stealing = true
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> {
                // 按整段位移判定，不看松手瞬间的速度：
                // 输入法面板上的划动通常又短又快，速度阈值在真机上时灵时不灵。
                val dx = ev.x - downX
                if (stealing) {
                    if (dx < -slop * 2 && index < childCount - 1) {
                        select(index + 1)
                    } else if (dx > slop * 2 && index > 0) {
                        select(index - 1)
                    }
                }
                stealing = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> stealing = false
            else -> Unit
        }
        return true
    }
}
