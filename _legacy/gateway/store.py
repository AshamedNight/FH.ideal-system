#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
store.py — 用 SQLite 兜住"重启就丢"的状态

为什么必须做
------------
到目前为止，待确认队列、已发送记录、信任额度全在内存里。
进程一重启，结果是：

  · 待确认队列清空 —— 运营以为处理完了，其实一条都没发
  · 已发送记录清空 —— 无法回答"上周那条评论回了没有"
  · 信任额度清零 —— 分级放权退回到全人工，而且没人知道为什么

这不是"以后再说"的问题，是"部署上去第一天就会遇到"的问题。
后台服务重启是常态：改配置、发版本、机器重启、容器漂移。

选型
----
SQLite，不用 Postgres/MySQL：
  · 单文件、零部署，拷走就是备份
  · 写入量级是每天几百到几千条，SQLite 绰绰有余
  · 真要换库，改这个文件一个地方

并发
----
控制台的 HTTP 服务是多线程的，所以连接用 check_same_thread=False，
所有写操作串行化在一把锁里。SQLite 本身支持多读单写，
再加一层锁是为了避免"两个线程同时 approve 同一条"。

依赖：仅标准库。
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path

from gateway import Reply

SCHEMA = """
CREATE TABLE IF NOT EXISTS seen (
    id   TEXT PRIMARY KEY,
    ts   REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS queue (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    channel    TEXT NOT NULL,
    conv_id    TEXT NOT NULL,
    text       TEXT NOT NULL DEFAULT '',
    thread     TEXT NOT NULL DEFAULT '',
    sender     TEXT NOT NULL DEFAULT '',
    incoming   TEXT NOT NULL DEFAULT '',
    reason     TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL,
    status     TEXT NOT NULL DEFAULT 'pending'
);

CREATE TABLE IF NOT EXISTS sent (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    channel  TEXT NOT NULL,
    conv_id  TEXT NOT NULL,
    text     TEXT NOT NULL,
    thread   TEXT NOT NULL DEFAULT '',
    sender   TEXT NOT NULL DEFAULT '',
    incoming TEXT NOT NULL DEFAULT '',
    auto     INTEGER NOT NULL DEFAULT 0,
    sent_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS kv (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_queue_status ON queue(status, id);
CREATE INDEX IF NOT EXISTS idx_sent_time ON sent(sent_at);
CREATE INDEX IF NOT EXISTS idx_seen_ts ON seen(ts);
"""


class StoreSeen:
    """
    把 Store 的去重表包装成 SeenStore 的接口。

    适配器只要求对象有 seen(cid) / mark(cid)，不关心背后是文件还是数据库。
    有了这一层，重启后不会把已经回过的评论再回一遍——
    这是纯文件方案做不到的（文件按 30 天滚动，重启不动它，但多进程会写坏）。
    """

    def __init__(self, store: Store):
        self.store = store

    def seen(self, cid: str) -> bool:
        return self.store.seen(cid)

    def mark(self, cid: str) -> None:
        self.store.mark_seen(cid)

    def __len__(self) -> int:
        return self.store.seen_count()


class Store:
    """持久化层。所有方法都是线程安全的。"""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(
            str(self.path), check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            # WAL 让读不阻塞写。控制台高频轮询状态时很有用。
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ---------- 去重 ----------

    def seen(self, mid: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM seen WHERE id = ?", (str(mid),)).fetchone()
        return row is not None

    def mark_seen(self, mid: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO seen(id, ts) VALUES (?, ?)",
                (str(mid), time.time()))

    def seen_count(self) -> int:
        with self._lock:
            return int(self._conn.execute(
                "SELECT COUNT(*) FROM seen").fetchone()[0])

    def prune_seen(self, keep_days: float = 30.0) -> int:
        """
        清掉过老的去重记录。

        不能不清：一条两年前的评论 id 留在表里没有意义，
        而且会让「已处理」这个数字缓慢膨胀到无法解释。
        """
        cutoff = time.time() - keep_days * 86400
        with self._lock:
            cur = self._conn.execute("DELETE FROM seen WHERE ts < ?", (cutoff,))
        return int(cur.rowcount or 0)

    # ---------- 待确认队列 ----------

    def enqueue(self, reply: Reply) -> int:
        with self._lock:
            cur = self._conn.execute(
                """INSERT INTO queue
                   (channel, conv_id, text, thread, sender, incoming, reason,
                    created_at, status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')""",
                (reply.channel, reply.conv_id, reply.text, reply.thread,
                 reply.sender, reply.incoming, reply.reason, time.time()))
            return int(cur.lastrowid)

    def load_pending(self) -> list[tuple[int, Reply]]:
        """返回 [(队列 id, Reply)]，按入队顺序。"""
        with self._lock:
            rows = self._conn.execute(
                """SELECT * FROM queue WHERE status = 'pending'
                   ORDER BY id""").fetchall()
        out: list[tuple[int, Reply]] = []
        for r in rows:
            out.append((int(r["id"]), Reply(
                channel=r["channel"], conv_id=r["conv_id"], text=r["text"],
                approved=False, reason=r["reason"], thread=r["thread"],
                sender=r["sender"], incoming=r["incoming"])))
        return out

    def resolve(self, queue_id: int, status: str) -> bool:
        """
        把一条队列项标记为已处理。

        用 status = 'pending' 做条件更新：两个线程同时 approve 同一条时，
        只有一个会成功。少了这个条件，同一条评论会被发两次。
        """
        if status not in ("approved", "rejected"):
            raise ValueError(f"非法状态：{status}")
        with self._lock:
            cur = self._conn.execute(
                "UPDATE queue SET status = ? WHERE id = ? AND status = 'pending'",
                (status, int(queue_id)))
        return bool(cur.rowcount)

    def pending_count(self) -> int:
        with self._lock:
            return int(self._conn.execute(
                "SELECT COUNT(*) FROM queue WHERE status = 'pending'").fetchone()[0])

    # ---------- 已发送 ----------

    def record_sent(self, reply: Reply, auto: bool = False) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO sent
                   (channel, conv_id, text, thread, sender, incoming, auto, sent_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (reply.channel, reply.conv_id, reply.text, reply.thread,
                 reply.sender, reply.incoming, 1 if auto else 0, time.time()))

    def recent_sent(self, limit: int = 20) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM sent ORDER BY id DESC LIMIT ?", (int(limit),)
            ).fetchall()
        return [dict(r) for r in rows]

    def sent_count(self, since: float | None = None) -> int:
        with self._lock:
            if since is None:
                return int(self._conn.execute(
                    "SELECT COUNT(*) FROM sent").fetchone()[0])
            return int(self._conn.execute(
                "SELECT COUNT(*) FROM sent WHERE sent_at >= ?", (since,)).fetchone()[0])

    # ---------- 键值（信任额度等） ----------

    def get_kv(self, key: str, default=None):
        with self._lock:
            row = self._conn.execute(
                "SELECT v FROM kv WHERE k = ?", (key,)).fetchone()
        if row is None:
            return default
        try:
            return json.loads(row["v"])
        except (json.JSONDecodeError, TypeError):
            return default

    def set_kv(self, key: str, value) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO kv(k, v) VALUES (?, ?) "
                "ON CONFLICT(k) DO UPDATE SET v = excluded.v",
                (key, json.dumps(value, ensure_ascii=False)))

    # ---------- 运维 ----------

    def reset(self, keep_credit: bool = True) -> None:
        """
        清空业务数据。默认保留信任额度——
        额度是对系统能力的长期判断，不该因为换了一批测试数据就清零。
        """
        with self._lock:
            self._conn.execute("DELETE FROM seen")
            self._conn.execute("DELETE FROM queue")
            self._conn.execute("DELETE FROM sent")
            if not keep_credit:
                self._conn.execute("DELETE FROM kv WHERE k = 'credit'")

    def stats(self) -> dict:
        with self._lock:
            q = {r["status"]: int(r["n"]) for r in self._conn.execute(
                "SELECT status, COUNT(*) AS n FROM queue GROUP BY status")}
            auto = int(self._conn.execute(
                "SELECT COUNT(*) FROM sent WHERE auto = 1").fetchone()[0])
            manual = int(self._conn.execute(
                "SELECT COUNT(*) FROM sent WHERE auto = 0").fetchone()[0])
        return {
            "seen": self.seen_count(),
            "pending": q.get("pending", 0),
            "approved": q.get("approved", 0),
            "rejected": q.get("rejected", 0),
            "sent_auto": auto,
            "sent_manual": manual,
        }
