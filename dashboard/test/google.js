// Google Meet flow against a stand-in for Google. Run: node dashboard/test/google.js (starts its own servers).
'use strict';
const http = require('node:http');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 8124;
const FAKE = 8125;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verbo-test-'));
let revoked = false;
const seen = []; // calls the fake Google received: { method, url, body }

// Plays the two Google endpoints the dashboard calls.
const fake = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/token') {
      const form = new URLSearchParams(body);
      if (form.get('grant_type') === 'authorization_code') {
        if (form.get('code') === 'meet-only') return res.end(JSON.stringify({ access_token: 'a1', refresh_token: 'r1', scope: 'https://www.googleapis.com/auth/meetings.space.created' }));
        return res.end(JSON.stringify(form.get('code') === 'good-code' ? { access_token: 'a1', refresh_token: 'r1' } : { error: 'invalid_grant' }));
      }
      if (revoked || form.get('refresh_token') !== 'r1') { res.statusCode = 400; return res.end(JSON.stringify({ error: 'invalid_grant' })); }
      return res.end(JSON.stringify({ access_token: 'a2' }));
    }
    if (req.headers.authorization === 'Bearer a2' && req.url !== '/spaces') {
      seen.push({ method: req.method, url: req.url, body: body ? JSON.parse(body) : null });
      if (req.url.startsWith('/calendar/v3/calendars/primary/events') && req.method === 'POST') {
        const b = JSON.parse(body);
        return res.end(JSON.stringify({ summary: b.summary, start: b.start, htmlLink: 'https://calendar.google.com/event?eid=1', hangoutLink: b.conferenceData ? 'https://meet.google.com/cal-meet-xyz' : undefined }));
      }
      if (req.url.startsWith('/calendar/v3/calendars/primary/events')) return res.end(JSON.stringify({ items: [{ summary: 'Dentist', start: { dateTime: '2026-11-16T10:00:00+05:30' } }] }));
      if (req.url.startsWith('/gmail/v1/users/me/messages?')) return res.end(JSON.stringify({ messages: [{ id: 'm1' }] }));
      if (req.url.startsWith('/gmail/v1/users/me/messages/m1')) return res.end(JSON.stringify({ id: 'm1', snippet: 'Invoice attached', payload: { headers: [{ name: 'From', value: 'Asha <asha@x.co>' }, { name: 'Subject', value: 'Invoice' }, { name: 'Date', value: 'Fri' }] } }));
      if (req.url === '/gmail/v1/users/me/messages/send' || req.url === '/gmail/v1/users/me/drafts') return res.end(JSON.stringify({ id: 'sent1' }));
    }
    if (req.url === '/spaces' && req.headers.authorization === 'Bearer a2') return res.end(JSON.stringify({ meetingUri: 'https://meet.google.com/abc-defg-hij' }));
    res.statusCode = 401;
    res.end('{}');
  });
}).listen(FAKE);

const server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
  env: {
    ...process.env, PORT, DATA_DIR: dir, SECRET_KEY: 'x'.repeat(40), INSECURE_COOKIES: '1', NODE_NO_WARNINGS: '1',
    GOOGLE_CLIENT_ID: 'cid', GOOGLE_CLIENT_SECRET: 'csecret', PUBLIC_URL: 'http://localhost:' + PORT,
    ADMIN_EMAILS: 'meet@test.co', // Meet, Calendar and Gmail on the phone are Verbo Pro features
    GOOGLE_TOKEN_URL: `http://localhost:${FAKE}/token`, GOOGLE_MEET_URL: `http://localhost:${FAKE}/spaces`,
    GOOGLE_API_URL: `http://localhost:${FAKE}`, GOOGLE_GMAIL_URL: `http://localhost:${FAKE}`,
  },
  stdio: 'ignore',
});

function request(method, url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : '';
    const req = http.request({ port: PORT, method, path: url, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), ...headers } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(text || '{}'), location: res.headers.location, cookie: (res.headers['set-cookie'] || [''])[0].split(';')[0] }));
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
    const { cookie } = await request('POST', '/api/signup', { email: 'meet@test.co', password: 'correct-horse-9' });
    const auth = { Cookie: cookie };
    check('start needs a signed-in user', (await request('GET', '/api/google/start')).status === 401);
    const start = await request('GET', '/api/google/start', null, auth);
    const state = new URL(start.location).searchParams.get('state');
    check('start redirects to Google with the Meet scope', start.status === 302 && start.location.includes('meetings.space.created') && !!state);
    check('a made-up state is refused', (await request('GET', '/api/google/callback?code=good-code&state=nope')).location === '/?google=failed');
    // No cookie on the callback, as in a real browser coming back from Google.
    check('callback connects the account', (await request('GET', `/api/google/callback?code=good-code&state=${state}`)).location === '/?google=connected');
    check('the state cannot be used twice', (await request('GET', `/api/google/callback?code=good-code&state=${state}`)).location === '/?google=failed');
    const cfg = (await request('GET', '/api/config', null, auth)).body;
    check('dashboard shows connected without exposing the token', cfg.googleConnected === true && !('googleRefresh' in cfg));

    const { code } = (await request('POST', '/api/devices/code', null, auth)).body;
    const { token } = (await request('POST', '/api/device/pair', { code, name: 'Phone' })).body;
    const device = { Authorization: 'Bearer ' + token };
    const deviceCfg = (await request('GET', '/api/device/config', null, device)).body;
    check('keyboard learns Google is connected but not the token', deviceCfg.googleConnected === true && !('googleRefresh' in deviceCfg));
    check('an unpaired caller cannot make a link', (await request('POST', '/api/device/meet')).status === 401);
    const meet = await request('POST', '/api/device/meet', null, device);
    check('paired keyboard gets a Meet link', meet.status === 200 && meet.body.url === 'https://meet.google.com/abc-defg-hij');


    // Calendar and Gmail on the same sign-in.
    const g = (body) => request('POST', '/api/device/google', body, device);
    check('the keyboard learns which Google services are on', deviceCfg.googleServices.join() === 'meet,calendar,gmail' && !('googleScopes' in deviceCfg));
    const ev = await g({ action: 'calendar_create', title: 'Lunch with Sam', start: '2026-11-20T13:00', end: '2026-11-20T14:00', timeZone: 'Asia/Kolkata', attendees: 'sam@x.co', meet: true });
    const sentEvent = seen.at(-1).body;
    check('an event is saved with invite and Meet', ev.status === 200 && ev.body.result.includes('Meet: https://meet.google.com/cal-meet-xyz')
      && sentEvent.start.dateTime === '2026-11-20T13:00:00' && sentEvent.start.timeZone === 'Asia/Kolkata' && sentEvent.attendees[0].email === 'sam@x.co' && seen.at(-1).url.includes('sendUpdates=all'));
    check('upcoming events are listed', (await g({ action: 'calendar_events', days: 3 })).body.result.includes('Dentist'));
    check('a bad time is refused clearly', (await g({ action: 'calendar_create', title: 'x', start: 'tomorrow', end: 'later' })).status === 400);
    check('gmail search returns sender and subject', (await g({ action: 'gmail_search', query: 'invoice' })).body.result.includes('Asha <asha@x.co> | Invoice'));
    const mail = await g({ action: 'gmail_send', to: 'boss@x.co', subject: 'Late today\r\nBcc: evil@x.co', body: 'Running 20 min late' });
    const raw = Buffer.from(seen.at(-1).body.raw, 'base64url').toString();
    check('an email is sent, headers cannot be injected', mail.status === 200 && raw.includes('To: boss@x.co') && !raw.includes('\r\nBcc:') && Buffer.from(raw.split('\r\n\r\n')[1], 'base64').toString() === 'Running 20 min late');
    check('a bad address is refused', (await g({ action: 'gmail_send', to: 'not-an-email', subject: 's', body: 'b' })).status === 400);
    check('a draft is saved', (await g({ action: 'gmail_draft', subject: 'Idea', body: 'Notes' })).body.result.includes('Draft'));
    check('unknown actions are refused', (await g({ action: 'delete_all' })).status === 400);

    // A sign-in where the user unticked Gmail and Calendar.
    const again = new URL((await request('GET', '/api/google/start', null, auth)).location).searchParams.get('state');
    await request('GET', `/api/google/callback?code=meet-only&state=${again}`);
    check('a partial sign-in turns on only what was allowed', (await request('GET', '/api/config', null, auth)).body.googleServices.join() === 'meet');
    check('and Gmail then asks to reconnect', (await g({ action: 'gmail_search', query: 'x' })).body.error?.includes('Reconnect'));

    revoked = true;
    check('revoked sign-in is reported', (await request('POST', '/api/device/meet', null, device)).status === 409);
    check('and forgotten, so the dashboard offers Connect again', (await request('GET', '/api/config', null, auth)).body.googleConnected === false);
  } catch (e) {
    check('no exception: ' + e.message, false);
  }
  server.kill();
  fake.close();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(results.every(Boolean) ? 0 : 1);
})();
