# Starsector Runtime Limitations

What Starsector `0.98a-RC8` does and does not let a mod script do, and what the coop mod accepts as a result. Every entry states the engine mechanism and the consequence a player or tester sees. Findings that later got fixed are removed from here as the fix lands; the ledger in `COOP_MP_IMPLEMENTATION_PLAN_V1.md` keeps that history.

## Script sandbox

Mod scripts load through a guarded classloader. When the guard trips the UI shows:

```text
Fatal: File access and reflection are not allowed to scripts.
```

The class name that tripped it is in `starsector-core/starsector.log`.

### What the loader refuses

`com/fs/starfarer/loading/scripts/B.loadClass` (read off the shipped class, 2026-09-04) refuses every name that starts with `java.io`, `java.nio.file.File` or `java.lang.reflect`, minus an allow-list held in its constant pool:

- `java.io`: `BufferedInputStream`, `BufferedReader`, `FilterInputStream`, `InputStreamReader`, `Reader`, `Serializable`, `InvalidClassException`, `ObjectStreamException`, `InputStream`, `IOException`, `PrintStream`, `PrintWriter`, `ByteArrayInputStream`, `FilterOutputStream`, `OutputStream`, `Closeable`, `Flushable`, `StringReader`, `FileReader`.
- `java.nio.file`: `Path` and `Paths` pass (they do not start with `File`); `Files` and `FileSystems` do not.
- `java.lang.reflect`: `AnnotatedElement`, `InvocationTargetException`, `Type` and `GenericDeclaration` pass; everything else does not.

Types that have crashed the mod in practice: `ByteArrayOutputStream` (at object construction), `UncheckedIOException`, `StringWriter`, `EOFException`, `File`, `java.nio.file.Files`, and the `java.lang.reflect.Method` load inside Netty's `PlatformDependent0` initializer. `java.io.IOException` is on the allow-list and does not trip the guard.

### Project rules

These are a superset of the engine's list. The superset costs nothing and survives an engine update that trims the allow-list, but it is not an explanation for a crash; the list above is.

- No Netty or other reflection-heavy networking library in `mod_info.json`.
- No `java.io.*` in runtime campaign or network code. Plain arrays, strings and sandbox-proven JDK types instead.
- No `java.nio.file.*`, `java.net.URL.openStream()` or protection-domain jar inspection in handshake code.
- Field access on engine classes goes through `java.lang.invoke.MethodHandles`, never `java.lang.reflect`. Both compile and unit-test green; only the second one throws in-game.
- All networking is progressed from `EveryFrameScript.advance()` on the campaign thread. Mod-created daemon threads are not reliable: in two-client testing the `coop-net-*` threads disappeared while the game kept running and left the socket half-open.
- `CoopNetServiceSandboxCompatibilityTest` and `CoopHandshakeSandboxCompatibilityTest` pin the rules; keep them aligned with any change here.

### `SettingsAPI.loadText` works from inside the sandbox

`SettingsAPI.loadText(String, String modId)` is the engine's own text loader and is usable from campaign scripts (verified on both clients 2026-08-17). `CoopHandshakeManifest` hashes each enabled mod's `mod_info.json` through it with `CoopChecksum.sha256Text`, line endings normalized so a CRLF/LF checkout difference does not read as a mismatch. Two conditions are pinned by `CoopHandshakeSandboxCompatibilityTest`:

- The call catches `Throwable`, not a named checked exception, so a blocked `java.io` type can never be named in the calling class.
- A per-mod failure degrades to that mod's `UNAVAILABLE:script-sandbox` entry instead of throwing out of `capture()`, so one unreadable third-party mod cannot kill the handshake.

Jar checksums stay `UNAVAILABLE` permanently: no engine surface hands back jar bytes and the sandbox forbids opening them. The git-commit comparison and the Phase 6 sector fingerprint are the primary guards against a skewed install; the `mod_info.json` hashes are corroborating detail in the handshake diff.

## Classpath forks (`coop-forks.jar`)

Source forks under `data/scripts/com/fs/starfarer/api/impl/...` are not reliable for API classes that are already loaded. Forked engine classes that must override `starfarer.api.jar` are compiled into `jars/coop-forks.jar`, which the host and guest launch scripts prepend to the JVM `-classpath`.

- Forked classes load in the JVM system classloader; `coop.jar` loads in Starsector's child mod classloader. A fork cannot see child-loader classes or objects, so any helper a fork uses must also be built into `coop-forks.jar` and excluded from `coop.jar`. Two helpers live there: `coop.rng.CoopRandom` (reads only the JVM `coop.newGameSeed` property, no mod runtime state) and `coop.presence.CoopPresenceRegistry` (the guest-presence slot). Their source sits under `src/main/java/coop/rng` and `coop/presence`; `build.gradle` compiles those directories into the `forks` source set and the `jar` task excludes them from `coop.jar`, along with the generated `CoopForksBuildInfo` stamp that identifies the forks jar in the handshake.
- `CoopPresenceRegistry` also owns the pinned-version guard (`PINNED_VERSION` + `getForFork(String)`): one constant to change on a Starsector version bump, one verdict logged per process, and on a mismatch every presence term goes silent and the forks behave as stock.
- Every presence edit is additive, guarded on `presence != null`, and adds no instance field, because the forked managers are save-serialised `EveryFrameScript`s.
- Re-fork procedure after an engine update: copy the new vanilla source over the fork byte-identically, diff to confirm, then re-apply only the `COOP FORK`-tagged hunks listed in the file's header banner. Whitespace-only blank-line hunks in that diff are editor normalisation.
- New forks go under `forks/`, never under `data/scripts/`.

RNG forks: `Misc` (seeds `Misc.random` from `CoopRandom.ofOrDefault("Misc.random")`, replaces `genRandomSeed()`'s `System.nanoTime()` entropy with the session seed while keeping vanilla's `seedUniquifier()` counter), `GateHaulerLocation` and `NamelessRock` (swap `StarSystemGenerator.random` for an independent topic-keyed stream for the duration of `generate()`), `AbyssalRogueStellarObjectEPEC` (reseeds `data.random` by encounter id and rounded hyperspace coordinates; abyss parity only, not part of the fingerprint). Presence forks: `RouteManager`, `PlayerVisibleFleetManager`, `DisposableFleetManager`, `SourceBasedFleetManager`, `DisposableHostileActivityFleetManager`, `DisposableThreatFleetManager`.

The `Misc` fork binds its field and logs its `[COOP-FORK] Misc fork active...` probe during core data loading, about ten seconds before any mod plugin exists, so the seeding only lands in time on the `-Dcoop.newGameSeed` path. The Phase 31 launcher's seed lives in `saves/common/coop_options.json.data` and becomes a system property only when `CoopModPlugin.onApplicationLoad` republishes it; on that path the probe legitimately reads `coopSession=false` and `CoopModPlugin.rebindTheForkedSharedRandom` writes the field afterwards. The line that proves the seed took is `Coop reseeded the forked Misc.random`, not the probe.

## Seed lock and sector fingerprint

- The vanilla new-game seed field is not enough for automated two-client runs. `coop.seed.CoopSectorProcGen`, registered through `data/config/settings.json`, applies `coop.newGameSeed` to `CharacterCreationData` (`setSeed(long)` + `setSeedString(String)`, via `CoopSeedSync`) at the top of both `prepare()` and `generate()`, and forces `sector.setSeedString` after generation so the saved sector carries the coop seed string.
- Derived seed longs must be positive: `SectorProcGen.prepare()` only seeds `StarSystemGenerator.random` when `data.getSeed() > 0`, so the SHA-256-derived long is masked with `Long.MAX_VALUE`.
- `CoopSectorFingerprint` is a session-start tripwire, not a world-equality proof. It covers system id, market id, market size, faction id and rounded hyperspace anchor coordinates, and excludes hidden markets (`MarketAPI.isHidden()`: pirate and Pather bases with engine-minted ids, replicated host-authoritatively instead). The verified matching fingerprint on a fresh two-client game had 272 entries.
- On a mismatch both sides log the full canonical fingerprint text (one line per entry, the exact SHA input), so the diverged entry is found by diffing the two logs. There is no diff protocol on purpose: the canonical text is about 11 KB.

**Mutability contract.** `marketSize` and `factionId` are mutable campaign state and are in the fingerprint deliberately: it is re-validated on every session start, including loaded-save reconnects, so those fields prove both saves evolved identically. Any feature that mutates market size, faction ownership or market existence must ship with replication of that mutation to the guest's save, or the next reconnect hard-rejects with no heal path. Player colonization (Phase 24) and decivilization (`WORLD_DELTA(DECIV)`) are the two vectors so far. The fix for a tripped fingerprint is always to add the missing replication, never to relax the check; splitting structural and mutable fingerprints was considered in Phase 6b and rejected for that reason. Related: seeds are gen-time only, runtime RNG is replicated and never re-seeded (per-script audit in plan Phase 13).

## Save serialization

Persistent campaign scripts are serialized into saves by XStream. A persistent `CoopNetPump` made XStream walk into runtime networking state (`AtomicReference` among others) and every new game failed with:

```text
Error creating new game:
Error saving game.
No converter available
```

Rules: install `CoopNetPump` with `SectorAPI.addTransientScript()`, remove both persistent and transient old pump instances before installing a fresh one, and never store sockets, channels, threads, queues or other transport objects in saved campaign state.

## Transport

- Host: non-blocking `ServerSocketChannel`. Guest: non-blocking `SocketChannel`. Both sides: one `DatagramChannel` for the UDP path, deliberately not `connect()`ed so an ICMP error does not poison the channel. No `Selector`, no worker thread.
- Frames are UTF-8 JSON, capped at `CoopNetService.MAX_FRAME_BYTES` (1 MB). `CoopCampaignReplicator.SNAPSHOT_WARN_BYTES` (256 KB) and `SNAPSHOT_MAX_BYTES` mirror that cap by value, not by reference; changing the frame cap means changing both.
- Guest connect attempts retry, because host and guest campaign loads are not ordered.

### The pump does not run during combat or while a save is written

`CoopNetPump` is an `EveryFrameScript` on the campaign engine. Combat runs on a different screen and `saveGame` blocks the thread that would tick the pump, so during either one nothing is read or sent. A save is a few seconds on a fast machine and past 15 s on a slow one with a large sector, which is the link-death threshold.

Link death is therefore declared on inbound TCP silence (`CoopLinkQuality.DEAD_TCP_SILENCE_MILLIS`, 15 s), with three exemptions evaluated in `CoopLinkQuality.evaluateLinkDeath` and acted on by `CoopNetPump.maybeDeclareLinkDead` on the 1 s supervision cadence: the peer is in a battle (the battle bridge's `isRemoteBattleActive`, cleared after `REMOTE_BATTLE_SILENCE_TIMEOUT_MILLIS` = 30 s of silence so a mid-combat drop cannot leave a phantom, or the shared combat pause intent), a save checkpoint passed within `DEATH_SAVE_EXEMPT_MILLIS` (60 s), or this process itself stalled (a frame gap over `LOCAL_STALL_FRAME_GAP_MILLIS` = 5 s, with the exemption held for a further 15 s after the stall ends). The peer's own save has no natural signal, since the saving side is the one that goes quiet, so both roles send a `STALL_NOTICE` from `beforeGameSave` and flush it before the block starts.

### A throttled game window runs its clock slow

Starsector caps per-frame `dt`. A minimized or background window gets fewer frames, so its campaign clock falls behind wall time, and from the other client's point of view the throttled side is behind. In the 2026-09-02 QA matrix the guest read up to 2.5 game-days ahead of a minimized host; the clock reconciler pulled it back to 0.01 game-days once both windows were visible. Only reachable with both games on one PC, which is what a two-instance test session is. Rule for testers: keep both windows restored and visible.

### Port mapping (UPnP IGD, NAT-PMP fallback)

- `CoopPortMapper` is a non-blocking NIO state machine driven one slice per campaign frame: no thread, no `HttpURLConnection` (that needs `java.io`). UPnP IGD first, NAT-PMP when SSDP finds nothing or UPnP refuses; PCP is documented-not-implemented. If the router's "external" address is private or in `100.64.0.0/10` the mapping is real but useless (CGNAT), and that verdict is reported as such.
- The lease is `LEASE_SECONDS` (3600), renewed every 30 min. Release runs from `CoopModPlugin.onGameLoad` through `CoopNetPump.shutdownPortMapper()`, because that is the only teardown hook the engine gives a mod (no quit-to-menu or exit callback), and `CoopPortMapper.shutdown()` drives the release as a bounded busy loop (1.2 s budget) since there is no thread to wait on. A process that exits without reaching it leaves the mapping to expire with the lease. A router that rejects timed leases (`UPnPError 725`) gets a permanent mapping instead, which the shutdown path still deletes; a crash leaves that one open until the next launch.
- A gateway response over `MAX_RESPONSE_BYTES` (256 KB) is abandoned and the exchange settles as failed. Real device descriptors and SOAP replies are a few kilobytes; the cap stops a streaming or hostile device from growing a buffer on the campaign thread. The log names the limit that was hit.

## Campaign time

### Control enum-constant names

`InputEventAPI.isControlActivated/isControlDownEvent/isControlUpEvent(String)` resolve the argument with `Enum.valueOf` on the obfuscated control enum (`com.fs.starfarer.title.<obf>$oo` in `starsector-core/starfarer_obf.jar`). An unknown name throws `IllegalArgumentException: No enum constant ...` and, uncaught, becomes a Fatal dialog back to the title screen.

- Campaign pause is `GENERAL_PAUSE`, not `PAUSE`. Passing `"PAUSE"` crashed the client.
- Campaign fast-forward is `FAST_FORWARD`; combat slow-mo is `GO_SLOW`.
- To list them: extract the `$oo` class from `starfarer_obf.jar` and run `javap -v <class> | grep "= Utf8"` (constants like `CORE_*`, `CMENU_*`, `C2_*`).
- `CoopCampaignInputBlocker` wraps the lookups in `try/catch (IllegalArgumentException)`, but the unit tests mock `InputEventAPI` with arbitrary strings, so a wrong name passes tests and crashes the real engine. Tests must assert against the real constant names.

### Fast-forward

Vanilla has two fast-forward input modes. Which one is active is a static boolean on `com.fs.starfarer.settings.StarfarerSettings` (settings-menu checkbox `Campaign "speed up time" is a toggle`), stored in a private static field literally named `class`, getter `Oo0000()Z`. The field is resolved with `findStaticGetter`/`findStaticSetter` on a `privateLookupIn` of that class, never by setter name: the setter is `ö00000(Z)V` (U+00F6) and four other static `(boolean)` setters on the class print identically under `javap`. Dumps are in `K:\Starsector\tmp_ff_analysis\`.

**Hold mode** (vanilla default) cannot be mirrored or blocked from a mod. The loop lives in obfuscated `CampaignState.advance(float, ...)`, polls the Shift key directly and calls `CampaignEngine.advance()` twice per frame. Consuming the `FAST_FORWARD` input event does not stop it. `SectorAPI.isInFastAdvance()` and `isFastForwardIteration()` both read `false` from an `EveryFrameScript` while it runs, `setFastForwardIteration` is overwritten every frame, and `setInFastAdvance(true)` sticks but does not change the clock rate (it drives a separate extra `CampaignClock.advance()`, the ">>" path). The only public read is `CampaignUIAPI.isFastForward()`.

**Toggle mode** skips the per-frame key poll. The persistent private field `CampaignState.fastForward` is flipped only by a consumable `FAST_FORWARD` key event, so the guest's pre-core event consumption blocks it and a `MethodHandles` write sticks. The speed loop is `iters = fastForward ? Math.round(getFloat("campaignSpeedupMult")) : 1` with a live `getFloat` per frame, so the multiplier is settable at runtime through public `SettingsAPI.setFloat`.

What the mod does (`coop.time.CoopFastForwardLock`): forces toggle mode and `campaignSpeedupMult = SESSION_MULT` (2) on both roles for the life of a session, mirrors the host's `fastForward` field onto the guest from `CoopTimeLock.apply`, and restores the player's own toggle preference at session end. If the handles fail to resolve, or `-Dcoop.ff.disable=true` is set, the lock goes sticky-unavailable and the session runs at a runtime 1x lock instead.

Two caveats on the forced toggle flag, which is process-wide and set false by the class initializer:

1. The restore runs from the pump's per-frame `syncFastForwardLock` on the first campaign frame with the session inactive, so it needs the pump to tick after the session ends. Leaving the campaign mid-session (exit to menu, load another save) leaves toggle mode on until the next coop session ends or the game restarts. Hold-Shift then acts as a tap toggle. Harmless; recognise a "my Shift became a toggle" report.
2. If the player opens the vanilla settings menu during a session and applies, vanilla writes the forced-true value to its settings file, and the mod cannot tell.

`CoopNpcThreatWatcher.handoffMargin` takes the campaign speed multiplier (read per scan off `CampaignUIAPI.isFastForward()`, clamped at 1 so it can only widen the band): at 2x a chaser covers twice the distance inside the same RTT budget, and a margin sized for 1x fired the pre-contact handoff after contact. The `p95 <= 0` loopback case keeps the flat `CONTACT_MARGIN_SU`, which covers measurement noise, not travel.

### The campaign clock

`CampaignClockAPI.getCal()` is public and returns the `private transient GregorianCalendar cal` that `com.fs.starfarer.campaign.CampaignClock` (`DoNotObfuscate`) uses as its source of truth, so the clock is writable with `cal.setTimeInMillis(...)`. The cached `private long timestamp` is the only persisted representation and needs a `MethodHandles` setter; both are written together, `cal` first (javap dump: `K:\Starsector\tmp_ff_analysis\CampaignClock.javap.txt`). `createClock(long)` makes a detached clock that cannot be installed, and `SectorAPI` has no `setClock`.

`CampaignClock.advance(float)` int-truncates calendar-seconds per frame, so two clocks drift apart structurally even at a shared rate. `coop.time.CoopClockReconciler` (guest-only) corrects accumulated in-session drift with a bounded monotonic slew and forward-only snaps; `-Dcoop.clock.disable=true` or any handle failure falls back to uncorrected drift with one logged warning. Writing the clock does not move an on-screen orbit: every orbit class integrates a private `currAngle` from the frame dt and never reads the clock's absolute value. The 1 Hz orbit snap owns that.

The reconciler does not close the connect-time gap and does not do late-join catch-up. That gap is prevented instead: `CoopNetPump.maybeHoldPausedUntilSessionReady()` holds the local clock paused on both roles from the moment the client takes a coop role until the session is playable (peer connected, handshake validated, seed lock done), evaluated every frame with no timeout, so no time passes during connect, handshake and seed lock. The guest skips the hold while its own interaction dialog is open (forcing `setPaused` under a dialog freezes the trade-tab exit). Afterwards the host's pause state mirrors to the guest (residual offset = network latency). `setPaused`/`isPaused` is read by the engine every frame, so the guest pause lock is a real lever.

## Title screen, new game, rejoin

### The "New Game" button cannot be renamed

The label is a string constant inside the obfuscated title-screen class (`com.fs.starfarer.title.C`). It is not in `data/strings` or `settings.json`, and the title screen is built before `onApplicationLoad` returns, so no mod hook reaches it. The coop cue lives on the new-game dialog's Continue option instead (`coop.newgame.CoopNewGameDialogPlugin`, registered through the `newGameDialogPlugin` key the mod's `settings.json` already owns for procgen).

### The new-game options panel is one atomic widget

`VisualPanelAPI.showNewGameOptionsPanel(data)` is the only entry point for name, portrait, gender, seed field, sector size and star age. There is no per-field enable/disable, and the panel writes seed, `sectorSize` and `sectorAge` back onto `CharacterCreationData` whenever its state changes. The plugin pins the coop values on `init`, on every `advance` frame, and once more on Continue; procgen reads the data object last, so the last write wins.

### Do not re-show the text panel next to the options panel

`NewGameDialogPluginImpl.init` ends with `dialog.hideTextPanel()`. Calling `showTextPanel()` after it reserves the left column, pushes the options panel right of center, and the added paragraph still does not render. Tried and reverted 2026-09-02.

### Player-faction fleet names carry the faction article

Vanilla renders every fleet as `<faction display name with article> <fleet name>`, and `data/world/factions/player.faction` sets `displayNameWithArticle` to `Your`. A player-faction fleet named `Alice` shows as "Your Alice"; once colonies name the faction it becomes "<Faction> Alice". The partner's mirror fleet is named `partner <Name>` so both prefixes read as a sentence. Any future player-faction fleet the mod names needs a noun phrase, not a bare name.

### Guest rejoin is by loading the coordinated autosave

A guest that quit mid-session rejoins by loading the save that the Phase 16 coordinated autosave wrote, whose stored campaign id matches the host's. A New Game on the same seed is rejected at seed lock ("this campaign is already in flight and this guest campaign is brand new"); only `launch-guest.ps1 -AdoptCampaign` (`-Dcoop.adoptCampaignId=true`) forces it through, at the cost of the guest's progress.

Which save folder holds which campaign is not derivable from the engine's slot list: folders are named `save_<character>_<random>` and `descriptor.xml` carries no campaign id. `CoopSaveIndex` writes one row per save to `saves/common/coop_saves.json.data` from `afterGameSave`, keyed by the sector-persistent `coop.campaignId`, and the launcher reads it. Two engine facts behind it: `SectorAPI` has no `getSaveDirName()` (it is on `CampaignEngine`, `DoNotObfuscate`, reached through `MethodHandles.privateLookupIn`), and the engine swaps `saveDirName` to a fresh folder for the duration of an autosave or save-as, restoring it after the routine returns, so the value has to be read inside the hook every time and never cached. When the handle cannot be resolved the row is written without a folder name and the launcher falls back to matching `characterName` plus `gameDateTimestamp` against each `descriptor.xml`. Manual fallback: grep the host campaign id from the reject line across `saves/save_*/campaign.xml`.

## Runtime randomness: accepted divergences

A shared seed only guarantees identical draw sequences while both clients execute the same code in lockstep, and lockstep ends the moment the campaign runs: the guest sim is suppressed, frame timing differs, and call order diverges after the first runtime draw. Every runtime-random site gets one of three treatments: replicate the outcome, suppress the generator, or accept the divergence. The accepted cases follow. The ownership argument behind most of them: own fleets are owner-authoritative and NPC mirrors are position-forced echoes, so an effect that only touches the fleet it hits never touches shared state.

### Hyperspace storm cells

`HyperspaceTerrainPlugin` + `HyperspaceAutomaton`: cell evolution is a deterministic automaton, but generation reseeds use `new Random()` (`HyperspaceAutomaton.java:150`) and strike timing and damage use `Math.random()` (`HyperspaceTerrainPlugin.java:1472,1485`). It is a terrain plugin, not a script, so the script suppressor has no hook into it. Each player sees their own weather, and a strike only hits the fleet inside its cell. Not purely cosmetic: a strike grants a 1.25 s burn burst (`HyperStormBoost`) and CR damage, so two fleets travelling together get different boosts and hits.

Planned fix (Phase 26 milestone 4): the plugin is registered by class name in `data/campaign/terrain.json`, `auto`/`activeCells`/`tiles` are `protected`, and `advance`/`readResolve` are overridable, so a mod-side subclass can replace it without a classpath fork. The committed generation (`cells`) changes only at the 1.5 to 2.5 day interval boundary and already has a deflate codec (`encodeTiles`), so it replicates as one capture per generation. `CellStateTracker` instances exist only within 10000 su of the local player and take their durations from `Math.random()` at creation, so they get keyed off `(generation, i, j)` with elapsed catch-up rather than shipped.

### Star-corona and pulsar flares

`FlareManager`, `new Random()` near line 307. A flare only affects the fleet it hits. Accepted.

### Slipstream networks

`SlipstreamManager.random` (`SlipstreamManager.java:442`) is `new Random()`, minted per client when the manager is constructed and serialized into each save; `random = Misc.random` happens only under `DebugFlags.SLIPSTREAM_DEBUG`. Monthly layout draws fire from an `IntervalUtil(1f, 2f)` with a random phase, so the number and timing of draws differ per client even from identical RNG state. A per-month-reseed fork was rejected: outcomes would still depend on per-client `addStream` call counts, and removing that dependence means restructuring gameplay logic, which the fork rules forbid.

In play: fleet positions never desync (fleets are owner-authoritative), but players see different slipstream maps, one fleet can appear to burn impossibly fast through empty hyperspace, and travel opportunities differ. Planned fix (Phase 26 milestone 1): suppress the guest's `SlipstreamManager` through the same `removeScript`/`addScript`-at-`onGameLoad` mechanism the base managers use and replicate the host's finished stream polylines, not the placement parameters.

### Abyss partial parity

Encounter-point placement comes from each client's own unseeded `HyperspaceAbyssPluginImpl.random` (line 59), so the EP sets differ by construction. The `AbyssalRogueStellarObjectEPEC` fork makes generated systems deterministic per encounter-point id once an EP exists, but placement is not forkable. With the guest's `EncounterManager` suppressed (unseeded `new Random()`, `EncounterManager.java:66`), abyssal temporary star systems exist host-side only: guests can travel the abyss, but rogue stellar objects, lights and Threat encounters are host-experienced. Planned fix (Phase 26 milestone 2): replicate each encounter's outcome and let the forked EPEC regenerate identical content guest-side.

### Sensor ghosts are suppressed, not left to diverge

`SensorGhostManager` seeds from `new Random(Misc.genRandomSeed())` (`SensorGhostManager.java:79`). Several ghost types spawn real encounters or fleets (EncounterTrickster, ShipGhost) or touch story state (Ziggurat and guide ghosts), so an independent guest-side roll risks story-state or NPC-authority conflicts. The manager is removed guest-side and the suppressor nulls the cached sector-memory handle. The guest never sees a ghost, host-originated or otherwise.

### Trade consequences run only on the engine that made the trade

Smuggling suspicion, the odds a patrol scans you, learning a blueprint from a sale, and the price impact of a large trade all run on the trading player's engine against that player's cargo and rep. Since Phase 32 the black market and military submarket contents are shared, but the consequences are not: two players at the same black market can read different suspicion strings. The cargo moves; the consequences do not.

## Markets and the bar

### Shop listings diverge between opens

Ship and weapon stock on the open, black and military submarkets is rolled from unseeded item RNG on each engine. Snapshot-on-open is the whole convergence mechanism: the host answers a dock with one `MARKET_SNAPSHOT` per shared submarket and the partner's shelf is replaced with the host's. Between opens the two drift again.

One extra source of drift: `OpenMarketPlugin.writeReplace` clears ship and weapon stock at serialization time when `okToUpdateShipsAndWeapons()` says the last roll is over 30 days old. That reads `sinceLastCargoUpdate`, a frame-dt accumulator on `BaseSubmarketPlugin`, not a campaign-clock timestamp, so clock reconciliation does not touch it; host and guest save at different moments with different accumulated play time, and a market neither has docked at recently can change across a save/load with no player action. The reroll seed is `getMonth() * 170000`, which reconciliation does keep aligned. Docking fixes both.

### Mercenary level is rolled before the snapshot

`OfficerManagerEvent.createOfficer` draws a mercenary's level with `Misc.random.nextInt(maxLevel + 1 - minLevel)` and the officer-vs-merc bump with `(float) Math.random() > 0.75f` (`OfficerManagerEvent.java:378,388`). Both clients roll independently. The host's pool rides the `MARKET_SNAPSHOT` as one stock line per person and the guest strips its own pool and rebuilds the host's through `OfficerManagerEvent.addAvailable`/`addAvailableAdmin`, so the divergence is visible only between the guest's market screen opening and the host's reply arriving, and only in the comm directory. Not worth a suppressor: `OfficerManagerEvent` also runs the timeout pruning that keeps stale offers from accumulating.

### Bar pool: what is replicated and what is not

Offer selection runs through a `WeightedRandomPicker` with a null `Random`, which falls back to `Math.random()`, so equal `BarEventManager.seed` values never produce equal offers. The host's `PortsideBarData` pool is therefore captured in order and pushed on change (`MISSION_POOL_SNAPSHOT`: each offer's id, class name, content seed and `shownAt` pin, plus the host's `BarEventManager` seed). The guest rebuilds the replicable part of its own pool from that list and has its `BarEventManager` script registration removed. Five things this does not give you:

**Offer numbers scale off the local fleet (user-accepted).** The wire carries the seed an offer regenerates from, not the numbers. `DeliveryBarEvent` and its siblings size quantity and payment against the local player's cargo capacity and the local market's supply price inside `regen(market)`, so two players see the same offer from the same person for the same commodity with different tonnage and credits. Pinning the numbers would mean capturing every derived field per offer type or forking each event class.

**Rumor offers stay locally generated and can shift the shown subset.** `PirateBaseRumorBarEvent` and `LuddicPathBaseBarEvent` hold a live `PirateBaseIntel`/`LuddicPathBaseIntel` reference that `shouldRemoveEvent()` reads, so the capture skips them and the guest makes its own from the replicated base intel. Both are `isAlwaysShow()`, so both players see their local one. The cost: `BarCMD.showOptions` runs `Collections.shuffle(events, random)` over the whole pool, and the permutation depends on list size and the random alone, so a rumor event at a different index on each client shifts every other offer's position and the two bars can show different picks from an identical pool. With no rumor events live, the normal early-campaign state, the picks match.

**Injected offers never expire on the guest.** `BarEventManager.advance` is what ages `active` and drops timed-out offers, and it is what the suppressor stops. Injected events are also kept out of `barEventCreators` because `advance`'s orphan sweep deletes anything there that is not in `active`. The host's next snapshot removes them instead: when an offer expires or is accepted host-side it leaves the host pool, the pool signature changes, and the guest's rebuild drops it. An offer the guest accepts stays on the host's copy until its own timer runs out.

**Acceptance is detected by disappearance, not by a listener.** The engine fires no "mission accepted" event; every acceptance path funnels through `BarEventManager.notifyWasInteractedWith(event)`, which removes the offer from `PortsideBarData`. `CoopBarAcceptanceWatcher` polls the local pool every 2 s and treats an offer that was present last poll and is gone now as accepted, raising the first-come claim (`MISSION_CLAIM_REQUEST`), with `BarEventManager.getCreatorFor(event)` on the retained reference as the discriminator between an acceptance and an expiry. Two readings it cannot make: accept-then-expire inside one poll interval reads as expiry, and a poll that lands after the manager's orphan sweep sees the offer gone with no creator and reads it as expiry. `CoopInteractionGate` claims are keyed by entity id, so two players at the same market already serialize on the market entity, bar screen included, and `visibleEntriesFor` keeps any recorded claim out of the injected set.

**Contacts, contact-board missions and person bounties stay per-player (user-accepted).** Only bar events ride the pool. Contact lists, `BaseMissionHub` missions and `PersonBountyManager` are untouched, and `PersonBountyManager` is one of the scripts the Phase 9 suppressor removes guest-side, so a guest has no person bounties in v1.

### System bounties are posted per engine (planned fix: Phase 34)

`SystemBountyManager` (`CoreLifecyclePluginImpl.java:722`, a `BaseEventManager` sector script) spawns no fleets, only `SystemBountyIntel` entries, so the Phase 13 spawner filter never caught it. Each engine posts its own system bounties from its own rolls, and `SystemBountyIntel.reportBattleOccurred` pays the local player from the local intel for local kills. The guest is paid by its own game for bounties the host never saw and sees none of the host's. Phase 34 replicates the host's set and suppresses the guest's manager; person bounties are the other half of that phase.

## Storage and shared submarkets

Storage, the black market and the military submarket became host-canonical on 2026-09-05, on the same snapshot-on-open and guest-delta path the open market uses.

### One unlock, two monthly fees

Either player's 5000 credits opens a market's storage for both, and after that each engine bills its own monthly storage fee against the same contents. The alternative, billing one player for a locker both use, was worse.

### The guest holds the host's commission and none of what it pays

The host's commission faction is mirrored to the guest as the `$fcm_faction` memory key, which is the one thing the military submarket reads to decide whether a commission-gated item is buyable. The salary, the commission bounties and the `FactionCommissionIntel` entry stay on the host, because instantiating the intel on the guest would run a second salary and a second termination. The guest cannot sign or resign a commission of its own: `cmsn_askForCommissionOpt` and `cmsn_resignCommissionOpt` are replaced in `rules.csv` with `!$global.coopIsGuest` appended (the mirrored key otherwise makes vanilla offer the guest the chance to resign the host's commission).

### Size limits

- There is no chunking on `MARKET_SNAPSHOT`. A submarket whose encoded stock passes the 1 MB frame cap is skipped; the host player gets a feed line naming the submarket, the market and the size in KB, and the partner keeps whatever it had until the locker shrinks. A warning fires at 256 KB. It takes thousands of hulls to get there.
- Module nesting is captured to a depth of four. Capture warns and stops there; decoding a deeper blob throws. The cap exists so a mod that makes a module cycle costs a warning rather than the campaign thread.

### Hull capture and rebuild residues

- A stored hull the host cannot capture in full is listed degraded: the partner sees a pristine hull of the right variant with the right CR and hull damage, missing the D-mods, s-mods, weapons and weapon groups on their side only. Log line: `Coop stored hull member=... could not be captured in full`. The depositor's own copy is untouched.
- A stored ship the receiver cannot rebuild comes back as its base variant with the sender's CR and hull fraction, losing the refit, D-mods and custom name. A *shop* listing that fails the same rebuild is skipped instead, because a wrong price is worse than a missing line.
- A withdrawal whose hull id the host cannot find is a no-op with a warning; the guest's view is corrected by the next snapshot.
- An s-modded built-in hull mod gains a `permaMods` entry on the rebuilt copy (built-ins are not in `permaMods` on a stock variant). Stable after one cycle, changes nothing in play, shows up in a field-by-field diff.
- Only a module hanging directly off the member carries its hull damage: the per-module hull fraction is read off the member's status index, which vanilla populates one level deep. Reachable only with a mod that nests modules deeper than vanilla.
- When the receiving engine cannot resolve a weapon in a group, that slot is dropped; an empty group stays as a placeholder so the surviving groups keep their numbers, and if no group survives the receiver autogenerates them the way the refit screen does. A warning names the member.
- Officers do not travel with a stored ship. Vanilla removes the officer when the player stores a ship, so nothing is lost; the officer stays in the depositor's fleet.

### Docking and materialisation details

- A market whose only shared submarket is a locked locker opens on the 5 s timeout rather than on the reply: the guest's sync gate arms on the dock and the host finds nothing to snapshot. Same for a hidden base the guest has paired but the host has since lost. Looks like a slow dock.
- Capturing storage calls `getCargo()`, which creates the submarket's cargo object when it does not exist, so on the host an unlocked market the host never used gets an empty locker object. Invisible in play.
- Hidden-base (pirate, Luddic Path) markets have engine-minted ids, so they are paired through a `hostMarketId <-> localMarketId` table (`CoopMarketIds`, written only by `CoopBaseAuthority`, identity for every other market); once paired, stock and transactions are shared, and guest traffic for a base not yet paired is held until it is. The base's name and orbit still differ per engine because each engine mints those itself, and a base carries only `open_market` and `black_market`, so the storage code never runs there.
- The host resolves every market id before sending its unlock baseline, so a destroyed base or abandoned colony is pruned from what the peer receives. The `coop.storageUnlocked:<marketId>` key stays in sector persistent data on purpose: a market rebuilt at the same id keeps its unlock instead of charging 5000 credits again.

### Credits

The engine wallet is a `float`, so above 2^24 credits a small transfer can land a credit or two off. That is vanilla arithmetic. On the transfer design: a grant already written to the OS socket counts as delivered, so a receiving process that dies before applying it loses the money the way it loses any other unsaved state; a grant that never reaches the socket (queue cap, session end, shutdown) is refunded to the sender with a feed line. Gifts are counted nowhere in the session stats.

### For the runbooks

Ship ids in the Phase 30 bridge market dump are origin-namespaced (`c_<playerId>_<memberId>`), so a grep for a bare member id finds nothing.

## Exploration and world state

### Gate scanning

The "Scan the Gate" option is gated by rules.csv on `$global.canScanGates` (`gateOpenDialogCanScan1`, `gateScanOpt`), a sector-memory flag only the host's Galatia questline sets. It rides the `GATE_ACTIVATED` payload alongside `$gatesActive` and `$playerCanUseGates`, and the poll that produces that payload runs on both roles, so a guest can scan and both players get the gate. Residues:

- The three globals repeat on every gate record, so a flip in any of them re-reports every gate in the sector on the next poll. A dozen-odd deltas, a handful of times per campaign; it is what makes each packet self-contained.
- `$numGatesScanned` is derived, not synced: whichever client applies a peer's scan calls vanilla's `GateEntityPlugin.addGateScanned()`. A client that never hears about a scan (gate scanned while it was disconnected, never re-reported) counts one low, which matters only as a rules condition inside the Galatia questline.
- A guest that rejoins from a save where gates were already scanned reports each of them upward once on its seeding poll. The host applies them as no-ops.

### Stable-location construction

`Objectives.build` creates the makeshift relay, buoy or array with `addCustomEntity(null, ...)`, so the engine mints its id per client; the same holds for the stable location `Objectives.salvage` puts back on disassembly. Both ride a `SPAWN` world-delta carrying a coop-assigned id, the spec, the faction, the orbit and the id of the stable location the build consumed.

- The consumed stable location is removed twice on the receiving client (once by the `SPAWN` apply, once by the `CONSUME` the originator's watcher emits), and the peer's own removal reports a `CONSUME` back. All three are idempotent and the ledger absorbs them.
- The orbit rides the wire rather than being copied off the consumed stable location, so the two deltas are order-independent. If the orbit focus does not resolve on the peer, the entity materializes at the fixed position that rode along and does not orbit.
- Only the entity is replicated, not the interaction. Build costs come out of the acting player's cargo, and the reputation hit for disassembling somebody else's objective is charged to the acting client alone.
- The Phase 6b world fingerprint covers markets only, so neither half of this moves it.

### Survey levels and ruins

`MarketAPI.SurveyLevel` and the `$ruinsExplored` market-memory flag replicate on the both-sides skeleton poll as `WORLD_DELTA(SURVEY)` and `WORLD_DELTA(RUINS_EXPLORED)`, apply is max-wins on the level's ordinal. What does not replicate is everything around the level.

**A system can be remote-surveyed twice.** `RemoteSurveyAbility` latches its once-per-system flag into the star system's own memory (`$core_didRemoteSurveyInSystem`, `RemoteSurveyAbility.java:21,104`) and `findBestPlanet` refuses to run again while the key is present (line 131). The key is not on the wire, so each player gets one sweep per system. Left that way on purpose: the ability pins fleetwide max burn to zero while it charges (line 89), and the planet it would pick is already PRELIMINARY from the first player's sweep, so replicating the flag would only take the ability away from whoever activates second. The acting player keeps the `RemoteSurveyDataForPlanetIntel` entry (line 101); the other gets the level without it.

**Survey data goes to whoever ran the survey.** Completing a planet survey puts one `survey_data_1` through `survey_data_5` unit in the surveying fleet's cargo (`SurveyPluginImpl.getSurveyDataType`, lines 157 to 183). The peer's planet reaches FULL through the delta, and a FULL planet is not offered the survey option again, so the peer never collects a unit. Same rule as salvage: one player loots, the world state is shared.

**A guest's survey mission pays out when the host surveys.** `SurveyPlanetMissionIntel.advanceMission` polls the target every frame and calls `reportPlayerSurveyedPlanet` the moment its market reads FULL (`SurveyPlanetMissionIntel.java:141-143`), without asking who surveyed it. Two players holding the same contract from different bar offers both get paid for one survey. The same already happens for a mission whose target the host decivilizes or whose objective the host captures.

**Either player entering a system reveals its planets on both maps.** `CoreScript.markSystemAsEntered` bumps every planet in a newly entered system from NONE to SEEN, and the poll replicates SEEN like any other level (the system-map display reads the minimum system survey level, and filtering SEEN out would leave the two maps visibly different). One player's travels light up planet markers on the partner's map. Belongs in the "what's different in co-op" player doc.

### An orphan mirror cargo pod can outlive the pod it copies

Mirror pods are created with `setNeverExpire(true)` so the creating client is the only owner of the decay timer. When the original expires while nobody is in that location, nothing generates the `WORLD_DELTA(CONSUME)` that would remove the mirror, so the partner keeps a pod that no longer exists on the authoritative side and can still loot it. The alternative, each client running its own timer, deleted live pods out from under the player who dropped them.

### The guest's `PirateBaseManager` start date restarts on every guest load

`CoopNpcFleetSuppressor.removeSpawnerScripts` takes `PirateBaseManager` out of `sector.getScripts()`, so a guest save no longer carries it. On the next load `CoreLifecyclePluginImpl` sees `!sector.hasScript(PirateBaseManager.class)` and constructs a fresh one, whose constructor sets `start = clock.getTimestamp()` and overwrites the `$core_pirateBaseManager` handle `MANAGER_HANDLES` preserves as a data holder. `getDaysSinceStart()` then reads about 0 on the guest after a reload while the host's reads the real campaign age. It feeds `Tuning.getDaysSinceStart()` and a few locally constructed bar missions (`SurplusShipHull` cycles, `CustomProductionContract`), so those parameters differ between clients and reset on every guest load. The fix would be writing a private field on a vanilla manager after construction on every load, which is more surface than the drift is worth.

## NPC mirrors and patrol encounters

### Mirrors of inflated fleets carry d-mods but not the autofit loadout

When a player fleet comes near an NPC fleet the engine inflates it: `DefaultFleetInflater.inflate` autofits every ship onto a runtime variant named from the fleet id and member index, which exists in no other engine. The host therefore streams the stock variant the inflater autofit *from* (`setOriginalVariant`) plus the d-mod hullmod ids per member (`CoopShipMods`), and the guest clones the stock variant, applies the d-mods and runs `DModManager`'s damaged-hull swap. Weapon slots and fighter bays are not captured for NPC mirrors, so a scavenged loadout mirrors as the stock one. CR and hull fraction are replicated per member and battle outcomes are host-authoritative, so this is a sizing-up error only.

### Customs pursuit of the guest reads below vanilla

Vanilla's inspection pursuit of a dark fleet is hardcoded player-only, so `CoopNpcThreatWatcher` drives the patrol at the guest's mirror with a re-issued `INTERCEPT` assignment (`CUSTOMS_PURSUIT_ASSIGNMENT_DAYS`) on a 250 ms scan instead of the engine's per-tactical-interval `setMoveDestination` steering: coarser turn-in, no speed matching, no sensor bursts or hail posturing. All four scenarios pass (chase-from-detection, outrun and give up, transponder-on stand-down, catch-and-hail; verified 2026-08-19). Improving it means either shorter assignment slices at the vanilla tactical interval or a fork of `TacticalModule`'s inspection clauses.

### Synthesizing a patrol dialog against a mirror fleet

The plan's original "customs inspection" rules (`rules.csv:2749-2854`, keyed off `$doingCustomsInspection`) are dead: nothing sets that key, and the rulecmds they call (`CustomsInspectionGenerateResult` and siblings) are absent from the 0.98a API source. Two live paths replaced it, both `BeginFleetEncounter` rules on the patrol fleet's own memory, both ending in the `CargoScan` rulecmd:

- **Transponder-off stop**: `tOffPatrolBegin` (`rules.csv:3395`), conditions `CaresAboutTransponder`, `!$tOff_didAlready`, `!$isHostile`, `!$faction.c:allowsTransponderOffTrade`, `!$sourceMarket.mc:free_market`, `$isPatrol`, `$sawPlayerTransponderOff`. `CaresAboutTransponder` reduces to `memory.getBoolean("$cfai_makeAggressive_tOff")` and returns false early if `$patrolAllowTOff` is set. Continuation `tOffPatrolOpenComm` applies `AdjustRep $faction.id TRANSPONDER_OFF`, then `tOffCargoScan` runs `CargoScan`. This is the branch that carries the running-dark confrontation and its standing penalty.
- **Smuggling scan**: `cargoScanInitial` (`rules.csv:3551`), conditions `!$cargoScan_didAlready`, `!$isHostile`, `$pursuePlayer_smugglingScan`. `SmugglingScanScript` sets that flag through `Misc.setFlagWithReason(mem, MEMORY_KEY_PURSUE_PLAYER, "smugglingScan", ...)`. Fewest preconditions, so it is the fallback.

Memory scope matters. `RuleBasedInteractionDialogPluginImpl.updateMemory()` builds a map of named scopes rather than merging: `$foo` resolves against `LOCAL`, `$prefix.foo` against `memoryMap.get(prefix)`. `BeginFleetEncounter` fires before any person is active, so trigger conditions read the fleet's memory bare; the commander becomes the active person immediately before `OpenCommLink`, at which point `LOCAL` is the person's memory and `ENTITY` is the fleet's. A mirror with a null commander opens the encounter and then silently fails every comm-stage condition (`$entity.transponderOffConv` and friends) because `ENTITY` is not in the map. `CoopFleetMirror.ensureNpcFleet` calls `createEmptyFleet(factionId, label, true)`, and the third argument is `withCommander`.

Preconditions that break the path without throwing:

| Precondition | Consequence when unmet | Source |
| --- | --- | --- |
| `$sourceMarket` set to a real market id | `CargoScan` crashes | `CargoScan.java:103-110` dereferences `Misc.getSourceMarket(other).getMemory()` before null-checking |
| mirror commander non-null | all `OpenCommLink` conditions fail silently | `RuleBasedInteractionDialogPluginImpl.java:137-156` |
| mirror faction is not the player faction | option panel gets "Leave" only | `FleetInteractionDialogPluginImpl.java:2545-2549` (unless `$isSmuggler` is set) |
| mirror hostile state | `$isHostile` is recomputed on every `getMemory()` and cannot be pre-set | `CoreCampaignPluginImpl.updateEntityFacts` |
| `$sourceMarket` market lacks `free_market` | `!$sourceMarket.mc:free_market` fails on the transponder branch | `Conditions.FREE_PORT` |
| mirror has no `Tags.STATION` / `Tags.HAS_INTERACTION_DIALOG` / market | picker returns `RuleBasedInteractionDialogPluginImpl` instead of the fleet plugin, losing the encounter machinery | `CoreCampaignPluginImpl.pickInteractionDialogPlugin`, lines 86-111 |

`CargoScan` reads `Global.getSector().getPlayerFleet()` for the cargo it scans and judges legality against `other.getFaction()`, which against a local mirror is the wanted behaviour: the guest's own cargo, checked against the patrol's faction. Entry point: `CampaignUIAPI.showInteractionDialog(SectorEntityToken)` (the one-argument form) runs the plugin picker, which lands on `FleetInteractionDialogPluginImpl` for a `CampaignFleetAPI` target.

## Abilities

### A guest distress call retains the mirror fleet in the host save

When the guest activates `distress_call`, the host runs the vanilla plugin on the guest's mirror fleet (`CoopAbilityEffectApplier`). `DistressCallAbility.activate()` calls `addResponseScript`, which adds an anonymous `DelayedActionScript` (`DistressCallAbility.java:194,220`) holding an implicit reference to `DistressCallAbility.this`, which holds the mirror through `getFleet()`. Ten to twenty days later the script adds a route (`RouteManager.addRoute("dca_distress_call", ..., DistressCallAbility.this, data)`) whose `RouteData` holds the same plugin as its spawner. Both serialize into the host save.

The route half is cleanable through public API (`getRoutesForSource`, `RouteData.getSpawner()`, `removeRoute`) but usually does not exist yet when the mirror is torn down at session end. The script half is not removable: telling a guest-spawned `DelayedActionScript` from the host's own needs the anonymous class's captured outer reference, which needs `java.lang.reflect`; matching on the synthetic class name (`DistressCallAbility$2`) would cancel the host player's pending responses too.

Accepted because the reference is inert: neither `DelayedActionScript.doAction` nor `DistressCallAbility.spawnFleet` calls `getFleet()`; both read `Global.getSector().getPlayerFleet()` and the route's `custom` payload (lines 203, 324). The cost is a dead `CampaignFleetAPI` in the host save graph until the script fires and its route expires. Second consequence: because `spawnFleet` positions the response relative to `getPlayerFleet()`, a guest-triggered distress response arrives near the host. The jump points it routes through are the guest's (captured in `DistressResponseData` at activation), so it reaches the right system; only the hyperspace approach is anchored wrong.

### Interdiction pulse: the standing hit is charged by the guest's own pulse

On the guest the pulsing fleet is the player fleet, so `InterdictionPulseAbility.applyEffect` runs its `INTERDICTED` adjustment locally at pulse time and `onPlayerReputationChange` forwards it to the host as a `GUEST_REP_DELTA`. On the host, `CoopAbilityEffectApplier` runs the vanilla pulse on the mirror for the victims' benefit, and vanilla's own `INTERDICTED` charge is gated on `fleet.isPlayerFleet()` (`InterdictionPulseAbility.java:293`), which the mirror is not, so it charges nothing; the mod does not add a charge of its own (doing so charged every victim twice). The radius and duration the host uses come from the mirror's pinned `sensorRangeMod` aggregates (`CoopSensorSync.Profile`), so they match the guest's real values.

The residue is an undercharge: the guest's pulse only sees fleets the guest's client knows about, so a fleet inside the host's pulse radius that the guest never mirrored costs the guest nothing, and the victim set is gated by the guest's transponder and detection state rather than the host's. Undercharging by a fleet the guest could not see beats double-charging every fleet it could.

## Colonies

### The guest has no hostile-activity meter

`HostileActivityManager` is in the guest suppression set, and the session-start pass ends any `HostileActivityEventIntel` the save holds, removes it and unsets `$hae_ref`. Every reader of `HostileActivityEventIntel.get()` in the pristine source tolerates null. The guest therefore has no meter and no `HOSTILE_ACTIVITY` colony condition; the mirrored `CoopExpeditionWarningIntel` entry, scanned off the host's live intel manager and reconciled as a set, is its only inbound-attack signal. The host is untouched.

### Construction progress drifts until an industry finishes

`COLONY_MGMT` replicates the construction queue, not build progress. Vanilla's build button only appends to `market.getConstructionQueue()` and each engine's own `Market.advance` drains it through `BaseIndustry.buildNextInQueue`, so the two engines start the same build at slightly different moments and run their own timers. Reading true progress across the wire is not cheap: `Industry.getBuildOrUpgradeProgress()` reads `0` whenever the industry is disrupted (`BaseIndustry.java:491-498`), the absolute-days field needs a `BaseIndustry` cast, and `getBuildTime()` returns the spec value rather than the field during an upgrade.

It self-heals with a bound: the first client to finish reports the industry finished and the applier forces the lagging mirror to `finishBuildingOrUpgrading()`. The drift is never larger than the gap between the two starts. The client that finishes second may see its "construction complete" message a moment early.

### Commodity fulfillment and shortage markers can differ between clients

Observed live 2026-09-01: the same shared colony showed different demand-met and deficit markers on the two clients. The colony is in sync; the economy around it is not. Each engine runs its own `EconomyAPI` and solves supply for every market in the sector on its own iteration schedule, so fulfillment is a derived value. NPC market stockpiles and production are each engine's own simulation, so the inputs differ. `COLONY_MGMT` replicates industries and the queue, Phase 12 replicates market contents on open; neither replicates the sector-wide supply solve. Same root as the income drift seen that session (host 1456 vs guest 1663 in a month where the two colonies were not yet producing the same thing; the next month matched exactly).

The host is canonical. Worth revisiting only if a shortage sticks on one side long enough to feed the stability penalty, which would make the two colonies grow apart in a way the industry/queue channel would not catch.

### The colony editor is claimed whole

The colony screen reached from the command tab (`CoreUITabId.OUTPOSTS`) docks nothing and fires no market callback, so `CoopInteractionGate`, which keys claims on the entity a dialog opened, had nothing to key on. No engine call reports which colony that tab shows and none closes the core UI, so the claim is taken for the synthetic entity id `coop:colony-management`: while either player has the tab open, the other sees "Remote player is interacting: colony management" and is bounced to the INTEL tab. The lockout is global (the second player cannot edit a different colony either) and the bounce is a tab switch, not a closed screen.

## Story content gated off the guest

`CoopStoryChainGate` publishes `$coopIsGuest` on sector memory from the `CoopModPlugin.beginGameSession()` prologue (set on a guest launch, unset on host or no-role). Nine vanilla `rules.csv` rows are replaced by id with `!$global.coopIsGuest` appended: `goToTheGABarEventOption`, `goToGA_barEvent`, `gaAddOptionMeetProvost`, `gaIntro2surveyOpen`, `gaDHOhookStart`, `gaDHOhookStartDev`, `gaDHOjustFoundArrayStart`, `hamatsu_PostShipRecoverySpecial`, `gaDevMenuOption`. Everything downstream of the Galatia Academy chain tests state only those roots can write. Tutorial-only entries are unreachable because the mod forces the tutorial skip. `CoopRulesFileTest` pins the gate on every root and the file's id uniqueness. The commission rows under Storage above use the same mechanism.

## Engine call costs (measured 2026-08-20)

Measured with `coop.util.CoopFrameProfiler` (`-Dcoop.debug.frameProfile=true`) after the `ORBIT_SNAPSHOT` apply was caught doing about 70 sector-wide `getEntityById` scans per second (67 to 82 ms single-frame stalls).

- `sector.getAllLocations()` allocates two fresh `ArrayList`s and copies about 130 systems per call. Hyperspace is already in the list; a `!contains(hyperspace)` guard pays a full scan for nothing.
- `location.getFleets()` is a live list with one `unmodifiableList` wrapper allocation per call.
- `sector.getEntityById()` is an id-map hit plus a `getAllEntities().contains()` validation, falling back to hyperspace and every system.
- `fleet.setNoEngaging(f)` allocates a new `Fader` per call; the shield expires about 1 s after the last call, so a 4 Hz re-assert is enough.
- `entity.getMemoryWithoutUpdate()` lazily allocates, and save-persists, a `Memory` for entities that lack one.
- `IntelManager.getIntel(Class)` is O(1), not a scan.
- The profiler cannot see combat-engine plugins (`CoopBattleStatusCombatPlugin.advance` attaches to every combat engine, refit sim and title screen included), vanilla listener dispatch, or the save hooks: all of those run outside the pump's `advance()`.

## Debug tooling

### The agent bridge serves four clients at a time

`-Dcoop.debug.bridge` opens a loopback socket that accepts up to `MAX_CLIENTS` (4) connections. The fifth is closed on connect and logged. Each client carries its own framing buffer, request queue and write queue, and the four-commands-per-frame dispatch budget is spent one request per client per pass, so a client sending a burst cannot starve another. The cap is real: every slot costs a 256 KB framing buffer for the life of the connection, and accept, read, dispatch and write all run on the campaign thread inside `advance()`. Four is sized for one MCP server plus a helper or two.
