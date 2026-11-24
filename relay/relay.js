#!/usr/bin/env node
/**
 * relay.js —— 分发用的模型中转。一个文件、零依赖、跑在哪都行。
 *
 * 存在的理由只有一条：**让真实 API Key 不出现在分发出去的手机里。**
 *
 * 客户端（App）里配的不是 key，而是"设备令牌"：
 *   真正发请求的是这个中转，它拿着 key 换成上游的 Authorization。
 * 所以成员设备解包、抓包、翻 SharedPreferences —— 都只能看到令牌，
 * 令牌只能换来"这台设备今天的额度"，换不来你的 key。
 *
 * 额度账本也在这里。成员设备改不了它：不在同一台机器上。
 *
 * 跑法（Termux / VPS / 电脑都一样）：
 *   UPSTREAM_KEY=sk-xxx node relay.js
 *
 * 常用环境变量：
 *   PORT          监听端口，默认 8021（对齐 Termux 上那套 0.0.0.0:8021 穿透）
 *   UPSTREAM      上游地址，默认 https://api.deepseek.com/v1
 *   UPSTREAM_KEY  上游真实 Key（**只有这里会出现**）
 *   SECRET        签发设备令牌的密钥，换一个等于让所有旧令牌失效
 *   DAILY_LIMIT   每台设备每日条数，默认 30
 *   AUTH_MODE     open   = 设备自己生成 id 即可用（本机自测）
 *                 pinned = 认一个内置在所有分发包里的「固定假 key」（分发省事）
 *                 signed = 必须由 `node relay.js issue <id>` 签发（能挡住改包刷设备）
 *   SHARED_KEY    pinned 模式的那个固定假 key。换一个 = 一键吊销所有已发出的包
 *   ADMIN_TOKEN   管理接口口令。**端口会被公网穿透映射出去时必须设**，见下方说明
 *   DATA          账本文件，默认 ./relay-data.json
 *
 * ⚠️ 穿透场景下「管理接口只认 127.0.0.1」是不够的：
 *   frp / natapp / cloudflared 这类隧道客户端就跑在本机，它转发进来的请求
 *   remoteAddress 看起来也是 127.0.0.1 —— 成员设备照样能调 /admin/quota 给自己提额。
 *   所以只要设了 ADMIN_TOKEN，管理接口改为「只认 X-Admin-Token」，不再看来源 IP。
 */
'use strict';

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// 8021 而不是 8787：用户 Termux 上现成的穿透就是映射这个端口，别再让他改一遍配置。
const PORT = Number(process.env.PORT || 8021);
const UPSTREAM = (process.env.UPSTREAM || 'https://api.deepseek.com/v1').replace(/\/+$/, '');
const UPSTREAM_KEY = process.env.UPSTREAM_KEY || '';
const SECRET = process.env.SECRET || '';
const SHARED_KEY = process.env.SHARED_KEY || '';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
const DAILY_LIMIT = Number(process.env.DAILY_LIMIT || 30);
const AUTH_MODE = (process.env.AUTH_MODE || 'open').toLowerCase();
const DATA = process.env.DATA || path.join(__dirname, 'relay-data.json');

/** 账本里只留最近几天，避免文件无限长。 */
const KEEP_DAYS = 3;

// ------------------------------------------------------------------ 账本

/** @type {{devices: Record<string, {note?: string, limit?: number, days: Record<string, number>}>}} */
let store = { devices: {} };

function loadStore() {
  try {
    store = JSON.parse(fs.readFileSync(DATA, 'utf8'));
    if (!store || typeof store !== 'object' || !store.devices) store = { devices: {} };
  } catch (_) {
    store = { devices: {} };
  }
}

function saveStore() {
  // 同步写：账本很小（几十字节 × 设备数），异步反而要处理并发覆盖
  try {
    fs.writeFileSync(DATA, JSON.stringify(store, null, 2));
  } catch (e) {
    console.error('[relay] 写账本失败：' + e.message);
  }
}

/** 本地日期的 YYYY-MM-DD。用本地时区而不是 UTC —— 用户理解的"今天"是本地零点。 */
function dayKey(d) {
  const t = d || new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}`;
}

function trimDays(entry) {
  const keys = Object.keys(entry.days).sort();
  while (keys.length > KEEP_DAYS) delete entry.days[keys.shift()];
}

function deviceEntry(deviceId) {
  let d = store.devices[deviceId];
  if (!d) {
    // 自动建档：额度跟账本一起长，不需要预先登记
    d = { note: '', limit: DAILY_LIMIT, days: {} };
    store.devices[deviceId] = d;
    saveStore();
  }
  if (typeof d.limit !== 'number' || d.limit <= 0) d.limit = DAILY_LIMIT;
  if (!d.days) d.days = {};
  return d;
}

function usedToday(deviceId) {
  const d = store.devices[deviceId];
  return d ? Number(d.days[dayKey()] || 0) : 0;
}

function limitOf(deviceId) {
  const d = store.devices[deviceId];
  return d && typeof d.limit === 'number' && d.limit > 0 ? d.limit : DAILY_LIMIT;
}

// ------------------------------------------------------------------ 令牌

/**
 * 设备令牌 = `<deviceId>.<凭证>`。凭证随 AUTH_MODE 变：
 *
 *   open   —— 无凭证，裸 deviceId。本机自测用，任何人知道地址就能刷。
 *   pinned —— 凭证就是 SHARED_KEY，一个编译进所有分发包的「固定假 key」。
 *             App 里 deviceId 首次启动自动生成、隐藏、不可改，用户只看到「已授权」。
 *             好处是分发出去不用逐台签发；换 SHARED_KEY 等于一键吊销全部旧包。
 *   signed —— 凭证是 HMAC(SECRET, deviceId)。改包刷不出新设备，最严，但要逐台签发。
 *
 * 三种模式**都按 deviceId 分账**，所以「单独给某台设备调额」始终成立。
 */
function sign(deviceId) {
  return crypto.createHmac('sha256', SECRET).update(deviceId).digest('hex').slice(0, 32);
}

function issueToken(deviceId) {
  return `${deviceId}.${AUTH_MODE === 'pinned' ? SHARED_KEY : sign(deviceId)}`;
}

function bearer(req) {
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : '';
}

/** 常量时间比较，长度不等也要走一遍，别用 === 提前返回。 */
function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

/** @returns {string|null} 通过校验的设备 id，或 null */
function deviceFromToken(token) {
  if (!token) return null;
  const i = token.indexOf('.');
  const id = i < 0 ? token : token.slice(0, i);
  const sig = i < 0 ? '' : token.slice(i + 1);

  // 设备 id 由 App 生成，限定成短的安全字符，避免拼进日志/文件名出问题
  if (!/^[A-Za-z0-9_-]{6,32}$/.test(id)) return null;

  if (AUTH_MODE === 'open') return id;
  if (AUTH_MODE === 'pinned') {
    // 固定假 key 只挡「地址传出去被路人捡到」，不挡拿到 APK 的人反编译 ——
    // 那属于 signed 模式的职责，README 里说清楚，别让人误以为这是加密。
    if (!SHARED_KEY || !sig) return null;
    return safeEqual(sig, SHARED_KEY) ? id : null;
  }
  if (!SECRET || !sig) return null;
  const want = sign(id);
  return safeEqual(sig, want) ? id : null;
}

// ------------------------------------------------------------------ 转发

function forward(payload, cb) {
  let u;
  try {
    u = new URL(UPSTREAM + '/chat/completions');
  } catch (e) {
    return cb(new Error('UPSTREAM 地址不合法：' + UPSTREAM));
  }
  const lib = u.protocol === 'https:' ? https : http;
  const body = Buffer.from(JSON.stringify(payload));

  const req = lib.request(
    {
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': body.length,
        // 真实 Key 只在这一行出现，而且不出这台机器
        Authorization: `Bearer ${UPSTREAM_KEY}`,
      },
      timeout: 60000,
    },
    (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => cb(null, res.statusCode || 502, Buffer.concat(chunks)));
    }
  );
  req.on('timeout', () => req.destroy(new Error('上游超时')));
  req.on('error', (e) => cb(e));
  req.write(body);
  req.end();
}

// ------------------------------------------------------------------ HTTP

function json(res, code, obj, extraHeaders) {
  const s = Buffer.from(JSON.stringify(obj));
  res.writeHead(code, Object.assign(
    { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': s.length },
    extraHeaders || {}
  ));
  res.end(s);
}

/** 只允许本机调用：管理接口不该暴露给成员设备。 */
function isLocal(req) {
  const a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

/**
 * 管理接口鉴权。
 *
 * 没设 ADMIN_TOKEN 时保持老行为（只认 127.0.0.1），本机自测不用改命令。
 * 一旦设了，就**只认口令、完全不看 IP** —— 因为穿透客户端转发进来的请求
 * remoteAddress 也是 127.0.0.1，光看 IP 等于没锁。
 */
function adminAllowed(req) {
  if (!ADMIN_TOKEN) return isLocal(req);
  return safeEqual(req.headers['x-admin-token'] || '', ADMIN_TOKEN);
}

/** 管理接口统一拒绝文案，不给探测者提示「到底差在哪」。 */
function denyAdmin(res) {
  return json(res, 403, { error: '管理接口未授权' });
}

function readBody(req, cb) {
  const chunks = [];
  let size = 0;
  req.on('data', (c) => {
    size += c.length;
    // 单个请求正常只有几 KB，超过 512KB 一定不是正常对话
    if (size > 512 * 1024) {
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on('end', () => cb(Buffer.concat(chunks)));
  req.on('error', () => cb(Buffer.alloc(0)));
}

function quotaHeaders(deviceId) {
  return {
    'X-Quota-Used': String(usedToday(deviceId)),
    'X-Quota-Limit': String(limitOf(deviceId)),
    'X-Quota-Day': dayKey(),
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', 'http://localhost');
  const p = url.pathname.replace(/\/+$/, '') || '/';

  // 健康检查：让自己确认"中转活着"。
  // 只回 ok —— 这个接口会被公网扫到，不该告诉对方「我是 open 模式、有几台设备」。
  if (p === '/healthz') {
    return json(res, 200, { ok: true });
  }

  // 客户端查额度（App 首页/面板显示"今日剩余 N 条"用）
  if (p === '/v1/quota' && req.method === 'GET') {
    const id = deviceFromToken(bearer(req));
    if (!id) return json(res, 401, { error: { type: 'unauthorized', message: '设备令牌无效' } });
    return json(res, 200, {
      used: usedToday(id),
      limit: limitOf(id),
      day: dayKey(),
    }, quotaHeaders(id));
  }

  // 真正的转发口
  if (p === '/v1/chat/completions' && req.method === 'POST') {
    const id = deviceFromToken(bearer(req));
    if (!id) {
      return json(res, 401, {
        error: { type: 'unauthorized', message: '设备令牌无效 —— 这台设备没有授权' },
      });
    }
    if (!UPSTREAM_KEY) {
      return json(res, 500, {
        error: { type: 'relay_misconfigured', message: '中转没配 UPSTREAM_KEY' },
      });
    }

    // 先判额度，再读 body：超额时连解析都省了
    const used = usedToday(id);
    const limit = limitOf(id);
    if (used >= limit) {
      return json(res, 429, {
        error: {
          type: 'quota_exceeded',
          message: `今天的额度用完了（${used}/${limit}），明天零点恢复`,
          used, limit, day: dayKey(),
        },
      }, quotaHeaders(id));
    }

    return readBody(req, (buf) => {
      let payload;
      try {
        payload = JSON.parse(buf.toString('utf8'));
      } catch (_) {
        return json(res, 400, { error: { type: 'bad_request', message: '请求不是合法 JSON' } });
      }
      // 模型名可以把控在中转侧：成员设备填什么都不影响你实际用哪个
      if (process.env.MODEL) payload.model = process.env.MODEL;

      forward(payload, (err, code, upstreamBody) => {
        if (err) {
          return json(res, 502, {
            error: { type: 'upstream_error', message: '上游连不上：' + err.message },
          }, quotaHeaders(id));
        }
        // **只在真正成功时计一次数。**
        // 上游 4xx/5xx（余额不足、限流、参数错）算我们自己的问题，
        // 计进去会让用户觉得"什么都没得到却少了额度"。
        if (code >= 200 && code < 300) {
          const d = deviceEntry(id);
          const k = dayKey();
          d.days[k] = Number(d.days[k] || 0) + 1;
          trimDays(d);
          saveStore();
        }
        res.writeHead(code, Object.assign(
          { 'Content-Type': 'application/json; charset=utf-8' },
          quotaHeaders(id)
        ));
        res.end(upstreamBody);
      });
    });
  }

  // ---------------------------------------------------------- 管理（仅本机）

  if (p === '/admin/devices' && req.method === 'GET') {
    if (!adminAllowed(req)) return denyAdmin(res);
    const today = dayKey();
    return json(res, 200, {
      day: today,
      devices: Object.keys(store.devices).map((id) => {
        const d = store.devices[id];
        return { id, note: d.note || '', used: Number(d.days[today] || 0), limit: limitOf(id) };
      }),
    });
  }

  if (p === '/admin/quota' && req.method === 'POST') {
    if (!adminAllowed(req)) return denyAdmin(res);
    return readBody(req, (buf) => {
      let body;
      try {
        body = JSON.parse(buf.toString('utf8'));
      } catch (_) {
        return json(res, 400, { error: '请求不是合法 JSON' });
      }
      const id = String(body.id || '').trim();
      const limit = Number(body.limit);
      if (!id || !Number.isFinite(limit) || limit < 0 || limit > 10000) {
        return json(res, 400, { error: '要 { id, limit }，limit 取 0~10000' });
      }
      const d = deviceEntry(id);
      d.limit = Math.floor(limit);
      if (typeof body.note === 'string') d.note = body.note.slice(0, 40);
      saveStore();
      return json(res, 200, {
        ok: true, id, limit: d.limit, used: Number(d.days[dayKey()] || 0),
      });
    });
  }

  json(res, 404, { error: '不认识的路径：' + p });
});

// ------------------------------------------------------------------ 命令行

function cli() {
  const cmd = process.argv[2];

  // 生成一个够长的固定假 key。pinned 模式用它，或随便要一串口令时也能用。
  if (cmd === 'keygen') {
    console.log(crypto.randomBytes(24).toString('base64url'));
    return true;
  }

  if (cmd === 'issue') {
    const id = process.argv[3];
    if (!id || !/^[A-Za-z0-9_-]{6,32}$/.test(id)) {
      console.error('用法：node relay.js issue <设备ID>（6~32 位字母数字）');
      process.exit(1);
    }
    if (AUTH_MODE === 'pinned') {
      if (!SHARED_KEY) {
        console.error('需要先设 SHARED_KEY（可用 node relay.js keygen 生成）');
        process.exit(1);
      }
    } else if (!SECRET) {
      console.error('需要先设 SECRET，否则签出来的令牌谁都验不过');
      process.exit(1);
    }
    console.log(issueToken(id));
    return true;
  }

  if (cmd === 'devices') {
    loadStore();
    const today = dayKey();
    for (const id of Object.keys(store.devices)) {
      const d = store.devices[id];
      console.log(`${id}  今日 ${d.days[today] || 0}/${limitOf(id)}  ${d.note || ''}`);
    }
    return true;
  }
  return false;
}

if (!cli()) {
  loadStore();
  if (!UPSTREAM_KEY) {
    console.error('[relay] 警告：没设 UPSTREAM_KEY，所有请求都会返回 500');
  }
  if (AUTH_MODE === 'signed' && !SECRET) {
    console.error('[relay] 警告：AUTH_MODE=signed 但没设 SECRET，所有令牌都会失败');
  }
  if (AUTH_MODE === 'pinned' && !SHARED_KEY) {
    console.error('[relay] 警告：AUTH_MODE=pinned 但没设 SHARED_KEY，所有设备都会 401');
  }
  if (AUTH_MODE === 'open') {
    console.error('[relay] 警告：open 模式任何人知道地址就能用，只适合本机自测');
  }
  if (!ADMIN_TOKEN) {
    console.error('[relay] 警告：没设 ADMIN_TOKEN。若端口会被穿透映射出去，成员设备可借 127.0.0.1 转发自行提额');
  }
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[relay] 监听 0.0.0.0:${PORT}（穿透工具指向 127.0.0.1:${PORT} 即可）`);
    console.log(`[relay] 上游 ${UPSTREAM} · 模式 ${AUTH_MODE} · 每日 ${DAILY_LIMIT} 条`);
    console.log(`[relay] 管理接口：${ADMIN_TOKEN ? 'X-Admin-Token 口令保护' : '仅本机（未设 ADMIN_TOKEN）'}`);
    console.log(`[relay] 本机自测：curl http://127.0.0.1:${PORT}/healthz`);
  });
}
