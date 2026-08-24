# Starsector Runtime Limitations

These notes capture constraints discovered while implementing and smoke-testing the coop Phase 3 TCP pump on Starsector `0.98a-RC8`.

## Script Sandbox

Starsector mod scripts run under a guarded script classloader. When the guard trips, the UI usually reports:

```text
Fatal: File access and reflection are not allowed to scripts.
```

The useful detail is in `starsector-core/starsector.log`.

Observed blocked patterns:

- Netty `4.1.69.Final` initializes `io.netty.util.internal.PlatformDependent0`, which loads reflection classes such as `java.lang.reflect.Method`. This crashes during host startup.
- `java.io.*` is treated as file access even when the class is only an in-memory type. `ByteArrayOutputStream` in `CoopNetService` crashed at object construction.
- `java.nio.file.Files` is blocked in campaign scripts. Phase 5 manifest capture crashed at `coop.handshake.CoopChecksum.sha256IfExists()` before sending `HANDSHAKE_MANIFEST`.
- Mod-created networking daemon threads are not reliable enough for campaign networking. In live two-client testing the `coop-net-*` threads disappeared while the game stayed running and the socket state was left half-open.

Current rules:

- Do not add Netty or similar reflection-heavy networking libraries to `mod_info.json`.
- Do not use `java.io.*` in runtime campaign/network code. Use plain arrays, strings, and sandbox-proven JDK types instead.
- Do not use `java.nio.file.*`, `java.net.URL.openStream()`, or protection-domain jar inspection in runtime handshake code. Runtime manifests may compare Starsector/mod metadata and generated coop build constants, but direct file checksums are blocked by the Starsector script sandbox unless a future non-script API is found.
- Keep coop networking progressed from `EveryFrameScript.advance()` on the campaign thread.
- Keep runtime dependencies minimal and covered by sandbox compatibility tests.

### Handshake checksums: RESOLVED — `SettingsAPI.loadText` works in the sandbox (Phase 6b, 2026-08-17)

Phase 12b could not settle whether `SettingsAPI.loadText(String, String modId)` survives the script
sandbox (the guard lives in the mod classloader, so the call compiles and unit-tests clean either
way). It shipped a dormant, diagnostics-gated `CoopChecksumProbe` instead, and the 12b/12d drill
session (2026-08-17) delivered the verdict: **SUCCESS on both clients**, identical hashes
(`Coop checksum probe: SUCCESS, mod_info.json hashed to a35cede3... (275 chars)`). The engine's own
text loader is usable from campaign scripts.

Phase 6b promoted the call: `CoopHandshakeManifest` now hashes each enabled mod's `mod_info.json`
via `loadText` + `CoopChecksum.sha256Text` (line endings normalized so a CRLF/LF checkout difference
does not read as a mismatch), and the probe was deleted. Two safety conditions survive from the
probe era and are pinned by `CoopHandshakeSandboxCompatibilityTest`:

- The call catches `Throwable`, never a named checked exception — `loadText` declares `IOException`,
  and naming that type makes the verifier resolve a blocked i/o class in the calling class, the
  exact pattern recorded above as sandbox-fatal.
- A per-mod failure degrades to that mod's `UNAVAILABLE:script-sandbox` placeholder entry instead of
  throwing out of `capture()`, so one unreadable third-party mod cannot kill the handshake.

Jar checksums stay `UNAVAILABLE` permanently: no engine surface hands back jar bytes and the sandbox
forbids opening them directly. The git-commit comparison and the Phase 6 sector fingerprint remain
the primary guards against a skewed install; the checksums are corroborating detail in the
handshake diff.

## Save Serialization

Persistent campaign scripts are serialized into saves by XStream. Runtime networking objects are not save-safe.

Observed failure:

```text
Error creating new game:
Error saving game.
No converter available
```

Root cause found during testing:

- A persistent `CoopNetPump` caused XStream to walk into runtime networking state, including classes such as `AtomicReference`.

Current rules:

- Install `CoopNetPump` with `SectorAPI.addTransientScript()`.
- Remove both persistent and transient old pump instances before installing a fresh pump.
- Do not store sockets, channels, threads, queues, or other runtime transport objects in saved campaign state.

## TCP Pump Constraints

The working Phase 3 transport is intentionally conservative:

- Host uses `ServerSocketChannel` in non-blocking mode.
- Guest uses `SocketChannel` in non-blocking mode.
- No `Selector`, no background worker thread, no Netty event loop.
- Message frames are UTF-8 JSON lines buffered with a fixed byte array.
- Guest connect attempts retry because host and guest campaign loads are not ordered.

Two-client smoke evidence to preserve:

```text
Host:  Coop TCP channel active as HOST
Host:  Coop net HOST inbound PING seq=1
Host:  Coop net HOST outbound PONG seq=1
Guest: Coop TCP channel active as GUEST
Guest: Coop TCP guest connected to 127.0.0.1:7777
Guest: Coop net GUEST outbound PING seq=1
Guest: Coop net GUEST inbound PONG seq=1
```

## Phase 7 Time Lock - Control Names and Fast-Forward

Discovered while implementing the guest time lock on `0.98a-RC8`.

### Control enum-constant names (input blocker)

`InputEventAPI.isControlActivated/isControlDownEvent/isControlUpEvent(String)` resolve the
argument via `Enum.valueOf` on the obfuscated control enum (`com.fs.starfarer.title.<obf>$oo` in
`starsector-core/starfarer_obf.jar`). An unknown name throws `IllegalArgumentException: No enum
constant ...<name>`; if uncaught it becomes a **Fatal** crash dialog back to the title screen.

- The campaign pause control is **`GENERAL_PAUSE`**, not `PAUSE`. Passing `"PAUSE"` crashed the client.
- Campaign fast-forward control is **`FAST_FORWARD`**; combat slow-mo is `GO_SLOW`.
- To dump the readable list: extract the `$oo` class from `starfarer_obf.jar` and
  `javap -v <class> | grep "= Utf8"` (constants like `CORE_*`, `CMENU_*`, `C2_*`).
- `CoopCampaignInputBlocker` wraps the lookups in `try/catch (IllegalArgumentException)` as a
  defensive net, but tests must assert against the real constant names - the test mocks
  `InputEventAPI` with arbitrary strings, so a wrong name passes tests yet crashes the real engine.

### Fast-forward has no public on/off setter

> **⚠️ SUPERSEDED IN PART (2026-06-10, see plan Phase 7b).** Everything below is true only for
> vanilla's default **hold**-Shift input mode. Vanilla also has a **toggle** fast-forward mode
> (settings-menu checkbox `Campaign "speed up time" is a toggle`, backed by a static boolean in
> `com.fs.starfarer.settings.StarfarerSettings`: getter `Oo0000()Z`, public static setter
> `?00000(Z)V`). In toggle mode the per-frame key poll is skipped entirely; the persistent
> `CampaignState.fastForward` field (plain name, private) is flipped only by a consumable
> `FAST_FORWARD` key event — so the guest's existing pre-core event consumption blocks it, and a
> `MethodHandles` field write sticks. The speed loop is
> `iters = fastForward ? Math.round(getFloat("campaignSpeedupMult")) : 1` with a live `getFloat`
> each frame, so the multiplier is also runtime-settable via public `SettingsAPI.setFloat`.
> Verified by `javap` disassembly of `starfarer_obf.jar` (dumps in `K:\Starsector\tmp_ff_analysis\`).
> Phase 7b restores shared fast-forward on this basis; the 1x lock below remains the fallback when
> the handles fail to resolve.

Hold-Shift fast-forward is implemented inside the obfuscated
`com.fs.starfarer.campaign.CampaignState.advance(float, ...)` as a per-frame loop that calls
`CampaignEngine.advance()` ~2x while the key is held. **Runtime-verified via TIMEDIAG logging:**
while fast-forwarding, the campaign clock advanced at exactly 2x, yet both
`SectorAPI.isInFastAdvance()` and `SectorAPI.isFastForwardIteration()` read `false` at the point an
`EveryFrameScript` observes them.

- `setFastForwardIteration(boolean)` - internal per-frame flag the engine overwrites; setting it
  does nothing. (Original plan assumption - wrong.)
- `setInFastAdvance(boolean)` - drives a *separate* extra `CampaignClock.advance()` inside
  `CampaignEngine.advance()` (the ">>" toggle path), NOT the hold-Shift loop; reads `false` on the
  host during hold-Shift, so it cannot *capture* fast-forward.
- **Capture lever (host):** `CampaignUIAPI.isFastForward()` - the public getter that reflects the
  fast-forward state. Reached via `Global.getSector().getCampaignUI().isFastForward()`.
- **Apply lever (guest):** `SectorAPI.setInFastAdvance(hostValue)` each frame makes the guest's
  clock run ~2x to mirror the host.
- `setInFastAdvance(true)` sticks on the guest (read-back is true) but does **not** change the
  guest clock rate (verified: `dGuestClockTs` stays 1x). The real 2x comes only from
  `CampaignState.advance` calling `CampaignEngine.advance()` twice per frame.
- The guest's own hold-Shift loop lives in obfuscated `CampaignState` and polls the key directly,
  so it cannot be blocked via public API (consuming the `FAST_FORWARD` input event does not stop
  it). Forking `CampaignState` is impractical because it is obfuscated engine code (unlike the
  Phase 11 forks, which were readable `com.fs.starfarer.api.impl.*` source).

### Resolution adopted for v1

> **⚠️ SUPERSEDED (plan Phase 7b):** the 1x lock is demoted from "the resolution" to "the fallback";
> Phase 7b removes the static `campaignSpeedupMult:1` override and restores shared fast-forward via
> toggle mode, re-applying a 1x lock at runtime (`setFloat`) only when the MethodHandles are
> unavailable.

Because hold-mode fast-forward cannot be mirrored or blocked via public API, the v1 coop session was
locked to 1x instead:

- `data/config/settings.json` sets `"campaignSpeedupMult":1` (engine default is 2). Hold-Shift then
  advances at 1x for any client running the mod, so no client can fast-forward and there is nothing
  to mirror. Verified: with the override, `clockTs` stays at the 1x delta even while
  `CampaignUIAPI.isFastForward()` reports true. (Side effect: fast-forward is also disabled in solo
  games while the coop mod is enabled.)
- `CoopTimeLock.capture()` still reads `CampaignUIAPI.isFastForward()` and `apply()` still calls
  `setInFastAdvance(...)` to keep the guest's flag consistent with the host (animation/UI only); the
  1x lock - not these calls - is what enforces equal time rate.

### Connect-time clock alignment

> **⚠️ SUPERSEDED IN PART (2026-06-10, see plan Phase 7c).** The "no public clock-setter" claim
> below is wrong on 0.98a-RC8: `com.fs.starfarer.campaign.CampaignClock` is `DoNotObfuscate`, its
> source of truth is a `private transient GregorianCalendar cal` exposed by a **public `getCal()`**
> (so the clock is writable via plain `cal.setTimeInMillis(...)`), with the cached
> `private long timestamp` field re-syncable via `MethodHandles` (javap dump:
> `K:\Starsector\tmp_ff_analysis\CampaignClock.javap.txt`). Also note `advance(float)` int-truncates
> calendar-seconds per frame, so clocks drift structurally across machines even at shared 1x.
> Phase 7c builds a guest-side drift reconciler on this basis (bounded monotonic slew + forward-only
> snaps). The host-pause-hold during connect described below REMAINS correct and primary —
> prevention still beats correction for the connect gap.

There is **no public clock-setter on the API surface** (`CampaignClockAPI` has no `setTimestamp`;
`SectorAPI` has no `setClock`; `createClock(long)` makes a detached clock that cannot be installed),
and fast-advance cannot be driven, so a guest that starts behind the host **cannot be made to catch
up** *via API-only means*. If the host
runs unpaused during the multi-second connect/handshake/seed-lock, the guest starts several campaign
days behind permanently.

Fix: the host **holds the campaign paused** (`CoopNetPump.maybeHoldHostPausedUntilSessionReady`) from
the moment it starts hosting until the session is active (`handshakeValidated && seedLong != null`).
No time passes during connection, so the guest starts aligned; afterwards the host's normal
pause/unpause mirrors to the guest (residual offset = network latency, sub-second). Prevent the gap
rather than close it.

Pause itself is a real lever: `setPaused`/`isPaused` is read by the engine every frame, so the guest
pause lock works fully.

## Regression Tests

Keep these tests aligned with the rules above:

- `coop.net.CoopNetServiceSandboxCompatibilityTest`
- `coop.net.CoopNetServiceTest`
- `coop.net.CoopNetPumpInstallerTest`

Before declaring runtime networking safe, run:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"
```

Then deploy to both test clients and verify PING/PONG in both logs.

## Phase 13 — Accepted Runtime Divergences

Runtime randomness cannot be fixed by seeding or forking an RNG. A shared seed only guarantees identical draw sequences while both clients execute the same code in lockstep, and that lockstep breaks the moment the campaign runs: the guest sim is suppressed, frame timing differs between clients, and call order diverges after the first runtime draw. The bar-offer experiment proved this empirically — offers diverged despite a synced seed (memory `bar-mission-seed-sync`). Every runtime-random site gets one of three treatments: replicate the outcome, suppress the generator, or accept the divergence. This section covers the accepted cases, plus one item (sensor ghosts) where suppression itself is the permanent answer rather than a step toward replication.

### Hyperspace storm cells

`HyperspaceTerrainPlugin` + `HyperspaceAutomaton`: cell evolution is a deterministic automaton, but generation reseeds use `new Random()` (`HyperspaceAutomaton.java:150`) and strike timing/damage use `Math.random()` (`HyperspaceTerrainPlugin.java:1472,1485`). Not suppressible — it is a terrain plugin, not a script, so the suppressor has no hook into it.

Accepted: a storm strike only hits the fleet inside its cell. Own fleets are owner-authoritative and NPC mirrors are position-forced echoes, so a strike never touches shared state. Each player just sees their own weather.

### Star-corona / pulsar flares

`FlareManager`, `new Random()` at line ~307. Same ownership argument as storm cells — a flare only affects the fleet it hits, no shared state involved. Accepted.

### Officer pools at markets — RESOLVED by Phase 12c gap 2d

Was accepted here as "each player hires from their own pool". Now replicated: the host's pool rides the `MARKET_SNAPSHOT` as one stock line per person and the guest strips its own pool and rebuilds the host's through `OfficerManagerEvent.addAvailable`/`addAvailableAdmin`. One roll still diverges before the snapshot lands; see "Mercenary level rolled off `Misc.random`" below.

### Smuggling scans and patrol hassles of the local player

Per-player by design: local dialog interactions against a player's own cargo and rep, with no shared state touched. Accepted.

Bar *offers* used to be listed here on the same reasoning. They are not accepted any more; Phase 12c build task C replicates the pool instead of trying to reseed it. The seeding half of that old entry stands and is why the pool had to be replicated: offer selection runs through a `WeightedRandomPicker` with a null `Random`, which falls back to `Math.random()`, so equal `BarEventManager.seed` values never produced equal offers (memory `bar-mission-seed-sync`). What the seed does control is the shown subset, and that is now synced too. See "Phase 12c — Bar Pool" below for what still diverges.

### Slipstream networks

`SlipstreamManager.random` (~`SlipstreamManager.java:442`) is `new Random()` — unseeded wall-clock entropy, minted per client when the manager is constructed and serialized into each save. The `Misc.random` fork does not cover it: `random = Misc.random` only happens under `DebugFlags.SLIPSTREAM_DEBUG` (~line 460). Monthly layout draws (config pick, `addStream` placement, month-6/12 despawn timing) fire from the `interval.intervalElapsed()` branch of `advance()`, and the interval itself has a random phase (`IntervalUtil(1f, 2f)`), so the number and timing of draws differ per client even from identical RNG state.

No fork closes this gap. Per the gen-time-only principle, a per-month-reseed fork (`CoopRandom.of("Slipstream", cycle, month)`) was considered and rejected: outcomes would still depend on per-client `addStream` call counts and days, and removing that dependence means restructuring gameplay logic, which the fork rules forbid. Accepted for v1: fleets stay owner-authoritative (Phase 8/9 mirroring), so positions never desync — players just see different slipstream maps (one fleet can appear to burn impossibly fast through empty hyperspace) and get different travel opportunities.

Deferred fix, Phase 26 milestone 1: suppress the guest's `SlipstreamManager` via the same `removeScript`/`addScript`-at-`onGameLoad` mechanism used for the base managers, and replicate the host's finished stream segment polylines. Not the placement params — the builder itself consumes RNG, so RNG alignment is not attempted.

### Abyss partial parity

EP placement comes from each client's own unseeded `HyperspaceAbyssPluginImpl.random` (~line 59), so the encounter-point sets differ per client by construction. The `AbyssalRogueStellarObjectEPEC` fork makes generated systems deterministic per encounter-point id once an EP exists, but placement itself is not forkable. With the guest's `EncounterManager` suppressed (unseeded `new Random()`, `EncounterManager.java:66`), abyssal temporary star systems exist host-side only. Guests can travel the abyss, but deep abyssal content — rogue stellar objects, lights, Threat encounters — is host-experienced only.

Full fix, Phase 26 milestone 2: replicate each encounter's outcome (EPs are transient per-player probe points, not shared entities) and let the forked EPEC regenerate identical content guest-side; the fork was built cheap enough to support this.

### Sensor ghosts — suppressed, not accepted

`SensorGhostManager` seeds from `new Random(Misc.genRandomSeed())` (`SensorGhostManager.java:79`). Unlike the items above, this one is not left to diverge — it is suppressed guest-side, so hyperspace sensor ghosts do not spawn on the guest at all. Several ghost types spawn real encounters or fleets (EncounterTrickster, ShipGhost) or touch story state (Ziggurat/guide ghosts), so an independent guest-side roll risks story-state or NPC-authority conflicts, not just a visual mismatch. The suppressor nulls the cached sector-memory handle after removal. The loss is cosmetic only: the guest never sees ghosts, host or otherwise.

## Phase 12c — Guest Distress Call Retains the Mirror Fleet in the Host Save

When the guest activates `distress_call`, the host runs the vanilla plugin on the guest's mirror fleet (`CoopAbilityEffectApplier`). `DistressCallAbility.activate()` immediately calls `addResponseScript`, which does **not** create the route — it calls `Global.getSector().addScript(new DelayedActionScript(delayDays) { ... })` (`impl/campaign/abilities/DistressCallAbility.java:194,220`). That anonymous script holds an implicit reference to `DistressCallAbility.this`, which holds the mirror fleet through `getFleet()`. Ten to twenty in-game days later the script fires and calls `RouteManager.getInstance().addRoute("dca_distress_call", ..., DistressCallAbility.this, data)`, so a `RouteData` then holds the same plugin as its `RouteFleetSpawner`. Both are serialized into the host save, and the mirror fleet is reachable from them for as long as they live.

Targeted cleanup was considered and rejected:

- The route half is cheap to clean — `RouteManager.getRoutesForSource("dca_distress_call")` plus `RouteData.getSpawner()` identity plus `removeRoute` are all public (`impl/campaign/fleets/RouteManager.java:387,515,557`; the coop fork keeps that surface unchanged) — but it is also the half that usually does not exist yet. The mirror is torn down at session end, long before the 10-20 day delay elapses, so at cleanup time there is nothing in the route list to remove.
- The script half is the actual retention and is not removable. `SectorAPI.getScripts()` returns the list, but the only way to tell one guest-spawned `DelayedActionScript` from the host's own is the anonymous class's captured outer reference, and reading it needs `java.lang.reflect` — blocked by the script sandbox. Matching on the synthetic class name (`DistressCallAbility$2`) would cancel the host player's pending distress responses too.

Accepted. The retained reference is inert: neither `DelayedActionScript.doAction` nor `DistressCallAbility.spawnFleet` ever calls `getFleet()` — both read `Global.getSector().getPlayerFleet()` and the route's own `custom` payload (`DistressCallAbility.java:203,324`), so a dead mirror is never dereferenced. The cost is a dead `CampaignFleetAPI` kept in the host save graph until the script fires and its route expires — days of game time, then it is collected.

Second-order consequence, also accepted: because `spawnFleet` positions the response fleet relative to `getPlayerFleet()`, a guest-triggered distress response arrives near the **host**, not the guest. The jump points it routes through are still the guest's (they come from the `DistressResponseData` captured at activation, when the mirror was the fleet in system), so the responder does reach the right system; only the hyperspace approach is anchored wrong.

### Guest interdiction pulse: radius and duration read an unpinned stat

`InterdictionPulseAbility.getRange` and `getInterdictSeconds` both read `fleet.getSensorRangeMod().computeEffective(fleet.getSensorStrength())` (`InterdictionPulseAbility.java:123,309`). Phase 14b's `CoopSensorSync` pins the mirror's *sensor strength* and its `detectedRangeMod` totals, but not `sensorRangeMod`, so a guest whose skills or hullmods modify sensor range gets a pulse on the host that is slightly the wrong size and slightly the wrong strength against each victim. Accepted for v1: the error is a percentage on a 500+ su radius, and the pulse is not a state the two clients have to agree on — the host's result is the only one that exists.

The standing hit the pulse costs is also applied at **activation** time rather than at pulse time (`CoopAbilityEffectApplier.applyInterdictionRepHit`). Vanilla charges it from inside `applyEffect`, after the charge-up, against whoever is in range then; hooking that moment would mean forking the ability. A fleet that leaves or enters the radius during the charge-up therefore counts differently for reputation than it does for the interdict itself.

## Phase 12c — Market Capture Fidelity: Accepted Gaps

Gaps 2a through 2e closed most of what the market snapshot used to drop. Three things it still does not carry.

### Multi-module ships arrive with pristine modules

`CoopShipDetail` captures one variant: the parent's hull spec, perma mods, s-mods, refit, suppressed mods, weapons, wings, vents/caps and the member's base CR. A station or multi-module hull keeps its modules as separate variants referenced from the parent's module slots, and the codec does not recurse into them. A damaged Prometheus MkII listing therefore reconstructs with a battered parent and clean modules.

Not attempted because the recursion is unbounded in the wrong direction: module variants can themselves reference modules, the slot-to-variant mapping is not exposed as a settable pair on `ShipVariantAPI`, and the market listings that carry modules at all are rare (station hulls are almost never open-market stock). The parent's D-mods and CR are what price the listing, and those do survive.

### Mercenary level rolled off `Misc.random` before the snapshot

`OfficerManagerEvent.createOfficer` draws a mercenary's level with `Misc.random.nextInt(maxLevel + 1 - minLevel)` and its officer-vs-merc level bump with `(float) Math.random() > 0.75f` (`impl/campaign/events/OfficerManagerEvent.java:378,388`). Both clients roll independently, so before a `MARKET_SNAPSHOT` reaches the guest its bar holds different captains at different levels than the host's.

The snapshot overwrites that, so the divergence is only visible in the window between the guest's market screen opening and the host's reply arriving, and only if the guest is looking at the comm directory rather than the trade screen. Not worth a suppressor: `OfficerManagerEvent` also runs the timeout pruning that keeps stale offers from accumulating, so removing it guest-side would need that half reimplemented.

### `OpenMarketPlugin.writeReplace` drops stock older than 30 days on save

`OpenMarketPlugin` clears its ship and weapon stock at serialization time when `okToUpdateShipsAndWeapons()` says the last roll is over 30 days old, so a market's shop contents can change across a save/load with no player action and no event. Host and guest save at different moments and reload with different clocks, so the two copies of a market neither has docked at recently can drift apart without either client doing anything.

Independent of gap 2e's restock rebroadcast, which only fires on `reportPlayerOpenedMarketAndCargoUpdated`. The converging force is the same one gap 2e relies on: any dock re-runs `updateCargoPrePlayerInteraction` and the host re-broadcasts, so the drift lasts until the next time either player opens that market.

## Phase 12c — Bar Pool: What Is Replicated and What Is Not

The host's `PortsideBarData` pool is captured in order and pushed on change (`MISSION_POOL_SNAPSHOT`, carrying each offer's id, class name, content seed and `shownAt` pin, plus the host's `BarEventManager` seed). The guest rebuilds the replicable part of its own pool from that list and has its `BarEventManager` script registration removed so it rolls nothing of its own. Five things this does not give you.

### Offer numbers scale off the local fleet — user-accepted

The wire carries the seed an offer regenerates from, not the numbers it regenerates into. `DeliveryBarEvent` and its siblings size quantity and payment against the *local* player's cargo capacity and the local market's supply price, inside `regen(market)`, from `seed + market.getId().hashCode()`. Two players therefore see the same offer from the same person for the same commodity with different tonnage and different credits.

Accepted on request rather than by default: pinning the numbers would mean either capturing every derived field per offer type or forking each event class, and both players completing the same offer is the point, not both completing it for the same fee.

### Rumor offers stay locally generated, and can shift the shown subset

`PirateBaseRumorBarEvent` and `LuddicPathBaseBarEvent` hold a live `PirateBaseIntel` / `LuddicPathBaseIntel` reference, and their `shouldRemoveEvent()` reads it. Nothing about that survives the wire, so the capture skips them and the guest keeps making its own from the Phase 13 replicated base intel. Both are `isAlwaysShow()`, so both players do see their local one.

The cost is subset parity. `BarCMD.showOptions` runs `Collections.shuffle(events, random)` over the whole pool, and `shuffle`'s permutation is a function of the list *size* and the random alone. A rumor event sitting at a different index on each client shifts every other offer's post-shuffle position, so the two bars can show different picks out of an identical pool. With no rumor events live, which is the normal early-campaign state, the two pools are element-for-element identical and the picks match.

### Injected offers never expire on the guest

`BarEventManager.advance` is what ages `active` and drops timed-out offers, and it is exactly what the suppressor stops. Injected events are also deliberately kept out of `barEventCreators`, because `advance`'s orphan sweep deletes anything that is in `barEventCreators` but not in `active`; an event the manager has never seen is invisible to that sweep and survives.

So nothing on the guest ever removes an injected offer on a timer. The host's next snapshot does it instead: when an offer expires or is accepted host-side it leaves the host pool, the pool signature changes, and the guest's rebuild drops it. Same for a guest that accepts an offer, which the host's copy will keep offering until its own timer runs out.

### Accepting an offer is not arbitrated at the offer level

`CoopInteractionGate` claims are keyed by entity id, so two players at the same market already serialize on the market entity, bar screen included. There is no seam below that: the engine fires no listener on `BarEventManager.notifyWasInteractedWith`, so a bar acceptance cannot request a `MISSION_CLAIM_REQUEST` without forking the dialog plugin. What the pool does enforce is the host's side of first-come — an offer the host has taken vanishes from its pool and is gone from the guest's next rebuild — and `visibleEntriesFor` keeps any claim recorded through the existing machinery out of the injected set.

### Contacts, contact-board missions and person bounties stay per-player — user-accepted

Only bar events ride the pool. Contact lists, the missions a contact offers through `BaseMissionHub`, and `PersonBountyManager` are untouched, and `PersonBountyManager` is one of the scripts the Phase 9 suppressor removes guest-side, so a guest has no person bounties at all in v1.

## Phase 12c — Survey Levels and Ruins: Three Accepted Leaks

`MarketAPI.SurveyLevel` and the `$ruinsExplored` market-memory flag now replicate on the both-sides skeleton poll, as `WORLD_DELTA(SURVEY)` and `WORLD_DELTA(RUINS_EXPLORED)`. Apply is max-wins on the level's ordinal, so the two clients converge whatever order the deltas land in. What does not replicate is everything *around* the level.

### The remote_survey lockout is per-player, so a system can be swept twice

`RemoteSurveyAbility` latches its once-per-system flag into the star system's own memory (`$core_didRemoteSurveyInSystem`, `abilities/RemoteSurveyAbility.java:21,104`) and `findBestPlanet` refuses to run again while the key is present (line 131). That key is not on the wire, so host and guest each hold their own copy and each can remote-survey the same system once. Two PRELIMINARY sweeps where a solo campaign gets one.

Left that way on purpose. The flag guards a cost the second player would still pay: the ability pins fleetwide max burn to zero while it charges (line 89), and the planet it would pick is already at PRELIMINARY from the first player's sweep, which arrived as a `SURVEY` delta. Replicating the flag buys nothing and takes the ability away from whoever activates second. The acting player also keeps the `RemoteSurveyDataForPlanetIntel` entry the ability mints (line 101); the other player gets the level without the intel entry.

### The survey data goes to whoever ran the survey

Completing a planet survey puts one `survey_data_1` through `survey_data_5` unit in the surveying fleet's cargo, picked by `SurveyPluginImpl.getSurveyDataType` off the planet's conditions and hazard (`impl/campaign/SurveyPluginImpl.java:157-183`). The peer's planet reaches FULL through the delta instead, and a planet at FULL is not offered the survey option again, so the peer never collects a unit of its own.

Same rule as salvage: one player loots, the world state is shared. Not a bug to fix, and the alternative (minting a second commodity stack from a replicated flag) would be duplication of a sellable good.

### A guest's survey mission pays out when the host does the surveying

`SurveyPlanetMissionIntel.advanceMission` polls the target planet every frame and calls `reportPlayerSurveyedPlanet` the moment its market reads FULL (`intel/SurveyPlanetMissionIntel.java:141-143`). It never asks who surveyed it. So a survey mission the guest accepted completes, with payment, when the host's FULL arrives over the wire.

Consistent with the shared world the rest of Phase 12 builds, and the same thing already happens for a mission whose target the host decivilizes or whose objective the host captures. Worth knowing before treating survey contracts as a per-player income stream: two players holding the same contract from different bar offers both get paid for one survey.

### Either player entering a system reveals its planets on both maps

`CoreScript.markSystemAsEntered` bumps every planet in a newly entered system from NONE to SEEN, and the poll replicates SEEN like any other level (deliberately — the system-map display reads the minimum system survey level, and filtering SEEN out would leave the two maps visibly different). The consequence: one player's travels light up planet markers on the partner's map. That is a shared-exploration feature under this mod's model, but it is a visible departure from two solo campaigns and belongs in any "what's different in co-op" player doc (Phase 23).
