package com.autoreply.app.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.autoreply.app.core.llm.Turn

/**
 * 会话记忆库：**只存"对方是谁"，不存聊天原文**。
 *
 * ## 为什么是数据库、为什么只有两张表
 *
 * 内存里的 [com.autoreply.app.core.llm.ConversationMemory] 解决"这一段时间内别忘"，
 * 但它一杀进程就没了；而且从会话 A 切到 B、再切回 A 时，只要那一屏读出来的东西
 * 和缓存的最后一条对不上，就会整段重置 —— 用户看到的是"消息明明还在，它却像没见过"。
 *
 * 这里存的是**抽取后的事实**（年龄/城市/职业/婚育/诉求/雷区），
 * 事实不随窗口滚动消失，也不随重启丢失，而且很小 —— 进提示词只要一百多 token。
 *
 * 刻意**不存原文**：聊天内容落盘是另一个量级的隐私问题（"聊天记录"开关默认就是关的）。
 * 重启后丢掉的只是逐字对话，而"她是谁、聊到哪"还在，这才是要守的部分。
 *
 * ## 表
 *
 * - `sessions(pkg, peer, last_active, msg_count)`：一人一行，给"会话记忆"页展示用
 * - `facts(pkg, peer, k, v, confidence, updated_at)`：k 固定几个，值短
 */
object SessionStore {

    private const val DB_NAME = "session_memory.db"
    private const val DB_VER = 1

    private class Helper(ctx: Context) :
        SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VER) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE sessions(
                  pkg TEXT NOT NULL,
                  peer TEXT NOT NULL,
                  last_active INTEGER NOT NULL,
                  msg_count INTEGER NOT NULL,
                  PRIMARY KEY(pkg, peer)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE facts(
                  pkg TEXT NOT NULL,
                  peer TEXT NOT NULL,
                  k TEXT NOT NULL,
                  v TEXT NOT NULL,
                  confidence REAL NOT NULL,
                  updated_at INTEGER NOT NULL,
                  PRIMARY KEY(pkg, peer, k)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 是初版。以后加列/加表在这里按版本号分支，别 drop —— 那是用户数据。
        }
    }

    @Volatile
    private var helper: Helper? = null

    private fun open(ctx: Context): SQLiteDatabase? = runCatching {
        (helper ?: Helper(ctx).also { helper = it }).writableDatabase
    }.getOrNull()

    /**
     * 读：高置信事实，按固定顺序拼成几条短句，直接进提示词。
     *
     * peer 为空直接返回空 —— 读不到昵称就不知道这是谁，写进去会串会话。
     */
    fun digest(ctx: Context, pkg: String, peer: String): List<String> {
        if (pkg.isBlank() || peer.isBlank()) return emptyList()
        val db = open(ctx) ?: return emptyList()
        val pairs = runCatching {
            db.query(
                "facts",
                arrayOf("k", "v"),
                "pkg=? AND peer=? AND confidence>=?",
                arrayOf(pkg, peer, FactExtractor.MIN_CONFIDENCE.toString()),
                null, null, null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getString(0) to c.getString(1))
                }
            }
        }.getOrNull().orEmpty()
        // 顺序固定（年龄→城市→职业…）：前缀稳定，上游的提示词缓存更容易命中
        return FactExtractor.ORDER.mapNotNull { k -> pairs.firstOrNull { it.first == k }?.second }
    }

    /**
     * 写：抽事实 + 记一次会话活跃。
     *
     * 在后台线程调用（[com.autoreply.app.platform.ReplyRunner] 本来就在后台）。
     * 任何异常都吞掉 —— 记忆是增强项，不能因为它让生成失败。
     */
    fun observe(
        ctx: Context,
        pkg: String,
        peer: String,
        turns: List<Turn>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (pkg.isBlank() || peer.isBlank() || turns.isEmpty()) return
        val facts = FactExtractor.extract(turns)
        val db = open(ctx) ?: return
        runCatching {
            db.beginTransaction()
            try {
                db.insertWithOnConflict(
                    "sessions", null,
                    ContentValues().apply {
                        put("pkg", pkg)
                        put("peer", peer)
                        put("last_active", now)
                        put("msg_count", turns.size)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                facts.forEach { f ->
                    db.insertWithOnConflict(
                        "facts", null,
                        ContentValues().apply {
                            put("pkg", pkg)
                            put("peer", peer)
                            put("k", f.key)
                            put("v", f.value)
                            put("confidence", f.confidence)
                            put("updated_at", now)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 会话列表里一行。 */
    data class Session(
        val pkg: String,
        val peer: String,
        val lastActive: Long,
        val msgCount: Int,
    )

    /** 事实一行（含低置信，供「会话记忆」页核对）。 */
    data class FactRow(val k: String, val v: String, val confidence: Float)

    /** 全部会话，按最近活跃倒序。 */
    fun sessions(ctx: Context): List<Session> {
        val db = open(ctx) ?: return emptyList()
        return runCatching {
            db.query(
                "sessions", arrayOf("pkg", "peer", "last_active", "msg_count"),
                null, null, null, null, "last_active DESC",
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Session(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /**
     * 某个人的**全部**事实（不过滤置信度）。
     *
     * 界面和提示词的要求不一样：提示词只该看高置信的，
     * 而人核对时要能看到"它到底记了什么"，包括那些不确定的。
     */
    fun facts(ctx: Context, pkg: String, peer: String): List<FactRow> {
        val db = open(ctx) ?: return emptyList()
        val rows = runCatching {
            db.query(
                "facts", arrayOf("k", "v", "confidence"),
                "pkg=? AND peer=?", arrayOf(pkg, peer), null, null, null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(FactRow(c.getString(0), c.getString(1), c.getFloat(2)))
                }
            }
        }.getOrNull().orEmpty()
        return FactExtractor.ORDER.mapNotNull { k -> rows.firstOrNull { it.k == k } }
    }

    /** 人工改一条：置信度直接给 1.0 —— 人确认过的就是准的。 */
    fun putFact(
        ctx: Context,
        pkg: String,
        peer: String,
        key: String,
        value: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val v = value.trim()
        if (v.isEmpty()) {
            deleteFact(ctx, pkg, peer, key)
            return
        }
        val db = open(ctx) ?: return
        runCatching {
            db.insertWithOnConflict(
                "facts", null,
                ContentValues().apply {
                    put("pkg", pkg)
                    put("peer", peer)
                    put("k", key)
                    put("v", v)
                    put("confidence", 1.0f)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun deleteFact(ctx: Context, pkg: String, peer: String, key: String) {
        val db = open(ctx) ?: return
        runCatching { db.delete("facts", "pkg=? AND peer=? AND k=?", arrayOf(pkg, peer, key)) }
    }

    /** 忘掉某个人（用户手动删、或判定读串了）。 */
    fun forget(ctx: Context, pkg: String, peer: String) {
        val db = open(ctx) ?: return
        runCatching {
            db.delete("facts", "pkg=? AND peer=?", arrayOf(pkg, peer))
            db.delete("sessions", "pkg=? AND peer=?", arrayOf(pkg, peer))
        }
    }

    /** 一键清空（设置页「清空所有会话记忆」）。 */
    fun clear(ctx: Context) {
        val db = open(ctx) ?: return
        runCatching {
            db.delete("facts", null, null)
            db.delete("sessions", null, null)
        }
    }

    /** 会话数，给设置页显示"已记 N 个会话"。 */
    fun sessionCount(ctx: Context): Int {
        val db = open(ctx) ?: return 0
        return runCatching {
            db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)
    }
}
