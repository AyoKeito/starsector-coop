import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { compareFocus, summarizeFocus } from './compare-focus.mjs';

const startedUtc = '2026-10-18T10:00:00Z';
function capture(change = () => {}) {
  const rows = [{ kind: 'metadata', logicalProcessorCount: 16, processCpuPctBasis: 'total-capacity' },
    { kind: 'operation', action: 'launch', role: 'host', elapsedMs: 0, launcherPid: 10, jvmPid: 11,
      owned: [{ pid: 10, startedUtc }, { pid: 11, startedUtc }] },
    { kind: 'modules', role: 'host', pid: 11, names: ['RTSSHooks64.dll', 'nvspcap64.dll'] }];
  for (let index = 0; index < 8; index++) {
    const row = { kind: 'sample', elapsedMs: index * 1000, phase: 'both', foregroundPid: 10,
      lastInputAgeMs: 50, sampleCostMs: 10, schedulingDelayMs: 0, errors: [],
      processes: [{ pid: 10, name: 'starsector', startedUtc, cpuPct: 0 },
        { pid: 20, name: 'dwm', startedUtc, cpuPct: 6 }], heartbeat: { maxDelayMs: 13, p95DelayMs: 10 } };
    change(row, index); rows.push(row);
  }
  rows.push({ kind: 'end', complete: true }); return rows;
}

test('focus comparison excludes transition rate samples and groups input recency', () => {
  const report = summarizeFocus(capture((row, index) => {
    if (index >= 4) row.foregroundPid = 11;
    if (index >= 6) row.lastInputAgeMs = 2000;
    if (index === 4) row.processes[1].cpuPct = 100;
  }));
  assert.equal(report.quality.stableSamples, 6);
  assert.equal(report.quality.droppedTransition, 2);
  assert.equal(report.groups.find((group) => group.input === 'recent').dwmTotalCapacityCpuPct.max, 6);
  assert.equal(report.groups.find((group) => group.input === 'not_recent').sampleCount, 2);
});

test('phase boundaries and unavailable focus reset interval attribution', () => {
  const report = summarizeFocus(capture((row, index) => {
    if (index >= 4) row.phase = 'host_after';
    if (index === 6) row.foregroundPid = 0;
  }));
  assert.equal(report.quality.droppedTransition, 3);
  assert.equal(report.quality.droppedUnavailable, 1);
  assert.equal(report.quality.stableSamples, 4);
});

test('owned role follows launch identity and rejects a reused PID', () => {
  const report = summarizeFocus(capture((row, index) => {
    if (index >= 4) row.processes[0].startedUtc = '2026-10-18T11:00:00Z';
  }));
  assert.equal(report.groups.find((group) => group.foregroundRole === 'host').sampleCount, 3);
  assert.equal(report.groups.find((group) => group.foregroundRole === 'unresolved').sampleCount, 4);
  assert.match(report.quality.notices.join(' '), /start time differs/);
});

test('CPU conversion preserves one-core units and missing DWM is unavailable', () => {
  const rows = capture((row) => { row.processes[1].cpuPct = 160; });
  rows[0].processCpuPctBasis = 'one-core';
  assert.equal(summarizeFocus(rows).groups[0].dwmTotalCapacityCpuPct.median, 10);
  delete rows[0].processCpuPctBasis;
  assert.equal(summarizeFocus(rows).groups[0].dwmTotalCapacityCpuPct.observed, 0);
  const missing = summarizeFocus(capture((row) => { row.processes.pop(); }));
  assert.equal(missing.groups[0].dwmTotalCapacityCpuPct.median, null);
  assert.equal(missing.groups[0].dwmTotalCapacityCpuPct.unavailable, 7);
});

test('module inventory and matched-group differences report observations without cause', () => {
  const before = capture(), after = capture((row) => { row.processes[1].cpuPct = 3; });
  after.find((row) => row.kind === 'modules').names = ['nvspcap64.dll'];
  const report = compareFocus(before, after);
  assert.equal(report.comparisons[0].status, 'MATCHED_GROUP');
  assert.equal(report.comparisons[0].dwmMedianDeltaPp, -3);
  assert.equal(report.comparisons[0].heartbeatPeakDeltaMs, 0);
  assert.equal(report.before.moduleInventory[0].modules['RTSSHooks64.dll'].status, 'observed_present');
  assert.equal(report.after.moduleInventory[0].modules['RTSSHooks64.dll'].status, 'not_observed');
  assert.match(report.interpretation.join(' '), /do not establish a cause/);
  const absent = summarizeFocus(before.filter((row) => row.kind !== 'modules'));
  assert.equal(absent.moduleInventory[0].modules['nvspcap64.dll'].status, 'unavailable');
});

test('incomplete capture, parse error, unavailable input and unmatched groups cannot imply improvement', () => {
  const before = capture(), after = capture((row) => { row.lastInputAgeMs = null; });
  after.pop(); after.push({ kind: 'parse_error', line: 99 });
  const report = compareFocus(before, after);
  assert.equal(report.status, 'INCONCLUSIVE');
  assert.ok(report.comparisons.every((group) => group.status === 'INSUFFICIENT_MATCHED_SAMPLES'));
  assert.ok(report.comparisons.every((group) => group.dwmMedianDeltaPp === null));
  assert.equal(report.after.groups[0].input, 'unavailable');
  assert.match(report.after.quality.issues.join(' '), /Unparsed JSON.*No successful end marker/);
});

test('CLI keeps both captures intact and refuses to overwrite a comparison', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'coop-lag-focus-'));
  try {
    const before = path.join(directory, 'before.ndjson'), after = path.join(directory, 'after.ndjson');
    const output = path.join(directory, 'comparison.json');
    const raw = capture().map((row) => JSON.stringify(row)).join('\n');
    await Promise.all([fs.writeFile(before, raw), fs.writeFile(after, raw)]);
    const script = fileURLToPath(new URL('./compare-focus.mjs', import.meta.url));
    const args = [script, '--before', before, '--after', after, '--output', output];
    const first = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(first.status, 0, first.stderr);
    const original = await fs.readFile(output, 'utf8');
    assert.equal(JSON.parse(original).comparisons[0].dwmMedianDeltaPp, 0);
    assert.notEqual(spawnSync(process.execPath, args, { encoding: 'utf8' }).status, 0);
    assert.equal(await fs.readFile(output, 'utf8'), original);
    const collision = spawnSync(process.execPath,
      [script, '--before', before, '--after', after, '--output', before], { encoding: 'utf8' });
    assert.notEqual(collision.status, 0);
    assert.equal(await fs.readFile(before, 'utf8'), raw);
    assert.equal(await fs.readFile(after, 'utf8'), raw);
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
