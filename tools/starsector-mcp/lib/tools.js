// Instance registry, verb tables and the five MCP tool implementations.
// Kept free of the MCP SDK so the test suite can drive it against a mock bridge.

import { BridgeClient, DEFAULT_TIMEOUT_MS } from './bridge-client.js';
import { DEFAULT_IGNORE_KEYS, diffJson } from './diff.js';

/** Read-only verbs. ss_dump and ss_diff accept these. */
export const QUERY_VERBS = [
  'status',
  'fleets',
  'market',
  'markets',
  'barpool',
  'survey',
  'visibility',
  'colonizable'
];

/** State-changing verbs. ss_act accepts these. */
export const ACTION_VERBS = [
  'teleport',
  'pause',
  'ability',
  'setcr',
  'give',
  'objective',
  'surveyset',
  'expedition'
];

/**
 * Verbs the bridge deliberately does not implement, and the reason. Each of these
 * exists as a smoke check because a UI listener drives it; going in through the
 * bridge would exercise the engine call underneath the listener and pass while the
 * listener is broken.
 */
export const NON_VERBS = {
  buy: 'market buy/sell run through PlayerMarketTransaction; the bridge would skip the listener the check exists to verify',
  sell: 'market buy/sell run through PlayerMarketTransaction; the bridge would skip the listener the check exists to verify',
  hire: 'officer hire is verified through the close-diff claim path, which only a real dialog close produces',
  accept: 'bar-offer accept is verified through the dialog listener, not the offer object',
  open: 'market open/close drives snapshot-on-open; a bridge call would bypass the snapshot trigger',
  close: 'market open/close drives snapshot-on-open; a bridge call would bypass the snapshot trigger',
  save: 'save/load control is out of scope for the bridge',
  load: 'save/load control is out of scope for the bridge',
  screenshot: 'no screenshots, no vision, no input injection'
};

export const INSTANCES = ['host', 'guest'];

/** Milliseconds of clock timestamp per game day (CampaignClock divides the delta by 8.64E7). */
export const MS_PER_GAME_DAY = 86_400_000;

/** Wall-clock seconds one game day takes at normal speed (CampaignClock.SECONDS_PER_GAME_DAY). */
export const SECONDS_PER_GAME_DAY = 10;

export const ENV_VARS = {
  hostPort: 'STARSECTOR_MCP_HOST_PORT',
  guestPort: 'STARSECTOR_MCP_GUEST_PORT',
  address: 'STARSECTOR_MCP_ADDRESS',
  timeoutMs: 'STARSECTOR_MCP_TIMEOUT_MS'
};

export function resolveInstances(env = process.env) {
  const address = env[ENV_VARS.address] || '127.0.0.1';
  const timeoutMs = positiveInt(env[ENV_VARS.timeoutMs], DEFAULT_TIMEOUT_MS);
  const build = (instance, portEnv, defaultPort, script) => {
    const port = positiveInt(env[portEnv], defaultPort);
    return {
      instance,
      host: address,
      port,
      timeoutMs,
      hint:
        `Relaunch it with the bridge switch: ` +
        `powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\\Starsector\\mods\\coop\\scripts\\${script}' -Bridge ` +
        `(that appends -Dcoop.debug.bridge=${defaultPort}). Set ${portEnv} if the bridge listens somewhere else.`
    };
  };
  return {
    host: build('host', ENV_VARS.hostPort, 7801, 'launch-host.ps1'),
    guest: build('guest', ENV_VARS.guestPort, 7802, 'launch-guest.ps1')
  };
}

/** Lazily-created, cached BridgeClient per instance. */
export class Bridges {
  constructor(env = process.env) {
    this.config = resolveInstances(env);
    this.clients = new Map();
  }

  get(instance) {
    const config = this.config[instance];
    if (!config) {
      throw new Error(`unknown instance "${instance}"; expected one of: ${INSTANCES.join(', ')}`);
    }
    if (!this.clients.has(instance)) this.clients.set(instance, new BridgeClient(config));
    return this.clients.get(instance);
  }

  closeAll() {
    for (const client of this.clients.values()) client.close();
    this.clients.clear();
  }
}

export async function ssStatus(bridges, instance) {
  return bridges.get(instance).send('status');
}

export async function ssDump(bridges, instance, what, args) {
  assertVerb(what, QUERY_VERBS, 'query');
  return bridges.get(instance).send(what, args);
}

export async function ssAct(bridges, instance, verb, args) {
  assertVerb(verb, ACTION_VERBS, 'action');
  return bridges.get(instance).send(verb, args);
}

export { DEFAULT_IGNORE_KEYS };

export async function ssDiff(bridges, what, args, options = {}) {
  assertVerb(what, QUERY_VERBS, 'query');
  const [host, guest] = await Promise.all([
    bridges.get('host').send(what, args),
    bridges.get('guest').send(what, args)
  ]);
  return { what, args: args ?? {}, ...diffJson(host, guest, options) };
}

/**
 * Unpause the host, wait for its campaign clock to advance `days`, pause it again.
 * Orchestrated entirely here; the bridge contributes nothing beyond status + pause.
 * The guest follows the host clock, so only the host is driven.
 */
export async function ssAdvanceDays(bridges, days, options = {}) {
  const target = Number(days);
  if (!Number.isFinite(target) || target <= 0) throw new Error(`days must be a positive number, got: ${days}`);

  const pollMs = positiveInt(options.pollMs, 500);
  const timeoutMs = positiveInt(
    options.timeoutSeconds ? options.timeoutSeconds * 1000 : null,
    Math.min(900_000, Math.round(target * SECONDS_PER_GAME_DAY * 3 * 1000) + 20_000)
  );

  const host = bridges.get('host');
  const before = await host.send('status');
  const startTimestamp = clockTimestamp(before);
  const startDate = clockDate(before);

  await host.send('pause', pauseArgs(false));

  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let latest = before;
  let timedOut = false;
  let failure = null;

  try {
    for (;;) {
      latest = await host.send('status');
      if (elapsedDays(startTimestamp, latest) >= target) break;
      if (Date.now() >= deadline) {
        timedOut = true;
        break;
      }
      await sleep(pollMs);
    }
  } catch (err) {
    failure = err;
  }

  // Diagnosed BEFORE the re-pause, on purpose: pausing sets the host's own intent, which would then
  // be the answer to every stalled advance and would hide the one that actually held the clock.
  const stall = timedOut ? await diagnoseStall(bridges) : null;

  let repauseError = null;
  try {
    await host.send('pause', pauseArgs(true));
  } catch (err) {
    repauseError = err.message;
  }

  if (failure) {
    if (repauseError) failure.message += ` (the host also failed to re-pause: ${repauseError})`;
    throw failure;
  }

  const achievedDays = elapsedDays(startTimestamp, latest);
  return {
    requestedDays: target,
    achievedDays: Number(achievedDays.toFixed(4)),
    startDate,
    endDate: clockDate(latest),
    startTimestamp,
    endTimestamp: clockTimestamp(latest),
    elapsedRealSeconds: Number(((Date.now() - startedAt) / 1000).toFixed(1)),
    timedOut,
    repaused: repauseError === null,
    ...(stall ? { stall } : {}),
    ...(repauseError ? { repauseError } : {})
  };
}

/**
 * Which intent is holding the shared clock, for the case where an advance did not move it. Polls
 * `status` on both instances and reads the pause block the mod now reports; the answer is the first
 * true term of the host's OR, then either client's open screen, then `unknown` when nothing in the
 * two pause blocks explains the stall (which is itself the finding — it means the clock is being held
 * by something outside the coordinator).
 *
 * Never throws: an unreachable instance is recorded and the diagnosis carries on with what it has.
 * Replacing a useful timeout result with a connection error would lose the measurement.
 */
export async function diagnoseStall(bridges) {
  const pause = { host: null, guest: null };
  const unreachable = {};
  for (const instance of INSTANCES) {
    try {
      const status = await bridges.get(instance).send('status');
      pause[instance] = status?.pause ?? null;
    } catch (err) {
      unreachable[instance] = err.message;
    }
  }
  return {
    ...holderOf(pause),
    pause,
    ...(Object.keys(unreachable).length ? { unreachable } : {})
  };
}

function holderOf({ host, guest }) {
  const h = host ?? {};
  const g = guest ?? {};
  if (h.hostIntent) return { instance: 'host', reason: 'hostIntent' };
  if (h.guestScreenIntent) return { instance: 'guest', reason: 'guestScreenIntent' };
  if (h.guestKeyIntent) return { instance: 'guest', reason: 'guestKeyIntent' };
  if (h.guestIntent) return { instance: 'guest', reason: 'guestIntent' };
  if (h.eitherInCombat) return { instance: 'host', reason: 'eitherInCombat' };
  if (g.blockingScreenOpen) return { instance: 'guest', reason: 'blockingScreenOpen' };
  if (h.blockingScreenOpen) return { instance: 'host', reason: 'blockingScreenOpen' };
  return { instance: 'host', reason: 'unknown' };
}

// The bridge's pause handler reads {"on":true|false} (CoopAgentCommands.requiredPauseState).
function pauseArgs(on) {
  return { on };
}

function elapsedDays(startTimestamp, status) {
  return (clockTimestamp(status) - startTimestamp) / MS_PER_GAME_DAY;
}

function clockTimestamp(status) {
  const value = status?.clock?.timestamp ?? status?.timestamp ?? status?.clockTimestamp;
  if (!Number.isFinite(value)) {
    throw new Error(
      `host status carried no numeric clock timestamp; got keys [${Object.keys(status ?? {}).join(', ')}]`
    );
  }
  return value;
}

function clockDate(status) {
  return status?.clock?.date ?? status?.date ?? null;
}

function assertVerb(verb, allowed, kind) {
  if (allowed.includes(verb)) return;
  const reason = NON_VERBS[String(verb).toLowerCase()];
  if (reason) throw new Error(`"${verb}" is not a bridge verb by design: ${reason}. Run that check by hand.`);
  throw new Error(`unknown ${kind} verb "${verb}"; the bridge implements: ${allowed.join(', ')}`);
}

function positiveInt(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed) : fallback;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
