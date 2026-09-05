# Phase 22 rescoped: tactical orders for your own joined ships

Feasibility pass, 2026-09-05. Source-based; nothing below was run in a live game. Three research
reports back this file, kept out of the repo under `K:\Starsector\tmp_ff_analysis\phase22-tactical\`
(`W1-combat-orders-api.md`, `W2-guest-map-ui.md`, `W3-transport-lifecycle.md`). Engine facts come
from the 0.98a API source and a CFR decompile of `starfarer_obf.jar`; file:line citations are in the
reports.

## The feature under assessment

Phase 33 puts the partner's fleet into the piloting player's battle as an AI-controlled allied fleet
(the mirror `CampaignFleetAPI` on the fighting engine). On top of that, the non-fighting player,
held in a paused campaign with no combat engine, opens a drawn tactical map of the battle at 5 to
10 Hz, selects their own ships, and issues fleet orders: engage, escort, defend or capture an
objective, cancel, retreat. The fighting engine applies them. Nobody pilots remotely.

## Verdict

Feasible through public API with no reflection. Every engine surface the feature needs exists and
several are better than assumed. The work is mostly mod-side: one new transport mechanism (draining
inbound during combat), a stable per-ship identity across the two engines, a GL-drawn panel, and the
loss reconciliation Phase 33 already needs. Seven live checks remain, each a single instrumented
launch; two of them can change the design.

## What the engine allows

**Orders to allied ships.** Side 0 owns a second task manager, `getTaskManager(true)`, that governs
exactly the deployed members flagged `isAlly()`. `Battle.genCombined` sets that flag on every member
of every non-player fleet on the player's side, so the mirror's ships land under it automatically.
All 22 `CombatAssignmentType` values are available; targets are deployed members, objectives, or
waypoints created with `createWaypoint2(loc, true)`. Vanilla's war room binds only the player-side
manager, so there is no vanilla UI for allied orders and every partner order is mod-issued. `IGNORE`
is guarded out for the ally manager; stations cannot be ordered.

**The allied admiral does not fight the orders.** Campaign battles install an ally-mode `AdmiralAI`
on side 0. Its reassignment pass runs every 0.5 to 1 s inside the task manager and skips members
holding a direct order, so a given order stays given. `getAdmiralAI().setNoOrders(true)` silences it
outright; vanilla's own Dweller and Threat strategy AIs use that exact pattern. Trap: setting it
before `preCombat` kills allied initial deployment. Set it after deployment.

**Command points.** The ally manager has its own pool, and `createAssignment(..., false)` is free:
the spend counter is pre-decremented. A paid order against an empty pool fails silently, so the
fighting engine reads `getAssignmentFor` back and reports accepted or rejected to the partner.

**Orders while paused apply.** `giveDirectOrder` runs the reassignment pass synchronously, and the
mod's combat plugin keeps running while the engine is paused (unverified for the plugin, see below).

**Identity.** The same `FleetMemberAPI` instances flow from the mirror into the combined fleet;
`getId()` is stable for the battle; `BattleAPI.getSourceFleet` and `getMemberSourceMap` map any
combat ship back to the mirror. The gap is on the mod side: `CoopFleetMirror.builtMemberIds` is a
positional list, and a roster rebuild recreates every member with a new id. Orders must be keyed on
the owner's `fleetMemberId` from the snapshot, and mirror rebuilds must be frozen for the battle.

**Deployment.** The allied admiral decides what deploys. Code can shape that through
`setDelegate` (`allowedToDeploy`, `doAdditionalInitialDeployment`) or deploy explicitly with
`spawnFleetMember`. There is no `canDeploy` gate in the API.

**Observation.** Positions, facing, velocity, hull, flux, CR, objectives, per-ship assignment,
retreat state, `getFogOfWar(0)` and map bounds are all readable per frame. Today's `BATTLE_STATUS`
sends id, name, side, hull and flux at 400 ms with a 200-ship cap; everything else is new payload.

**Losses.** The engine applies losses to the mirror's `FleetData` automatically because the mirror
joined the `BattleAPI`. Recovery is the one hard no: `FleetEncounterContext.getRecoverableShips`
skips own-side allied casualties outright. Workarounds exist (clear the transient ally flag before
recovery, or subclass the encounter context); Phase 33 keeps vanilla behaviour.

## What the mod already has

**Outbound during combat works, UDP included.** There are no threads in the transport; bytes move
when something calls into `CoopNetService`. `CoopBattleBridge.sendStatus` calls `send` and
`flushOutbound` from the combat frame, and `flushOutbound` also polls the socket, sends the 5 s UDP
keepalives and flushes the datagram queue. The half-open detector exempts a peer in combat, pinned by
three pump tests.

**Inbound during combat is the gap, by design.** The plan says inbound is deliberately not drained
mid-combat. Bytes are read into unbounded queues and dispatched in one bulk frame after the battle.
The seam for a fix exists: `CoopNetPump.deferredInbound` is drained first and in order, so a
combat-frame drain can dispatch a whitelist (orders, PING, PONG) and re-queue the rest with the
connection-generation stamp intact.

**The guest can draw and click the map.** `CustomUIPanelPlugin.render` inside an interaction dialog
draws arbitrary GL each frame, in the same absolute screen space `CoopBitmapFont` already uses;
`processInput` receives mouse move, buttons, wheel and keys with drag deltas and hit testing, and
events can be consumed so a click or ESC does not close the dialog. Vanilla ships the precedent:
`DuelPanel`, 809 lines, a full-screen drawn interactive scene with scissor, camera pan, custom bars
and nested widgets. The mod owns the surrounding pieces: `CoopDialogController` for the exclusive
dialog slot, an arbiter for priority, an input blocker that suspends itself, and a pump that ticks
while paused and hands the clock to vanilla while a guest dialog is open. The 2026-08-19 status
panel was cancelled for a `TextPanelAPI` rebuild flash that a GL panel does not have.

**The position stream is an append.** `CoopBattleStatus` documents the extension point: add
`x|y|facing` to the ship lines and lower `STATUS_INTERVAL_MILLIS` from 400 to 100 to 200. Two
hundred ships is about 10 KB per update over TCP. No new type and no datagram work. The UDP route
is available later but needs a monotonic epoch, since `CoopStreamClock` is frozen for the battle.

**Battle lifecycle shape transfers.** `BATTLE_RESULT` parks until the dialog closes, builds from the
world, and reconciles idempotently; `ALLY_BATTLE_RESULT` for the owner's real fleet can use the same
shape with a second reconciler entry point. Disconnect mid-battle already finishes locally, discards
the result loudly, and banners the partner.

## Latent bugs the feature will expose

- `service.beginFrame()` resets the per-frame inbound budget and is called only from the campaign
  pump. During a battle the budget is spent once for the whole fight and TCP reads stop part way
  through. One line from the combat frame fixes it. Needed before any combat inbound drain.
- The after-battle bulk drain of an unbounded backlog is one frame. A 10 minute battle has not been
  run in a two-instance session; the Phase 14 smoke on 2026-08-19 was short engagements.
- If the combat plugin does not advance while combat is paused, the partner's
  `maybeExpireRemoteBattle` declares the battle over after 30 s of tactical-map pause and unfreezes
  the world under a live fight. Pre-existing; this feature guarantees hitting it.

## Product constraint

A full-screen dialog holds the observer's campaign for the whole battle. The observer is held by the
shared pause anyway, but the map must be opt-in and dismissable at any time, which reverses Phase 14's
deliberately inescapable panel. A reconnect dialog must win the slot over an open map.

## Live checks that decide the design

| # | Check | Why it matters |
|---|---|---|
| 1 | `getFleetManager(0).getAdmiralAI()` is non-null and ally-mode in a campaign battle; log assignments for 30 s | Whether the allied admiral exists to silence, and whether orders hold |
| 2 | Does `EveryFrameCombatPlugin.advance` run while combat is paused | Orders during pause, keepalives during pause, the 30 s false battle-over |
| 3 | Ally CP pool at frame 1 (`getTaskManager(true).getCommandPointsLeft()`) | Free orders vs a CP policy |
| 4 | A combat `ShipAPI` maps back to the owner's `fleetMemberId` through the mirror | The identity scheme |
| 5 | `showCustomVisualDialog` at full screen size, and callable from `init()` | Map size, and click-free open on BATTLE_BEGIN |
| 6 | Allied ships extend owner-0 fog (`getFogOfWar(0).isVisible` on an enemy only an ally sees) | What the partner is allowed to see |
| 7 | The 700 su ally pull-in lands with a mirror in the fight (Phase 33 spike, on main, deferred) | Everything above |

Checks 1 to 4 and 6 fit one instrumented ally battle once check 7 passes. Check 5 is a guest-side
launch with a logging panel.

## Work list

Already works: outbound TCP and UDP from the combat frame; keepalive exemption in combat; the
additive status codec; mirror self-heal after a mid-battle roster shrink; the result park-and-
reconcile shape; mid-battle disconnect handling.

Small: `beginFrame` from the combat frame; hand the bridge the existing state-stream sink; classify
two or three new types across the seven policy tables (the policy test enforces this); append
positions to the status lines and raise the rate.

Real work: the combat-frame inbound drain with a whitelist and correct re-queueing; stable per-ship
identity keyed on the owner's member id with mirror rebuilds frozen for the battle; the drawn map
panel with pan, zoom, selection, order buttons and confirmed-versus-pending state; the owner-side
apply for `ALLY_BATTLE_RESULT`; a bound on the after-battle backlog.

Not possible without engine changes: ordering allied ships through vanilla UI; recovering allied
wrecks without a mod-side override; `IGNORE` or station orders for allies.

## Suggested milestones after Phase 33

1. Observer: positions and facing on the status stream, the drawn map with pan and zoom and the
   fighting side's fog, opt-in and dismissable.
2. One order: select one owned ship, engage a target or cancel, over TCP, with the combat-frame
   inbound drain and accepted/rejected feedback.
3. Escort, defend, retreat, multi-select, and a command-point policy.
4. Fidelity: the Phase 22 combat manifest (officers, skills) if the ally's weakness shows in play.

Remote piloting through the vendored CoOpCombat pilot moves to the Maybe list. The prior art's own
README records a shared camera, forward-only aiming, AI shields and cycled targeting, and LWJGL
releases keyboard state when the window loses focus, so the guest cannot watch a stream and pilot
without an input-capture solution nobody has designed.
