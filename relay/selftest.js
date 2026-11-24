#!/usr/bin/env node
/**
 * relay 自测：不起真上游，用本地假上游把每条分支走一遍。
 *
 * 为什么值得写：中转是"分发后你摸不到"的一层 —— 成员设备出问题时你看不到它的日志。
 * 所以它必须在出门前就把「没令牌 / 令牌伪造 / 超额 / 上游挂了」这些分支验证过。
 *
 * 跑法：node relay/selftest.js
 */
'use strict';

const http = require('http');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const UP_PORT = 9911;
const RELAY_PORT = 9912;
const SIGNED_PORT = 9913;
const PINNED_PORT = 9914;

let pass = 0;
let fail = 0;

function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(`  ${ok ? '✓' : '✗'} ${name}${ok ? '' : `  得到 ${JSON.stringify(got)} 期望 ${JSON.stringify(want)}`}`);
  ok ? pass++ : fail++;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function waitUp(url) {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(url);
      if (r.ok) return true;
    } catch (_) {}
    await sleep(100);
  }
  return false;
}

// 假上游：model=boom 时返回 500，其余返回一句固定回复
function startMockUpstream() {
  return new Promise((resolve) => {
    const s = http.createServer((req, res) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        let model = '';
        try {
          model = JSON.parse(Buffer.concat(chunks).toString()).model;
        } catch (_) {}
        if (model === 'boom') {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: { message: '上游炸了' } }));
        }
        const body = JSON.stringify({
          choices: [{ message: { content: '在呢' } }],
          usage: { prompt_tokens: 10, completion_tokens: 5 },
        });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(body);
      });
    });
    s.listen(UP_PORT, '127.0.0.1', () => resolve(s));
  });
}

function startRelay(port, env) {
  const child = spawn(process.execPath, [path.join(__dirname, 'relay.js')], {
    env: Object.assign({}, process.env, env, { PORT: String(port) }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', () => {});
  child.stderr.on('data', () => {});
  return child;
}

async function chat(port, token, model) {
  const r = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
    method: 'POST',
    headers: Object.assign(
      { 'Content-Type': 'application/json' },
      token ? { Authorization: 'Bearer ' + token } : {}
    ),
    body: JSON.stringify({ model: model || 'deepseek-chat', messages: [{ role: 'user', content: 'hi' }] }),
  });
  const text = await r.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch (_) {}
  return { status: r.status, body, used: r.headers.get('x-quota-used'), limit: r.headers.get('x-quota-limit') };
}

(async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-'));
  const dataFile = path.join(tmp, 'data.json');
  const up = await startMockUpstream();
  const relay = startRelay(RELAY_PORT, {
    UPSTREAM: `http://127.0.0.1:${UP_PORT}/v1`,
    UPSTREAM_KEY: 'test-key',
    DAILY_LIMIT: '2',
    AUTH_MODE: 'open',
    DATA: dataFile,
  });

  const upUrl = `http://127.0.0.1:${RELAY_PORT}/healthz`;
  if (!(await waitUp(upUrl))) {
    console.log('中转没起来，后面不用测了');
    process.exit(1);
  }

  console.log('\n【1】健康检查与鉴权');
  const h = await (await fetch(upUrl)).json();
  check('healthz 正常', h.ok, true);
  check('没有令牌 → 401', (await chat(RELAY_PORT, '')).status, 401);
  check('令牌格式非法 → 401', (await chat(RELAY_PORT, 'a')).status, 401);

  console.log('\n【2】正常一次：放行 + 计数 +1');
  const r1 = await chat(RELAY_PORT, 'devAAAAAA');
  check('状态 200', r1.status, 200);
  check('回复透传', r1.body.choices[0].message.content, '在呢');
  check('已用 1', r1.used, '1');

  console.log('\n【3】上游失败不计数');
  const rb = await chat(RELAY_PORT, 'devAAAAAA', 'boom');
  check('上游 500 透传成 500', rb.status, 500);
  check('次数仍是 1（失败不扣）', rb.used, '1');

  console.log('\n【4】额度用尽 → 429 且带人话');
  const r2 = await chat(RELAY_PORT, 'devAAAAAA');
  check('第二次仍 200', r2.status, 200);
  check('已用 2', r2.used, '2');
  const r3 = await chat(RELAY_PORT, 'devAAAAAA');
  check('第三次 429', r3.status, 429);
  check('额度类型正确', r3.body.error.type, 'quota_exceeded');
  check('文案说明是今天的', /今天的额度用完了/.test(r3.body.error.message), true);

  console.log('\n【5】另一台设备不受影响');
  const other = await chat(RELAY_PORT, 'devBBBBBB');
  check('新设备直接可用', other.status, 200);
  check('新设备从 1 开始数', other.used, '1');

  console.log('\n【6】本机调额后立刻恢复');
  const adm = await fetch(`http://127.0.0.1:${RELAY_PORT}/admin/quota`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: 'devAAAAAA', limit: 5, note: '测试' }),
  });
  check('调额接口 200', adm.status, 200);
  const list = await (await fetch(`http://127.0.0.1:${RELAY_PORT}/admin/devices`)).json();
  const d = list.devices.find((x) => x.id === 'devAAAAAA');
  check('列表里上限变成 5', d.limit, 5);
  check('列表里带着已用 2', d.used, 2);
  check('调额后能继续用', (await chat(RELAY_PORT, 'devAAAAAA')).status, 200);

  console.log('\n【7】/v1/quota 给客户端查剩余');
  const q = await (await fetch(`http://127.0.0.1:${RELAY_PORT}/v1/quota`, {
    headers: { Authorization: 'Bearer devAAAAAA' },
  })).json();
  check('used=3', q.used, 3);
  check('limit=5', q.limit, 5);

  relay.kill();

  // ---------------------------------------------------------- 签名模式
  console.log('\n【8】AUTH_MODE=signed：伪造的令牌无效');
  const signed = startRelay(SIGNED_PORT, {
    UPSTREAM: `http://127.0.0.1:${UP_PORT}/v1`,
    UPSTREAM_KEY: 'test-key',
    DAILY_LIMIT: '2',
    AUTH_MODE: 'signed',
    SECRET: 'unit-test-secret',
    DATA: path.join(tmp, 'data2.json'),
  });
  if (!(await waitUp(`http://127.0.0.1:${SIGNED_PORT}/healthz`))) {
    console.log('签名模式没起来');
    process.exit(1);
  }
  check('裸设备 id → 401', (await chat(SIGNED_PORT, 'devAAAAAA')).status, 401);
  check('签名乱填 → 401', (await chat(SIGNED_PORT, 'devAAAAAA.deadbeef')).status, 401);

  const token = require('child_process')
    .execFileSync(process.execPath, [path.join(__dirname, 'relay.js'), 'issue', 'devCCCCCC'], {
      env: Object.assign({}, process.env, { SECRET: 'unit-test-secret' }),
    })
    .toString()
    .trim();
  check('签发出来的格式对', /^devCCCCCC\.[0-9a-f]{32}$/.test(token), true);
  check('签发的令牌可用', (await chat(SIGNED_PORT, token)).status, 200);

  signed.kill();

  // ---------------------------------------------------------- 固定假 key 模式
  // 分发出去的主力形态：APK 内置 SHARED_KEY，谁都看不到真 Key；
  // 端口经公网穿透暴露，所以管理接口必须靠 ADMIN_TOKEN 而不是 127.0.0.1。
  console.log('\n【9】AUTH_MODE=pinned：内置固定假 key + 穿透下的管理保护');
  const FAKE = 'unit-test-shared-key-abc123';
  const ADM = 'unit-test-admin-token-xyz789';
  const pinned = startRelay(PINNED_PORT, {
    UPSTREAM: `http://127.0.0.1:${UP_PORT}/v1`,
    UPSTREAM_KEY: 'test-key',
    DAILY_LIMIT: '1',
    AUTH_MODE: 'pinned',
    SHARED_KEY: FAKE,
    ADMIN_TOKEN: ADM,
    DATA: path.join(tmp, 'data3.json'),
  });
  if (!(await waitUp(`http://127.0.0.1:${PINNED_PORT}/healthz`))) {
    console.log('pinned 模式没起来');
    process.exit(1);
  }
  check('裸 deviceId → 401', (await chat(PINNED_PORT, 'phoneAAAAAA')).status, 401);
  check('假 key 填错 → 401', (await chat(PINNED_PORT, 'phoneAAAAAA.wrong')).status, 401);
  const okPin = await chat(PINNED_PORT, `phoneAAAAAA.${FAKE}`);
  check('内置假 key 放行', okPin.status, 200);
  check('按 deviceId 计数', okPin.used, '1');

  // 同一假 key、另一台设备：额度独立，不能被第一台的超额连坐
  const pin2 = await chat(PINNED_PORT, `phoneBBBBBB.${FAKE}`);
  check('另一台设备独立额度', pin2.status, 200);
  check('另一台从 1 开始', pin2.used, '1');
  check('第一台超额 → 429', (await chat(PINNED_PORT, `phoneAAAAAA.${FAKE}`)).status, 429);

  // 关键回归：请求来自 127.0.0.1（模拟穿透客户端转发），但没带 ADMIN_TOKEN
  const adminNoTok = await fetch(`http://127.0.0.1:${PINNED_PORT}/admin/devices`);
  check('穿透转发但无口令 → 403', adminNoTok.status, 403);
  const quotaNoTok = await fetch(`http://127.0.0.1:${PINNED_PORT}/admin/quota`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: 'phoneAAAAAA', limit: 9999 }),
  });
  check('无口令不能给自己提额 → 403', quotaNoTok.status, 403);

  const adminWithTok = await fetch(`http://127.0.0.1:${PINNED_PORT}/admin/quota`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Admin-Token': ADM },
    body: JSON.stringify({ id: 'phoneAAAAAA', limit: 3, note: '老张' }),
  });
  check('带对口令调额 → 200', adminWithTok.status, 200);
  check('调额后第一台恢复', (await chat(PINNED_PORT, `phoneAAAAAA.${FAKE}`)).status, 200);

  // healthz 不泄露模式和设备数（公网会扫到它）
  const hz = await (await fetch(`http://127.0.0.1:${PINNED_PORT}/healthz`)).json();
  check('healthz 只回 ok', JSON.stringify(hz), JSON.stringify({ ok: true }));

  // keygen / issue 能产出可校验的令牌
  const kg = require('child_process')
    .execFileSync(process.execPath, [path.join(__dirname, 'relay.js'), 'keygen'])
    .toString().trim();
  check('keygen 生成非空随机串', kg.length >= 20, true);
  const issued = require('child_process')
    .execFileSync(process.execPath, [path.join(__dirname, 'relay.js'), 'issue', 'phoneCCCCCC'], {
      env: Object.assign({}, process.env, { AUTH_MODE: 'pinned', SHARED_KEY: FAKE }),
    })
    .toString().trim();
  check('issue 产出 id.假key', issued, `phoneCCCCCC.${FAKE}`);

  pinned.kill();
  up.close();

  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`);
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
