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

Role, whether a co-op session is up, the campaign clock, pause state, where the player fleet is, and what is holding the clock.

```
ss_status(instance: "guest")

{ "role": "GUEST", "sessionActive": true, "paused": false,
  "clock": { "date": "day 3, month 2, cycle 206", "timestamp": 6503846400000 },
  "playerFleet": { "locationId": "corvus", "x": 1204.5, "y": -880.25 },
  "pause": { "blockingScreenOpen": false } }
```

`pause.blockingScreenOpen` is the local screen state, on both roles: a core tab, a dialog or the in-game menu open on either client holds the shared clock. On the host the block also carries the coordinator's intent breakdown, which is the OR that decides the clock:

```
"pause": { "blockingScreenOpen": false, "hostIntent": false, "guestIntent": true,
           "guestKeyIntent": false, "guestScreenIntent": true,
           "eitherInCombat": false, "effective": true }
```

The guest has no such breakdown, because a guest's coordinator holds its own outgoing intents rather than the authority's view. `ss_advance_days` reads this block when the clock does not move.

### `ss_dump(instance, what, args?)`

One read-only verb against one instance, returned as-is. Verbs and their arguments:

| `what` | `args` | returns |
| --- | --- | --- |
| `status` | none | same payload as `ss_status` |
| `fleets` | `{locationId?}` | per fleet: ids, name, faction, position, velocity, transponder, action text, members |
| `market` | `{marketId}` | full stock including ship details, specials, hireables |
| `markets` | none | every market in the economy: `marketId`, `name`, `factionId`, `size`, `locationId` |
| `barpool` | none | ordered offer list plus the bar's render order |
| `survey` | `{systemId}` or `{systemId: "all"}` | planetId to survey level and ruins state |
| `visibility` | `{fleetId?}` | `lines`, the probe's text dump, plus `view`, a coopFleetId to visibility-level map |
| `colonizable` | `{limit?, maxLy?}` | uncolonized planets nearest the local player fleet, nearest first |
| `landmarks` | `{kinds?, limit?, maxLy?}` | hypershunts, cryosleepers, gates, stable locations and the gate hauler, nearest first |

```
ss_dump(instance: "host", what: "market", args: { "marketId": "jangala" })
```

`markets` is an index, not a dock visit: it enumerates and stocks nothing. Use it to find the `marketId` that `market` wants. `survey` takes the same system id every other verb emits as a `locationId` (`system_16cf` and the like), not the display name.

`colonizable` answers "where do I put a colony" without searching the map, which is what the Phase 24 smoke needs before it can use `teleport`, `surveyset` and `give`. `limit` defaults to 10 and must be 1..200; `maxLy` is a range filter in light years, and 0 or absent means no filter. `candidateCount` is every planet that passed the filters, `count` is how many survived `maxLy` and `limit` — so "none within 8 LY" and "none anywhere" read differently.

```
ss_dump(instance: "host", what: "colonizable", args: { "limit": 3, "maxLy": 8 })

{ "fromLocationId": "corvus", "limit": 3, "maxLy": 8, "candidateCount": 214, "count": 3,
  "planets": [
    { "planetId": "ancyra", "name": "Ancyra", "type": "terran", "gasGiant": false,
      "systemId": "corvus", "systemName": "Corvus Star System",
      "distanceLy": 0, "distanceSu": 1487.5, "hazard": 1.25,
      "surveyLevel": "FULL", "unexploredRuins": false,
      "conditions": ["farmland_poor", "habitable", "ore_moderate"] },
    ...
  ] }
```

`distanceLy` is hyperspace distance to the planet's system and is 0 for anything in the fleet's own system; `distanceSu` is the in-system distance and is 0 for everything else, so the two together sort "here first, then nearest". Sorting is `distanceLy`, then `distanceSu`, then `planetId`, and the condition list is sorted, so two clients whose worldgen agrees return byte-identical rows and `ss_diff` on this verb is a real worldgen check.

What counts as colonizable is vanilla's own test, not a heuristic. A candidate is a non-star planet whose market is `planetConditionMarketOnly` — the flag colonizing clears, and the one `rules.csv` requires before it offers "Establish a colony" — in a system that is not hyperspace, not deep space, not `system_abyssal`, and not `system_cut_off_from_hyper`. The last three are the tooltips `PlanetSurveyPanel` prints when it disables the colonize button, and two of them appear in no rule and in no API source. Gas giants are in; vanilla colonizes them. `temporary_location` is filtered too, which is the one deliberate step past vanilla: those systems are minted and discarded by the abyssal encounter generators, so offering one as a target would be offering something that stops existing.

Two of vanilla's gates are reported instead of applied, because the run itself can clear them: `surveyLevel` (colonizing needs `FULL`, which is what `ss_act(verb: "surveyset")` is for) and `unexploredRuins` (salvage them and the button unlocks). Filtering on those would hide exactly the planets the smoke is allowed to set up.

`landmarks` is the other half of picking a site: the notable objects a colony gets sited relative to. Five kinds, and the `kinds` argument takes either an array or a comma-separated string:

| kind | found by | extras |
| --- | --- | --- |
| `hypershunt` | tag `coronal_tap` | `usable`, `benefitRangeLy` |
| `cryosleeper` | tag `cryosleeper` | `usable`, `benefitRangeLy`, `minBenefitMult` |
| `gate` | tag `gate` | `active`, `scanned`, `gatesActive`, `playerCanUseGates` |
| `stable_location` | tag `stable_location` | — |
| `gate_hauler` | spec id `derelict_gatehauler` | — |

```
ss_dump(instance: "host", what: "landmarks", args: { "kinds": "hypershunt,cryosleeper", "maxLy": 12 })

{ "fromLocationId": "corvus", "kinds": ["hypershunt", "cryosleeper"], "limit": 25, "maxLy": 12,
  "candidateCount": 4, "count": 2,
  "landmarks": [
    { "kind": "cryosleeper", "entityId": "cryosleeper_calypso", "name": "Domain-era Cryosleeper \"Calypso\"",
      "type": "derelict_cryosleeper", "systemId": "system_a41c", "systemName": "Tuvalu Star System",
      "hyperspace": false, "distanceLy": 6.82, "distanceSu": 0,
      "usable": true, "benefitRangeLy": 10, "minBenefitMult": 0.1 },
    ...
  ] }
```

`limit` defaults to 25. `candidateCount` counts what the requested kinds found sector-wide, before `maxLy` and `limit` trimmed it. Sorting is `distanceLy`, then `distanceSu`, then `kind`, then `entityId` — total and stable, so `ss_diff what: "landmarks"` is a worldgen check rather than a reorder report.

**These are not all one-per-sector.** Hypershunts and cryosleepers are exactly two each and the gate hauler is exactly one, but a stock sector has 15-20 gates plus a second-pass batch, and more stable locations than that. `kinds` and the limit are how you keep the answer readable.

**`benefitRangeLy` is read off the engine, not written here.** It is `ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS` for the hypershunt and `Cryorevival.MAX_BONUS_DIST_LY` for the cryosleeper — both `10` in stock 0.98a, both non-final `public static` fields, so a modded install reports its own value and the field simply disappears if the read fails. Nothing else on the list gets a range, because nothing else has a colony effect with a radius.

**Cross-referencing it is still your job, and two things make that less obvious than it looks.** Vanilla measures both radii from the *colony's* hyperspace position, not the player fleet's, so the `distanceLy` in the row is not the distance the game will test — it tells you which landmark to aim near, not whether a given planet qualifies. And both checks run over discovered intel (`HypershuntIntel`, `CryosleeperIntel`), so an undiscovered landmark counts for nothing however close it is. The hypershunt effect is binary at the radius; the cryosleeper's is graded, from `1.0` on top down to `minBenefitMult` at the edge and `0` past it.

There is deliberately no "occupied" field on `stable_location`, and that is not an omission. Vanilla does not mark one as used — `Objectives.build` creates the relay/array/buoy as a new entity, copies the orbit across, and then *removes* the stable location. A `stable_location` that still exists is free by construction, and destroying the objective spawns a fresh one back.

Of the four gate fields, `active` is the weakest: it reads a flag the gate's plugin only sets inside `advance()`, so a gate this client has never had loaded reads `false` even when it is scanned and usable. Trust `scanned && gatesActive`. The sector-wide pair is read from sector memory rather than through `GateEntityPlugin.areGatesActive()`, because that method ORs in "the player is carrying a Janus Device" — one client's cargo, which would make the same sector answer differently on host and guest.

Story one-offs are not included: the Ziggurat wreck, the Alpha Site, the red planet, the Nameless Rock, Galatia. They are identified by memory flags rather than tags, several do not exist at worldgen at all (the Ziggurat is created only once its guardian is beaten), and none of them changes where you would put a colony.

`visibility.view` is the half worth diffing. The guest reports the visibility level it actually has on each fleet; the host reports its estimate of that same level, asked of the engine through the guest's reverse mirror. Equal maps mean the two sensor models agree, and every key that differs is a replication gap. `lines` is the same computation as text for reading, which is why it is on the default ignore list below.

Two things about `market` that are behaviour, not bugs. On the host it runs the same `ensureOpenMarketStocked` a real dock visit runs, so calling it stocks the market. On the guest it reports raw current cargo, and a market the guest has never docked at comes back with `"stocked": false` rather than an error.

### `ss_diff(what, args?, tolerance?, ignore?)`

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
- `ignore` is a list of key names skipped at any depth, and it is echoed back as `ignored`. The default is `["role", "engineId", "lines"]`: the role is per-instance by definition, engine fleet ids are assigned locally on each client, and `lines` is the visibility probe's prose. Passing a list replaces that default instead of extending it, so `ignore: []` compares everything and `ignore: ["role", "engineId", "lines", "isPlayer"]` also drops the fleets flag that says which fleet is the local one. An ignored name is not used as a collection key either, so ignoring `engineId` cannot pair up two rows that are not the same fleet.

The two player fleets carry the same key on both instances: `coopFleetId` is `player:<playerId>` for a player fleet and for its mirror on the other client, so one logical fleet is one row in the diff. `engineId` keeps the local engine id.

### `ss_act(instance, verb, args?)`

One state-changing verb against one instance.

| `verb` | `args` |
| --- | --- |
| `teleport` | `{x, y, locationId}` |
| `pause` | `{on: true}` or `{on: false}` |
| `ability` | `{abilityId}` or `{abilityId, on: true}` / `{abilityId, on: false}` |
| `setcr` | `{value, memberIndex}` or `{value, memberIndex: "all"}` |
| `give` | `{commodityId?, qty?, credits?}` |
| `objective` | `{entityId, factionId}` |
| `surveyset` | `{planetId, level}` |
| `expedition` | `{}` or `{factionId}` — host only |

```
ss_act(instance: "guest", verb: "ability", args: { "abilityId": "interdiction_pulse" })
```

`ability` goes through the same engine path the toolbar button uses, down to `reportPlayerActivatedAbility`, so the mod's listener fires and the host sees `ABILITY_ACTIVATE`. With no `on` argument it is one press of the button, whatever state the ability was in, which is what a one-shot like the distress call wants. Add `on` to make it a level instead: `on: true` activates only if the ability is off, `on: false` deactivates only if it is on, and either is a no-op otherwise. Without it a toggle like the transponder could only ever be re-armed, never turned off. `surveyset` does not: it sets the survey level at the engine level, which is faithful to what the replication poll watches but skips the survey dialog. Check the dialog path by hand.

`expedition` exists so the Phase 24 expedition-warning check does not have to sit through months of game time waiting for one. It calls `PunitiveExpeditionManager.createExpedition`, the same public method the manager calls itself once a faction's anger passes its threshold, and the `PunitiveExpeditionIntel` that comes out registers with the intel manager the way an organic one does. The only guard it skips is `punExMaxConcurrent`. With no `factionId` it picks the first faction that has a live reason, preferring one with an `ANTI_FREE_PORT` reason so a repeated run picks the same faction; factions already running an expedition are skipped, because the manager holds one intel handle per faction.

It runs on the host only. The guest's `PunitiveExpeditionManager` is on the Phase 13 suppressor list, so a guest-side call is refused by name; the resulting warning reaches the guest through the Phase 24 sync, which scans `RaidIntel` and picks the new intel up on its next one-second poll.

```
ss_act(instance: "host", verb: "expedition")

{ "role": "HOST", "factionId": "hegemony", "created": true,
  "reasonCount": 3, "reasonTypes": ["ANTI_FREE_PORT"], "trackedBefore": true, "ongoing": 1,
  "targetMarketId": "player_colony_1", "targetMarketName": "New Kaunas", "etaDays": 47.315 }
```

`createExpedition` returns `void` and bails silently from five places, so success is read off `PunExData.intel` afterwards rather than assumed. Every failure comes back as an error naming the cause: no faction carries `punitiveExpeditionData`, the named faction has none, the faction has no live reason, one is already running, or vanilla picked a reason and then found no colony at or above `punExMinColonySizeForNonTerritorial`, no market to stage from, or no raidable spaceport. The reason a caller can create on demand is free port: turn it on at any player colony outside hyperspace and every `vsFreePort` faction (`hegemony`, `luddic_church`, `sindrian_diktat` in vanilla) gets a reason with no other preconditions, weighted `max(1, size - 2) * 5`.

### `ss_advance_days(days, timeoutSeconds?)`

Unpauses the host, polls its clock until it has advanced `days` game days, then pauses it again. The guest follows the host clock, so only the host is driven. Nothing in the mod implements this; it is `pause` and `status` in a loop.

```
ss_advance_days(days: 5)

{ "requestedDays": 5, "achievedDays": 5.0021,
  "startDate": "day 3, month 2, cycle 206", "endDate": "day 8, month 2, cycle 206",
  "elapsedRealSeconds": 51.4, "timedOut": false, "repaused": true }
```

One game day is about ten real seconds at normal speed (`CampaignClock.SECONDS_PER_GAME_DAY` is 10), so five days costs roughly a minute of wall time. Fast-forward is disabled until Phase 7b, and this tool does not touch it. The default budget is three times the nominal duration plus 20 seconds; raise it with `timeoutSeconds`. The host is re-paused whether the wait finished, timed out, or failed, and `repaused` reports whether that last pause call landed.

When the clock does not move, the result carries a `stall` object naming what held it:

```
{ ..., "timedOut": true, "achievedDays": 0,
  "stall": { "instance": "guest", "reason": "blockingScreenOpen",
             "pause": { "host": { ... }, "guest": { ... } } } }
```

It polls `status` on both instances and reads the pause blocks, before the re-pause rather than after, so the host's own re-pause intent is not the answer to every stalled advance. `reason` is the first true term of the host's OR (`hostIntent`, `guestScreenIntent`, `guestKeyIntent`, `eitherInCombat`), then an open screen on either client, then `unknown` when neither pause block explains it — which is itself the finding, since it means something outside the coordinator is holding the clock. An unreachable instance is reported under `stall.unreachable` and does not sink the diagnosis.

## What this will not do

There is no verb for market buy/sell, officer hire, bar-offer accept, or market open/close, and adding one would defeat the checks it looked like it was helping with. Each of those is on the smoke checklist precisely because a UI listener drives it: `PlayerMarketTransaction` for trades, the dialog close-diff for hire claims, snapshot-on-open for market state. Calling the engine method underneath the listener would pass while the listener was unhooked. Those four stay manual. Asking for one by name returns that reason instead of an error code.

No screenshots, no vision, no keyboard or mouse injection, no save or load control.

## Tests

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop\tools\starsector-mcp'; npm test"
```

`test/mock-bridge-test.js` stands up a mock bridge, a plain `net` server speaking the same newline-JSON protocol, and runs the client and the diff against it. It covers out-of-order response correlation, the request timeout, reconnect-and-retry after a mid-request socket drop, the unreachable-port message, `ok:false` passthrough, order-insensitive and index-based diffing, the default and overridden `ignore` lists, and the `ss_advance_days` pause-poll-pause loop with its stall diagnosis. Starsector does not need to be running.

## Layout

```
index.js              MCP tool schemas and stdio wiring
lib/tools.js          instance registry, verb tables, the five tool implementations
lib/bridge-client.js  TCP client: id correlation, timeout, reconnect
lib/diff.js           structural JSON diff
test/                 mock bridge and assertions
```
