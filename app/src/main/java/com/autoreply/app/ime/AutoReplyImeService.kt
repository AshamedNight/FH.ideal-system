package com.autoreply.app.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.view.Gravity
import android.widget.LinearLayout
import com.autoreply.app.R
import com.autoreply.app.core.AppConfig

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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
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
    override fun onCreateInputView(): View = buildPanel()

    /**
     * 输入法面板。
     *
     * 折叠态只有一行（跟原来一样高），展开态约 300dp：
     * **左边是「生成什么」，右边是「对输入框做什么」**——
     * 这个分区是参考图里最值得学的一点，拇指不用在两种语义之间来回找。
     *
     * 刻意**没有**的东西（不是漏了）：
     *   - 发送按钮：项目铁律是"发送永远由人点"，而且放在面板上会把
     *     "写入→发送"的时间差压到两次相邻点击，人来不及看对方刚说了什么
     *   - 连点 / 切换会话：跨会话批量动作是"脚本"最典型的形状，
     *     不能因为"顺手"就把它搬到输入法上
     */
    private fun buildPanel(): View {
        val cfg = AppConfig(this)
        val expanded = cfg.imePanelExpanded

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

        // ── 草稿预览：让用户知道框里现在是什么，不用切回聊天页确认 ──
        val draft = TextView(this).apply {
            text = draftPreview()
            textSize = 11f
            setTextColor(getColor(R.color.ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        draftLine = draft
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
            }
        )

        // ── 主体：左网格（生成什么） + 右列（对输入框做什么） ──
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
            // 第三行通栏：面板上看不到判定链，所以留一个回 App 的入口
            addView(
                panelCard("打开 App · 看判定链") { openApp() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
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

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(6))
                addView(
                    left,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(right, rightLp)
            }
        )

        // ── 底部：一句提醒（不用占一行按钮位） ──
        root.addView(
            TextView(this).apply {
                text = "发送仍然由你点"
                textSize = 11f
                setTextColor(getColor(R.color.ink_faint))
                setPadding(dp(12), 0, dp(12), dp(10))
            }
        )

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var draftLine: TextView? = null

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
                setStatus(r.note.take(28).ifEmpty { "完成" })
                refreshDraftLine()
            }
        }
    }

    /** 「用开场白」：从钩子库里挑一条不带槽位的写进去。 */
    private fun useGreetingHook() {
        val cfg = AppConfig(this)
        val lines = cfg.greetingLines.ifEmpty {
            com.autoreply.app.core.llm.GreetingHooks.DEFAULT
        }
        // 面板里拿不到昵称/原话，带槽位的那几条填不上 → 只挑纯文本的
        val pick = lines.filter { !it.contains("{") }.randomOrNull()
        if (pick == null) {
            setStatus("开场白库里没有可用的纯文本条目")
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            setStatus("输入框没绑上，先点一下输入框")
            return
        }
        clearOwnDraft(ic)
        ic.commitText(pick, 1)
        lastCommitted = pick
        setStatus("已写入：${pick.take(12)}")
        refreshDraftLine()
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
        flushPending()
    }

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
            statusView?.text = "草稿已过期（换过会话），丢弃"
            return
        }

        val ic = currentInputConnection ?: return
        clearOwnDraft(ic)
        ic.commitText(pending, 1)
        lastCommitted = pending
        pendingText = null
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
    fun commit(text: String, replaceFirst: Boolean = false): Boolean {
        val ic = currentInputConnection
        if (ic == null) {
            pendingText = text
            pendingAt = System.currentTimeMillis()
            return false
        }
        // 只删光标两侧各 120 字符：草稿本来就短，
        // 这个范围足够清掉上一段，又不会误删更早的对话内容
        if (replaceFirst) ic.deleteSurroundingText(120, 120) else clearOwnDraft(ic)
        ic.commitText(text, 1)
        lastCommitted = text
        pendingText = null
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
