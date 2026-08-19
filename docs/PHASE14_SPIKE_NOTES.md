# Phase 14 spike notes

Plan reference: `COOP_MP_IMPLEMENTATION_PLAN_V1.md` line 1351 ("Spike first — do this step before any
other in this phase"). Engine-facts block: lines 1318-1326.

Harness: `src/main/java/coop/combat/CoopCombatSpike.java`, called once per frame from
`CoopNetPump.advance` via `tickCombatSpike()`. Every spike is gated twice: `CoopDebug.diagnosticsEnabled()`
(JVM arg `-Dcoop.debug.diagnostics=true` or sector memory `$coopDebug`) plus a per-spike sector memory
flag that the harness unsets when it fires.

**Arming (no console exists in the test environment):** the test profiles run no Console Commands mod,
so the harness polls a trigger file at 1 Hz through the sandbox-legal `SettingsAPI` common-file surface
(`fileExistsInCommon` / `readTextFileFromCommon` / `deleteTextFileFromCommon`, filename `coop_spike`,
which the engine stores as `saves\common\coop_spike.data`, plain text). Writing a command into that file
sets the same one-shot sector memory flag a console would; the file is consumed on read. Commands:
`customs [toff|smuggle|legacy]`, `engage`, `eject`, `ejectstop`. Per test profile, the file to write is:

- host: `K:\Starsector-coop-test\host\saves\common\coop_spike.data`
- guest: `K:\Starsector-coop-test\guest\saves\common\coop_spike.data`

The `SetMemoryKey $coopSpike...` console commands below remain valid wherever a console mod is present;
in the standing test environment the file is the way.

## Scope deviation from the plan text

The plan's step (b) reads "one `ENGAGE_GUEST` → `startBattle` round trip". The spikes here drop the
`ENGAGE_GUEST` half. Reliable-TCP request/response between host and guest is already carrying
`NPC_FLEET_SET`, `BASE_SET`, `INTERACTION_CLAIM` and `REP_DELTA` in shipped phases, so a new message id
proves nothing that Phase 12 has not proved. What is unproven is the engine behaviour on each end, and
that is what gets triggered locally: the guest arms `$coopSpikeEngage` on its own machine, the host arms
`$coopSpikeEject` on its own. The message hop is deferred to the implementation step that adds
`ENGAGE_GUEST`.

## Customs research (spike a)

### The plan's "customs" rules are dead code

`rules.csv:2749-2854` holds a complete customs-inspection conversation keyed off
`$doingCustomsInspection` on the interaction target's memory (`customsInspectionScan`, rules.csv:2755,
trigger `BeginFleetEncounter`, score 100). Nothing sets that key. It appears in `rules.csv` seventeen
times, always as a read or an `unset`, and it appears nowhere in the 0.98a API source dump at
`tmp_ff_analysis/agentC/api_src`. The rulecmds it calls (`CustomsInspectionGenerateResult`,
`CustomsInspectionApplyResult`, `CustomsInspectionApplyRepLoss`, `TakeRepCheck`) are also absent from
api_src. This is the pre-0.8 mechanic, still in the data file, driven by a script that no longer ships.

Two live paths replaced it. Both are `BeginFleetEncounter` rules on the patrol fleet's own memory, and
both end in the same `CargoScan` rulecmd.

### Path 1: transponder-off patrol stop (the one Phase 14 actually needs)

`tOffPatrolBegin`, rules.csv:3395. Conditions:

```
CaresAboutTransponder
!$tOff_didAlready
!$isHostile
!$faction.c:allowsTransponderOffTrade
!$sourceMarket.mc:free_market
$isPatrol
$sawPlayerTransponderOff score:100
```

`CaresAboutTransponder.java:28` reduces to `Misc.flagHasReason(mem, MEMORY_KEY_MAKE_AGGRESSIVE, "tOff")`,
which `Misc.java:1453` implements as `memory.getBoolean("$cfai_makeAggressive_tOff")`. It also returns
false early if `$patrolAllowTOff` is set. `$sawPlayerTransponderOff` is normally written by
`CoreCampaignPluginImpl.updateEntityFacts` (line 290) on every `getMemory()` call while the player runs
dark.

Continuation: `tOffPatrolOpenComm` (rules.csv:3422) applies `AdjustRep $faction.id TRANSPONDER_OFF` and
offers comply / story-point / refuse; `tOffCargoScan` (rules.csv:3481) runs the `CargoScan` rulecmd and
fires `TOffScanResult`, which branches to `tOffCargoScanClean`, `tOffCargoScanContraband`,
`tOffCargoScanBoarding1` or `tOffCargoScanPods`.

This is the branch that closes the Phase 9 gap recorded in memory `guest-transponder-reactions-gone`:
the running-dark confrontation plus its standing penalty, not just contraband cargo. The spike defaults
to it.

### Path 2: smuggling-suspicion scan

`cargoScanInitial`, rules.csv:3551. Conditions are only three, all on the fleet's memory:

```
!$cargoScan_didAlready
!$isHostile
$pursuePlayer_smugglingScan score:50
```

`SmugglingScanScript.java:118` writes that key via
`Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_PURSUE_PLAYER, "smugglingScan", true, 1f)`, which sets
`$pursuePlayer`, sets `$pursuePlayer_smugglingScan`, and registers the second as a required key of the
first (`Misc.java:1439-1451`). Continuation: `cargoScanFirstComms` (rules.csv:3561) →
`cargoScanStart` (3566) → `CargoScanResult`.

Fewest preconditions, so it is the fallback variant when the transponder branch will not fire.

### Memory scope, and why the commander matters

`RuleBasedInteractionDialogPluginImpl.updateMemory()` (lines 105-135) does not merge memories. It builds
a map of named scopes; `$foo` resolves against `MemKeys.LOCAL` and `$prefix.foo` against
`memoryMap.get(prefix)`. `updatePersonMemory()` (lines 137-156) then swaps LOCAL depending on whether
the interaction target has an active person:

- No active person: `LOCAL` = the fleet's memory, and `ENTITY` is **removed** from the map.
- Active person set: `LOCAL` = the person's memory, `ENTITY` = the fleet's memory.

`FleetInteractionDialogPluginImpl` fires `BeginFleetEncounter` at line 397, before any person is active,
so the trigger conditions above read the fleet's memory bare. It sets the commander as active person at
line 1328, immediately before firing `OpenCommLink`. Every comm-stage rule therefore uses
`$entity.transponderOffConv`, `$entity.cargoScanConv`, `$entity.patrolAllowTOff`. **A mirror fleet with a
null commander opens the encounter and then silently fails every comm-stage condition**, because ENTITY
is not in the map.

`CoopFleetMirror.ensureNpcFleet` calls `createEmptyFleet(factionId, label, true)`, and the third argument
is `withCommander`, so mirrors do have one. The harness logs it anyway.

### Preconditions that break the path without throwing

Checked and logged by the harness before it opens the dialog:

| Precondition | Consequence when unmet | Source |
| --- | --- | --- |
| `$sourceMarket` set to a real market id | `CargoScan` crashes | `CargoScan.java:103-110` dereferences `Misc.getSourceMarket(other).getMemory()` on line 104, then null-checks `market` on line 106 |
| mirror commander non-null | all `OpenCommLink` conditions fail silently | `RuleBasedInteractionDialogPluginImpl.java:137-156` |
| mirror faction is not the player faction | option panel gets "Leave" only, no engage | `FleetInteractionDialogPluginImpl.java:2545-2549` (unless `$isSmuggler` is also set) |
| mirror not hostile to player | `!$isHostile` fails on both branches | `CoreCampaignPluginImpl.updateEntityFacts` recomputes `$isHostile` on every `getMemory()`; it cannot be pre-set |
| `$sourceMarket` market lacks `free_market` | `!$sourceMarket.mc:free_market` fails (toff branch) | `Conditions.FREE_PORT = "free_market"` |
| mirror has no `Tags.STATION` / `Tags.HAS_INTERACTION_DIALOG` / market | picker returns `RuleBasedInteractionDialogPluginImpl` instead of the fleet plugin, losing the whole encounter machinery | `CoreCampaignPluginImpl.pickInteractionDialogPlugin`, lines 86-111 |

`CargoScan.java:66` reads `Global.getSector().getPlayerFleet()` for the cargo it scans, and judges
legality against `other.getFaction()`. Against a local mirror that is exactly the desired behaviour: the
guest's own cargo, checked against the patrol's faction.

### Dialog entry point

`CampaignUIAPI` has three `showInteractionDialog` forms and no others:

```java
boolean showInteractionDialog(InteractionDialogPlugin plugin, SectorEntityToken interactionTarget);   // line 42
boolean showInteractionDialog(SectorEntityToken interactionTarget);                                    // line 51
boolean showInteractionDialogFromCargo(InteractionDialogPlugin plugin, SectorEntityToken interactionTarget, DismissDialogDelegate delegate); // line 156
```

The one-argument form at line 51 runs the plugin picker. For a `CampaignFleetAPI` target that lands on
`new FleetInteractionDialogPluginImpl()` at `CoreCampaignPluginImpl.java:109-111`. That is the overload
the spike uses.

### Flags the harness sets

Common to all variants, on the mirror fleet's `getMemoryWithoutUpdate()`:

```
unset $cfai_ignoredByOtherFleets     (12b's interim flag)
unset $cfai_ignoreOtherFleets
unset $ignorePlayerCommRequests
unset $patrolAllowTOff
unset $tOff_didAlready
unset $cargoScan_didAlready
set   $cfai_doNotIgnorePlayer = true
set   $isPatrol = true
set   $sourceMarket = <picked non-free-port market of the mirror's own faction>
```

Variant `toff` (default) adds `$cfai_makeAggressive_tOff` via `Misc.setFlagWithReason(..., "tOff", 7f)`
and `$sawPlayerTransponderOff = true`. Variant `smuggle` adds `$pursuePlayer_smugglingScan` and
`$keepPursuingPlayer_smugglingScan`, both via `setFlagWithReason`. Variant `legacy` adds
`$doingCustomsInspection` and `$isCustomsInspector`, and is expected to produce the plain encounter menu
if the pre-0.8 reading is right.

## Regressions observed during the spike session (not spike failures)

- **2026-08-19, guest-first market open is empty (Phase 12 gap):** if the guest opens a market the host
  player has never visited, the open-market tab arrives empty except mod specs; other submarkets showed
  stock. Once the host physically opens the same market, the guest's next open shows full contents.
  Root-cause hypothesis: the host-side snapshot-on-open captures the engine market as-is, but vanilla
  only generates submarket stock on player interaction (`updateCargoPrePlayerInteraction`), which never
  ran for a host-unvisited market. Fix: on the guest's market-open request, host must invoke the vanilla
  stock-update path per submarket before capturing the snapshot. Owner: post-spike fix, Phase 12 code.

- **2026-08-19, no NPC spawns in guest-only systems (Phase 9 gap):** the guest sat near Umbra for
  minutes with zero pirate fleets; ~3 materialized the moment the host entered the system. Vanilla keeps
  distant fleets as RouteManager routes and only materializes fleet objects near the player fleet; the
  host-side guest mirror is not a player fleet, so guest-only systems never materialize. Fix direction:
  host-side presence extension so route materialization also happens around the guest mirror (needs
  RouteManager surface research). Owner: post-spike fix, Phase 9/13 code. **Fixed 2026-08-19**
  (`CoopGuestRouteMaterializer`, commit c735ecd): force-spawns routes in the guest's system via the
  public spawner call, adopts fleets into `RouteData.activeFleet` via MethodHandles (duplicate-spawn
  guard), pins `daysSinceSeenByPlayer` (self-expiring despawn guard); fails safe to stock behavior.
  **Residual gap (follow-up):** `DisposableFleetManager`/`PlayerVisibleFleetManager` subclasses (e.g.
  `DisposablePirateFleetManager`) and `SourceBasedFleetManager` spawn near the player by design and
  still ignore the guest — guest-only systems get route traffic (patrols/traders/raids) but not
  player-proximity ambient spawns.

## Verdicts (in-game, 2026-08-19, two-instance session, new-game seed MN-1234567890123456789)

- **Spike (a) customs dialog against a mirror: PASS.** `toff` variant, Hegemony "Fast Picket" mirror,
  dist 34.8 su. `showInteractionDialog returned=true`, `plugin=FleetInteractionDialogPluginImpl`; the
  full vanilla running-dark confrontation rendered on the guest (hail text, rep −2 faction / −3
  commander applied, Allow-the-scan / story-point / refuse options); the cargo scan ran against the
  guest's real cargo and closed cleanly. Bonus: the faction rep penalty propagated guest→host through
  the existing Phase 12 `GUEST_REP_DELTA` path and converged on both sides — the shared-rep half of the
  customs acceptance already works with no new code.
- **Spike (b) `startBattle` versus a mirror: PASS.** Guest opened a battle against a 12-ship pirate
  "Raiders" mirror via `BattleCreationContext(player, ATTACK, mirror, ATTACK)`: real deployment screen,
  pilotable battle (user fought and retreated), clean return to campaign. The coop session survived the
  ~2.3-minute combat gap — the TCP backlog (5 rep snapshots) flushed in one burst on return, no
  disconnect. (Harness note: the "back in campaign after 33ms" line was one queued-transition frame
  early; fixed by deferring the report 2 s.)
- **Spike (c) battle-eject timing: MOOT — superseded by a larger engine finding.** Vanilla never forms
  an NPC-vs-mirror battle at all:
  - Hostiles *see* the mirror (`seesMirror=true`) and *judge* it normally
    (`pickEncounterOption(null, mirror, true)` returns ENGAGE/HOLD/DISENGAGE tracking fleet strength),
    but never retask to hunt it — assignments stay PATROL_SYSTEM/GO_TO_LOCATION, never
    INTERCEPT→mirror. Vanilla detect→chase retasking is player-fleet machinery, not generic hostility.
  - An ENGAGE-picking hostile Scout crossed the mirror at 10–14 su: no battle formed.
  - The `hunt` probe injected `addAssignmentAtStart(INTERCEPT, mirror, 2f, null)` on an ENGAGE-picking
    Corsair: it genuinely hunted (`targetingMirror=true`, closed 389→17 su at up to 151 su/s), reached
    the mirror, the assignment completed, and it reverted to patrol. **No battle, no autoresolve, zero
    ejects across the whole session.** Likely cause: NPC-vs-NPC encounter formation is negotiated by
    both fleets' AIs, and the mirror's AI is inert.
  - `battle.leave()` timing therefore remains unverified and does not need to be verified: the race the
    plan worried about (autoresolve beating the handoff) cannot occur.
  - Numbers harvested for the trigger threshold: observed closing speeds 57–193 su/s for pirate
    chasers (up to 340 su/s for a burn-17 patrol); no engine contact range exists vs the mirror, so the
    ENGAGE_GUEST trigger distance is a design choice, not a race — ~2 s at max observed closing speed
    suggests a 400–700 su default, Phase 20 re-derive note unchanged.

### Design consequences for Phase 14 (fold into the plan before implementing)

1. **The pre-contact handoff race, contact backstop, and battle-window shielding shrink to almost
   nothing.** No engine battle can form against the mirror, so silent autoresolve is a non-threat.
   Keep a cheap `getBattle() != null → leave()` assertion in the watcher as belt-and-braces, but it is
   not load-bearing and needs no timing guarantee.
2. **The watcher becomes the initiator, not a supervisor.** `CoopNpcThreatWatcher` detects hostile +
   proximity + `pickEncounterOption(...) == ENGAGE` (all three proven available and correct) and fires
   `ENGAGE_GUEST` itself at a chosen trigger distance.
3. **Visible pursuit must be synthesized and is proven to work:** injecting
   `INTERCEPT→mirror` makes a vanilla hostile genuinely chase (and the completed intercept is harmless).
   The watcher injects the chase for fidelity, then fires `ENGAGE_GUEST` at the trigger distance.
4. **12b's permanent ignore flag is removable with less risk than planned** — its main job (preventing
   autoresolve contact) protects against a threat that empirically does not exist. Its removal still
   needs the customs/hassle interaction checked (spike a cleared the flag manually before opening).

## Log-line reference (what each spike greps for)

The subsections below list the log lines and the original pass/fail calls, kept for re-runs.

### Spike (a): customs/inspection rules dialog, guest side

Arm: `SetMemoryKey $coopSpikeCustoms true` (transponder branch), or the string `smuggle`, or `legacy`.

Log prefix: `SPIKE14a`. Expected sequence:

```
SPIKE14a armed variant=toff player=... mirror=... dist=... playerTransponderOn=false
SPIKE14a preconditions faction=hegemony playerFaction=false commander=<name> hostileToPlayer=false sourceMarket=chicomoztoc (assigned)
SPIKE14a flags applied on mirror: -$cfai_ignoredByOtherFleets ... $cfai_makeAggressive_tOff=true ...
SPIKE14a showInteractionDialog returned=true dialog=open target=... plugin=FleetInteractionDialogPluginImpl
```

- **PASS** requires all three: `returned=true`; `plugin=FleetInteractionDialogPluginImpl` (not
  `RuleBasedInteractionDialogPluginImpl`); and on screen, the patrol hails the player and demands the
  scan, with `CargoScan` running against the guest's own cargo and applying a fine or confiscation.
- **FAIL, recoverable:** the dialog opens on the fleet plugin but shows the plain engage/leave menu. The
  posture flags are wrong, not the approach. Retry with the other variant; the plan's fallback (a custom
  coop dialog performing the cargo check directly) is not yet warranted.
- **FAIL, blocking:** `showInteractionDialog` throws, returns false with no dialog open, or a
  `NullPointerException` appears inside `CargoScan` (means `$sourceMarket` was not resolved). If the
  dialog cannot be driven against a mirror at all, take the custom-dialog fallback from the plan's
  customs step and record the exact API blocker here.
- A `SPIKE14a WARN precondition(s) unmet` line means the harness expects the fall-through and opened
  anyway; treat the run as inconclusive, not as evidence against the approach.

### Spike (b): `startBattle` against a mirror, guest side

Arm: `SetMemoryKey $coopSpikeEngage true`.

Log prefix: `SPIKE14b`. Expected sequence:

```
SPIKE14b armed player=... mirror=... dist=... mirrorMembers=<hullId>:<hull>/<cr> ...
SPIKE14b calling startBattle
SPIKE14b startBattle returned without throwing
SPIKE14b back in campaign after 47213ms playerMembers=... mirror=... mirrorMembers=...
```

- **PASS:** a real deployment screen appears, the battle is pilotable, and the `back in campaign` line
  arrives with an elapsed time matching how long the battle took. Losses on both sides show up in the
  `playerMembers` / `mirrorMembers` fractions.
- **FAIL:** `startBattle threw`, or the game returns to campaign in under a second with unchanged member
  fractions (the state transition was rejected), or the client hangs in the combat state. Record which.
- Watch for the guest's mirror set: the NPC mirror is host-replicated, so the host will rewrite it on the
  next `NPC_FLEET_SET`. Divergence after the battle is expected and is Phase 15's problem, not a spike
  failure.

### Spike (c): battle-eject timing, host side

Arm: `SetMemoryKey $coopSpikeEject true`. Stop: `SetMemoryKey $coopSpikeEjectStop true`.

Log prefix: `SPIKE14c`. The watcher runs every frame until stopped or until the session ends.

```
SPIKE14c watcher armed mirror=... ignoredFlag=was=true nowSet=false members=...
SPIKE14c chase mirror=... mirrorVel=(x,y)|speed | <chaser> dist=1840.0 closing=112.4 vel=... burn=9.0 targetingMirror=true assignment=INTERCEPT->... hostile=true
SPIKE14c CONTACT battle detected opponents=[...] membersPrevFrame=... membersAtContact=...
SPIKE14c EJECT #1 leave=ok stillInBattle=false membersAfter=... damaged=false
```

- **PASS** on the eject question requires `leave=ok`, `stillInBattle=false`, and `damaged=false` on every
  `EJECT` line. `damaged=false` is the whole point: it diffs the mirror's hull fractions from the frame
  before contact against the fractions read after `battle.leave` returns, so any autoresolve round that
  landed shows up as a changed number. The comparison deliberately excludes CR, which drifts every frame
  from supply use and repair; the `membersAfter=` field still prints `hullId:hull/cr` for eyeballing.
- **FAIL:** `damaged=true` on any eject, or `stillInBattle=true`. Then the plan's stated fallback applies:
  enlarge the handoff threshold and keep `$cfai_ignoredByOtherFleets` set whenever the watcher is not
  confident, trading chase fidelity for safety.
- **Numbers to harvest regardless of verdict.** The Phase 14 handoff threshold is sized from two
  measurements, both readable from these lines:
  1. **Engine contact range** = the `dist` on the last `chase` sample before the `CONTACT` line, and the
     `dist` inside `opponents=[...]` on the `CONTACT` line itself. Samples are throttled to 2 Hz, so the
     `CONTACT` line's own distance is the tighter bound.
  2. **Closing speed** = the `closing` column, in su/sec along the line between the two fleets, at max
     burn. Take the largest value seen in the approach.

  Threshold to record in `CoopNpcThreatWatcher` = contact range + 2 seconds of that closing speed, with a
  code comment naming the Phase 20 obligation to re-derive it as `2 x p95 RTT + processing margin`.
- If no `chase` lines appear at all, the mirror is not being detected. Check that the `ignoredFlag=` field
  on the `watcher armed` line reads `nowSet=false`, and cross-check with `CoopFleetVisibilityProbe`'s
  host dump for the mirror's sensor profile.

## Manual test procedure

Both instances need diagnostics on: launch with `-Dcoop.debug.diagnostics=true` (already in the test
profiles' vmparams on the host; verify the guest). All spike triggers are consumed on use, so re-arming
means re-writing the trigger file.

Arming is done from a shell by writing the command into the profile's trigger file, e.g. (PowerShell):

```powershell
Set-Content K:\Starsector-coop-test\guest\saves\common\coop_spike.data "customs"
Set-Content K:\Starsector-coop-test\guest\saves\common\coop_spike.data "engage"
Set-Content K:\Starsector-coop-test\host\saves\common\coop_spike.data  "eject"
Set-Content K:\Starsector-coop-test\host\saves\common\coop_spike.data  "ejectstop"
```

1. Start a coop session normally and let the handshake complete (`isGameplaySessionActive()` must be true
   or the harness does nothing — including the trigger-file poll).
2. Fly the guest next to a host-owned NPC fleet so an NPC mirror exists in the guest's own location. The
   harness only considers mirrors in the player's location; a battle or dialog needs co-location.
3. **Spike a**, guest trigger file: `customs` (or `customs smuggle` / `customs legacy`). Watch the four
   `SPIKE14a` lines and the screen.
4. **Spike b**, guest trigger file, after closing any dialog: `engage`.
5. **Spike c**, host trigger file: `eject`. Then, on the guest, fly the guest fleet toward a hostile
   pirate or Remnant fleet with the transponder off until it gives chase. Let time pass until the
   `CONTACT` line appears. Stop with `ejectstop` in the host trigger file.

Record each verdict in the sections above before starting any Phase 14 implementation step.
