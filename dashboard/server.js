// Verbo dashboard: accounts, skills, connections, keyboard pairing and a setup assistant.
// One npm dependency (the Anthropic SDK, for the assistant). Needs Node 22.13 or newer (built-in SQLite).
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

// Google sign-in, used only to create Google Meet links. Optional: without these the feature is simply off.
const GOOGLE_ID = process.env.GOOGLE_CLIENT_ID || '';
const GOOGLE_SECRET = process.env.GOOGLE_CLIENT_SECRET || '';
const PUBLIC_URL = (process.env.PUBLIC_URL || '').replace(/\/+$/, ''); // e.g. https://verbo.example.com
const GOOGLE_ON = !!(GOOGLE_ID && GOOGLE_SECRET && PUBLIC_URL);
const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
// Overridable so the tests can stand in for Google.
const GOOGLE_TOKEN_URL = process.env.GOOGLE_TOKEN_URL || 'https://oauth2.googleapis.com/token';
const GOOGLE_MEET_URL = process.env.GOOGLE_MEET_URL || 'https://meet.googleapis.com/v2/spaces';
const GOOGLE_API = (process.env.GOOGLE_API_URL || 'https://www.googleapis.com').replace(/\/+$/, '');
const GMAIL_API = (process.env.GOOGLE_GMAIL_URL || 'https://gmail.googleapis.com').replace(/\/+$/, '');
// One sign-in covers all three. Each service is on only if the user allowed every scope it needs.
const GOOGLE_SCOPES = {
  meet: ['https://www.googleapis.com/auth/meetings.space.created'],
  calendar: ['https://www.googleapis.com/auth/calendar.events'],
  gmail: ['https://www.googleapis.com/auth/gmail.compose', 'https://www.googleapis.com/auth/gmail.readonly'],
};
const GOOGLE_SCOPE = Object.values(GOOGLE_SCOPES).flat().join(' ');

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
const MODELS = ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5']; // first is the default: cheapest
// The Notion skill used to only name the page; now it opens it. Saved copies of the old wording are upgraded on load.
const OLD_NOTION = "Search the user's Notion for the page the text describes and return its title and link. If Notion is not connected, say so in one line.";
const NOTION_INSTRUCTION = 'Find the Notion page that best matches the text, or the copied message if there is one, with notion_search: try the key names and topics, and search again with fewer words if nothing matches. Reply with exactly two lines: the page title, then Last edited: and when it was last edited in plain words (like Today at 10:42 AM), and end with [[open:PAGE_URL]]. If Notion is not connected, reply: Connect Notion in Verbo to search your pages, and end with [[open:https://www.notion.so]].';
const DEFAULT_SKILLS = [
  ['✨', 'Polish', 'Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length.', 'edit', 'p'],
  ['💬', 'Reply', 'The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own.', 'write', 'r'],
  ['✂️', 'Shorter', 'Say the same thing in roughly half the words. Keep every fact and the tone.', 'edit'],
  ['👔', 'Formal', "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang.", 'edit'],
  ['😄', 'Casual', 'Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji.', 'edit'],
  ['🌍', 'English', 'Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes.', 'translate', 't'],
  ['🧾', 'Summarize', 'Summarise the text as a few short key points, one per line. Keep names, numbers and dates.', 'summarize', 's'],
  ['🔎', 'Look up', 'Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences.', 'write', 'g'],
  ['📍', 'Place', 'The text names a place. Find it with the place search and return its name, address and a Google Maps link, with the map marker line.', 'write', 'l'],
  ['👤', 'Who is', 'The text or the context names a person. Look them up on the web and reply with one item per line: their full name; a one-line headline with their role and organisation; then Current: their current role, Previous: their previous role, Education: where they studied; and last a LinkedIn people search link, https://www.linkedin.com/search/results/people/?keywords= followed by the name with spaces written as %20. Leave out any line you cannot verify. If you cannot tell who they are, say so in one line instead of guessing.', 'write', 'w'],
  ['🗒️', 'Notion', NOTION_INSTRUCTION, 'write', 'n'],
  ['📧', 'Email', 'Turn the text into a clear email with a short subject. Return the email text, then a final [[open:mailto:?subject=...&body=...]] line with both URL-encoded so it opens as a ready draft in Gmail.', 'write'],
  ['💼', 'LinkedIn post', 'Turn the text into a LinkedIn post: a strong first line, short paragraphs, no more than three hashtags at the end. Return the post, then a final [[open:https://www.linkedin.com/feed/?shareActive=true&text=...]] line with the post URL-encoded.', 'write'],
  ['📋', 'List', "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can.", 'edit'],
].map(([emoji, name, instruction, category, key = '']) => ({ emoji, name, instruction, category, key }));

// Auto actions: when a copied message has one of these, the keyboard offers (or runs) the skill.
const TRIGGER_KINDS = ['person', 'time', 'place', 'link', 'email', 'phone', 'foreign', 'keyword', 'any'];
const DEFAULT_TRIGGERS = [
  { kind: 'person', keyword: '', skill: 'Who is', auto: false },
  { kind: 'time', keyword: '', skill: 'Schedule it', auto: false },
  { kind: 'place', keyword: '', skill: 'Place', auto: false },
  { kind: 'foreign', keyword: '', skill: 'Understand any language', auto: false },
];

const defaultConfig = () => ({
  apiKey: '', model: MODELS[0], provider: 'anthropic', openaiKey: '', openaiModel: 'gpt-5.5',
  customBase: 'https://api.airouter.in/v1', customKey: '', customModel: 'anthropic/claude-haiku-5.5', webSearch: true, weather: true,
  notionToken: '', calendlyToken: '', slackToken: '', githubToken: '', todoistToken: '', linearToken: '', discordWebhook: '', customApis: [], skills: DEFAULT_SKILLS, googleRefresh: '', googleScopes: '',
  triggers: DEFAULT_TRIGGERS,
  memory: [], // facts the user told the keyboard: { label, value }
  keyboard: {}, // the phone's own settings (look, sound, wheel...), kept here so they can be changed from the web
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

const DUMMY_HASH = hashPassword(crypto.randomBytes(16).toString('hex'));

function loadConfig(userId) {
  const row = db.prepare('SELECT data, updated FROM configs WHERE user_id = ?').get(userId);
  if (!row) return { config: defaultConfig(), updated: 0 };
  const config = { ...defaultConfig(), ...JSON.parse(decrypt(row.data)) };
  config.skills = config.skills.map((s) => (s.instruction === OLD_NOTION ? { ...s, instruction: NOTION_INSTRUCTION } : s));
  return { config, updated: row.updated };
}

function saveConfig(userId, config) {
  const updated = now();
  db.prepare('INSERT INTO configs (user_id, data, updated) VALUES (?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET data = excluded.data, updated = excluded.updated')
    .run(userId, encrypt(JSON.stringify(config)), updated);
  return updated;
}

// Tokens for apps the keyboard can act in. Kept on the server and synced to paired phones, which make the calls.
const APP_TOKENS = ['notionToken', 'calendlyToken', 'slackToken', 'githubToken', 'todoistToken', 'linearToken', 'discordWebhook'];

// What the browser gets: secrets are never sent back, only whether they are set.
const hint = (secret) => (secret && secret.length >= 8 ? secret.slice(-4) : '');

// Keyboard settings the dashboard may store: name -> allowed values (list), [min, max] for numbers, or a type.
const THEMES = ['system', 'dark', 'light', 'amoled', 'custom', 'ocean', 'rose', 'forest', 'sunset', 'mint'];
const KEYBOARD_FIELDS = {
  assistantOn: 'boolean', numberRow: 'boolean', sound: 'boolean', haptic: 'boolean', autoCorrect: 'boolean',
  suggestions: 'boolean', wheelOn: 'boolean', batterySaver: 'boolean',
  agentMode: ['auto', 'ask', 'manual'], theme: THEMES,
  keyShape: ['raised', 'rounded', 'square', 'bubble', 'glass', 'outline', 'flat'],
  soundPack: ['system', 'typewriter', 'mechanical', 'pop', 'drop', 'soft'], hapticStrength: ['light', 'medium', 'strong'],
  heightPercent: [80, 130], soundVolume: [5, 100], customAccent: [-2147483648, 2147483647],
  wheel: 'list', languages: 'list',
};
const KEYBOARD_DEFAULTS = {
  assistantOn: true, numberRow: false, sound: true, haptic: true, autoCorrect: true, suggestions: true, wheelOn: true,
  batterySaver: true, agentMode: 'auto', theme: 'system', keyShape: 'raised', soundPack: 'system', hapticStrength: 'medium',
  heightPercent: 100, soundVolume: 60, wheel: ['translate', 'rewrite', 'ai', 'meet', 'voice', 'clips'],
  languages: ['English', 'Hindi', 'Spanish', 'French', 'Chinese (Simplified)'],
};

function cleanKeyboard(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Bad(400, 'Keyboard settings must be an object');
  const out = {};
  for (const [name, value] of Object.entries(input)) {
    const rule = KEYBOARD_FIELDS[name];
    if (!rule) continue; // settings this server doesn't know are ignored, so older and newer phones can both sync
    if (rule === 'boolean') { if (typeof value === 'boolean') out[name] = value; }
    else if (rule === 'list') {
      if (Array.isArray(value) && value.length <= 40 && value.every((v) => typeof v === 'string' && v.length <= 80)) out[name] = value;
    } else if (typeof rule[0] === 'number') {
      if (Number.isInteger(value) && value >= rule[0] && value <= rule[1]) out[name] = value;
    } else if (rule.includes(value)) out[name] = value;
  }
  return out;
}

function publicConfig(config) {
  return {
    model: config.model, provider: config.provider, openaiModel: config.openaiModel, openaiKeySet: !!config.openaiKey,
    customBase: config.customBase, customModel: config.customModel, customKeySet: !!config.customKey,
    webSearch: config.webSearch, weather: config.weather, skills: config.skills,
    googleAvailable: GOOGLE_ON, googleConnected: !!config.googleRefresh, googleServices: googleServices(config),
    keyboard: { ...KEYBOARD_DEFAULTS, ...config.keyboard },
    // The last four characters, so a saved key can be recognised without ever being shown.
    apiKeyHint: hint(config.apiKey), openaiKeyHint: hint(config.openaiKey), customKeyHint: hint(config.customKey),
    apiKeySet: !!config.apiKey, notionSet: !!config.notionToken, calendlySet: !!config.calendlyToken,
    appsSet: Object.fromEntries(APP_TOKENS.map((t) => [t, !!config[t]])),
    customApis: config.customApis.map(({ headerValue, ...rest }) => ({ ...rest, headerValueSet: !!headerValue })),
    triggers: config.triggers,
    memory: config.memory,
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
    if (!['anthropic', 'openai', 'custom'].includes(body.provider)) throw new Bad(400, 'Unknown provider');
    next.provider = body.provider;
  }
  if ('openaiModel' in body) {
    if (typeof body.openaiModel !== 'string' || !/^[A-Za-z0-9._:-]{1,80}$/.test(body.openaiModel.trim())) throw new Bad(400, 'That does not look like a model ID');
    next.openaiModel = body.openaiModel.trim();
  }
  if ('customBase' in body) {
    const base = typeof body.customBase === 'string' ? body.customBase.trim().replace(/\/+$/, '') : '';
    // Plain http is accepted only in local test mode, where the tests stand in for a gateway.
    if (!(INSECURE_COOKIES ? /^https?:\/\/[^\s]{3,300}$/ : /^https:\/\/[^\s]{3,300}$/).test(base)) throw new Bad(400, 'The gateway address must start with https://');
    next.customBase = base;
  }
  if ('customModel' in body) {
    if (typeof body.customModel !== 'string' || !/^[A-Za-z0-9._:\/-]{1,100}$/.test(body.customModel.trim())) throw new Bad(400, 'That does not look like a model name');
    next.customModel = body.customModel.trim();
  }
  if ('keyboard' in body) next.keyboard = { ...config.keyboard, ...cleanKeyboard(body.keyboard) };
  for (const flag of ['webSearch', 'weather']) if (flag in body) next[flag] = body[flag] === true;
  for (const secret of ['apiKey', 'openaiKey', 'customKey', ...APP_TOKENS]) {
    if (typeof body[secret] === 'string') next[secret] = str(body[secret], 500, 'Token');
  }
  if (next.discordWebhook && next.discordWebhook !== config.discordWebhook && !/^https:\/\/(discord|discordapp)\.com\/api\/webhooks\/\d+\/[\w-]+$/.test(next.discordWebhook)) {
    throw new Bad(400, 'Paste the whole webhook address, starting https://discord.com/api/webhooks/');
  }
  if ('skills' in body) {
    if (!Array.isArray(body.skills) || body.skills.length > 60) throw new Bad(400, 'Too many skills');
    next.skills = body.skills.map((s) => {
      const skill = {
        emoji: str(s.emoji ?? '', 16, 'Emoji'), name: str(s.name, 40, 'Skill name'), instruction: str(s.instruction, 2000, 'Instruction'),
        category: CATEGORIES.includes(s.category) ? s.category : 'edit',
        key: typeof s.key === 'string' && /^[a-z]$/.test(s.key.trim().toLowerCase()) ? s.key.trim().toLowerCase() : '',
      };
      if (!skill.name || !skill.instruction) throw new Bad(400, 'A skill needs a name and an instruction');
      return skill;
    });
  }
  if ('memory' in body) {
    if (!Array.isArray(body.memory) || body.memory.length > 100) throw new Bad(400, 'Too many things to remember');
    next.memory = body.memory.map((f) => {
      const fact = { label: str(f?.label ?? '', 60, 'Name'), value: str(f?.value ?? '', 500, 'Value') };
      if (!fact.label || !fact.value) throw new Bad(400, 'Each memory needs a name and a value');
      return fact;
    });
  }
  if ('triggers' in body) {
    if (!Array.isArray(body.triggers) || body.triggers.length > 20) throw new Bad(400, 'Too many auto actions');
    next.triggers = body.triggers.map((t) => {
      if (!t || !TRIGGER_KINDS.includes(t.kind)) throw new Bad(400, 'Unknown kind of auto action');
      const trigger = { kind: t.kind, keyword: str(t.keyword ?? '', 100, 'Words'), skill: str(t.skill ?? '', 40, 'Skill'), auto: t.auto === true };
      if (!trigger.skill) throw new Bad(400, 'An auto action needs a skill');
      if (trigger.kind === 'keyword' && !trigger.keyword) throw new Bad(400, 'Type the words to look for');
      return trigger;
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
const oauthStates = new Map(); // one-time values for Google sign-in in progress
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
  for (const [k, v] of oauthStates) if (v.expires < t) oauthStates.delete(k);
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
    // Always do the (slow) password check, so response time doesn't reveal which emails have accounts.
    const ok = typeof body.password === 'string' && checkPassword(body.password, user ? user.pw : DUMMY_HASH);
    if (!user || !ok) throw new Bad(401, 'Wrong email or password');
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
    // Read the body first. Loading the stored config before this await let a second save slip in
    // between, and the slower one then wrote back a stale copy and silently undid the other.
    const body = await readJson(req);
    const next = applyUpdate(loadConfig(userId).config, body);
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
    // The Google sign-in stays on the server; the phone only learns whether it exists.
    const { googleRefresh, googleScopes, ...rest } = config;
    return { body: { ...rest, googleConnected: GOOGLE_ON && !!googleRefresh, googleServices: googleServices(config), updated } };
  },

  // The phone sends its own changes (a key typed on the phone, a setting flipped there) so that the
  // dashboard never overwrites them with an older copy. Only the fields it lists are touched.
  'PUT /api/device/config': async (req) => {
    const device = requireDevice(req);
    const body = await readJson(req);
    const { config } = loadConfig(device.user_id);
    const next = applyUpdate(config, body);
    const updated = saveConfig(device.user_id, next);
    const { googleRefresh, googleScopes, ...rest } = next;
    return { body: { ...rest, googleConnected: GOOGLE_ON && !!googleRefresh, googleServices: googleServices(next), updated } };
  },

  // --- the setup assistant ---

  'POST /api/chat': async (req) => {
    const userId = requireUser(req);
    limit('chat:' + userId, 80, 60 * 60 * 1000);
    const body = await readJson(req);
    if (!Array.isArray(body.messages) || body.messages.length === 0) throw new Bad(400, 'Say something first');
    const messages = body.messages.slice(-16).map((m) => ({
      role: m.role === 'assistant' ? 'assistant' : 'user', content: str(String(m.content ?? ''), 4000, 'Message'),
    })).filter((m) => m.content);
    if (messages.length === 0 || messages[messages.length - 1].role !== 'user') throw new Bad(400, 'Say something first');
    while (messages[0].role !== 'user') messages.shift(); // a conversation has to open with the user
    const { config } = loadConfig(userId);
    const text = await callModel(config, chatSystem(config), messages);
    return { body: parseChat(text) };
  },

  // --- Google Meet ---

  'GET /api/google/start': (req) => {
    if (!GOOGLE_ON) throw new Bad(400, 'Google sign-in is not set up on this server');
    const userId = requireUser(req);
    const state = randomToken();
    oauthStates.set(state, { userId, expires: now() + 10 * 60 * 1000 });
    const query = new URLSearchParams({
      client_id: GOOGLE_ID, redirect_uri: PUBLIC_URL + '/api/google/callback', response_type: 'code',
      scope: GOOGLE_SCOPE, access_type: 'offline', prompt: 'consent', include_granted_scopes: 'true', state,
    });
    return { status: 302, body: {}, headers: { Location: GOOGLE_AUTH_URL + '?' + query } };
  },

  // Google sends the browser back here. The session cookie is not sent on this hop (it comes from another
  // site), so the one-time state value made in /start is what ties the answer to the signed-in user.
  'GET /api/google/callback': async (req) => {
    const params = new URL(req.url, 'http://x').searchParams;
    const pending = oauthStates.get(params.get('state') || '');
    oauthStates.delete(params.get('state') || '');
    const back = (result) => ({ status: 302, body: {}, headers: { Location: '/?google=' + result } });
    if (!pending || pending.expires < now() || !params.get('code')) return back('failed');
    const res = await fetch(GOOGLE_TOKEN_URL, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code: params.get('code'), client_id: GOOGLE_ID, client_secret: GOOGLE_SECRET,
        redirect_uri: PUBLIC_URL + '/api/google/callback', grant_type: 'authorization_code',
      }),
    });
    const tokens = await res.json().catch(() => ({}));
    if (!res.ok || !tokens.refresh_token) return back('failed');
    const { config } = loadConfig(pending.userId);
    // Google lists what the user actually allowed; they can untick Gmail or Calendar on the consent page.
    saveConfig(pending.userId, { ...config, googleRefresh: tokens.refresh_token, googleScopes: typeof tokens.scope === 'string' ? tokens.scope : GOOGLE_SCOPE });
    return back('connected');
  },

  'POST /api/google/disconnect': (req) => {
    const userId = requireUser(req);
    const { config } = loadConfig(userId);
    saveConfig(userId, { ...config, googleRefresh: '', googleScopes: '' });
    return { body: publicConfig({ ...config, googleRefresh: '', googleScopes: '' }) };
  },

  /** Called by a paired keyboard: makes a new Google Meet room in the owner's account and returns its link. */
  'POST /api/device/meet': async (req) => {
    const device = requireDevice(req);
    limit('meet:' + device.user_id, 30, 60 * 60 * 1000);
    return { body: { url: await meetLink(device.user_id) } };
  },

  /** Called by a paired keyboard while the assistant works: one Calendar or Gmail action in the owner's account. */
  'POST /api/device/google': async (req) => {
    const device = requireDevice(req);
    limit('google:' + device.user_id, 120, 60 * 60 * 1000);
    const body = await readJson(req);
    return { body: { result: await googleAction(device.user_id, body) } };
  },
};

// ---------- Google: Meet, Calendar and Gmail on the owner's sign-in ----------

/** The services the stored sign-in allows. Sign-ins made before Calendar and Gmail were added cover Meet only. */
function googleServices(config) {
  if (!GOOGLE_ON || !config.googleRefresh) return [];
  const granted = (config.googleScopes || GOOGLE_SCOPES.meet[0]).split(/\s+/);
  return Object.entries(GOOGLE_SCOPES).filter(([, scopes]) => scopes.every((s) => granted.includes(s))).map(([name]) => name);
}

/** A fresh access token from the stored refresh token. A revoked sign-in is forgotten so the dashboard offers Connect again. */
async function googleAccess(userId) {
  const { config } = loadConfig(userId);
  if (!GOOGLE_ON || !config.googleRefresh) throw new Bad(409, 'Connect Google on the dashboard first');
  const tokenRes = await fetch(GOOGLE_TOKEN_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: GOOGLE_ID, client_secret: GOOGLE_SECRET, refresh_token: config.googleRefresh, grant_type: 'refresh_token' }),
  });
  const tokens = await tokenRes.json().catch(() => ({}));
  if (!tokenRes.ok || !tokens.access_token) {
    if (tokens.error === 'invalid_grant') saveConfig(userId, { ...config, googleRefresh: '', googleScopes: '' });
    throw new Bad(409, 'Google sign-in has expired. Connect Google again on the dashboard');
  }
  return { token: tokens.access_token, services: googleServices(config) };
}

async function googleFetch(token, method, url, body) {
  const res = await fetch(url, {
    method, headers: { Authorization: 'Bearer ' + token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(20000),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 || res.status === 403) {
    throw new Bad(409, 'Google refused: ' + (data.error?.message || 'no permission') + '. Reconnect Google on the dashboard and allow everything it asks for');
  }
  if (!res.ok) throw new Bad(502, 'Google returned an error: ' + (data.error?.message || res.status));
  return data;
}

async function meetLink(userId) {
  const { token } = await googleAccess(userId);
  const meetRes = await fetch(GOOGLE_MEET_URL, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: '{}' });
  const space = await meetRes.json().catch(() => ({}));
  if (!meetRes.ok || typeof space.meetingUri !== 'string') throw new Bad(502, 'Google could not create the meeting. Try again');
  return space.meetingUri;
}

const oneLine = (v, max) => String(v ?? '').replace(/[\r\n]+/g, ' ').trim().slice(0, max);
const EMAIL = /^[^\s@,<>]+@[^\s@,<>]+\.[^\s@,<>]+$/;
const emailList = (v, what) => {
  const list = oneLine(v, 1000).split(/[,;]\s*/).map((s) => s.trim()).filter(Boolean);
  for (const e of list) if (!EMAIL.test(e)) throw new Bad(400, `${what}: "${e}" is not an email address`);
  return list;
};
const when = (v, what) => {
  const s = oneLine(v, 40);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(Z|[+-]\d{2}:\d{2})?$/.test(s)) throw new Bad(400, `${what} must look like 2026-11-15T13:00:00`);
  return s.length === 16 ? s + ':00' : s;
};

/** A plain-text email in the form Gmail's API takes (base64url of the whole message). */
function mime({ to, cc, subject, body }) {
  const lines = [];
  if (to.length) lines.push('To: ' + to.join(', '));
  if (cc.length) lines.push('Cc: ' + cc.join(', '));
  lines.push('Subject: =?UTF-8?B?' + Buffer.from(subject).toString('base64') + '?=', 'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', Buffer.from(body).toString('base64'));
  return Buffer.from(lines.join('\r\n')).toString('base64url');
}

function header(message, name) {
  return (message.payload?.headers || []).find((h) => h.name.toLowerCase() === name.toLowerCase())?.value || '';
}

function plainText(part) {
  if (!part) return '';
  if (part.mimeType === 'text/plain' && part.body?.data) return Buffer.from(part.body.data, 'base64url').toString('utf8');
  for (const p of part.parts || []) { const t = plainText(p); if (t) return t; }
  if (part.mimeType === 'text/html' && part.body?.data) return Buffer.from(part.body.data, 'base64url').toString('utf8').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  return '';
}

async function googleAction(userId, b) {
  const action = String(b.action || '');
  const need = { meet: 'meet', calendar_events: 'calendar', calendar_create: 'calendar', gmail_search: 'gmail', gmail_read: 'gmail', gmail_send: 'gmail', gmail_draft: 'gmail' }[action];
  if (!need) throw new Bad(400, 'Unknown Google action');
  const { token, services } = await googleAccess(userId);
  if (!services.includes(need)) throw new Bad(409, `Google ${need === 'gmail' ? 'Gmail' : need === 'calendar' ? 'Calendar' : 'Meet'} is not allowed yet. Open the dashboard → Connections → Google → Reconnect, and tick every box`);
  const cal = GOOGLE_API + '/calendar/v3/calendars/primary/events';
  const gmail = GMAIL_API + '/gmail/v1/users/me';

  if (action === 'meet') return 'Meet link: ' + await meetLink(userId);

  if (action === 'calendar_events') {
    const days = Math.min(Math.max(parseInt(b.days, 10) || 7, 1), 60);
    const q = new URLSearchParams({ timeMin: new Date().toISOString(), timeMax: new Date(now() + days * 864e5).toISOString(), singleEvents: 'true', orderBy: 'startTime', maxResults: '20' });
    if (oneLine(b.query, 200)) q.set('q', oneLine(b.query, 200));
    const data = await googleFetch(token, 'GET', `${cal}?${q}`);
    const lines = (data.items || []).map((e) => `${e.start?.dateTime || e.start?.date} | ${e.summary || '(no title)'}${e.location ? ' | ' + e.location : ''}${e.hangoutLink ? ' | ' + e.hangoutLink : ''}`);
    return lines.length ? lines.join('\n') : `Nothing on the calendar in the next ${days} days.`;
  }

  if (action === 'calendar_create') {
    const title = oneLine(b.title, 200);
    if (!title) throw new Bad(400, 'The event needs a title');
    const timeZone = /^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)*$/.test(String(b.timeZone || '')) ? b.timeZone : 'UTC';
    const event = {
      summary: title, location: oneLine(b.location, 300) || undefined, description: String(b.description ?? '').slice(0, 4000) || undefined,
      start: { dateTime: when(b.start, 'start'), timeZone }, end: { dateTime: when(b.end, 'end'), timeZone },
      attendees: emailList(b.attendees, 'attendees').map((email) => ({ email })),
    };
    const withMeet = b.meet === true || b.meet === 'true';
    if (withMeet) event.conferenceData = { createRequest: { requestId: crypto.randomUUID(), conferenceSolutionKey: { type: 'hangoutsMeet' } } };
    const q = new URLSearchParams({ conferenceDataVersion: '1', sendUpdates: event.attendees.length ? 'all' : 'none' });
    const e = await googleFetch(token, 'POST', `${cal}?${q}`, event);
    return `Saved "${e.summary}" for ${e.start?.dateTime || event.start.dateTime}${event.attendees.length ? `, invited ${event.attendees.length}` : ''} | ${e.htmlLink}${e.hangoutLink ? ' | Meet: ' + e.hangoutLink : ''}`;
  }

  if (action === 'gmail_search') {
    const list = await googleFetch(token, 'GET', `${gmail}/messages?${new URLSearchParams({ maxResults: '8', q: oneLine(b.query, 300) })}`);
    const ids = (list.messages || []).map((m) => m.id);
    if (!ids.length) return 'No emails match.';
    const meta = await Promise.all(ids.map((id) => googleFetch(token, 'GET', `${gmail}/messages/${encodeURIComponent(id)}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date`)));
    return meta.map((m) => `id ${m.id} | ${header(m, 'Date')} | ${header(m, 'From')} | ${header(m, 'Subject')} | ${(m.snippet || '').slice(0, 160)}`).join('\n');
  }

  if (action === 'gmail_read') {
    const id = oneLine(b.id, 100);
    if (!/^[A-Za-z0-9]+$/.test(id)) throw new Bad(400, 'Use an id from gmail_search');
    const m = await googleFetch(token, 'GET', `${gmail}/messages/${id}?format=full`);
    const text = plainText(m.payload).trim() || m.snippet || '(no text)';
    return `From: ${header(m, 'From')}\nTo: ${header(m, 'To')}\nDate: ${header(m, 'Date')}\nSubject: ${header(m, 'Subject')}\n\n${text.slice(0, 6000)}`;
  }

  // gmail_send and gmail_draft
  const msg = { to: emailList(b.to, 'to'), cc: emailList(b.cc, 'cc'), subject: oneLine(b.subject, 300), body: String(b.body ?? '').slice(0, 20000) };
  if (action === 'gmail_send') {
    if (!msg.to.length) throw new Bad(400, 'Who should it go to? Give an email address, or save a draft instead');
    await googleFetch(token, 'POST', `${gmail}/messages/send`, { raw: mime(msg) });
    return `Sent "${msg.subject}" to ${msg.to.join(', ')}`;
  }
  await googleFetch(token, 'POST', `${gmail}/drafts`, { message: { raw: mime(msg) } });
  return `Draft "${msg.subject}" saved in Gmail | https://mail.google.com/mail/u/0/#drafts`;
}

// ---------- the setup assistant ----------

/** What the assistant is told: what the product is, what this user has set up, and the reply format. */
function chatSystem(config) {
  const kb = { ...KEYBOARD_DEFAULTS, ...config.keyboard };
  const skills = config.skills.map((s) => `- ${s.name}${s.key ? ` (hold ${s.key.toUpperCase()})` : ''}${kb.wheel.includes('skill:' + s.name) ? ' (on the wheel)' : ''}: ${s.instruction.slice(0, 160)}`).join('\n') || '(none)';
  const usedKeys = config.skills.filter((s) => s.key).map((s) => s.key.toUpperCase()).join(', ') || 'none';
  const hasKey = config.provider === 'openai' ? !!config.openaiKey : config.provider === 'custom' ? !!config.customKey : !!config.apiKey;
  return `You are the setup assistant inside the Verbo dashboard. Verbo is an Android keyboard with an AI assistant built in. You help the user set it up: create skills, give skills a hold key, put skills on the wheel, and connect apps. You do not carry out keyboard actions yourself.

How Verbo works:
- A skill is one saved instruction applied to the text in the field (or to a copied message). It appears as a key above the keyboard, can be bound to a letter (hold that letter to run it), and can be put on the wheel (hold the space bar, slide, release). The wheel holds six shortcuts.
- Skills can use connected tools during an action: web search, place search with maps, weather, Google with one sign-in (Meet links, reading and saving Calendar events with invites, searching, reading, drafting and sending Gmail), the user's own HTTPS APIs, and these apps once a token is pasted under Connections: Notion (search, read, create pages), Calendly (booking links), Slack (list channels, read, send, search messages), GitHub (repos, search and create issues), Todoist (list and add tasks), Linear (search and create issues), Discord (send to a channel through a webhook).
- Gmail, LinkedIn, WhatsApp, Telegram, Outlook, Google Calendar, Maps, YouTube and X need no connection: a skill writes the text and opens the app with a draft or post filled in, and the user presses send there. With Google connected, Gmail and Calendar skills act directly instead. Reading LinkedIn profiles is not available.
- Connections, the AI model and API key, and keyboard settings each have a page in this dashboard.

This user right now:
Skills:
${skills}
Hold keys in use: ${usedKeys}
Wheel: ${kb.wheel.map((id) => id.replace('skill:', '')).join(', ')}
Connected: ${APP_TOKENS.map((t) => `${t.replace(/Token$|Webhook$/, '')} ${config[t] ? 'yes' : 'no'}`).join(', ')}, Google ${googleServices(config).join(' + ') || 'no'}, custom APIs ${config.customApis.length}
AI key saved: ${hasKey ? 'yes' : 'no'}

Reply with one JSON object and nothing else: {"reply": "...", "actions": [...]}.
"reply" is what you say to the user: plain text, short, friendly, no markdown. When you propose something, say in a sentence what it will do; the details go in the action.
"actions" is a list (often empty) of things the user can approve with one click. Nothing happens until they click. Each is one of:
- {"type": "create_skill", "name": "two or three words", "emoji": "one emoji", "does": "one sentence for the user", "instruction": "two to four sentences addressed to the assistant that runs the skill: what to do with the text, what to keep, what the result looks like; name the app link or tool to use if one is involved", "group": "write|edit|translate|summarize", "key": "a free letter or empty", "wheel": true or false}
- {"type": "bind_key", "skill": "exact skill name", "key": "one letter"}
- {"type": "add_to_wheel", "skill": "exact skill name"}
- {"type": "open", "page": "connections|settings|phones", "label": "short button text", "why": "one sentence"} for anything the user must do themselves, such as pasting a token or signing in to Google.
Suggest a hold key only if that letter is free. If the wish is vague, ask one short question instead of guessing. If a skill needs a connection the user doesn't have, propose the skill and add an "open" action for the connection.`;
}

/** Reads the assistant's JSON reply. Anything unexpected is dropped; if it isn't JSON at all, the text is shown as is. */
function parseChat(text) {
  let data = null;
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start >= 0 && end > start) { try { data = JSON.parse(text.slice(start, end + 1)); } catch { data = null; } }
  if (!data || typeof data.reply !== 'string') return { reply: text.trim().slice(0, 4000) || 'I could not put an answer together. Try rephrasing.', actions: [] };
  const short = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
  const letter = (v) => (typeof v === 'string' && /^[a-zA-Z]$/.test(v.trim()) ? v.trim().toLowerCase() : '');
  const actions = [];
  for (const a of Array.isArray(data.actions) ? data.actions.slice(0, 6) : []) {
    if (!a || typeof a !== 'object') continue;
    if (a.type === 'create_skill' && short(a.name, 40) && short(a.instruction, 2000)) {
      actions.push({ type: 'create_skill', name: short(a.name, 40), emoji: short(a.emoji, 16), does: short(a.does, 300), instruction: short(a.instruction, 2000),
        group: CATEGORIES.includes(a.group) ? a.group : 'edit', key: letter(a.key), wheel: a.wheel === true });
    } else if (a.type === 'bind_key' && short(a.skill, 40) && letter(a.key)) {
      actions.push({ type: 'bind_key', skill: short(a.skill, 40), key: letter(a.key) });
    } else if (a.type === 'add_to_wheel' && short(a.skill, 40)) {
      actions.push({ type: 'add_to_wheel', skill: short(a.skill, 40) });
    } else if (a.type === 'open' && ['connections', 'settings', 'phones'].includes(a.page)) {
      actions.push({ type: 'open', page: a.page, label: short(a.label, 40) || 'Open', why: short(a.why, 300) });
    }
  }
  return { reply: data.reply.trim().slice(0, 4000), actions };
}

/** One model call on the user's own key and provider. Returns the reply text. */
async function callModel(config, system, messages) {
  const provider = config.provider;
  const key = provider === 'openai' ? config.openaiKey : provider === 'custom' ? config.customKey : config.apiKey;
  if (!key) throw new Bad(409, 'Add your AI key under Settings first, then I can help');

  if (provider === 'anthropic') {
    const sdk = require('@anthropic-ai/sdk');
    const Anthropic = sdk.default || sdk;
    const client = new Anthropic({ apiKey: key, maxRetries: 1, timeout: 60000 });
    try {
      const base = { model: config.model, max_tokens: 4000, system, messages };
      // Opus and Sonnet 5.5 can decline a request; the fallback lets another model answer instead of failing.
      const response = /opus-5-5|sonnet-5-5/.test(config.model)
        ? await client.beta.messages.create({ ...base, betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' })
        : await client.messages.create(base);
      if (response.stop_reason === 'refusal') throw new Bad(422, 'The assistant declined that request');
      return response.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
    } catch (e) {
      if (e instanceof Bad) throw e;
      if (e instanceof Anthropic.AuthenticationError) throw new Bad(409, 'Your Claude API key was rejected. Check it under Settings');
      if (e instanceof Anthropic.PermissionDeniedError) throw new Bad(409, 'Your Claude API key cannot use that model');
      if (e instanceof Anthropic.RateLimitError) throw new Bad(429, 'Claude rate limit reached. Try again in a moment');
      if (e instanceof Anthropic.APIConnectionError) throw new Bad(502, 'Could not reach Claude. Try again');
      if (e instanceof Anthropic.APIError) throw new Bad(502, 'Claude returned an error (' + e.status + '). Try again');
      throw e;
    }
  }

  const openai = provider === 'openai';
  const url = openai ? 'https://api.openai.com/v1/responses' : config.customBase.replace(/\/+$/, '') + '/chat/completions';
  const payload = openai
    ? { model: config.openaiModel, instructions: system, input: messages }
    : { model: config.customModel, messages: [{ role: 'system', content: system }, ...messages] };
  let res;
  try {
    res = await fetch(url, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(60000),
      headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    });
  } catch {
    throw new Bad(502, 'Could not reach the AI service. Try again');
  }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 || res.status === 403) throw new Bad(409, 'Your AI key was rejected. Check it under Settings');
  if (res.status === 402) throw new Bad(409, 'The AI account is out of credit');
  if (res.status === 404) throw new Bad(409, 'The AI service does not know that model. Check it under Settings');
  if (res.status === 429) throw new Bad(429, 'AI rate limit reached. Try again in a moment');
  if (!res.ok) throw new Bad(502, 'The AI service returned an error (' + res.status + '). Try again');
  if (openai) {
    return (data.output || []).filter((o) => o.type === 'message').flatMap((o) => o.content || [])
      .filter((c) => c.type === 'output_text').map((c) => c.text).join('');
  }
  return String(data.choices?.[0]?.message?.content ?? '');
}

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
    const { status = 200, body, headers } = await handler(req, res);
    send(res, status, body, headers);
  } catch (e) {
    if (!(e instanceof Bad)) console.error(e);
    send(res, e instanceof Bad ? e.status : 500, { error: e instanceof Bad ? e.message : 'Server error' });
  }
}).listen(PORT, () => console.log(`Verbo dashboard listening on port ${PORT}`));
