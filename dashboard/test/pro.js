// Verbo Pro: plans, the phone limit, Razorpay payment links, the signed return and the webhook.
// Run: node dashboard/test/pro.js (starts its own server and a stand-in for Razorpay).
'use strict';
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 8130;
const FAKE = 8131;
const SECRET = 'rzp_test_secret_123456';
const HOOK = 'whsec_test_654321';
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verbo-pro-'));
const links = [];

// Plays Razorpay's payment-links API.
const fake = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url !== '/v1/payment_links' || req.headers.authorization !== 'Basic ' + Buffer.from('rzp_test_key:' + SECRET).toString('base64')) {
      res.statusCode = 401; return res.end('{}');
    }
    const b = JSON.parse(body);
    const id = 'plink_' + (links.length + 1);
    links.push({ id, ...b });
    res.end(JSON.stringify({ id, short_url: 'https://rzp.io/i/' + id, reference_id: b.reference_id }));
  });
}).listen(FAKE);

const server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
  env: {
    ...process.env, PORT, DATA_DIR: dir, SECRET_KEY: 'x'.repeat(40), INSECURE_COOKIES: '1', NODE_NO_WARNINGS: '1',
    PUBLIC_URL: 'http://127.0.0.1:' + PORT, RAZORPAY_KEY_ID: 'rzp_test_key', RAZORPAY_KEY_SECRET: SECRET, RAZORPAY_WEBHOOK_SECRET: HOOK,
    RAZORPAY_API_URL: 'http://127.0.0.1:' + FAKE, DEVICE_LIMIT: '2', ADMIN_EMAILS: 'owner@example.com',
  },
  stdio: 'ignore',
});

function request(method, url, body, headers = {}, raw) {
  return new Promise((resolve, reject) => {
    const data = raw ?? (body ? JSON.stringify(body) : '');
    const req = http.request({ port: PORT, method, path: url, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), ...headers } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(text || '{}'), location: res.headers.location, cookie: (res.headers['set-cookie'] || [''])[0].split(';')[0] }));
    });
    req.on('error', reject);
    req.end(data);
  });
}

const sign = (secret, text) => crypto.createHmac('sha256', secret).update(text).digest('hex');

(async () => {
  await new Promise((r) => setTimeout(r, 800));
  const results = [];
  const check = (name, ok) => { results.push(ok); console.log(ok ? 'PASS' : 'FAIL', name); };
  try {
    const signup = async (email) => (await request('POST', '/api/signup', { email, password: 'a-long-password-1' })).cookie;
    const a = await signup('buyer@example.com');
    const me = await request('GET', '/api/me', null, { Cookie: a });
    check('a new account is on the free plan', me.body.plan && me.body.plan.pro === false && me.body.plan.plan === 'free');
    const billing = await request('GET', '/api/billing', null, { Cookie: a });
    check('payments are on, with prices', billing.body.enabled === true && billing.body.prices.lifetime.amount === 49900 && billing.body.prices.month.amount === 9900);

    const pair = async (cookie, deviceId) => {
      const code = (await request('POST', '/api/devices/code', null, { Cookie: cookie })).body.code;
      return request('POST', '/api/device/pair', { code, name: 'Pixel', deviceId });
    };
    const p1 = await pair(a, 'phone-one-1234');
    const token = p1.body.token;
    const cfg = await request('GET', '/api/device/config', null, { Authorization: 'Bearer ' + token });
    check('the phone learns its plan when it syncs', cfg.body.plan && cfg.body.plan.pro === false && cfg.body.plan.deviceLimit === 2);
    const meet = await request('POST', '/api/device/meet', {}, { Authorization: 'Bearer ' + token });
    check('Pro-only server features answer 402 on the free plan', meet.status === 402);

    check('a second phone pairs', (await pair(a, 'phone-two-5678')).status === 200);
    const third = await pair(a, 'phone-three-9012');
    check('a third phone is refused past the limit', third.status === 403 && /Remove one/.test(third.body.error));
    const again = await pair(a, 'phone-one-1234');
    check('the same phone signing in again replaces its old entry', again.status === 200);
    check('...so the account still has two phones', (await request('GET', '/api/devices', null, { Cookie: a })).body.length === 2);
    const oldToken = await request('GET', '/api/device/config', null, { Authorization: 'Bearer ' + token });
    check('...and the replaced entry stops working', oldToken.status === 401);
    const t2 = again.body.token;

    // Buy lifetime: a payment link, then Razorpay sends the browser back.
    const link = await request('POST', '/api/billing/link', { plan: 'lifetime' }, { Cookie: a });
    check('a payment link is made for the right amount', link.status === 200 && link.body.url === 'https://rzp.io/i/plink_1' && links[0].amount === 49900 && links[0].currency === 'INR');
    check('the link sends the buyer back to this server', links[0].callback_url === 'http://127.0.0.1:' + PORT + '/api/billing/return' && links[0].customer.email === 'buyer@example.com');
    const ref = links[0].reference_id;
    const q = (sig) => `/api/billing/return?razorpay_payment_id=pay_1&razorpay_payment_link_id=plink_1&razorpay_payment_link_reference_id=${ref}&razorpay_payment_link_status=paid&razorpay_signature=${sig}`;
    const forged = await request('GET', q('0'.repeat(64)));
    check('a forged return is refused', forged.status === 302 && forged.location === '/?pro=failed');
    check('...and grants nothing', (await request('GET', '/api/me', null, { Cookie: a })).body.plan.pro === false);
    const good = await request('GET', q(sign(SECRET, `plink_1|${ref}|paid|pay_1`)));
    check('a signed return activates Pro', good.location === '/?pro=active');
    const plan = (await request('GET', '/api/me', null, { Cookie: a })).body.plan;
    check('the account has lifetime Pro', plan.pro === true && plan.plan === 'lifetime' && plan.features.includes('assistant'));
    const synced = await request('GET', '/api/device/config', null, { Authorization: 'Bearer ' + t2 });
    check('the phone sees Pro on its next sync', synced.body.plan.pro === true);
    check('Pro-only server features are allowed now', (await request('POST', '/api/device/meet', {}, { Authorization: 'Bearer ' + t2 })).status !== 402);
    check('lifetime can\'t be bought twice', (await request('POST', '/api/billing/link', { plan: 'month' }, { Cookie: a })).status === 409);

    // A month, paid through the webhook only.
    const b = await signup('monthly@example.com');
    await request('POST', '/api/billing/link', { plan: 'month' }, { Cookie: b });
    const hook = JSON.stringify({ event: 'payment_link.paid', payload: { payment_link: { entity: { id: 'plink_2' } }, payment: { entity: { id: 'pay_2' } } } });
    const bad = await request('POST', '/api/billing/webhook', null, { 'X-Razorpay-Signature': 'nope' }, hook);
    check('a webhook without the right signature is refused', bad.status === 400);
    const ok = await request('POST', '/api/billing/webhook', null, { 'X-Razorpay-Signature': sign(HOOK, hook) }, hook);
    const m1 = (await request('GET', '/api/me', null, { Cookie: b })).body.plan;
    check('a signed webhook activates a month', ok.status === 200 && m1.pro === true && m1.plan === 'month');
    const days = (m1.until - Date.now()) / 86400000;
    check('...for 30 days', days > 29.9 && days <= 30.01);
    await request('POST', '/api/billing/webhook', null, { 'X-Razorpay-Signature': sign(HOOK, hook) }, hook);
    const m2 = (await request('GET', '/api/me', null, { Cookie: b })).body.plan;
    check('the same payment reported twice counts once', m2.until === m1.until);

    // The owner is always Pro.
    const o = await signup('owner@example.com');
    check('an admin email always has Pro', (await request('GET', '/api/me', null, { Cookie: o })).body.plan.pro === true);
  } catch (e) {
    console.error(e);
    results.push(false);
  } finally {
    server.kill();
    fake.close();
    const failed = results.filter((r) => !r).length;
    console.log(failed ? `${failed} failed` : `all ${results.length} passed`);
    process.exit(failed ? 1 : 0);
  }
})();
