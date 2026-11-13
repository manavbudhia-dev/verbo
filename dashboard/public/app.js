'use strict';

// Tiny DOM helper: h('div', {class: 'x', onclick: fn}, child, 'text')
function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k in el && k !== 'list') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c);
  return el;
}

async function api(method, url, body) {
  const res = await fetch(url, {
    method, credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Something went wrong');
  return data;
}

let toastTimer;
function toast(message) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

const app = document.getElementById('app');
const state = { email: null, allowSignup: true, tab: 'skills', config: null, devices: [], code: null };

const MODELS = [
  ['claude-opus-5-5', 'Opus 5.5 — most capable'],
  ['claude-sonnet-5-5', 'Sonnet 5.5 — balanced'],
  ['claude-haiku-5-5', 'Haiku 5.5 — fastest'],
];

async function save(patch, message) {
  try {
    state.config = await api('PUT', '/api/config', patch);
    toast(message || 'Saved');
    render();
  } catch (e) { toast(e.message); }
}

// ---------- sign in ----------

function authView() {
  let mode = 'login';
  const box = h('div', { class: 'auth' });
  const draw = () => {
    const email = h('input', { type: 'email', autocomplete: 'email', required: true });
    const password = h('input', { type: 'password', required: true, minLength: mode === 'signup' ? 10 : 1,
      autocomplete: mode === 'signup' ? 'new-password' : 'current-password' });
    const form = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      try {
        const me = await api('POST', mode === 'signup' ? '/api/signup' : '/api/login', { email: email.value, password: password.value });
        state.email = me.email;
        await load();
      } catch (err) { toast(err.message); }
    } },
      h('h2', null, mode === 'signup' ? 'Create your account' : 'Sign in'),
      h('p', { class: 'muted' }, 'Manage your keyboard’s skills and connected apps.'),
      h('label', null, 'Email', email),
      h('label', null, mode === 'signup' ? 'Password (10 characters or more)' : 'Password', password),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', type: 'submit' }, mode === 'signup' ? 'Create account' : 'Sign in'),
        state.allowSignup && h('button', { class: 'btn ghost', type: 'button', onclick: () => { mode = mode === 'signup' ? 'login' : 'signup'; draw(); } },
          mode === 'signup' ? 'I have an account' : 'Create an account')));
    box.replaceChildren(h('div', { class: 'brand' }, h('span', null, 'V'), 'Verbo'), form);
  };
  draw();
  return box;
}

// ---------- dialogs ----------

function dialog(title, fields, onSave, onDelete) {
  const d = h('dialog');
  const inputs = {};
  const form = h('form', { onsubmit: async (e) => {
    e.preventDefault();
    const values = Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.value]));
    if (await onSave(values) !== false) d.close();
  } },
    h('h2', null, title),
    fields.map((f) => {
      const el = f.multiline ? h('textarea', { placeholder: f.hint || '' }) : h('input', { type: f.type || 'text', placeholder: f.hint || '' });
      el.value = f.value || '';
      if (f.required) el.required = true;
      inputs[f.key] = el;
      return h('label', null, f.label, el);
    }),
    h('div', { class: 'actions' },
      h('button', { class: 'btn primary', type: 'submit' }, 'Save'),
      h('button', { class: 'btn', type: 'button', onclick: () => d.close() }, 'Cancel'),
      onDelete && h('button', { class: 'btn ghost danger', type: 'button', onclick: () => { d.close(); onDelete(); } }, 'Delete')));
  d.append(form);
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
}

// ---------- tabs ----------

function skillsTab() {
  const skills = state.config.skills;
  const edit = (index) => {
    const s = skills[index] || {};
    dialog(index < 0 ? 'New skill' : 'Edit skill', [
      { key: 'emoji', label: 'Emoji (optional)', value: s.emoji },
      { key: 'name', label: 'Name', value: s.name, required: true, hint: 'Pirate' },
      { key: 'instruction', label: 'What should it do with the text?', value: s.instruction, required: true, multiline: true,
        hint: 'Rewrite it the way a cheerful pirate would say it.' },
    ], (v) => {
      const next = skills.slice();
      if (index < 0) next.push(v); else next[index] = v;
      return save({ skills: next });
    }, index >= 0 && (() => save({ skills: skills.filter((_, i) => i !== index) }, 'Skill deleted')));
  };
  const move = (index, by) => {
    const next = skills.slice();
    const [item] = next.splice(index, 1);
    next.splice(index + by, 0, item);
    save({ skills: next }, 'Order saved');
  };
  return [
    h('div', { class: 'head' },
      h('div', null, h('h2', null, 'Skill keys'), h('p', { class: 'muted' }, 'Each skill is a key above the keyboard that applies one instruction to what you typed.')),
      h('button', { class: 'btn primary', onclick: () => edit(-1) }, 'New skill')),
    skills.map((s, i) => h('div', { class: 'card row' },
      h('div', { class: 'emoji' }, s.emoji || '✦'),
      h('div', { class: 'grow' }, h('h3', null, s.name), h('p', { class: 'muted clamp' }, s.instruction)),
      h('button', { class: 'btn ghost', disabled: i === 0, 'aria-label': 'Move up', onclick: () => move(i, -1) }, '↑'),
      h('button', { class: 'btn ghost', disabled: i === skills.length - 1, 'aria-label': 'Move down', onclick: () => move(i, 1) }, '↓'),
      h('button', { class: 'btn', onclick: () => edit(i) }, 'Edit'))),
    skills.length === 0 && h('div', { class: 'card muted' }, 'No skills yet. Create one to see it on your keyboard.'),
  ];
}

function tokenCard(icon, title, help, isSet, field) {
  const input = h('input', { type: 'password', placeholder: isSet ? 'Saved. Paste a new token to replace it' : 'Paste token', autocomplete: 'off' });
  return h('div', { class: 'card' },
    h('div', { class: 'row' }, h('div', { class: 'emoji' }, icon),
      h('div', { class: 'grow' }, h('h3', null, title), h('p', { class: 'muted' }, help)),
      h('span', { class: 'pill' + (isSet ? '' : ' off') }, isSet ? 'Connected' : 'Not connected')),
    h('div', { class: 'row', style: 'margin-top:12px' }, h('div', { class: 'grow' }, input),
      h('button', { class: 'btn primary', onclick: () => input.value.trim() ? save({ [field]: input.value }, 'Connected') : toast('Paste a token first') }, 'Save'),
      isSet && h('button', { class: 'btn ghost danger', onclick: () => save({ [field]: '' }, 'Disconnected') }, 'Disconnect')));
}

function connectionsTab() {
  const c = state.config;
  const apis = c.customApis;
  const strip = (a) => ({ id: a.id, name: a.name, description: a.description, url: a.url, headerName: a.headerName });
  const edit = (index) => {
    const a = apis[index] || {};
    dialog(index < 0 ? 'Connect an API' : 'Edit connection', [
      { key: 'name', label: 'Name', value: a.name, required: true, hint: 'NBA scores' },
      { key: 'description', label: 'What it returns and when to use it', value: a.description, multiline: true },
      { key: 'url', label: 'Address (https). Put {query} where the search term goes', value: a.url, required: true, type: 'url', hint: 'https://api.example.com/search?q={query}' },
      { key: 'headerName', label: 'Header name (optional)', value: a.headerName, hint: 'X-Api-Key' },
      { key: 'headerValue', label: a.headerValueSet ? 'Header value (saved; leave empty to keep it)' : 'Header value (optional)', type: 'password' },
    ], (v) => {
      const item = { id: a.id, name: v.name, description: v.description, url: v.url, headerName: v.headerName };
      if (v.headerValue || !a.headerValueSet) item.headerValue = v.headerValue;
      const next = apis.map(strip);
      if (index < 0) next.push(item); else next[index] = item;
      return save({ customApis: next });
    }, index >= 0 && (() => save({ customApis: apis.filter((_, i) => i !== index).map(strip) }, 'Connection removed')));
  };
  return [
    h('div', { class: 'head' },
      h('div', null, h('h2', null, 'Connections'), h('p', { class: 'muted' }, 'Apps and APIs the assistant can use while carrying out an action.')),
      h('button', { class: 'btn primary', onclick: () => edit(-1) }, 'Connect an API')),
    h('div', { class: 'card row' }, h('div', { class: 'emoji' }, '☀️'),
      h('div', { class: 'grow' }, h('h3', null, 'Weather'), h('p', { class: 'muted' }, 'Forecasts from Open-Meteo. No account needed.')),
      h('label', { class: 'switch' }, h('input', { type: 'checkbox', checked: c.weather, 'aria-label': 'Weather', onchange: (e) => save({ weather: e.target.checked }) }))),
    tokenCard('📝', 'Notion', 'Search, read and create pages. Use an internal integration token and share the pages you want with it.', c.notionSet, 'notionToken'),
    tokenCard('📅', 'Calendly', 'Drop your booking links into a message. Use a personal access token.', c.calendlySet, 'calendlyToken'),
    apis.map((a, i) => h('div', { class: 'card row' }, h('div', { class: 'emoji' }, '🔌'),
      h('div', { class: 'grow' }, h('h3', null, a.name), h('p', { class: 'muted clamp' }, a.description || a.url)),
      h('span', { class: 'pill' }, 'API'),
      h('button', { class: 'btn', onclick: () => edit(i) }, 'Edit'))),
  ];
}

function assistantTab() {
  const c = state.config;
  const key = h('input', { type: 'password', placeholder: c.apiKeySet ? 'Saved. Paste a new key to replace it' : 'sk-ant-…', autocomplete: 'off' });
  const model = h('select', { onchange: (e) => save({ model: e.target.value }) },
    MODELS.map(([id, name]) => h('option', { value: id, selected: id === c.model }, name)));
  return [
    h('div', null, h('h2', null, 'Assistant'), h('p', { class: 'muted' }, 'The model behind your keyboard’s actions.')),
    h('div', { class: 'card' },
      h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h3', null, 'Claude API key'),
        h('p', { class: 'muted' }, 'Actions run on your own key. It is stored encrypted here and sent only to your paired keyboards.')),
        h('span', { class: 'pill' + (c.apiKeySet ? '' : ' off') }, c.apiKeySet ? 'Saved' : 'Missing')),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('div', { class: 'grow' }, key),
        h('button', { class: 'btn primary', onclick: () => key.value.trim() ? save({ apiKey: key.value }, 'Key saved') : toast('Paste a key first') }, 'Save'),
        c.apiKeySet && h('button', { class: 'btn ghost danger', onclick: () => save({ apiKey: '' }, 'Key removed') }, 'Remove'))),
    h('div', { class: 'card' }, h('h3', null, 'Model'), h('label', null, 'Used for every action', model)),
    h('div', { class: 'card' }, h('label', { class: 'switch' },
      h('input', { type: 'checkbox', checked: c.webSearch, onchange: (e) => save({ webSearch: e.target.checked }) }),
      'Search the web when an action needs fresh facts')),
  ];
}

const when = (ms) => ms ? new Date(ms).toLocaleString() : 'never';

function devicesTab() {
  return [
    h('div', { class: 'head' },
      h('div', null, h('h2', null, 'Keyboards'), h('p', { class: 'muted' }, 'Phones that sync their skills and connections from this account.')),
      h('button', { class: 'btn primary', onclick: async () => {
        try { state.code = await api('POST', '/api/devices/code'); render(); } catch (e) { toast(e.message); }
      } }, 'Pair a keyboard')),
    state.code && h('div', { class: 'card' },
      h('h3', null, 'Pairing code'),
      h('p', { class: 'muted' }, `In the Verbo app, open Dashboard, enter this site’s address and this code. It works once, for ${state.code.minutes} minutes.`),
      h('div', { class: 'code' }, state.code.code)),
    state.devices.map((d) => h('div', { class: 'card row' }, h('div', { class: 'emoji' }, '⌨️'),
      h('div', { class: 'grow' }, h('h3', null, d.name), h('p', { class: 'muted' }, `Paired ${when(d.created)} · last synced ${when(d.lastSeen)}`)),
      h('button', { class: 'btn ghost danger', onclick: async () => {
        if (!confirm(`Unpair ${d.name}? It keeps its current settings but stops syncing.`)) return;
        try { await api('POST', '/api/devices/remove', { id: d.id }); await load(); } catch (e) { toast(e.message); }
      } }, 'Unpair'))),
    state.devices.length === 0 && !state.code && h('div', { class: 'card muted' }, 'No keyboards paired yet.'),
  ];
}

const TABS = [['skills', 'Skill keys', skillsTab], ['connections', 'Connections', connectionsTab], ['assistant', 'Assistant', assistantTab], ['devices', 'Keyboards', devicesTab]];

function render() {
  if (!state.email) return app.replaceChildren(authView());
  const current = TABS.find((t) => t[0] === state.tab);
  app.replaceChildren(h('div', { class: 'shell' },
    h('aside', { class: 'side' },
      h('div', { class: 'brand' }, h('span', null, 'V'), 'Verbo'),
      h('nav', null, TABS.map(([id, name]) => h('button', { 'aria-current': id === state.tab ? 'page' : null, onclick: async () => {
        state.tab = id;
        if (id === 'devices') state.devices = await api('GET', '/api/devices').catch(() => state.devices);
        render();
      } }, name))),
      h('div', { class: 'who' }, state.email, h('br'),
        h('button', { class: 'btn ghost', style: 'padding-left:0', onclick: async () => {
          await api('POST', '/api/logout').catch(() => {});
          state.email = null; state.config = null; state.code = null;
          render();
        } }, 'Sign out'))),
    h('main', null, current[2]())));
}

async function load() {
  [state.config, state.devices] = await Promise.all([api('GET', '/api/config'), api('GET', '/api/devices')]);
  render();
}

(async () => {
  try {
    const me = await api('GET', '/api/me');
    state.allowSignup = me.allowSignup;
    state.email = me.email;
    if (me.email) await load(); else render();
  } catch (e) {
    app.replaceChildren(h('div', { class: 'auth' }, h('div', { class: 'card' }, 'Could not reach the server. ' + e.message)));
  }
})();
