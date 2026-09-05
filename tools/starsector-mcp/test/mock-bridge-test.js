// Offline test suite: a mock CoopAgentBridge (plain net server speaking the
// newline-JSON protocol) plus assertions over the TCP client and the structural diff.
//
// Nothing here needs Starsector running. Run with: npm test

import assert from 'node:assert/strict';
import net from 'node:net';
import test from 'node:test';

import {
  BridgeClient,
  BridgeError,
  BridgeOutcomeUnknownError,
  BridgeTimeoutError,
  BridgeUnreachableError,
  READ_ONLY_COMMANDS
} from '../lib/bridge-client.js';
import { DEFAULT_IGNORE_KEYS, diffJson, leafCount, pickKeyField } from '../lib/diff.js';
import { Bridges, ssAct, ssAdvanceDays, ssDiff, ssDump, MS_PER_GAME_DAY } from '../lib/tools.js';

// --------------------------------------------------------------------------
// Mock bridge
// --------------------------------------------------------------------------

/**
 * Speaks the bridge protocol over TCP. `handle(request)` returns one of:
 *   {ok: true, data}        normal answer
 *   {ok: false, error}      the mod's error response
 *   {drop: true}            destroy the socket without answering (mid-request drop)
 *   {silent: true}          read the request, never answer (timeout path)
 * Add `delayMs` to any answer to make responses arrive out of request order.
 */
class MockBridge {
  constructor(handle) {
    this.handle = handle;
    this.connections = 0;
    this.requests = [];
    this.port = 0;
    this._sockets = new Set();
    this._server = net.createServer((socket) => this._onConnection(socket));
  }

  async start() {
    await new Promise((resolve) => this._server.listen(0, '127.0.0.1', resolve));
    this.port = this._server.address().port;
    return this.port;
  }

  async stop() {
    for (const socket of this._sockets) socket.destroy();
    this._sockets.clear();
    await new Promise((resolve) => this._server.close(resolve));
  }

  _onConnection(socket) {
    this.connections++;
    this._sockets.add(socket);
    socket.setEncoding('utf8');
    socket.on('error', () => {});
    socket.on('close', () => this._sockets.delete(socket));

    let buffer = '';
    socket.on('data', (chunk) => {
      buffer += chunk;
      let newline = buffer.indexOf('\n');
      while (newline >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf('\n');
        if (!line) continue;
        const request = JSON.parse(line);
        this.requests.push(request);
        this._answer(socket, request);
      }
    });
  }

  _answer(socket, request) {
    const reply = this.handle(request);
    if (reply.silent) return;
    if (reply.drop) {
      socket.destroy();
      return;
    }
    const write = () => {
      if (socket.destroyed) return;
      socket.write(`${JSON.stringify({ id: request.id, ...reply })}\n`);
    };
    if (reply.delayMs) setTimeout(write, reply.delayMs);
    else write();
  }
}

function clientFor(bridge, overrides = {}) {
  return new BridgeClient({
    instance: 'host',
    host: '127.0.0.1',
    port: bridge.port,
    timeoutMs: 2000,
    hint: 'Relaunch with -Bridge.',
    ...overrides
  });
}

async function freePort() {
  const probe = net.createServer();
  await new Promise((resolve) => probe.listen(0, '127.0.0.1', resolve));
  const { port } = probe.address();
  await new Promise((resolve) => probe.close(resolve));
  return port;
}

// --------------------------------------------------------------------------
// TCP client
// --------------------------------------------------------------------------

test('correlates responses by id when the bridge answers out of order', async (t) => {
  const bridge = new MockBridge((request) =>
    request.cmd === 'barpool'
      ? { ok: true, data: { verb: 'barpool' }, delayMs: 150 }
      : { ok: true, data: { verb: request.cmd } }
  );
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  const slow = client.send('barpool');
  const fast = client.send('status');

  assert.deepEqual(await fast, { verb: 'status' });
  assert.deepEqual(await slow, { verb: 'barpool' });

  const ids = bridge.requests.map((r) => r.id);
  assert.equal(new Set(ids).size, 2, 'each request carries a distinct id');
});

test('sends args verbatim inside the request envelope', async (t) => {
  const bridge = new MockBridge(() => ({ ok: true, data: {} }));
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  await client.send('market', { marketId: 'jangala' });
  assert.deepEqual(bridge.requests[0], { id: 1, cmd: 'market', args: { marketId: 'jangala' } });

  await client.send('barpool');
  assert.deepEqual(bridge.requests[1].args, {}, 'a verb with no args still sends an empty object');
});

test('times out a request the bridge never answers', async (t) => {
  const bridge = new MockBridge(() => ({ silent: true }));
  await bridge.start();
  const client = clientFor(bridge, { timeoutMs: 200 });
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  await assert.rejects(() => client.send('fleets'), (err) => {
    assert.ok(err instanceof BridgeTimeoutError);
    assert.match(err.message, /bridge "host" did not answer fleets within 200 ms/);
    return true;
  });
});

test('reconnects and retries once when the socket drops mid-request on a read command', async (t) => {
  let seen = 0;
  const bridge = new MockBridge(() => {
    seen++;
    return seen === 1 ? { drop: true } : { ok: true, data: { attempt: seen } };
  });
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  assert.deepEqual(await client.send('status'), { attempt: 2 });
  assert.equal(bridge.connections, 2, 'the retry opened a fresh connection');
});

test('does not retry a mutating command after a mid-request drop; throws BridgeOutcomeUnknownError', async (t) => {
  let seen = 0;
  const bridge = new MockBridge(() => {
    seen++;
    return seen === 1 ? { drop: true } : { ok: true, data: { attempt: seen } };
  });
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  await assert.rejects(() => client.send('give', { credits: 1000 }), (err) => {
    assert.ok(err instanceof BridgeOutcomeUnknownError);
    assert.equal(err.instance, 'host');
    assert.equal(err.cmd, 'give');
    assert.match(err.message, /may or may not have executed/);
    assert.match(err.message, /status|cargo/);
    return true;
  });

  assert.equal(bridge.requests.length, 1, 'the mutation was sent exactly once, no replayed request line');
  assert.equal(bridge.requests[0].cmd, 'give');
});

test('READ_ONLY_COMMANDS is exactly the ten read verbs and none of the mutations', () => {
  assert.deepEqual(
    [...READ_ONLY_COMMANDS].sort(),
    ['barpool', 'cargo', 'colonizable', 'fleets', 'landmarks', 'market', 'markets', 'status', 'survey', 'visibility']
  );
  for (const mutation of ['teleport', 'pause', 'ability', 'setcr', 'give', 'addship', 'objective', 'surveyset', 'expedition']) {
    assert.ok(!READ_ONLY_COMMANDS.has(mutation), `${mutation} must not be in the read-only allowlist`);
  }
});

test('reuses one connection across sequential requests', async (t) => {
  const bridge = new MockBridge(() => ({ ok: true, data: {} }));
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  await client.send('status');
  await client.send('status');
  await client.send('status');
  assert.equal(bridge.connections, 1, 'the bridge accepts one client at a time; do not churn connections');
});

test('surfaces the bridge error string on ok:false', async (t) => {
  const bridge = new MockBridge(() => ({ ok: false, error: 'IllegalArgumentException: no market with id nowhere' }));
  await bridge.start();
  const client = clientFor(bridge);
  t.after(async () => {
    client.close();
    await bridge.stop();
  });

  await assert.rejects(() => client.send('market', { marketId: 'nowhere' }), (err) => {
    assert.ok(err instanceof BridgeError);
    assert.equal(err.bridgeError, 'IllegalArgumentException: no market with id nowhere');
    assert.match(err.message, /no market with id nowhere/);
    return true;
  });
});

test('names the instance, the port and the launch switch when nothing is listening', async () => {
  const port = await freePort();
  const client = new BridgeClient({
    instance: 'guest',
    port,
    timeoutMs: 1000,
    hint: "Relaunch it with: launch-guest.ps1 -Bridge (that appends -Dcoop.debug.bridge=7802)."
  });

  await assert.rejects(() => client.send('status'), (err) => {
    assert.ok(err instanceof BridgeUnreachableError);
    assert.match(err.message, /bridge "guest"/);
    assert.match(err.message, new RegExp(`127\\.0\\.0\\.1:${port}`));
    assert.match(err.message, /-Bridge/);
    return true;
  });
});

// --------------------------------------------------------------------------
// Structural diff
// --------------------------------------------------------------------------

const FLEETS = {
  locationId: 'corvus',
  fleets: [
    {
      coopFleetId: 'cf-1',
      name: 'Hegemony Patrol',
      faction: 'hegemony',
      x: 100.5,
      y: -220.25,
      transponder: true,
      members: [
        { variantId: 'lasher_Standard', cr: 0.7, hullFraction: 1 },
        { variantId: 'lasher_Standard', cr: 0.7, hullFraction: 0.9 }
      ]
    },
    {
      coopFleetId: 'cf-2',
      name: 'Pirate Raiders',
      faction: 'pirates',
      x: -40,
      y: 12,
      transponder: false,
      members: [{ variantId: 'wolf_Assault', cr: 0.55, hullFraction: 1 }]
    },
    {
      coopFleetId: 'cf-3',
      name: 'Trade Convoy',
      faction: 'independent',
      x: 0,
      y: 0,
      transponder: true,
      members: []
    }
  ]
};

function reorderedFleets(mutate = () => {}) {
  const copy = structuredClone(FLEETS);
  copy.fleets = [copy.fleets[2], copy.fleets[0], copy.fleets[1]];
  mutate(copy);
  return copy;
}

test('order-insensitive on keyed collections: reordered fleets are equal', () => {
  const result = diffJson(FLEETS, reorderedFleets());
  assert.equal(result.equal, true);
  assert.deepEqual(result.differences, []);
  assert.equal(result.counts.host, result.counts.guest);
  assert.equal(result.counts.differing, 0);
});

test('catches a single changed field inside a reordered collection', () => {
  const guest = reorderedFleets((copy) => {
    copy.fleets.find((f) => f.coopFleetId === 'cf-2').members[0].cr = 0.31;
  });
  const result = diffJson(FLEETS, guest);

  assert.equal(result.equal, false);
  assert.equal(result.counts.differing, 1);
  assert.deepEqual(result.differences[0], {
    path: '$.fleets[coopFleetId=cf-2].members[0].cr',
    host: 0.55,
    guest: 0.31
  });
});

test('reports a collection member present on one side only', () => {
  const guest = reorderedFleets((copy) => {
    copy.fleets = copy.fleets.filter((f) => f.coopFleetId !== 'cf-3');
  });
  const result = diffJson(FLEETS, guest);

  assert.equal(result.differences.length, 1);
  assert.equal(result.differences[0].path, '$.fleets[coopFleetId=cf-3]');
  assert.equal(result.differences[0].missing, 'guest');
  assert.equal(result.differences[0].host.name, 'Trade Convoy');
});

test('reports a missing object key and a type change', () => {
  const guest = structuredClone(FLEETS);
  delete guest.fleets[0].transponder;
  guest.fleets[1].x = '-40';
  const result = diffJson(FLEETS, guest);

  const byPath = Object.fromEntries(result.differences.map((d) => [d.path, d]));
  assert.equal(byPath['$.fleets[coopFleetId=cf-1].transponder'].missing, 'guest');
  assert.match(byPath['$.fleets[coopFleetId=cf-2].x'].note, /number on host, string on guest/);
});

test('unkeyable arrays compare by index, so a reorder is a difference', () => {
  const host = { members: [{ cr: 0.7 }, { cr: 0.4 }] };
  const guest = { members: [{ cr: 0.4 }, { cr: 0.7 }] };
  assert.equal(pickKeyField(host.members, guest.members), null);
  assert.equal(diffJson(host, guest).counts.differing, 2);
});

test('tolerance suppresses float noise but not a real gap', () => {
  const host = { x: 100.5 };
  assert.equal(diffJson(host, { x: 100.5004 }, { tolerance: 0.001 }).equal, true);
  assert.equal(diffJson(host, { x: 100.5004 }).equal, false);
  assert.equal(diffJson(host, { x: 130 }, { tolerance: 0.001 }).equal, false);
});

test('counts report leaf values per side', () => {
  assert.equal(leafCount({ a: 1, b: { c: 2, d: [3, 4] }, e: [] }), 4);
  const result = diffJson({ a: 1 }, { a: 1, b: 2 });
  assert.deepEqual(result.counts, { host: 1, guest: 2, differing: 1 });
});

// --------------------------------------------------------------------------
// Ignored keys
// --------------------------------------------------------------------------

// The three payload shapes the live run tripped over. Everything here is per-instance except the
// coopFleetId keys and the visibility view, which is the point: with the default ignore list these
// two sides are equal, and without it every run reports the same three meaningless differences.
const FLEETS_HOST = {
  role: 'HOST',
  locationId: 'corvus',
  count: 2,
  fleets: [
    { coopFleetId: 'player:p-host', engineId: 'fleet_00a', name: 'Alice', isPlayer: true, x: 10, y: 20 },
    { coopFleetId: 'player:p-guest', engineId: 'fleet_11b', name: 'Bob', isPlayer: false, x: 30, y: 40 }
  ]
};

const FLEETS_GUEST = {
  role: 'GUEST',
  locationId: 'corvus',
  count: 2,
  // Same two logical fleets, opposite sides of the mirror, and engine ids assigned locally.
  fleets: [
    { coopFleetId: 'player:p-guest', engineId: 'fleet_77x', name: 'Bob', isPlayer: true, x: 30, y: 40 },
    { coopFleetId: 'player:p-host', engineId: 'fleet_88y', name: 'Alice', isPlayer: false, x: 10, y: 20 }
  ]
};

const VISIBILITY_HOST = {
  role: 'HOST',
  fleetId: '',
  lines: ['HOST visibility probe hostPlayerStr=180 fleets=2', '  H fleet_A "Patrol" visHost=SENSOR_CONTACT'],
  viewCount: 2,
  view: { fleet_A: 'SENSOR_CONTACT', fleet_B: 'NONE' }
};

const VISIBILITY_GUEST = {
  role: 'GUEST',
  fleetId: '',
  lines: ['GUEST visibility probe guestPlayerStr=120 mirrors=2', '  G fleet_A "Patrol" vis=SENSOR_CONTACT'],
  viewCount: 2,
  view: { fleet_A: 'SENSOR_CONTACT', fleet_B: 'NONE' }
};

test('role, engineId and lines are excluded by default', () => {
  const fleets = diffJson(FLEETS_HOST, FLEETS_GUEST);
  assert.deepEqual(fleets.ignored, DEFAULT_IGNORE_KEYS);
  assert.deepEqual(
    fleets.differences.map((d) => d.path).sort(),
    ['$.fleets[coopFleetId=player:p-guest].isPlayer', '$.fleets[coopFleetId=player:p-host].isPlayer'],
    'role and both engine ids are gone; only isPlayer is left, and it is not on the default list'
  );

  const visibility = diffJson(VISIBILITY_HOST, VISIBILITY_GUEST);
  assert.equal(visibility.equal, true, 'two probes describing the same fact in different sentences');
  assert.equal(visibility.counts.host, visibility.counts.guest);
});

test('an explicit ignore replaces the default rather than adding to it', () => {
  const result = diffJson(FLEETS_HOST, FLEETS_GUEST, { ignore: ['engineId'] });

  assert.equal(result.equal, false);
  assert.deepEqual(result.ignored, ['engineId']);
  assert.deepEqual(
    result.differences.map((d) => d.path).sort(),
    [
      '$.fleets[coopFleetId=player:p-guest].isPlayer',
      '$.fleets[coopFleetId=player:p-host].isPlayer',
      '$.role'
    ],
    'role came back because the caller took over the list'
  );
});

test('a per-instance field outside the default list is dropped by naming it', () => {
  const result = diffJson(FLEETS_HOST, FLEETS_GUEST, { ignore: [...DEFAULT_IGNORE_KEYS, 'isPlayer'] });
  assert.equal(result.equal, true);
});

test('an empty ignore list compares everything', () => {
  const result = diffJson(FLEETS_HOST, FLEETS_GUEST, { ignore: [] });

  const paths = result.differences.map((d) => d.path).sort();
  assert.deepEqual(paths, [
    '$.fleets[coopFleetId=player:p-guest].engineId',
    '$.fleets[coopFleetId=player:p-guest].isPlayer',
    '$.fleets[coopFleetId=player:p-host].engineId',
    '$.fleets[coopFleetId=player:p-host].isPlayer',
    '$.role'
  ]);
});

test('an ignored key cannot be the collection identity either', () => {
  // engineId sits in KEY_FIELDS ahead of most ids; keying on it would pair rows that are not the
  // same fleet and report every field of both as different.
  const host = [{ engineId: 'a', marketId: 'jangala', size: 6 }];
  const guest = [{ engineId: 'z', marketId: 'jangala', size: 6 }];
  assert.equal(pickKeyField(host, guest), 'engineId');
  assert.equal(pickKeyField(host, guest, new Set(['engineId'])), 'marketId');
  assert.equal(diffJson({ markets: host }, { markets: guest }).equal, true);
});

test('ignored keys do not inflate the leaf counts', () => {
  const result = diffJson({ role: 'HOST', a: 1 }, { role: 'GUEST', a: 1 });
  assert.deepEqual(result.counts, { host: 1, guest: 1, differing: 0 });
});

// --------------------------------------------------------------------------
// status.pause and markets shapes
// --------------------------------------------------------------------------

function statusFor(role, pause) {
  return {
    role,
    sessionActive: true,
    paused: true,
    sessionId: 's-1',
    localPlayerId: role === 'HOST' ? 'p-host' : 'p-guest',
    clock: { date: 'day 3, month 2, cycle 206', timestamp: 6_503_846_400_000 },
    playerFleet: { locationId: 'corvus', x: 10, y: 20 },
    pause
  };
}

const HOST_PAUSE = {
  blockingScreenOpen: false,
  hostIntent: false,
  guestIntent: true,
  guestKeyIntent: false,
  guestScreenIntent: true,
  eitherInCombat: false,
  effective: true
};

const MARKETS = {
  count: 2,
  markets: [
    { marketId: 'asharu', name: 'Asharu', factionId: 'independent', size: 4, locationId: 'askonia' },
    { marketId: 'jangala', name: 'Jangala', factionId: 'hegemony', size: 6, locationId: 'corvus' }
  ]
};

test('the host-only pause breakdown reads as missing on the guest, not as a value difference', () => {
  const result = diffJson(
    statusFor('HOST', HOST_PAUSE),
    statusFor('GUEST', { blockingScreenOpen: true })
  );

  const byPath = Object.fromEntries(result.differences.map((d) => [d.path, d]));
  assert.equal(byPath['$.pause.hostIntent'].missing, 'guest', 'only the host has an authority view');
  assert.equal(byPath['$.pause.effective'].missing, 'guest');
  assert.deepEqual(byPath['$.pause.blockingScreenOpen'], {
    path: '$.pause.blockingScreenOpen',
    host: false,
    guest: true
  });
  assert.equal(byPath['$.localPlayerId'].host, 'p-host');
});

const CARGO = {
  engineId: 'fleet_host_1',
  supplies: 120,
  fuel: 90,
  crew: 40,
  marines: 12,
  credits: 250000,
  cargoSpace: { capacity: 200, used: 150, free: 50 },
  fuelSpace: { capacity: 300, used: 90, free: 210 },
  personnel: { capacity: 200, used: 52, free: 148 },
  overloaded: false,
  over: []
};

test('cargo is a query verb, dumped whole and diffed like any other', async (t) => {
  const hostBridge = new MockBridge(() => ({ ok: true, data: CARGO }));
  // Same load, different engine id for the fleet: that id is per-instance and must not diff.
  const guestBridge = new MockBridge(() => ({ ok: true, data: { ...CARGO, engineId: 'fleet_guest_7' } }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  assert.deepEqual(await ssDump(bridges, 'host', 'cargo'), CARGO);
  assert.deepEqual(hostBridge.requests[0].args, {}, 'cargo takes no arguments');

  const equal = await ssDiff(bridges, 'cargo');
  assert.equal(equal.equal, true, 'engineId is dropped by the default ignore list');

  await assert.rejects(() => ssAct(bridges, 'host', 'cargo', {}), /unknown action verb "cargo"/);
});

test('a cargo divergence is reported per field, including the overload flag', () => {
  const result = diffJson(CARGO, {
    ...CARGO,
    supplies: 4,
    cargoSpace: { capacity: 200, used: 260, free: -60 },
    overloaded: true,
    over: ['cargoSpace']
  });

  const byPath = Object.fromEntries(result.differences.map((d) => [d.path, d]));
  assert.deepEqual(byPath['$.supplies'], { path: '$.supplies', host: 120, guest: 4 });
  assert.deepEqual(byPath['$.cargoSpace.free'], { path: '$.cargoSpace.free', host: 50, guest: -60 });
  assert.deepEqual(byPath['$.overloaded'], { path: '$.overloaded', host: false, guest: true });
  assert.equal(byPath['$.over[0]'].missing, 'host', 'an empty over list on one side reads as missing');
});

test('markets is a query verb, diffed by marketId', async (t) => {
  const hostBridge = new MockBridge(() => ({ ok: true, data: MARKETS }));
  const guestBridge = new MockBridge(() => ({
    ok: true,
    data: { count: 2, markets: [MARKETS.markets[1], MARKETS.markets[0]] }
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  assert.deepEqual(await ssDump(bridges, 'host', 'markets'), MARKETS);
  const result = await ssDiff(bridges, 'markets');
  assert.equal(result.equal, true, 'the economy is the same on both sides, whatever order it came in');
  await assert.rejects(() => ssAct(bridges, 'host', 'markets', {}), /unknown action verb "markets"/);
});

// --------------------------------------------------------------------------
// Tool layer
// --------------------------------------------------------------------------

function bridgesFor(hostPort, guestPort) {
  return new Bridges({
    STARSECTOR_MCP_HOST_PORT: String(hostPort),
    STARSECTOR_MCP_GUEST_PORT: String(guestPort),
    STARSECTOR_MCP_TIMEOUT_MS: '3000'
  });
}

test('ss_diff dumps both instances and diffs server-side', async (t) => {
  const hostBridge = new MockBridge(() => ({ ok: true, data: FLEETS }));
  const guestBridge = new MockBridge(() => ({
    ok: true,
    data: reorderedFleets((copy) => {
      copy.fleets.find((f) => f.coopFleetId === 'cf-1').x = 100.5 + 40;
    })
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  const result = await ssDiff(bridges, 'fleets', { locationId: 'corvus' });
  assert.equal(result.what, 'fleets');
  assert.deepEqual(result.args, { locationId: 'corvus' });
  assert.equal(result.equal, false);
  assert.deepEqual(result.differences, [
    { path: '$.fleets[coopFleetId=cf-1].x', host: 100.5, guest: 140.5 }
  ]);
  assert.deepEqual(hostBridge.requests[0].args, { locationId: 'corvus' });
  assert.deepEqual(guestBridge.requests[0].args, { locationId: 'corvus' });
});

test('ss_diff reports equal when both instances agree', async (t) => {
  const hostBridge = new MockBridge(() => ({ ok: true, data: FLEETS }));
  const guestBridge = new MockBridge(() => ({ ok: true, data: reorderedFleets() }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  const result = await ssDiff(bridges, 'fleets');
  assert.equal(result.equal, true);
  assert.equal(result.counts.differing, 0);
});

const COLONIZABLE = {
  fromLocationId: 'corvus',
  limit: 10,
  maxLy: 0,
  neutralOnly: false,
  candidateCount: 2,
  count: 2,
  planets: [
    {
      planetId: 'ancyra',
      name: 'Ancyra',
      type: 'terran',
      gasGiant: false,
      systemId: 'corvus',
      systemName: 'Corvus Star System',
      x: 8123.5,
      y: -2210.75,
      marketsInSystem: 1,
      distanceLy: 0,
      distanceSu: 1487.5,
      hazard: 1.25,
      surveyLevel: 'FULL',
      unexploredRuins: false,
      conditions: ['farmland_poor', 'habitable', 'ore_moderate']
    },
    {
      planetId: 'aleph_gas',
      name: 'Aleph',
      type: 'gas_giant',
      gasGiant: true,
      systemId: 'aleph',
      systemName: 'Aleph Star System',
      x: -400,
      y: 6100,
      marketsInSystem: 0,
      distanceLy: 5,
      distanceSu: 0,
      hazard: 1.5,
      surveyLevel: 'NONE',
      unexploredRuins: true,
      conditions: ['ruins_scattered', 'volatiles_plentiful']
    }
  ]
};

test('colonizable is a query verb, keyed by planetId and diffable across instances', async (t) => {
  const hostBridge = new MockBridge((request) =>
    request.cmd === 'colonizable'
      ? { ok: true, data: COLONIZABLE }
      : { ok: false, error: 'IllegalArgumentException: unknown command' }
  );
  // Same two planets, opposite order: worldgen agrees, the guest's walk just found them the other
  // way around. That must diff as equal.
  const guestBridge = new MockBridge(() => ({
    ok: true,
    data: { ...COLONIZABLE, planets: [COLONIZABLE.planets[1], COLONIZABLE.planets[0]] }
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  assert.deepEqual(await ssDump(bridges, 'host', 'colonizable', { limit: 3, maxLy: 8 }), COLONIZABLE);
  assert.deepEqual(hostBridge.requests[0].args, { limit: 3, maxLy: 8 });

  await ssDump(bridges, 'host', 'colonizable');
  assert.deepEqual(hostBridge.requests[1].args, {}, 'no args means the defaults, not a bad request');

  // neutralOnly is the mod's filter; the server passes it through rather than filtering client-side.
  await ssDump(bridges, 'host', 'colonizable', { neutralOnly: true, limit: 3 });
  assert.deepEqual(hostBridge.requests[2].args, { neutralOnly: true, limit: 3 });

  const result = await ssDiff(bridges, 'colonizable');
  assert.equal(result.equal, true, 'the same planets in a different order are not a divergence');

  // A read-only verb must stay out of ss_act, the same way markets does.
  await assert.rejects(() => ssAct(bridges, 'host', 'colonizable', {}), /unknown action verb "colonizable"/);
});

test('a colonizable divergence is reported per planet, not as one opaque array difference', () => {
  const guest = {
    ...COLONIZABLE,
    planets: [
      { ...COLONIZABLE.planets[0], surveyLevel: 'PRELIMINARY' },
      COLONIZABLE.planets[1]
    ]
  };

  const result = diffJson(COLONIZABLE, guest);

  assert.equal(result.equal, false);
  assert.deepEqual(result.differences, [
    {
      path: '$.planets[planetId=ancyra].surveyLevel',
      host: 'FULL',
      guest: 'PRELIMINARY'
    }
  ]);
});

test('the position and neutrality fields diff per planet like every other row field', () => {
  const occupied = {
    ...COLONIZABLE,
    planets: [{ ...COLONIZABLE.planets[0], marketsInSystem: 0 }, COLONIZABLE.planets[1]]
  };
  assert.deepEqual(diffJson(COLONIZABLE, occupied).differences, [
    { path: '$.planets[planetId=ancyra].marketsInSystem', host: 1, guest: 0 }
  ]);

  const drifted = {
    ...COLONIZABLE,
    planets: [{ ...COLONIZABLE.planets[0], x: 8124.25 }, COLONIZABLE.planets[1]]
  };
  assert.deepEqual(diffJson(COLONIZABLE, drifted).differences, [
    { path: '$.planets[planetId=ancyra].x', host: 8123.5, guest: 8124.25 }
  ]);
  // Planets orbit. x/y are ordinary numeric leaves, so a live position compare takes a tolerance
  // like every other one rather than needing its own ignore entry.
  assert.equal(diffJson(COLONIZABLE, drifted, { tolerance: 1 }).equal, true);
});

const LANDMARKS = {
  fromLocationId: 'corvus',
  kinds: ['hypershunt', 'cryosleeper'],
  limit: 25,
  maxLy: 12,
  candidateCount: 4,
  count: 2,
  landmarks: [
    {
      kind: 'cryosleeper',
      entityId: 'cryosleeper_calypso',
      name: 'Domain-era Cryosleeper "Calypso"',
      type: 'derelict_cryosleeper',
      systemId: 'system_a41c',
      systemName: 'Tuvalu Star System',
      hyperspace: false,
      x: -4120,
      y: 980,
      distanceLy: 6.82,
      distanceSu: 0,
      usable: true,
      benefitRangeLy: 10,
      minBenefitMult: 0.1
    },
    {
      kind: 'hypershunt',
      entityId: 'coronal_tap_1',
      name: 'Coronal Hypershunt',
      type: 'coronal_tap',
      systemId: 'system_0b3f',
      systemName: 'Naraka Star System',
      hyperspace: false,
      x: 2400,
      y: -60,
      distanceLy: 9.5,
      distanceSu: 0,
      usable: false,
      benefitRangeLy: 10
    }
  ]
};

test('landmarks is a query verb, keyed by entityId and diffable across instances', async (t) => {
  const hostBridge = new MockBridge((request) =>
    request.cmd === 'landmarks'
      ? { ok: true, data: LANDMARKS }
      : { ok: false, error: 'IllegalArgumentException: unknown command' }
  );
  // Same landmarks, opposite order: the two walks found them differently, which is not a divergence.
  const guestBridge = new MockBridge(() => ({
    ok: true,
    data: { ...LANDMARKS, landmarks: [LANDMARKS.landmarks[1], LANDMARKS.landmarks[0]] }
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  const args = { kinds: 'hypershunt,cryosleeper', maxLy: 12 };
  assert.deepEqual(await ssDump(bridges, 'host', 'landmarks', args), LANDMARKS);
  assert.deepEqual(hostBridge.requests[0].args, args, 'kinds is passed through verbatim');

  await ssDump(bridges, 'host', 'landmarks');
  assert.deepEqual(hostBridge.requests[1].args, {}, 'no args means every kind, not a bad request');

  assert.equal((await ssDiff(bridges, 'landmarks')).equal, true);
  await assert.rejects(() => ssAct(bridges, 'host', 'landmarks', {}), /unknown action verb "landmarks"/);
});

test('a landmark divergence is reported per entity, and a per-kind extra only where it exists', () => {
  const guest = {
    ...LANDMARKS,
    landmarks: [
      { ...LANDMARKS.landmarks[0], usable: false },
      LANDMARKS.landmarks[1]
    ]
  };

  const result = diffJson(LANDMARKS, guest);

  assert.deepEqual(result.differences, [
    { path: '$.landmarks[entityId=cryosleeper_calypso].usable', host: true, guest: false }
  ]);
  // minBenefitMult is on the cryosleeper and not the hypershunt; rows key by entityId, so an extra
  // that only some kinds carry never reads as a missing field.
  assert.equal(diffJson(LANDMARKS, LANDMARKS).equal, true);

  // x/y are the teleport-ready coordinates and diff per entity like the rest of the row.
  const moved = {
    ...LANDMARKS,
    landmarks: [{ ...LANDMARKS.landmarks[0], x: -4118 }, LANDMARKS.landmarks[1]]
  };
  assert.deepEqual(diffJson(LANDMARKS, moved).differences, [
    { path: '$.landmarks[entityId=cryosleeper_calypso].x', host: -4120, guest: -4118 }
  ]);
});

test('teleport relays both argument modes, including the mod\'s refusal of the two together', async (t) => {
  const jumped = {
    locationId: 'penelope',
    x: 3320,
    y: -1500,
    movedFrom: 'corvus',
    entityId: 'aztlan',
    entityName: 'Aztlan',
    transition: 'jump',
    pending: true
  };
  const placed = {
    locationId: 'corvus',
    x: 500,
    y: 250,
    movedFrom: 'corvus',
    entityId: '',
    entityName: '',
    transition: 'local',
    pending: false
  };
  const hostBridge = new MockBridge((request) => {
    if (request.cmd !== 'teleport') {
      return { ok: false, error: 'IllegalArgumentException: unknown command' };
    }
    const args = request.args ?? {};
    if (args.entityId && (args.x !== undefined || args.locationId !== undefined)) {
      return {
        ok: false,
        error:
          'IllegalArgumentException: teleport takes either entityId or x/y/locationId, not both; got entityId and x'
      };
    }
    return { ok: true, data: args.entityId ? jumped : placed };
  });
  const guestBridge = new MockBridge(() => ({ ok: true, data: placed }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  assert.deepEqual(await ssAct(bridges, 'host', 'teleport', { entityId: 'aztlan' }), jumped);
  assert.deepEqual(hostBridge.requests[0].args, { entityId: 'aztlan' }, 'entityId goes through verbatim');

  const local = await ssAct(bridges, 'host', 'teleport', { locationId: 'corvus', x: 500, y: 250 });
  assert.equal(local.transition, 'local', 'the original coordinate mode is unchanged');
  assert.deepEqual(hostBridge.requests[1].args, { locationId: 'corvus', x: 500, y: 250 });

  // Which argument combinations are legal is the mod's call, not the server's: the bad request has
  // to reach the bridge and come back as its refusal rather than being pre-judged here.
  await assert.rejects(
    () => ssAct(bridges, 'host', 'teleport', { entityId: 'aztlan', x: 5 }),
    /either entityId or x\/y\/locationId/
  );
  assert.deepEqual(hostBridge.requests[2].args, { entityId: 'aztlan', x: 5 });
});

test('expedition is an action verb, passed through with its optional factionId', async (t) => {
  const created = {
    role: 'HOST',
    factionId: 'hegemony',
    created: true,
    reasonCount: 3,
    reasonTypes: ['ANTI_FREE_PORT'],
    trackedBefore: true,
    ongoing: 1,
    targetMarketId: 'player_colony_1',
    targetMarketName: 'New Kaunas',
    etaDays: 47.315
  };
  const hostBridge = new MockBridge((request) =>
    request.cmd === 'expedition'
      ? { ok: true, data: created }
      : { ok: false, error: 'IllegalArgumentException: unknown command' }
  );
  const guestBridge = new MockBridge(() => ({
    ok: false,
    error: 'IllegalStateException: expedition is host-only: the guest\'s PunitiveExpeditionManager is suppressed (Phase 13)'
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  assert.deepEqual(await ssAct(bridges, 'host', 'expedition', { factionId: 'hegemony' }), created);
  assert.deepEqual(hostBridge.requests[0].args, { factionId: 'hegemony' });

  await ssAct(bridges, 'host', 'expedition');
  assert.deepEqual(hostBridge.requests[1].args, {}, 'no factionId means "pick one", not a missing argument');

  // The host-only refusal is the mod's to make; the server must relay it rather than pre-judge it.
  await assert.rejects(() => ssAct(bridges, 'guest', 'expedition', {}), /host-only.*suppressed/s);
  await assert.rejects(() => ssDump(bridges, 'host', 'expedition', {}), /unknown query verb "expedition"/);
});

test('addship is an action verb, relaying the count and the refusals the mod makes', async (t) => {
  const added = {
    variantId: 'shuttle_Hauler',
    requested: 2,
    added: 2,
    members: [
      { memberId: 'member-1', variantId: 'shuttle_Hauler', hullId: 'shuttle', cr: 0.7 },
      { memberId: 'member-2', variantId: 'shuttle_Hauler', hullId: 'shuttle', cr: 0.7 }
    ],
    fleetSize: 3,
    fleetHashBefore: 'aaaa',
    fleetHashAfter: 'bbbb',
    fleetHashChanged: true
  };
  const hostBridge = new MockBridge((request) => {
    if (request.cmd !== 'addship') return { ok: false, error: 'IllegalArgumentException: unknown command' };
    if (request.args.variantId === 'shuttle_Hauler') return { ok: true, data: added };
    return {
      ok: false,
      error: `IllegalArgumentException: unknown variant ${request.args.variantId}; the fleet factory would substitute a placeholder hull rather than refuse it`
    };
  });
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  assert.deepEqual(await ssAct(bridges, 'host', 'addship', { variantId: 'shuttle_Hauler', count: 2 }), added);
  assert.deepEqual(hostBridge.requests[0].args, { variantId: 'shuttle_Hauler', count: 2 });

  await ssAct(bridges, 'host', 'addship', { variantId: 'shuttle_Hauler' });
  assert.deepEqual(hostBridge.requests[1].args, { variantId: 'shuttle_Hauler' }, 'count is optional');

  // Variant validation is the mod's: the server relays the refusal rather than keeping a ship list.
  await assert.rejects(
    () => ssAct(bridges, 'host', 'addship', { variantId: 'shuttle_Hauller' }),
    /unknown variant shuttle_Hauller/
  );
  await assert.rejects(() => ssDump(bridges, 'host', 'addship', {}), /unknown query verb "addship"/);
});

test('refuses the non-verbs with the reason, and unknown verbs with the verb list', async () => {
  const bridges = bridgesFor(1, 2);
  await assert.rejects(() => ssDump(bridges, 'host', 'buy', {}), /not a bridge verb by design.*PlayerMarketTransaction/s);
  await assert.rejects(() => ssAct(bridges, 'host', 'hire', {}), /not a bridge verb by design.*close-diff/s);
  await assert.rejects(() => ssDump(bridges, 'host', 'teleport', {}), /unknown query verb "teleport".*status, fleets/s);
  await assert.rejects(() => ssAct(bridges, 'host', 'fleets', {}), /unknown action verb "fleets".*teleport, pause/s);
  bridges.closeAll();
});

test('ss_advance_days unpauses, waits out the clock, and pauses again', async (t) => {
  const start = 1_000_000_000_000;
  const state = { timestamp: start, paused: true };
  const pauseCalls = [];

  const hostBridge = new MockBridge((request) => {
    if (request.cmd === 'pause') {
      state.paused = request.args.on === true || request.args.state === 'on';
      pauseCalls.push(state.paused ? 'on' : 'off');
      return { ok: true, data: { paused: state.paused } };
    }
    if (request.cmd === 'status') {
      if (!state.paused) state.timestamp += MS_PER_GAME_DAY * 0.25;
      return {
        ok: true,
        data: {
          role: 'HOST',
          sessionActive: true,
          paused: state.paused,
          clock: { date: `day ${Math.round((state.timestamp - start) / MS_PER_GAME_DAY)}`, timestamp: state.timestamp },
          playerFleet: { locationId: 'corvus', x: 0, y: 0 }
        }
      };
    }
    return { ok: false, error: 'IllegalArgumentException: unknown command' };
  });
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  const result = await ssAdvanceDays(bridges, 1, { pollMs: 1, timeoutSeconds: 10 });

  assert.equal(result.requestedDays, 1);
  assert.ok(result.achievedDays >= 1, `achieved ${result.achievedDays} days`);
  assert.equal(result.timedOut, false);
  assert.equal(result.repaused, true);
  assert.deepEqual(pauseCalls, ['off', 'on'], 'unpaused first, re-paused last');
  assert.equal(state.paused, true, 'the host is left paused');
  assert.equal(result.startDate, 'day 0');
});

test('ss_advance_days re-pauses and reports the timeout when the clock does not move', async (t) => {
  const hostBridge = new MockBridge((request) =>
    request.cmd === 'pause'
      ? { ok: true, data: {} }
      : { ok: true, data: { clock: { date: 'day 0', timestamp: 5 } } }
  );
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  const result = await ssAdvanceDays(bridges, 3, { pollMs: 1, timeoutSeconds: 1 });
  assert.equal(result.timedOut, true);
  assert.equal(result.achievedDays, 0);
  assert.equal(result.repaused, true);
  assert.equal(hostBridge.requests.filter((r) => r.cmd === 'pause').length, 2);
  assert.equal(result.stall.reason, 'unknown', 'nothing in either pause block explains this one');
});

test('a stalled advance names the guest screen that is holding the clock', async (t) => {
  // The case the live run hit blind: the clock did not move and there was no way to ask why.
  const hostBridge = new MockBridge((request) =>
    request.cmd === 'pause'
      ? { ok: true, data: {} }
      : {
          ok: true,
          data: statusFor('HOST', { ...HOST_PAUSE, guestIntent: false, guestScreenIntent: false })
        }
  );
  const guestBridge = new MockBridge(() => ({
    ok: true,
    data: statusFor('GUEST', { blockingScreenOpen: true })
  }));
  await hostBridge.start();
  await guestBridge.start();
  const bridges = bridgesFor(hostBridge.port, guestBridge.port);
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
    await guestBridge.stop();
  });

  const result = await ssAdvanceDays(bridges, 2, { pollMs: 1, timeoutSeconds: 1 });

  assert.equal(result.timedOut, true);
  assert.equal(result.stall.instance, 'guest');
  assert.equal(result.stall.reason, 'blockingScreenOpen');
  assert.equal(result.stall.pause.guest.blockingScreenOpen, true);
  assert.equal(result.repaused, true, 'the diagnosis must not cost the re-pause');
  assert.equal(result.achievedDays, 0);
  assert.equal(result.startDate, 'day 3, month 2, cycle 206', 'the existing fields are unchanged');
});

test('the host intent wins the stall diagnosis over a screen, and is read before the re-pause', async (t) => {
  const pauseCalls = [];
  const hostBridge = new MockBridge((request) => {
    if (request.cmd === 'pause') {
      pauseCalls.push(request.args.on);
      return { ok: true, data: {} };
    }
    return { ok: true, data: statusFor('HOST', { ...HOST_PAUSE, hostIntent: true }) };
  });
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  const result = await ssAdvanceDays(bridges, 1, { pollMs: 1, timeoutSeconds: 1 });

  assert.deepEqual(result.stall.instance, 'host');
  assert.equal(result.stall.reason, 'hostIntent');
  assert.equal(result.stall.pause.guest, null, 'an unreachable guest does not sink the diagnosis');
  assert.match(result.stall.unreachable.guest, /bridge "guest"/);
  assert.deepEqual(pauseCalls, [false, true]);
});

test('a completed advance carries no stall object', async (t) => {
  const start = 2_000_000_000_000;
  const state = { timestamp: start, paused: true };
  const hostBridge = new MockBridge((request) => {
    if (request.cmd === 'pause') {
      state.paused = request.args.on === true;
      return { ok: true, data: {} };
    }
    if (!state.paused) state.timestamp += MS_PER_GAME_DAY;
    return { ok: true, data: { clock: { date: 'day x', timestamp: state.timestamp } } };
  });
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  const result = await ssAdvanceDays(bridges, 1, { pollMs: 1, timeoutSeconds: 10 });

  assert.equal(result.timedOut, false);
  assert.equal(result.stall, undefined, 'nothing to diagnose when the clock moved');
});

test('ss_advance_days rejects a status payload with no clock timestamp', async (t) => {
  const hostBridge = new MockBridge(() => ({ ok: true, data: { role: 'HOST', sessionActive: false } }));
  await hostBridge.start();
  const bridges = bridgesFor(hostBridge.port, await freePort());
  t.after(async () => {
    bridges.closeAll();
    await hostBridge.stop();
  });

  await assert.rejects(() => ssAdvanceDays(bridges, 1, { pollMs: 1 }), /no numeric clock timestamp.*role, sessionActive/s);
});
