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

### What the loader actually refuses (read off the shipped class, 2026-09-04)

`com/fs/starfarer/loading/scripts/B.loadClass` refuses every name that starts with `java.io`,
`java.nio.file.File` or `java.lang.reflect`, minus an explicit allow-list held in its constant pool:

- `java.io`: `BufferedInputStream`, `BufferedReader`, `FilterInputStream`, `InputStreamReader`,
  `Reader`, `Serializable`, `InvalidClassException`, `ObjectStreamException`, `InputStream`,
  `IOException`, `PrintStream`, `PrintWriter`, `ByteArrayInputStream`, `FilterOutputStream`,
  `OutputStream`, `Closeable`, `Flushable`, `StringReader`, `FileReader`.
- `java.nio.file`: `Path` and `Paths` pass (they do not start with `File`); `Files` and
  `FileSystems` do not.
- `java.lang.reflect`: `AnnotatedElement`, `InvocationTargetException`, `Type` and
  `GenericDeclaration` pass; everything else does not.

The project rule above stays as written: it is a superset of the engine's list, it costs nothing,
and it survives an engine update that trims the list. But it is not an explanation for a crash. The
types that actually trip the guard include `UncheckedIOException`, `StringWriter`,
`ByteArrayOutputStream`, `EOFException` and `File`, and `java.io.IOException` is not one of them.

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

- The call catches `Throwable`, never a named checked exception. `loadText` declares `IOException`;
  that one type is on the loader's allow-list above, so naming it would in fact load, but the broad
  catch costs nothing and is what keeps the call safe against the `java.io` types that are blocked.
  (Corrected 2026-09-04: this bullet used to state that naming `IOException` trips the guard.)
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
> `com.fs.starfarer.settings.StarfarerSettings`: getter `Oo0000()Z`, static setter `ö00000(Z)V`
> — U+00F6 then five zeros; the earlier `?00000` reading was `javap` rendering that non-ASCII
> character, and five *different* static `(boolean)` setters on that class print identically, so the
> setter must never be looked up by name. Both accessors read/write the private static boolean field
> literally named `class` (the Java keyword; distinct from the separate field `class.class`), which
> is what `CoopFastForwardLock` resolves instead, with `findStaticGetter`/`findStaticSetter` on a
> `privateLookupIn` of that class. Corrected 2026-09-02 by parsing the constant pool of
> `StarfarerSettings.class` on 0.98a-RC8.) In toggle mode the per-frame key poll is skipped entirely; the persistent
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
>
> **Built (Phase 7b, 2026-09-02).** `data/config/settings.json` no longer carries the override at
> all; `coop.time.CoopFastForwardLock` forces toggle mode + `campaignSpeedupMult=2` on both roles
> for the life of a session, mirrors the host's `CampaignState.fastForward` field onto the guest from
> `CoopTimeLock.apply`, and restores the player's own toggle preference when the session ends. The
> `setInFastAdvance(...)` mirror described in the last bullet below is **gone** — it moved nothing
> but a cosmetic flag. The runtime 1x lock survives only as the degrade path (handles fail to
> resolve, or `-Dcoop.ff.disable=true`).
>
> Two caveats on the forced toggle flag (it is a static on `StarfarerSettings`, process-wide, set
> false by the class initializer): (1) the restore only runs from the pump's session-end branch, so
> leaving the campaign mid-session (exit to menu, load another save) leaves toggle mode on until the
> next coop session ends or the game restarts — harmless in play, hold-Shift simply acts as a tap
> toggle; (2) if the player opens the vanilla settings menu *during* a session and applies, vanilla
> writes the current (forced-true) value to its settings file, and the mod cannot tell. Neither is
> worth a fix in v1; noted so a "my Shift became a toggle" report is recognised.
>
> **Consequence for the NPC handoff margin (2026-09-04).** `CoopNpcThreatWatcher.handoffMargin`
> takes a campaign speed multiplier, read per scan off `CampaignUIAPI.isFastForward()`: at
> `CoopFastForwardLock.SESSION_MULT` a chaser covers that multiple of the distance inside the same
> RTT budget, so a margin sized for 1x fired the pre-contact handoff after contact. The multiplier is
> clamped at 1, so it can only widen the band. The `p95 <= 0` case (a loopback link) still returns
> the flat `CONTACT_MARGIN_SU` and is deliberately not scaled: that floor covers measurement noise,
> not travel.

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
>
> **Built 2026-09-02 (`coop.time.CoopClockReconciler`).** `getCal()` is on the public
> `CampaignClockAPI` (javap of `starfarer.api.jar`), so the only handle the reconciler needs is a
> `MethodHandles` setter for the private `long timestamp` cache; both are written together, `cal`
> first, because `cal` is `transient` and `timestamp` is the only persisted representation. The
> reconciler is guest-only and corrects accumulated *in-session* drift; it does not close a
> connect-time gap and does not attempt late-join catch-up, so nothing below is retired. Writing the
> clock also cannot move an on-screen orbit position: every orbit class integrates a private
> `currAngle` from the frame dt and never reads the clock's absolute value; the 1 Hz orbit snap still
> owns that. Falls back to the pre-7c behaviour (uncorrected drift, one logged warning) on any handle
> failure or with `-Dcoop.clock.disable=true`.

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

## Title Screen And New Game Dialog (2026-09-02)

### The "New Game" button cannot be renamed

The label is a string constant inside the obfuscated title-screen class (`com.fs.starfarer.title.C`). It is not in `data/strings`, not in `settings.json`, and the title screen is built before `onApplicationLoad` returns, so no mod hook can reach it. A guest launch therefore still starts with "New Game". The coop cue lives on the new-game dialog's Continue option instead (`coop.newgame.CoopNewGameDialogPlugin`, registered through the `newGameDialogPlugin` key the mod's `settings.json` already owns for procgen).

### The new-game options panel is one atomic widget

`VisualPanelAPI.showNewGameOptionsPanel(data)` is the only entry point for name, portrait, gender, seed field, sector size and star age. There is no per-field enable/disable, and the panel writes seed, `sectorSize` and `sectorAge` back onto `CharacterCreationData` whenever its state changes. The only way to hold coop values is to overwrite them after the panel: the plugin pins on `init`, on every `advance` frame, and once more on Continue. Procgen reads the data object last, so the last write wins.

### Do not re-show the text panel next to the options panel

`NewGameDialogPluginImpl.init` ends with `dialog.hideTextPanel()`. Calling `showTextPanel()` after it reserves the left column for the text panel, which pushes the options panel to the right of center, and the added paragraph still does not render. Tried and reverted the same day.

### Player-faction fleet names carry the faction article

Vanilla renders every fleet as `<faction display name with article> <fleet name>`, and `data/world/factions/player.faction` sets `displayNameWithArticle` to `Your`. A player-faction fleet named `Alice` therefore shows as "Your Alice"; once colonies name the faction it becomes "<Faction> Alice". The partner's mirror fleet is named `partner <Name>` so both prefixes read as a sentence. Any future player-faction fleet the mod names needs a noun phrase, not a bare name.

### Guest rejoin is by loading the coordinated autosave

A guest that quit mid-session rejoins by loading the save that Phase 16's coordinated autosave wrote, whose stored campaign id matches the host's. A New Game on the same seed is rejected at seed lock ("this campaign is already in flight and this guest campaign is brand new") and only `launch-guest.ps1 -AdoptCampaign` (`-Dcoop.adoptCampaignId=true`) forces it through, at the cost of the guest's progress. To find the right save, grep the host campaign id from the reject line across `saves/save_*/campaign.xml`.

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

Correction and deferred fix (2026-09-05, Phase 26 milestone 4, open decision): "not suppressible" is true of the script suppressor only. The plugin is registered by class name in `data/campaign/terrain.json`, `auto`/`activeCells`/`tiles` are `protected`, and `advance`/`readResolve` are overridable, so a mod-side subclass can take its place without a classpath fork. The committed generation (`cells`) only changes at the 1.5-2.5 day interval boundary and already has a deflate codec (`encodeTiles`), so it replicates as a one-shot capture per generation, like a slipstream polyline. Trackers (`CellStateTracker`) are per-client by construction: they exist only within ±10000 su of the local player and take their durations from `Math.random()` at creation, so they are keyed off `(generation, i, j)` with an elapsed catch-up instead of being shipped. Strike timing and damage stay local per fleet. Also worth knowing for the "cosmetic" claim: a storm strike grants a 1.25 s burn burst (`HyperStormBoost`) and CR damage, so two fleets travelling together do get different boosts and hits today.

### Star-corona / pulsar flares

`FlareManager`, `new Random()` at line ~307. Same ownership argument as storm cells — a flare only affects the fleet it hits, no shared state involved. Accepted.

### Officer pools at markets — RESOLVED by Phase 12c gap 2d

Was accepted here as "each player hires from their own pool". Now replicated: the host's pool rides the `MARKET_SNAPSHOT` as one stock line per person and the guest strips its own pool and rebuilds the host's through `OfficerManagerEvent.addAvailable`/`addAvailableAdmin`. One roll still diverges before the snapshot lands; see "Mercenary level rolled off `Misc.random`" below.

### Smuggling scans and patrol hassles of the local player

Per-player by design: local dialog interactions against a player's own cargo and rep, with no shared state touched. Accepted.

**Widened by Phase 32 (2026-09-05), same reasoning.** The black market and the military submarket are shared now, but everything a trade *causes* still runs only on the engine that made the trade: smuggling suspicion, the odds a patrol scans you, learning a blueprint from a sale, and the price impact of a large trade. Two players standing at the same black market can read different suspicion strings. The cargo moves; the consequences do not.

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

## Gate Scanning and Stable-Location Construction: Accepted Residues

Both were guest-limiting gaps rather than engine limits, and both are closed. What is left is small and worth knowing.

**Gate scanning.** The "Scan the Gate" option is gated by rules.csv on `$global.canScanGates` (`gateOpenDialogCanScan1`, `gateScanOpt`), a sector-memory flag only the host's Galatia questline ever sets. It now rides the `GATE_ACTIVATED` payload alongside `$gatesActive` and `$playerCanUseGates`, and the poll that produces that payload runs on both roles, so a guest can scan and both players get the gate.

- Because the three globals repeat on every gate record, a flip in any of them re-reports **every** gate in the sector on the next poll. That is a dozen-odd deltas, a handful of times per campaign, and it is what makes each packet self-contained.
- `$numGatesScanned` is derived rather than synced: whichever client applies a peer's scan calls vanilla's own `GateEntityPlugin.addGateScanned()`. Both clients therefore count the gates they know to be scanned, which converges — but a client that never hears about a scan (a gate scanned while it was disconnected and never re-reported) counts one low, which only matters as a rules condition inside the Galatia questline.
- A guest that **rejoins from a save** where gates were already scanned reports each of them upward once on its seeding poll. The host applies them as no-ops. Harmless, and cheaper than tracking which flips the guest learned from the host in a previous session.

**Stable-location construction.** `Objectives.build` creates the makeshift relay/buoy/array with `addCustomEntity(null, ...)`, so the engine mints its id per client; the same is true of the stable location `Objectives.salvage` puts back on disassembly. Both now ride a `SPAWN` world-delta carrying a coop-assigned id, the spec, the faction, the orbit, and the id of the stable location the build consumed.

- The consumed stable location is removed **twice** on the receiving client — once by the `SPAWN` apply and once by the `CONSUME` the originator's watcher emits for it — and the peer's own removal then reports a `CONSUME` back. All three are idempotent and the ledger absorbs them; suppressing the redundancy would mean tracking a per-entity exception through two watchers to save one packet.
- The orbit rides the wire rather than being copied off the consumed stable location, so the two deltas are order-independent. If the orbit focus does not resolve on the peer (it is a gen-time planet or star, so it should), the entity materializes at the fixed position that rode along and does not orbit.
- Only the entity is replicated, not the interaction that produced it. Build costs come out of the acting player's own cargo, and the reputation hit for disassembling somebody else's objective (`Objectives.salvage`) is charged to the acting client alone.
- The Phase 6b world fingerprint covers markets only, so neither half of this can move it.

## Phase 12c — Guest Distress Call Retains the Mirror Fleet in the Host Save

When the guest activates `distress_call`, the host runs the vanilla plugin on the guest's mirror fleet (`CoopAbilityEffectApplier`). `DistressCallAbility.activate()` immediately calls `addResponseScript`, which does **not** create the route — it calls `Global.getSector().addScript(new DelayedActionScript(delayDays) { ... })` (`impl/campaign/abilities/DistressCallAbility.java:194,220`). That anonymous script holds an implicit reference to `DistressCallAbility.this`, which holds the mirror fleet through `getFleet()`. Ten to twenty in-game days later the script fires and calls `RouteManager.getInstance().addRoute("dca_distress_call", ..., DistressCallAbility.this, data)`, so a `RouteData` then holds the same plugin as its `RouteFleetSpawner`. Both are serialized into the host save, and the mirror fleet is reachable from them for as long as they live.

Targeted cleanup was considered and rejected:

- The route half is cheap to clean — `RouteManager.getRoutesForSource("dca_distress_call")` plus `RouteData.getSpawner()` identity plus `removeRoute` are all public (`impl/campaign/fleets/RouteManager.java:387,515,557`; the coop fork keeps that surface unchanged) — but it is also the half that usually does not exist yet. The mirror is torn down at session end, long before the 10-20 day delay elapses, so at cleanup time there is nothing in the route list to remove.
- The script half is the actual retention and is not removable. `SectorAPI.getScripts()` returns the list, but the only way to tell one guest-spawned `DelayedActionScript` from the host's own is the anonymous class's captured outer reference, and reading it needs `java.lang.reflect` — blocked by the script sandbox. Matching on the synthetic class name (`DistressCallAbility$2`) would cancel the host player's pending distress responses too.

Accepted. The retained reference is inert: neither `DelayedActionScript.doAction` nor `DistressCallAbility.spawnFleet` ever calls `getFleet()` — both read `Global.getSector().getPlayerFleet()` and the route's own `custom` payload (`DistressCallAbility.java:203,324`), so a dead mirror is never dereferenced. The cost is a dead `CampaignFleetAPI` kept in the host save graph until the script fires and its route expires — days of game time, then it is collected.

Second-order consequence, also accepted: because `spawnFleet` positions the response fleet relative to `getPlayerFleet()`, a guest-triggered distress response arrives near the **host**, not the guest. The jump points it routes through are still the guest's (they come from the `DistressResponseData` captured at activation, when the mirror was the fleet in system), so the responder does reach the right system; only the hyperspace approach is anchored wrong.

### Guest interdiction pulse: radius and duration read an unpinned stat — RESOLVED by the Phase 20 red-team pass (C3)

**Correction 2026-09-05:** the first paragraph below is stale. `CoopSensorSync.Profile` has carried the three `sensorRangeMod` aggregates (flat, percent, mult) since red-team finding C3 (`04a8676`), and `applySensorRange` pins them on the mirror every frame next to the sensor strength, so `InterdictionPulseAbility.getRange`/`getInterdictSeconds` now read the guest's real values on the host. What remains accepted is the second and third paragraph: the standing hit is charged by the guest's own pulse at charge-up time, and only against fleets the guest's client knows about.


`InterdictionPulseAbility.getRange` and `getInterdictSeconds` both read `fleet.getSensorRangeMod().computeEffective(fleet.getSensorStrength())` (`InterdictionPulseAbility.java:123,309`). Phase 14b's `CoopSensorSync` pins the mirror's *sensor strength* and its `detectedRangeMod` totals, but not `sensorRangeMod`, so a guest whose skills or hullmods modify sensor range gets a pulse on the host that is slightly the wrong size and slightly the wrong strength against each victim. Accepted for v1: the error is a percentage on a 500+ su radius, and the pulse is not a state the two clients have to agree on — the host's result is the only one that exists.

The standing hit is charged by the **guest's own** vanilla pulse, not by the host. On the guest the pulsing fleet *is* the player fleet, so `InterdictionPulseAbility.applyEffect` runs its `INTERDICTED` adjustment locally at pulse time, and `onPlayerReputationChange` forwards it to the host as a `GUEST_REP_DELTA`. The host used to re-apply the hit against the mirror as well (`CoopAbilityEffectApplier.applyInterdictionRepHit`), which charged the canonical standing twice per victim and then rebroadcast the doubled value; that code is gone.

The residue is a narrow **undercharge**: the guest's own pulse only sees the fleets the guest's client knows about, so a fleet inside the host's pulse radius that the guest never mirrored costs the guest nothing. The victim set is also gated by the guest's transponder and detection state rather than the host's. Accepted — undercharging by a fleet the guest could not see beats double-charging every fleet it could.

## Phase 12c — Market Capture Fidelity: Accepted Gaps

Gaps 2a through 2e closed most of what the market snapshot used to drop. Two things it still does not carry; the third, module loadouts, was closed by Phase 32.

### Multi-module ships arrive with pristine modules — RESOLVED by Phase 32 (2026-09-05)

Was accepted here because `CoopShipDetail` captured one variant: a damaged Prometheus MkII listing reconstructed with a battered parent and clean modules. Phase 32 needed the same blob for shared storage, where a parked ship losing its modules is a real loss rather than a wrong price, so the codec now recurses `getModuleSlots()`/`setModuleVariant` to a depth of four and carries weapon groups, current hull fraction and the variant display name with it.

Two residues, both in the Phase 32 section below: only a module hanging directly off the member carries its hull damage, and nesting past four levels is refused rather than walked.

### Mercenary level rolled off `Misc.random` before the snapshot

`OfficerManagerEvent.createOfficer` draws a mercenary's level with `Misc.random.nextInt(maxLevel + 1 - minLevel)` and its officer-vs-merc level bump with `(float) Math.random() > 0.75f` (`impl/campaign/events/OfficerManagerEvent.java:378,388`). Both clients roll independently, so before a `MARKET_SNAPSHOT` reaches the guest its bar holds different captains at different levels than the host's.

The snapshot overwrites that, so the divergence is only visible in the window between the guest's market screen opening and the host's reply arriving, and only if the guest is looking at the comm directory rather than the trade screen. Not worth a suppressor: `OfficerManagerEvent` also runs the timeout pruning that keeps stale offers from accumulating, so removing it guest-side would need that half reimplemented.

### `OpenMarketPlugin.writeReplace` drops stock older than 30 days on save

`OpenMarketPlugin` clears its ship and weapon stock at serialization time when `okToUpdateShipsAndWeapons()` says the last roll is over 30 days old, so a market's shop contents can change across a save/load with no player action and no event. Host and guest save at different moments with different amounts of accumulated play time, so the two copies of a market neither has docked at recently can drift apart without either client doing anything. *(Mechanism precision, 2026-08-25 bytecode check: `okToUpdateShipsAndWeapons()` reads `sinceLastCargoUpdate`, a frame-dt accumulator on `BaseSubmarketPlugin` — NOT a campaign-clock timestamp — so Phase 7c clock reconciliation does not change this behavior. The clock-adjacent part is the reroll seed, `getMonth() * 170000`, which 7c DOES help by keeping both clients on the same month number.)*

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

## Phase 24 — Shared Colonies: Three Accepted Divergences

### The guest's hostile-activity meter runs its own race

`HostileActivityManager` is deliberately *not* on the Phase 9/13 suppressor list — it does not end in `FleetManager`/`RouteManager`/`BountyManager` and it was never added to `KNOWN_SPAWNERS` — so both clients advance their own copy of the colony-crisis event. It is harmless where it counts: everything it spawns reaches the campaign through `RouteManager`, which *is* suppressed guest-side, so the guest's copy produces no fleets. What diverges is the meter itself — event points, stage progress, which factor is loudest — because the two clients' `EconomyUpdateListener` inputs are not identical and the intel is not replicated.

The consequence is that the guest's own hostile-activity intel is not a reliable read on what is actually coming. The mirrored `CoopExpeditionWarningIntel` entry is the authoritative inbound-attack signal on the guest: it is scanned off the host's live intel manager and reconciled as a set. A guest that sees a native hostile-activity entry and a coop warning for the same colony is seeing its own simulation next to the real one; the coop entry is the one with a countdown that matches the fleets on the map.

**RESOLVED 2026-09-05 (`5e342e2`):** the paragraph that stood here argued suppression would cost more than it buys because `HostileActivityEventIntel.get()` is a singleton other systems reach for. Every reader was checked in the pristine source and tolerates null, so `HostileActivityManager` now joins the guest suppression set and the session-start pass ends any `HostileActivityEventIntel` the save holds, removes it and unsets `$hae_ref`. The guest has no meter and no `HOSTILE_ACTIVITY` colony condition; the coop expedition warning is its only inbound-attack signal. The host is untouched.

### Construction progress drifts between the two clients until an industry finishes

`COLONY_MGMT` replicates the construction *queue*, not build progress. That is the right primary channel — vanilla's build button only appends to `market.getConstructionQueue()`, and each engine's own `Market.advance` drains it through `BaseIndustry.buildNextInQueue` — but the two engines start the same build at slightly different moments and then run their own timers, so the progress bars do not match to the day.

Reading the true progress across the wire is not cheaply possible: `Industry.getBuildOrUpgradeProgress()` returns a 0..1 fraction that reads `0` whenever the industry is disrupted (`BaseIndustry.java:491-498`), the absolute-days field needs a `BaseIndustry` cast, and the `buildTime` field it is measured against is not readable at all during an upgrade — `getBuildTime()` returns the *spec* value, not the field.

Accepted, because it self-heals with a bound: the first client to finish reports the industry as finished, and the applier forces the lagging mirror to `finishBuildingOrUpgrading()`. The drift is therefore never larger than the gap between the two starts, and it always resolves at completion. The one visible artifact is that the client that finishes second may see its "construction complete" message a moment early.

### Commodity fulfillment and shortage markers on a player colony can differ between clients

Observed live 2026-09-01: the same shared colony showed different demand-met / deficit markers on the two clients — one side flagging a shortage the other did not.

Nothing about the colony itself is out of sync; the economy around it is. Each engine runs its own `EconomyAPI` and solves supply for every market in the sector on its own iteration schedule, so fulfillment is a *derived* value, not replicated state. Two things guarantee the inputs differ: the two clocks sit a couple of days apart (the clock reconciler in Phase 7c is not built yet), and NPC market stockpiles and production are each engine's own simulation. `COLONY_MGMT` replicates the industries and the queue, Phase 12 replicates market contents on open — neither claims to replicate the sector-wide supply solve that decides which commodity reads as short.

Same root as the income drift seen in that session (host 1456 vs guest 1663 in a month where the two colonies were not yet producing the same thing; the next month matched exactly, drift 0).

Accepted. The host is canonical — its reading is the one to trust when the two disagree. Worth revisiting only if this ever turns into a persistent *stability* divergence rather than a display difference: a shortage that sticks on one side long enough to feed the stability penalty would make the two colonies grow apart, which the industry/queue channel would not catch.


## Phase 20 — Transport Hardening: Four Runtime Facts

### The network pump does not run during combat or while a save is written

`CoopNetPump` is an `EveryFrameScript` on the campaign engine. Combat runs on a different screen and
`saveGame` blocks the thread that would otherwise tick it, so during either one the pump stops
draining inbound bytes and stops sending. On a fast machine a save is a few seconds; on a slow one
with a large sector it can pass 15 s, which is exactly the link-death threshold.

This is why link death is declared on *inbound TCP silence* rather than on anything the pump measures
about its own cadence, and why the rule carries three exemptions: the peer is in a battle
(`BATTLE_STATUS`, aged out after 30 s of silence so a mid-combat drop cannot leave a phantom), a
`SAVE_CHECKPOINT` passed within the last 60 s, or this process itself stalled. A fourth case, the
*peer's* own save, has no natural signal at all: the saving side is the one that goes quiet, and it
cannot send while it is blocked. Both roles therefore announce a `STALL_NOTICE` from `beforeGameSave`
and flush it immediately, before the block starts.

The same constraint rules out declaring death from silence in `CoopLinkQuality`, which measures RTT
and loss: quiet is normal there. All death decisions live in `CoopReconnectCoordinator`.

### A throttled game window runs its clock slow, and looks like the *other* client running fast

Starsector caps per-frame `dt`. A minimized or background window gets far fewer frames, so its
campaign clock advances slower than wall time, and from the other client's point of view the throttled
side is behind and it is ahead. During the 2026-09-02 QA matrix the guest read up to 2.5 game-days
ahead of a minimized host; the Phase 7c reconciler pulled it back to 0.01 game-days once both windows
were visible again.

This is only reachable when both games run on one PC, which is exactly what a two-instance test
session does. It is not a defect and there is no fix from inside the mod: the engine will not run a
window it does not have. Rule for two-windows-one-PC sessions, and it is worth telling testers:
keep both windows restored and visible.

### The agent bridge serves four clients at a time

`-Dcoop.debug.bridge` opens a socket that accepts up to four connections at once. The fifth is closed
on connect and the refusal is logged; the four already connected are untouched, because the client
being served is the one with work in flight.

It used to accept exactly one, which is how a scripted supply drip got `ECONNRESET` once a minute
through profile (e) of the QA matrix while the MCP server held the line. Each client now carries its
own framing buffer, request queue and write queue, and the four-commands-per-frame dispatch budget is
spent one request per client per pass, so the cap on campaign-thread time per frame is unchanged and
a client sending a burst cannot starve another.

The cap is a real limit, not a formality: every slot costs a 256 KB framing buffer for the life of the
connection, and all of it — accept, read, dispatch, write — runs on the campaign thread inside
`advance()`. Four is sized for one MCP server plus a helper or two, which is the load this tooling
was built for.

### The mod cannot release its UPnP port mapping when the process exits

`CoopPortMapper` releases its lease on the next game load, not at shutdown. There is no engine hook
for process exit that the sandbox can reach, and a JVM shutdown hook would run on a thread the mod is
not allowed to build network state on. A mapping therefore outlives a closed game until the next
launch cleans it up.

Routers with working lease timers expire it on their own within the renewal interval. The case that
matters is a router that rejects timed leases (`UPnPError 725`): the mod falls back to a permanent
mapping there, so a crash leaves the port open until the next launch.

### A UPnP response larger than 256 KB is abandoned

`CoopPortMapper` stops reading a gateway's HTTP response at `MAX_RESPONSE_BYTES` (256 KB) and settles
the exchange as failed rather than growing its buffer. Real device descriptors and SOAP replies are a
few kilobytes; the cap exists so a gateway that answers with a stream, or a device on the LAN
pretending to be one, cannot make the mod accumulate unbounded bytes on the campaign thread. A router
whose descriptor genuinely exceeds it will not be mapped, and the log says which limit was hit.

## Limitations review 2026-09-05 — One New Divergence, One Enforced Rule

### System bounties are posted per engine (planned fix: Phase 34)

`SystemBountyManager` (`CoreLifecyclePluginImpl.java:722`, a `BaseEventManager` sector script) was never in the Phase 13 suppression set: it spawns no fleets, only `SystemBountyIntel` entries, so the "spawner" filter did not catch it. Each engine therefore posts its own system bounties from its own rolls, and `SystemBountyIntel.reportBattleOccurred` pays the local player from the local intel for local kills. The guest is paid by its own game for bounties the host never saw, and sees none of the host's. Accepted until Phase 34 replicates the host's set and suppresses the guest's manager; person bounties (already suppressed, so the guest has none) are the other half of that phase.

### The Galatia Academy chain is unavailable on the guest (enforced 2026-09-05)

Not a divergence but the rule that prevents one. `CoopStoryChainGate` publishes `$coopIsGuest` on sector memory from the `CoopModPlugin.beginGameSession()` prologue (set on a guest launch, unset on host or no-role), and nine vanilla `rules.csv` rows are replaced by id with `!$global.coopIsGuest` appended: `goToTheGABarEventOption`, `goToGA_barEvent`, `gaAddOptionMeetProvost`, `gaIntro2surveyOpen`, `gaDHOhookStart`, `gaDHOhookStartDev`, `gaDHOjustFoundArrayStart`, `hamatsu_PostShipRecoverySpecial`, `gaDevMenuOption`. Everything downstream tests state only those roots can write. Tutorial-only entries are unreachable because the mod forces the tutorial skip. `CoopRulesFileTest` pins the gate on every root and the file's id uniqueness.

## Bug audit 2026-09-04 — Four Accepted Divergences

Found by the bug-hunt campaign recorded in the plan; each was judged not worth the code it would take
in v1. The full report lives outside the repo at
`tmp_ff_analysis\bughunt\BUG-REPORT-2026-09-03.md`.

### The guest's `PirateBaseManager` start date restarts on every guest load (fleet-12)

`CoopNpcFleetSuppressor.removeSpawnerScripts` takes `PirateBaseManager` out of `sector.getScripts()`,
so a guest save no longer carries it. On the next load vanilla's `CoreLifecyclePluginImpl` sees
`!sector.hasScript(PirateBaseManager.class)` and constructs a fresh one, whose constructor sets
`start = clock.getTimestamp()` and overwrites the `$core_pirateBaseManager` handle that
`MANAGER_HANDLES` deliberately preserves as a data holder.

The visible effect is that `PirateBaseManager.getInstance().getDaysSinceStart()` reads ~0 on the guest
after a reload while the host's reads the real campaign age. It feeds `Tuning.getDaysSinceStart()` and
a few locally constructed bar missions (`SurplusShipHull` cycles, `CustomProductionContract`), so
those parameters differ between the two clients and reset again on every subsequent guest load. Fixing
it means writing a private field on a vanilla manager after construction, on every load, which is more
surface than the drift is worth.

### An orphan mirror cargo pod can outlive the pod it copies

Mirror pods are created with `setNeverExpire(true)` so the creating client stays the only owner of the
decay timer. The cost is the other direction: when the original expires while nobody is in that
location, nothing generates the `WORLD_DELTA(CONSUME)` that would remove the mirror, so the partner
keeps a pod that no longer exists on the authoritative side and can still loot it. This is the milder
half of a trade — the alternative, letting each client run its own timer, deleted live pods out from
under the player who dropped them.

### Ambient fleets can appear on top of a guest in a system the host is not in (forks-2) — RESOLVED 2026-09-05 (`5dece99`)

**Resolution:** the forked `setLocationAndOrders` reads the position back after the AI constructor places the fleet and, when the guest's presence entity is in the same system and the fleet is inside `getMaxSensorRange() + 500` of it, moves it to `minDist + 2000` toward the star (the same numbers `pickLocationNotNearPlayer` uses for the host), unless that point would land within the same distance of the host. Presence null = vanilla. The paragraphs below describe the defect as it was.


The `DisposableFleetManager` fork makes `currSpawnLoc` presence-aware so ambient pirate and Pather
fleets spawn around the guest as well as the host. Vanilla's placement, however, branches on
`fleet.getContainingLocation() == Global.getSector().getCurrentLocation()`, which on the host-authored
side is the host's location, and only the host-present branch routes through
`Misc.pickLocationNotNearPlayer`. In a guest-only system the fleet takes the other branch and is
dropped at `Misc.getPointAtRadius(target.getLocation(), target.getRadius() + 100f)` with no distance
check against anyone — which can be the jump point or planet the guest is sitting at.

The result is a hostile fleet materializing next to the guest instead of at a polite distance. The fix
is a second geometry edit inside a forked vanilla placement path, and the fork subtree is already the
most expensive thing in the mod to keep in step with an engine update, so v1 accepts the pop-in.

### The colony editor is claimed whole, because no API says which colony it is editing (colony-3)

The colony screen reached from the command tab (`CoreUITabId.OUTPOSTS`) docks nothing and fires no
market callback, so `CoopInteractionGate` — which keys claims on the entity a dialog opened — had
nothing to key on, and both players could edit the same colony at once. There is no engine call that
reports which colony that tab currently shows, and none that closes the core UI, so the claim is taken
for the synthetic entity id `coop:colony-management`: while either player has the tab open, the other
sees "Remote player is interacting: colony management" and is bounced to the INTEL tab.

Two consequences to expect in play: the lockout is global, so the second player cannot edit a
*different* colony either, and the bounce is a tab switch rather than a closed screen.


## Phase 32 — Shared Submarkets and Storage: Accepted Divergences

Storage, the black market and the military submarket became host-canonical on 2026-09-05, on the same snapshot-on-open and guest-delta path the open market had used since Phase 12. What follows is what the two engines still do differently, and what a player sees when they hit it.

### One unlock, two monthly fees

Either player's 5000 credits opens a market's storage for both, and after that both engines bill their own monthly storage fee against the same contents. The fee is charged locally by each engine's economy tick, and the alternative, billing one player for a locker both use, was worse. It is the price of one unlock for two players.

### Shop listings diverge between opens

Ship and weapon stock on the open, black and military submarkets is rolled from unseeded item RNG on each engine, so the two disagree until someone docks. Snapshot-on-open is the whole convergence mechanism: the host answers the dock with one snapshot per shared submarket and the partner's shelf is replaced with the host's. Between opens they drift again, and the 30-day save-time reroll described under `OpenMarketPlugin.writeReplace` above adds to it. Docking fixes it.

### The guest holds the host's commission, and none of what it pays

The host's commission faction is mirrored to the guest as the `$fcm_faction` memory key, which is the one thing the military submarket reads to decide whether a commission-gated item is buyable. That is the whole of it: the salary, the commission bounties and the `FactionCommissionIntel` entry stay on the host, because instantiating the intel on the guest would run a second salary and a second termination. The guest also cannot sign or resign a commission of its own, and that is enforced rather than accepted: `cmsn_askForCommissionOpt` and `cmsn_resignCommissionOpt` are replaced in `rules.csv` with `!$global.coopIsGuest` appended, the same mechanism that removes the Galatia chain. Resign is gated because the mirrored key otherwise makes vanilla offer the guest the chance to resign the host's commission.

### A locker too big to send is not sent

There is no chunking on `MARKET_SNAPSHOT`. A submarket whose encoded stock passes the 1 MB frame cap is skipped, and the host player gets a feed line naming the submarket, the market and the size in KB; the partner keeps whatever it had until the locker shrinks. A warning fires at 256 KB, well before the cap. It takes thousands of hulls to get there, and the remedy is to take ships out of that locker.

### A stored hull the host cannot capture in full is listed degraded

The partner sees a pristine hull of the right variant with the right CR and hull damage; the D-mods, s-mods, weapons and weapon groups are missing on their side only. The alternative was omitting the hull from the listing, which reads to the other player as a ship that was stolen. The log line is `Coop stored hull member=... could not be captured in full`, and the depositor's own copy is untouched.

### A stored ship the receiver cannot rebuild comes back as its base variant

The same failure at the other end: the ship is added on the base variant with the sender's CR and hull fraction, losing the refit, the D-mods and the custom name, rather than being dropped. A ship listing in a *shop* that fails the same rebuild is skipped instead, because a shop listing belongs to nobody and a wrong price is worse than a missing line.

### A withdrawal that matches nothing on the host is a no-op

If a guest withdraws a hull whose id the host cannot find, the host logs a warning and changes nothing. The guest's own view is corrected by the next snapshot, which is the next time it opens that storage.

### An s-modded built-in hull mod gains a `permaMods` entry

Built-in hull mods are not in `permaMods` on a stock variant; s-modding one puts it there on the rebuilt copy, so a capture, rebuild and re-capture cycle adds the entry. It is stable after one cycle and changes nothing in play, but a field-by-field diff of the same ship before and after a round trip shows it.

### Only a module hanging directly off the member carries its hull damage

Module variants recurse, module *damage* does not: the per-module hull fraction is read off the member's status index, which vanilla populates one level deep. A module of a module rebuilds undamaged. Vanilla nests one level, so this is reachable only with a mod that nests deeper.

### Module nesting is capped at four levels

Capture warns and stops at four; decoding a deeper blob throws. The cap exists so a mod that manages to make a module cycle costs a warning rather than the campaign thread.

### Officers do not travel with a stored ship

Vanilla removes the officer when the player stores a ship, so there is nothing to carry across and nothing is lost. The officer stays in the depositor's fleet.

### Weapon groups under a mod mismatch

When the receiving engine cannot resolve a weapon in a group, that slot is dropped from the group. A group that ends up empty stays as an empty placeholder so the surviving groups keep their numbers, and if no group survives at all the receiver autogenerates them the way the refit screen does. A warning names the member.

### A market whose only shared submarket is a locked locker opens on the timeout

The guest's sync gate arms on the dock, the host finds nothing shareable to snapshot, and the trade options open 5 seconds later on the timeout instead of on the reply. Same for a hidden base the guest has paired but the host has since lost. It looks like a slow dock and nothing else.

### The host materialises an empty locker for every unlocked market the guest opens

Capturing storage calls `getCargo()`, which creates the submarket's cargo object when it does not exist. On the host that means an unlocked market the host has never used gets an empty locker object. It rolls nothing, costs nothing and is invisible in play.

### Hidden-base markets are shared, with two residues

A pirate or Luddic Path base's market is paired across the two engines by id now, so its stock and transactions are shared. The base's **name and orbit still differ per engine**, because each engine mints those itself. And a base carries only `open_market` and `black_market`, no storage and no military, so the storage half of the shared-submarket code never runs there.

### Storage-unlock flags outlive the market only in persistent data

The host resolves every market id before sending its unlock baseline, so a destroyed base or an abandoned colony is pruned out of what the peer receives. The `coop.storageUnlocked:<marketId>` key itself stays in sector persistent data, deliberately: a market rebuilt at the same id keeps its unlock instead of charging 5000 credits a second time.

### Credits are a float, and a delivered grant is delivered

The engine wallet is a `float`, so above 2^24 credits a small transfer can land a credit or two off. That is vanilla arithmetic, not the wire. Two consequences of the transfer design itself: a grant already written to the OS socket counts as delivered, so a receiving process that dies before applying it loses the money the way it loses any other unsaved state; and a grant that never reaches the socket (queue cap, session end, shutdown) is refunded to the sender with a feed line. Gifts are counted nowhere in the session stats.

### Two notes for the runbooks

Ship ids in the Phase 30 bridge market dump are origin-namespaced (`c_<playerId>_<memberId>`), so a grep for a bare member id finds nothing. And `SNAPSHOT_WARN_BYTES`/`SNAPSHOT_MAX_BYTES` in `CoopCampaignReplicator` mirror `CoopNetService`'s frame constants by value rather than by reference; changing the frame cap means changing both.
