import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const EXPECTED_PHASES = ['baseline', 'host', 'both', 'host_after', 'after'];
const METRICS = ['cpuPct', 'cpuMaxCorePct', 'privilegedPct', 'dpcPct', 'dpcMaxCorePct',
  'interruptPct', 'interruptMaxCorePct', 'availableMB', 'pagesInPerSec', 'commitPct', 'diskQueue', 'diskReadMs', 'diskWriteMs'];
const finite = (value) => typeof value === 'number' && Number.isFinite(value);
const quantile = (values, fraction) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const index = (sorted.length - 1) * fraction;
  const low = Math.floor(index), high = Math.ceil(index);
  return sorted[low] + (sorted[high] - sorted[low]) * (index - low);
};
export function stats(values) {
  const observed = values.filter(finite);
  return { observed: observed.length, unavailable: values.length - observed.length,
    zeroCount: observed.filter((value) => value === 0).length,
    min: observed.length ? Math.min(...observed) : null, median: quantile(observed, 0.5),
    p95: quantile(observed, 0.95), max: observed.length ? Math.max(...observed) : null };
}
const delta = (current, baseline) => finite(current) && finite(baseline) ? current - baseline : null;
const timestamp = (value) => typeof value === 'string' && Number.isFinite(Date.parse(value));
function physicalGpu(row) {
  if (row.gpuStatus !== 'ok' || !Array.isArray(row.gpu)) return null;
  const engines = new Map();
  const seen = new Set();
  for (const item of row.gpu) {
    if (!Number.isInteger(item.pid) || typeof item.engine !== 'string' || !finite(item.pct) || item.pct < 0) continue;
    const physical = item.engine.replace(/^pid_\d+_/i, '');
    const key = `${item.pid}|${physical}`;
    if (seen.has(key)) continue;
    seen.add(key);
    engines.set(physical, (engines.get(physical) ?? 0) + item.pct);
  }
  return engines;
}

function summarizePhase(phaseRows, interval) {
  const gaps = phaseRows.slice(1).map((row, index) => row.elapsedMs - phaseRows[index].elapsedMs);
  return {
    sampleCount: phaseRows.length,
    observedSpanMs: phaseRows.length ? phaseRows.at(-1).elapsedMs - phaseRows[0].elapsedMs : null,
    metrics: Object.fromEntries(METRICS.map((key) => [key, stats(phaseRows.map((row) => row.metrics?.[key]))])),
    heartbeatWindowMaxMs: stats(phaseRows.map((row) => row.heartbeat?.maxDelayMs)),
    heartbeatWindowP95Ms: stats(phaseRows.map((row) => row.heartbeat?.p95DelayMs)),
    captureCostMs: stats(phaseRows.map((row) => row.sampleCostMs)),
    sampleGapMs: stats(gaps), schedulingDelayMs: stats(gaps.map((gap) => interval === null ? null : Math.max(0, gap - interval))),
    gpuPeakEnginePct: stats(phaseRows.map((row) => {
      const physical = physicalGpu(row);
      return physical === null ? null : Math.max(0, ...physical.values());
    })),
    gpuUnavailableSamples: phaseRows.filter((row) => row.gpuStatus !== 'ok').length,
    gpuTruncatedSamples: phaseRows.filter((row) => row.gpuTruncated === true).length
  };
}

/** Parse damaged captures without silently dropping the line that makes them incomplete. */
export function parseNdjson(text) {
  return text.split(/\r?\n/).flatMap((line, index) => {
    if (!line.trim()) return [];
    try { return [JSON.parse(line)]; }
    catch (error) { return [{ kind: 'parse_error', line: index + 1, error: error.message }]; }
  });
}

export function analyze(rows) {
  if (!Array.isArray(rows)) throw new TypeError('analyze expects an array of capture rows');
  const metadata = Object.assign({}, ...rows.filter((row) => row?.kind === 'metadata'));
  const interval = finite(metadata.sampleIntervalMs) && metadata.sampleIntervalMs > 0 ? metadata.sampleIntervalMs : null;
  const cores = Number.isInteger(metadata.logicalProcessorCount) && metadata.logicalProcessorCount > 0
    ? metadata.logicalProcessorCount : null;
  const cpuBasis = ['one-core', 'total-capacity'].includes(metadata.processCpuPctBasis)
    ? metadata.processCpuPctBasis : 'unknown';
  const settlingMs = finite(metadata.phaseSettlingMs) && metadata.phaseSettlingMs >= 0 ? metadata.phaseSettlingMs : 3000;
  const issues = [], notices = [], samples = [];
  let previousElapsed = null, previousUtc = null;
  for (const [index, row] of rows.entries()) {
    if (row?.kind === 'parse_error') { issues.push(`Unparsed JSON at line ${row.line}: ${row.error}`); continue; }
    if (row?.kind !== 'sample') {
      if (row?.kind === 'error') issues.push(`Capture error: ${row.error ?? row.message ?? 'unspecified'}`);
      if (Array.isArray(row?.errors)) issues.push(...row.errors.map((error) => `${row.kind}: ${typeof error === 'string' ? error : JSON.stringify(error)}`));
      continue;
    }
    if (row.schema !== 1 || !timestamp(row.utc) || !finite(row.elapsedMs) || row.elapsedMs < 0
        || typeof row.phase !== 'string' || !row.phase.trim()) {
      issues.push(`Malformed sample row ${index + 1}`); continue;
    }
    if (previousElapsed !== null && row.elapsedMs < previousElapsed) issues.push(`Monotonic elapsed time reversed at row ${index + 1}`);
    if (previousUtc !== null && Date.parse(row.utc) < previousUtc) notices.push(`Wall-clock timestamp reversed at row ${index + 1}; scheduling uses monotonic elapsed time`);
    previousElapsed = row.elapsedMs; previousUtc = Date.parse(row.utc);
    samples.push(row);
    if (Array.isArray(row.errors) && row.errors.length) issues.push(...row.errors.map((error) => `${row.phase} ${row.utc}: ${typeof error === 'string' ? error : JSON.stringify(error)}`));
    if (!Array.isArray(row.errors)) issues.push(`Missing error diagnostics at sample row ${index + 1}`);
    if (row.gpuStatus !== 'ok') notices.push(`${row.phase} ${row.utc}: GPU counters unavailable`);
    if (row.gpuTruncated === true) notices.push(`${row.phase} ${row.utc}: GPU engine table truncated`);
  }
  if (!interval) notices.push('Expected sampling interval unavailable; scheduler delay cannot be inferred');
  if (cpuBasis === 'unknown') notices.push('Process CPU percentage basis unavailable; raw CPU values are not normalized');
  if (cpuBasis === 'one-core' && !cores) notices.push('Logical CPU count unavailable; one-core process CPU cannot be normalized');
  const completed = rows.filter((row) => row?.kind === 'end').at(-1)?.complete === true;
  if (!completed) issues.push('No successful end marker; capture or controlled cleanup may be incomplete');
  const phaseNames = [...new Set([...EXPECTED_PHASES, ...samples.map((row) => row.phase)])];
  const phases = {}, processes = new Map(), gpuEngines = new Map(), physicalGpuEngines = new Map(), pidIdentities = new Map();
  for (const phase of phaseNames) {
    const phaseRows = samples.filter((row) => row.phase === phase);
    if (EXPECTED_PHASES.includes(phase) && phaseRows.length < 3) issues.push(`${phase}: ${phaseRows.length} samples; at least 3 are needed for a useful phase comparison`);
    const marker = rows.find((row) => row?.kind === 'phase' && row.phase === phase && finite(row.elapsedMs));
    const phaseStartMs = marker?.elapsedMs ?? phaseRows[0]?.elapsedMs ?? null;
    const steadyRows = phaseStartMs === null ? [] : phaseRows.filter((row) => row.elapsedMs >= phaseStartMs + settlingMs);
    phases[phase] = { ...summarizePhase(phaseRows, interval), phaseStartMs,
      steady: { ...summarizePhase(steadyRows, interval), settlingMs,
        excludedSampleCount: phaseRows.length - steadyRows.length,
        status: steadyRows.length >= 3 ? 'SUFFICIENT' : 'INSUFFICIENT' } };
    if (phaseRows.length && steadyRows.length < 3) notices.push(`${phase}: only ${steadyRows.length} steady samples after ${settlingMs} ms settling; steady behavior is inconclusive`);
    if (phaseRows.length && phases[phase].heartbeatWindowMaxMs.observed === 0) issues.push(`${phase}: independent heartbeat unavailable`);
    if (phaseRows.length && METRICS.every((key) => phases[phase].metrics[key].observed === 0)) issues.push(`${phase}: all native performance counters unavailable`);
    for (const row of phaseRows) {
      for (const [engine, pct] of physicalGpu(row) ?? []) {
        const key = `${phase}|${engine}`;
        if (!physicalGpuEngines.has(key)) physicalGpuEngines.set(key, { phase, engine, values: [] });
        physicalGpuEngines.get(key).values.push(pct);
      }
      const identities = new Map();
      for (const process of Array.isArray(row.processes) ? row.processes : []) {
        if (!Number.isInteger(process.pid) || process.pid < 0 || typeof process.name !== 'string') {
          issues.push(`Invalid process identity in ${phase} ${row.utc}`); continue;
        }
        const started = timestamp(process.startedUtc) ? process.startedUtc : null;
        const identity = `${process.pid}|${started ?? `unknown:${process.name}`}`;
        if (!started) notices.push(`PID ${process.pid} ${process.name}: start time unavailable; same-name PID reuse cannot be distinguished`);
        if (started && Date.parse(started) > Date.parse(row.utc)) notices.push(`PID ${process.pid}: process start is later than its sample timestamp`);
        if (!pidIdentities.has(process.pid)) pidIdentities.set(process.pid, new Set());
        pidIdentities.get(process.pid).add(identity); identities.set(process.pid, identity);
        const key = `${phase}|${identity}`;
        if (!processes.has(key)) processes.set(key, { phase, identity, pid: process.pid, name: process.name, startedUtc: started,
          cpu: [], totalCpu: [], steadyCpu: [], steadyTotalCpu: [], workingSet: [], private: [], read: [], write: [] });
        const target = processes.get(key);
        target.cpu.push(process.cpuPct);
        const totalCpu = cpuBasis === 'total-capacity' ? process.cpuPct
          : cpuBasis === 'one-core' && cores && finite(process.cpuPct) ? process.cpuPct / cores : null;
        target.totalCpu.push(totalCpu);
        if (row.elapsedMs >= phases[phase].phaseStartMs + settlingMs) {
          target.steadyCpu.push(process.cpuPct); target.steadyTotalCpu.push(totalCpu);
        }
        target.workingSet.push(process.workingSetMB); target.private.push(process.privateMB);
        target.read.push(process.readMBps); target.write.push(process.writeMBps);
      }
      for (const engine of Array.isArray(row.gpu) ? row.gpu : []) {
        if (!Number.isInteger(engine.pid) || typeof engine.engine !== 'string' || !finite(engine.pct)) {
          issues.push(`Invalid GPU engine in ${phase} ${row.utc}`); continue;
        }
        const identity = identities.get(engine.pid) ?? `${engine.pid}|unresolved`;
        const key = `${phase}|${identity}|${engine.engine}`;
        if (!gpuEngines.has(key)) gpuEngines.set(key, { phase, identity, pid: engine.pid, engine: engine.engine, values: [] });
        gpuEngines.get(key).values.push(engine.pct);
      }
    }
  }
  const processSummary = [...processes.values()].map(({ cpu, totalCpu, steadyCpu, steadyTotalCpu, workingSet, private: priv, read, write, ...identity }) => ({
    ...identity, cpuPct: stats(cpu), totalCapacityCpuPct: stats(totalCpu), workingSetMB: stats(workingSet),
    privateMB: stats(priv), readMBps: stats(read), writeMBps: stats(write),
    steady: { cpuPct: stats(steadyCpu), totalCapacityCpuPct: stats(steadyTotalCpu),
      selectedSamples: steadyCpu.length, phaseSamples: phases[identity.phase].steady.sampleCount }
  }));
  const gpuSummary = [...gpuEngines.values()].map(({ values, ...identity }) => ({ ...identity, pct: stats(values) }));
  const physicalGpuSummary = [...physicalGpuEngines.values()].map(({ values, ...identity }) => ({ ...identity, pct: stats(values) }));
  const sortMax = (get) => (a, b) => (get(b) ?? -Infinity) - (get(a) ?? -Infinity);
  const comparisons = EXPECTED_PHASES.filter((phase) => phase !== 'baseline').map((phase) => ({ phase, baseline: 'baseline',
    medianDeltas: Object.fromEntries(METRICS.map((key) => [key, delta(phases[phase].metrics[key].median, phases.baseline.metrics[key].median)])),
    heartbeatPeakDeltaMs: delta(phases[phase].heartbeatWindowMaxMs.max, phases.baseline.heartbeatWindowMaxMs.max),
    heartbeatWindowP95DeltaMs: delta(phases[phase].heartbeatWindowP95Ms.p95, phases.baseline.heartbeatWindowP95Ms.p95),
    captureP95DeltaMs: delta(phases[phase].captureCostMs.p95, phases.baseline.captureCostMs.p95),
    schedulingP95DeltaMs: delta(phases[phase].schedulingDelayMs.p95, phases.baseline.schedulingDelayMs.p95),
    steady: {
      available: phases[phase].steady.status === 'SUFFICIENT' && phases.baseline.steady.status === 'SUFFICIENT',
      medianDeltas: Object.fromEntries(METRICS.map((key) => [key,
        phases[phase].steady.status === 'SUFFICIENT' && phases.baseline.steady.status === 'SUFFICIENT'
          ? delta(phases[phase].steady.metrics[key].median, phases.baseline.steady.metrics[key].median) : null])),
      heartbeatPeakDeltaMs: phases[phase].steady.status === 'SUFFICIENT' && phases.baseline.steady.status === 'SUFFICIENT'
        ? delta(phases[phase].steady.heartbeatWindowMaxMs.max, phases.baseline.steady.heartbeatWindowMaxMs.max) : null
    }
  }));
  const candidates = rankCandidates(phases, interval, processSummary, cores);
  return { schema: 1, status: issues.length ? 'INCONCLUSIVE' : 'EVIDENCE_AVAILABLE',
    quality: { completed, sampleCount: samples.length, missingPhases: EXPECTED_PHASES.filter((phase) => !phases[phase].sampleCount),
      issues: [...new Set(issues)], notices: [...new Set(notices)],
      pidReuse: [...pidIdentities].filter(([, identities]) => identities.size > 1).map(([pid, identities]) => ({ pid, identities: [...identities] })) },
    captureMetadata: metadata,
    metadata: { sampleIntervalMs: interval, phaseSettlingMs: settlingMs, logicalProcessorCount: cores, processCpuPctBasis: cpuBasis },
    phases, comparisons,
    perPhaseProcessCpuRanks: Object.fromEntries(Object.keys(phases).map((phase) => [phase,
      processSummary.filter((process) => process.phase === phase)
        .sort(sortMax((process) => process.steady.totalCapacityCpuPct.median ?? process.steady.cpuPct.median))
        .slice(0, 10).map((process, index) => ({ rank: index + 1, ...process }))])),
    topProcessesByCpu: [...processSummary].sort(sortMax((p) => p.totalCapacityCpuPct.max ?? p.cpuPct.max)).slice(0, 20),
    topProcessesByIo: [...processSummary].sort(sortMax((p) => Math.max(p.readMBps.max ?? -Infinity, p.writeMBps.max ?? -Infinity))).slice(0, 20),
    topGpuEngines: gpuSummary.sort(sortMax((engine) => engine.pct.max)).slice(0, 20),
    topPhysicalGpuEngines: physicalGpuSummary.sort(sortMax((engine) => engine.pct.max)).slice(0, 20), candidates,
    interpretation: [
      'Candidates rank observed signals, not proven causes. This capture has no kernel stacks and cannot identify a driver responsible for a stall.',
      'Steady windows exclude the configured settling interval from each phase marker (or its first sample). At least three steady samples are required. Whole-phase observations are retained; startup-only signals do not establish idle lag.',
      'Heartbeat p95 is summarized across per-window p95 values; it is not a global event-level p95. Scheduling gaps and capture cost are separate from the independent heartbeat.',
      'PID shares of the same physical GPU engine are aggregated after removing the pid prefix. Different engines and GPUs are never added. Per-PID contributors remain separate. Process tables contain the collector-selected processes, not a complete process census.',
      'Missing counters remain unavailable, not zero. A complete capture with no ranked candidate does not prove the system was clear of lag.'
    ] };
}

function rankCandidates(phases, interval, processes, cores) {
  const whole = rankWindowCandidates(phases, interval);
  const steadyPhases = Object.fromEntries(Object.entries(phases).map(([phase, value]) =>
    [phase, { ...value.steady, sampleCount: value.steady.status === 'SUFFICIENT' ? value.steady.sampleCount : 0 }]));
  const steady = rankWindowCandidates(steadyPhases, interval).map((candidate) => ({ ...candidate, scope: 'steady' }));
  const seen = new Set(steady.map((candidate) => `${candidate.phase}|${candidate.category}`));
  const remaining = whole.filter((candidate) => !seen.has(`${candidate.phase}|${candidate.category}`)).map((candidate) => {
    const scope = phases[candidate.phase].steady.status === 'SUFFICIENT' ? 'startup-only' : 'insufficient-steady';
    return { ...candidate, scope, limit: candidate.limit + (scope === 'startup-only'
      ? ' This threshold was met only outside the sufficient steady window; it does not establish steady idle lag.'
      : ' Too few steady samples were captured to establish steady idle behavior.') };
  });
  const compositor = compositorCandidates(processes, cores);
  const priority = { steady: 0, 'startup-only': 1, 'insufficient-steady': 2 };
  return [...steady, ...compositor, ...remaining].sort((a, b) => priority[a.scope] - priority[b.scope] || a.rank - b.rank)
    .map((candidate, index) => ({ ...candidate, rank: index + 1 }));
}

function compositorCandidates(processes, cores) {
  const observed = (phase) => processes.filter((process) => process.phase === phase && /^dwm(?:\.exe)?$/i.test(process.name)
    && process.steady.phaseSamples >= 3
    && process.steady.totalCapacityCpuPct.observed >= Math.max(3, Math.ceil(process.steady.phaseSamples * 0.8)))
    .sort((a, b) => b.steady.totalCapacityCpuPct.median - a.steady.totalCapacityCpuPct.median)[0];
  const baseline = observed('baseline');
  if (!baseline) return [];
  const base = baseline.steady.totalCapacityCpuPct.median;
  return EXPECTED_PHASES.filter((phase) => phase !== 'baseline').flatMap((phase) => {
    const process = observed(phase);
    if (!process) return [];
    const cpu = process.steady.totalCapacityCpuPct.median;
    if (cpu - base < 2 || cpu < base * 3) return [];
    return [{ phase, cpu, delta: cpu - base, process }];
  }).sort((a, b) => b.delta - a.delta).map(({ phase, cpu, delta: increase, process }, index) => ({
    rank: 100 + index, category: 'compositor_activity', phase, scope: 'steady',
    evidence: `DWM PID ${process.pid} steady median rose from ${number(base)}% to ${number(cpu)}% total CPU (+${number(increase)} percentage points)`
      + (cores ? `, about ${number(cpu * cores / 100)} logical-core equivalents across ${cores} logical CPUs.` : '.'),
    limit: 'This is phase-correlated compositor activity, not proof of the lag cause or a saturated single thread. GPU utilization need not cross a threshold; no exact graphics driver or hook is identified.'
  }));
}

function rankWindowCandidates(phases, interval) {
  const candidates = [];
  const add = (category, score, phase, evidence, limit) => candidates.push({ category, score, phase, evidence, limit });
  for (const [phase, value] of Object.entries(phases)) {
    if (!value.sampleCount) continue;
    const metric = (key, stat = 'p95') => value.metrics[key][stat];
    const heartbeat = value.heartbeatWindowMaxMs.max;
    if (metric('cpuPct') >= 90 || metric('cpuMaxCorePct') >= 95) add('cpu_pressure', 3, phase,
      `Total CPU p95 ${number(metric('cpuPct'))}%; busiest core p95 ${number(metric('cpuMaxCorePct'))}%.`,
      'High utilization is activity evidence; it does not by itself prove the visible stall was CPU-bound.');
    if (metric('dpcPct') >= 10 || metric('dpcMaxCorePct') >= 15 || metric('interruptPct') >= 5 || metric('interruptMaxCorePct') >= 10) add('kernel_activity', 4, phase,
      `DPC p95 ${number(metric('dpcPct'))}% total / ${number(metric('dpcMaxCorePct'))}% max-core; interrupt p95 ${number(metric('interruptPct'))}% total / ${number(metric('interruptMaxCorePct'))}% max-core.`,
      'A driver or device cannot be named without event traces or kernel stacks.');
    if (metric('diskReadMs') >= 20 || metric('diskWriteMs') >= 20 || metric('diskQueue') >= 2) add('storage_activity', 2, phase,
      `Disk read/write p95 ${number(metric('diskReadMs'))}/${number(metric('diskWriteMs'))} ms; queue p95 ${number(metric('diskQueue'))}.`,
      'Queue length depends on the device; compare timing and selected process I/O before blaming storage.');
    if (metric('availableMB', 'min') !== null && metric('availableMB', 'min') < 1024
        && (metric('pagesInPerSec') >= 100 || metric('commitPct') >= 90)) add('memory_pressure', 2, phase,
      `Minimum available memory ${number(metric('availableMB', 'min'))} MB; pages-in p95 ${number(metric('pagesInPerSec'))}/s; commit p95 ${number(metric('commitPct'))}%.`,
      'Low available memory and paging/commit activity coincide. Ordinary file-backed page-in alone is not ranked as memory pressure, and these counters do not prove thrashing.');
    if (value.gpuPeakEnginePct.p95 >= 90) add('gpu_engine_activity', 2, phase,
      `Busiest individual GPU engine p95 ${number(value.gpuPeakEnginePct.p95)}%.`,
      'Concurrent engine percentages are not additive and do not establish GPU-wide saturation.');
    const baselineHeartbeat = phases.baseline.heartbeatWindowMaxMs.max;
    if (heartbeat >= 100 || finite(baselineHeartbeat) && heartbeat >= baselineHeartbeat * 3
        && heartbeat - baselineHeartbeat >= 40) add('responsiveness_latency', 5, phase,
      `Independent heartbeat peak ${number(heartbeat)} ms; p95 of window p95s ${number(value.heartbeatWindowP95Ms.p95)} ms.`,
      finite(metric('cpuPct')) && metric('cpuPct') < 60
        ? 'Measured total CPU stayed low. GPU, kernel, driver, scheduling, or unmeasured work remain unresolved possibilities; no driver cause is established.'
        : 'Correlate this phase with counters; the timer cannot assign the cause of a delay.');
    if (interval && value.captureCostMs.p95 >= interval * 0.5) add('capture_overhead', 1, phase,
      `Capture cost p95 ${number(value.captureCostMs.p95)} ms against ${interval} ms sampling.`,
      finite(heartbeat) && heartbeat < 50 ? 'The independent heartbeat stayed below 50 ms; slow collection alone is not evidence of a system-wide stall.'
        : 'Collection may contribute overhead, but independent heartbeat delay must be assessed separately.');
    if (interval && value.schedulingDelayMs.p95 >= interval) add('sample_scheduling_delay', 3, phase,
      `Sampling schedule delay p95 ${number(value.schedulingDelayMs.p95)} ms.`,
      'This is sample-loop delay relative to its declared cadence; expensive collection can also cause it.');
  }
  return candidates.sort((a, b) => b.score - a.score || EXPECTED_PHASES.indexOf(a.phase) - EXPECTED_PHASES.indexOf(b.phase))
    .map(({ score, ...candidate }, index) => ({ rank: index + 1, ...candidate }));
}

const number = (value) => finite(value) ? Number(value.toFixed(2)).toString() : 'unavailable';
const cell = (value) => String(value).replaceAll('|', '\\|').replaceAll('\n', ' ');
export function markdown(report) {
  const lines = ['# Lag capture analysis', '', `Status: **${report.status}**. ${report.quality.sampleCount} samples.`, '',
    '## Whole-phase samples', '',
    '| Phase | Samples | CPU median / p95 % | Max-core CPU p95 % | Heartbeat peak ms | Window p95 p95 ms | Capture p95 ms | Schedule p95 ms |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'];
  for (const [phase, p] of Object.entries(report.phases)) lines.push(`| ${cell(phase)} | ${p.sampleCount} | ${number(p.metrics.cpuPct.median)} / ${number(p.metrics.cpuPct.p95)} | ${number(p.metrics.cpuMaxCorePct.p95)} | ${number(p.heartbeatWindowMaxMs.max)} | ${number(p.heartbeatWindowP95Ms.p95)} | ${number(p.captureCostMs.p95)} | ${number(p.schedulingDelayMs.p95)} |`);
  lines.push('', '## Steady samples', '', `The first ${report.metadata.phaseSettlingMs} ms of each phase are excluded here; whole-phase spikes remain above.`, '',
    '| Phase | Status | Samples | Excluded | CPU median / p95 % | Heartbeat peak ms | Capture p95 ms |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: |');
  for (const [phase, value] of Object.entries(report.phases)) {
    const p = value.steady;
    lines.push(`| ${cell(phase)} | ${p.status} | ${p.sampleCount} | ${p.excludedSampleCount} | ${number(p.metrics.cpuPct.median)} / ${number(p.metrics.cpuPct.p95)} | ${number(p.heartbeatWindowMaxMs.max)} | ${number(p.captureCostMs.p95)} |`);
  }
  lines.push('', '## Compared with baseline', '', '| Phase | CPU median delta pp | Heartbeat peak delta ms | Capture p95 delta ms | Schedule p95 delta ms |', '| --- | ---: | ---: | ---: | ---: |');
  for (const p of report.comparisons) lines.push(`| ${p.phase} | ${number(p.medianDeltas.cpuPct)} | ${number(p.heartbeatPeakDeltaMs)} | ${number(p.captureP95DeltaMs)} | ${number(p.schedulingP95DeltaMs)} |`);
  lines.push('', 'Steady comparisons require sufficient samples on both sides.', '', '| Phase | Available | Steady CPU median delta pp | Steady heartbeat peak delta ms |', '| --- | --- | ---: | ---: |');
  for (const p of report.comparisons) lines.push(`| ${p.phase} | ${p.steady.available} | ${number(p.steady.medianDeltas.cpuPct)} | ${number(p.steady.heartbeatPeakDeltaMs)} |`);
  lines.push('', '## Ranked signals', '');
  if (!report.candidates.length) lines.push('No strong signal met these screening thresholds. This is not a clear-system verdict.');
  for (const c of report.candidates) lines.push(`${c.rank}. **${cell(c.category)} — ${cell(c.phase)} (${c.scope}):** ${c.evidence} ${c.limit}`);
  lines.push('', '## Selected processes by CPU', '', `Process CPU basis: ${report.metadata.processCpuPctBasis}; logical CPUs: ${report.metadata.logicalProcessorCount ?? 'unavailable'}.`, '',
    '| Phase | PID / name | Started UTC | Raw CPU peak % | Total-capacity CPU peak % | Read / write peak MB/s |', '| --- | --- | --- | ---: | ---: | ---: |');
  for (const p of report.topProcessesByCpu.slice(0, 10)) lines.push(`| ${cell(p.phase)} | ${p.pid} / ${cell(p.name)} | ${p.startedUtc ?? 'unavailable'} | ${number(p.cpuPct.max)} | ${number(p.totalCapacityCpuPct.max)} | ${number(p.readMBps.max)} / ${number(p.writeMBps.max)} |`);
  lines.push('', '## Per-phase selected process CPU ranks', '', 'Steady medians use observed selected samples; missing process samples are not treated as zero.', '',
    '| Phase | Rank | PID / name | Steady total CPU median % | Observed / steady phase samples |', '| --- | ---: | --- | ---: | ---: |');
  for (const [phase, processes] of Object.entries(report.perPhaseProcessCpuRanks)) {
    for (const process of processes.slice(0, 5)) lines.push(`| ${cell(phase)} | ${process.rank} | ${process.pid} / ${cell(process.name)} | ${number(process.steady.totalCapacityCpuPct.median)} | ${process.steady.totalCapacityCpuPct.observed} / ${process.steady.phaseSamples} |`);
  }
  lines.push('', '## Selected processes by I/O', '', '| Phase | PID / name | Read peak MB/s | Write peak MB/s |', '| --- | --- | ---: | ---: |');
  for (const p of report.topProcessesByIo.slice(0, 10)) lines.push(`| ${cell(p.phase)} | ${p.pid} / ${cell(p.name)} | ${number(p.readMBps.max)} | ${number(p.writeMBps.max)} |`);
  lines.push('', '## Physical GPU engines', '', 'PID shares are combined only within the same physical engine. Different engines/GPUs are not added.', '', '| Phase | Engine | Median % | Peak % |', '| --- | --- | ---: | ---: |');
  for (const p of report.topPhysicalGpuEngines.slice(0, 10)) lines.push(`| ${cell(p.phase)} | ${cell(p.engine)} | ${number(p.pct.median)} | ${number(p.pct.max)} |`);
  lines.push('', '## GPU contributors', '', '| Phase | PID / engine | Median % | Peak % |', '| --- | --- | ---: | ---: |');
  for (const p of report.topGpuEngines.slice(0, 10)) lines.push(`| ${cell(p.phase)} | ${p.pid} / ${cell(p.engine)} | ${number(p.pct.median)} | ${number(p.pct.max)} |`);
  lines.push('', '## Capture quality', '');
  for (const issue of report.quality.issues) lines.push(`- ${cell(issue)}`);
  for (const notice of report.quality.notices.slice(0, 30)) lines.push(`- ${cell(notice)}`);
  lines.push('', ...report.interpretation.map((text) => `- ${text}`), '');
  return lines.join('\n');
}

async function main(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    if (!['--input', '--json', '--markdown'].includes(args[index]) || !args[index + 1] || options[args[index]]) {
      throw new Error('Usage: node analyze.mjs --input capture.ndjson --json report.json --markdown report.md');
    }
    options[args[index]] = path.resolve(args[index + 1]);
  }
  const pathKeys = Object.values(options).map((value) => process.platform === 'win32' ? value.toLowerCase() : value);
  if (Object.keys(options).length !== 3 || new Set(pathKeys).size !== 3) throw new Error('Input, JSON output and Markdown output must be three distinct paths');
  const report = analyze(parseNdjson(await fs.readFile(options['--input'], 'utf8')));
  for (const key of ['--json', '--markdown']) {
    await fs.mkdir(path.dirname(options[key]), { recursive: true });
    try { await fs.access(options[key]); throw new Error(`Output already exists: ${options[key]}`); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const handles = [];
  try {
    handles.push(await fs.open(options['--json'], 'wx'));
    handles.push(await fs.open(options['--markdown'], 'wx'));
    await handles[0].writeFile(JSON.stringify(report, null, 2) + '\n');
    await handles[1].writeFile(markdown(report));
  } finally { await Promise.all(handles.map((handle) => handle.close())); }
  process.stdout.write(`${report.status}: ${options['--json']}\n${options['--markdown']}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
