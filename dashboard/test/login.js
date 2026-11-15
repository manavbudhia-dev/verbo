// "Continue with Google" on the web and in the app, plus agents and key actions. Run: node dashboard/test/login.js
'use strict';
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 8134;
const FAKE = 8135;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verbo-login-'));
const jwt = (claims) => ['x', Buffer.from(JSON.stringify(claims)).toString('base64url'), 'sig'].join('.');
const PEOPLE = {
  asha: { iss: 'https://accounts.google.com', aud: 'cid', sub: 'g-asha', email: 'asha@test.co', email_verified: true, name: 'Asha' },
  taken: { iss: 'https://accounts.google.com', aud: 'cid', sub: 'g-owner', email: 'taken@test.co', email_verified: true, name: 'Owner' },
  other: { iss: 'https://accounts.google.com', aud: 'someone-else', sub: 'g-x', email: 'x@test.co', email_verified: true },
};

const fake = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    res.setHeader('Content-Type', 'application/json');
    const who = PEOPLE[new URLSearchParams(body).get('code')];
    if (req.url === '/token' && who) return res.end(JSON.stringify({ access_token: 'a', id_token: jwt(who) }));
    res.statusCode = 400;
    res.end('{"error":"invalid_grant"}');
  });
}).listen(FAKE);

const server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
  env: {
    ...process.env, PORT, DATA_DIR: dir, SECRET_KEY: 'x'.repeat(40), INSECURE_COOKIES: '1', NODE_NO_WARNINGS: '1',
    GOOGLE_CLIENT_ID: 'cid', GOOGLE_CLIENT_SECRET: 'csecret', PUBLIC_URL: 'http://localhost:' + PORT, GOOGLE_TOKEN_URL: `http://localhost:${FAKE}/token`,
  },
  stdio: 'ignore',
});

function request(method, url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : '';
    const req = http.request({ port: PORT, method, path: url, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), ...headers } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => {
        let json = {};
        try { json = JSON.parse(text || '{}'); } catch { json = {}; }
        resolve({ status: res.statusCode, body: json, text, location: res.headers.location, cookie: (res.headers['set-cookie'] || [''])[0].split(';')[0] });
      });
    });
    req.on('error', reject);
    req.end(data);
  });
}

const stateOf = (location) => new URL(location).searchParams.get('state');

(async () => {
  await new Promise((r) => setTimeout(r, 800));
  const results = [];
  const check = (name, ok) => { results.push(ok); console.log(ok ? 'PASS' : 'FAIL', name); };
  try {
    check('the sign-in page learns Google is on', (await request('GET', '/api/me')).body.googleLogin === true);

    // Web sign-in.
    const start = await request('GET', '/api/auth/google');
    check('starts at Google with only the basic scopes', start.status === 302 && start.location.includes('scope=openid+email+profile') && !start.location.includes('gmail'));
    const back = await request('GET', `/api/google/callback?code=asha&state=${stateOf(start.location)}`);
    check('coming back signs in with a cookie and moves on', back.status === 200 && back.cookie.startsWith('verbo_session=') && back.cookie.length > 20 && back.text.includes('url=/'));
    const me = (await request('GET', '/api/me', null, { Cookie: back.cookie })).body;
    check('the new account is that Google user', me.email === 'asha@test.co' && me.name === 'Asha');
    check('a Google account has no password to guess', (await request('POST', '/api/login', { email: 'asha@test.co', password: '' })).status === 401);
    const again = await request('GET', `/api/google/callback?code=asha&state=${stateOf((await request('GET', '/api/auth/google')).location)}`);
    check('signing in again reaches the same account', (await request('GET', '/api/me', null, { Cookie: again.cookie })).body.email === 'asha@test.co');
    const wrong = await request('GET', `/api/google/callback?code=other&state=${stateOf((await request('GET', '/api/auth/google')).location)}`);
    check('a token for another app is refused', wrong.status === 200 && !wrong.cookie && wrong.text.includes('did not complete'));

    // Someone made a password account on another person's address first.
    const squatter = await request('POST', '/api/signup', { email: 'taken@test.co', password: 'squatter-pass-1' });
    await request('GET', `/api/google/callback?code=taken&state=${stateOf((await request('GET', '/api/auth/google')).location)}`);
    check('linking Google ends the old password session', (await request('GET', '/api/me', null, { Cookie: squatter.cookie })).body.email === null);
    check('and the old password', (await request('POST', '/api/login', { email: 'taken@test.co', password: 'squatter-pass-1' })).status === 401);

    // App sign-in: the phone keeps a verifier and sends its hash.
    const verifier = crypto.randomBytes(32).toString('base64url');
    const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
    check('the app must send a challenge', (await request('GET', '/api/auth/google?app=1')).status === 400);
    const appStart = await request('GET', `/api/auth/google?app=1&challenge=${challenge}`);
    const appBack = await request('GET', `/api/google/callback?code=asha&state=${stateOf(appStart.location)}`);
    const code = /code=([A-Z0-9]+)/.exec(appBack.text)?.[1];
    check('the app gets a pairing code through its link, and no web cookie', !!code && appBack.text.includes('scheme=verbo') && !appBack.cookie);
    check('another phone cannot use that code', (await request('POST', '/api/device/pair', { code, verifier: 'guess' })).status === 400);
    const paired = await request('POST', '/api/device/pair', { code, verifier, name: 'Pixel' });
    check('the phone that started it is paired', paired.status === 200 && !!paired.body.token);
    const devices = (await request('GET', '/api/devices', null, { Cookie: again.cookie })).body;
    check('and shows up on the same account', devices.some((d) => d.name === 'Pixel'));

    // Agents, key actions and favourites.
    const auth = { Cookie: again.cookie };
    const agent = {
      id: 'a1', name: 'Lead helper', emoji: '🤖', description: 'Look up and reply', active: true,
      nodes: [{ id: 't', kind: 'trigger', ref: 'person', x: 10, y: 20 }, { id: 's', kind: 'step', ref: 'Who is', x: 200, y: 20 }, { id: 'c', kind: 'step', ref: 'custom', text: 'Write a warm reply', x: 400, y: 20 }],
      edges: [{ from: 't', to: 's' }, { from: 's', to: 'c' }, { from: 's', to: 'ghost' }],
    };
    const saved = await request('PUT', '/api/config', { agents: [agent], keymap: [{ key: 'B', app: 'com.whatsapp', action: 'act:Reply options' }, { key: 'b', app: '', action: 'tool:translate' }], favorites: ['Polish'] }, auth);
    check('an agent is saved and links to missing blocks are dropped', saved.status === 200 && saved.body.agents[0].edges.length === 2 && saved.body.agents[0].nodes[2].text === 'Write a warm reply');
    check('key actions keep one letter per app', saved.body.keymap.length === 2 && saved.body.keymap[0].key === 'b' && saved.body.favorites[0] === 'Polish');
    check('the same letter twice in one app is refused', (await request('PUT', '/api/config', { keymap: [{ key: 'b', app: '', action: 'tool:ai' }, { key: 'b', app: '', action: 'tool:voice' }] }, auth)).status === 400);
    check('an unknown tool is refused', (await request('PUT', '/api/config', { keymap: [{ key: 'c', app: '', action: 'tool:format_disk' }] }, auth)).status === 400);
    check('an unknown trigger is refused', (await request('PUT', '/api/config', { agents: [{ ...agent, nodes: [{ id: 't', kind: 'trigger', ref: 'moon' }] }] }, auth)).status === 400);
    const phone = (await request('GET', '/api/device/config', null, { Authorization: 'Bearer ' + paired.body.token })).body;
    await request('PUT', '/api/config', { keyboard: { keysOn: false } }, auth);
    const phone2 = (await request('GET', '/api/device/config', null, { Authorization: 'Bearer ' + paired.body.token })).body;
    check('the phone receives key actions and the pause switch', phone.keymap.length === 2 && phone2.keyboard.keysOn === false);
    const run = await request('POST', '/api/run', { text: 'hi', steps: [{ name: 'Polish', instruction: 'Fix it' }] }, auth);
    check('a run without an AI key logs why the step stopped', run.status === 200 && run.body.result === null && run.body.logs[0].error.includes('AI key'));
  } catch (e) {
    check('no exception: ' + e.message, false);
  }
  server.kill();
  fake.close();
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* Windows keeps the file a moment */ }
  process.exit(results.every(Boolean) ? 0 : 1);
})();
