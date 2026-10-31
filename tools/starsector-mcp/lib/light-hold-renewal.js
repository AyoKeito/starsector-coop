import assert from 'node:assert/strict';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CONTEXT = ['sessionId', 'actorId', 'remoteActorId', 'connectionGeneration', 'sectorIdentity', 'ownerIdentity'];
const copy = value => structuredClone(value);

function fields(encoded) {
  const out = []; let offset = 0;
  while (offset < encoded.length) {
    const colon = encoded.indexOf(':', offset);
    assert(colon > offset && /^\d+$/.test(encoded.slice(offset, colon)), 'invalid result field length');
    const length = Number(encoded.slice(offset, colon)); offset = colon + 1;
    assert(Number.isSafeInteger(length) && offset + length <= encoded.length, 'truncated result field');
    out.push(encoded.slice(offset, offset + length)); offset += length;
  }
  return out;
}

export function assertHostRequestUnsettled(rows, requestId, lightId) {
  assert(Array.isArray(rows) && rows.every(row => typeof row === 'string')
    && new Set(rows).size === rows.length, 'full unique Host primitive ledger rows required');
  assert(!rows.includes('T' + lightId), 'selected light already has a Host tombstone');
  for (const row of rows.filter(row => row.startsWith('R'))) {
    const result = fields(row.slice(1));
    assert(result.length === 11 && result[0] === 'LIGHT_RESULT' && result[1] === '1', 'invalid Host result row');
    assert(result[2] !== requestId && result[3] !== lightId, 'selected request/light already has a Host result');
  }
}

export function assertNativeBattleInProgress(battle, sessionId) {
  assert(battle?.id?.startsWith(sessionId + '-'), 'matching canonical native battle BEGIN required before renewal');
  assert(battle.beginHost?.id === battle.id && battle.beginGuest?.id === battle.id
    && battle.beginHost.line.includes('Coop BATTLE_BEGIN received battleId=' + battle.id + ' ')
    && battle.beginGuest.line.includes('Coop BATTLE_BEGIN sent battleId=' + battle.id + ' '),
  'both native BEGIN directions required before renewal');
  assert(!battle.endHost && !battle.endGuest, 'native battle already ended; do not renew through teardown');
}

/**
 * Explicit bounded QA epochs, not atomic extension of an original envelope.
 * Callbacks run only from renew(); this module performs no I/O on import.
 */
export class LightHoldRenewal {
  constructor({lightId, sessionId, initialArm, now = Date.now(), maxWaitMillis = 45 * 60_000,
    renewAtRemainingMillis = 300_000, maxRenewals = 12}) {
    assert(/^light:/.test(lightId) && UUID.test(lightId.slice(6)), 'canonical light required');
    assert(UUID.test(sessionId), 'canonical session required');
    assert(Number.isSafeInteger(maxWaitMillis) && maxWaitMillis > 0 && maxWaitMillis <= 45 * 60_000,
      'unattended wait must be bounded to at most45minutes');
    assert(Number.isSafeInteger(renewAtRemainingMillis) && renewAtRemainingMillis >= 240_000
      && renewAtRemainingMillis <= 300_000, 'renewal threshold must be240–300seconds remaining');
    assert(Number.isSafeInteger(maxRenewals) && maxRenewals > 0 && maxRenewals <= 12, 'bounded renewal count required');
    this.lightId = lightId; this.sessionId = sessionId; this.startedAtMillis = now;
    this.waitDeadlineMillis = now + maxWaitMillis; this.renewAtRemainingMillis = renewAtRemainingMillis;
    this.maxRenewals = maxRenewals; this.initialArm = copy(initialArm); this.currentArm = copy(initialArm);
    this.originalRequest = null; this.currentEnvelope = null; this.renewals = [];
    this.#validateArm(initialArm, now);
  }

  #validateArm(arm, now) {
    assert(arm?.stage === 'consumption_request' && arm.action === 'DWELLER'
      && arm.lightId === this.lightId && arm.sessionId === this.sessionId && UUID.test(arm.armId),
    'same bounded DWELLER hold arm required');
    assert(CONTEXT.filter(key => key !== 'connectionGeneration').every(key => typeof arm[key] === 'string' && arm[key])
      && Number.isSafeInteger(arm.connectionGeneration) && arm.connectionGeneration >= 0, 'complete primitive hold context required');
    assert(Number.isSafeInteger(arm.armedAtMillis) && Number.isSafeInteger(arm.deadlineMillis)
      && arm.deadlineMillis > now && arm.deadlineMillis - arm.armedAtMillis > 0
      && arm.deadlineMillis - arm.armedAtMillis <= 600_000, 'hold epoch must be unexpired and bounded to600seconds');
    for (const key of CONTEXT) assert.equal(arm[key], this.initialArm[key], 'hold context changed: ' + key);
  }

  observe(status, now = Date.now()) {
    assert(now < this.waitDeadlineMillis, 'bounded unattended battle wait expired');
    assert(status?.sessionActive && status.sessionId === this.sessionId, 'Host session/context changed');
    const hold = status.lightMessageHold;
    assert(hold?.armed && hold.arm.armId === this.currentArm.armId, 'current hold expired, invalidated or replaced');
    this.#validateArm(hold.arm, now);
    assert.equal(hold.arm.deadlineMillis, this.currentArm.deadlineMillis, 'current hold deadline changed');
    if (hold.held) {
      assert(hold.arm.messageType === 'LIGHT_REQUEST' && UUID.test(hold.arm.latchedIdentity)
        && Number.isSafeInteger(hold.arm.messageSeq), 'real native request envelope required');
      const envelope = {armId: hold.arm.armId, messageSeq: hold.arm.messageSeq,
        requestId: hold.arm.latchedIdentity, messageSentAtMillis: hold.arm.messageSentAtMillis};
      if (!this.originalRequest) this.originalRequest = copy(envelope);
      assert.equal(envelope.requestId, this.originalRequest.requestId, 'current arm latched a different logical request');
      if (this.currentEnvelope) assert.deepEqual(envelope, this.currentEnvelope, 'held envelope changed within an epoch');
      this.currentEnvelope = envelope;
    } else assert(!this.currentEnvelope, 'captured envelope disappeared without a new epoch');
    return hold;
  }

  due(now = Date.now()) { return this.currentArm.deadlineMillis - now <= this.renewAtRemainingMillis; }

  snapshot() {
    return copy({startedAtMillis: this.startedAtMillis, waitDeadlineMillis: this.waitDeadlineMillis,
      renewAtRemainingMillis: this.renewAtRemainingMillis, maxRenewals: this.maxRenewals,
      initialArm: this.initialArm, originalRequest: this.originalRequest, currentArm: this.currentArm,
      currentEnvelope: this.currentEnvelope, renewals: this.renewals});
  }

  async renew({readHostFrame, actHost, clock = Date.now}) {
    const receipt = {startedAtMillis: clock(), previousArm: copy(this.currentArm), phase: 'preflight'};
    this.renewals.push(receipt);
    try {
      assert(this.renewals.length <= this.maxRenewals, 'bounded renewal count exhausted');
      assert(this.originalRequest, 'a genuine native K must be captured before any renewal');
      const before = await readHostFrame(); receipt.before = copy(before);
      this.observe(before.status, clock());
      assert(this.due(clock()), 'renewal is not due');
      assertNativeBattleInProgress(before.battle, this.sessionId);
      assert(before.status.pause?.eitherInCombat === true, 'renewal requires an actual active combat pause intent');
      assertHostRequestUnsettled(before.hostRows, this.originalRequest.requestId, this.lightId);
      receipt.phase = 'drop-old-epoch';
      receipt.drop = await actHost('lightmessagehold', {mode: 'drop'});
      assert(!receipt.drop.armed && !receipt.drop.held
        && receipt.drop.lastReceipt?.armId === this.currentArm.armId
        && receipt.drop.lastReceipt.reason === 'bridge_command', 'old epoch drop receipt mismatch');
      receipt.phase = 'arm-new-epoch';
      receipt.arm = await actHost('lightmessagehold', {mode: 'arm', stage: 'consumption_request',
        lightId: this.lightId, action: 'DWELLER', seconds: 600});
      assert(receipt.arm.armed && !receipt.arm.held, 'new epoch must start armed and unlatched');
      this.#validateArm(receipt.arm.arm, clock());
      assert.notEqual(receipt.arm.arm.armId, this.currentArm.armId, 'new explicit epoch must have a fresh armId');
      this.currentArm = copy(receipt.arm.arm); this.currentEnvelope = null;
      receipt.phase = 'verify-gap-did-not-settle-request';
      const after = await readHostFrame(); receipt.after = copy(after);
      this.observe(after.status, clock());
      assertHostRequestUnsettled(after.hostRows, this.originalRequest.requestId, this.lightId);
      assert.deepEqual([...after.hostRows].sort(), [...before.hostRows].sort(),
        'Host ledger changed across non-atomic drop/arm gap; abort without fault');
      receipt.phase = 'complete'; receipt.finishedAtMillis = clock();
      return receipt;
    } catch (error) {
      receipt.error = {name: error.name, message: error.message}; receipt.failedAtMillis = clock();
      throw error; // Never retry either mutation after an uncertain outcome.
    }
  }
}
