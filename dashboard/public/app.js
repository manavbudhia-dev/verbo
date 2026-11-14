'use strict';

// ---------- small helpers ----------

// DOM helper: h('div', {class: 'x', onclick: fn}, child, 'text')
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

function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = window.ICONS[name] || '';
  return svg;
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
const state = {
  email: null, allowSignup: true, tab: 'skills', config: null, devices: [], code: null,
  search: '', chat: [], thinking: false, draftMessage: '',
};

// ---------- saving ----------

let saveSeq = 0;
async function save(patch, message) {
  const seq = ++saveSeq;
  try {
    const config = await api('PUT', '/api/config', patch);
    if (seq !== saveSeq) return true; // a newer save is on its way; its reply is the one to show
    state.config = config;
    toast(message || 'Saved');
    render();
    return true;
  } catch (e) {
    toast(e.message);
    return false;
  }
}

const saveKeyboard = (patch, message) => save({ keyboard: patch }, message);

// Skills are identified by name on the wheel, so a rename or delete has to be mirrored there.
const wheelId = (name) => 'skill:' + name;
const cleanSkill = (s) => ({ emoji: s.emoji || '', name: s.name, instruction: s.instruction, category: s.category || 'edit', key: s.key || '' });

function saveSkills(skills, wheel, message) {
  const patch = { skills: skills.map(cleanSkill) };
  if (wheel) patch.keyboard = { wheel };
  return save(patch, message);
}

// ---------- shared pieces ----------

function toggle(checked, onChange, label) {
  return h('label', { class: 'switch' },
    h('input', { type: 'checkbox', checked, 'aria-label': label, onchange: (e) => onChange(e.target.checked) }), h('span'));
}

function setting(title, help, control) {
  return h('div', { class: 'setting' },
    h('div', { class: 'what' }, h('div', null, title), help && h('p', { class: 'muted small' }, help)), control);
}

function select(value, options, onChange, label) {
  return h('select', { 'aria-label': label, onchange: (e) => onChange(e.target.value) },
    options.map(([v, name]) => h('option', { value: v, selected: v === value }, name)));
}

function modal(title, build) {
  const d = h('dialog');
  const close = () => d.close();
  d.append(h('h2', null, title), build(close));
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
  return d;
}

const WHEEL_TOOLS = { ai: 'Ask AI', translate: 'Translate', rewrite: 'Rewrite', meet: 'Meeting link', voice: 'Voice', clips: 'Clips' };
const MAX_WHEEL = 6;
const GROUPS = [['write', 'Write'], ['edit', 'Edit'], ['translate', 'Translate'], ['summarize', 'Summarize']];

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
      h('p', { class: 'muted' }, 'Set up your keyboard from the web.'),
      h('label', { class: 'field' }, 'Email', email),
      h('label', { class: 'field' }, mode === 'signup' ? 'Password (10 characters or more)' : 'Password', password),
      h('div', { class: 'actions', style: 'margin-top:18px' },
        h('button', { class: 'btn primary', type: 'submit' }, mode === 'signup' ? 'Create account' : 'Sign in'),
        state.allowSignup && h('button', { class: 'btn ghost', type: 'button', onclick: () => { mode = mode === 'signup' ? 'login' : 'signup'; draw(); } },
          mode === 'signup' ? 'I have an account' : 'Create an account')));
    box.replaceChildren(h('div', { class: 'brand' }, h('span', null, 'V'), 'Verbo'), form);
  };
  draw();
  return box;
}

// ---------- My skills ----------

/** Pick which skill a letter runs. One letter runs one skill, so choosing a skill moves the letter to it. */
function keyDialog(letter) {
  const skills = state.config.skills;
  const current = skills.findIndex((s) => s.key === letter);
  modal(`Hold ${letter.toUpperCase()} to run…`, (close) => {
    const pick = async (index) => {
      const next = skills.map((s, i) => ({ ...s, key: i === index ? letter : s.key === letter ? '' : s.key }));
      if (await saveSkills(next, null, index < 0 ? `${letter.toUpperCase()} is free again` : `Hold ${letter.toUpperCase()} runs ${skills[index].name}`)) close();
    };
    return h('div', null,
      h('p', { class: 'muted', style: 'margin:4px 0 14px' }, 'A quick tap still types the letter. Holding it runs the skill.'),
      h('div', { class: 'chips' },
        skills.map((s, i) => h('button', { class: 'chip' + (i === current ? ' on' : ''), onclick: () => pick(i) },
          (s.emoji ? s.emoji + ' ' : '') + s.name, s.key && s.key !== letter ? h('span', { class: 'muted small' }, s.key.toUpperCase()) : null)),
        current >= 0 && h('button', { class: 'chip', onclick: () => pick(-1) }, 'Nothing')),
      skills.length === 0 && h('p', { class: 'muted' }, 'Create a skill first.'),
      h('div', { class: 'actions' }, h('button', { class: 'btn', onclick: close }, 'Close')));
  });
}

function skillDialog(index, preset) {
  const skills = state.config.skills;
  const wheel = state.config.keyboard.wheel;
  const s = skills[index] || preset || {};
  const isNew = index < 0;
  let key = s.key || '';
  let onWheel = isNew ? !!s.wheel : wheel.includes(wheelId(s.name));
  modal(isNew ? 'New skill' : 'Edit skill', (close) => {
    const name = h('input', { type: 'text', value: s.name || '', required: true, placeholder: 'Pirate', maxLength: 40 });
    const emoji = h('input', { type: 'text', value: s.emoji || '', placeholder: '🏴‍☠️', maxLength: 16 });
    const instruction = h('textarea', { required: true, placeholder: 'Rewrite it the way a cheerful pirate would say it.' });
    instruction.value = s.instruction || '';
    const group = select(s.category || s.group || 'edit', GROUPS, () => {}, 'Group');
    const letters = h('div', { class: 'letters' });
    const drawLetters = () => letters.replaceChildren(
      ...'abcdefghijklmnopqrstuvwxyz'.split('').map((l) => {
        const owner = skills.find((x, i) => x.key === l && i !== index);
        return h('button', { type: 'button', class: (l === key ? 'on' : '') + (owner ? ' taken' : ''), title: owner ? `Now runs ${owner.name}` : '',
          onclick: () => { key = l; drawLetters(); } }, l);
      }),
      h('button', { type: 'button', class: 'none' + (key === '' ? ' on' : ''), onclick: () => { key = ''; drawLetters(); } }, 'No key'));
    drawLetters();
    return h('form', { onsubmit: async (e) => {
      e.preventDefault();
      const fresh = { emoji: emoji.value.trim(), name: name.value.trim(), instruction: instruction.value.trim(), category: group.value, key };
      if (skills.some((x, i) => i !== index && x.name.toLowerCase() === fresh.name.toLowerCase())) return toast('Another skill already has that name');
      // Taking a letter frees it from the skill that had it.
      const next = skills.map((x) => (key && x.key === key ? { ...x, key: '' } : x));
      if (isNew) next.push(fresh); else next[index] = fresh;
      let nextWheel = wheel.filter((id) => id !== wheelId(s.name));
      if (onWheel) {
        if (nextWheel.length >= MAX_WHEEL) return toast(`The wheel holds ${MAX_WHEEL}. Remove one under Settings first`);
        // Keep its place on the wheel when it was already there.
        const at = wheel.indexOf(wheelId(s.name));
        if (at >= 0) nextWheel.splice(Math.min(at, nextWheel.length), 0, wheelId(fresh.name)); else nextWheel.push(wheelId(fresh.name));
      }
      if (await saveSkills(next, nextWheel, isNew ? 'Skill created' : 'Skill saved')) close();
    } },
      h('div', { class: 'row', style: 'align-items:flex-end' },
        h('label', { class: 'field grow' }, 'Name', name),
        h('label', { class: 'field', style: 'width:90px' }, 'Emoji', emoji)),
      h('label', { class: 'field' }, 'What should it do with the text?', instruction),
      h('label', { class: 'field' }, 'Hold key', h('p', { class: 'muted small', style: 'font-weight:400' }, 'Holding this letter on the keyboard runs the skill.'), letters),
      // The wheel holds six. When it is full, say so here rather than failing on save.
      (!onWheel && wheel.length >= MAX_WHEEL)
        ? h('div', { class: 'setting', style: 'margin-top:10px' }, h('div', { class: 'what' }, 'On the wheel', h('p', { class: 'muted small' }, `The wheel is full (${MAX_WHEEL}). Remove one under Settings → Wheel to add this.`)))
        : h('div', { class: 'setting', style: 'margin-top:10px' }, h('div', { class: 'what' }, 'On the wheel', h('p', { class: 'muted small' }, 'Hold the space bar, slide to it, release')),
          toggle(onWheel, (v) => { onWheel = v; }, 'On the wheel')),
      h('div', { class: 'setting' }, h('div', { class: 'what' }, 'Group', h('p', { class: 'muted small' }, 'Edit skills also appear under Rewrite')), group),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', type: 'submit' }, isNew ? 'Create skill' : 'Save'),
        h('button', { class: 'btn', type: 'button', onclick: close }, 'Cancel'),
        !isNew && h('button', { class: 'btn ghost danger', type: 'button', onclick: async () => {
          if (!confirm(`Delete ${s.name}?`)) return;
          if (await saveSkills(skills.filter((_, i) => i !== index), wheel.filter((id) => id !== wheelId(s.name)), 'Skill deleted')) close();
        } }, 'Delete')));
  });
}

const TRIGGER_KINDS = [
  ['person', "a person's name"], ['time', 'a date or time'], ['place', 'a place or address'], ['link', 'a link'],
  ['email', 'an email address'], ['phone', 'a phone number'], ['foreign', 'another language'], ['keyword', 'words I choose'], ['any', 'anything'],
];
const BUILTIN_TRIGGER_SKILLS = ['Who is', 'Save event', 'Place', 'Reply options', 'Schedule it', 'Understand any language', 'Save to Notion', 'Find anything', 'Extract what matters', 'Explain simply', 'Fact check', 'Sound like a native'];

// Auto actions: copy a message on the phone, and the matching skill is offered (or run) by itself.
function triggersCard() {
  const triggers = state.config.triggers || [];
  const kindName = (k) => (TRIGGER_KINDS.find((x) => x[0] === k) || [k, k])[1];
  return h('div', { class: 'card', style: 'margin-top:16px' },
    h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', null, 'Auto actions'),
      h('p', { class: 'muted' }, 'When you copy a message on your phone, Verbo checks it there and offers the right skill as a ⚡ chip. Automatic ones run by themselves.')),
      h('button', { class: 'btn', onclick: () => triggerDialog(-1) }, icon('plus'), 'New')),
    triggers.length === 0 ? h('p', { class: 'muted small' }, 'None yet.')
      : triggers.map((t, i) => h('div', { class: 'setting' },
        h('div', { class: 'what' }, h('div', null, '⚡ ', t.skill, t.auto ? ' · automatic' : ''),
          h('p', { class: 'muted small' }, 'When copied text has ', t.kind === 'keyword' ? `“${t.keyword}”` : kindName(t.kind))),
        h('button', { class: 'btn ghost', onclick: () => triggerDialog(i) }, 'Edit'))));
}

// Memory: facts the keyboard uses when they help ("Mummy: +91…", "UPI: me@okicici"). Synced with the phone.
function memoryCard() {
  const facts = state.config.memory || [];
  return h('div', { class: 'card', style: 'margin-top:16px' },
    h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', null, 'Memory'),
      h('p', { class: 'muted' }, 'Things your keyboard knows about you and uses when they help. On the phone you can also type “remember my UPI is …” and hold the space bar.')),
      h('button', { class: 'btn', onclick: () => factDialog(-1) }, icon('plus'), 'Add')),
    facts.length === 0 ? h('p', { class: 'muted small' }, 'Nothing yet. Try your home address, UPI ID, or who “mummy” is.')
      : facts.map((f, i) => h('div', { class: 'setting' },
        h('div', { class: 'what' }, h('div', null, f.label), h('p', { class: 'muted small' }, f.value)),
        h('button', { class: 'btn ghost', onclick: () => factDialog(i) }, 'Edit'))));
}

// Text shortcuts (;addr) and pinned clips, both synced with the phone.
function shortcutsCard() {
  const list = state.config.shortcuts || [];
  const clips = state.config.clips || [];
  return h('div', { class: 'card', style: 'margin-top:16px' },
    h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', null, 'Text shortcuts'),
      h('p', { class: 'muted' }, 'On the phone, type a semicolon, the key and a space: ;addr becomes your address. Memory items work too (;upi).')),
      h('button', { class: 'btn', onclick: () => shortcutDialog(-1) }, icon('plus'), 'Add')),
    list.length === 0 ? h('p', { class: 'muted small' }, 'None yet.')
      : list.map((s, i) => h('div', { class: 'setting' },
        h('div', { class: 'what' }, h('div', null, ';' + s.key), h('p', { class: 'muted small clamp' }, s.text)),
        h('button', { class: 'btn ghost', onclick: () => shortcutDialog(i) }, 'Edit'))),
    h('h2', { style: 'margin-top:18px' }, 'Pinned clips'),
    clips.length === 0 ? h('p', { class: 'muted small' }, 'Pin a copied text from the Clips panel on the phone and it shows here.')
      : clips.map((c, i) => h('div', { class: 'setting' }, h('div', { class: 'what' }, h('p', { class: 'clamp' }, c)),
        h('button', { class: 'btn ghost danger', onclick: () => save({ clips: clips.filter((_, j) => j !== i) }, 'Clip removed') }, 'Remove'))));
}

function shortcutDialog(index) {
  const list = state.config.shortcuts || [];
  const s = list[index] || { key: '', text: '' };
  modal(index < 0 ? 'New shortcut' : 'Edit shortcut', (close) => {
    const key = h('input', { type: 'text', value: s.key, placeholder: 'addr' });
    const text = h('textarea', { rows: 4, placeholder: 'Flat 4B, Green Park, Jalandhar 144001' }, s.text);
    const saveList = async (next, msg) => { if (await save({ shortcuts: next }, msg)) close(); };
    return h('div', null,
      h('label', { class: 'field' }, 'Key (type ;key on the phone)', key), h('label', { class: 'field' }, 'Text it becomes', text),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: () => {
          const item = { key: key.value.trim().replace(/^;/, '').toLowerCase(), text: text.value };
          if (!item.key || !item.text.trim()) return toast('It needs a key and the text');
          const next = list.slice(); if (index < 0) next.push(item); else next[index] = item;
          saveList(next, 'Shortcut saved');
        } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        index >= 0 && h('button', { class: 'btn ghost danger', onclick: () => saveList(list.filter((_, i) => i !== index), 'Shortcut removed') }, 'Delete')));
  });
}

function factDialog(index) {
  const list = state.config.memory || [];
  const f = list[index] || { label: '', value: '' };
  modal(index < 0 ? 'Remember' : 'Edit memory', (close) => {
    const label = h('input', { type: 'text', value: f.label, placeholder: 'Mummy, Home address, UPI' });
    const value = h('textarea', { rows: 3, placeholder: '+91 98765 43210' }, f.value);
    const saveList = async (next, msg) => { if (await save({ memory: next }, msg)) close(); };
    return h('div', null,
      h('label', { class: 'field' }, 'What it is', label), h('label', { class: 'field' }, 'Value', value),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: () => {
          if (!label.value.trim() || !value.value.trim()) return toast('It needs a name and a value');
          const next = list.slice(); const item = { label: label.value.trim(), value: value.value.trim() };
          if (index < 0) next.push(item); else next[index] = item;
          saveList(next, 'Remembered');
        } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        index >= 0 && h('button', { class: 'btn ghost danger', onclick: () => saveList(list.filter((_, i) => i !== index), 'Forgotten') }, 'Forget')));
  });
}

// A chain runs several acts in order as one act. Stored as a skill: "Chain: step | step"; the phone expands each step.
const isChain = (s) => s.instruction.startsWith('Chain:');
const chainSteps = (s) => s.instruction.slice(6).split('|').map((x) => x.trim()).filter(Boolean);

function chainDialog(index) {
  const skills = state.config.skills;
  const existing = skills[index];
  const names = [...new Set([...skills.filter((s) => !isChain(s)).map((s) => s.name), ...BUILTIN_TRIGGER_SKILLS])];
  const steps = existing ? chainSteps(existing) : [names[0], names[1]];
  modal(existing ? 'Edit chain' : 'New chain', (close) => {
    const name = h('input', { type: 'text', value: existing ? existing.name : '', placeholder: 'Trip planner' });
    const list = h('div');
    const draw = () => list.replaceChildren(...steps.map((st, i) => h('div', { class: 'row', style: 'margin:6px 0' },
      h('span', { class: 'keycap' }, String(i + 1)),
      select(names.includes(st) ? st : names[0], names.map((n) => [n, n]), (v) => { steps[i] = v; }, 'Step ' + (i + 1)),
      steps.length > 2 && h('button', { class: 'btn ghost', onclick: () => { steps.splice(i, 1); draw(); } }, icon('x')))));
    draw();
    return h('div', null,
      h('p', { class: 'muted' }, 'Runs each act in order, passing what one makes to the next. Events from any step are saved to the phone calendar.'),
      h('label', { class: 'field' }, 'Name', name), list,
      steps.length < 6 && h('button', { class: 'btn', style: 'margin-top:6px', onclick: () => { steps.push(names[0]); draw(); } }, icon('plus'), 'Add step'),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: async () => {
          if (!name.value.trim()) return toast('Give the chain a name');
          const item = { emoji: '⛓️', name: name.value.trim(), instruction: 'Chain: ' + steps.join(' | '), category: 'write', key: existing ? existing.key : '' };
          const next = skills.slice();
          if (existing) next[index] = item; else next.push(item);
          if (await saveSkills(next, null, 'Chain saved')) close();
        } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        existing && h('button', { class: 'btn ghost danger', onclick: async () => { if (await saveSkills(skills.filter((_, i) => i !== index), null, 'Chain removed')) close(); } }, 'Delete')));
  });
}

function triggerDialog(index) {
  const list = state.config.triggers || [];
  const t = list[index] || { kind: 'person', keyword: '', skill: 'Who is', auto: false };
  const names = [...new Set([...state.config.skills.map((s) => s.name), ...BUILTIN_TRIGGER_SKILLS])];
  modal(index < 0 ? 'New auto action' : 'Edit auto action', (close) => {
    const keyword = h('input', { type: 'text', value: t.keyword, placeholder: 'invoice, urgent' });
    const keywordField = h('label', { class: 'field', style: t.kind === 'keyword' ? null : 'display:none' }, 'Words to look for, separated by commas', keyword);
    const kind = select(t.kind, TRIGGER_KINDS, (v) => { keywordField.style.display = v === 'keyword' ? '' : 'none'; }, 'Kind');
    const skill = select(t.skill, names.map((n) => [n, n]), () => {}, 'Skill');
    const auto = h('input', { type: 'checkbox', checked: t.auto });
    const saveList = async (next, msg) => { if (await save({ triggers: next }, msg)) close(); };
    return h('div', null,
      h('label', { class: 'field' }, 'When the copied text has', kind), keywordField,
      h('label', { class: 'field' }, 'Run this skill', skill),
      h('label', { class: 'row', style: 'margin:8px 0' }, auto, 'Automatic: run it without asking'),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: () => {
          const item = { kind: kind.value, keyword: keyword.value.trim(), skill: skill.value, auto: auto.checked };
          const next = list.slice();
          if (index < 0) next.push(item); else next[index] = item;
          saveList(next, 'Auto action saved');
        } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        index >= 0 && h('button', { class: 'btn ghost danger', onclick: () => saveList(list.filter((_, i) => i !== index), 'Auto action removed') }, 'Delete')));
  });
}

function skillsTab() {
  const { skills, keyboard } = state.config;
  const byKey = Object.fromEntries(skills.filter((s) => s.key).map((s) => [s.key, s]));
  const q = state.search.trim().toLowerCase();
  const shown = skills.map((s, i) => [s, i]).filter(([s]) => !q || (s.name + ' ' + s.instruction).toLowerCase().includes(q));
  return [
    h('div', { class: 'head' },
      h('div', null, h('h1', null, 'My skills'), h('p', { class: 'muted sub' }, 'Each skill is one instruction your keyboard can apply to what you typed.')),
      h('div', { class: 'actions' },
        h('button', { class: 'btn', onclick: () => go('assistant') }, icon('sparkles'), 'Ask the assistant'),
        h('button', { class: 'btn', onclick: () => chainDialog(-1) }, '⛓️ New chain'),
        h('button', { class: 'btn primary', onclick: () => skillDialog(-1) }, icon('plus'), 'New skill'))),
    h('div', { class: 'card' },
      h('h2', null, 'Hold keys'),
      h('p', { class: 'muted' }, 'Click a letter to choose the skill it runs when held. Purple letters already run one.'),
      h('div', { class: 'keymap' }, ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'].map((row) => h('div', null, row.split('').map((l) =>
        h('button', { class: byKey[l] ? 'on' : '', title: byKey[l] ? `Hold ${l.toUpperCase()}: ${byKey[l].name}` : `Hold ${l.toUpperCase()}: nothing yet`,
          'aria-label': byKey[l] ? `${l.toUpperCase()}, runs ${byKey[l].name}` : `${l.toUpperCase()}, free`, onclick: () => keyDialog(l) }, l)))))),
    triggersCard(),
    memoryCard(),
    shortcutsCard(),
    skills.length > 6 && h('input', { type: 'search', placeholder: 'Search my skills', value: state.search, 'aria-label': 'Search my skills',
      style: 'margin-top:16px', oninput: (e) => { state.search = e.target.value; renderKeepingFocus(e.target); } }),
    h('div', { class: 'grid', style: 'margin-top:16px' },
      shown.map(([s, i]) => h('button', { class: 'card tile', onclick: () => (isChain(s) ? chainDialog(i) : skillDialog(i)) },
        h('div', { class: 'top' }, h('div', { class: 'emoji' }, s.emoji || '✦'), h('h3', null, s.name), s.key && h('span', { class: 'keycap', title: `Hold ${s.key.toUpperCase()}` }, s.key.toUpperCase())),
        h('p', { class: 'muted clamp' }, s.instruction),
        h('div', { class: 'foot' },
          h('span', { class: 'pill off' }, (GROUPS.find((g) => g[0] === s.category) || GROUPS[1])[1]),
          keyboard.wheel.includes(wheelId(s.name)) && h('span', { class: 'pill' }, 'On the wheel')))),
      !q && h('button', { class: 'card tile dashed', onclick: () => skillDialog(-1) }, icon('plus'), 'New skill')),
    q && shown.length === 0 && h('p', { class: 'muted', style: 'margin-top:16px' }, 'No skill matches that search.'),
  ];
}

// Typing in the search box re-renders the page; put the cursor back where it was.
function renderKeepingFocus(input) {
  const at = input.selectionStart;
  render();
  const again = app.querySelector('input[type=search]');
  if (again) { again.focus(); again.setSelectionRange(at, at); }
}

// ---------- Assistant ----------

const IDEAS = [
  'Create a skill that turns my text into emoji',
  'I want to reply to messages in Hindi with one key',
  'Set up a skill that writes a polite email and opens it in Gmail',
  'How do I get a Google Meet link from the keyboard?',
];

async function sendChat(text) {
  const content = text.trim();
  if (!content || state.thinking) return;
  state.chat.push({ role: 'user', content });
  state.draftMessage = '';
  state.thinking = true;
  render();
  try {
    const messages = state.chat.filter((m) => m.role === 'user' || m.role === 'assistant').map((m) => ({ role: m.role, content: m.content }));
    const answer = await api('POST', '/api/chat', { messages });
    state.chat.push({ role: 'assistant', content: answer.reply, actions: answer.actions.map((a) => ({ ...a, done: false })) });
  } catch (e) {
    state.chat.push({ role: 'error', content: e.message });
  }
  state.thinking = false;
  render();
  window.scrollTo(0, document.body.scrollHeight);
}

async function applyAction(action) {
  const { skills, keyboard } = state.config;
  if (action.type === 'create_skill') {
    let name = action.name;
    for (let n = 2; skills.some((s) => s.name.toLowerCase() === name.toLowerCase()); n++) name = `${action.name} ${n}`;
    const key = action.key && !skills.some((s) => s.key === action.key) ? action.key : '';
    const wheel = action.wheel && keyboard.wheel.length < MAX_WHEEL ? [...keyboard.wheel, wheelId(name)] : null;
    const note = action.wheel && !wheel ? `${name} created. The wheel is full, so it was not added there` : `${name} created`;
    if (await saveSkills([...skills, { emoji: action.emoji, name, instruction: action.instruction, category: action.group, key }], wheel, note)) action.done = true;
  } else if (action.type === 'bind_key') {
    const index = skills.findIndex((s) => s.name.toLowerCase() === action.skill.toLowerCase());
    if (index < 0) return toast(`There is no skill called ${action.skill}`);
    const next = skills.map((s, i) => ({ ...s, key: i === index ? action.key : s.key === action.key ? '' : s.key }));
    if (await saveSkills(next, null, `Hold ${action.key.toUpperCase()} runs ${skills[index].name}`)) action.done = true;
  } else if (action.type === 'add_to_wheel') {
    const skill = skills.find((s) => s.name.toLowerCase() === action.skill.toLowerCase());
    if (!skill) return toast(`There is no skill called ${action.skill}`);
    if (keyboard.wheel.includes(wheelId(skill.name))) { action.done = true; return render(); }
    if (keyboard.wheel.length >= MAX_WHEEL) return toast(`The wheel holds ${MAX_WHEEL}. Remove one under Settings first`);
    if (await saveKeyboard({ wheel: [...keyboard.wheel, wheelId(skill.name)] }, `${skill.name} is on the wheel`)) action.done = true;
  }
  render();
}

function actionCard(action) {
  const done = h('span', { class: 'pill ok' }, 'Done');
  if (action.type === 'create_skill') {
    return h('div', { class: 'card proposal' },
      h('div', { class: 'row' }, h('div', { class: 'emoji' }, action.emoji || '✦'), h('h3', { class: 'grow' }, action.name),
        action.key && h('span', { class: 'keycap', title: 'Suggested hold key' }, action.key.toUpperCase())),
      action.does && [h('div', { class: 'label' }, 'WHAT IT DOES'), h('div', { class: 'box' }, action.does)],
      h('div', { class: 'label' }, 'INSTRUCTION'), h('div', { class: 'box' }, action.instruction),
      h('div', { class: 'foot', style: 'margin-top:12px' },
        h('span', { class: 'pill off' }, (GROUPS.find((g) => g[0] === action.group) || GROUPS[1])[1]), action.wheel && h('span', { class: 'pill' }, 'Goes on the wheel')),
      h('div', { class: 'actions', style: 'margin-top:14px' }, action.done ? done : [
        h('button', { class: 'btn primary', onclick: () => applyAction(action) }, icon('check'), 'Create skill'),
        h('button', { class: 'btn', onclick: () => skillDialog(-1, { ...action, category: action.group }) }, 'Edit first')]));
  }
  if (action.type === 'bind_key') {
    return h('div', { class: 'card proposal row' }, h('span', { class: 'keycap' }, action.key.toUpperCase()),
      h('div', { class: 'grow' }, `Hold ${action.key.toUpperCase()} to run ${action.skill}`),
      action.done ? done : h('button', { class: 'btn primary', onclick: () => applyAction(action) }, 'Apply'));
  }
  if (action.type === 'add_to_wheel') {
    return h('div', { class: 'card proposal row' }, h('div', { class: 'emoji' }, icon('circle_dashed')),
      h('div', { class: 'grow' }, `Put ${action.skill} on the wheel`),
      action.done ? done : h('button', { class: 'btn primary', onclick: () => applyAction(action) }, 'Apply'));
  }
  return h('div', { class: 'card proposal row' }, h('div', { class: 'emoji' }, icon('plug')),
    h('div', { class: 'grow' }, h('div', null, action.label), action.why && h('p', { class: 'muted small' }, action.why)),
    h('button', { class: 'btn', onclick: () => go(action.page === 'phones' ? 'settings' : action.page) }, 'Open', icon('arrow_right')));
}

function assistantTab() {
  const c = state.config;
  const hasKey = c.provider === 'openai' ? c.openaiKeySet : c.provider === 'custom' ? c.customKeySet : c.apiKeySet;
  const input = h('textarea', { placeholder: 'Describe what you want your keyboard to do…', rows: 1, 'aria-label': 'Message to the assistant',
    oninput: (e) => { state.draftMessage = e.target.value; },
    onkeydown: (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(input.value); } } });
  input.value = state.draftMessage;
  return [
    h('div', { class: 'head' },
      h('div', null, h('h1', null, 'Assistant'), h('p', { class: 'muted sub' }, 'Say what you want. It drafts the skill or shows the next step, and nothing changes until you approve.')),
      state.chat.length > 0 && h('button', { class: 'btn', onclick: () => { state.chat = []; render(); } }, 'New chat')),
    !hasKey && h('div', { class: 'card row' }, h('div', { class: 'emoji' }, icon('key_round')),
      h('div', { class: 'grow' }, h('h3', null, 'Add your AI key first'), h('p', { class: 'muted' }, 'The assistant runs on the same key your keyboard uses.')),
      h('button', { class: 'btn primary', onclick: () => go('settings') }, 'Open settings')),
    h('div', { class: 'chat', style: 'margin-top:8px' },
      state.chat.length === 0 && h('div', { class: 'card' },
        h('h2', null, 'What would you like to set up?'), h('p', { class: 'muted' }, 'Try one of these, or type your own.'),
        h('div', { class: 'chips' }, IDEAS.map((idea) => h('button', { class: 'chip', onclick: () => sendChat(idea) }, idea)))),
      state.chat.map((m) => [
        h('div', { class: 'bubble ' + (m.role === 'user' ? 'user' : 'bot'), style: m.role === 'error' ? 'color:var(--danger)' : null }, m.content),
        (m.actions || []).map(actionCard)]),
      state.thinking && h('div', { class: 'bubble bot muted' }, 'Thinking…')),
    h('div', { class: 'composer' }, input,
      h('button', { class: 'btn primary', disabled: state.thinking, 'aria-label': 'Send', onclick: () => sendChat(input.value) }, icon('send'))),
  ];
}

// ---------- Connections ----------

function tokenDialog(title, help, field, isSet) {
  modal(title, (close) => {
    const input = h('input', { type: 'password', placeholder: isSet ? 'Paste a new token to replace the saved one' : 'Paste token', autocomplete: 'off' });
    return h('div', null, h('p', { class: 'muted', style: 'margin:4px 0 4px' }, help), h('label', { class: 'field' }, 'Token', input),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: async () => { if (!input.value.trim()) return toast('Paste a token first'); if (await save({ [field]: input.value }, `${title} connected`)) close(); } }, 'Save'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        isSet && h('button', { class: 'btn ghost danger', onclick: async () => { if (await save({ [field]: '' }, `${title} disconnected`)) close(); } }, 'Disconnect')));
  });
}

function apiDialog(index) {
  const apis = state.config.customApis;
  const strip = (a) => ({ id: a.id, name: a.name, description: a.description, url: a.url, headerName: a.headerName });
  const a = apis[index] || {};
  modal(index < 0 ? 'Connect an API' : 'Edit API', (close) => {
    const f = {
      name: h('input', { type: 'text', value: a.name || '', required: true, placeholder: 'NBA scores' }),
      description: h('input', { type: 'text', value: a.description || '', placeholder: 'Returns today\'s scores; use for basketball questions' }),
      url: h('input', { type: 'url', value: a.url || '', required: true, placeholder: 'https://api.example.com/search?q={query}' }),
      headerName: h('input', { type: 'text', value: a.headerName || '', placeholder: 'X-Api-Key' }),
      headerValue: h('input', { type: 'password', placeholder: a.headerValueSet ? 'Saved. Leave empty to keep it' : '', autocomplete: 'off' }),
    };
    return h('form', { onsubmit: async (e) => {
      e.preventDefault();
      const item = { id: a.id, name: f.name.value, description: f.description.value, url: f.url.value, headerName: f.headerName.value };
      if (f.headerValue.value || !a.headerValueSet) item.headerValue = f.headerValue.value;
      const next = apis.map(strip);
      if (index < 0) next.push(item); else next[index] = item;
      if (await save({ customApis: next }, 'API saved')) close();
    } },
      h('label', { class: 'field' }, 'Name', f.name),
      h('label', { class: 'field' }, 'What it returns and when to use it', f.description),
      h('label', { class: 'field' }, 'Address (https). Put {query} where the search term goes', f.url),
      h('div', { class: 'row', style: 'align-items:flex-end' },
        h('label', { class: 'field grow' }, 'Header name (optional)', f.headerName), h('label', { class: 'field grow' }, 'Header value', f.headerValue)),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', type: 'submit' }, 'Save'), h('button', { class: 'btn', type: 'button', onclick: close }, 'Cancel'),
        index >= 0 && h('button', { class: 'btn ghost danger', type: 'button', onclick: async () => {
          if (await save({ customApis: apis.filter((_, i) => i !== index).map(strip) }, 'API removed')) close();
        } }, 'Remove')));
  });
}

function connection(iconName, title, about, status, ...controls) {
  return h('div', { class: 'card tile conn', style: 'cursor:default' },
    h('div', { class: 'top' }, h('div', { class: 'emoji' }, icon(iconName)), h('h3', null, title), status),
    h('p', { class: 'muted' }, about),
    h('div', { class: 'foot' }, controls));
}

// Apps a skill opens with the text already filled in. Nothing to connect; the user presses send in the app.
const LINK_APPS = [
  ['mail', 'Gmail', 'Writes the email and opens it as a ready draft. With Google connected below, it sends by itself.',
    ['📧', 'Email', 'Turn the text into a clear email with a short subject. Return the email text, then a final [[open:mailto:?subject=...&body=...]] line with both URL-encoded so it opens as a ready draft in Gmail.', 'write']],
  ['briefcase', 'LinkedIn', 'Writes a post and opens LinkedIn with it filled in. The “Who is” skill finds people.',
    ['💼', 'LinkedIn post', 'Turn the text into a LinkedIn post: a strong first line, short paragraphs, no more than three hashtags at the end. Return the post, then a final [[open:https://www.linkedin.com/feed/?shareActive=true&text=...]] line with the post URL-encoded.', 'write']],
  ['message_circle', 'WhatsApp', 'Writes the message and opens WhatsApp to pick a chat.',
    ['🟢', 'WhatsApp', 'Write the message the user wants to send. Return it, then a final [[open:https://wa.me/?text=...]] line with the message URL-encoded (use wa.me/NUMBER if they gave a number with country code).', 'write']],
  ['send', 'Telegram', 'Writes the message and opens Telegram to share it.',
    ['✈️', 'Telegram', 'Write the message the user wants to send. Return it, then a final [[open:https://t.me/share/url?url=%20&text=...]] line with the message URL-encoded.', 'write']],
  ['inbox', 'Outlook', 'Writes the email and opens an Outlook draft.',
    ['📨', 'Outlook email', 'Turn the text into a clear work email with a subject. Return it, then a final [[open:https://outlook.office.com/mail/deeplink/compose?subject=...&body=...]] line with both URL-encoded.', 'write']],
  ['calendar', 'Google Calendar', 'Turns “lunch with Sam Friday 1pm” into an event. With Google connected below, it is saved by itself.',
    ['📅', 'Calendar event', 'The text describes an event. Work out the title, date, time and place, and return one line describing it, then a final [[open:https://calendar.google.com/calendar/render?action=TEMPLATE&text=...&dates=YYYYMMDDTHHMMSS/YYYYMMDDTHHMMSS&location=...]] line. Assume one hour if no end time is given.', 'write']],
  ['map_pin', 'Google Maps', 'Finds places and adds a Maps link and a small map. Try the “Place” skill.', null],
  ['play', 'YouTube', 'Opens a YouTube search for what you describe.',
    ['▶️', 'YouTube', 'Work out what the user wants to watch. Return one short line, then a final [[open:https://www.youtube.com/results?search_query=...]] line.', 'write']],
  ['at_sign', 'X', 'Writes a post under 280 characters and opens X with it.',
    ['𝕏', 'Post on X', 'Turn the text into a post under 280 characters. Return it, then a final [[open:https://x.com/intent/post?text=...]] line with the post URL-encoded.', 'write']],
];

// Apps that need a token. Paired phones get the token on sync and call the app's own API during an action.
const TOKEN_APPS = [
  ['hash', 'Slack', 'slackToken', 'List channels, read and send messages, search.',
    'At api.slack.com/apps create an app, add the user token scopes channels:read, groups:read, channels:history, groups:history, chat:write and search:read, install it to your workspace, and paste the User OAuth Token (it starts with xoxp-).',
    ['💬', 'Slack', 'Use the Slack tools. If the text asks to send or post something, send it to the channel it names (find it with slack_list_channels) and say in one line where it went. Otherwise search or read Slack and answer briefly.', 'write']],
  ['notebook_text', 'Notion', 'notionToken', 'Search, read and create pages.',
    'Create an internal integration at notion.so/my-integrations, paste its token here, and share the pages you want with that integration.', null],
  ['git_branch', 'GitHub', 'githubToken', 'Your repos, search issues and pull requests, open new issues.',
    'At github.com/settings/personal-access-tokens create a fine-grained token with read and write access to Issues for the repos you want, and paste it here.',
    ['🐙', 'GitHub issue', 'Turn the text into a GitHub issue with a clear title and a short body. Create it with github_create_issue in the repo the user names (use github_my_repos to find it) and return the issue link.', 'write']],
  ['list_checks', 'Todoist', 'todoistToken', 'Add tasks with due dates and see what is due.',
    'In Todoist open Settings → Integrations → Developer and copy your API token.',
    ['✅', 'Add task', 'Add each task in the text to Todoist with todoist_add_task, with a due date in words if one is mentioned (like "tomorrow 5pm"). Reply with one line listing what was added.', 'write']],
  ['square_kanban', 'Linear', 'linearToken', 'Search issues and create new ones.',
    'In Linear open Settings → Security & access → Personal API keys, create a key and paste it here.',
    ['📐', 'Linear issue', 'Turn the text into a Linear issue with a clear title and description, create it with linear_create_issue, and return its identifier and link.', 'write']],
  ['message_square', 'Discord', 'discordWebhook', 'Post messages to a channel.',
    'In Discord open the channel settings → Integrations → Webhooks → New Webhook, then Copy Webhook URL and paste it here.',
    ['🎮', 'Discord', 'Write the message the user wants posted, send it with discord_send_message, and say in one line that it was posted.', 'write']],
  ['calendar', 'Calendly', 'calendlyToken', 'Drop your booking links into a message.',
    'In Calendly open Integrations → API & webhooks and create a personal access token.',
    ['🗓️', 'Booking link', 'Write a short friendly line offering a meeting and add the matching Calendly booking link from calendly_scheduling_links.', 'write']],
];

// One click puts a ready-made skill for the app on the keyboard.
function skillButton(template) {
  if (!template) return null;
  const [emoji, name, instruction, category] = template;
  const has = state.config.skills.some((s) => s.name.toLowerCase() === name.toLowerCase());
  return h('button', { class: 'btn ghost', disabled: has, onclick: () => saveSkills([...state.config.skills, { emoji, name, instruction, category }], null, `“${name}” added to your skills`) },
    has ? icon('check') : icon('plus'), has ? `${name} skill added` : `Add ${name} skill`);
}

const GOOGLE_SKILLS = [
  ['📅', 'Schedule', 'The text describes a meeting or event. Save it in Google Calendar with google_calendar_create, inviting anyone whose email is given and adding a Meet link if it is a call or online meeting. Reply with one line saying what was saved and when, with the Meet link if there is one.', 'write'],
  ['📤', 'Send email', 'Write the email the text asks for and send it with gmail_send if the recipient address is known; otherwise save it with gmail_create_draft. Reply with one line saying what was sent or saved.', 'write'],
  ['📥', 'Inbox', 'Answer the question about the user\'s email using gmail_search and gmail_read, in one to three short lines with sender names and dates.', 'write'],
];

// One Google sign-in covers Meet, Calendar and Gmail. The server's own Google client has to exist first (README).
function googleCard() {
  const c = state.config;
  const services = c.googleServices || [];
  const all = ['meet', 'calendar', 'gmail'].every((s) => services.includes(s));
  const status = !c.googleAvailable ? h('span', { class: 'pill off' }, 'Not set up')
    : all ? h('span', { class: 'pill ok' }, 'Connected') : c.googleConnected ? h('span', { class: 'pill' }, 'Partly') : h('span', { class: 'pill off' }, 'Not connected');
  const connect = (label) => h('button', { class: 'btn primary', onclick: () => { location.href = '/api/google/start'; } }, label);
  return connection('video', 'Google: Meet, Calendar, Gmail',
    'Sign in once. The keyboard then makes Meet links, saves events with invites, and sends or drafts email for you.', status,
    !c.googleAvailable ? h('p', { class: 'muted small' }, 'This server has no Google client yet. See “Google” in the dashboard README: a one-time setup by whoever runs this server.')
      : !c.googleConnected ? connect('Connect Google')
        : [
          !all && h('p', { class: 'muted small', style: 'width:100%' }, `On: ${services.join(', ') || 'Meet'}. Reconnect and tick every box to add the rest.`),
          !all && connect('Reconnect'),
          all && GOOGLE_SKILLS.map(skillButton),
          h('button', { class: 'btn ghost danger', onclick: async () => {
            try { state.config = await api('POST', '/api/google/disconnect'); toast('Google disconnected'); render(); } catch (e) { toast(e.message); }
          } }, 'Disconnect'),
        ]);
}

function connectionsTab() {
  const c = state.config;
  const set = c.appsSet || {};
  const pill = (on, yes = 'Connected', no = 'Not connected') => h('span', { class: 'pill' + (on ? ' ok' : ' off') }, on ? yes : no);
  return [
    h('div', { class: 'head' }, h('div', null, h('h1', null, 'Connections'),
      h('p', { class: 'muted sub' }, 'Apps your skills can use. Run a skill from the keyboard and it writes the text, then opens the app or does the job there.'))),
    h('h2', { class: 'section' }, 'Ready to use'),
    h('p', { class: 'muted small section-sub' }, 'Nothing to connect. The skill opens the app with your text filled in, and nothing is sent until you press send there.'),
    h('div', { class: 'grid' },
      LINK_APPS.map(([ic, title, about, template]) => connection(ic, title, about, pill(true, 'Ready'), skillButton(template)))),
    h('h2', { class: 'section' }, 'Connect your accounts'),
    h('p', { class: 'muted small section-sub' }, 'Paste a token once. Paired phones pick it up on the next sync, and skills can then read and act in the app for you.'),
    h('div', { class: 'grid' },
      googleCard(),
      TOKEN_APPS.map(([ic, title, field, about, help, template]) => connection(ic, title, about, pill(set[field]),
        h('button', { class: 'btn' + (set[field] ? '' : ' primary'), onclick: () => tokenDialog(title, help, field, set[field]) }, set[field] ? 'Manage' : 'Connect'),
        set[field] && skillButton(template))),
    ),
    h('h2', { class: 'section' }, 'Built in, and your own APIs'),
    h('div', { class: 'grid' },
      connection('globe', 'Web search', 'Lets skills look up fresh facts. Works with a Claude or OpenAI key, not through a gateway.', pill(c.webSearch, 'On', 'Off'),
        toggle(c.webSearch, (v) => save({ webSearch: v }), 'Web search')),
      connection('cloud_sun', 'Weather', 'Forecasts from Open-Meteo. No account needed.', pill(c.weather, 'On', 'Off'),
        toggle(c.weather, (v) => save({ weather: v }), 'Weather')),
      c.customApis.map((a, i) => connection('plug', a.name, a.description || a.url, h('span', { class: 'pill ok' }, 'API'),
        h('button', { class: 'btn', onclick: () => apiDialog(i) }, 'Edit'))),
      h('button', { class: 'card tile dashed', onclick: () => apiDialog(-1) }, icon('plus'), 'Connect an API')),
  ];
}

// ---------- Settings ----------

const MODELS = [
  ['claude-haiku-5-5', 'Haiku 5.5 — cheapest, fast'],
  ['claude-sonnet-5-5', 'Sonnet 5.5 — balanced'],
  ['claude-opus-5-5', 'Opus 5.5 — most capable'],
];
const LANGUAGES = ['English', 'Hindi', 'Spanish', 'French', 'Chinese (Simplified)', 'Arabic', 'Bengali', 'Portuguese', 'Russian', 'Japanese', 'German', 'Korean',
  'Italian', 'Turkish', 'Indonesian', 'Urdu', 'Marathi', 'Tamil', 'Telugu', 'Gujarati', 'Punjabi', 'Kannada', 'Malayalam', 'Vietnamese', 'Thai', 'Dutch', 'Polish', 'Swahili'];

/** One key field: says clearly whether a key is saved (and which, by its last four characters). */
function keyRow(label, field, isSet, hintText, placeholder) {
  const input = h('input', { type: 'password', placeholder: isSet ? 'Paste a new key to replace it' : placeholder, autocomplete: 'off', 'aria-label': label });
  return h('div', { style: 'margin-top:14px' },
    h('div', { class: 'row' }, h('div', { class: 'grow', style: 'font-weight:600;font-size:.86rem' }, label),
      h('span', { class: 'pill' + (isSet ? ' ok' : ' off') }, isSet ? (hintText ? `Saved · ends in ${hintText}` : 'Saved') : 'No key yet')),
    h('div', { class: 'row', style: 'margin-top:6px' }, h('div', { class: 'grow' }, input),
      h('button', { class: 'btn primary', onclick: () => (input.value.trim() ? save({ [field]: input.value }, 'Key saved') : toast('Paste a key first')) }, 'Save'),
      isSet && h('button', { class: 'btn ghost danger', onclick: () => confirm('Remove this key? Your keyboard will stop running actions until you add one.') && save({ [field]: '' }, 'Key removed') }, 'Remove')));
}

function modelCard() {
  const c = state.config;
  const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'AI service' },
    [['anthropic', 'Claude'], ['openai', 'OpenAI'], ['custom', 'Gateway']].map(([id, name]) =>
      h('button', { class: id === c.provider ? 'on' : '', onclick: () => save({ provider: id }) }, name)));
  let fields;
  if (c.provider === 'openai') {
    const model = h('input', { type: 'text', value: c.openaiModel, placeholder: 'gpt-5.5', 'aria-label': 'OpenAI model' });
    fields = [
      setting('Model', 'The model ID exactly as OpenAI lists it', h('div', { class: 'row' }, model, h('button', { class: 'btn', onclick: () => save({ openaiModel: model.value }) }, 'Save'))),
      keyRow('OpenAI API key', 'openaiKey', c.openaiKeySet, c.openaiKeyHint, 'sk-…')];
  } else if (c.provider === 'custom') {
    const base = h('input', { type: 'url', value: c.customBase, placeholder: 'https://api.airouter.in/v1', 'aria-label': 'Gateway address' });
    const model = h('input', { type: 'text', value: c.customModel, placeholder: 'anthropic/claude-haiku-5.5', 'aria-label': 'Gateway model' });
    fields = [
      h('label', { class: 'field' }, 'Gateway address', base), h('label', { class: 'field' }, 'Model name', model),
      h('div', { class: 'actions', style: 'margin-top:10px' }, h('button', { class: 'btn', onclick: () => save({ customBase: base.value, customModel: model.value }) }, 'Save address and model')),
      keyRow('Gateway API key', 'customKey', c.customKeySet, c.customKeyHint, 'sk-air-v1-…'),
      h('p', { class: 'muted small', style: 'margin-top:10px' }, 'For AI Router, OpenRouter and other OpenAI-compatible services. Your text and key go to that address. Gateways have no web search.')];
  } else {
    fields = [
      setting('Model', 'Haiku is the cheapest and is fine for a keyboard', select(c.model, MODELS, (v) => save({ model: v }), 'Claude model')),
      keyRow('Claude API key', 'apiKey', c.apiKeySet, c.apiKeyHint, 'sk-ant-…')];
  }
  return h('div', { class: 'card' }, h('h2', null, 'AI service'),
    h('p', { class: 'muted' }, 'Which service runs your keyboard\'s actions. Keys are stored encrypted here and sent only to your paired phones.'), seg, fields);
}

function keyboardCard() {
  const k = state.config.keyboard;
  const set = (name) => (value) => saveKeyboard({ [name]: value });
  return h('div', { class: 'card' }, h('h2', null, 'Keyboard'), h('p', { class: 'muted' }, 'Changes reach your phone the next time it syncs (within about ten minutes of typing, or tap Sync now in the app).'),
    setting('Assistant', 'Skill keys, the wheel and voice commands', toggle(k.assistantOn, set('assistantOn'), 'Assistant')),
    setting('When a skill finishes', null, select(k.agentMode, [['auto', 'Apply the result straight away'], ['ask', 'Show it first, then I decide'], ['manual', 'Only run skill keys I tap']], set('agentMode'), 'Agent mode')),
    setting('Look', null, select(k.theme, [['system', 'Follow the phone'], ['dark', 'Dark'], ['light', 'Light'], ['amoled', 'Amoled black'], ['ocean', 'Ocean blue'], ['forest', 'Forest green'], ['sunset', 'Sunset'], ['rose', 'Rose'], ['mint', 'Mint'], ['custom', 'Custom accent (set on the phone)']], set('theme'), 'Theme')),
    setting('Key style', null, select(k.keyShape, [['raised', 'Raised'], ['rounded', 'Rounded'], ['square', 'Square'], ['bubble', 'Bubble'], ['glass', 'Glass'], ['outline', 'Outline'], ['flat', 'Flat']], set('keyShape'), 'Key style')),
    setting('Key height', `${k.heightPercent}%`, h('input', { type: 'range', min: 80, max: 130, step: 5, value: k.heightPercent, 'aria-label': 'Key height', onchange: (e) => set('heightPercent')(Number(e.target.value)) })),
    setting('Number row', 'Show 1–0 above the letters', toggle(k.numberRow, set('numberRow'), 'Number row')),
    setting('Key sound', null, h('div', { class: 'row' },
      select(k.soundPack, [['system', 'Phone default'], ['typewriter', 'Typewriter'], ['mechanical', 'Mechanical'], ['pop', 'Pop'], ['drop', 'Water drop'], ['soft', 'Soft tap']], set('soundPack'), 'Sound effect'),
      toggle(k.sound, set('sound'), 'Key sound'))),
    setting('Vibration', null, h('div', { class: 'row' },
      select(k.hapticStrength, [['light', 'Light'], ['medium', 'Medium'], ['strong', 'Strong']], set('hapticStrength'), 'Vibration strength'),
      toggle(k.haptic, set('haptic'), 'Vibration'))),
    setting('Auto-correct', 'Fix typos as you type', toggle(k.autoCorrect, set('autoCorrect'), 'Auto-correct')),
    setting('Word suggestions', null, toggle(k.suggestions, set('suggestions'), 'Word suggestions')));
}

function wheelCard() {
  const { keyboard: k, skills } = state.config;
  const label = (id) => WHEEL_TOOLS[id] || id.replace('skill:', '');
  const wheel = k.wheel.filter((id) => WHEEL_TOOLS[id] || skills.some((s) => wheelId(s.name) === id));
  const free = [...Object.keys(WHEEL_TOOLS), ...skills.map((s) => wheelId(s.name))].filter((id) => !wheel.includes(id));
  return h('div', { class: 'card' }, h('h2', null, 'Wheel'),
    h('p', { class: 'muted' }, `Hold the space bar and slide to one of these. Left to right, up to ${MAX_WHEEL}.`),
    setting('Hold the space bar for the wheel', 'When off, holding it asks the assistant straight away', toggle(k.wheelOn, (v) => saveKeyboard({ wheelOn: v }), 'Wheel')),
    h('div', { class: 'chips', style: 'margin-top:12px' },
      wheel.map((id) => h('button', { class: 'chip on', title: 'Remove from the wheel', onclick: () => saveKeyboard({ wheel: wheel.filter((x) => x !== id) }, `${label(id)} removed`) }, label(id), icon('x'))),
      wheel.length === 0 && h('span', { class: 'muted' }, 'Nothing on the wheel yet.')),
    wheel.length < MAX_WHEEL && free.length > 0 && h('div', { style: 'margin-top:14px' },
      h('div', { class: 'muted small', style: 'margin-bottom:8px' }, 'Add to the wheel'),
      h('div', { class: 'chips' }, free.map((id) => h('button', { class: 'chip', onclick: () => saveKeyboard({ wheel: [...wheel, id] }, `${label(id)} added`) }, icon('plus'), label(id))))));
}

function languagesCard() {
  const mine = state.config.keyboard.languages;
  return h('div', { class: 'card' }, h('h2', null, 'Translate languages'), h('p', { class: 'muted' }, 'These are listed first when you translate, with one button for all of them at once.'),
    h('div', { class: 'chips' }, LANGUAGES.map((name) => h('button', { class: 'chip' + (mine.includes(name) ? ' on' : ''), 'aria-pressed': mine.includes(name),
      onclick: () => saveKeyboard({ languages: mine.includes(name) ? mine.filter((x) => x !== name) : [...mine, name] }) }, name))));
}

const when = (ms) => (ms ? new Date(ms).toLocaleString() : 'never');

function phonesCard() {
  return h('div', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', null, 'Phones'), h('p', { class: 'muted' }, 'Keyboards that sync with this account, both ways.')),
    h('button', { class: 'btn primary', onclick: async () => { try { state.code = await api('POST', '/api/devices/code'); render(); } catch (e) { toast(e.message); } } }, icon('smartphone'), 'Pair a phone')),
    state.code && h('div', null, h('p', { class: 'muted', style: 'margin-top:12px' }, `In the Verbo app open Settings → Web dashboard, enter this site's address and this code. It works once, for ${state.code.minutes} minutes.`),
      h('div', { class: 'code' }, state.code.code)),
    state.devices.map((d) => setting(d.name, `Paired ${when(d.created)} · last synced ${when(d.lastSeen)}`,
      h('button', { class: 'btn ghost danger', onclick: async () => {
        if (!confirm(`Unpair ${d.name}? It keeps its current settings but stops syncing.`)) return;
        try { await api('POST', '/api/devices/remove', { id: d.id }); await load(); } catch (e) { toast(e.message); }
      } }, 'Unpair'))),
    state.devices.length === 0 && !state.code && h('p', { class: 'muted', style: 'margin-top:12px' }, 'No phone paired yet.'));
}

// What the phone reports it spent this month, and the cap it obeys.
function usageCard() {
  const c = state.config;
  const u = c.usage;
  const now = new Date().toISOString().slice(0, 7);
  const current = u && u.month === now;
  const limit = h('input', { type: 'number', min: 0, max: 1000, step: '0.5', value: c.monthlyLimit || '', placeholder: 'No limit', style: 'width:120px' });
  const top = current ? Object.entries(u.skills || {}).sort((a, b) => b[1].cost - a[1].cost).slice(0, 5) : [];
  return h('div', { class: 'card' },
    h('h2', null, 'Usage this month'),
    current ? [
      h('p', { class: 'muted' }, `About $${u.cost.toFixed(3)} for ${u.requests} requests · ${u.inTokens.toLocaleString()} tokens in, ${u.outTokens.toLocaleString()} out${u.searches ? ` · ${u.searches} web searches` : ''}. Estimated from your phone's token counts at list prices.`),
      top.length > 0 && h('div', null, top.map(([name, v]) => h('div', { class: 'setting' }, h('div', { class: 'what' }, h('div', null, name), h('p', { class: 'muted small' }, `${v.n} times`)), h('span', null, '$' + v.cost.toFixed(3))))),
    ] : h('p', { class: 'muted' }, 'Nothing reported yet this month. The phone sends its numbers when it syncs.'),
    setting('Monthly limit', 'AI actions stop on the phone when the month reaches this many dollars. Empty or 0 means no limit.',
      h('div', { class: 'row' }, limit, h('button', { class: 'btn', onclick: () => save({ monthlyLimit: Number(limit.value || 0) }, 'Limit saved') }, 'Save'))));
}

function settingsTab() {
  return [
    h('div', { class: 'head' }, h('div', null, h('h1', null, 'Settings'), h('p', { class: 'muted sub' }, 'Everything here syncs with your phone.'))),
    h('div', { class: 'stack' }, modelCard(), usageCard(), phonesCard(), keyboardCard(), wheelCard(), languagesCard(),
      h('div', { class: 'card row' }, h('div', { class: 'grow' }, h('h2', null, 'Account'), h('p', { class: 'muted' }, state.email)),
        h('button', { class: 'btn', onclick: async () => {
          await api('POST', '/api/logout').catch(() => {});
          Object.assign(state, { email: null, config: null, code: null, chat: [] });
          render();
        } }, icon('log_out'), 'Sign out'))),
  ];
}

// ---------- shell ----------

const TABS = [
  ['skills', 'My skills', 'layout_grid', skillsTab],
  ['assistant', 'Assistant', 'message_circle', assistantTab],
  ['connections', 'Connections', 'plug', connectionsTab],
  ['settings', 'Settings', 'settings', settingsTab],
];

async function go(tab) {
  state.tab = tab;
  if (tab === 'settings') state.devices = await api('GET', '/api/devices').catch(() => state.devices);
  render();
  window.scrollTo(0, 0);
}

function render() {
  if (!state.email) return app.replaceChildren(authView());
  const current = TABS.find((t) => t[0] === state.tab) || TABS[0];
  app.replaceChildren(h('div', { class: 'shell' },
    h('aside', { class: 'side' },
      h('div', { class: 'brand' }, h('span', null, 'V'), 'Verbo'),
      h('nav', { 'aria-label': 'Sections' }, TABS.map(([id, name, ic]) =>
        h('button', { 'aria-current': id === current[0] ? 'page' : null, onclick: () => go(id) }, icon(ic), name)))),
    h('main', null, current[3]())));
}

async function load() {
  [state.config, state.devices] = await Promise.all([api('GET', '/api/config'), api('GET', '/api/devices')]);
  render();
}

(async () => {
  try {
    // Back from Google's sign-in page: say how it went, open the right tab, and tidy the address bar.
    const google = new URLSearchParams(location.search).get('google');
    if (google) {
      state.tab = 'connections';
      history.replaceState(null, '', '/');
      setTimeout(() => toast(google === 'connected' ? 'Google connected' : 'Google sign-in did not complete'), 300);
    }
    const me = await api('GET', '/api/me');
    state.allowSignup = me.allowSignup;
    state.email = me.email;
    if (me.email) await load(); else render();
  } catch (e) {
    app.replaceChildren(h('div', { class: 'auth' }, h('div', { class: 'card' }, 'Could not reach the server. ' + e.message)));
  }
})();
