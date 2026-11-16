#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
scheduler.py — 定时轮询、退避、健康检查、告警

为什么需要
----------
到现在为止，所有验证都是"点一下跑一轮"。部署成后台服务之后，
没有人在旁边点按钮，所以这三件事必须由程序自己回答：

  1. 多久跑一次？（太密会被频控，太疏会错过对话窗口）
  2. 出错了怎么办？（连续失败还按原频率猛撞，等于主动申请封禁）
  3. 出事了谁知道？（token 到期、权限被收回、队列积压，
     这些都不会报错，只会安静地停下来）

设计取舍
--------
· 不用 cron，也不用 APScheduler：一个 while 循环 + Event 就够，
  少一个依赖，也少一个"为什么定时任务没触发"的排查面。
· 退避是指数的，但重置条件是"成功跑完一轮"，不是"没抛异常"。
  因为批量失败经常表现为"接口返回空列表"，异常是空的。
· 告警做去重：同一个问题一小时只喊一次。否则 token 到期会变成
  每 3 分钟一条消息，人就会开始忽略告警——那比不告警更糟。

依赖：仅标准库。
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Callable

SECONDS_PER_DAY = 86400.0


@dataclass
class Alert:
    level: str          # info / warn / critical
    code: str           # 去重键，同一 code 一小时内只发一次
    message: str
    at: float = field(default_factory=time.time)

    def __str__(self) -> str:
        icon = {"info": "·", "warn": "!", "critical": "!!"}.get(self.level, "·")
        return f"[{icon}] {self.message}"


def default_printer(alert: Alert) -> None:
    """默认告警出口：打到标准错误。生产环境换成企业微信/钉钉 webhook。"""
    import sys
    print(str(alert), file=sys.stderr, flush=True)


class Scheduler:
    """
    轮询调度器。

    runner 是一个无参可调用对象，跑一轮、返回处理条数。
    传 Hub.step 或控制台的 run_round 都行——它只要求"调一次 = 跑一轮"。
    """

    def __init__(self, runner: Callable[[], int],
                 adapters: tuple = (),
                 interval: float = 180.0,
                 backoff_max: float = 1800.0,
                 token_warn_days: float = 7.0,
                 queue_warn: int = 50,
                 on_alert: Callable[[Alert], None] | None = None,
                 alert_ttl: float = 3600.0,
                 clock: Callable[[], float] = time.time):
        self.runner = runner
        self.adapters = tuple(adapters)
        self.interval = float(interval)
        self.backoff_max = float(backoff_max)
        self.token_warn_days = float(token_warn_days)
        self.queue_warn = int(queue_warn)
        self.on_alert = on_alert or default_printer
        self.alert_ttl = float(alert_ttl)
        self.clock = clock

        self.failures = 0              # 连续失败轮数
        self.last_ok_at = 0.0
        self.last_error = ""
        self.rounds = 0
        self._alerted: dict[str, float] = {}
        self._stop = threading.Event()

    # ---------- 告警 ----------

    def _alert(self, level: str, code: str, message: str) -> Alert | None:
        """
        发一条告警。返回真正发出的 Alert，被去重时返回 None。

        去重键是 code 而不是文案：同一个问题的文案会随数字变化
        （"还剩 6.9 天"、"还剩 6.8 天"），按文案去重等于没去重。
        """
        now = self.clock()
        last = self._alerted.get(code)
        # 同一问题一小时内只喊一次。重复告警会训练人忽略告警。
        if last is not None and now - last < self.alert_ttl:
            return None
        self._alerted[code] = now
        alert = Alert(level, code, message, now)
        self.on_alert(alert)
        return alert

    # ---------- 健康检查 ----------

    def token_days_left(self) -> float | None:
        """
        所有适配器里最短的令牌剩余天数。没有令牌信息时返回 None。

        取最小值而不是平均值：任何一个账号令牌快过期，
        整个服务就不能算健康。
        """
        worst: float | None = None
        for ad in self.adapters:
            client = getattr(ad, "client", None)
            token = getattr(client, "token", None)
            if token is None:
                continue
            left = (float(token.expires_at) - self.clock()) / SECONDS_PER_DAY
            worst = left if worst is None else min(worst, left)
        return worst

    def adapter_errors(self) -> list[str]:
        out: list[str] = []
        for ad in self.adapters:
            out.extend(getattr(ad, "last_errors", []) or [])
        return out

    def health(self, queue_depth: int | None = None) -> dict:
        days = self.token_days_left()
        return {
            "rounds": self.rounds,
            "failures": self.failures,
            "last_ok_at": self.last_ok_at,
            "last_error": self.last_error,
            "token_days_left": None if days is None else round(days, 2),
            "adapter_errors": self.adapter_errors(),
            "queue_depth": queue_depth,
            "healthy": (self.failures == 0
                        and (days is None or days > 1.0)),
        }

    def check(self, queue_depth: int | None = None) -> list[Alert]:
        """跑一次健康检查，发出必要的告警。返回本轮新增的告警。"""
        found: list[Alert] = []

        def emit(level: str, code: str, msg: str) -> None:
            a = self._alert(level, code, msg)
            if a is not None:
                found.append(a)

        days = self.token_days_left()
        if days is not None:
            if days <= 1.0:
                emit("critical", "token_expiring",
                     f"抖音令牌 {days:.1f} 天后到期，刷新失败将导致整条链路停发")
            elif days <= self.token_warn_days:
                emit("warn", "token_soon",
                     f"抖音令牌还剩 {days:.1f} 天，确认 refresh_token 续期是否正常")

        if self.failures >= 3:
            emit("critical", "rounds_failing",
                 f"连续 {self.failures} 轮拉取失败：{self.last_error[:80]}")

        errors = self.adapter_errors()
        if errors:
            # 只报第一条，避免一屏都是同一个原因
            emit("warn", "adapter_error", f"适配器报错：{errors[0][:100]}")

        if queue_depth is not None and queue_depth >= self.queue_warn:
            emit("warn", "queue_backlog",
                 f"待确认队列积压 {queue_depth} 条，超过阈值 {self.queue_warn}")

        # 恢复正常时清掉失败告警记录，否则下次再出问题不会重新喊
        if self.failures == 0:
            self._alerted.pop("rounds_failing", None)

        return found

    # ---------- 调度 ----------

    def next_delay(self) -> float:
        """
        下一轮该等多久。

        正常是 interval；连续失败时指数退避，封顶 backoff_max。
        第一轮失败不加延迟——偶发超时很常见，立刻重试是对的。
        """
        if self.failures <= 1:
            return self.interval
        exp = min(self.failures - 1, 6)          # 最多 2^6 倍
        return min(self.interval * (2 ** exp), self.backoff_max)

    def tick(self, queue_depth: int | None = None) -> dict:
        """跑一轮 + 健康检查。返回本轮结果。"""
        started = self.clock()
        count = 0
        try:
            count = int(self.runner() or 0)
            self.failures = 0
            self.last_ok_at = self.clock()
            self.last_error = ""
            ok = True
        except Exception as exc:            # noqa: BLE001
            # 单轮异常不能让后台进程退出。记下来，退避，下一轮继续。
            self.failures += 1
            self.last_error = f"{type(exc).__name__}: {exc}"
            ok = False

        self.rounds += 1
        alerts = self.check(queue_depth)
        return {
            "ok": ok,
            "processed": count,
            "failures": self.failures,
            "elapsed": round(self.clock() - started, 3),
            "next_delay": self.next_delay(),
            "alerts": [str(a) for a in alerts if a.message],
        }

    def run_forever(self, queue_depth: Callable[[], int] | None = None,
                    max_rounds: int | None = None) -> None:
        """
        阻塞循环。Ctrl+C 或 stop() 退出。

        max_rounds 用于测试：跑够指定轮数就返回，不用开线程再杀。
        """
        rounds = 0
        while not self._stop.is_set():
            depth = queue_depth() if queue_depth else None
            self.tick(depth)
            rounds += 1
            if max_rounds is not None and rounds >= max_rounds:
                return
            # 用 Event.wait 而不是 sleep：stop() 能立刻打断等待，
            # 否则退出要等到下一个间隔，长间隔时体验很差。
            self._stop.wait(self.next_delay())

    def stop(self) -> None:
        self._stop.set()

    def reset(self) -> None:
        self._stop.clear()
