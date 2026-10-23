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

Each instance's bridge serves up to **four clients at once**, so a scripted helper can hold a connection alongside this server; the fifth is closed on connect and the refusal is logged. Every client gets its own request framing and response queue, and the four-commands-per-frame dispatch budget is shared out one request per client per pass, so a client sending a burst cannot starve another.

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

`status.lightGrantFault` reports the transient QA reward-interruption hook: `armed`, the current `arm`, `lastTrip` and `lastDisarm`. `ss_dump(what:"lights")` carries the same block. These snapshots do not arm the hook or grant rewards; the explicit `lightgrantfault` command below always uses `ss_act`.

`status.lightMessageHold` reports the transient real-message hold: `armed`, `held`, the current `arm` and `lastReceipt`. Use this snapshot to inspect an uncertain action response; the explicit `lightmessagehold` command, including its status mode, always uses `ss_act`.

### `ss_dump(instance, what, args?)`

One read-only verb against one instance, returned as-is. Verbs and their arguments:

| `what` | `args` | returns |
| --- | --- | --- |
| `status` | none | same payload as `ss_status` |
| `fleets` | `{locationId?}` | per fleet: ids, name, faction, position, velocity, transponder, action text, members |
| `cargo` | none | the local player fleet's load: supplies, fuel, crew, marines, credits, the three capacities with used/free, and `overloaded` |
| `market` | `{marketId}` | full stock including ship details, specials, hireables |
| `markets` | none | every market in the economy: `marketId`, `name`, `factionId`, `size`, `locationId` |
| `barpool` | none | ordered offer list plus the bar's render order |
| `survey` | `{systemId}` or `{systemId: "all"}` | planetId to survey level and ruins state |
| `visibility` | `{fleetId?}` | `lines`, the probe's text dump, plus `view`, a coopFleetId to visibility-level map |
| `colonizable` | `{limit?, maxLy?, neutralOnly?}` | uncolonized planets nearest the local player fleet, nearest first |
| `hirable` | `{limit?, maxLy?}` | markets holding officers or administrators for hire, nearest the local player fleet first; the game only rolls officers when a player opens a market screen, so `candidateCount: 0` on a fresh campaign means dock at a big hub first |
| `landmarks` | `{kinds?, limit?, maxLy?}` | hypershunts, cryosleepers, gates, stable locations and the gate hauler, nearest first |
| `streams` | none | campaign timestamp, stream ids/kinds, params, segment geometry/faders, native fade state and effective brightness sampled every 1,000 units |
| `lights` | `{limit?:1..512}` | read-only native hyperspace lights: canonical ids, position, complete native params, lifetime, dweller flag, lossless salvage seed, despawn/`fadingOutAndExpiring` state, truncation counts, local `actorBonus`, `lightClaims`, `deferredActions` and transient `lightGrantFault` receipt; never adopts lights or creates reward scripts |
| `screen` | `{lightId?:"light:<UUID>"}` | local native UI state and `lightInteraction`, including `claimState`, cached `deferredActions`, exact selected `nativeLight` flags and weak exact-fleet cleanup observations; never acquires/renews a claim |

```
ss_dump(instance: "host", what: "market", args: { "marketId": "jangala" })
```

`markets` is an index, not a dock visit: it enumerates and stocks nothing. Use it to find the `marketId` that `market` wants. `survey` takes the same system id every other verb emits as a `locationId` (`system_16cf` and the like), not the display name.

`cargo` reports the local player fleet only, and each instance answers for its own — `ss_diff(what: "cargo")` compares two different fleets, so a difference there is normal, not a desync. The three capacities are independent: `cargoSpace`, `fuelSpace` and `personnel`, each with `capacity`, `used` and `free`, where `free` is the same `capacity - used` subtraction vanilla exposes as `$cargoRoom`, `$fuelRoom` and `$crewRoom` and goes negative when the fleet is over. `overloaded` is true when any of the three is over its limit and `over` names which, so "the fleet is overloaded" and "the fleet is overloaded on fuel" are one call apart. `personnel` counts everyone aboard, so it is larger than `crew` on a fleet carrying marines.

```
ss_dump(instance: "host", what: "cargo")

{ "engineId": "fleet_1", "supplies": 12, "fuel": 90, "crew": 40, "marines": 12, "credits": 250000,
  "cargoSpace": { "capacity": 200, "used": 260, "free": -60 },
  "fuelSpace": { "capacity": 300, "used": 90, "free": 210 },
  "personnel": { "capacity": 200, "used": 52, "free": 148 },
  "overloaded": true, "over": ["cargoSpace"] }
```

`colonizable` answers "where do I put a colony" without searching the map, which is what the Phase 24 smoke needs before it can use `teleport`, `surveyset` and `give`. `limit` defaults to 10 and must be 1..200; `maxLy` is a range filter in light years, and 0 or absent means no filter. `candidateCount` is every planet that passed the filters, `count` is how many survived `maxLy`, `neutralOnly` and `limit` — so "none within 8 LY" and "none anywhere" read differently.

```
ss_dump(instance: "host", what: "colonizable", args: { "limit": 3, "maxLy": 8, "neutralOnly": true })

{ "fromLocationId": "corvus", "limit": 3, "maxLy": 8, "neutralOnly": true,
  "candidateCount": 214, "count": 3,
  "planets": [
    { "planetId": "ancyra", "name": "Ancyra", "type": "terran", "gasGiant": false,
      "systemId": "corvus", "systemName": "Corvus Star System",
      "x": 8123.5, "y": -2210.75, "marketsInSystem": 0,
      "distanceLy": 0, "distanceSu": 1487.5, "hazard": 1.25,
      "surveyLevel": "FULL", "unexploredRuins": false,
      "conditions": ["farmland_poor", "habitable", "ore_moderate"] },
    ...
  ] }
```

`x` and `y` are the planet's current location-local coordinates, which with `systemId` are exactly what `ss_act(verb: "teleport")` wants — or skip the pair and pass `teleport` the `planetId` as its `entityId`. Do not derive a planet's position from its orbit by hand; planets orbit, so the answer changes with the clock, and this field is the engine's own reading of it.

`marketsInSystem` counts *economy* markets in the planet's system, and `neutralOnly: true` keeps only the rows where that is 0 — "find me somewhere no faction is already sitting" in one query instead of a cross-reference against the `markets` dump. It is a count of live colonies: every uncolonized planet carries a planet-condition market of its own and none of those are in the economy, and decivilizing a colony removes it from the economy too, so 0 really does mean nobody is there. The filter runs before `limit`, so `limit: 3, neutralOnly: true` is three neutral planets rather than the three nearest planets with the occupied ones struck out.

`distanceLy` is hyperspace distance to the planet's system and is 0 for anything in the fleet's own system; `distanceSu` is the in-system distance and is 0 for everything else, so the two together sort "here first, then nearest". Sorting is `distanceLy`, then `distanceSu`, then `planetId`, and the condition list is sorted, so two clients whose worldgen agrees return the same rows in the same order and `ss_diff` on this verb is a real worldgen check. `x`/`y` move as planets orbit, which is not a diff hazard: both instances read them off the same shared clock.

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
      "hyperspace": false, "x": -4120, "y": 980, "distanceLy": 6.82, "distanceSu": 0,
      "usable": true, "benefitRangeLy": 10, "minBenefitMult": 0.1 },
    ...
  ] }
```

`x` and `y` mean the same thing they do on a `colonizable` row: location-local coordinates, ready to hand to `teleport` with `systemId`, or skip them and pass `teleport` the `entityId`. For a `hyperspace: true` row they are hyperspace coordinates, which is still what teleporting there wants. `limit` defaults to 25. `candidateCount` counts what the requested kinds found sector-wide, before `maxLy` and `limit` trimmed it. Sorting is `distanceLy`, then `distanceSu`, then `kind`, then `entityId` — total and stable, so `ss_diff what: "landmarks"` is a worldgen check rather than a reorder report.

**These are not all one-per-sector.** Hypershunts and cryosleepers are exactly two each and the gate hauler is exactly one, but a stock sector has 15-20 gates plus a second-pass batch, and more stable locations than that. `kinds` and the limit are how you keep the answer readable.

**`benefitRangeLy` is read off the engine, not written here.** It is `ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS` for the hypershunt and `Cryorevival.MAX_BONUS_DIST_LY` for the cryosleeper — both `10` in stock 0.98a, both non-final `public static` fields, so a modded install reports its own value and the field simply disappears if the read fails. Nothing else on the list gets a range, because nothing else has a colony effect with a radius.

**Cross-referencing it is still your job, and two things make that less obvious than it looks.** Vanilla measures both radii from the *colony's* hyperspace position, not the player fleet's, so the `distanceLy` in the row is not the distance the game will test — it tells you which landmark to aim near, not whether a given planet qualifies. And both checks run over discovered intel (`HypershuntIntel`, `CryosleeperIntel`), so an undiscovered landmark counts for nothing however close it is. The hypershunt effect is binary at the radius; the cryosleeper's is graded, from `1.0` on top down to `minBenefitMult` at the edge and `0` past it.

There is deliberately no "occupied" field on `stable_location`, and that is not an omission. Vanilla does not mark one as used — `Objectives.build` creates the relay/array/buoy as a new entity, copies the orbit across, and then *removes* the stable location. A `stable_location` that still exists is free by construction, and destroying the objective spawns a fresh one back.

Of the four gate fields, `active` is the weakest: it reads a flag the gate's plugin only sets inside `advance()`, so a gate this client has never had loaded reads `false` even when it is scanned and usable. Trust `scanned && gatesActive`. The sector-wide pair is read from sector memory rather than through `GateEntityPlugin.areGatesActive()`, because that method ORs in "the player is carrying a Janus Device" — one client's cargo, which would make the same sector answer differently on host and guest.

Story one-offs are not included: the Ziggurat wreck, the Alpha Site, the red planet, the Nameless Rock, Galatia. They are identified by memory flags rather than tags, several do not exist at worldgen at all (the Ziggurat is created only once its guardian is beaten), and none of them changes where you would put a colony.

`visibility.view` is the half worth diffing. The guest reports the visibility level it actually has on each fleet; the host reports its estimate of that same level, asked of the engine through the guest's reverse mirror. Equal maps mean the two sensor models agree, and every key that differs is a replication gap. `lines` is the same computation as text for reading, which is why it is on the default ignore list below.

Two things about `market` that are behaviour, not bugs. On the host it runs the same `ensureOpenMarketStocked` a real dock visit runs, so calling it stocks the market. On the guest it reports raw current cargo, and a market the guest has never docked at comes back with `"stocked": false` rather than an error.

`lights.lightClaims` and `screen.lightInteraction.claimState` expose the current manual/preclaim light ids and tokens, selected token/holder, adapter identities and at most 128 actual ACCEPT/REJECT observations. Rows include sequence, direction, reply token, current token and `matched`; `evictedObservations` reports lost history. On Guest inbound replies, `matched` is the production comparison with the current generation. Host outbound observation alone is not evidence that Guest received or applied a reply. Observations require diagnostics enabled; empty or evicted history cannot establish that no reply occurred.

`deferredActions` has `pending`, `pendingCount`, `pendingTruncated`, `journal`, `maxEvents:256` and `evictedEvents`. Attempt rows carry stable `observationId`, `originalClaimToken` captured when the action queues, `tokenObserved`/`tokenReadError`, owner/sector/player/dialog/light identities, state/reason, queue times and native action execution/return counts and failure details. These are cached primitive snapshots; reads never invoke native permission checks, call a token provider or renew a claim. Journal eviction and token errors limit what a capture can prove.

For exact native fading state, call `ss_dump(instance:"guest", what:"screen", args:{lightId:"light:<UUID>"})`. `lightInteraction.nativeLight` scans that selected canonical light independently of the bounded general census. It reports match count and up to two matching native entities with `engineId`, `alive`, `despawning` and `fadingOutAndExpiring`, plus `complete`/`truncated` and read errors. Scan completeness alone does not prove a unique canonical entity; check the match count and errors. General `lights` rows also include the fading tag. These diagnostics support new native tests; they do not repair older truncated tag captures or missing-nonce observations.

On `8fd6b06`, native audit 28 passes 181 checks of one same-session Leave/old-ACCEPT isolation case using these probes. Native Leave cancels the exact queued observation; a fresh N2 story/lease is accepted, then actual delivery of the original held N1 ACCEPT records current N2 and `matched:false`. The old action remains at zero executions/returns and the fresh lease survives. This does not establish N2 deferred execution/combat or expiry/new-session reply handling. See the [bounded native evidence](../../docs/P26_M3_NATIVE_ACCEPTANCE_2026-10-20.md).

### `ss_diff(what, args?, tolerance?, ignore?)`

`lights` uses a strict comparison: both manifests must be complete, every row must have a valid managed canonical identity, and every native gameplay/recipe field compares exactly. Local `engineId`, actor-local state counts and `actorBonus` are excluded. Matching raw campaign timestamps produce `PASS` or `FAIL`; unequal or unsafe timestamps produce `INCONCLUSIVE`, even when the fields match. `maxDifferences` limits displayed mismatches while `counts.differing` retains the full count. Nonzero tolerance and gameplay ignore keys are refused.

`actorBonus` reports `{present,burnReserve,sensorReserve}` from an existing native `AbyssalLightBonus` script on the local player's fleet. The query scans `fleet.getScripts()` and reads its getters; it never calls the native create-if-missing `get(fleet)` method. Duplicate native bonus scripts are an error. These floats are travel reserves: one unit lasts three light-years, rather than representing a flat burn or sensor increase. Player rewards first enter native pending fields and settle after more than one unpaused native frame. Keep the fleet stationary in actual abyssal depth of at least 1 while comparing before/after reserves; travel drains them and leaving the abyss clears them. Compare actor-local deltas separately from the shared-light world diff.

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

`streams` comparisons require exactly matching campaign timestamps, even when `ignore` or
`tolerance` would otherwise hide the difference. Pause both clients and run `ss_align_clocks`
before triggering a new fade. During a fade, compare naturally equal timestamps; do not align
again, because the helper changes the calendar without advancing native fade timers. Geometry and params should match exactly; P26's live fade
gate allows an effective-brightness difference of at most 0.05 at each sampled point.

### `ss_act(instance, verb, args?)`

One state-changing verb against one instance.

| `verb` | `args` |
| --- | --- |
| `teleport` | `{entityId}` or `{x, y, locationId}` — not both |
| `pause` | `{on: true}` or `{on: false}` |
| `clockalign` | `{targetTimestamp}` — paused active session only; forward by at most one game day |
| `ability` | `{abilityId}` or `{abilityId, on: true}` / `{abilityId, on: false}`; explicit `grantMissing:true` can add a missing native ability for test setup |
| `setcr` | `{value, memberIndex}` or `{value, memberIndex: "all"}` |
| `give` | `{commodityId?, qty?, credits?}` |
| `addship` | `{variantId, count?}` — count defaults to 1, capped at 20 |
| `objective` | `{entityId, factionId}` |
| `surveyset` | `{planetId, level}` |
| `expedition` | `{}` or `{factionId}` — host only |
| `streamadd` | `{rearmUsedRoute?}` — host only; invokes native seasonal stream creation, reports actual new ids/configuration state |
| `streamdespawn` | `{}` — host only; starts native seasonal despawn with no delay and a ten-day fade |
| `lightspawn` | `{kind?:normal\|pair\|cluster\|large\|dweller, x, y, seed?:decimalString}` — Host-only native light creation; never changes the calendar or retries an uncertain request |
| `lightgrantfault` | `{mode:"arm", requestId:"<existing pending UUID>"}`, `{mode:"status"}` or `{mode:"disarm"}`; one-shot post-native-grant QA interruption, always action-only and never retried after a dropped socket |
| `lightmessagehold` | `{mode:"arm", stage:"claim_reply", lightId, seconds?:1..600}` or `{mode:"arm", stage:"consumption_request", lightId, action:"PULSE"\|"SENSOR_BURST"\|"DWELLER", seconds?:1..600}`; other modes `status`, `release`, `drop`; all action-only with no socket retry |

`lightmessagehold` holds a real Host/Guest light message without blocking other traffic or fabricating protocol state. Arm/release require the configured agent bridge, diagnostics and a paused active Host gameplay session. Use existing `-Bridge` and `-Diagnostics` launch options. `lightId` must be canonical. `claim_reply` accepts no `action` and latches only a real accepted Host arbitration result before sending it. `consumption_request` requires the exact uppercase action and latches a real Guest `LIGHT_REQUEST` before Host application. Once latched, only retries of that token/request identity remain held; fresh identities and unrelated traffic continue normally.

`seconds` is an integer from 1 to 600, default 30, and the deadline begins at arm rather than capture. Use explicit 600 for planned native battle checks and finish within that deadline. Timeout drops the held copy; session, connection generation, Sector or handler replacement invalidates it, as does diagnostics disable. No old-session delivery is forced into a new context. Status/drop remain available with diagnostics off through the loaded campaign's bridge. Drop clears only instrumentation, never a ledger or the gameplay request, so ordinary retries can resume afterward.

Release consumes the hold before using the original envelope through normal send/dispatch. The receipt reports `armed`, `held`, `arm` and `lastReceipt`, with stage, context identities, deadline, latched identity, matching-message count and original sequence metadata. `lastReceipt.releaseDisposition` records `original_reply_enqueued`, `original_request_dispatched` or a release failure. These are transport dispositions, not proof of Guest receipt or native reward application; correlate subsequent claim/deferred, ledger and native observations. All modes use `ss_act`, including status; `ss_dump` refuses `what:"lightmessagehold"`. Use `ss_status` for a retryable receipt after an uncertain socket response.

For fault-hook setup, first arm a `consumption_request` hold, then create the real Guest request, read its actual P UUID without writing memory and arm `lightgrantfault`. Release the held request through Host normally. For pending DWELLER tests, the request must originate from native `battleContextCreated`; opening and leaving a story cannot create it. The Guest PULSE hold/grant Save/Load case has bounded native acceptance on `8fd6b06`; same-session native Leave/old-ACCEPT isolation also passes in audit 28, while expiry/new-session replies and pending-DWELLER UI/battle cases remain pending. A battle that outlasts the hold cannot establish held-request coverage, and an enqueue/dispatch receipt alone closes no M3 gate.

`lightgrantfault` arms only an exact existing local Guest PULSE or SENSOR_BURST request with no uncertain/applied guard. It requires the configured agent bridge, diagnostics, an active gameplay session and the campaign paused. Use the existing launch-script `-Bridge` and `-Diagnostics` options; there is no new configuration flag. DWELLER requests, missing/future request ids, a second arm and mismatched contexts fail explicitly. While delivery is held, read the real pending UUID with `ss_act(instance:"guest", verb:"memory", args:{scope:"global", key:"coopLightState"})` without a `value`, then arm it; never fabricate or replace the ledger.

The arm binds the current World, Sector, actor, request, light and action. It is transient, disarms on reset, adapter/campaign replacement, outcome mismatch/rejection or diagnostics disable, and consumes itself before throwing once after the real native reward returns. The Guest network catch preserves the campaign with pending plus uncertain state and no applied guard. A normal native Save can then capture that state for Load testing; on retry, the saved uncertain guard refuses a second grant. Explicit disarm never clears the guard or edits the ledger.

All three modes use `ss_act`, including `mode:"status"`; `ss_dump` refuses `what:"lightgrantfault"`. Read `ss_status` or `ss_dump(what:"lights")` for retryable snapshots instead. The receipt has `armed`, `arm`, `lastTrip` and `lastDisarm`; arm details identify the binding, and a trip records `phase:"post_native_grant_before_applied"`. Status/disarm still work with diagnostics disabled for cleanup, through a loaded campaign's bridge. A dropped arm response is uncertain: inspect the receipt before issuing another action.

For native acceptance, capture the actual saved P+U/no-A state and native reward reserves/queued points, then use standard native Load and prove no second grant. `lights.actorBonus` exposes burn/sensor reserves, but `AbyssalLightBonus.pendingTopographyPoints` has no API getter and must be inspected in the normal native save XML. Paused settled intel alone can miss a reward already queued. One real Guest PULSE interruption/Save/Load/replay case passes on `8fd6b06` in the [352-check native audit](../../docs/P26_M3_NATIVE_ACCEPTANCE_2026-10-20.md): exact P/U survive, six queued points settle once and new retries refuse a second grant. An empty active arm proves disarmed state even when the same-JVM static `lastTrip` receipt remains. Other native failure forms and Sensor Burst remain outside that bounded grant pass. Audit 28 separately passes same-session Leave/old-ACCEPT isolation; expiry/new-session reply and pending-DWELLER/reconnect cases remain open.

`ability` normally refuses an ability the player does not own. For test setup,
`{abilityId:"generate_slipsurge", grantMissing:true, on:false}` validates the native ability spec,
adds missing ownership to the character's saved ability list, obtains the native fleet plugin, and
leaves it inactive. The engine removes fleet-only grants when it reconciles abilities. The response
reports `grantedMissing`; repeated requests preserve an existing plugin and its state. A missing
character list, ownership readback, or native plugin fails the request.

Vanilla remembers which seasonal routes it has used. Default `streamadd` can therefore return
`created:false` with `reason: "routes_exhausted"`. After all seasonal terrain is gone, explicit
`rearmUsedRoute:true` temporarily rearms one used route and calls the native builder, restoring
the used flag even if generation fails. It refuses missing/empty configuration, live seasonal
terrain or remaining unused routes, and fails if no genuinely new terrain appears.

```
ss_act(instance: "guest", verb: "ability", args: { "abilityId": "interdiction_pulse" })
```

`teleport` takes either an entity or a point, and refuses both together rather than picking one. `{entityId: "ancyra"}` resolves any entity in the sector — planet, station, gate, cryosleeper, anything with an id — and parks the fleet `radius + 200` units off it, on the +x side so two runs of the same request land in the same place. That is the mode to use for a planet: a planet's coordinates are a function of its orbit and the clock, so hand-deriving them from the orbit definition is both work and a reliable source of wrong answers. `{locationId, x, y}` is still there for a bare point.

```
ss_act(instance: "host", verb: "teleport", args: { "entityId": "ancyra" })

{ "locationId": "corvus", "x": 8223.5, "y": -2210.75, "movedFrom": "hyperspace",
  "entityId": "ancyra", "entityName": "Ancyra", "transition": "jump", "pending": true }
```

**A teleport that crosses locations is a real jump, and it takes game time.** Moving a fleet between systems by re-parenting it leaves it unable to fly — the engine hands player input only to the location it considers current, so a fleet dropped into a system the engine has not switched to receives none. The location switch is one of the things `doHyperspaceTransition` does, so a cross-location teleport calls that instead, and `transition` says which path ran: `"jump"` for the engine transition, `"local"` for a same-location placement, which is unchanged and still immediate. `pending: true` means the fleet is still on its way and the reported `x`/`y` are its destination. The transition is an ordinary frame script and **does not advance while the game is paused**, so after a cross-location teleport let the clock run — `ss_advance_days(days: 0.5)` is more than enough — before expecting `ss_status` to report the new location. A second teleport issued mid-jump is refused by name rather than silently ignored.

`ability` goes through the same engine path the toolbar button uses, down to `reportPlayerActivatedAbility`, so the mod's listener fires and the host sees `ABILITY_ACTIVATE`. With no `on` argument it is one press of the button, whatever state the ability was in, which is what a one-shot like the distress call wants. Add `on` to make it a level instead: `on: true` activates only if the ability is off, `on: false` deactivates only if it is on, and either is a no-op otherwise. Without it a toggle like the transponder could only ever be re-armed, never turned off. `surveyset` does not: it sets the survey level at the engine level, which is faithful to what the replication poll watches but skips the survey dialog. Check the dialog path by hand.

`addship` is the counterpart to `give`: `give` moves commodities and credits, `addship` adds ships. A one-ship test fleet overloads at 200 supplies and there was no other way to add a freighter without restarting the instance. Ships are created through the engine's fleet factory, added to the local player fleet and set to full CR and no damage, so they are usable the moment they appear. `count` defaults to 1 and is capped at 20.

An unknown `variantId` is refused by name. That check is not politeness: `createFleetMember` does not reject an unknown id, it substitutes a placeholder hull and returns successfully, so without the check a typo would quietly add the wrong ships. The response carries the fleet hash before and after with `fleetHashChanged`, which is what the replicator keys a roster resend on; the same before/after pair goes into `starsector.log`, so a ship that was added but did not move the hash is visible rather than mysterious.

```
ss_act(instance: "host", verb: "addship", args: { "variantId": "tarsus_Standard", "count": 2 })

{ "variantId": "tarsus_Standard", "requested": 2, "added": 2, "fleetSize": 3,
  "members": [ { "memberId": "...", "variantId": "tarsus_Standard", "hullId": "tarsus", "cr": 0.7 }, ... ],
  "fleetHashBefore": "9f0c...", "fleetHashAfter": "1ab7...", "fleetHashChanged": true }
```

`expedition` exists so the Phase 24 expedition-warning check does not have to sit through months of game time waiting for one. It calls `PunitiveExpeditionManager.createExpedition`, the same public method the manager calls itself once a faction's anger passes its threshold, and the `PunitiveExpeditionIntel` that comes out registers with the intel manager the way an organic one does. The only guard it skips is `punExMaxConcurrent`. With no `factionId` it picks the first faction that has a live reason, preferring one with an `ANTI_FREE_PORT` reason so a repeated run picks the same faction; factions already running an expedition are skipped, because the manager holds one intel handle per faction.

It runs on the host only. The guest's `PunitiveExpeditionManager` is on the Phase 13 suppressor list, so a guest-side call is refused by name; the resulting warning reaches the guest through the Phase 24 sync, which scans `RaidIntel` and picks the new intel up on its next one-second poll.

```
ss_act(instance: "host", verb: "expedition")

{ "role": "HOST", "factionId": "hegemony", "created": true,
  "reasonCount": 3, "reasonTypes": ["ANTI_FREE_PORT"], "trackedBefore": true, "ongoing": 1,
  "targetMarketId": "player_colony_1", "targetMarketName": "New Kaunas", "etaDays": 47.315 }
```

`createExpedition` returns `void` and bails silently from five places, so success is read off `PunExData.intel` afterwards rather than assumed. Every failure comes back as an error naming the cause: no faction carries `punitiveExpeditionData`, the named faction has none, the faction has no live reason, one is already running, or vanilla picked a reason and then found no colony at or above `punExMinColonySizeForNonTerritorial`, no market to stage from, or no raidable spaceport. The reason a caller can create on demand is free port: turn it on at any player colony outside hyperspace and every `vsFreePort` faction (`hegemony`, `luddic_church`, `sindrian_diktat` in vanilla) gets a reason with no other preconditions, weighted `max(1, size - 2) * 5`.

### `ss_align_clocks()`

Aligns the clocks for paired QA samples while keeping the games paused. Both clients must report
an active session with the same nonempty session id, HOST/GUEST roles, and local pause; the host
must also report effective shared pause. The helper advances only the earlier timestamp to the
later one, refuses a gap over one game day, then rereads both clients to verify the same session,
pause and exact target timestamp. Already equal clocks produce a no-op result.

```
ss_align_clocks()
ss_diff(what: "streams", tolerance: 0.05)
```

The result includes `sessionId`, `targetTimestamp`, `alignedInstance`, `advanced`, and the `before`
and `after` timestamp pairs. Normal clock reconciliation permits a small offset; shared pause
alone does not make the timestamps identical. This tool is an explicit QA action. Like other
mutating commands, `clockalign` is not automatically retried after a dropped response.

Use it before a fade starts, not repeatedly during the fade. Calendar jumps leave native terrain
timers unchanged. The 2026-10-15 test demonstrated that repeated alignment alone created a
0.025-day fade-age difference and a misleading brightness failure.

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

`test/mock-bridge-test.js` stands up a mock bridge, a plain `net` server speaking the same newline-JSON protocol, and runs the client and the diff against it. It covers out-of-order response correlation, the request timeout, reconnect-and-retry after a mid-request socket drop on a read-only command (a mutating command is not retried, since the bridge has no request dedup; it throws `BridgeOutcomeUnknownError` instead), the unreachable-port message, `ok:false` passthrough, order-insensitive and index-based diffing, the default and overridden `ignore` lists, and the `ss_advance_days` pause-poll-pause loop with its stall diagnosis. Starsector does not need to be running.

## Layout

```
index.js              MCP tool schemas and stdio wiring
lib/tools.js          instance registry, verb tables, the five tool implementations
lib/bridge-client.js  TCP client: id correlation, timeout, reconnect
lib/diff.js           structural JSON diff
test/                 mock bridge and assertions
```
