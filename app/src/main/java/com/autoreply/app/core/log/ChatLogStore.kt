package com.autoreply.app.core.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机聊天记录：把「程序实际读到的对话」和「生成过的草稿」追加成 txt。
 *
 * ## 为什么记的是"读到的"，不是"真实的"
 *
 * 程序只在你打开会话、且它读了一次界面时才知道对话内容。所以：
 * - 没打开过的会话没有记录；
 * - 消息的**真实发送时间**拿不到（屏幕上只有"今天 11:02"这种节点，没有可用文本），
 *   记的是**首次读到它的时间**；
 * - 语音 / 图片 / 表情没有文字节点，记不到 —— 导出后可能看到"跳了一句"的错觉。
 *
 * 这三点都写在导出文件头里，避免以后翻记录时误以为是完整聊天史。
 *
 * ## 存哪、留多久
 *
 * `filesDir/chatlog/YYYY-MM-DD.txt`，按天一个文件、追加写，**零权限**。
 * 超过 [KEEP_DAYS] 天自动删（在写入时顺手做，不需要后台任务）。
 * 导出走系统的"保存到文件"（SAF），由用户自己选位置。
 */
object ChatLogStore {

    private const val DIR = "chatlog"
    private const val KEEP_DAYS = 30

    /** 一条待记录的消息。刻意不依赖 platform 包的 ChatMessage —— core 不该反过来依赖 platform。 */
    data class Entry(val text: String, val fromOther: Boolean)

    /**
     * 进程内去重：同一条消息在一次运行里会被读到很多次（判定、填入、连点各读一次），
     * 不去重就会写成几十行重复。
     */
    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
            size > 800
    }

    private var lastCleanupDay = 0L

    /** 目录本身不创建 —— 没打开记录开关的人，不该在私有目录里多一个空文件夹。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name } ?: emptyList()

    fun sizeBytes(context: Context): Long = files(context).sumOf { it.length() }

    /**
     * 记录一次读到的对话。
     *
     * 跨进程重启的去重靠"每个会话最后一条"游标（存 SharedPreferences）：
     * 在新列表里找到那条，只写它后面的。找不到（对话已滚走）就整段重写一次，
     * 进程内 [seen] 能挡住大部分重复。
     */
    fun record(
        context: Context,
        platform: String,
        peer: String,
        messages: List<Entry>,
        now: Long = System.currentTimeMillis(),
    ) {
        val list = messages.filter { it.text.isNotBlank() }
        if (list.isEmpty()) return

        val key = cursorKey(platform, peer)
        val sp = context.getSharedPreferences("chatlog_cursor", Context.MODE_PRIVATE)
        val cursor = sp.getString(key, "").orEmpty()
        val start = if (cursor.isEmpty()) {
            0
        } else {
            val at = list.indexOfLast { mark(it) == cursor }
            if (at >= 0) at + 1 else 0
        }
        if (start >= list.size) return

        val file = dayFile(context, now)
        val tm = hhmm(now)
        val fresh = list.subList(start, list.size)
        // 游标失效（对话滚走了、找不到那条）时只能整段重写，
        // 这时才靠进程内 [seen] 去重；正常情况下按位置续写，
        // **不做**内容去重 —— 否则对方连发两个「哈哈」会被并成一条。
        val rewrite = start == 0 && cursor.isNotEmpty()
        for (e in fresh) {
            val fp = "$platform|$peer|${mark(e)}"
            if (rewrite && seen.put(fp, true) != null) continue
            if (!rewrite) seen[fp] = true
            append(file, "[$tm] ${peer.ifBlank { "未知会话" }} ${if (e.fromOther) "对方" else "我"}：${e.text}")
        }
        sp.edit().putString(key, mark(list.last())).apply()
        cleanup(context, now)
    }

    /**
     * 记录一条生成出来的草稿。
     *
     * 和"我：…"分开写，是为了复盘时能一眼看出哪句是 AI 写的、有没有真的发出去。
     * 是否发送以之后读到的「我：…」为准 —— 那才是真的发出去了。
     */
    fun draft(
        context: Context,
        platform: String,
        peer: String,
        text: String,
        delivery: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (text.isBlank()) return
        val fp = "$platform|$peer|D|${text.take(120)}"
        if (seen.put(fp, true) != null) return
        append(
            dayFile(context, now),
            "[${hhmm(now)}] ${peer.ifBlank { "未知会话" }} 草稿（$delivery）：$text",
        )
        cleanup(context, now)
    }

    /** 导出全文。没有记录时返回空串，调用方负责提示。 */
    fun exportText(context: Context): String {
        val list = files(context)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# AutoReply 聊天记录（本机生成）\n")
        sb.append("# 内容为明文；只包含程序实际读到过的对话，语音/图片/表情不在其中。\n")
        sb.append("# 时间戳是「程序首次读到」的时间，不是消息真实发送时间。\n")
        for (f in list) {
            sb.append("\n# ======== ").append(f.name.removeSuffix(".txt")).append(" ========\n")
            sb.append(runCatching { f.readText() }.getOrDefault(""))
        }
        return sb.toString()
    }

    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
        seen.clear()
        context.getSharedPreferences("chatlog_cursor", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------------------------------------------------------------- 内部

    private fun mark(e: Entry) = (if (e.fromOther) "O|" else "M|") + e.text.take(80)

    private fun cursorKey(platform: String, peer: String) = "c_" + (platform + "|" + peer).hashCode()

    private fun dayFile(context: Context, now: Long): File {
        val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)) + ".txt"
        val d = dir(context).apply { mkdirs() }
        val f = File(d, name)
        if (!f.exists()) {
            runCatching {
                f.writeText(
                    "# 聊天记录 $name\n" +
                        "# 明文存放于本机，保留 $KEEP_DAYS 天后自动清理；导出由你决定。\n"
                )
            }
        }
        return f
    }

    private fun append(file: File, line: String) {
        runCatching { file.appendText(line + "\n") }
    }

    private fun hhmm(now: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))

    /** 写入时顺手清理，一天最多一次 —— 不值得为它开后台任务。 */
    private fun cleanup(context: Context, now: Long) {
        val day = now / 86_400_000L
        if (day == lastCleanupDay) return
        lastCleanupDay = day
        val deadline = now - KEEP_DAYS * 86_400_000L
        files(context).forEach { f ->
            val t = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(f.name.removeSuffix(".txt"))?.time
            }.getOrNull() ?: return@forEach
            if (t < deadline) runCatching { f.delete() }
        }
    }
}
