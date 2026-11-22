'use strict';
// Sign-in, My skills, Keys, Analytics, onboarding and the page shell. Loaded after app.js and agents.js.

const local = {
  get: (k) => { try { return localStorage.getItem('verbo.' + k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem('verbo.' + k, v); } catch { /* private window */ } },
};

// ---------- sign in ----------

function googleButton(label) {
  const g = h('span', { style: 'font-weight:800;font-size:1.05rem;background:conic-gradient(from -45deg,#ea4335 0 25%,#4285f4 0 50%,#34a853 0 75%,#fbbc05 0);-webkit-background-clip:text;background-clip:text;color:transparent' }, 'G');
  return h('button', { class: 'btn google', type: 'button', onclick: () => { location.href = '/api/auth/google'; } }, g, label);
}

function authView() {
  let mode = 'login';
  const formSide = h('div', { class: 'formside' });
  const draw = () => {
    const email = h('input', { type: 'email', autocomplete: 'email', required: true });
    const password = h('input', { type: 'password', required: true, minLength: mode === 'signup' ? 10 : 1, autocomplete: mode === 'signup' ? 'new-password' : 'current-password' });
    const form = h('form', { onsubmit: async (e) => {
      e.preventDefault();
      try {
        const me = await api('POST', mode === 'signup' ? '/api/signup' : '/api/login', { email: email.value, password: password.value });
        state.email = me.email;
        await load();
      } catch (err) { toast(err.message); }
    } },
      h('label', { class: 'field' }, 'Email', email),
      h('label', { class: 'field' }, mode === 'signup' ? 'Password (10 characters or more)' : 'Password', password),
      h('div', { class: 'actions', style: 'margin-top:18px' },
        h('button', { class: 'btn primary', type: 'submit' }, mode === 'signup' ? 'Create account' : 'Sign in with email'),
        state.allowSignup && h('button', { class: 'btn ghost', type: 'button', onclick: () => { mode = mode === 'signup' ? 'login' : 'signup'; draw(); } },
          mode === 'signup' ? 'I have an account' : 'Create an account')));
    formSide.replaceChildren(h('div', { class: 'card' },
      h('h2', { style: 'font-size:1.45rem' }, mode === 'signup' ? 'Create your account' : 'Welcome back'),
      h('p', { class: 'muted', style: 'margin:6px 0 20px' }, 'One account for the web and the Verbo app on your phone.'),
      state.googleLogin && [googleButton('Continue with Google'), h('div', { class: 'or' }, 'or use email')],
      form));
  };
  draw();
  return h('div', { class: 'auth' },
    h('div', { class: 'pitch' },
      h('div', { class: 'brand' }, h('span', { class: 'logo' }, 'V'), h('div', null, h('b', null, 'Verbo'), h('small', null, 'AI agentic keyboard'))),
      h('h1', null, 'Turn your typing into actions'),
      h('p', null, 'Skills, agents and your apps, one hold of a key away. Set it up here and your phone keyboard follows.'),
      h('ul', null, h('li', null, 'Run skills on anything you type'), h('li', null, 'Build agents by dragging blocks'), h('li', null, 'Assign any key, per app'), h('li', null, 'Sign in once with Google on web and phone'))),
    formSide);
}

// ---------- onboarding ----------

function onboardingSteps() {
  const c = state.config;
  const hasKey = c.provider === 'openai' ? c.openaiKeySet : c.provider === 'custom' ? c.customKeySet : c.apiKeySet;
  return [
    ['Add your AI key', 'Your keyboard and the assistant run on it', hasKey, () => go('settings')],
    ['Get the app and sign in', 'Install Verbo on Android, choose Continue with Google: it syncs by itself', state.devices.length > 0, () => phoneHelp()],
    ['Connect an app', 'Gmail, Calendar, Notion, Slack…', !!(c.googleConnected || Object.values(c.appsSet || {}).some(Boolean)), () => go('connections')],
    ['Try a skill', 'Press Run on any skill card', !!local.get('tried'), () => document.querySelector('.skillcard .run')?.focus()],
    ['Build your first agent', 'Drag a trigger and steps onto the canvas', (c.agents || []).length > 0, () => go('agents')],
  ];
}

function checklistCard() {
  const steps = onboardingSteps();
  const done = steps.filter((s) => s[2]).length;
  if (done === steps.length || local.get('checklist') === 'hidden') return null;
  return h('div', { class: 'card checklist wide' },
    h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', null, `Get started · ${done} of ${steps.length}`), h('p', { class: 'muted small' }, 'A few minutes, and your keyboard does the rest.')),
      h('button', { class: 'btn ghost small', onclick: () => { local.set('checklist', 'hidden'); render(); } }, 'Hide')),
    h('div', { class: 'progress' }, h('div', { style: `width:${(done / steps.length) * 100}%` })),
    steps.map(([title, about, ok, act]) => h('div', { class: 'item' + (ok ? ' done' : '') },
      h('span', { class: 'dot' }, ok ? icon('check') : null),
      h('div', { class: 'grow' }, h('div', { class: 'label' }, title), h('p', { class: 'muted small' }, about)),
      !ok && h('button', { class: 'btn small', onclick: act }, 'Start'))));
}

const TOUR = [
  ['👋', 'Welcome to Verbo', 'Your keyboard can now rewrite, reply, look things up and act in your apps. This dashboard sets it all up; your phone follows on its own.'],
  ['✨', 'Skills', 'A skill is one instruction, like Polish or Reply. Press Run on any card to try it right here, or star the ones you use most.'],
  ['🤖', 'Agents', 'Drag a trigger, steps and actions onto a canvas and link them. “Copy a time → save the event → invite on WhatsApp” becomes one tap.'],
  ['⌨️', 'Keys', 'Hold any letter to run a skill, an agent or a tool. Give the same letter a different job in WhatsApp, Gmail or any app.'],
  ['📱', 'Your phone', 'Install the Verbo app and choose Continue with Google. Same account, so everything here appears there.'],
];

function tour(start = 0) {
  let i = start;
  const d = modal('', (close) => {
    const body = h('div', { class: 'tour' });
    const draw = () => {
      const [art, title, text] = TOUR[i];
      body.replaceChildren(h('div', { class: 'art' }, art), h('h2', { style: 'font-size:1.4rem' }, title), h('p', { class: 'muted', style: 'margin-top:8px' }, text),
        h('div', { class: 'actions' },
          h('div', { class: 'dots' }, TOUR.map((_, j) => h('i', { class: j === i ? 'on' : '' }))),
          h('button', { class: 'btn ghost', onclick: () => { local.set('tour', 'done'); close(); } }, 'Skip'),
          h('button', { class: 'btn primary', onclick: () => { if (i < TOUR.length - 1) { i++; draw(); } else { local.set('tour', 'done'); close(); } } }, i < TOUR.length - 1 ? 'Next' : 'Get started')));
    };
    draw();
    return body;
  });
  d.querySelector('h2').remove();
}

function phoneHelp() {
  modal('Verbo on your phone', (close) => h('div', null,
    h('div', { class: 'checklist' },
      [['1', 'Install the Verbo app', 'Open the APK on your Android phone and install it.'],
        ['2', 'Turn the keyboard on', 'The app walks you through it: enable Verbo, then choose it.'],
        ['3', 'Continue with Google', `Use the same Google account as here${state.email ? ` (${state.email})` : ''}. Skills, agents, keys and connections arrive by themselves.`]]
        .map(([n, t, p]) => h('div', { class: 'item' }, h('span', { class: 'dot' }, n), h('div', { class: 'grow' }, h('div', null, t), h('p', { class: 'muted small' }, p))))),
    h('p', { class: 'muted small', style: 'margin-top:12px' }, 'No Google on this server? Use Settings → Phones → Pair a phone to get a one-time code instead.'),
    h('div', { class: 'actions' }, h('button', { class: 'btn primary', onclick: () => { close(); go('settings'); } }, 'Pair with a code'), h('button', { class: 'btn', onclick: close }, 'Close'))));
}

// ---------- My skills ----------

const GROUP_COLORS = { write: '#a78bfa', edit: '#60a5fa', translate: '#4ade80', summarize: '#fbbf24' };

function runDialog(skill) {
  const d = modal(`${skill.emoji ? skill.emoji + ' ' : ''}Run ${skill.name}`, (close) => {
    const input = h('textarea', { rows: 5, placeholder: 'Type or paste the text to run it on' });
    const out = h('div');
    const go2 = async (btn) => {
      if (!input.value.trim()) return toast('Type some text first');
      btn.disabled = true;
      out.replaceChildren(h('p', { class: 'muted', style: 'margin-top:14px' }, 'Running…'));
      try {
        const r = await api('POST', '/api/run', { text: input.value, steps: runSteps(skill) });
        local.set('tried', '1');
        const last = r.logs[r.logs.length - 1];
        out.replaceChildren(
          r.logs.length > 1 && h('div', { style: 'margin-top:14px' }, r.logs.map((l) => h('span', { class: 'logstep' + (l.error ? ' err' : '') }, l.error ? '✕' : '✓', ' ', l.name.toUpperCase()))),
          r.result != null
            ? [h('div', { class: 'label muted small', style: 'margin-top:14px;font-weight:700' }, 'RESULT'), h('div', { class: 'logs', style: 'margin-top:6px;font-family:inherit;font-size:.95rem' }, r.result),
              h('div', { class: 'actions', style: 'margin-top:10px' }, h('button', { class: 'btn small', onclick: () => navigator.clipboard?.writeText(r.result).then(() => toast('Copied')) }, 'Copy'))]
            : h('p', { style: 'margin-top:14px;color:var(--danger)' }, last?.error || 'It did not finish'));
      } catch (e) { out.replaceChildren(h('p', { style: 'margin-top:14px;color:var(--danger)' }, e.message)); }
      btn.disabled = false;
    };
    const runBtn = h('button', { class: 'btn primary', onclick: () => go2(runBtn) }, icon('play'), 'Run');
    return h('div', null, h('p', { class: 'muted clamp', style: 'margin:4px 0 12px' }, isChain(skill) ? 'Chain: ' + chainSteps(skill).join(' → ') : skill.instruction), input, out,
      h('div', { class: 'actions' }, runBtn, h('button', { class: 'btn', onclick: close }, 'Close'),
        h('span', { class: 'muted small' }, 'On the web there are no tools; on your phone it can search and use your apps.')));
  });
  d.classList.add('wide');
}

function exportSetup() {
  const c = state.config;
  const blob = new Blob([JSON.stringify({ skills: c.skills.map(cleanSkill), agents: c.agents, triggers: c.triggers, keymap: c.keymap }, null, 2)], { type: 'application/json' });
  const a = h('a', { href: URL.createObjectURL(blob), download: 'verbo-skills.json' });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

function importSkills() {
  const input = h('input', { type: 'file', accept: '.json,application/json', style: 'display:none' });
  input.addEventListener('change', async () => {
    try {
      const data = JSON.parse(await input.files[0].text());
      const list = Array.isArray(data) ? data : data.skills;
      if (!Array.isArray(list)) throw new Error();
      const have = new Set(state.config.skills.map((s) => s.name.toLowerCase()));
      const fresh = list.filter((s) => s && typeof s.name === 'string' && typeof s.instruction === 'string' && !have.has(s.name.toLowerCase()))
        .map((s) => cleanSkill({ ...s, key: '' }));
      if (!fresh.length) return toast('Nothing new to import');
      const next = [...state.config.skills, ...fresh].slice(0, 60);
      await saveSkills(next, null, `Imported ${next.length - state.config.skills.length} skills`);
    } catch { toast('That file is not a Verbo skills file'); }
    input.remove();
  });
  document.body.append(input);
  input.click();
}

function toggleFavorite(name) {
  const favs = state.config.favorites || [];
  save({ favorites: favs.includes(name) ? favs.filter((f) => f !== name) : [...favs, name] }, favs.includes(name) ? 'Unstarred' : 'Starred');
}

function skillCard(s, i) {
  const fav = (state.config.favorites || []).includes(s.name);
  const edit = () => (isChain(s) ? (state.config.agents.find((a) => a.name === s.name) ? openAgent(state.config.agents.find((a) => a.name === s.name)) : chainDialog(i)) : skillDialog(i));
  return h('div', { class: 'card tile skillcard' },
    h('div', { class: 'top' }, h('div', { class: 'emoji' }, s.emoji || '✦'), h('h3', null, s.name),
      s.key && h('span', { class: 'keycap', title: `Hold ${s.key.toUpperCase()}` }, s.key.toUpperCase()),
      h('button', { class: 'star' + (fav ? ' on' : ''), 'aria-label': fav ? 'Unstar' : 'Star', 'aria-pressed': fav, onclick: () => toggleFavorite(s.name) }, fav ? '★' : '☆')),
    h('p', { class: 'muted clamp small' }, isChain(s) ? 'Runs ' + chainSteps(s).join(' → ') : s.instruction),
    h('div', { class: 'foot' },
      h('button', { class: 'btn small', onclick: edit }, isChain(s) ? 'Open' : 'Edit'),
      h('button', { class: 'btn ghost small run', onclick: () => runDialog(s) }, icon('play'), 'Run')));
}

function skillsTab() {
  const c = state.config;
  const used = (s) => c.usage?.skills?.[s.name]?.n || 0;
  const q = state.search.trim().toLowerCase();
  const counts = { all: c.skills.length, chain: c.skills.filter(isChain).length };
  for (const [g] of GROUPS) counts[g] = c.skills.filter((s) => !isChain(s) && s.category === g).length;
  let shown = c.skills.map((s, i) => [s, i])
    .filter(([s]) => state.filter === 'all' || (state.filter === 'chain' ? isChain(s) : !isChain(s) && s.category === state.filter))
    .filter(([s]) => !q || (s.name + ' ' + s.instruction).toLowerCase().includes(q));
  const favs = c.favorites || [];
  if (state.sort === 'used') shown.sort((a, b) => used(b[0]) - used(a[0]) || (favs.includes(b[0].name) - favs.includes(a[0].name)));
  if (state.sort === 'name') shown.sort((a, b) => a[0].name.localeCompare(b[0].name));
  if (state.sort === 'starred') shown.sort((a, b) => favs.includes(b[0].name) - favs.includes(a[0].name));
  const total = c.skills.length || 1;
  const ring = Math.min(100, Math.round((c.skills.filter((s) => s.key).length + favs.length) / total * 100)) || 8;

  return h('div', { class: 'home' },
    h('div', { class: 'card hero' },
      h('span', { class: 'tag' }, icon('sparkles'), 'AI agentic keyboard'),
      h('h2', null, 'Turn your typing into actions'),
      h('p', null, 'Write, rewrite, summarise, research and act in your apps, right from your keyboard.'),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: () => skillDialog(-1) }, icon('plus'), 'New skill'),
        h('button', { class: 'btn', onclick: () => tour() }, icon('play'), 'See how it works')),
      h('div', { class: 'art' }, h('div', { class: 'key3d back' }), h('div', { class: 'key3d' }, '⚡'))),
    h('div', { class: 'card stats' },
      h('h2', null, 'Your keyboard, supercharged'),
      h('div', { class: 'body' },
        h('div', { class: 'ring', style: `--p:${ring}` }, h('div', null, h('div', null, h('b', null, String(c.skills.length)), h('small', null, 'Skills active')))),
        h('div', { class: 'legend' },
          GROUPS.map(([g, name]) => h('div', null, h('i', { style: `background:${GROUP_COLORS[g]}` }), name, h('span', null, String(counts[g])))),
          h('div', null, h('i', { style: 'background:#f472b6' }), 'Agents', h('span', null, String((c.agents || []).length)))))),
    checklistCard(),
    h('div', { class: 'wide filters' },
      [['all', 'All skills'], ...GROUPS, ['chain', 'Chains & agents']].map(([id, name]) =>
        h('button', { class: 'chip' + (state.filter === id ? ' on' : ''), onclick: () => { state.filter = id; render(); } }, name, h('span', { class: 'count' }, String(counts[id] ?? 0)))),
      h('span', { class: 'spacer' }),
      select(state.sort, [['used', 'Sort: Most used'], ['starred', 'Sort: Starred'], ['name', 'Sort: A–Z'], ['mine', 'Sort: My order']], (v) => { state.sort = v; render(); }, 'Sort'),
      h('div', { class: 'seg' },
        h('button', { class: state.view === 'grid' ? 'on' : '', 'aria-label': 'Grid', onclick: () => { state.view = 'grid'; render(); } }, icon('layout_grid')),
        h('button', { class: state.view === 'list' ? 'on' : '', 'aria-label': 'List', onclick: () => { state.view = 'list'; render(); } }, icon('list')))),
    h('div', null,
      h('div', { class: 'grid' + (state.view === 'list' ? ' list' : '') },
        shown.map(([s, i]) => skillCard(s, i)),
        !q && state.filter === 'all' && h('button', { class: 'card tile dashed', onclick: () => skillDialog(-1) }, icon('plus'), 'New skill')),
      shown.length === 0 && h('p', { class: 'muted', style: 'margin-top:16px' }, q ? 'No skill matches that search.' : 'Nothing here yet.'),
      triggersCard(), memoryCard(), shortcutsCard()),
    h('div', { class: 'stack' },
      h('div', { class: 'card' }, h('h2', { style: 'margin-bottom:12px' }, 'Quick actions'),
        h('div', { class: 'quick' },
          h('button', { class: 'main', onclick: () => skillDialog(-1) }, icon('plus'), h('span', { style: 'flex:1' }, 'Create new skill'), icon('arrow_right')),
          h('button', { onclick: () => go('assistant') }, icon('sparkles'), 'Describe it to the assistant'),
          h('button', { onclick: () => chainDialog(-1) }, icon('link'), 'Quick chain'),
          h('button', { onclick: importSkills }, icon('download'), 'Import skills'),
          h('button', { onclick: exportSetup }, icon('upload'), 'Export my setup'),
          h('button', { onclick: () => go('keys') }, icon('keyboard'), 'Manage my keys'))),
      h('div', { class: 'card agentpromo' }, h('div', { style: 'font-size:2.2rem' }, '🧩'), h('h2', null, 'Build your own agent'),
        h('p', null, 'Combine skills, tools and apps into one agent for your workflow.'),
        h('button', { class: 'btn primary', onclick: () => { go('agents'); openAgent(blankAgent()); } }, icon('plus'), 'Create agent'))));
}

// ---------- Keys ----------

const APPS = [
  ['', 'All apps', '🌐'], ['com.whatsapp', 'WhatsApp', '🟢'], ['com.google.android.gm', 'Gmail', '📧'], ['com.android.chrome', 'Chrome', '🧭'],
  ['com.instagram.android', 'Instagram', '📸'], ['org.telegram.messenger', 'Telegram', '✈️'], ['com.Slack', 'Slack', '#️⃣'], ['com.linkedin.android', 'LinkedIn', '💼'],
  ['com.twitter.android', 'X', '𝕏'], ['com.google.android.apps.messaging', 'Messages', '💬'], ['com.microsoft.office.outlook', 'Outlook', '📨'], ['com.discord', 'Discord', '🎮'],
];
const appInfo = (pkg) => APPS.find((a) => a[0] === pkg) || [pkg, pkg.split('.').pop(), '📱'];

function actionLabel(action) {
  const [type, rest] = [action.slice(0, action.indexOf(':')), action.slice(action.indexOf(':') + 1)];
  if (type === 'skill') { const s = state.config.skills.find((x) => x.name === rest); return (s?.emoji ? s.emoji + ' ' : '') + rest; }
  if (type === 'act') return (BUILTIN_ACTS[rest]?.[0] || '⚡') + ' ' + rest;
  if (type === 'tool') return WHEEL_TOOLS[rest] || rest;
  return '“' + (rest.length > 28 ? rest.slice(0, 28) + '…' : rest) + '”';
}

/** Every key action: letters bound to skills (all apps) and the extra key map. */
function keyRows() {
  const c = state.config;
  return [
    ...c.skills.filter((s) => s.key).map((s) => ({ key: s.key, app: '', action: 'skill:' + s.name, fromSkill: true })),
    ...(c.keymap || []),
  ].sort((a, b) => (a.app === b.app ? a.key.localeCompare(b.key) : a.app === '' ? -1 : b.app === '' ? 1 : a.app.localeCompare(b.app)));
}

async function saveKeyAction(prev, next) {
  let skills = state.config.skills.map((s) => ({ ...s }));
  let keymap = (state.config.keymap || []).slice();
  if (prev) {
    if (prev.fromSkill) skills = skills.map((s) => (s.key === prev.key && 'skill:' + s.name === prev.action ? { ...s, key: '' } : s));
    else keymap = keymap.filter((k) => !(k.key === prev.key && k.app === prev.app));
  }
  if (next) {
    keymap = keymap.filter((k) => !(k.key === next.key && k.app === next.app));
    if (next.app === '') skills = skills.map((s) => (s.key === next.key ? { ...s, key: '' } : s));
    const name = next.action.startsWith('skill:') ? next.action.slice(6) : null;
    // A skill in every app keeps using the skill's own hold key, which older phones understand too.
    if (name && next.app === '' && skills.some((s) => s.name === name)) skills = skills.map((s) => (s.name === name ? { ...s, key: next.key } : s));
    else keymap.push({ key: next.key, app: next.app, action: next.action });
  }
  return save({ skills: skills.map(cleanSkill), keymap }, next ? `Hold ${next.key.toUpperCase()}${next.app ? ' in ' + appInfo(next.app)[1] : ''}: ${actionLabel(next.action)}` : 'Key action removed');
}

function keyActionDialog(prev, preset) {
  const c = state.config;
  const start = prev || preset || { key: 'a', app: '', action: '' };
  let key = start.key;
  modal(prev ? 'Edit key action' : 'New key action', (close) => {
    const letters = h('div', { class: 'letters' });
    const app = h('select', { 'aria-label': 'App' }, APPS.map(([pkg, name, emoji]) => h('option', { value: pkg, selected: pkg === start.app }, `${emoji}  ${name}`)),
      h('option', { value: '__other', selected: !!start.app && !APPS.some((a) => a[0] === start.app) }, '📱  Another app…'));
    const other = h('input', { type: 'text', placeholder: 'App package, e.g. com.zhiliaoapp.musically', value: APPS.some((a) => a[0] === start.app) ? '' : start.app });
    const otherField = h('label', { class: 'field', style: app.value === '__other' ? '' : 'display:none' }, 'Package name (Play Store link shows it after id=)', other);
    app.addEventListener('change', () => { otherField.style.display = app.value === '__other' ? '' : 'none'; drawLetters(); });
    const appValue = () => (app.value === '__other' ? other.value.trim() : app.value);
    const drawLetters = () => {
      const taken = keyRows().filter((r) => r.app === appValue() && !(prev && r.key === prev.key && r.app === prev.app));
      letters.replaceChildren(...'abcdefghijklmnopqrstuvwxyz'.split('').map((l) => {
        const owner = taken.find((r) => r.key === l);
        return h('button', { type: 'button', class: (l === key ? 'on' : '') + (owner ? ' taken' : ''), title: owner ? `Now: ${actionLabel(owner.action)}` : '', onclick: () => { key = l; drawLetters(); } }, l);
      }));
    };
    drawLetters();
    const agentNames = new Set(c.agents.map((a) => a.name));
    const action = h('select', { 'aria-label': 'Action' },
      h('optgroup', { label: 'My skills' }, c.skills.filter((s) => !agentNames.has(s.name)).map((s) => h('option', { value: 'skill:' + s.name }, (s.emoji ? s.emoji + ' ' : '') + s.name))),
      agentNames.size > 0 && h('optgroup', { label: 'Agents' }, c.skills.filter((s) => agentNames.has(s.name)).map((s) => h('option', { value: 'skill:' + s.name }, (s.emoji ? s.emoji + ' ' : '') + s.name))),
      h('optgroup', { label: 'Ready-made acts' }, Object.entries(BUILTIN_ACTS).map(([n, [e]]) => h('option', { value: 'act:' + n }, e + ' ' + n))),
      h('optgroup', { label: 'Keyboard tools' }, Object.entries(WHEEL_TOOLS).map(([id, n]) => h('option', { value: 'tool:' + id }, n))),
      h('optgroup', { label: 'Other' }, h('option', { value: 'text:' }, '✍️ Type a saved text')));
    const isText = start.action.startsWith('text:');
    action.value = isText ? 'text:' : start.action || action.options[0]?.value;
    const text = h('textarea', { rows: 3, placeholder: 'Thanks! I will get back to you shortly.' }, isText ? start.action.slice(5) : '');
    const textField = h('label', { class: 'field', style: isText ? '' : 'display:none' }, 'Text to type', text);
    action.addEventListener('change', () => { textField.style.display = action.value === 'text:' ? '' : 'none'; });
    return h('div', null,
      h('label', { class: 'field' }, 'In which app?', app), otherField,
      h('label', { class: 'field' }, 'Hold this key', letters),
      h('label', { class: 'field' }, 'To run', action), textField,
      h('p', { class: 'muted small', style: 'margin-top:12px' }, 'A tap still types the letter. Holding it runs the action. An app-specific key wins over the same key in All apps.'),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: async () => {
          const pkg = appValue();
          if (!/^[A-Za-z0-9._]{0,100}$/.test(pkg)) return toast('That does not look like an app package');
          const value = action.value === 'text:' ? 'text:' + text.value : action.value;
          if (value === 'text:' || !value) return toast(action.value === 'text:' ? 'Type the text first' : 'Choose what to run');
          if (await saveKeyAction(prev, { key, app: pkg, action: value })) close();
        } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        prev && h('button', { class: 'btn ghost danger', onclick: async () => { if (await saveKeyAction(prev, null)) close(); } }, 'Delete')));
  });
}

function keysTab() {
  const c = state.config;
  const rows = keyRows();
  const on = c.keyboard.keysOn !== false;
  const sel = rows[state.keySel];
  const lit = new Set(rows.map((r) => r.key));
  return [
    h('div', { class: 'head' }, h('div', null, h('h1', null, 'Keys'), h('p', { class: 'muted sub' }, 'Hold a letter to run a skill, an agent, a tool or a saved text. Give a key a different job in each app.'))),
    h('div', { class: 'home keys' },
      h('div', { class: 'card keyspanel' },
        h('div', { class: 'keytools' },
          h('button', { class: 'iconbtn accent', title: 'Add', 'aria-label': 'Add key action', onclick: () => keyActionDialog(null) }, icon('square_plus')),
          h('button', { class: 'iconbtn', title: 'Edit', 'aria-label': 'Edit selected', disabled: !sel, onclick: () => sel && keyActionDialog(sel) }, icon('pencil')),
          h('button', { class: 'iconbtn', title: 'Delete', 'aria-label': 'Delete selected', disabled: !sel, onclick: async () => { if (sel && await saveKeyAction(sel, null)) state.keySel = -1; } }, icon('trash_2')),
          h('span', { class: 'spacer' }),
          h('button', { class: 'iconbtn', title: on ? 'Pause all key actions' : 'Resume key actions', 'aria-label': on ? 'Pause' : 'Resume', onclick: () => saveKeyboard({ keysOn: !on }, on ? 'Key actions paused' : 'Key actions on') }, icon(on ? 'pause_circle' : 'play_circle')),
          h('button', { class: 'iconbtn', title: 'Keyboard settings', 'aria-label': 'Keyboard settings', onclick: () => go('settings') }, icon('settings'))),
        !on && h('p', { class: 'small', style: 'color:var(--warn);margin:0 6px 10px' }, 'Paused: held letters just type on every phone until you resume.'),
        h('div', { class: 'keylist' },
          rows.length === 0 ? h('p', { class: 'muted', style: 'padding:10px' }, 'No key actions yet. Press + to add one.')
            : rows.map((r, i) => {
              const [, appName, emoji] = appInfo(r.app);
              return h('div', { class: 'keyrow' + (i === state.keySel ? ' sel' : '') + (on ? '' : ' paused'), tabindex: 0, title: appName,
                onclick: () => { state.keySel = i; render(); }, ondblclick: () => keyActionDialog(r), onkeydown: (e) => { if (e.key === 'Enter') keyActionDialog(r); } },
              h('span', { class: 'appicon', title: appName }, emoji),
              h('span', { class: 'box' }, 'HOLD ' + r.key.toUpperCase()),
              h('span', { class: 'arrow' }, '›'),
              h('span', { class: 'box' }, actionLabel(r.action)));
            }))),
      h('div', { class: 'card' }, h('h2', null, 'Key map'), h('p', { class: 'muted' }, 'Lit letters do something when held. Click one to set it.'),
        h('div', { class: 'keymap' }, ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'].map((row) => h('div', null, row.split('').map((l) => {
          const r = rows.find((x) => x.key === l && x.app === '') || rows.find((x) => x.key === l);
          return h('button', { class: lit.has(l) ? 'on' : '', title: r ? `Hold ${l.toUpperCase()}: ${actionLabel(r.action)}` : `Hold ${l.toUpperCase()}: nothing yet`,
            onclick: () => (r ? keyActionDialog(r) : keyActionDialog(null, { key: l, app: '', action: '' })) }, l);
        })))),
        h('p', { class: 'muted small', style: 'margin-top:14px' }, 'Tip: hold the space bar for the wheel. Its six slots are set under Settings → Wheel.'))),
  ];
}

// ---------- Analytics ----------

function analyticsTab() {
  const c = state.config;
  const u = c.usage;
  const month = new Date().toISOString().slice(0, 7);
  const current = u && u.month === month;
  const skills = current ? Object.entries(u.skills || {}).sort((a, b) => b[1].n - a[1].n).slice(0, 10) : [];
  const max = Math.max(1, ...skills.map(([, v]) => v.n));
  const tile = (label, value, sub) => h('div', { class: 'card tilestat' }, h('div', { class: 'muted small' }, label), h('b', null, value), sub && h('div', { class: 'muted small' }, sub));
  const pct = c.monthlyLimit && current ? Math.min(100, (u.cost / c.monthlyLimit) * 100) : 0;
  return [
    h('div', { class: 'head' }, h('div', null, h('h1', null, 'Analytics'), h('p', { class: 'muted sub' }, 'What your keyboard did this month, reported by your phone when it syncs.'))),
    h('div', { class: 'tiles' },
      tile('Actions run', current ? String(u.requests) : '0', month),
      tile('Estimated cost', current ? '$' + u.cost.toFixed(2) : '$0.00', c.monthlyLimit ? `of $${c.monthlyLimit} limit` : 'No monthly limit'),
      tile('Tokens', current ? (u.inTokens + u.outTokens).toLocaleString() : '0', current ? `${u.inTokens.toLocaleString()} in · ${u.outTokens.toLocaleString()} out` : null),
      tile('Web searches', current ? String(u.searches) : '0'),
      tile('Skills · Agents', `${c.skills.length} · ${(c.agents || []).length}`, `${keyRows().length} key actions`)),
    c.monthlyLimit > 0 && h('div', { class: 'card', style: 'margin-top:16px' }, h('div', { class: 'row' }, h('h2', { class: 'grow' }, 'Monthly limit'), h('span', { class: 'muted' }, `${Math.round(pct)}%`)),
      h('div', { class: 'progress', style: 'height:10px' }, h('div', { style: `width:${pct}%` }))),
    h('div', { class: 'card bars', style: 'margin-top:16px' }, h('h2', null, 'Most used'), h('p', { class: 'muted' }, 'Skills and agents by how often they ran this month.'),
      skills.length === 0 ? h('p', { class: 'muted small' }, 'Nothing yet. Run a skill on your phone and sync.')
        : skills.map(([name, v]) => h('div', { class: 'bar' }, h('span', { class: 'clamp' }, name), h('div', { class: 'track' }, h('div', { style: `width:${(v.n / max) * 100}%` })), h('span', null, `${v.n} · $${v.cost.toFixed(3)}`)))),
    h('div', { style: 'margin-top:16px' }, usageCard()),
  ];
}

// ---------- Verbo Pro ----------

const PRO_FEATURES = [
  ['sparkles', 'Personal assistant', 'Chat with Verbo in the app: it plans, writes and acts for you.'],
  ['plug', 'One-tap app shortcuts', 'Send to Slack and Discord, save to Notion, add Todoist tasks, file Linear and GitHub issues.'],
  ['zap', 'Auto actions that run by themselves', 'A copied message is translated, scheduled or looked up without a tap.'],
  ['message_square', 'Acts on incoming messages', 'A French WhatsApp message arrives already translated.'],
  ['calendar', 'Google Meet, Calendar and Gmail', 'Meeting links, events and emails straight from the keyboard.'],
];
const rupees = (paise) => '₹' + (paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 });

async function buy(plan) {
  try {
    const { url } = await api('POST', '/api/billing/link', { plan });
    location.href = url; // Razorpay's page: UPI, cards or netbanking. It sends the browser back here.
  } catch (e) { toast(e.message); }
}

function proTab() {
  const b = state.billing;
  if (!b) return h('div', { class: 'card' }, h('p', { class: 'muted' }, 'Loading…'));
  const p = b.plan;
  const status = p.pro
    ? (p.plan === 'lifetime' ? 'Verbo Pro for life. Thank you!' : `Verbo Pro until ${new Date(p.until).toLocaleDateString()}`)
    : 'Free plan: the keyboard, the wheel, translation, skills and acts.';
  const offer = (plan, title, note, strong) => h('div', { class: 'card', style: strong ? 'border:2px solid var(--accent, #7c3aed)' : '' },
    h('h2', null, title), h('p', { style: 'font-size:2rem;font-weight:700;margin:6px 0' }, rupees(b.prices[plan].amount)),
    h('p', { class: 'muted' }, note),
    h('button', { class: 'btn ' + (strong ? 'primary' : ''), style: 'margin-top:12px;width:100%;justify-content:center', disabled: !b.enabled,
      onclick: () => buy(plan) }, icon('zap'), plan === 'month' && p.pro && p.plan !== 'lifetime' ? 'Add a month' : 'Get ' + title));
  return [
    h('div', { class: 'head' }, h('div', null, h('h1', null, 'Verbo Pro'), h('p', { class: 'muted sub' }, 'Your plan follows your account to every phone you sign in on.'))),
    h('div', { class: 'stack' },
      h('div', { class: 'card row' }, h('div', { class: 'grow' }, h('h2', null, p.pro ? 'You have Pro' : 'You are on Free'), h('p', { class: 'muted' }, status)),
        h('span', { class: 'badge', style: 'font-size:.95rem;padding:6px 12px' }, p.pro ? 'PRO' : 'FREE')),
      h('div', { class: 'card' }, h('h2', null, 'What Pro adds'),
        PRO_FEATURES.map(([ic, title, about]) => h('div', { class: 'setting' }, h('div', { class: 'what' }, h('div', null, icon(ic), ' ', title), h('p', { class: 'muted small' }, about)),
          h('span', null, p.pro ? '✓' : '')))),
      p.plan !== 'lifetime' && h('div', { style: 'display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px' },
        offer('lifetime', 'Lifetime', 'Pay once, Pro forever. Uses your own AI key.', true),
        offer('month', '1 month', '30 days of Pro. Buy again whenever you like; days add up.', false)),
      !b.enabled && p.plan !== 'lifetime' && h('p', { class: 'muted' }, 'Payments are not set up on this server yet.'),
      h('div', { class: 'card' }, h('h2', null, 'Phones'), h('p', { class: 'muted' },
        `One account can be signed in on up to ${p.deviceLimit} phones at once. Remove one under Settings → Phones to sign in on another.`),
        h('button', { class: 'btn', style: 'margin-top:10px', onclick: () => go('settings') }, icon('smartphone'), 'Manage phones'))),
  ];
}

// ---------- shell ----------

const TABS = [
  ['skills', 'My skills', 'layout_grid', skillsTab],
  ['assistant', 'Assistant', 'sparkles', assistantTab],
  ['agents', 'Agents', 'bot', agentsTab, 'New'],
  ['keys', 'Keys', 'keyboard', keysTab],
  ['connections', 'Connections', 'plug', connectionsTab],
  ['analytics', 'Analytics', 'chart', analyticsTab],
  ['pro', 'Pro', 'zap', proTab],
  ['settings', 'Settings', 'settings', settingsTab],
];

async function go(tab) {
  state.tab = tab;
  if (tab !== 'agents') state.editing = null;
  if (tab === 'settings') state.devices = await api('GET', '/api/devices').catch(() => state.devices);
  if (tab === 'pro') state.billing = await api('GET', '/api/billing').catch(() => state.billing);
  render();
  window.scrollTo(0, 0);
}

function accountMenu() {
  modal('Account', (close) => h('div', null,
    h('div', { class: 'row', style: 'margin:6px 0 4px' }, h('span', { class: 'avatar' }, (state.name || state.email || '?')[0].toUpperCase()),
      h('div', { class: 'grow' }, state.name && h('b', null, state.name), h('p', { class: 'muted' }, state.email))),
    h('div', { class: 'actions' },
      h('button', { class: 'btn', onclick: () => { close(); tour(); } }, 'Show the tour'),
      h('button', { class: 'btn', onclick: () => { close(); local.set('checklist', ''); go('skills'); } }, 'Show Get started'),
      h('button', { class: 'btn ghost danger', onclick: async () => {
        await api('POST', '/api/logout').catch(() => {});
        close();
        Object.assign(state, { email: null, config: null, code: null, chat: [], editing: null });
        render();
      } }, icon('log_out'), 'Sign out'))));
}

const TITLES = { skills: ['My skills', 'Skills are AI instructions your keyboard can run on any text you type.'], connections: ['Connections', 'Connect your apps so skills and agents can read, write and act for you.'] };

function render() {
  if (!state.email || !state.config) return app.replaceChildren(authView());
  const current = TABS.find((t) => t[0] === state.tab) || TABS[0];
  const title = TITLES[current[0]];
  const search = current[0] === 'skills' && h('label', { class: 'search' }, icon('search'),
    h('input', { type: 'search', placeholder: 'Search skills, agents or actions…', value: state.search, 'aria-label': 'Search skills',
      oninput: (e) => { state.search = e.target.value; renderKeepingFocus(e.target); } }));
  const editing = current[0] === 'agents' && state.editing;
  app.replaceChildren(h('div', { class: 'shell' },
    h('aside', { class: 'side' },
      h('div', { class: 'brand' }, h('span', { class: 'logo' }, 'V'), h('div', null, h('b', null, 'Verbo'), h('small', null, 'AI agentic keyboard'))),
      h('nav', { 'aria-label': 'Sections' }, TABS.map(([id, name, ic, , badge]) =>
        h('button', { 'aria-current': id === current[0] ? 'page' : null, onclick: () => go(id) }, icon(ic), name, badge && h('span', { class: 'badge' }, badge)))),
      h('div', { class: 'promo' }, h('div', { class: 'key3d', style: 'position:relative;right:auto;top:auto;width:64px;height:64px;font-size:1.8rem;margin:0 auto;border-radius:16px' }, '⚡'),
        h('h3', null, 'Verbo for mobile'), h('p', null, state.devices.length ? `${state.devices.length} phone${state.devices.length > 1 ? 's' : ''} syncing` : 'Take your skills anywhere with the AI keyboard.'),
        h('button', { class: 'btn primary small', style: 'width:100%;justify-content:center', onclick: phoneHelp }, icon('smartphone'), state.devices.length ? 'Add a phone' : 'Get the app'))),
    h('main', null,
      !editing && h('div', { class: 'topbar' },
        title ? h('div', { class: 'titles' }, h('h1', null, title[0]), h('p', { class: 'muted sub' }, title[1])) : h('div', { class: 'titles' }),
        search,
        h('button', { class: 'avatar', 'aria-label': 'Account', title: state.email, onclick: accountMenu }, (state.name || state.email || '?')[0].toUpperCase())),
      // Pages with their own heading drop the duplicate one under the top bar.
      title ? stripHead(current[3]()) : current[3]())));
}

function stripHead(nodes) {
  const list = [nodes].flat();
  return list.filter((n) => !(n && n.classList && n.classList.contains('head')));
}

async function load() {
  [state.config, state.devices, state.billing] = await Promise.all([api('GET', '/api/config'), api('GET', '/api/devices'), api('GET', '/api/billing').catch(() => null)]);
  render();
  if (!local.get('tour')) setTimeout(() => tour(), 400);
}

(async () => {
  try {
    // Back from Google's sign-in page: say how it went, open the right tab, and tidy the address bar.
    // The app's Upgrade button opens /?tab=pro.
    const tab = new URLSearchParams(location.search).get('tab');
    if (tab && TABS.some((t) => t[0] === tab)) { state.tab = tab; history.replaceState(null, '', '/'); }
    // Back from Razorpay: say how the payment went and show the plan.
    const pro = new URLSearchParams(location.search).get('pro');
    if (pro) {
      state.tab = 'pro';
      history.replaceState(null, '', '/');
      setTimeout(() => toast(pro === 'active' ? 'Verbo Pro is active. Your phones get it on their next sync' : 'The payment did not complete'), 300);
    }
    const google = new URLSearchParams(location.search).get('google');
    if (google) {
      state.tab = 'connections';
      history.replaceState(null, '', '/');
      setTimeout(() => toast(google === 'connected' ? 'Google connected' : 'Google sign-in did not complete'), 300);
    }
    const me = await api('GET', '/api/me');
    Object.assign(state, { allowSignup: me.allowSignup, googleLogin: me.googleLogin, email: me.email, name: me.name || '' });
    if (me.email) await load(); else render();
  } catch (e) {
    app.replaceChildren(h('div', { class: 'formside', style: 'min-height:100vh' }, h('div', { class: 'card' }, 'Could not reach the server. ' + e.message)));
  }
})();
