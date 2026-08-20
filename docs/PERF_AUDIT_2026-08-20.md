# Performance audit — 2026-08-20

Read-only sweep of every per-frame / per-second path, run after the frame profiler
(`coop.util.CoopFrameProfiler`, `-Dcoop.debug.frameProfile=true`) caught the ORBIT_SNAPSHOT
apply doing ~70 sector-wide `getEntityById` scans per second (67–82 ms single-frame stalls,
fixed same day). This document records every finding and its disposition so nothing silently
drops. Line numbers are as-of the audit date and will drift.

## Verified engine cost facts

- `sector.getAllLocations()` — allocates 2 fresh ArrayLists + copies ~130 systems per call;
  **hyperspace is already included** (every `!contains(hyperspace)` guard in the mod was dead
  code that paid a full scan).
- `location.getFleets()` — live list, 1 `unmodifiableList` wrapper alloc per call.
- `sector.getEntityById()` — id-map hit + `getAllEntities().contains()` validation, falls back
  to hyperspace + every system.
- `fleet.setNoEngaging(f)` — allocates a new `Fader` per call; shield expires ~1 s after the
  last call, so 4 Hz re-assert suffices.
- `entity.getMemoryWithoutUpdate()` — lazily allocates (and save-persists) a `Memory` for
  entities that lack one.
- `IntelManager.getIntel(Class)` — O(1), not a scan.

## Findings

| # | Site | Cadence × scale | Status |
|---|---|---|---|
| 1 | `CoopNpcThreatWatcher.findGuestMirror` above the 250 ms throttle | per frame × all fleets sector-wide | **fix batch 1** |
| 2 | `CoopFullFidelitySystemDriver` → `CoopGuestPresence.findGuestMirror` before early-outs | per frame × sector | **fix batch 1** |
| 3 | `CoopNpcFleetSuppressor.sweep` — per-location list copies, no timer | per frame × ~130 locations | **fix batch 1** |
| 4 | `CoopFleetMirror.resolveLocation` — `getAllLocations()` per motion record | 10 Hz × N mirrors | **fix batch 1** |
| 5 | `CoopCampaignReplicator.tickWorldDeltas` — per-frame walk of the player's location entities (358 in a belt), 2× `getMemoryWithoutUpdate` per entity, HashSet + ArrayList per frame | per frame × entities in location | deferred → batch 2 (throttle ~4 Hz + scratch collections) |
| 6 | `CoopNpcFleetReplicator.sendSetIfChanged` — full capture + N+1 SHA-256 built then discarded on unchanged hash | 1 Hz × all NPC fleets sector-wide | deferred → batch 2 (**judgment**: cheap pre-fingerprint must not miss same-count roster changes; consider caching the MessageDigest + skipping only encode, not capture) |
| 7 | `CoopFleetMirrorRegistry.applySet` re-applies every mirror when any fleet changed (`lastAppliedHash` tracked but unused for skipping) | 1 Hz × N mirrors | deferred → batch 2 (**judgment**: motion stream owns movement; skip identity/roster work only) |
| 8 | `CoopNpcFleetReplicator.sendMotion` — sector walk with the location filter inside the visitor | 10 Hz × sector | **fix batch 1** (via cached mirror handle + direct location iteration) |
| 9 | Engagement shield `setNoEngaging(1f)` per mirror per frame (2580 Fader allocs/s @ 43 mirrors) | per frame × N mirrors | **fix batch 1** (250 ms re-assert timer) |
| 10 | `CoopNetService.pollNetworkLocked` runs ~15–20× per frame (isConnected/pollInbound/pollDatagram/flushOutbound all poll) — ~3000 socket syscalls/s | per frame, constant | deferred → batch 2 (poll 2×/frame, cache isConnected) |
| 11 | `tickSkeletonMutations` — 2 `getEntitiesWithTag` per location per 5 s pass (260 scans/5 s) | 5 s × locations | deferred → batch 2 (cache token lists, slow cadence) |
| 12 | `CoopNpcThreatWatcher.viewOf` — eager `FleetView` record computes `engagePick`/`allowedToEngage`/`pursuitDays` for every fleet, mostly discarded; encounter-option cache bypassed within ~300 su | 4 Hz × fleets in mirror's location (worst: hyperspace) | deferred → batch 2 (lazy fields) |
| 13 | 10 Hz player-roster full capture + hash + encode when roster changes ~1/min | 10 Hz both sides | deferred → batch 2 (**judgment**: cache encoded tail keyed on cheap fingerprint) |
| 14 | 4 copy-pasted `forEachLocation` helpers, each `getAllLocations()` twice + dead hyperspace guard | multiplier on #2/#3/#8 | **fix batch 1** (shared helper) |
| 15 | `CoopDebug.diagnosticsEnabled()` uncached (`Boolean.getBoolean` + sector memory read), 3–4×/frame | per frame | deferred → batch 2 (volatile cache on the 300-frame poll) |
| 16 | Diagnostics-on: per-frame 43-id LinkedHashSet + string concat just to diff against `lastNpcDebug` — perturbs measurement runs | per frame (diagnostics only) | deferred → batch 2 (size + rolling hash pre-check) |
| 17 | `CoopBattleResultReconciler` — `find()` runs twice per involved fleet (~10 sector scans in the return-from-combat frame) | event, burst | deferred → batch 2 (stash resolved fleet) |
| 18 | Minor churn: per-frame `System.getProperty` kill switch; `CoopDelimited.field()` StringBuilder always; `CoopOrbitSync.encode` no initial capacity; unguarded 1 Hz orbit-apply log concat; boxed `List<Integer>.contains` O(n²) in reconciler | small | deferred → opportunistic |

## Checked and clean (verified, do not re-audit)

Event listener adapter; base authority ticks (interval-gated, O(1) intel lookup;
`getStarSystems()` only on base creation); rep sync + orbit sync capture (interval-gated,
typed lists); battle bridge campaign tick (early returns; scans event-driven); combat spike
(dormant); orphan sweeper (once per load); guest presence tick (2 s gate); motion smoother;
inflation latch; roster hash gate (CR storm stays fixed); sensor sync early-out (degrades only
while terrain mods are actively changing); all codecs/pure logic; CoopLog guard audit clean
except the 1 Hz orbit-apply info line (#18).

## Profiler blind spots (by construction)

- `CoopBattleStatusCombatPlugin.advance` — attaches to every combat engine (incl. refit sim
  and title screen); uninstrumented. `CoopBattleBridge.sendStatus` allocates ~200 ShipRecords
  + flushes at 2.5 Hz inside the combat window the campaign profiler cannot see.
- Vanilla listener dispatch (event listener, cargo screen listener, input blockers) and the
  save hooks run outside `advance()`.
- `CoopSystemDriveFrameHook.advance` is a second EveryFrameScript but its body is trivial;
  the heavy work is captured under the pump's npc section.
- Fix batch 1 splits `npc.syncReplication` into per-component sections.
