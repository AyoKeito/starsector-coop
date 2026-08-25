// Offline test suite: a mock CoopAgentBridge (plain net server speaking the
// newline-JSON protocol) plus assertions over the TCP client and the structural diff.
//
// Nothing here needs Starsector running. Run with: npm test

import assert from 'node:assert/strict';
import net from 'node:net';
import test from 'node:test';

import { BridgeClient, BridgeError, BridgeTimeoutError, BridgeUnreachableError } from '../lib/bridge-client.js';
import { diffJson, leafCount, pickKeyField } from '../lib/diff.js';
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

test('reconnects and retries once when the socket drops mid-request', async (t) => {
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
