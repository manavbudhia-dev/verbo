// Two saves that overlap must both be kept. Run: node dashboard/test/race.js (starts its own server).
'use strict';
const http = require('node:http');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 8123;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verbo-test-'));
const server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
  env: { ...process.env, PORT, DATA_DIR: dir, SECRET_KEY: 'x'.repeat(40), INSECURE_COOKIES: '1', NODE_NO_WARNINGS: '1' },
  stdio: 'ignore',
});

function request(method, url, body, cookie, slow) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : '';
    const req = http.request({ port: PORT, method, path: url, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), Cookie: cookie || '' } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(text || '{}'), cookie: (res.headers['set-cookie'] || [''])[0].split(';')[0] }));
    });
    req.on('error', reject);
    if (slow) { req.write(data.slice(0, 5)); setTimeout(() => req.end(data.slice(5)), 300); } else req.end(data);
  });
}

(async () => {
  await new Promise((r) => setTimeout(r, 800));
  let failed = false;
  try {
    const { cookie } = await request('POST', '/api/signup', { email: 'race@test.co', password: 'correct-horse-9' });
    // The first save's body arrives slowly; the second save lands in the middle of it.
    const first = request('PUT', '/api/config', { weather: false }, cookie, true);
    await new Promise((r) => setTimeout(r, 100));
    await request('PUT', '/api/config', { webSearch: false }, cookie);
    await first;
    const { body } = await request('GET', '/api/config', null, cookie);
    failed = body.weather !== false || body.webSearch !== false;
    console.log(failed ? 'FAIL' : 'PASS', 'overlapping saves:', { weather: body.weather, webSearch: body.webSearch });
  } catch (e) {
    failed = true;
    console.log('FAIL', e.message);
  }
  server.kill();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(failed ? 1 : 0);
})();
