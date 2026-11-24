#!/data/data/com.termux/files/usr/bin/bash
# Termux 上启动中转。用法：把下面标着「改我」的值改掉，然后 bash start-termux.sh
#
# 端口 8021 是刻意选的：对齐你 Termux 上现成那套 0.0.0.0:8021 内网穿透，
# 穿透工具（frp / natapp / cloudflared / 花生壳…）继续指向 127.0.0.1:8021 即可，
# 成员手机的浏览器/App 填穿透给的公网域名，不用装 VPN、不用连同一个 WiFi。
#
# termux-wake-lock 只解决"CPU 休眠"，**不解决国产 ROM 的后台清理**。
# 必须另外做两件事，否则过几小时一定会静默掉线：
#   1. 系统设置 → 电池 → Termux → 不受限制（各家叫法不同）
#   2. 多任务界面里锁住 Termux（小米/华为/OPPO 都有这个操作）

set -e

# ── 改我：上游真实 Key。全世界只有这一行有它，绝不进 APK ──
export UPSTREAM_KEY='sk-换成你的真实Key'

# ── 改我：固定假 key。用 `node relay.js keygen` 生成一次，填这里 ──
# 同一个值也要编译进分发出去的 APK（assets/distribution.json 的 sharedKey）。
# 想一键吊销所有旧包：换这里的值 + 重启中转，旧 APK 立刻全部 401。
export SHARED_KEY='换成keygen生成的固定假key'

# ── 改我：管理接口口令。穿透暴露到公网后必须设，别留空 ──
# 本机调额度时带 -H "X-Admin-Token: 这一串"。
# 再生成一串：`node relay.js keygen`
export ADMIN_TOKEN='换成另一串口令'

export AUTH_MODE=pinned       # pinned=内置固定假key；signed=逐台签发；open=仅自测
export DAILY_LIMIT=30
export PORT=8021

termux-wake-lock

cd "$(dirname "$0")"
echo "中转启动中。手机上自测：curl http://127.0.0.1:$PORT/healthz"
echo "成员设备填：https://<穿透给你的域名>/v1   （不是 0.0.0.0，那是监听地址）"
node relay.js
