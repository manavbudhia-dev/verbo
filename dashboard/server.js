// Verbo dashboard: accounts, skills, connections and keyboard pairing.
// No npm dependencies. Needs Node 22.13 or newer (built-in SQLite).
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const ALLOW_SIGNUP = process.env.ALLOW_SIGNUP !== 'false';
const INSECURE_COOKIES = process.env.INSECURE_COOKIES === '1'; // local testing over http only
const TRUST_PROXY = process.env.TRUST_PROXY === '1';

if (!process.env.SECRET_KEY || process.env.SECRET_KEY.length < 32) {
  console.error('Set SECRET_KEY to a random string of at least 32 characters (e.g. `openssl rand -hex 32`).');
  process.exit(1);
}
// Saved tokens and API keys are encrypted at rest with this key. Losing it loses them.
const ENC_KEY = crypto.createHash('sha256').update(process.env.SECRET_KEY).digest();

fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'verbo.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL, pw TEXT NOT NULL, created INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL, expires INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS configs (user_id INTEGER PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS pair_codes (code TEXT PRIMARY KEY, user_id INTEGER NOT NULL, expires INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS devices (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL, created INTEGER NOT NULL, last_seen INTEGER);
`);

const CATEGORIES = ['write', 'edit', 'translate', 'summarize'];
const MODELS = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5'];
const DEFAULT_SKILLS = [
  ['✨', 'Polish', 'Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length.', 'edit'],
  ['💬', 'Reply', 'The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own.', 'write'],
  ['✂️', 'Shorter', 'Say the same thing in roughly half the words. Keep every fact and the tone.', 'edit'],
  ['👔', 'Formal', "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang.", 'edit'],
  ['😄', 'Casual', 'Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji.', 'edit'],
  ['🌍', 'English', 'Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes.', 'translate'],
  ['🧾', 'Summarize', 'Summarise the text as a few short key points, one per line. Keep names, numbers and dates.', 'summarize'],
  ['🔎', 'Look up', 'Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences.', 'write'],
  ['📋', 'List', "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can.", 'edit'],
].map(([emoji, name, instruction, category]) => ({ emoji, name, instruction, category }));

const defaultConfig = () => ({
  apiKey: '', model: MODELS[0], provider: 'anthropic', openaiKey: '', openaiModel: 'gpt-5.5', webSearch: true, weather: true,
  notionToken: '', calendlyToken: '', customApis: [], skills: DEFAULT_SKILLS,
});

// ---------- helpers ----------

const now = () => Date.now();
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const randomToken = () => crypto.randomBytes(32).toString('base64url');

function encrypt(text) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', ENC_KEY, iv);
  const body = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString('base64');
}

function decrypt(blob) {
  const raw = Buffer.from(blob, 'base64');
  const decipher = crypto.createDecipheriv('aes-256-gcm', ENC_KEY, raw.subarray(0, 12));
  decipher.setAuthTag(raw.subarray(12, 28));
  return Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString('utf8');
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  return salt.toString('hex') + ':' + crypto.scryptSync(password, salt, 64).toString('hex');
}

function checkPassword(password, stored) {
  const [salt, hash] = stored.split(':');
  const actual = crypto.scryptSync(password, Buffer.from(salt, 'hex'), 64);
  return crypto.timingSafeEqual(actual, Buffer.from(hash, 'hex'));
}

function loadConfig(userId) {
  const row = db.prepare('SELECT data, updated FROM configs WHERE user_id = ?').get(userId);
  if (!row) return { config: defaultConfig(), updated: 0 };
  return { config: { ...defaultConfig(), ...JSON.parse(decrypt(row.data)) }, updated: row.updated };
}

function saveConfig(userId, config) {
  const updated = now();
  db.prepare('INSERT INTO configs (user_id, data, updated) VALUES (?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET data = excluded.data, updated = excluded.updated')
    .run(userId, encrypt(JSON.stringify(config)), updated);
  return updated;
}

// What the browser gets: secrets are never sent back, only whether they are set.
function publicConfig(config) {
  return {
    model: config.model, provider: config.provider, openaiModel: config.openaiModel, openaiKeySet: !!config.openaiKey,
    webSearch: config.webSearch, weather: config.weather, skills: config.skills,
    apiKeySet: !!config.apiKey, notionSet: !!config.notionToken, calendlySet: !!config.calendlyToken,
    customApis: config.customApis.map(({ headerValue, ...rest }) => ({ ...rest, headerValueSet: !!headerValue })),
  };
}

class Bad extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

const str = (v, max, what) => {
  if (typeof v !== 'string') throw new Bad(400, `${what} must be text`);
  const t = v.trim();
  if (t.length > max) throw new Bad(400, `${what} is too long`);
  return t;
};

// Merges an update from the dashboard into the stored config.
// For secrets: leaving the field out keeps the saved value, an empty string clears it.
function applyUpdate(config, body) {
  const next = { ...config };
  if ('model' in body) {
    if (!MODELS.includes(body.model)) throw new Bad(400, 'Unknown model');
    next.model = body.model;
  }
  if ('provider' in body) {
    if (!['anthropic', 'openai'].includes(body.provider)) throw new Bad(400, 'Unknown provider');
    next.provider = body.provider;
  }
  if ('openaiModel' in body) {
    if (typeof body.openaiModel !== 'string' || !/^[A-Za-z0-9._:-]{1,80}$/.test(body.openaiModel.trim())) throw new Bad(400, 'That does not look like a model ID');
    next.openaiModel = body.openaiModel.trim();
  }
  for (const flag of ['webSearch', 'weather']) if (flag in body) next[flag] = body[flag] === true;
  for (const secret of ['apiKey', 'openaiKey', 'notionToken', 'calendlyToken']) {
    if (typeof body[secret] === 'string') next[secret] = str(body[secret], 500, 'Token');
  }
  if ('skills' in body) {
    if (!Array.isArray(body.skills) || body.skills.length > 60) throw new Bad(400, 'Too many skills');
    next.skills = body.skills.map((s) => {
      const skill = {
        emoji: str(s.emoji ?? '', 16, 'Emoji'), name: str(s.name, 40, 'Skill name'), instruction: str(s.instruction, 2000, 'Instruction'),
        category: CATEGORIES.includes(s.category) ? s.category : 'edit',
      };
      if (!skill.name || !skill.instruction) throw new Bad(400, 'A skill needs a name and an instruction');
      return skill;
    });
  }
  if ('customApis' in body) {
    if (!Array.isArray(body.customApis) || body.customApis.length > 40) throw new Bad(400, 'Too many APIs');
    next.customApis = body.customApis.map((a) => {
      const api = {
        id: typeof a.id === 'string' && a.id.length <= 40 ? a.id : crypto.randomUUID(),
        name: str(a.name, 60, 'API name'), description: str(a.description ?? '', 600, 'Description'),
        url: str(a.url, 1000, 'Address'), headerName: str(a.headerName ?? '', 100, 'Header name'),
      };
      if (!api.name || !api.url.startsWith('https://')) throw new Bad(400, 'An API needs a name and an https:// address');
      const previous = config.customApis.find((p) => p.id === api.id);
      api.headerValue = typeof a.headerValue === 'string' ? str(a.headerValue, 1000, 'Header value') : previous?.headerValue ?? '';
      return api;
    });
  }
  return next;
}

// Small fixed-window limiter for login, signup and pairing attempts.
const attempts = new Map();
function limit(key, max, windowMs) {
  const t = now();
  const entry = attempts.get(key);
  if (!entry || entry.reset < t) { attempts.set(key, { count: 1, reset: t + windowMs }); return; }
  if (++entry.count > max) throw new Bad(429, 'Too many attempts. Try again later');
}
setInterval(() => {
  const t = now();
  for (const [k, v] of attempts) if (v.reset < t) attempts.delete(k);
  db.prepare('DELETE FROM sessions WHERE expires < ?').run(t);
  db.prepare('DELETE FROM pair_codes WHERE expires < ?').run(t);
}, 10 * 60 * 1000).unref();

const clientIp = (req) => (TRUST_PROXY && req.headers['x-forwarded-for']?.split(',')[0].trim()) || req.socket.remoteAddress;

function readJson(req) {
  return new Promise((resolve, reject) => {
    if (!(req.headers['content-type'] || '').startsWith('application/json')) return reject(new Bad(415, 'Send JSON'));
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 256 * 1024) { reject(new Bad(413, 'Too large')); req.destroy(); } else chunks.push(c);
    });
    req.on('end', () => {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
        resolve(body);
      } catch { reject(new Bad(400, 'Invalid JSON')); }
    });
    req.on('error', reject);
  });
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
  res.end(JSON.stringify(body));
}

const SESSION_MS = 30 * 24 * 60 * 60 * 1000;
const cookie = (value, maxAgeSec) =>
  `verbo_session=${value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAgeSec}${INSECURE_COOKIES ? '' : '; Secure'}`;

function startSession(res, userId) {
  const token = randomToken();
  db.prepare('INSERT INTO sessions (token_hash, user_id, expires) VALUES (?, ?, ?)').run(sha(token), userId, now() + SESSION_MS);
  return { 'Set-Cookie': cookie(token, SESSION_MS / 1000) };
}

function sessionToken(req) {
  return /(?:^|;\s*)verbo_session=([^;]+)/.exec(req.headers.cookie || '')?.[1];
}

function requireUser(req) {
  const token = sessionToken(req);
  const row = token && db.prepare('SELECT user_id FROM sessions WHERE token_hash = ? AND expires > ?').get(sha(token), now());
  if (!row) throw new Bad(401, 'Sign in first');
  return row.user_id;
}

function requireDevice(req) {
  const token = /^Bearer (.+)$/.exec(req.headers.authorization || '')?.[1];
  const row = token && db.prepare('SELECT id, user_id FROM devices WHERE token_hash = ?').get(sha(token));
  if (!row) throw new Bad(401, 'This keyboard is not paired');
  db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?').run(now(), row.id);
  return row;
}

const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const newCode = () => Array.from(crypto.randomBytes(8), (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');

// ---------- routes ----------

const routes = {
  'GET /api/me': (req) => {
    const token = sessionToken(req);
    const row = token && db.prepare('SELECT u.email FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires > ?').get(sha(token), now());
    return { body: { email: row?.email ?? null, allowSignup: ALLOW_SIGNUP } };
  },

  'POST /api/signup': async (req, res) => {
    if (!ALLOW_SIGNUP) throw new Bad(403, 'Sign-up is closed on this server');
    limit('signup:' + clientIp(req), 10, 60 * 60 * 1000);
    const body = await readJson(req);
    const email = str(body.email, 200, 'Email').toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Bad(400, 'Enter a valid email address');
    if (typeof body.password !== 'string' || body.password.length < 10 || body.password.length > 200) throw new Bad(400, 'Use a password of at least 10 characters');
    if (db.prepare('SELECT 1 FROM users WHERE email = ?').get(email)) throw new Bad(409, 'That email already has an account');
    const info = db.prepare('INSERT INTO users (email, pw, created) VALUES (?, ?, ?)').run(email, hashPassword(body.password), now());
    return { body: { email }, headers: startSession(res, Number(info.lastInsertRowid)) };
  },

  'POST /api/login': async (req, res) => {
    limit('login:' + clientIp(req), 10, 15 * 60 * 1000);
    const body = await readJson(req);
    const email = str(body.email ?? '', 200, 'Email').toLowerCase();
    const user = db.prepare('SELECT id, pw FROM users WHERE email = ?').get(email);
    if (!user || typeof body.password !== 'string' || !checkPassword(body.password, user.pw)) throw new Bad(401, 'Wrong email or password');
    return { body: { email }, headers: startSession(res, user.id) };
  },

  'POST /api/logout': (req) => {
    const token = sessionToken(req);
    if (token) db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(sha(token));
    return { body: {}, headers: { 'Set-Cookie': cookie('', 0) } };
  },

  'GET /api/config': (req) => ({ body: publicConfig(loadConfig(requireUser(req)).config) }),

  'PUT /api/config': async (req) => {
    const userId = requireUser(req);
    const next = applyUpdate(loadConfig(userId).config, await readJson(req));
    saveConfig(userId, next);
    return { body: publicConfig(next) };
  },

  'GET /api/devices': (req) => ({
    body: db.prepare('SELECT id, name, created, last_seen AS lastSeen FROM devices WHERE user_id = ? ORDER BY created DESC').all(requireUser(req)),
  }),

  'POST /api/devices/code': (req) => {
    const userId = requireUser(req);
    db.prepare('DELETE FROM pair_codes WHERE user_id = ?').run(userId);
    const code = newCode();
    db.prepare('INSERT INTO pair_codes (code, user_id, expires) VALUES (?, ?, ?)').run(code, userId, now() + 10 * 60 * 1000);
    return { body: { code, minutes: 10 } };
  },

  'POST /api/devices/remove': async (req) => {
    const userId = requireUser(req);
    const body = await readJson(req);
    db.prepare('DELETE FROM devices WHERE id = ? AND user_id = ?').run(Number(body.id), userId);
    return { body: {} };
  },

  // --- called by the keyboard app ---

  'POST /api/device/pair': async (req) => {
    limit('pair:' + clientIp(req), 10, 15 * 60 * 1000);
    const body = await readJson(req);
    const code = str(body.code ?? '', 20, 'Code').toUpperCase().replace(/[^A-Z0-9]/g, '');
    const row = db.prepare('SELECT user_id FROM pair_codes WHERE code = ? AND expires > ?').get(code, now());
    if (!row) throw new Bad(400, 'That code is wrong or has expired');
    db.prepare('DELETE FROM pair_codes WHERE code = ?').run(code);
    const token = randomToken();
    db.prepare('INSERT INTO devices (user_id, name, token_hash, created) VALUES (?, ?, ?, ?)')
      .run(row.user_id, str(body.name ?? 'Phone', 60, 'Name') || 'Phone', sha(token), now());
    return { body: { token } };
  },

  'GET /api/device/config': (req) => {
    const { config, updated } = loadConfig(requireDevice(req).user_id);
    return { body: { ...config, updated } };
  },
};

const PUBLIC = path.join(__dirname, 'public');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml' };
const SECURITY = {
  'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'",
};

function serveStatic(pathname, res) {
  const file = path.join(PUBLIC, pathname === '/' ? 'index.html' : pathname);
  if (!file.startsWith(PUBLIC + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    return res.end('Not found');
  }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache', ...SECURITY });
  fs.createReadStream(file).pipe(res);
}

http.createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://x');
  if (!pathname.startsWith('/api/')) {
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
    return serveStatic(pathname, res);
  }
  try {
    const handler = routes[`${req.method} ${pathname}`];
    if (!handler) throw new Bad(404, 'Not found');
    const { body, headers } = await handler(req, res);
    send(res, 200, body, headers);
  } catch (e) {
    if (!(e instanceof Bad)) console.error(e);
    send(res, e instanceof Bad ? e.status : 500, { error: e instanceof Bad ? e.message : 'Server error' });
  }
}).listen(PORT, () => console.log(`Verbo dashboard listening on port ${PORT}`));
