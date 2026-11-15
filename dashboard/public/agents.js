'use strict';
// The visual agent builder. An agent is a trigger plus blocks linked on a canvas. On save it also becomes a
// chain skill ("Chain: step | step") and, for copied-text triggers, an auto action, which the phone already runs.

// The phone's ready-made acts (Acts.BUILTIN and Trigger.BUILTIN in Cards.kt), so a test run on the web uses the same words.
const BUILTIN_ACTS = {
  'Reply options': ['💬', 'Write three different versions of the reply or message the user wants, using their text and the copied message if there is one: one warm, one brief, one formal. Return exactly three lines starting with 1), 2) and 3), and nothing else.'],
  'Schedule it': ['📅', "The text or the copied message describes an event (a meeting, a birthday, a reminder). Work out its title, date, time and place, using today's date for words like tomorrow. Reply with a short friendly message the user can send to invite someone, with the event details, then a final [[open:https://calendar.google.com/calendar/render?action=TEMPLATE&text=TITLE&dates=DATES&details=DETAILS&location=PLACE]] line."],
  'Understand any language': ['🌐', 'The copied message, or the text, may be in any language. Reply with: on the first line From LANGUAGE; then what it says, translated naturally into English (into Hindi if the user\'s own text is in Hindi); then a last line starting Tone: with one short phrase on the tone and what the sender wants.'],
  'Save to Notion': ['📝', 'Save the copied message, or the text, to the user\'s Notion as a new page with a short title and the full text. Reply with the new page title.'],
  'Find anything': ['🔎', 'Search everything the user has connected (Gmail, Notion, Calendar, Slack, GitHub, Linear, Todoist) for what the text asks about, and the web only if nothing matches. Return a short title line, then up to five numbered results, most relevant first.'],
  'Extract what matters': ['🎯', 'From the copied message or the text, pull out only what matters: decisions, dates and times, amounts, names and things the user must do. One short item per line, each starting with a dash. Nothing else.'],
  'Explain simply': ['🧠', 'Explain the copied message, or the text, in plain words a 14-year-old would understand, in two to four short sentences. Keep names and numbers.'],
  'Fact check': ['✅', 'Check the claims in the copied message, or the text, searching the web where needed. First line: True, False, Misleading or Unclear. Then one or two short lines with what is actually true and the source name.'],
  'Sound like a native': ['🗣️', 'Rewrite the text so it sounds like a native speaker of its language wrote it: natural, idiomatic, same meaning and tone, similar length.'],
  'Who is': ['👤', 'The text or the context names a person. Look them up on the web and reply with one item per line: their full name; a one-line headline with their role and organisation; then Current:, Previous:, Education:, Location:; and last a LinkedIn people search link. Leave out any line you cannot verify.'],
  'Save event': ['🗓️', 'The text describes an event or a meeting. Work out its title, date, start time and place. Reply with one line describing it, then a Google Calendar template link.'],
  'Place': ['📍', 'The text names a place. Find it with the place search and return its name, address and a Google Maps link.'],
};

const TRIGGER_BLOCKS = [
  ['manual', '⌨️', 'Run from the keyboard', 'Hold its key, or pick it in Acts or on the wheel'],
  ['person', '👤', 'Copied text has a person', 'A name in a copied message'],
  ['time', '🕒', 'Copied text has a date or time', '“Lunch Friday 1pm”'],
  ['place', '📍', 'Copied text has a place', 'An address or a venue'],
  ['link', '🔗', 'Copied text has a link', 'Any web address'],
  ['email', '✉️', 'Copied text has an email', 'name@company.com'],
  ['phone', '📞', 'Copied text has a phone number', '+91 98…'],
  ['foreign', '🌐', 'Copied text is another language', 'Not English or Hindi'],
  ['keyword', '🔑', 'Copied text has words I choose', 'invoice, urgent…'],
  ['any', '📋', 'Anything is copied', 'Every copied message'],
];

// Tools and actions run as plain-instruction steps. {x} is replaced with the block's own text (param).
const TOOL_BLOCKS = [
  ['web', '🔍', 'Search the web', 'Search the web for what the text asks about and keep the key facts, with the source names.'],
  ['place', '🗺️', 'Find a place', 'Find the place the text names with the place search: name, address, rating and a Google Maps link.'],
  ['weather', '⛅', 'Check the weather', 'Get the weather forecast for the place and day in the text and add it in one short line.'],
  ['calendar', '📆', 'Check my calendar', 'Look at the user\'s upcoming calendar events with google_calendar_events and note anything that clashes or relates to the text.'],
  ['gmail', '📥', 'Search my Gmail', 'Search the user\'s Gmail with gmail_search for emails about the text and keep who sent them, when and what they say.'],
  ['notion', '🗒️', 'Look in Notion', 'Search the user\'s Notion with notion_search for pages about the text and keep the best match with its link.'],
  ['slackread', '#️⃣', 'Search Slack', 'Search Slack with slack_search for messages about the text and keep the most relevant ones.'],
];
const ACTION_BLOCKS = [
  ['insert', '⌨️', 'Put it in the message box', '', ''],
  ['whatsapp', '🟢', 'Open in WhatsApp', 'Open WhatsApp with the final message: end with [[open:https://wa.me/{x}?text=...]] with the message URL-encoded.', 'Number with country code (optional)'],
  ['email', '📧', 'Email draft', 'Turn the result into an email to {x} with a short subject; send it with gmail_send if Google is connected and the address is known, otherwise end with a [[open:mailto:...]] draft link.', 'To (email, optional)'],
  ['calendar', '📅', 'Save to calendar', 'Save the event from the result: with google_calendar_create if Google is connected, otherwise end with a Google Calendar template link.', ''],
  ['notion', '📝', 'Save to Notion', 'Save the result to Notion as a new page under {x} with notion_create_page and give its link.', 'Parent page (optional)'],
  ['slack', '💬', 'Post to Slack', 'Send the result to the Slack channel {x} with slack_send_message and say in one line where it went.', 'Channel, e.g. #team'],
  ['todoist', '✅', 'Add Todoist task', 'Add each to-do in the result to Todoist with todoist_add_task, with a due date if one is mentioned.', ''],
  ['github', '🐙', 'Open GitHub issue', 'Create a GitHub issue in {x} from the result with github_create_issue and give its link.', 'Repo, e.g. me/app'],
  ['linear', '📐', 'Create Linear issue', 'Create a Linear issue from the result with linear_create_issue and give its identifier.', ''],
  ['discord', '🎮', 'Post to Discord', 'Post the result to Discord with discord_send_message.', ''],
  ['linkedin', '💼', 'Post on LinkedIn', 'Turn the result into a LinkedIn post and end with [[open:https://www.linkedin.com/feed/?shareActive=true&text=...]].', ''],
  ['x', '𝕏', 'Post on X', 'Turn the result into a post under 280 characters and end with [[open:https://x.com/intent/post?text=...]].', ''],
];

const newId = () => 'n' + Math.random().toString(36).slice(2, 9);
const triggerInfo = (ref) => TRIGGER_BLOCKS.find((t) => t[0] === ref) || TRIGGER_BLOCKS[0];

/** What a block shows and how it runs. step: the chain step the phone reads; test: the instruction for a web run. */
function blockInfo(node) {
  const fill = (template) => template.replace(/\{x\}/g, node.text.trim() || (template.includes('wa.me/{x}') ? '' : 'the one the user names')).replace(/\|/g, '/');
  if (node.kind === 'trigger') {
    const [, emoji, label, about] = triggerInfo(node.ref);
    return { emoji, label, about: node.ref === 'keyword' ? (node.text ? `Words: ${node.text}` : 'Choose the words') : node.ref === 'manual' ? about : (node.auto ? 'Runs by itself' : 'Offered as a ⚡ chip'), kind: 'TRIGGER' };
  }
  const [type, ref] = [node.ref.split(':')[0], node.ref.slice(node.ref.indexOf(':') + 1)];
  if (node.kind === 'action') {
    const a = ACTION_BLOCKS.find((x) => x[0] === ref) || ACTION_BLOCKS[0];
    return { emoji: a[1], label: a[2], about: a[4] ? (node.text || a[4]) : 'Final step', kind: 'ACTION', param: a[4], step: a[3] ? fill(a[3]) : '', test: a[3] ? fill(a[3]) : '' };
  }
  if (type === 'skill') {
    const s = state.config.skills.find((x) => x.name === ref);
    return { emoji: s?.emoji || '✦', label: ref, about: s ? s.instruction.slice(0, 70) : 'This skill was deleted', kind: 'SKILL', step: ref, test: s ? s.instruction : '', missing: !s };
  }
  if (type === 'builtin') {
    const b = BUILTIN_ACTS[ref] || ['⚡', ''];
    return { emoji: b[0], label: ref, about: 'Ready-made act', kind: 'ACT', step: ref, test: b[1] };
  }
  if (type === 'tool') {
    const t = TOOL_BLOCKS.find((x) => x[0] === ref) || TOOL_BLOCKS[0];
    return { emoji: t[1], label: t[2], about: 'Tool', kind: 'TOOL', step: t[3], test: t[3] };
  }
  const text = node.text.trim().replace(/\|/g, '/');
  return { emoji: '✨', label: 'AI step', about: text ? text.slice(0, 70) : 'Write what it should do', kind: 'AI STEP', step: text, test: text, custom: true };
}

/** Blocks in the order they run: from the trigger along the links (upper branch first). */
function agentOrder(agent) {
  const byId = new Map(agent.nodes.map((n) => [n.id, n]));
  const trigger = agent.nodes.find((n) => n.kind === 'trigger');
  const queue = trigger ? [trigger] : agent.nodes.filter((n) => !agent.edges.some((e) => e.to === n.id)).sort((a, b) => a.x - b.x);
  const seen = new Set();
  const out = [];
  while (queue.length) {
    const n = queue.shift();
    if (!n || seen.has(n.id)) continue;
    seen.add(n.id);
    out.push(n);
    queue.push(...agent.edges.filter((e) => e.from === n.id).map((e) => byId.get(e.to)).filter(Boolean).sort((a, b) => a.y - b.y));
  }
  return out;
}

function compileAgent(agent) {
  const order = agentOrder(agent);
  const runs = order.filter((n) => n.kind !== 'trigger').map((n) => [n, blockInfo(n)]).filter(([, i]) => i.step);
  return {
    order, trigger: agent.nodes.find((n) => n.kind === 'trigger') || null,
    steps: runs.map(([, i]) => i.step),
    tests: runs.map(([, i]) => ({ name: i.label, instruction: i.test })),
    unlinked: agent.nodes.length - order.length,
  };
}

/** The steps for a web run of a skill: a chain is expanded, step by step, like the phone does. */
function runSteps(skill) {
  if (!isChain(skill)) return [{ name: skill.name, instruction: skill.instruction }];
  return chainSteps(skill).map((step) => {
    const own = state.config.skills.find((s) => s.name.toLowerCase() === step.toLowerCase() && !isChain(s));
    const builtin = Object.entries(BUILTIN_ACTS).find(([n]) => n.toLowerCase() === step.toLowerCase());
    return { name: own?.name || builtin?.[0] || 'AI step', instruction: own?.instruction || builtin?.[1][1] || step };
  });
}

const TEMPLATES = [
  { name: 'Lead helper', emoji: '🤝', description: 'Copy a name: look them up and draft a connection note.', chain: [['trigger', 'person'], ['step', 'builtin:Who is'], ['step', 'custom', 'Write a short, friendly LinkedIn connection note to this person, under 300 characters, mentioning their current role.'], ['action', 'do:insert']] },
  { name: 'Meeting maker', emoji: '📅', description: 'Copy a time: save the event and send an invite on WhatsApp.', chain: [['trigger', 'time'], ['step', 'builtin:Schedule it'], ['action', 'do:calendar'], ['action', 'do:whatsapp']] },
  { name: 'Translate & reply', emoji: '🌐', description: 'A message in another language: understand it, then get three replies.', chain: [['trigger', 'foreign'], ['step', 'builtin:Understand any language'], ['step', 'builtin:Reply options']] },
  { name: 'Notes to Notion', emoji: '🗒️', description: 'Pull out what matters from a message and save it to Notion.', chain: [['trigger', 'manual'], ['step', 'builtin:Extract what matters'], ['action', 'do:notion']] },
];

function fromTemplate(t) {
  const nodes = t.chain.map(([kind, ref, text], i) => ({ id: newId(), kind, ref, text: text || '', auto: false, x: 40 + i * 250, y: 80 + (i % 2) * 70 }));
  return { id: 'a' + Math.random().toString(36).slice(2, 10), name: t.name, emoji: t.emoji, description: t.description, active: true, nodes, edges: nodes.slice(1).map((n, i) => ({ from: nodes[i].id, to: n.id })), _key: '' };
}

function blankAgent() {
  const n = state.config.agents.length + 1;
  return { id: 'a' + Math.random().toString(36).slice(2, 10), name: n > 1 ? `My agent ${n}` : 'My agent', emoji: '🤖', description: '', active: true, _key: '',
    nodes: [{ id: newId(), kind: 'trigger', ref: 'manual', text: '', auto: false, x: 60, y: 140 }], edges: [] };
}

function openAgent(agent) {
  const copy = JSON.parse(JSON.stringify(agent));
  copy._key = state.config.skills.find((s) => s.name === agent.name)?.key || copy._key || '';
  copy._original = state.config.agents.some((a) => a.id === agent.id) ? agent.name : null;
  Object.assign(state, { editing: copy, selected: null, testLogs: null, tab: 'agents' });
  render();
  window.scrollTo(0, 0);
}

// ---------- saving ----------

async function saveAgent() {
  const a = state.editing;
  a.name = a.name.trim();
  if (!a.name) return toast('Give the agent a name');
  const c = compileAgent(a);
  if (!c.trigger) return toast('Add a trigger: drag one from the left');
  if (c.steps.length === 0) return toast('Link at least one step after the trigger');
  if (c.trigger.ref === 'keyword' && !c.trigger.text.trim()) return toast('Type the words the trigger looks for');
  const instruction = 'Chain: ' + c.steps.join(' | ');
  if (instruction.length > 2000) return toast('This agent is too long for the keyboard. Shorten the AI steps');
  const old = a._original;
  const { skills, triggers, keyboard, agents } = state.config;
  const clash = skills.find((s) => s.name.toLowerCase() === a.name.toLowerCase() && s.name !== old);
  if (clash && !agents.some((x) => x.name === clash.name && x.id === a.id)) return toast('A skill already has that name. Pick another name for the agent');
  const mine = (name) => name === old || name === a.name;
  let nextSkills = skills.filter((s) => !mine(s.name)).map((s) => (a.active && a._key && s.key === a._key ? { ...s, key: '' } : s));
  if (a.active) nextSkills = [...nextSkills, { emoji: a.emoji || '🤖', name: a.name, instruction, category: 'write', key: a._key }];
  let nextTriggers = triggers.filter((t) => !mine(t.skill));
  if (a.active && c.trigger.ref !== 'manual') nextTriggers = [...nextTriggers, { kind: c.trigger.ref, keyword: c.trigger.text.trim(), skill: a.name, auto: c.trigger.auto }];
  if (nextTriggers.length > 20) return toast('You have 20 auto actions already. Remove one first');
  const wheel = keyboard.wheel.map((id) => (old && id === wheelId(old) ? wheelId(a.name) : id)).filter((id) => a.active || id !== wheelId(a.name));
  const { _key, _original, ...clean } = a;
  const nextAgents = agents.some((x) => x.id === a.id) ? agents.map((x) => (x.id === a.id ? clean : x)) : [...agents, clean];
  if (await save({ agents: nextAgents, skills: nextSkills.map(cleanSkill), triggers: nextTriggers, keyboard: { wheel } }, a.active ? `${a.name} saved. Your phone gets it on the next sync` : `${a.name} saved (paused)`)) {
    a._original = a.name;
    render();
  }
}

async function deleteAgent(agent) {
  if (!confirm(`Delete ${agent.name}? Its skill and auto action are removed from the keyboard too.`)) return;
  const name = state.config.agents.find((x) => x.id === agent.id)?.name ?? agent.name;
  const ok = await save({
    agents: state.config.agents.filter((x) => x.id !== agent.id),
    skills: state.config.skills.filter((s) => s.name !== name).map(cleanSkill),
    triggers: state.config.triggers.filter((t) => t.skill !== name),
    keyboard: { wheel: state.config.keyboard.wheel.filter((id) => id !== wheelId(name)) },
  }, 'Agent deleted');
  if (ok) { state.editing = null; render(); }
}

async function runAgentTest() {
  const c = compileAgent(state.editing);
  if (!state.testText.trim()) return toast('Type or paste a message to test with');
  if (c.tests.length === 0) return toast('Link at least one step after the trigger');
  state.testing = true;
  state.testLogs = { logs: c.tests.map((t) => ({ name: t.name, pending: true })) };
  rerender();
  try { state.testLogs = await api('POST', '/api/run', { text: state.testText, steps: c.tests }); } catch (e) { state.testLogs = { logs: [{ name: 'Run', error: e.message }] }; }
  state.testing = false;
  rerender();
}

// ---------- the editor ----------

let canvasScroll = { left: 0, top: 0 };
/** Redraws the page but keeps the canvas where the user had scrolled it. */
function rerender() {
  const wrap = app.querySelector('.canvaswrap');
  if (wrap) canvasScroll = { left: wrap.scrollLeft, top: wrap.scrollTop };
  render();
  const again = app.querySelector('.canvaswrap');
  if (again) { again.scrollLeft = canvasScroll.left; again.scrollTop = canvasScroll.top; }
}

function addBlock(kind, ref, at) {
  const a = state.editing;
  if (kind === 'trigger') {
    const t = a.nodes.find((n) => n.kind === 'trigger');
    if (t) { t.ref = ref; state.selected = t.id; return rerender(); }
  }
  const order = agentOrder(a);
  const last = order[order.length - 1];
  const node = { id: newId(), kind, ref, text: '', auto: false, x: at ? at.x : (last ? last.x + 270 : 60), y: at ? at.y : (last ? last.y : 140) };
  a.nodes.push(node);
  if (a.nodes.length > 24) { a.nodes.pop(); return toast('An agent has up to 24 blocks'); }
  // Clicked (not dropped): link it after the last block so a chain can be built with taps alone.
  if (!at && last && kind !== 'trigger') a.edges.push({ from: last.id, to: node.id });
  if (kind === 'trigger') a.nodes.filter((n) => n !== node).forEach((n) => { if (!a.edges.some((e) => e.to === n.id)) { a.edges.push({ from: node.id, to: n.id }); } });
  state.selected = node.id;
  rerender();
}

function paletteItem(kind, ref, emoji, label) {
  return h('button', { class: 'pitem', draggable: true, title: 'Drag onto the canvas, or click to add after the last block',
    ondragstart: (e) => { e.dataTransfer.setData('text/plain', JSON.stringify({ kind, ref })); e.dataTransfer.effectAllowed = 'copy'; },
    onclick: () => addBlock(kind, ref) }, h('span', null, emoji), label);
}

function paletteView() {
  const skills = state.config.skills.filter((s) => !isChain(s) && !state.config.agents.some((a) => a.name === s.name));
  return h('div', { class: 'card palette' },
    h('h4', null, 'TRIGGER'), TRIGGER_BLOCKS.map(([ref, emoji, label]) => paletteItem('trigger', ref, emoji, label)),
    h('h4', null, 'AI STEPS'), paletteItem('step', 'custom', '✨', 'AI step (write your own)'),
    Object.entries(BUILTIN_ACTS).filter(([n]) => !['Save event', 'Place'].includes(n)).map(([n, [emoji]]) => paletteItem('step', 'builtin:' + n, emoji, n)),
    h('h4', null, 'MY SKILLS'), skills.map((s) => paletteItem('step', 'skill:' + s.name, s.emoji || '✦', s.name)),
    h('h4', null, 'TOOLS'), TOOL_BLOCKS.map(([ref, emoji, label]) => paletteItem('step', 'tool:' + ref, emoji, label)),
    h('h4', null, 'ACTIONS'), ACTION_BLOCKS.map(([ref, emoji, label]) => paletteItem('action', 'do:' + ref, emoji, label)));
}

const NODE_W = 210;
const PORT_Y = 28;

function edgePath(a, b) {
  const x1 = a.x + NODE_W, y1 = a.y + PORT_Y, x2 = b.x, y2 = b.y + PORT_Y;
  const bend = Math.max(50, Math.abs(x2 - x1) / 2);
  return `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
}

function svgEl(tag, attrs) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  return el;
}

function drawEdges(svg) {
  const a = state.editing;
  const byId = new Map(a.nodes.map((n) => [n.id, n]));
  svg.replaceChildren();
  for (const e of a.edges) {
    const from = byId.get(e.from), to = byId.get(e.to);
    if (!from || !to) continue;
    const d = edgePath(from, to);
    const hit = svgEl('path', { d, class: 'hit' });
    hit.append(svgEl('title', {}));
    hit.firstChild.textContent = 'Click to remove this link';
    hit.addEventListener('click', () => { a.edges = a.edges.filter((x) => x !== e); rerender(); });
    svg.append(hit, svgEl('path', { d, class: 'line' }));
  }
}

function nodeView(node, number, svg, canvas) {
  const info = blockInfo(node);
  const el = h('div', { class: `node ${node.kind}${state.selected === node.id ? ' sel' : ''}${number ? '' : ' off'}`, style: `left:${node.x}px;top:${node.y}px`,
    'data-id': node.id, onclick: (e) => { if (!e.target.classList.contains('port') && state.selected !== node.id) { state.selected = node.id; rerender(); } } },
  number ? h('span', { class: 'ord', title: 'Runs in this order' }, String(number)) : null,
  h('div', { class: 'nh' }, h('div', { class: 'emoji', style: 'width:32px;height:32px;font-size:1rem' }, info.emoji),
    h('div', { style: 'min-width:0' }, h('div', { class: 'kind' }, info.kind), h('div', { class: 'nt' }, info.label))),
  h('div', { class: 'nb clamp', style: info.missing ? 'color:var(--danger)' : null }, info.about),
  node.kind !== 'trigger' && h('span', { class: 'port in', 'data-in': node.id, title: 'Link into this block' }),
  h('span', { class: 'port out', title: 'Drag to the next block' }));

  // Move the block by its header.
  const head = el.querySelector('.nh');
  head.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    head.setPointerCapture(e.pointerId);
    const start = { x: e.clientX, y: e.clientY, nx: node.x, ny: node.y };
    let moved = false;
    const move = (ev) => {
      node.x = Math.max(0, Math.round(start.nx + ev.clientX - start.x));
      node.y = Math.max(0, Math.round(start.ny + ev.clientY - start.y));
      moved = moved || Math.abs(ev.clientX - start.x) + Math.abs(ev.clientY - start.y) > 3;
      el.style.left = node.x + 'px';
      el.style.top = node.y + 'px';
      drawEdges(svg);
    };
    const up = () => { head.removeEventListener('pointermove', move); head.removeEventListener('pointerup', up); if (moved) rerender(); };
    head.addEventListener('pointermove', move);
    head.addEventListener('pointerup', up);
  });

  // Drag from the right-hand port to another block's left-hand port to link them.
  const out = el.querySelector('.port.out');
  out.addEventListener('pointerdown', (e) => {
    e.stopPropagation();
    out.setPointerCapture(e.pointerId);
    const temp = svgEl('path', { class: 'line temp' });
    svg.append(temp);
    const move = (ev) => {
      const r = canvas.getBoundingClientRect();
      const x1 = node.x + NODE_W, y1 = node.y + PORT_Y, x2 = ev.clientX - r.left, y2 = ev.clientY - r.top;
      temp.setAttribute('d', `M ${x1} ${y1} C ${x1 + 60} ${y1}, ${x2 - 60} ${y2}, ${x2} ${y2}`);
    };
    const up = (ev) => {
      out.removeEventListener('pointermove', move);
      out.removeEventListener('pointerup', up);
      temp.remove();
      const target = document.elementFromPoint(ev.clientX, ev.clientY)?.closest('.node');
      const to = target?.dataset.id;
      const a = state.editing;
      if (to && to !== node.id && a.nodes.find((n) => n.id === to)?.kind !== 'trigger' && !a.edges.some((x) => x.from === node.id && x.to === to)) {
        a.edges = a.edges.filter((x) => x.to !== to); // one link into each block, so the order stays clear
        a.edges.push({ from: node.id, to });
        rerender();
      }
    };
    out.addEventListener('pointermove', move);
    out.addEventListener('pointerup', up);
  });
  return el;
}

function canvasView(c) {
  const svg = svgEl('svg', { class: 'edges' });
  const canvas = h('div', { class: 'canvas' }, svg);
  const numbers = new Map(c.order.map((n, i) => [n.id, i + 1]));
  state.editing.nodes.forEach((n) => canvas.append(nodeView(n, numbers.get(n.id), svg, canvas)));
  drawEdges(svg);
  const wrap = h('div', { class: 'canvaswrap',
    onclick: (e) => { if (e.target === canvas || e.target === wrap || e.target === svg) { if (state.selected) { state.selected = null; rerender(); } } },
    ondragover: (e) => { e.preventDefault(); wrap.classList.add('over'); },
    ondragleave: () => wrap.classList.remove('over'),
    ondrop: (e) => {
      e.preventDefault();
      wrap.classList.remove('over');
      let block;
      try { block = JSON.parse(e.dataTransfer.getData('text/plain')); } catch { return; }
      const r = canvas.getBoundingClientRect();
      addBlock(block.kind, block.ref, { x: Math.max(0, Math.round(e.clientX - r.left - NODE_W / 2)), y: Math.max(0, Math.round(e.clientY - r.top - 24)) });
    } }, canvas);
  if (state.editing.nodes.length <= 1) {
    canvas.append(h('div', { class: 'muted', style: 'position:absolute;left:320px;top:150px;max-width:340px' },
      'Drag blocks here from the left, or click them to add in order. Link blocks by dragging from a right-hand dot to the next block.'));
  }
  return wrap;
}

function propsView(c) {
  const a = state.editing;
  const node = a.nodes.find((n) => n.id === state.selected);
  if (node) {
    const info = blockInfo(node);
    const remove = () => { a.nodes = a.nodes.filter((n) => n !== node); a.edges = a.edges.filter((e) => e.from !== node.id && e.to !== node.id); state.selected = null; rerender(); };
    return h('div', { class: 'card props' },
      h('div', { class: 'row' }, h('h3', { class: 'grow' }, 'Block'), h('span', { class: 'id' }, info.kind), h('button', { class: 'iconbtn', style: 'width:32px;height:32px', 'aria-label': 'Close', onclick: () => { state.selected = null; rerender(); } }, icon('x'))),
      h('div', { class: 'lbl' }, 'NAME'), h('div', { class: 'row' }, h('div', { class: 'emoji' }, info.emoji), h('b', null, info.label)),
      node.kind === 'trigger' && [
        h('div', { class: 'lbl' }, 'WHEN'), select(node.ref, TRIGGER_BLOCKS.map((t) => [t[0], t[2]]), (v) => { node.ref = v; rerender(); }, 'Trigger'),
        node.ref === 'keyword' && [h('div', { class: 'lbl' }, 'WORDS, SEPARATED BY COMMAS'), h('input', { type: 'text', value: node.text, placeholder: 'invoice, urgent', oninput: (e) => { node.text = e.target.value; } })],
        node.ref !== 'manual' && h('label', { class: 'row', style: 'margin-top:12px' }, h('input', { type: 'checkbox', checked: node.auto, onchange: (e) => { node.auto = e.target.checked; rerender(); } }), 'Run by itself, without asking'),
        h('p', { class: 'muted small', style: 'margin-top:10px' }, node.ref === 'manual' ? 'Runs when you hold its key, or pick it in the Acts panel or on the wheel. Set the key under Agent.' : 'Copy a message on your phone: when it matches, the agent is offered as a ⚡ chip (or runs by itself).')],
      info.custom && [h('div', { class: 'lbl' }, 'WHAT SHOULD THIS STEP DO?'),
        h('textarea', { placeholder: 'Write a polite reply that accepts and suggests Friday', oninput: (e) => { node.text = e.target.value; }, onchange: () => rerender() }, node.text)],
      info.param && [h('div', { class: 'lbl' }, info.param.toUpperCase()), h('input', { type: 'text', value: node.text, placeholder: info.param, oninput: (e) => { node.text = e.target.value; }, onchange: () => rerender() })],
      !info.custom && node.kind !== 'trigger' && info.test && [h('div', { class: 'lbl' }, 'WHAT IT DOES'), h('p', { class: 'muted small' }, info.test)],
      info.missing && h('p', { class: 'small', style: 'color:var(--danger);margin-top:10px' }, 'This skill no longer exists. Remove the block or pick another.'),
      h('div', { class: 'actions', style: 'margin-top:18px' }, h('button', { class: 'btn ghost danger', onclick: remove }, icon('trash_2'), 'Remove block')));
  }
  const free = 'abcdefghijklmnopqrstuvwxyz'.split('');
  const owner = (l) => state.config.skills.find((s) => s.key === l && s.name !== a._original);
  return h('div', { class: 'card props' },
    h('div', { class: 'row' }, h('h3', { class: 'grow' }, 'Agent'), h('span', { class: 'id' }, 'ID: ' + a.id.slice(0, 8))),
    h('div', { class: 'lbl' }, 'NAME'),
    h('div', { class: 'row' }, h('input', { type: 'text', value: a.emoji, maxLength: 16, style: 'width:56px;text-align:center', 'aria-label': 'Emoji', oninput: (e) => { a.emoji = e.target.value; } }),
      h('input', { type: 'text', value: a.name, maxLength: 40, 'aria-label': 'Agent name', oninput: (e) => { a.name = e.target.value; }, onchange: () => rerender() })),
    h('div', { class: 'lbl' }, 'DESCRIPTION'),
    h('textarea', { rows: 2, style: 'min-height:60px', maxLength: 300, placeholder: 'What this agent is for', oninput: (e) => { a.description = e.target.value; } }, a.description),
    h('div', { class: 'lbl' }, 'HOLD KEY'),
    select(a._key, [['', 'No key'], ...free.map((l) => [l, `Hold ${l.toUpperCase()}${owner(l) ? ` (now ${owner(l).name})` : ''}`])], (v) => { a._key = v; }, 'Hold key'),
    h('div', { class: 'lbl' }, 'RUNS IN THIS ORDER'),
    c.order.length ? h('div', { class: 'order' }, c.order.map((n, i) => { const info = blockInfo(n); return h('div', null, h('b', null, String(i + 1)), info.emoji, ' ', info.label); }))
      : h('p', { class: 'muted small' }, 'Nothing linked yet.'),
    c.unlinked > 0 && h('p', { class: 'small', style: 'color:var(--warn);margin-top:10px' }, `${c.unlinked} block${c.unlinked === 1 ? ' is' : 's are'} not linked to the trigger and will not run.`),
    h('p', { class: 'muted small', style: 'margin-top:14px' }, 'Click a block to change it. Click a link to remove it.'));
}

function testView() {
  const t = state.testLogs;
  const input = h('textarea', { rows: 5, placeholder: 'Paste a message to try the agent on, e.g. “Can we meet Friday 3pm at Blue Tokai, Saket?”', oninput: (e) => { state.testText = e.target.value; } });
  input.value = state.testText;
  return h('div', { class: 'testpanel' },
    h('div', { class: 'card' }, h('div', { class: 'row' }, h('h3', { class: 'grow' }, 'Test run'), h('span', { class: 'pill off' }, 'Uses your AI key · no tools on the web')),
      h('div', { style: 'margin-top:10px' }, input),
      h('div', { class: 'actions', style: 'margin-top:10px' }, h('button', { class: 'btn primary', disabled: state.testing, onclick: runAgentTest }, icon('play'), state.testing ? 'Running…' : 'Run test'))),
    h('div', { class: 'card' }, h('div', { class: 'row' }, h('h3', { class: 'grow' }, 'Live run log'), t && h('span', { class: 'muted small' }, state.testing ? 'status: running' : t.result != null ? 'status: done' : 'status: stopped')),
      !t ? h('p', { class: 'muted', style: 'margin-top:10px' }, 'Run a test to see each step and what it made.')
        : h('div', { style: 'margin-top:10px' },
          h('div', null, t.logs.map((l) => h('span', { class: 'logstep' + (l.error ? ' err' : l.pending ? ' run' : '') }, l.error ? '✕' : l.pending ? '…' : '✓', ' ', l.name.toUpperCase(), l.ms ? ` · ${(l.ms / 1000).toFixed(1)}s` : ''))),
          h('div', { class: 'logs' }, t.logs.filter((l) => !l.pending).map((l) => `▸ ${l.name}\n${l.error ? 'Error: ' + l.error : l.output}\n\n`).join('') || 'Waiting for the first step…'))));
}

function editorView() {
  const a = state.editing;
  const c = compileAgent(a);
  const saved = state.config.agents.some((x) => x.id === a.id);
  return h('div', { class: 'editor' },
    h('div', { class: 'card bar' },
      h('div', { class: 'crumb' }, icon('workflow'), h('button', { class: 'btn ghost small', onclick: () => { state.editing = null; render(); } }, 'Agents'), '/', h('b', null, (a.emoji ? a.emoji + ' ' : '') + (a.name || 'New agent'))),
      h('div', { class: 'spacer' }),
      h('label', { class: 'row small' }, toggle(a.active, (v) => { a.active = v; }, 'Active'), 'Active'),
      saved && h('button', { class: 'btn ghost danger small', onclick: () => deleteAgent(a) }, icon('trash_2'), 'Delete'),
      h('button', { class: 'btn small', onclick: () => document.querySelector('.testpanel textarea')?.focus() }, icon('play'), 'Test'),
      h('button', { class: 'btn primary small', onclick: saveAgent }, icon('check'), 'Save')),
    paletteView(), canvasView(c), propsView(c), testView());
}

function agentCard(agent) {
  const c = compileAgent(agent);
  const live = agent.active && state.config.skills.some((s) => s.name === agent.name);
  const key = state.config.skills.find((s) => s.name === agent.name)?.key;
  return h('div', { class: 'card tile' },
    h('div', { class: 'top' }, h('div', { class: 'emoji' }, agent.emoji || '🤖'), h('h3', null, agent.name), key && h('span', { class: 'keycap' }, key.toUpperCase()),
      h('span', { class: 'pill' + (live ? ' ok' : ' off') }, live ? 'Active' : 'Paused')),
    h('p', { class: 'muted clamp' }, agent.description || 'No description'),
    h('p', { class: 'small clamp' }, c.order.map((n) => blockInfo(n).label).join(' → ')),
    h('div', { class: 'foot' }, h('button', { class: 'btn small', onclick: () => openAgent(agent) }, icon('pencil'), 'Open editor'),
      h('button', { class: 'btn ghost small', onclick: () => { openAgent(agent); } }, icon('play'), 'Test')));
}

function agentsTab() {
  if (state.editing) return editorView();
  const agents = state.config.agents || [];
  return [
    h('div', { class: 'head' },
      h('div', null, h('h1', null, 'Agents'), h('p', { class: 'muted sub' }, 'Link a trigger, skills, tools and actions on a canvas. Your keyboard runs the whole chain in one go.')),
      agents.length > 0 && h('button', { class: 'btn primary', onclick: () => openAgent(blankAgent()) }, icon('plus'), 'Create agent')),
    agents.length === 0
      ? h('div', { class: 'card empty' }, h('div', null,
        h('div', { class: 'bot', style: 'margin:0 auto 16px' }, '🤖'), h('h2', null, 'No agents created yet'),
        h('p', { class: 'muted' }, 'Get started by building your first agent: pick what starts it, then drag in the steps it should take.'),
        h('button', { class: 'btn primary', onclick: () => openAgent(blankAgent()) }, icon('plus'), 'Create agent')))
      : h('div', { class: 'grid' }, agents.map(agentCard)),
    h('h2', { class: 'section' }, 'Start from a template'),
    h('p', { class: 'muted small section-sub' }, 'Opens in the editor. Change anything, then Save.'),
    h('div', { class: 'grid' }, TEMPLATES.map((t) => h('button', { class: 'card tile', onclick: () => openAgent(fromTemplate(t)) },
      h('div', { class: 'top' }, h('div', { class: 'emoji' }, t.emoji), h('h3', null, t.name)),
      h('p', { class: 'muted' }, t.description),
      h('p', { class: 'small clamp' }, t.chain.map(([kind, ref, text]) => blockInfo({ kind, ref, text: text || '', auto: false }).label).join(' → '))))),
  ];
}
