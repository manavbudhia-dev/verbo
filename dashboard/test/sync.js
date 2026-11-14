// Two-way phone sync, keyboard settings and the assistant, against a stand-in AI gateway.
// Run: node dashboard/test/sync.js (starts its own servers).
'use strict';
const http = require('node:http');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 8126;
const FAKE = 8127;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verbo-test-'));
let nextReply = '';
let lastSystem = '';

// Plays an OpenAI-compatible gateway: answers every chat with whatever the test queued.
const fake = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    res.setHeader('Content-Type', 'application/json');
    if (req.headers.authorization !== 'Bearer sk-good-key-1234') { res.statusCode = 401; return res.end('{"error":{"message":"bad key"}}'); }
    lastSystem = JSON.parse(body).messages[0].content;
    res.end(JSON.stringify({ choices: [{ message: { content: nextReply } }] }));
  });
}).listen(FAKE);

const server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
  env: { ...process.env, PORT, DATA_DIR: dir, SECRET_KEY: 'x'.repeat(40), INSECURE_COOKIES: '1', NODE_NO_WARNINGS: '1' },
  stdio: 'ignore',
});

function request(method, url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : '';
    const req = http.request({ port: PORT, method, path: url, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), ...headers } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(text || '{}'), cookie: (res.headers['set-cookie'] || [''])[0].split(';')[0] }));
    });
    req.on('error', reject);
    req.end(data);
  });
}

(async () => {
  await new Promise((r) => setTimeout(r, 800));
  const results = [];
  const check = (name, ok) => { results.push(ok); console.log(ok ? 'PASS' : 'FAIL', name); };
  try {
    const { cookie } = await request('POST', '/api/signup', { email: 'sync@test.co', password: 'correct-horse-9' });
    const web = { Cookie: cookie };
    const { code } = (await request('POST', '/api/devices/code', null, web)).body;
    const phone = { Authorization: 'Bearer ' + (await request('POST', '/api/device/pair', { code, name: 'Phone' })).body.token };

    // A key typed on the phone goes up to the dashboard and survives the next download.
    await request('PUT', '/api/config', { weather: false }, web);
    const up = await request('PUT', '/api/device/config', { provider: 'custom', customKey: 'sk-good-key-1234', customBase: `http://localhost:${FAKE}`, keyboard: { theme: 'ocean', heightPercent: 110, wheel: ['ai', 'voice'], bogus: 1, soundVolume: 9999 } }, phone);
    check('phone upload is accepted', up.status === 200 && up.body.customKey === 'sk-good-key-1234');
    check('fields the phone did not send are untouched', up.body.weather === false && up.body.skills.length > 0);
    const down = (await request('GET', '/api/device/config', null, phone)).body;
    check('the key comes back on the next download', down.customKey === 'sk-good-key-1234' && down.provider === 'custom');
    check('keyboard settings are stored, bad ones dropped', down.keyboard.theme === 'ocean' && down.keyboard.heightPercent === 110 && !('bogus' in down.keyboard) && !('soundVolume' in down.keyboard));
    const seen = (await request('GET', '/api/config', null, web)).body;
    check('dashboard shows the key as saved with a hint, never the key', seen.customKeySet === true && seen.customKeyHint === '1234' && !('customKey' in seen));
    check('dashboard sees the phone\'s keyboard settings merged with defaults', seen.keyboard.theme === 'ocean' && seen.keyboard.keyShape === 'raised' && seen.keyboard.wheel.join() === 'ai,voice');
    await request('PUT', '/api/config', { keyboard: { keyShape: 'glass' } }, web);
    check('a setting changed on the web reaches the phone and keeps the rest', (() => 0)() === 0 && await (async () => {
      const k = (await request('GET', '/api/device/config', null, phone)).body.keyboard;
      return k.keyShape === 'glass' && k.theme === 'ocean';
    })());
    check('an unpaired caller cannot upload', (await request('PUT', '/api/device/config', { weather: true })).status === 401);

    // App tokens (Slack and friends): saved on the web, shown only as connected, delivered to the phone.
    const apps = await request('PUT', '/api/config', { slackToken: 'xoxp-1-secret', todoistToken: 'todo-secret' }, web);
    check('app tokens show as connected, never the token', apps.body.appsSet.slackToken === true && apps.body.appsSet.githubToken === false && !JSON.stringify(apps.body).includes('xoxp-1-secret'));
    const phoneApps = (await request('GET', '/api/device/config', null, phone)).body;
    check('the phone receives app tokens', phoneApps.slackToken === 'xoxp-1-secret' && phoneApps.todoistToken === 'todo-secret');
    check('a Discord webhook must be a Discord address', (await request('PUT', '/api/config', { discordWebhook: 'https://evil.example/hook' }, web)).status === 400
      && (await request('PUT', '/api/config', { discordWebhook: 'https://discord.com/api/webhooks/123/abc-DEF_9' }, web)).body.appsSet.discordWebhook === true);
    const trig = await request('PUT', '/api/config', { triggers: [{ kind: 'keyword', keyword: 'invoice', skill: 'Summarize', auto: true }, { kind: 'person', skill: 'Who is' }] }, web);
    check('auto actions are saved and reach the phone', trig.status === 200 && trig.body.triggers.length === 2 && trig.body.triggers[0].auto === true
      && (await request('GET', '/api/device/config', null, phone)).body.triggers[1].skill === 'Who is');
    check('a bad auto action is refused', (await request('PUT', '/api/config', { triggers: [{ kind: 'mind-reading', skill: 'x' }] }, web)).status === 400
      && (await request('PUT', '/api/config', { triggers: [{ kind: 'keyword', keyword: '', skill: 'x' }] }, web)).status === 400);
    check('an app token can be removed', (await request('PUT', '/api/config', { slackToken: '' }, web)).body.appsSet.slackToken === false);

    // The assistant.
    check('chat needs a signed-in user', (await request('POST', '/api/chat', { messages: [{ role: 'user', content: 'hi' }] })).status === 401);
    nextReply = 'Sure! {"reply": "Here is a skill that turns your text into emoji.", "actions": [{"type": "create_skill", "name": "Emoji Translator", "emoji": "😀", "does": "Turns text into emoji", "instruction": "Replace the text with emoji.", "group": "edit", "key": "E", "wheel": true}, {"type": "delete_everything"}, {"type": "open", "page": "connections", "label": "Connect Notion", "why": "Needs a token"}, {"type": "bind_key", "skill": "Polish", "key": "pp"}]}';
    const chat = await request('POST', '/api/chat', { messages: [{ role: 'user', content: 'make an emoji skill' }] }, web);
    check('assistant reply is parsed', chat.status === 200 && chat.body.reply.startsWith('Here is a skill'));
    check('known actions are kept and cleaned, unknown or malformed ones dropped',
      chat.body.actions.length === 2 && chat.body.actions[0].type === 'create_skill' && chat.body.actions[0].key === 'e' && chat.body.actions[1].page === 'connections');
    check('the assistant is told what the user has', lastSystem.includes('Polish (hold P)') && lastSystem.includes('AI key saved: yes'));
    nextReply = 'Just some plain words, no JSON.';
    const plain = await request('POST', '/api/chat', { messages: [{ role: 'user', content: 'hello' }] }, web);
    check('a non-JSON answer is shown as text', plain.body.reply === 'Just some plain words, no JSON.' && plain.body.actions.length === 0);
    await request('PUT', '/api/config', { customKey: 'sk-wrong-key-0000' }, web);
    check('a rejected key is reported clearly', (await request('POST', '/api/chat', { messages: [{ role: 'user', content: 'hello' }] }, web)).body.error.includes('rejected'));
    await request('PUT', '/api/config', { customKey: '' }, web);
    check('no key gives a helpful message', (await request('POST', '/api/chat', { messages: [{ role: 'user', content: 'hello' }] }, web)).body.error.includes('Add your AI key'));
  } catch (e) {
    check('no exception: ' + e.message, false);
  }
  server.kill();
  fake.close();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(results.every(Boolean) ? 0 : 1);
})();
