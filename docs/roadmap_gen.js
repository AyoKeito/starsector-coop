#!/usr/bin/env node
/*
 * roadmap_gen.js — regenerate docs/roadmap.html for the Starsector Coop mod.
 *
 *   node docs/roadmap_gen.js          # regenerate docs/roadmap.html
 *   node docs/roadmap_gen.js --check  # validate only, don't write (prints drift warnings)
 *
 * SOURCES OF TRUTH
 *   - Per-phase STATUS  ← parsed from COOP_MP_IMPLEMENTATION_PLAN_V1.md "Phase Status Ledger" table.
 *   - Remaining BUILD ORDER ← parsed from that doc's "Implementation Order" bold arrow sequence.
 *   - Everything else (titles, summaries, "introduces", residue, track, banner, attention,
 *     acceptance, non-goals) ← docs/roadmap.data.json.
 *
 * So the common update — flipping a phase's status in the ledger, or reordering the build queue —
 * needs NO edit here or in the JSON: just re-run this script. Adding a brand-new phase, or changing
 * a card's prose, means editing roadmap.data.json (the script warns when the ledger has a phase the
 * JSON doesn't). Design tweaks live in roadmap.template.html.
 *
 * Pure Node, no dependencies.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const DIR = __dirname;
const DATA_PATH = path.join(DIR, 'roadmap.data.json');
const TEMPLATE_PATH = path.join(DIR, 'roadmap.template.html');
const OUT_PATH = path.join(DIR, 'roadmap.html');

const CHECK_ONLY = process.argv.includes('--check');
const warnings = [];
const warn = (m) => warnings.push(m);

const data = JSON.parse(fs.readFileSync(DATA_PATH, 'utf8'));
const PLAN_PATH = path.join(DIR, (data.meta && data.meta.planDoc) || 'COOP_MP_IMPLEMENTATION_PLAN_V1.md');
if (!fs.existsSync(PLAN_PATH)) { console.error('FATAL: plan doc not found at ' + PLAN_PATH); process.exit(2); }
const plan = fs.readFileSync(PLAN_PATH, 'utf8');

// ---- parse the Phase Status Ledger table -> { id: 'built'|'partial'|'not-built' } ----
function parseLedger(text) {
  const at = text.indexOf('Phase Status Ledger');
  if (at < 0) { console.error('FATAL: "Phase Status Ledger" heading not found in plan doc'); process.exit(2); }
  const lines = text.slice(at).split(/\r?\n/);
  const rows = [];
  let started = false;
  for (const ln of lines) {
    if (/^\s*\|/.test(ln)) { started = true; rows.push(ln); }
    else if (started) break; // table ended
  }
  if (!rows.length) { console.error('FATAL: could not locate the ledger table'); process.exit(2); }
  const map = {};
  for (const row of rows) {
    const cells = row.split('|').map(c => c.trim());
    // cells[0] is '' (leading pipe); real cells start at index 1
    const phaseCell = cells[1] || '';
    const statusCell = cells[2] || '';
    if (!phaseCell || /^phase$/i.test(phaseCell) || /^:?-{2,}:?$/.test(phaseCell)) continue; // header/sep
    const sNorm = statusCell.replace(/[*`]/g, '').trim().toUpperCase();
    let status = null;
    if (sNorm.includes('PARTIAL')) status = 'partial';
    else if (sNorm.includes('NOT BUILT')) status = 'not-built';
    else if (sNorm.includes('BUILT')) status = 'built';
    if (!status) continue;
    for (const tok of phaseCell.split(',').map(t => t.trim())) {
      const range = tok.match(/^(\d+)\s*[–—-]\s*(\d+)$/); // en/em dash or hyphen
      if (range) {
        for (let i = +range[1]; i <= +range[2]; i++) map[String(i)] = status;
      } else if (/^\d+[a-z]?$/.test(tok)) {
        map[tok] = status;
      } else {
        warn('Ledger: unparsed phase token "' + tok + '" (status ' + status + ')');
      }
    }
  }
  return map;
}

// ---- parse the "Implementation Order" bold arrow sequence -> ['12b','6b',...] ----
function parseBuildOrder(text) {
  const secAt = text.indexOf('Implementation Order');
  const scope = secAt >= 0 ? text.slice(secAt, secAt + 4000) : text;
  const bolds = scope.match(/\*\*([^*]*?→[^*]*?)\*\*/g) || []; // bold runs containing →
  let best = null, bestCount = 0;
  for (const b of bolds) {
    const count = (b.match(/→/g) || []).length;
    if (count > bestCount) { bestCount = count; best = b; }
  }
  if (!best || bestCount < 6) return null;
  const ids = [];
  for (const seg of best.replace(/^\*\*|\*\*$/g, '').split('→')) {
    const m = seg.trim().match(/^\(?\s*(\d+[a-z]?)/);
    if (m && !ids.includes(m[1])) ids.push(m[1]);
  }
  return ids.length >= 6 ? ids : null;
}

// ---- parse "## Phase N: Title" headings for a cross-check ----
function parseTitles(text) {
  const map = {};
  const re = /^##\s+Phase\s+(\d+[a-z]?)(?:\.\d+)?\s*:\s*(.+?)\s*$/gm;
  let m;
  while ((m = re.exec(text))) { if (!map[m[1]]) map[m[1]] = m[2]; }
  return map;
}

const ledger = parseLedger(plan);
const parsedOrder = parseBuildOrder(plan);
const titles = parseTitles(plan);

const buildOrder = parsedOrder || data.buildOrderFallback || [];
if (!parsedOrder) warn('Could not parse the Implementation Order build sequence — using buildOrderFallback from roadmap.data.json.');
else if (JSON.stringify(parsedOrder) !== JSON.stringify(data.buildOrderFallback || [])) {
  warn('Build order in the plan doc differs from buildOrderFallback in roadmap.data.json (using the doc). Doc: ' + parsedOrder.join(' -> '));
}

// ---- merge: apply ledger status, cross-check titles, flag missing/extra phases ----
const byId = {};
data.phases.forEach(p => { byId[p.id] = p; });

data.phases.forEach(p => {
  const ds = ledger[p.id];
  if (ds == null) warn('P' + p.id + ': not in the Phase Status Ledger — status falls back to data.json ("' + p.status + '").');
  else {
    if (ds !== p.status) warn('P' + p.id + ': status drift — data.json="' + p.status + '", ledger="' + ds + '" (using ledger).');
    p.status = ds;
  }
  const dt = titles[p.id];
  if (!dt) warn('P' + p.id + ': no "## Phase ' + p.id + ':" heading found in the plan doc.');
  else {
    const base = s => s.toLowerCase().split(' (')[0].replace(/[^a-z0-9]+/g, ' ').trim();
    if (base(dt) !== base(p.title) && !base(dt).startsWith(base(p.title)) && !base(p.title).startsWith(base(dt)))
      warn('P' + p.id + ': title differs from doc heading — data.json="' + p.title + '", doc="' + dt + '".');
  }
});

// ledger ids with no card in the JSON
Object.keys(ledger).forEach(id => {
  if (!byId[id]) warn('Ledger has P' + id + ' (' + ledger[id] + ') but roadmap.data.json has no card for it — add {id,num,title,theme,track,summary,introduces} to render it.');
});

// ---- derive bucket / order / pendingSmoke ----
const nextId = buildOrder.find(id => byId[id] && byId[id].status === 'not-built');
function bucketOf(p) {
  if (p.status === 'built') return 'built';
  if (p.status === 'partial') return 'partial';
  if (p.track === 'post-v1') return 'postv1';
  if (p.id === nextId) return 'next';
  return 'queued';
}
data.phases.forEach(p => {
  p.bucket = bucketOf(p);
  const oi = buildOrder.indexOf(p.id);
  p.order = oi >= 0 ? oi + 1 : null;
  p.pendingSmoke = (p.residue || []).some(r => /PENDING/i.test(r));
});

// ---- assemble the object the template expects ----
const DATA = {
  generated: new Date().toISOString().slice(0, 10),
  planDate: (data.meta && data.meta.planDate) || '',
  repo: (data.meta && data.meta.repo) || '',
  here: data.here,
  buckets: data.buckets,
  phases: data.phases,
  attention: data.attention,
  buildOrder: buildOrder,
  acceptance: data.acceptance || [],
  nonGoals: data.nonGoals || [],
};

// ---- report ----
const counts = {};
data.phases.forEach(p => { counts[p.bucket] = (counts[p.bucket] || 0) + 1; });
const order = ['built', 'partial', 'next', 'queued', 'postv1'];
console.log('Phases: ' + data.phases.length + '  |  ' + order.map(k => k + '=' + (counts[k] || 0)).join(' '));
console.log('Next up: P' + (nextId || '?') + '   Build order: ' + buildOrder.map(i => 'P' + i).join(' -> '));
if (warnings.length) {
  console.log('\n' + warnings.length + ' warning(s):');
  warnings.forEach(w => console.log('  ! ' + w));
} else {
  console.log('No drift — data.json is consistent with the plan doc.');
}

if (CHECK_ONLY) { console.log('\n--check: not writing ' + path.basename(OUT_PATH) + '.'); process.exit(0); }

const template = fs.readFileSync(TEMPLATE_PATH, 'utf8');
if (template.indexOf('/*__DATA__*/') < 0) { console.error('FATAL: template is missing the /*__DATA__*/ placeholder'); process.exit(2); }
const html = template.replace('/*__DATA__*/', () => JSON.stringify(DATA));
fs.writeFileSync(OUT_PATH, html, 'utf8');
console.log('\nWrote ' + OUT_PATH + ' (' + html.length + ' bytes).');
