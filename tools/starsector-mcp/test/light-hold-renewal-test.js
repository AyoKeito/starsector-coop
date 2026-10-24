import assert from 'node:assert/strict';
import test from 'node:test';
import {LightHoldRenewal, assertHostRequestUnsettled} from '../lib/light-hold-renewal.js';

const sessionId = '11111111-1111-4111-8111-111111111111';
const lightId = 'light:22222222-2222-4222-8222-222222222222';
const requestId = '33333333-3333-4333-8333-333333333333';
const firstArmId = '44444444-4444-4444-8444-444444444444';
const nextArmId = '55555555-5555-4555-8555-555555555555';
const epochStart = 1_000_000;
const encode = values => values.map(value => String(value).length + ':' + value).join('');
const initialArm = () => ({stage: 'consumption_request', action: 'DWELLER', lightId, sessionId,
  armId: firstArmId, armedAtMillis: epochStart, deadlineMillis: epochStart + 600_000,
  actorId: 'host', remoteActorId: 'guest', connectionGeneration: 4, sectorIdentity: 'sector', ownerIdentity: 'owner'});
const heldArm = arm => ({...arm, messageType: 'LIGHT_REQUEST', latchedIdentity: requestId,
  messageSeq: 23, messageSentAtMillis: epochStart + 1_000});
const status = (arm, held = true) => ({sessionActive: true, sessionId, pause: {eitherInCombat: true},
  lightMessageHold: {armed: true, held, arm: held ? heldArm(arm) : arm}});
const battle = () => {
  const id = sessionId + '-24';
  return {id, beginHost: {id, line: 'Coop BATTLE_BEGIN received battleId=' + id + ' enemy=Lesser Manifestation'},
    beginGuest: {id, line: 'Coop BATTLE_BEGIN sent battleId=' + id + ' enemy=Lesser Manifestation'}};
};

function fixture({afterRows = ['Aprevious'], afterStatus, preBattle = battle(), preStatus,
  mutate, latch = true} = {}) {
  let now = epochStart + 300_000;
  const controller = new LightHoldRenewal({lightId, sessionId, initialArm: initialArm(), now: epochStart});
  if (latch) controller.observe(status(initialArm()), epochStart + 2_000);
  const nextArm = {...initialArm(), armId: nextArmId, armedAtMillis: now, deadlineMillis: now + 600_000};
  const calls = []; let reads = 0;
  const readHostFrame = async () => {
    calls.push('readHostFrame'); reads++;
    return reads === 1 ? {status: preStatus || status(initialArm()), hostRows: ['Aprevious'], battle: preBattle}
      : {status: afterStatus || status(nextArm, false), hostRows: afterRows, battle: preBattle};
  };
  const actHost = async (verb, args) => {
    calls.push({role: 'host', verb, args});
    if (mutate) return mutate(verb, args, nextArm);
    return args.mode === 'drop' ? {armed: false, held: false,
      lastReceipt: {...heldArm(initialArm()), reason: 'bridge_command'}}
      : {armed: true, held: false, arm: nextArm};
  };
  return {controller, calls, nextArm, readHostFrame, actHost, clock: () => now, setNow: value => { now = value; }};
}

test('separate audited renewal preserves original K and requires fresh current retry before fault', async () => {
  const f = fixture();
  const receipt = await f.controller.renew(f);
  assert.equal(receipt.phase, 'complete');
  assert.deepEqual(f.calls.map(call => typeof call === 'string' ? call : call.args.mode),
    ['readHostFrame', 'drop', 'arm', 'readHostFrame']);
  assert.deepEqual(f.calls.filter(call => typeof call !== 'string').map(call => call.role), ['host', 'host']);
  assert.equal(f.controller.originalRequest.armId, firstArmId);
  assert.equal(f.controller.originalRequest.messageSeq, 23);
  assert.equal(f.controller.currentArm.armId, nextArmId);
  assert.equal(f.controller.currentEnvelope, null);
  const retry = status(f.nextArm); retry.lightMessageHold.arm.messageSeq = 58;
  f.controller.observe(retry, f.clock() + 1_000);
  assert.equal(f.controller.currentEnvelope.requestId, requestId);
  assert.equal(f.controller.currentEnvelope.armId, nextArmId);
  assert.equal(f.controller.currentEnvelope.messageSeq, 58);
  assert.equal(f.controller.originalRequest.messageSeq, 23);
  assert.deepEqual(receipt.before.hostRows, receipt.after.hostRows);
});

test('a genuine held K and both real native BEGIN directions are mandatory before mutations', async () => {
  for (const options of [{latch: false}, {preBattle: null}, {preBattle: {...battle(), beginGuest: null}},
    {preBattle: {...battle(), endGuest: {id: battle().id, line: 'Coop BATTLE_END sent outcome=WIN'}}},
    {preStatus: {...status(initialArm()), pause: {eitherInCombat: false}}}]) {
    const f = fixture(options);
    await assert.rejects(f.controller.renew(f));
    assert.equal(f.calls.filter(call => typeof call !== 'string').length, 0);
  }
});

test('selected request settlement in the non-atomic gap aborts without any release or network fault', async () => {
  const row = 'R' + encode(['LIGHT_RESULT', '1', requestId, lightId, 'guest', 'DWELLER', 'true', '', '0.0', '0', 'accepted']);
  for (const afterRows of [['Aprevious', 'T' + lightId], ['Aprevious', row]]) {
    const f = fixture({afterRows});
    await assert.rejects(f.controller.renew(f), /already has a Host/);
    assert.equal(f.controller.renewals[0].phase, 'verify-gap-did-not-settle-request');
    assert(f.controller.renewals[0].error);
    assert(f.calls.filter(call => typeof call !== 'string').every(call => call.verb === 'lightmessagehold'
      && ['drop', 'arm'].includes(call.args.mode)));
  }
});

test('full Host ledger equality catches unrelated gap contamination too', async () => {
  const f = fixture({afterRows: ['Aprevious', 'Aunrelated']});
  await assert.rejects(f.controller.renew(f), /ledger changed across non-atomic/);
});

test('context changes before drop or after arm are terminal', async () => {
  const before = status(initialArm()); before.lightMessageHold.arm.ownerIdentity = 'replacement';
  const f = fixture({preStatus: before});
  await assert.rejects(f.controller.renew(f), /hold context changed/);
  assert.equal(f.calls.filter(call => typeof call !== 'string').length, 0);
  const next = {...initialArm(), armId: nextArmId, armedAtMillis: epochStart + 300_000,
    deadlineMillis: epochStart + 900_000};
  const after = status(next, false); after.sessionId = 'new-session';
  const g = fixture({afterStatus: after});
  await assert.rejects(g.controller.renew(g), /session\/context changed/);
  assert.equal(g.calls.filter(call => typeof call !== 'string').length, 2);
});

test('uncertain mutation outcomes are never retried', async () => {
  for (const failingMode of ['drop', 'arm']) {
    const f = fixture({mutate: async (verb, args) => {
      if (args.mode === failingMode) throw Object.assign(new Error('transport outcome unknown'), {name: 'BridgeOutcomeUnknownError'});
      return {armed: false, held: false, lastReceipt: {...heldArm(initialArm()), reason: 'bridge_command'}};
    }});
    await assert.rejects(f.controller.renew(f), /outcome unknown/);
    assert.equal(f.calls.filter(call => typeof call !== 'string').length, failingMode === 'drop' ? 1 : 2);
    assert.equal(f.controller.renewals[0].error.name, 'BridgeOutcomeUnknownError');
  }
});

test('fresh arm refuses a different K and original envelope cannot silently disappear', async () => {
  const f = fixture(); await f.controller.renew(f);
  const changed = status(f.nextArm); changed.lightMessageHold.arm.latchedIdentity = '66666666-6666-4666-8666-666666666666';
  assert.throws(() => f.controller.observe(changed, f.clock()), /different logical request/);
  const g = fixture();
  assert.throws(() => g.controller.observe(status(initialArm(), false), g.clock()), /disappeared/);
});

test('expiry, renewal threshold, overall wait and epoch count are bounded', async () => {
  const f = fixture(); f.setNow(epochStart + 200_000);
  await assert.rejects(f.controller.renew(f), /not due/);
  assert.equal(f.calls.filter(call => typeof call !== 'string').length, 0);
  const g = fixture(); g.setNow(epochStart + 600_000);
  await assert.rejects(g.controller.renew(g), /unexpired/);
  assert.throws(() => g.controller.observe(status(initialArm()), epochStart + 45 * 60_000), /unattended battle wait expired/);
  assert.throws(() => new LightHoldRenewal({lightId, sessionId, initialArm: initialArm(), now: epochStart,
    maxWaitMillis: 46 * 60_000}), /at most45minutes/);
});

test('pre-existing results are rejected by exact decoded K or light identity', () => {
  const row = 'R' + encode(['LIGHT_RESULT', '1', 'another', lightId, 'guest', 'DWELLER', 'true', '', '0', '0', 'accepted']);
  assert.throws(() => assertHostRequestUnsettled([row], requestId, lightId), /already has a Host result/);
  assertHostRequestUnsettled(['Aprevious'], requestId, lightId);
});

test('a long real battle can cross multiple unlatched epochs before the first postcombat retry', async () => {
  let now = epochStart + 300_000, live = status(initialArm()), epoch = 0;
  const controller = new LightHoldRenewal({lightId, sessionId, initialArm: initialArm(), now: epochStart, maxRenewals: 2});
  controller.observe(live, epochStart + 2_000);
  const ids = [nextArmId, '66666666-6666-4666-8666-666666666666'];
  const readHostFrame = async () => ({status: structuredClone(live), hostRows: ['Aprevious'], battle: battle()});
  const actHost = async (verb, args) => {
    assert.equal(verb, 'lightmessagehold');
    if (args.mode === 'drop') {
      const previous = live.lightMessageHold.arm;
      return {armed: false, held: false, lastReceipt: {...previous, reason: 'bridge_command'}};
    }
    const arm = {...initialArm(), armId: ids[epoch++], armedAtMillis: now, deadlineMillis: now + 600_000};
    live = status(arm, false); return structuredClone(live.lightMessageHold);
  };
  await controller.renew({readHostFrame, actHost, clock: () => now});
  assert.equal(controller.currentEnvelope, null);
  now += 300_000;
  await controller.renew({readHostFrame, actHost, clock: () => now});
  assert.equal(controller.renewals.length, 2);
  assert.equal(controller.originalRequest.messageSeq, 23);
  assert.equal(controller.currentEnvelope, null);
  const retry = status(live.lightMessageHold.arm); retry.lightMessageHold.arm.messageSeq = 99;
  controller.observe(retry, now + 1_000);
  assert.equal(controller.currentEnvelope.armId, ids[1]);
  assert.equal(controller.currentEnvelope.messageSeq, 99);
  assert.equal(controller.currentEnvelope.requestId, requestId);
  now += 300_000;
  await assert.rejects(controller.renew({readHostFrame, actHost, clock: () => now}), /renewal count exhausted/);
  assert.equal(epoch, 2, 'exhausted count prevents a third mutation epoch');
});
