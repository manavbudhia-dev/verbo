// Gives or takes away Verbo Pro by hand: for testers, friends, or a payment taken outside Razorpay (UPI to you).
// Run on the server, next to server.js, with the same DATA_DIR:
//   node grant.js someone@gmail.com lifetime     Pro for life
//   node grant.js someone@gmail.com month        30 more days
//   node grant.js someone@gmail.com days 90      90 more days
//   node grant.js someone@gmail.com free         back to the free plan
//   node grant.js someone@gmail.com              show the account's plan
// With Docker: docker compose exec dashboard node grant.js someone@gmail.com lifetime
'use strict';
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const [email, what, count] = process.argv.slice(2);
if (!email) { console.log('Usage: node grant.js EMAIL [lifetime|month|days N|free]'); process.exit(1); }
const db = new DatabaseSync(path.join(process.env.DATA_DIR || path.join(__dirname, 'data'), 'verbo.db'));
for (const sql of ['ALTER TABLE users ADD COLUMN pro_until INTEGER NOT NULL DEFAULT 0', 'ALTER TABLE users ADD COLUMN pro_plan TEXT']) {
  try { db.exec(sql); } catch { /* already there */ }
}
const user = db.prepare('SELECT id, pro_until, pro_plan FROM users WHERE email = ?').get(email.trim().toLowerCase());
if (!user) { console.log('No account with that email. They need to sign in to the dashboard or the app once first.'); process.exit(1); }

const LIFETIME = Date.UTC(2100, 0, 1);
const left = Math.max(Date.now(), user.pro_until);
let until = user.pro_until;
let plan = user.pro_plan;
if (what === 'lifetime') { until = LIFETIME; plan = 'lifetime'; }
else if (what === 'month') { until = left + 30 * 86400000; plan = plan === 'lifetime' ? plan : 'month'; }
else if (what === 'days') {
  const n = Number(count);
  if (!Number.isInteger(n) || n < 1 || n > 3650) { console.log('Give the number of days, like: days 90'); process.exit(1); }
  until = left + n * 86400000; plan = plan === 'lifetime' ? plan : 'gift';
} else if (what === 'free') { until = 0; plan = null; }
else if (what) { console.log('Unknown plan. Use lifetime, month, days N or free.'); process.exit(1); }

if (what) db.prepare('UPDATE users SET pro_until = ?, pro_plan = ? WHERE id = ?').run(until, plan, user.id);
console.log(until > Date.now() ? `${email}: Verbo Pro (${plan}) until ${until >= LIFETIME ? 'forever' : new Date(until).toDateString()}` : `${email}: free plan`);
