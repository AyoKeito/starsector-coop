# starsector-mcp

An MCP server that gives an agent a read/act channel into two running Starsector instances at once, so co-op state-equality checks run without a human watching two screens.

It talks to `coop.debug.CoopAgentBridge`, a dormant TCP listener the mod opens only when the game is started with `-Dcoop.debug.bridge=<port>`. Host listens on 127.0.0.1:7801, guest on 127.0.0.1:7802. The bridge only serializes state and applies setup actions. Every comparison happens in this server.

Phase 30 of the co-op plan. This is a development tool: without the system property the mod opens no socket and writes no log line.

## Prerequisites

- Node 18 or newer (`node --version`).
- The two-client test setup under `K:\Starsector-coop-test`, built and deployed via `scripts\deploy-to-test-clients.ps1`.
- Both instances launched with the bridge switch:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-host.ps1' -Bridge
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-guest.ps1' -Bridge
```

The bridge answers once a campaign is loaded. It does not need an active co-op session, so `ss_status` works on a single instance sitting in its own game.

## Install

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop\tools\starsector-mcp'; npm install"
```

One dependency, `@modelcontextprotocol/sdk`, pinned to 1.30.0.

## Register with Claude Code

Add to `.mcp.json` at the repo root, or to your user-level MCP config:

```json
{
  "mcpServers": {
    "starsector": {
      "command": "node",
      "args": ["K:\\Starsector\\mods\\coop\\tools\\starsector-mcp\\index.js"]
    }
  }
}
```

The transport is stdio, so the server starts and stops with the Claude Code session. Sockets to the games are opened lazily on the first tool call and reused; if a game exits and restarts, the next call reconnects.

To point at different ports, add an `env` block:

```json
{
  "mcpServers": {
    "starsector": {
      "command": "node",
      "args": ["K:\\Starsector\\mods\\coop\\tools\\starsector-mcp\\index.js"],
      "env": { "STARSECTOR_MCP_HOST_PORT": "7811", "STARSECTOR_MCP_GUEST_PORT": "7812" }
    }
  }
}
```

## Ports and environment variables

| Instance name | Default port | Launch switch that opens it | Env override |
| --- | --- | --- | --- |
| `host` | 7801 | `launch-host.ps1 -Bridge` | `STARSECTOR_MCP_HOST_PORT` |
| `guest` | 7802 | `launch-guest.ps1 -Bridge` | `STARSECTOR_MCP_GUEST_PORT` |

Two more knobs:

- `STARSECTOR_MCP_ADDRESS` (default `127.0.0.1`). The bridge binds loopback only, so this is here for tunnels, not for reaching another machine.
- `STARSECTOR_MCP_TIMEOUT_MS` (default `10000`). Per-request deadline. Commands are serviced a few per game frame, so a response can lag a frame or two; ten seconds is generous for that, and a request that blows through it means the game is stalled or the command hung.

If a port has nothing listening, the tool fails with the instance name, the address it tried, the errno, and the launch command that would open it.

## Tools

### `ss_status(instance)`

Role, whether a co-op session is up, the campaign clock, pause state, and where the player fleet is.

```
ss_status(instance: "guest")

{ "role": "GUEST", "sessionActive": true, "paused": false,
  "clock": { "date": "day 3, month 2, cycle 206", "timestamp": 6503846400000 },
  "playerFleet": { "locationId": "corvus", "x": 1204.5, "y": -880.25 } }
```

### `ss_dump(instance, what, args?)`

One read-only verb against one instance, returned as-is. Verbs and their arguments:

| `what` | `args` | returns |
| --- | --- | --- |
| `status` | none | same payload as `ss_status` |
| `fleets` | `{locationId?}` | per fleet: ids, name, faction, position, velocity, transponder, action text, members |
| `market` | `{marketId}` | full stock including ship details, specials, hireables |
| `barpool` | none | ordered offer list plus the bar's render order |
| `survey` | `{systemId}` or `{systemId: "all"}` | planetId to survey level and ruins state |
| `visibility` | `{fleetId?}` | detectability dump for the host-vs-guest sensor comparison |

```
ss_dump(instance: "host", what: "market", args: { "marketId": "jangala" })
```

Two things about `market` that are behaviour, not bugs. On the host it runs the same `ensureOpenMarketStocked` a real dock visit runs, so calling it stocks the market. On the guest it reports raw current cargo, and a market the guest has never docked at comes back with `"stocked": false` rather than an error.

### `ss_diff(what, args?, tolerance?)`

Runs one query verb against both instances and compares the two JSON trees field by field.

```
ss_diff(what: "fleets", args: { "locationId": "corvus" })

{ "what": "fleets",
  "equal": false,
  "differences": [
    { "path": "$.fleets[coopFleetId=cf-2].members[0].cr", "host": 0.55, "guest": 0.31 },
    { "path": "$.fleets[coopFleetId=cf-7]", "host": { "name": "Trade Convoy", ... }, "missing": "guest" }
  ],
  "counts": { "host": 412, "guest": 396, "differing": 2 } }
```

How to read the output:

- `path` starts at `$`. A keyed collection member shows as `[<field>=<value>]`, an index-compared element as `[<index>]`.
- `missing: "guest"` means the key or member exists on the host and not on the guest. The absent side is left out of the entry.
- `counts.host` and `counts.guest` are leaf value counts on each side, not fleet counts. `counts.differing` is the length of `differences`.
- An array is compared order-insensitively when every element on both sides carries the same identifying field and those values are unique per side: fleets key on `coopFleetId`, stock on `id`, and so on. An array that fails that test, such as a roster holding two ships of the same variant, is compared index by index, so a reorder shows up as a difference. That is deliberate; silently accepting a reorder would hide a real mirror bug.
- `tolerance` sets an absolute allowance on numeric leaves. Default 0, exact match. Use `tolerance: 0.5` on `fleets` if you want to ignore sub-unit position jitter and see only the fields that actually diverged.

### `ss_act(instance, verb, args?)`

One state-changing verb against one instance.

| `verb` | `args` |
| --- | --- |
| `teleport` | `{x, y, locationId}` |
| `pause` | `{on: true}` or `{on: false}` |
| `ability` | `{abilityId}` |
| `setcr` | `{value, memberIndex}` or `{value, memberIndex: "all"}` |
| `give` | `{commodityId?, qty?, credits?}` |
| `objective` | `{entityId, factionId}` |
| `surveyset` | `{planetId, level}` |

```
ss_act(instance: "guest", verb: "ability", args: { "abilityId": "interdiction_pulse" })
```

`ability` goes through the same engine path the toolbar button uses, down to `reportPlayerActivatedAbility`, so the mod's listener fires and the host sees `ABILITY_ACTIVATE`. `surveyset` does not: it sets the survey level at the engine level, which is faithful to what the replication poll watches but skips the survey dialog. Check the dialog path by hand.

### `ss_advance_days(days, timeoutSeconds?)`

Unpauses the host, polls its clock until it has advanced `days` game days, then pauses it again. The guest follows the host clock, so only the host is driven. Nothing in the mod implements this; it is `pause` and `status` in a loop.

```
ss_advance_days(days: 5)

{ "requestedDays": 5, "achievedDays": 5.0021,
  "startDate": "day 3, month 2, cycle 206", "endDate": "day 8, month 2, cycle 206",
  "elapsedRealSeconds": 51.4, "timedOut": false, "repaused": true }
```

One game day is about ten real seconds at normal speed (`CampaignClock.SECONDS_PER_GAME_DAY` is 10), so five days costs roughly a minute of wall time. Fast-forward is disabled until Phase 7b, and this tool does not touch it. The default budget is three times the nominal duration plus 20 seconds; raise it with `timeoutSeconds`. The host is re-paused whether the wait finished, timed out, or failed, and `repaused` reports whether that last pause call landed.

## What this will not do

There is no verb for market buy/sell, officer hire, bar-offer accept, or market open/close, and adding one would defeat the checks it looked like it was helping with. Each of those is on the smoke checklist precisely because a UI listener drives it: `PlayerMarketTransaction` for trades, the dialog close-diff for hire claims, snapshot-on-open for market state. Calling the engine method underneath the listener would pass while the listener was unhooked. Those four stay manual. Asking for one by name returns that reason instead of an error code.

No screenshots, no vision, no keyboard or mouse injection, no save or load control.

## Tests

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop\tools\starsector-mcp'; npm test"
```

`test/mock-bridge-test.js` stands up a mock bridge, a plain `net` server speaking the same newline-JSON protocol, and runs the client and the diff against it. It covers out-of-order response correlation, the request timeout, reconnect-and-retry after a mid-request socket drop, the unreachable-port message, `ok:false` passthrough, order-insensitive and index-based diffing, and the `ss_advance_days` pause-poll-pause loop. Starsector does not need to be running.

## Layout

```
index.js              MCP tool schemas and stdio wiring
lib/tools.js          instance registry, verb tables, the five tool implementations
lib/bridge-client.js  TCP client: id correlation, timeout, reconnect
lib/diff.js           structural JSON diff
test/                 mock bridge and assertions
```
