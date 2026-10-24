import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseNdjson, stats } from './analyze.mjs';

const finite = (value) => typeof value === 'number' && Number.isFinite(value);
const validPid = (value) => Number.isInteger(value) && value > 0;
const MODULES = ['RTSSHooks64.dll', 'nvspcap64.dll'];
const normalizeDate = (value) => typeof value === 'string' && Number.isFinite(Date.parse(value)) ? Date.parse(value) : null;
const difference = (a, b) => finite(a) && finite(b) ? b - a : null;

/** Foreground is observed at sample boundaries; stable boundaries cannot prove continuous focus. */
export function summarizeFocus(rows, { recentInputMs = 1000 } = {}) {
  if (!Array.isArray(rows)) throw new TypeError('Capture rows must be an array');
  if (!finite(recentInputMs) || recentInputMs < 0) throw new TypeError('recentInputMs must be nonnegative');
  const metadata = Object.assign({}, ...rows.filter((row) => row?.kind === 'metadata'));
  const issues = [], notices = [], roles = [], groups = new Map();
  const cores = Number.isInteger(metadata.logicalProcessorCount) && metadata.logicalProcessorCount > 0
    ? metadata.logicalProcessorCount : null;
  const basis = metadata.processCpuPctBasis;
  const cpu = (value) => !finite(value) ? null : basis === 'total-capacity' ? value
    : basis === 'one-core' && cores ? value / cores : null;
  if (basis !== 'total-capacity' && !(basis === 'one-core' && cores)) notices.push('DWM total-capacity CPU unavailable: percentage basis or logical CPU count missing.');
  for (const row of rows) {
    if (row?.kind === 'parse_error') issues.push(`Unparsed JSON at line ${row.line}`);
    if (row?.kind === 'error') issues.push(`Capture error: ${row.error ?? row.message ?? 'unspecified'}`);
    if (Array.isArray(row?.errors)) issues.push(...row.errors.map((error) => typeof error === 'string' ? error : JSON.stringify(error)));
    if (row?.kind !== 'operation' || row.action !== 'launch' || typeof row.role !== 'string') continue;
    for (const pid of new Set([row.launcherPid, row.jvmPid].filter(validPid))) {
      const owned = row.owned?.find((process) => process.pid === pid);
      roles.push({ pid, role: row.role, from: row.elapsedMs, until: Infinity,
        startedUtc: owned?.startedUtc ?? null });
    }
  }
  for (const role of roles) {
    const close = rows.find((row) => row?.kind === 'operation' && row.action === 'close'
      && row.role === role.role && finite(row.elapsedMs) && row.elapsedMs >= role.from);
    if (close) role.until = close.elapsedMs;
    const reused = roles.find((next) => next.pid === role.pid && next.from > role.from);
    if (reused) role.until = Math.min(role.until, reused.from);
  }
  const roleOf = (sample) => {
    const matches = roles.filter((role) => role.pid === sample.foregroundPid
      && sample.elapsedMs >= role.from && sample.elapsedMs < role.until);
    if (!matches.length) return 'other';
    const role = matches.at(-1);
    const process = sample.processes?.find((item) => item.pid === sample.foregroundPid);
    const expected = normalizeDate(role.startedUtc), actual = normalizeDate(process?.startedUtc);
    if (expected !== null && actual !== null && expected !== actual) {
      notices.push(`PID ${role.pid} start time differs from owned ${role.role} launch; role is unresolved.`);
      return 'unresolved';
    }
    if (expected === null || actual === null) notices.push(`PID ${role.pid}: owned foreground role lacks a matching process start-time observation.`);
    return role.role;
  };
  let previous = null, sampleCount = 0, stableSamples = 0, droppedTransition = 0, droppedUnavailable = 0;
  for (const row of rows) {
    if (row?.kind !== 'sample') continue;
    sampleCount++;
    if (!finite(row.elapsedMs) || typeof row.phase !== 'string') { issues.push('Malformed sample time/phase'); previous = null; continue; }
    if (!validPid(row.foregroundPid)) { droppedUnavailable++; previous = null; continue; }
    const stable = previous && previous.phase === row.phase && previous.foregroundPid === row.foregroundPid
      && row.elapsedMs > previous.elapsedMs;
    if (!stable) { droppedTransition++; previous = row; continue; }
    const role = roleOf(row);
    const input = finite(row.lastInputAgeMs) && row.lastInputAgeMs >= 0
      ? row.lastInputAgeMs <= recentInputMs ? 'recent' : 'not_recent' : 'unavailable';
    const key = `${row.phase}|${role}|${input}`;
    if (!groups.has(key)) groups.set(key, { key, phase: row.phase, foregroundRole: role, input,
      sampleCount: 0, dwm: [], heartbeatMax: [], heartbeatP95: [], inputAge: [], captureCost: [], schedulingDelay: [] });
    const group = groups.get(key);
    group.sampleCount++; stableSamples++;
    const dwm = (Array.isArray(row.processes) ? row.processes : []).filter((process) => /^dwm(?:\.exe)?$/i.test(process.name));
    const values = dwm.map((process) => cpu(process.cpuPct));
    group.dwm.push(values.length && values.every(finite) ? values.reduce((sum, value) => sum + value, 0) : null);
    group.heartbeatMax.push(row.heartbeat?.maxDelayMs); group.heartbeatP95.push(row.heartbeat?.p95DelayMs);
    group.inputAge.push(row.lastInputAgeMs); group.captureCost.push(row.sampleCostMs);
    group.schedulingDelay.push(row.schedulingDelayMs);
    previous = row;
  }
  const completed = rows.filter((row) => row?.kind === 'end').at(-1)?.complete === true;
  if (!completed) issues.push('No successful end marker');
  if (!stableSamples) issues.push('No stable foreground sample pairs');
  const inventories = [];
  for (const role of new Set([...roles.map((item) => item.role), ...rows.filter((row) => row?.kind === 'modules').map((row) => row.role)])) {
    const snapshots = rows.filter((row) => row?.kind === 'modules' && row.role === role && Array.isArray(row.names));
    inventories.push({ role, snapshots: snapshots.length, modules: Object.fromEntries(MODULES.map((name) => {
      const present = snapshots.filter((row) => row.names.some((module) => String(module).toLowerCase() === name.toLowerCase()));
      return [name, { status: !snapshots.length ? 'unavailable' : present.length ? 'observed_present' : 'not_observed',
        presentSnapshots: present.length, observedPids: [...new Set(present.map((row) => row.pid).filter(validPid))] }];
    })) });
  }
  return { status: issues.length ? 'INCONCLUSIVE' : 'OBSERVATIONS_AVAILABLE',
    metadata: { processCpuPctBasis: basis ?? 'unknown', logicalProcessorCount: cores, recentInputMs },
    quality: { completed, sampleCount, stableSamples, droppedTransition, droppedUnavailable,
      issues: [...new Set(issues)], notices: [...new Set(notices)] },
    groups: [...groups.values()].map(({ dwm, heartbeatMax, heartbeatP95, inputAge, captureCost, schedulingDelay, ...group }) => ({
      ...group, dwmTotalCapacityCpuPct: stats(dwm), heartbeatWindowMaxMs: stats(heartbeatMax),
      heartbeatWindowP95Ms: stats(heartbeatP95), lastInputAgeMs: stats(inputAge), captureCostMs: stats(captureCost),
      schedulingDelayMs: stats(schedulingDelay)
    })), moduleInventory: inventories };
}

export function compareFocus(beforeRows, afterRows, options) {
  const before = summarizeFocus(beforeRows, options), after = summarizeFocus(afterRows, options);
  const keys = new Set([...before.groups, ...after.groups].map((group) => group.key));
  return { schema: 1, status: before.status === 'INCONCLUSIVE' || after.status === 'INCONCLUSIVE'
    ? 'INCONCLUSIVE' : 'OBSERVATIONS_AVAILABLE', before, after,
    comparisons: [...keys].map((key) => {
      const first = before.groups.find((group) => group.key === key), second = after.groups.find((group) => group.key === key);
      const sufficient = first?.sampleCount >= 3 && second?.sampleCount >= 3;
      return { key, status: sufficient ? 'MATCHED_GROUP' : 'INSUFFICIENT_MATCHED_SAMPLES',
        beforeSamples: first?.sampleCount ?? 0, afterSamples: second?.sampleCount ?? 0,
        dwmMedianDeltaPp: sufficient ? difference(first.dwmTotalCapacityCpuPct.median, second.dwmTotalCapacityCpuPct.median) : null,
        dwmP95DeltaPp: sufficient ? difference(first.dwmTotalCapacityCpuPct.p95, second.dwmTotalCapacityCpuPct.p95) : null,
        heartbeatPeakDeltaMs: sufficient ? difference(first.heartbeatWindowMaxMs.max, second.heartbeatWindowMaxMs.max) : null };
    }), interpretation: [
      'Observations and differences do not establish a cause. Module presence is inventory, not evidence of a faulty hook or driver.',
      'The first sample in each phase or after a foreground PID change is excluded because its rate interval mixes focus states. Equal consecutive boundary observations do not prove continuous foreground focus.',
      'Input age reflects any local user input; no key, text, or target is recorded. Recent input does not prove interaction with the foreground launcher.',
      'DWM CPU sums observed selected DWM processes within a sample and uses declared total CPU capacity units. Missing DWM/counters are unavailable, not zero.',
      'Matched groups share phase, observed owned foreground role, and input recency, but are not randomized experiments. Three samples per side are a minimum screen, not a statistical confidence claim.'
    ] };
}

async function main(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    if (!['--before', '--after', '--output'].includes(args[index]) || !args[index + 1] || options[args[index]]) {
      throw new Error('Usage: node compare-focus.mjs --before capture.ndjson --after capture.ndjson --output comparison.json');
    }
    options[args[index]] = path.resolve(args[index + 1]);
  }
  const paths = Object.values(options).map((value) => process.platform === 'win32' ? value.toLowerCase() : value);
  if (Object.keys(options).length !== 3 || new Set(paths).size !== 3) throw new Error('Two inputs and output must be three distinct paths');
  const [before, after] = await Promise.all(['--before', '--after'].map(async (key) => parseNdjson(await fs.readFile(options[key], 'utf8'))));
  const report = compareFocus(before, after);
  await fs.writeFile(options['--output'], JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  process.stdout.write(`${report.status}: ${options['--output']}\n`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
