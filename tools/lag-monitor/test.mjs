import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { analyze, EXPECTED_PHASES, markdown, parseNdjson } from './analyze.mjs';

const epoch = Date.parse('2026-10-18T10:00:00Z');
const stamp = (milliseconds) => new Date(epoch + milliseconds).toISOString();
function capture(change = () => {}, samplesPerPhase = 3) {
  const rows = [{ kind: 'metadata', schema: 1, logicalProcessorCount: 4,
    processCpuPctBasis: 'total-capacity', sampleIntervalMs: 1000 }];
  const phaseStride = Math.max(5000, (samplesPerPhase + 1) * 1000);
  for (const [phaseIndex, phase] of EXPECTED_PHASES.entries()) {
    rows.push({ kind: 'phase', schema: 1, phase, utc: stamp(phaseIndex * phaseStride), elapsedMs: phaseIndex * phaseStride });
    for (let index = 0; index < samplesPerPhase; index++) {
      const elapsedMs = phaseIndex * phaseStride + index * 1000;
      const row = { kind: 'sample', schema: 1, phase, utc: stamp(elapsedMs), elapsedMs, sampleCostMs: 5,
        metrics: { cpuPct: 8, cpuMaxCorePct: 20, privilegedPct: 1, dpcPct: 0, dpcMaxCorePct: 0,
          interruptPct: 0, interruptMaxCorePct: 0, availableMB: 12000, pagesInPerSec: 0,
          diskQueue: 0, diskReadMs: 0, diskWriteMs: 0 },
        gpu: [], gpuStatus: 'ok', gpuTruncated: false,
        processes: [{ pid: 100, name: 'java', startedUtc: stamp(-10000), cpuPct: 5,
          workingSetMB: 500, privateMB: 700, readMBps: 0, writeMBps: 0 }],
        heartbeat: { maxDelayMs: 10, p95DelayMs: 8 }, errors: [] };
      change(row, index, phase); rows.push(row);
    }
  }
  rows.push({ kind: 'end', schema: 1, utc: stamp(EXPECTED_PHASES.length * phaseStride), elapsedMs: EXPECTED_PHASES.length * phaseStride, complete: true });
  return rows;
}

test('complete capture distinguishes measured zero from unavailable metrics', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'host') row.metrics.dpcPct = null;
  }));
  assert.equal(report.status, 'EVIDENCE_AVAILABLE');
  assert.equal(report.phases.baseline.metrics.dpcPct.median, 0);
  assert.equal(report.phases.baseline.metrics.dpcPct.zeroCount, 3);
  assert.equal(report.phases.host.metrics.dpcPct.median, null);
  assert.equal(report.phases.host.metrics.dpcPct.observed, 0);
  assert.equal(report.phases.host.metrics.dpcPct.unavailable, 3);
  assert.equal(report.comparisons.find((phase) => phase.phase === 'host').medianDeltas.dpcPct, null);
  assert.equal(report.phases.baseline.gpuPeakEnginePct.max, 0);
});

test('PID reuse is separated by process start, and wall-clock reversal does not distort monotonic gaps', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'both') row.processes[0].startedUtc = stamp(5000);
    if (phase === 'host' && index === 1) row.utc = stamp(1000);
  }));
  assert.equal(report.quality.pidReuse.length, 1);
  assert.equal(report.quality.pidReuse[0].pid, 100);
  assert.equal(report.quality.pidReuse[0].identities.length, 2);
  assert.ok(report.quality.notices.some((notice) => /Wall-clock timestamp reversed/.test(notice)));
  assert.equal(report.phases.host.schedulingDelayMs.max, 0);
  assert.equal(new Set(report.topProcessesByCpu.map((process) => process.identity)).size, 2);
});

test('one-core process percentages can exceed 100 and are normalized only with explicit metadata', () => {
  const rows = capture((row) => { row.processes[0].cpuPct = 400; });
  rows[0].processCpuPctBasis = 'one-core';
  let report = analyze(rows);
  assert.equal(report.topProcessesByCpu[0].cpuPct.max, 400);
  assert.equal(report.topProcessesByCpu[0].totalCapacityCpuPct.max, 100);
  delete rows[0].processCpuPctBasis;
  report = analyze(rows);
  assert.equal(report.topProcessesByCpu[0].cpuPct.max, 400);
  assert.equal(report.topProcessesByCpu[0].totalCapacityCpuPct.max, null);
  assert.ok(report.quality.notices.some((notice) => /basis unavailable/.test(notice)));
  assert.ok(!report.candidates.some((candidate) => candidate.category === 'cpu_pressure'),
    'process counter units do not manufacture global CPU saturation');
});

test('capture cost is independent of heartbeat and not automatically called a machine stall', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'host') row.sampleCostMs = 1500;
  }));
  assert.equal(report.phases.host.captureCostMs.p95, 1500);
  assert.equal(report.phases.host.heartbeatWindowMaxMs.max, 10);
  assert.ok(report.candidates.some((candidate) => candidate.category === 'capture_overhead' && candidate.phase === 'host'));
  assert.ok(!report.candidates.some((candidate) => candidate.category === 'responsiveness_latency'));
  assert.match(report.candidates.find((candidate) => candidate.category === 'capture_overhead').limit, /not evidence of a system-wide stall/);
});

test('GPU shares of one physical engine add, concurrent distinct engines and GPUs do not', () => {
  const report = analyze(capture((row) => {
    row.gpu = [
      { pid: 100, engine: 'pid_100_luid_A_phys_0_eng_0_engtype_3D', pct: 50 },
      { pid: 200, engine: 'pid_200_luid_A_phys_0_eng_0_engtype_3D', pct: 45 },
      { pid: 100, engine: 'pid_100_luid_A_phys_0_eng_1_engtype_Copy', pct: 80 },
      { pid: 100, engine: 'pid_100_luid_B_phys_0_eng_0_engtype_3D', pct: 75 }
    ];
  }));
  assert.equal(report.phases.both.gpuPeakEnginePct.max, 95);
  assert.equal(report.topPhysicalGpuEngines[0].pct.max, 95);
  assert.equal(report.topGpuEngines[0].pct.max, 80);
  assert.ok(report.topPhysicalGpuEngines.every((engine) => engine.pct.max <= 95));
});

test('GPU unavailability and truncation are explicit and never displayed as zero-idle evidence', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'host') { row.gpuStatus = 'unavailable'; row.gpu = []; }
    if (phase === 'both') row.gpuTruncated = true;
  }));
  assert.equal(report.phases.host.gpuPeakEnginePct.max, null);
  assert.equal(report.phases.host.gpuUnavailableSamples, 3);
  assert.equal(report.phases.both.gpuTruncatedSamples, 3);
  assert.ok(report.quality.notices.some((notice) => /GPU counters unavailable/.test(notice)));
  assert.ok(report.quality.notices.some((notice) => /GPU engine table truncated/.test(notice)));
});

test('missing phases, malformed JSON, incomplete capture and counter errors remain inconclusive', () => {
  const rows = capture().filter((row) => row.phase !== 'after' && row.kind !== 'end');
  rows.find((row) => row.kind === 'sample').errors.push('PDH counter unavailable');
  const parsed = parseNdjson(rows.map((row) => JSON.stringify(row)).join('\n') + '\n{"kind":"sample"');
  const report = analyze(parsed);
  assert.equal(report.status, 'INCONCLUSIVE');
  assert.ok(report.quality.missingPhases.includes('after'));
  assert.ok(report.quality.issues.some((issue) => /Unparsed JSON/.test(issue)));
  assert.ok(report.quality.issues.some((issue) => /successful end marker/.test(issue)));
  assert.ok(report.quality.issues.some((issue) => /PDH counter/.test(issue)));
  assert.match(markdown(report), /INCONCLUSIVE/);
});

test('low CPU and high both-client latency produces a phase delta with unresolved driver cause', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'both') {
      row.metrics.cpuPct = 12;
      row.heartbeat = { maxDelayMs: 350, p95DelayMs: 180 };
    }
  }));
  const comparison = report.comparisons.find((phase) => phase.phase === 'both');
  assert.equal(comparison.medianDeltas.cpuPct, 4);
  assert.equal(comparison.heartbeatPeakDeltaMs, 340);
  const candidate = report.candidates.find((item) => item.category === 'responsiveness_latency' && item.phase === 'both');
  assert.ok(candidate);
  assert.match(candidate.limit, /no driver cause is established/);
  assert.ok(!report.candidates.some((item) => item.category === 'cpu_pressure'));
  assert.ok(report.comparisons.some((phase) => phase.phase === 'host_after'));
  assert.ok(report.comparisons.some((phase) => phase.phase === 'after'));
});

test('declared scheduling cadence distinguishes sample-loop gaps from heartbeat delay', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'host') { row.elapsedMs += index * 2000; row.utc = stamp(row.elapsedMs); }
  }));
  assert.equal(report.phases.host.sampleGapMs.median, 3000);
  assert.equal(report.phases.host.schedulingDelayMs.p95, 2000);
  assert.equal(report.phases.host.heartbeatWindowMaxMs.max, 10);
});

test('missing process start times are reported, missing heartbeat/counters cannot become clean evidence', () => {
  const report = analyze(capture((row) => { row.processes[0].startedUtc = null; row.metrics = {}; row.heartbeat = {}; }));
  assert.equal(report.status, 'INCONCLUSIVE');
  assert.ok(report.quality.notices.some((notice) => /same-name PID reuse cannot be distinguished/.test(notice)));
  assert.ok(report.quality.issues.some((issue) => /independent heartbeat unavailable/.test(issue)));
  assert.equal(report.phases.baseline.metrics.cpuPct.max, null);
});

test('startup-only CPU pressure stays visible while sustained steady CPU pressure ranks first', () => {
  const rows = capture((row, index, phase) => {
    if (phase === 'host' && index < 3) { row.metrics.cpuPct = 100; row.metrics.cpuMaxCorePct = 100; }
    if (phase === 'both') { row.metrics.cpuPct = 96; row.metrics.cpuMaxCorePct = 100; }
  }, 7);
  rows[0].phaseSettlingMs = 3000;
  const report = analyze(rows);
  assert.equal(report.phases.host.metrics.cpuPct.max, 100);
  assert.equal(report.phases.host.steady.metrics.cpuPct.max, 8);
  assert.equal(report.phases.host.steady.sampleCount, 4);
  assert.equal(report.phases.host.steady.excludedSampleCount, 3);
  const startup = report.candidates.find((candidate) => candidate.category === 'cpu_pressure' && candidate.phase === 'host');
  const sustained = report.candidates.find((candidate) => candidate.category === 'cpu_pressure' && candidate.phase === 'both');
  assert.equal(startup.scope, 'startup-only');
  assert.equal(sustained.scope, 'steady');
  assert.ok(sustained.rank < startup.rank);
  assert.equal(report.comparisons.find((phase) => phase.phase === 'host').steady.medianDeltas.cpuPct, 0);
  assert.equal(report.comparisons.find((phase) => phase.phase === 'both').steady.medianDeltas.cpuPct, 88);
  assert.match(markdown(report), /startup-only/);
});

test('short phases expose unavailable steady evidence and fall back to first sample without a phase marker', () => {
  const rows = capture((row, index, phase) => { if (phase === 'host') row.metrics.cpuPct = 100; })
    .filter((row) => row.kind !== 'phase');
  const report = analyze(rows);
  assert.equal(report.metadata.phaseSettlingMs, 3000);
  assert.equal(report.phases.host.phaseStartMs, 5000);
  assert.equal(report.phases.host.steady.status, 'INSUFFICIENT');
  assert.equal(report.phases.host.steady.sampleCount, 0);
  assert.equal(report.phases.host.steady.metrics.cpuPct.median, null);
  assert.equal(report.comparisons.find((phase) => phase.phase === 'host').steady.available, false);
  assert.equal(report.comparisons.find((phase) => phase.phase === 'host').steady.medianDeltas.cpuPct, null);
  assert.equal(report.candidates.find((candidate) => candidate.category === 'cpu_pressure').scope, 'insufficient-steady');
  assert.ok(report.quality.notices.some((notice) => /steady behavior is inconclusive/.test(notice)));
  assert.match(markdown(report), /INSUFFICIENT/);
});

test('ordinary page-in with plentiful free memory is retained without a memory-pressure claim', () => {
  const report = analyze(capture((row, index, phase) => {
    row.metrics.availableMB = 84 * 1024;
    row.metrics.pagesInPerSec = phase === 'baseline' ? 400 : 30;
  }, 7));
  assert.equal(report.phases.baseline.steady.metrics.pagesInPerSec.p95, 400);
  assert.equal(report.phases.both.steady.metrics.availableMB.min, 84 * 1024);
  assert.ok(!report.candidates.some((candidate) => candidate.category === 'memory_pressure'));
});

test('memory pressure requires both low available memory and paging or commit pressure', () => {
  const report = analyze(capture((row, index, phase) => {
    if (phase === 'host') { row.metrics.availableMB = 256; row.metrics.pagesInPerSec = 400; }
    if (phase === 'both') { row.metrics.availableMB = 512; row.metrics.commitPct = 95; }
    if (phase === 'host_after') row.metrics.availableMB = 256;
    if (phase === 'after') row.metrics.commitPct = 99;
  }, 7));
  const candidates = report.candidates.filter((candidate) => candidate.category === 'memory_pressure');
  assert.deepEqual(candidates.map((candidate) => candidate.phase).sort(), ['both', 'host']);
  assert.ok(candidates.every((candidate) => candidate.scope === 'steady'));
  assert.match(candidates[0].limit, /do not prove thrashing/);
});

test('steady DWM growth ranks phase correlation with low GPU use without claiming its cause', () => {
  const dwmCpu = { baseline: 1.02, host: 4.1, both: 5.97, host_after: 3.15, after: 0.86 };
  const rows = capture((row, index, phase) => {
    row.processes.push({ pid: 200, name: 'dwm', startedUtc: stamp(-20000), cpuPct: dwmCpu[phase],
      workingSetMB: 120, privateMB: 200, readMBps: 0, writeMBps: 0 });
    row.gpu = [{ pid: 200, engine: 'pid_200_luid_A_phys_0_eng_0_engtype_3D', pct: 23 }];
    row.heartbeat = { maxDelayMs: 13, p95DelayMs: 10 };
  }, 7);
  rows[0].logicalProcessorCount = 16;
  const report = analyze(rows);
  const compositor = report.candidates.filter((candidate) => candidate.category === 'compositor_activity');
  assert.deepEqual(compositor.map((candidate) => candidate.phase), ['both', 'host', 'host_after']);
  assert.ok(compositor.every((candidate) => candidate.scope === 'steady'));
  assert.match(compositor[0].evidence, /0\.96 logical-core equivalents across 16 logical CPUs/);
  assert.match(compositor[0].limit, /phase-correlated.*not proof.*or a saturated single thread/);
  assert.match(compositor[0].limit, /no exact graphics driver or hook/);
  assert.ok(!report.candidates.some((candidate) => candidate.category === 'gpu_engine_activity'));
  assert.ok(!report.candidates.some((candidate) => candidate.category === 'responsiveness_latency'));
  const rank = report.perPhaseProcessCpuRanks.both.find((process) => process.pid === 200);
  assert.equal(rank.rank, 1);
  assert.equal(rank.steady.totalCapacityCpuPct.median, 5.97);
  assert.equal(rank.steady.totalCapacityCpuPct.observed, 4);
  assert.equal(rank.steady.phaseSamples, 4);
  assert.match(markdown(report), /Per-phase selected process CPU ranks/);
  assert.match(markdown(report), /compositor_activity/);
});

test('DWM candidate requires relative and absolute increases with sufficient steady observations', () => {
  const dwmCpu = { baseline: 1, host: 2.99, both: 2.5, host_after: 4, after: 4 };
  const rows = capture((row, index, phase) => {
    if (phase === 'host_after' && index >= 4) return;
    row.processes.push({ pid: 200, name: 'dwm.exe', startedUtc: stamp(-20000), cpuPct: dwmCpu[phase],
      workingSetMB: 120, privateMB: 200, readMBps: 0, writeMBps: 0 });
  }, 7);
  const report = analyze(rows);
  assert.deepEqual(report.candidates.filter((candidate) => candidate.category === 'compositor_activity')
    .map((candidate) => candidate.phase), ['after']);
  rows[0].processCpuPctBasis = 'unknown';
  assert.ok(!analyze(rows).candidates.some((candidate) => candidate.category === 'compositor_activity'),
    'raw CPU with unknown units cannot establish compositor total-capacity growth');
});

test('CLI writes separate JSON/Markdown exclusively and preserves existing output', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'coop-lag-analyzer-'));
  try {
    const input = path.join(directory, 'capture.ndjson');
    const json = path.join(directory, 'report.json');
    const md = path.join(directory, 'report.md');
    await fs.writeFile(input, capture().map((row) => JSON.stringify(row)).join('\n') + '\n');
    const script = fileURLToPath(new URL('./analyze.mjs', import.meta.url));
    const args = [script, '--input', input, '--json', json, '--markdown', md];
    const first = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(first.status, 0, first.stderr);
    assert.equal(JSON.parse(await fs.readFile(json, 'utf8')).status, 'EVIDENCE_AVAILABLE');
    const original = await fs.readFile(md, 'utf8');
    assert.match(original, /Compared with baseline/);
    const second = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.notEqual(second.status, 0);
    assert.match(second.stderr, /already exists/);
    assert.equal(await fs.readFile(md, 'utf8'), original);
    const collision = spawnSync(process.execPath, [script, '--input', input, '--json', input, '--markdown', md], { encoding: 'utf8' });
    assert.notEqual(collision.status, 0);
    assert.match(collision.stderr, /distinct paths/);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
