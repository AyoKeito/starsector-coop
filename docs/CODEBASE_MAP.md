# Codebase Map

Orientation map for the Starsector co-op mod. Last checked against the tree on 2026-09-20 (0.1.3, 7f88aac).

## Stack

- Java 17 toolchain (`build.gradle`: `JavaLanguageVersion.of(17)`), Gradle wrapper (`gradlew`)
- Compiles against the install's `starsector-core` jars: `starfarer.api.jar`, `xstream-1.4.10.jar`,
  `log4j-1.2.9.jar`, `json.jar`, `lwjgl.jar`, `lwjgl_util.jar`; the install is
  `../../starsector-core` unless `-PstarsectorCore` or `STARSECTOR_CORE` points elsewhere
- Three jars out of one build: `jars/coop.jar` (mod classloader), `jars/coop-forks.jar` (system
  classloader, prepended on the install's `vmparams` `-classpath` so forked `com.fs.starfarer.api.*`
  classes shadow the real ones), `jars/coop-launcher.jar` (own JVM).
- FlatLaf 3.6 for the desktop launcher, copied to `jars/flatlaf.jar` by `copyLauncherLibs`
- JUnit 5 (Jupiter) for tests; no test-time game process
- `docs/roadmap_gen.js` - pure Node, no dependencies; `tools/starsector-mcp` - Node stdio MCP server
- PowerShell for build/deploy/launch/package (`scripts/*.ps1`)
- Version identity: `mod_info.json` is the single source of truth. `build.gradle` parses it, and
  `generateCoopBuildInfo` / `generateCoopForksBuildInfo` emit `coop.build.CoopBuildInfo` and
  `coop.build.CoopForksBuildInfo` with the version plus `git rev-parse --short=12 HEAD`
  (`<hash>-dirty` from a modified tree, `dev-uncommitted` with no git metadata).

## Top-Level Layout

```text
K:\Starsector\mods\coop
|-- src/main/java/coop/   the mod: 22 packages + CoopModPlugin
|-- src/launcher/java/    coop.launcher, the FlatLaf desktop launcher (no Starsector API on its classpath)
|-- src/test/java/coop/   JUnit 5 suite, mirrors the main packages + coop/launcher + coop/testing
|-- forks/                classpath-shadow copies of 10 vanilla classes -> coop-forks.jar
|-- data/                 rules.csv, campaign/abilities.csv, config/coop_options.json,
|                         config/settings.json, version/
|-- docs/                 design, plan ledger, generated roadmap, player guides
|-- scripts/              build, clean, deploy, launch host/guest, two-client setup, package-release
|-- tools/starsector-mcp/ Node MCP server wrapping the in-game agent bridge
|-- jars/                 build output (gitignored) + committed FLATLAF-LICENSE.txt
|-- Coop Launcher.cmd     what a player starts; runs <install>\jre\bin\javaw.exe
|-- mod_info.json         canonical version
|-- coop.version          Version Checker manifest; modVersion must match mod_info.json
|-- build.gradle          three jar tasks, generated build-info sources, source sets
|-- tmp_ff_analysis/      scratch + decompiled sources; never committed, never shipped
```

The `forks` source set is `forks/` plus `src/main/java/coop/rng` and `src/main/java/coop/presence`,
which must share the system classloader with the forks. The `jar` task excludes `coop/rng/**`,
`coop/presence/**` and `coop/build/CoopForksBuildInfo*` from `coop.jar` so exactly one copy of each
exists on disk.

**The script classloader shapes the code.** It refuses `java.lang.reflect.*`, `java.io.*` (allow-list
aside) and `java.nio.file.Files`; such code compiles and passes unit tests and throws in-game. Field
access goes through `java.lang.invoke.MethodHandles` (the lazy-resolve + catch-Throwable pattern in
`CoopBarSync.resolveHandles()`), file access through `SettingsAPI`'s common-folder calls. There are no
mod threads either: every socket is non-blocking and pumped from `EveryFrameScript.advance()` on the
campaign thread.

## Runtime Flow

Startup and load:

```text
CoopModPlugin.onApplicationLoad()
  -> publishLauncherProperties()     coop_options.json.data -D-only keys -> System properties
  -> checkGameVersion()              CoopGameVersionCheck
  -> publishSeedForTheForks()        when -Dcoop.newGameSeed is set; rebinds CoopRandom

CoopModPlugin.onGameLoad(newGame)
  -> CoopSaveCheckpoint.notifySessionEnding() + sendSessionLeaveInline() + netService.shutdown()
  -> tearDownPreviousPump(), CoopGuestSnapshotStore/CoopSessionStatsStore/CoopLocations clear
  -> new CoopNetService()
  -> CoopMirrorOrphanSweeper.sweep()
  -> CoopSystemDriveFrameHook.install()
  -> CoopNetPumpInstaller.install(sector, netService)    the per-frame pump
  -> CoopLinkHud.install()
  -> CoopSessionIntel / CoopSessionStatsIntel / CoopOptionsPage .ensureRegistered()
  -> CoopAgentBridge.install()       dormant without -Dcoop.debug.bridge=<port>
  -> CoopSeedSync.storeCurrentSectorFingerprint()
  -> installExpectedCampaignNotice()
```

Connection, from a cold socket to a live session:

```text
CoopNetStartupConfig (-Dcoop.* / coop_options.json.data / sector memory flags)
  -> CoopNetService: TCP listen or dial, shared UDP channel, CoopPeerLink per peer
  -> CoopPortMapper (SSDP -> UPnP IGD, NAT-PMP fallback), CoopConnectionDoctor writes the tier block
  -> LOBBY_HELLO -> LOBBY_CHALLENGE (password) -> LOBBY_ACCEPT | LOBBY_REJECT
  -> HANDSHAKE_MANIFEST -> CoopHandshakeDiff.compare -> HANDSHAKE_RESULT
  -> SEED_LOCK_REQUEST -> SEED_LOCK_ACK | SEED_LOCK_REJECT   (campaign id + fingerprint)
  -> READY_STATE / LOBBY_STATUS, lobby held paused -> release
  -> session live: TIME_SNAPSHOT, FLEET_SNAPSHOT/FLEET_ROSTER, NPC_FLEET_SET/NPC_FLEET_MOTION, ...
```

**Authority model:** the host owns the sector clock, the NPC fleet population, markets, missions,
reputation, dynamic bases, colonies and the canonical save; the guest drives only its own player
fleet and pilots its own battles, and reports outcomes back.

One campaign frame, in `CoopNetPump.advanceFrame` order (abridged; each step is a `CoopFrameProfiler`
section):

```text
service.beginFrame() -> CoopDebug.pollFrame / CoopWiretap.pollFrame -> streamClock.advance
  -> linkQuality.noteFrame -> maybeRefuseForGameVersion -> start-from-properties / memory-flags
  -> service.flushOutbound -> resendSnapshotsDroppedByQueueOverflow -> detectPeerDisconnect
  -> tickReconnect -> tickPortMapper -> syncGuestInputBlocker
  -> maybeSendSessionResumeRequest / maybeSendLobbyHello
  -> drainInbound -> flushReliableAcks -> drainDelayedGuestMessages
  -> assertMirrorEngagementShields -> maybeSendHandshakeManifest -> maybeSendSeedLockRequest
  -> tickDesyncDialog -> tickLobby -> tickMarkDialog -> tickSessionStats -> tickOptionsPolicy
  -> maybeHoldPausedUntilSessionReady -> tickBattleBridge -> tickAllyBattle -> syncSharedPause
  -> syncFastForwardLock
  -> maybeApplyTimeSnapshot -> tickClockReconciler -> maybeSendTimeSnapshot
  -> syncFleetMirror -> drainFleetDatagrams -> advanceMirrorMotion -> maybeSendFleetSnapshot
  -> tickRespawnNotifier -> maybeSendGuestSnapshot -> tickSaveCheckpoint
  -> tickSessionLeaveWatchdog -> tickNetFault
  -> syncNpcReplication -> tickNpcThreatWatcher -> syncBaseReplication -> syncBarGeneration
  -> syncInteractionGate -> syncCampaignReplicator -> campaignReplicator.tick*(...)
  -> maybeSendPing -> tickLinkSupervision -> service.flushOutbound -> tickTerminalStop
```

Guest-side counterpart to host authority:

```text
CoopNpcFleetSuppressor      no native NPC simulation on the guest at all
CoopFleetMirrorRegistry     host coopFleetId -> CoopNpcMirror, driven by replicated snapshots
CoopFleetMirror             the partner's player fleet as an AI-mode CampaignFleetAPI
CoopBarGenerationSuppressor the guest rolls no bar offers; the host's pool is the only pool
CoopMarketSyncGate          "this shop is not canonical yet" until MARKET_SNAPSHOT lands
CoopCampaignInputBlocker    swallows the guest's time keys, suspended while a screen owns input
CoopStoryChainGate          publishes "this client is the guest" into sector memory for rules.csv
```

## Packages by Responsibility

### `coop` (root)

`CoopModPlugin` - the only `BaseModPlugin`. Owns the lifecycle above plus `configureXStream`
(save aliases), `beforeGameSave` / `afterGameSave` / `onGameSaveFailed`, and the launcher-property
republish.

### `coop.net` (31 classes) - transport and pump

- `CoopNetService` - one TCP control channel per peer (JSON lines, reliable) plus one shared UDP
  channel, all non-blocking. Owns the listening socket, session token, sender id, peer table,
  counters, the UDP inbound filter (pinned source address -> envelope prefix parse -> session token)
  and the Phase 20.4 abuse limits.
- `CoopNetPump` - the `EveryFrameScript` that drives everything: ~9.5k lines, the frame order above.
- `CoopNetPumpInstaller` - installs it as a transient script.
- `CoopMessages` - the `Type` enum (73 constants), the envelope codec, datagram header parse,
  `isReliableOneShot`, `wireToken`, `MAX_DATAGRAM_CHUNKS`.
- `CoopPeerLink` - everything known about one peer: TCP channel, half-written frame, inbound
  assembly, validated send address, watermarks.
- `CoopPortMapper` + `CoopSsdpMessages` / `CoopUpnpDescriptor` / `CoopUpnpSoap` / `CoopUpnpXml` /
  `CoopHttpMessages` / `CoopNatPmpMessages` - router port forwarding.
- `CoopConnectionDoctor` - the "why can't we connect" log block, also used by the launcher.
- `CoopReconnectCoordinator` - dead socket into a held session (grace window state machine).
  "Wait more" presses are unlimited, but none can push the deadline past `MAX_REMAINING_MILLIS`,
  thirty minutes from the press.
- `CoopCadenceController` / `CoopCadenceTier` / `CoopStreamCadence` / `CoopStreamClock` /
  `CoopDatagramWatermark` / `CoopDatagramRedundancy` - the UDP state-stream discipline.
- `CoopLinkQuality`, `CoopDatagramStats`, `CoopStateStreamSink`, `CoopOutboundDiscardListener`,
  `CoopStallNotice`, `CoopSessionLeaveWatchdog`, `CoopNetFault`, `CoopWiretap`, `CoopJson`,
  `CoopNetStartupConfig`, `CoopConnectionRole`.

Owns: every wire type, since the enum lives here.

### `coop.fleet` (36 classes) - player and NPC fleet replication

- `CoopFleetSnapshot` / `CoopFleetRoster` / `CoopFleetSnapshotFactory` / `CoopRosterCache` - the
  Phase 20 M4 split: volatile tick on UDP, immutable roster on TCP, recombined at the receiver.
- `CoopFleetMirror`, `CoopGuestMirrorHandle`, `CoopPresenceIndicator`, `CoopMirrorTags` - the
  partner's fleet as a local AI fleet, always visible, tagged so nothing mistakes it for real.
- `CoopNpcFleetReplicator` (host) / `CoopNpcFleetSuppressor` (guest) / `CoopNpcMirror` /
  `CoopFleetMirrorRegistry` / `CoopNpcFleetSetSnapshot` / `CoopNpcFleetSnapshot` - the whole NPC
  population, host-authoritative.
- `CoopNpcFleetMotion` / `CoopMotionInterpolator` / `CoopMotionTimeline` /
  `CoopNpcFleetMotionSmoother` - Phase 29 interpolation: sample buffer, render cursor, staircase
  removal.
- `CoopFullFidelitySystemDriver` / `CoopSystemDriveFrameHook` / `CoopSystemDriveState` /
  `CoopSystemPhaseSlots` - run the guest's system at host fidelity.
- `CoopGuestPresence`, `CoopSensorSync`, `CoopRespawnNotifier`, `CoopMirrorOrphanSweeper`,
  `CoopLocations`, `CoopInflationLatch`, `CoopShipMods`, `CoopNpcActionTextCapture`,
  `CoopFleetCodec`, `CoopRosterSummary`, `CoopOfficerSkills` (officer skill codec),
  `CoopAllyBattleTracker` (Phase 33: freezes the partner mirror while it is in a battle and reads
  the roster once when it leaves); diagnostics `CoopFleetVisibilityProbe`,
  `CoopMotionSpeedProbe`. Owns `FLEET_SNAPSHOT`, `FLEET_ROSTER`,
  `FLEET_ROSTER_REQUEST`, `NPC_FLEET_SET`, `NPC_FLEET_MOTION`, `RESPAWN_PLAYER`.

### `coop.campaign` (32 classes) - shared world state

- `CoopCampaignReplicator` - the hub; the pump calls its `tickWorldDeltas`, `tickOrbitSync`,
  `tickPlayerRepSync`, `tickBarAcceptance`, `tickBarPool`, `tickColony*`, `tickExpeditionWarnings`,
  `tickMarketSyncGate`.
- `CoopWorldDelta` (+ `CoopWorldEntitySpawn`) - the single guest -> host channel for any guest
  interaction that mutates shared state.
- `CoopMarketSync` / `CoopMarketSyncGate` / `CoopMarketIds` / `CoopShipDetail` / `CoopPersonDetail` /
  `CoopMemberIds` / `CoopStorageUnlock` / `CoopStorageUnlockSync` - markets, submarkets, storage.
- `CoopMissionBoardSync` / `CoopMissionClaim` / `CoopBarSync` / `CoopBarPoolCapture` /
  `CoopBarPoolInjector` / `CoopBarAcceptanceWatcher` / `CoopBarGenerationSuppressor` - shared
  mission and bar pools with first-come claims.
- `CoopBaseAuthority` / `CoopBaseRecord` / `CoopSkeletonMutationWatcher` - dynamic pirate and
  Luddic-Path bases, campaign objectives, gates.
- `CoopRepDelta` / `CoopFactionRelations` / `CoopCommissionSync` / `CoopCreditTransfer` /
  `CoopAbilityArbiter` / `CoopAbilityEffectApplier` / `CoopOrbitSync` / `CoopCampaignEventListener` /
  `CoopStoryChainGate` / `CoopDelimited` / `CoopAllyToggleAbility` (Phase 33: the `coop_ally` row in
  `data/campaign/abilities.csv`, a toggle whose effect is nothing and whose state is everything). Owns `MARKET_OPEN`, `MARKET_SNAPSHOT`, `MARKET_TXN`,
  `WORLD_DELTA`, `MISSION_POOL_SNAPSHOT`, `MISSION_CLAIM_*`, `REP_DELTA`, `GUEST_REP_DELTA`,
  `PLAYER_REP_SNAPSHOT`, `FACTION_REL_DELTA`, `ABILITY_ACTIVATE`, `ORBIT_SNAPSHOT`, `BASE_SET`,
  `CREDITS_GRANT`.

### `coop.ui` (29 classes) - dialogs, HUD, intel pages

- `CoopDialogArbiter` / `CoopDialogController` / `CoopDismissableDialog` - `showInteractionDialog`
  is exclusive, so one arbiter decides which coop dialog may ask for the slot and the controller
  owns the retry-until-shown / close-on-demand loop.
- `CoopLinkHud` + `CoopBitmapFont` + `CoopHudState` / `CoopHudCorner` / `CoopHudNotice` - the
  always-on link line, drawn as textured quads because `CampaignUIRenderingListener` offers no
  text-draw call.
- `CoopLobbyDialog` / `CoopLobbyView`, `CoopConnectingDialog`, `CoopReconnectHostDialog` /
  `CoopReconnectGuestDialog` / `CoopReconnectDialogPlugin`, `CoopDesyncDialog` / `CoopDesyncReason` /
  `CoopDoctorMarker`, `CoopMarkDialog`.
- `CoopSessionIntel` / `CoopSessionIntelFeed` / `CoopSessionIntelModel`, `CoopSessionStatsIntel` /
  `CoopSessionStatsView`, `CoopOptionsPage` / `CoopOptionsView` - the three intel pages, all
  transient (removed before every save, recreated after). Plus `CoopFeed` (campaign message feed),
  `CoopCampaignNotice`, `CoopLiveDialogLine`, `CoopColors`.

### The smaller packages

- **`coop.time`** (4) - shared clock. `CoopTimeLock` captures the host clock for `TIME_SNAPSHOT` and
  installs the input listeners; `CoopSharedPauseCoordinator` computes the effective pause;
  `CoopFastForwardLock` owns every `MethodHandles` touch of the engine's fast-forward state;
  `CoopClockReconciler` converges the guest's clock. Owns `TIME_SNAPSHOT`, `PAUSE_INTENT`.
- **`coop.combat`** (12) - own-fleet battles plus the Phase 33 AI ally. `CoopBattleBridge`;
  `CoopBattleStatus` + `CoopBattleStatusCombatPlugin` (the only mod code running inside a battle);
  `CoopBattleResult` + `CoopBattleResultReconciler`; `CoopNpcThreatWatcher` (vanilla pursuit AI ->
  guest local combat); `CoopEngageDialogStaging` / `CoopCustomsDialogStaging`;
  `CoopPreBattleAutosave`. Phase 33: `CoopAllyBattleJoin` and `CoopAllyBattleOutcome` are what the
  partner's mirror hands the pump when vanilla pulls it into a fight and when that fight ends;
  `CoopAllyLossApplier` is the one place allowed to remove ships from, or write hull and CR on, the
  local player's real fleet. Owns `BATTLE_BEGIN`, `BATTLE_STATUS`, `BATTLE_END`, `BATTLE_RESULT`,
  `ENGAGE_GUEST`, `DIALOG_BEGIN`, `ALLY_BATTLE_JOIN`, `ALLY_BATTLE_RESULT`.
- **`coop.colony`** (7) - shared player faction. `CoopColonySync` (found/abandon),
  `CoopColonyManagement` (industries, construction queue, toggles), `CoopColonyIncome`,
  `CoopRaidOutcomeSync`, `CoopExpeditionWarning` + `Sync` + `Intel`. Owns `COLONY_FOUNDED`,
  `COLONY_ABANDONED`, `COLONY_MGMT`, `COLONY_INCOME`, `EXPEDITION_WARNING`, `RAID_RESULT`.
- **`coop.save`** (7) - `CoopSaveCheckpoint` (host saves -> guest autosaves), `CoopGuestSnapshot` /
  `Factory` / `Store`, `CoopSaveIndex` + `CoopSaveIndexSchema`, `CoopCampaignGuard` (the only reader
  of `coop.expectedCampaignId`; a loaded save whose campaign id differs from the invite's, or that
  carries none at all, gets the wrong-campaign notice). Owns `SAVE_CHECKPOINT`,
  `SAVE_CHECKPOINT_RESULT`, `GUEST_SNAPSHOT`.
- **`coop.session`** (6) - `CoopSessionState`, `CoopPlayerInfo`, `CoopLobbyState`, `CoopLobbyRoster`,
  `CoopJoinPhase` (the five named join steps), `CoopIronModeGuard`. Owns `READY_STATE`,
  `LOBBY_STATUS`, `SESSION_LEAVE`.
- **`coop.handshake`** (4) - `CoopHandshakeManifest` captures game version, `coopBuildVersion`,
  `coopGitCommit`, the enabled-mod list and a SHA-256 of `mod_info.json`; `CoopHandshakeDiff.compare`
  rejects on any difference; `CoopGameVersionCheck` asks locally; `CoopChecksum`. Owns `LOBBY_*`,
  `HANDSHAKE_MANIFEST`, `HANDSHAKE_RESULT`.
- **`coop.seed`** (3) - `CoopSeedSync` holds `coop.seedLong`, `coop.seedString`,
  `coop.sectorFingerprint`, `coop.campaignId`, `coop.localPlayerId`; `CoopSectorFingerprint` and
  `CoopSectorProcGen` compute the structural check. Owns `SEED_LOCK_REQUEST` / `_ACK` / `_REJECT`.
- **`coop.interaction`** (4) - `CoopInteractionGate` arbitrates claims on shared entities;
  `CoopInteractionClaim`, `CoopRejectTracker` (force-close a rejected dialog), `CoopClaimWaitTracker`.
  Owns `INTERACTION_CLAIM` / `_ACCEPT` / `_REJECT` / `_RELEASE`.
- **`coop.config`** (3) - `CoopOptionsRegistry`, the one typed schema for every `coop.*` setting,
  tiered `LAUNCH` / `POLICY` / `CLIENT`; `CoopOptionsStore`, the precedence stack (`-D` beats the
  settings file beats the install default); `CoopOptionsPolicy`, the host-authoritative POLICY values
  with a pending/applied split so nothing applies retroactively. Owns `OPTIONS_SNAPSHOT`,
  `OPTIONS_APPLIED`.
- **`coop.input`** (3) - `CoopCampaignInputBlocker` (guest time keys, `setSuspended` while a blocking
  screen owns input), `CoopHostPauseInputListener`, `CoopMarkInputListener`.
- **`coop.stats`** (3) - `CoopSessionStats` (host-tallied, persisted under `coop.sessionStats`),
  `CoopSessionStatsCodec`, `CoopSessionStatsStore`. Owns `SESSION_STATS`, `SHIP_LOST`.
- **`coop.mark`** (3) - `CoopMarkService`, `CoopMarkKey`, `CoopMarkFormat`: the `coop.markKey`
  hotkey (`F11` by default) that writes one `COOP-MARK` line into both players' logs, stamped with
  the campaign day count (cycle times 360 plus the day into the cycle). Owns `MARK`.
- **`coop.newgame`** (3) - `CoopNewGameDialogPlugin` (guest-aware New Game dialog),
  `CoopNewGameChoices`, `CoopWorldSettings` (the two settings a sector cannot be asked for later).
- **`coop.debug`** (3) - `CoopAgentBridge` (dormant localhost TCP listener), `CoopAgentCommands`
  (verb -> handler registry + JSON codec), `CoopOwnFleetProbe`.
- **`coop.util`** (5) - `CoopLog`, `CoopDebug` (diagnostics opt-in), `CoopText`, `CoopFrameProfiler`,
  `CoopIntelFacts`.
- **`coop.rewards`** (1) - `CoopRewardSplitter`, the co-op share policy as pure arithmetic.
- **`coop.rng`** (1) and **`coop.presence`** (2) - system classloader only. `CoopRandom` is the
  deterministic session-seeded RNG the forks draw from; `CoopPresenceRegistry` is the one slot
  through which `coop.jar` tells the forked engine classes where the second player is;
  `CoopSpawnSpacing` is their spawn geometry. Both ship **only** in `coop-forks.jar`.

## Wire Protocol

Types are declared in `coop/net/CoopMessages.java` as `CoopMessages.Type`: **73 constants**. The envelope is flat JSON, six fields, no arrays (the parser has no array support - multi-element
payloads use the `CoopDelimited` unit-separator encoding instead):

```text
{"type","sessionId","seq","sentAtMillis","payloadJson","senderId"}
```

TCP carries control and reliable campaign traffic as newline-delimited JSON. UDP carries the two
state streams (`FLEET_SNAPSHOT`, `NPC_FLEET_MOTION`) inside `STATE_DATAGRAM`, with a token prefix,
per-sender epoch/stream time (`CoopStreamClock`), receiver watermark (`CoopDatagramWatermark`) and
depth-2 redundancy on a lossy floor (`CoopDatagramRedundancy`).

- **Budget:** `CoopNetService.MAX_DATAGRAM_BYTES = 1200`, `MAX_DATAGRAMS_PER_POLL = 256`,
  `CoopMessages.MAX_DATAGRAM_CHUNKS = 64`. A composed datagram over the cap is rerouted onto TCP by
  the pump, which owns the decision.
- **Cadence:** `CoopCadenceTier` is the set of certified rates; `CoopCadenceController` picks 5 or
  10 Hz per link from p50 RTT, loss, backlog and fallback state, with a 30 s clean window before a
  rate rise and the fallback pinning the floor.
- **Reliable one-shots:** `MARKET_TXN`, `CREDITS_GRANT`, `WORLD_DELTA`, `RAID_RESULT`, `SHIP_LOST`,
  `COLONY_FOUNDED`, `COLONY_ABANDONED`, `COLONY_MGMT`, `REP_DELTA`, `GUEST_REP_DELTA`,
  `FACTION_REL_DELTA`, `MARK`, `ALLY_BATTLE_RESULT`. Campaign events with no producer that would resend them, so the
  transport acks (`RELIABLE_ACK`) and replays them across a socket replacement.
- **Pre-session gating:** campaign traffic that arrives before a session is live is dropped with one
  warn per session (`preSessionCampaignDropWarned`). During a reconnect grace only
  `SESSION_RESUME_*`, `LOBBY_HELLO`, `LOBBY_CHALLENGE`, `PING`, `PONG` are accepted from an unproven
  peer.

**Adding a type means eight rows, not one.** `src/test/java/coop/net/CoopMessageTypePolicyTest.java`
pins every `Type` against the hand-written tables in `CoopNetService` and `CoopNetPump`, and
`everyTypeIsClassified()` fails the moment `Type.values()` outgrows its hand-written registry. A new
constant must be argued onto:

```text
ALL_KNOWN_TYPES                  the test's own registry (never EnumSet.allOf)
COALESCED                        CoopNetService.coalesceKey
CONNECTION_SCOPED_CONTROL        CoopNetService.isConnectionScopedControl
RESUME_VERDICT                   CoopNetService.isResumeVerdict
RELIABLE_ONE_SHOT                CoopMessages.isReliableOneShot
ALLOWED_DURING_RECONNECT_GRACE   CoopNetPump.allowedDuringReconnectGrace
SURVIVES_DROP_EDGE               CoopNetPump.survivesTheDropEdge (exhaustive switch, no default)
TERMINAL_REJECT / CONTROL_PLANE / HIGH_FREQUENCY   CoopNetPump logging + dispatch policy
```

Cross-table rules are asserted too: a reliable one-shot is never coalesced, never connection-scoped,
and always survives the drop edge; every resume verdict is also connection-scoped control.

## Time, Pause and Clocks

```text
guest key press / screen open -> PAUSE_INTENT -> host
host: effectivePaused = hostPauseIntent || guestKeyPauseIntent
                        || guestScreenPauseIntent || eitherInCombat
   -> host Sector.setPaused(effectivePaused)
   -> rides TIME_SNAPSHOT (with pausedBy) -> guest clock follows
```

- `CoopSharedPauseCoordinator` is the authority. The guest **never** calls `setPaused` locally to
  drive divergence - it reports intent and waits for the snapshot.
- Two guest intents, different rules: a **key** pause the host may override, a **screen** pause it
  cannot (the guest opened map/refit/cargo/intel/dialog and gets to read without the world running).
  The key intent is resolved against `observedPaused()` on every press, so no sticky bit can desync.
- `CoopTimeLock` captures the host clock (`SNAPSHOT_INTERVAL_MILLIS = 200`), installs
  `CoopCampaignInputBlocker` on the guest, `CoopHostPauseInputListener` on the host, and
  `CoopMarkInputListener` on both.
- `CoopFastForwardLock` (Phase 7b) makes campaign fast-forward a shared time speed: vanilla's
  toggle-FF mode makes the flag consumable and settable through `MethodHandles`.
- `CoopClockReconciler` (Phase 7c) converges the guest's clock on the drift signal the
  `TIME_SNAPSHOT` stream carries. `-Dcoop.clock.disable=true` turns it off (guest only).
- **The input blocker is suspended while a blocking screen owns the keyboard**
  (`CoopNetPump.syncGuestInputBlocker` -> `timeLock.setInputBlockerSuspended(isGuestScreenOwningInput())`),
  or the guest is trapped in its own dialog with no options and a dead ESC. The shared screen-pause
  already stops both clocks there, so suspending consumption cannot let the guest pause on its own.

## Saves and State

Everything the mod writes into persistent sector data, and who reads it back, is tabulated in
`README_DEV.md` ("Save-Visible State"). The keys: `coop.seedLong`, `coop.seedString`,
`coop.sectorFingerprint`, `coop.campaignId`, `coop.localPlayerId`, `coop.guestFleetSnapshot`,
`coop.sessionStats`, `coop.options.<key>`, `coop.optionsPolicyVersion`. Renaming a class named there
needs an XStream alias in `CoopModPlugin.configureXStream`;
`coop.colony.CoopExpeditionWarningIntel` reaches a save under its own class name with **no alias**.

**Identity.** The campaign id is host-minted once at the first seed lock and adopted by the guest; it
is what distinguishes "the same campaign resumed" from "a fresh re-roll of the same seed", since the
seed string and structural fingerprint are pure functions of the seed and pass identically for both.

**Coordinated saves.** `CoopSaveCheckpoint`: every host save orders a guest vanilla autosave, so the
two files stay temporally aligned. `autosave()` is silently a no-op while a dialog is open, so the
guest parks the checkpoint and retries every frame up to a ten-minute backstop, reporting progress
back over `SAVE_CHECKPOINT_RESULT`.

`saves/common` holds two files, both written through `SettingsAPI`'s `...Common` calls (which append
`.data`):

```text
coop_options.json.data   CoopOptionsStore.writeOverrides + the launcher's one-shot -D-only keys
coop_saves.json.data     CoopSaveIndex.recordCurrentSave, from CoopModPlugin.afterGameSave
                         one row per watched save; 8 rows per campaign, 16 campaigns
```

**Rejoin.** The guest rejoins by **loading its coordinated autosave**, not New Game. A fresh
same-seed campaign is rejected at seed lock ("already in flight") unless the guest is launched with
`-AdoptCampaign`, which discards the guest's progress. A guest save is co-op-only: the Phase 13
suppressors remove the vanilla spawner and `BarEventManager` scripts, so it will not run solo.

## Launcher

`Coop Launcher.cmd` at the mod root resolves the install root two levels up and runs
`<install>\jre\bin\javaw.exe` - no JRE to install, no execution-policy prompt.

```text
src/launcher/java/coop/launcher/   22 classes
  CoopLauncherApp      main; CardLayout Host/Join + fixed footer, owned dialogs, modeless Logs
  CoopLauncherConfig   settings round trip through saves/common/coop_options.json.data
  CoopInvite           the invite string; `cid` carries the campaign pick, last and optional
  CoopSaveIndexReader  reads coop_saves.json.data, joins rows to descriptor.xml on disk
  CoopCampaignPicker   the Campaign drop-down (New first, then one entry per campaign)
  CoopLauncherUi       shared Swing primitives: panels, form rows, the wrapping row layouts
  CoopInstallCheck / CoopInstallFixer / CoopInstallLayout / CoopVmparamsText   the Fix button
  CoopUpdateCheck, CoopBugReport, CoopGameProcess, CoopLogTail, CoopPublicAddress, CoopPasswords,
  CoopSeeds, CoopTheme, CoopIcons, CoopAtomicFiles, CoopLauncherProbe, CoopLauncherLogging
```

- **Config round trip.** The launcher writes player settings and `-D`-only keys into
  `coop_options.json.data`. `CoopModPlugin.publishLauncherProperties()` republishes each as a real
  system property unless the command line already carries one (a real `-D` stays top of the stack),
  then strikes the one-shot keys (`coop.expectedCampaignId`, `coop.adoptCampaignId`) out of the file.
  Launcher-only state lives in the same schema: `coop.launcher.bridgeEnabled` and
  `coop.launcher.bridgePort` are the Settings window's Agent bridge checkbox and the port beside it,
  which Launch composes into the `coop.debug.bridge` the game actually reads (0 means the role
  default, 7801 hosting and 7802 joining).
- **Install check.** `CoopInstallFixer` applies the two edits the mod cannot reach: inserting
  `..\mods\coop\jars\coop-forks.jar;` after the ` -classpath ` marker in `<install>\vmparams`
  (ISO-8859-1, no trailing newline, backup first), and appending `"coop"` to
  `<install>\mods\enabled_mods.json`. An `AccessDeniedException` offers an elevated relaunch.
- **Bug report** zip and **update check** (the launcher reads `releases/latest`, which is why a
  release is never marked prerelease).
- **Rule: the launcher module stays free of Starsector API imports.** `starfarer.api.jar` is
  deliberately absent from `sourceSets.launcher.compileClasspath`, so the compiler catches a slip.
  The launcher reuses `CoopPortMapper`, `CoopConnectionDoctor`, `CoopOptionsRegistry` and
  `CoopNetStartupConfig.validateNewGameSeed` out of `coop.jar`; any launcher path that reaches the
  API is a bug in the launcher, not a missing classpath entry.

## Dev Tooling

- **Agent bridge.** `coop.debug.CoopAgentBridge`, gated on `-Dcoop.debug.bridge=<port>`; absent,
  unparsable or `0` means no socket and no log line. Binds 127.0.0.1 only, four clients, a few
  commands per frame on the campaign thread. 30 verbs in `CoopAgentCommands`: `status`, `fleets`,
  `cargo`, `market`, `markets`, `barpool`, `survey`, `visibility`, `ownfleet`, `colonizable`,
  `hirable`, `landmarks`, `entities`, `intel`, `feed`, `screen` read; `teleport`, `pause`, `ability`,
  `setcr`, `give`, `addship`, `objective`, `surveyset`, `expedition`, `rep`, `netfault`, `save`,
  `mark`, `memory` act.
  Four things are deliberately **not** verbs (market buy/sell, officer hire, bar-offer accept, market
  open/close) because a UI listener drives each and a verb would bypass the listener under test.
- **MCP server.** `tools/starsector-mcp` (Node, stdio) wraps host port 7801 and guest 7802 into
  `ss_status`, `ss_dump`, `ss_diff`, `ss_act`, `ss_advance_days`. All state comparison lives there;
  the bridge only serializes. `ss_diff` excludes `role`, `engineId`, `lines` by default. Setup and a
  worked example per tool: `tools/starsector-mcp/README.md`.
- **Diagnostics switches.** `coop.util.CoopDebug` gates the dormant probes (orbit dump, dialog state,
  `CoopFleetVisibilityProbe`, `CoopMotionSpeedProbe`, `CoopOwnFleetProbe`) behind
  `-Dcoop.debug.diagnostics=true` or the `$coopDebug` sector-memory flag, polled every 300 frames;
  `CoopFrameProfiler` behind `-Dcoop.debug.frameProfile=true` or `$coopFrameProfile`;
  `coop.net.CoopWiretap` (UDP stream dump + size histogram) behind `-Dcoop.debug.wiretap` and
  `-Dcoop.debug.wiretapSample`. `netfault{mode, seconds, lossPercent, delaySeconds}` makes *this*
  instance stop hearing its peer; outbound is untouched in every mode.

```text
scripts/build.ps1                 clean test build (all three jars); -BaseInstallRoot -> -PstarsectorCore
scripts/clean.ps1                 removes build/ and jars/coop.jar only
scripts/coop-paths.ps1            shared path detection, dot-sourced by the others
scripts/setup-two-client-test.ps1 clones the install into K:\Starsector-coop-test\{host,guest}
scripts/deploy-to-test-clients.ps1  builds + copies the whole mod (data/ included) into both
scripts/launch-host.ps1           -Port, -Diagnostics, -Bridge (7801), -ExtraJvmProps
scripts/launch-guest.ps1          -HostAddress, -Port, -Bridge (7802), -ProxiedJvm, -AdoptCampaign
scripts/tail-two-client-logs.ps1  coop lines from both profiles
scripts/package-release.ps1       dist\coop-<version>.zip; refuses a dirty tree, a version mismatch,
                                  jars whose baked-in commit is not HEAD, or a broken classloader split
```

`docs/roadmap.html` is **generated**: `node docs/roadmap_gen.js` (`--check` validates without
writing). Status comes from the plan's Phase Status Ledger, build order from its Implementation
Order line, everything else from `docs/roadmap.data.json`; design lives in
`docs/roadmap.template.html`. Never hand-edit the HTML.

## Tests

```text
src/test/java/coop/   .java counts, 2026-09-20
  campaign/ 49   net/ 37   fleet/ 37   ui/ 22   launcher/ 19   combat/ 11   testing/ 8   save/ 6
  colony/ 5   debug/ 5   config/ 4   time/ 4   handshake/ 3   interaction/ 3   mark/ 3   seed/ 3
  session/ 3   util/ 3   input/ 2   newgame/ 2   stats/ 2   presence/ 1   rewards/ 1   rng/ 1
  CoopModPluginTest.java   CoopScaffoldTest.java
```

3726 tests green on the last full run. The suite is the only safety net between a change
and a two-instance smoke.

`coop.testing` holds the shared fixtures rather than a ninth copy of the same proxy:

- `ApiProxies` - dynamic-proxy stubs for `SettingsAPI`, `SectorAPI`, `ListenerManagerAPI`, `MemoryAPI`,
  the ones most colony/raid/campaign tests must hand to `Global` before the code under test runs.
- `ProxyDefaults`, `RecordingNetService`, `FakeCreditEngine`, `TestSessions`, `LogCapture`.

Run everything: `.\gradlew.bat test`. Run one class:
`.\gradlew.bat test --tests coop.net.CoopMessageTypePolicyTest`. From a worktree add
`-PstarsectorCore=K:\Starsector\starsector-core`. The `test` task passes the same `--add-opens` the
game's own `vmparams` do, because XStream 1.4.10 reflects into `java.base` during static init.

## Docs

| File | What it is |
| --- | --- |
| `README.md` | the player-facing pitch and release links |
| `README_DEV.md` | every command: build, test, package, launch, two-client test, bridge verbs, release checklist, save-visible state |
| `CLAUDE.md` | the short version an agent session gets wrong without being told |
| `CHANGELOG.md` | per-release notes; 0.1.3 at the head |
| `docs/COOP_MP_DESIGN.md` | design rationale |
| `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md` | the phase ledger and every agreed decision. **Canonical for status** - when it and a phase's checkboxes disagree, the ledger wins |
| `docs/starsector-runtime-limitations.md` | engine and sandbox facts plus accepted divergences; current facts only, entries are deleted when their fix lands |
| `docs/PHASE20_SPIKE_RESULTS.md` | the measured networking spike results: UPnP and NAT-PMP against the real router, dual-stack IPv6 binds, a session across the real Internet |
| `docs/PHASE22_TACTICAL_FEASIBILITY.md` | the 2026-09-05 source pass on tactical orders over joined ships |
| `docs/roadmap.html` | **generated** from `roadmap.data.json` + the ledger by `roadmap_gen.js`; never hand-edited |
| `docs/player/INSTALL.md` | install, plus "Every setting" with defaults for every `coop.*` property |
| `docs/player/CONNECT.md` | the player-facing networking guide |
| `docs/player/LIMITATIONS.md` | what is and is not shared |
| `docs/player/LISTING.md` | forum/Nexus listing copy |
| `docs/player/REPORTING.md` | how to report a problem |

`docs/player/` ships inside the release zip, alongside `mod_info.json`, `jars/`, `data/`,
`Coop Launcher.cmd`, `coop.version`, `LICENSE` and `CHANGELOG.md`.

## Read-First Files by Task

Paths are relative to the repo root; `coop/x/Y.java` is short for `src/main/java/coop/x/Y.java`.
Most important first.

| Task | Read first |
| --- | --- |
| Adding a wire message | `coop/net/CoopMessages.java` (the `Type` enum, codec, `isReliableOneShot`); `src/test/java/coop/net/CoopMessageTypePolicyTest.java` (all the tables); `coop/net/CoopNetPump.java` (`drainInbound`, the send tick); `coop/net/CoopNetService.java` (`coalesceKey`, `isConnectionScopedControl`); `coop/campaign/CoopDelimited.java` when the payload holds a list |
| Touching pause or time | `coop/time/CoopSharedPauseCoordinator.java`; `coop/time/CoopTimeLock.java`; `coop/time/CoopClockReconciler.java`; `coop/time/CoopFastForwardLock.java`; `coop/input/CoopCampaignInputBlocker.java`; `CoopNetPump.syncSharedPause` / `syncGuestInputBlocker` / `tickClockReconciler` |
| Guest-side mirror behaviour | `coop/fleet/CoopFleetMirror.java`; `coop/fleet/CoopNpcMirror.java`; `coop/fleet/CoopFleetMirrorRegistry.java`; `coop/fleet/CoopMotionInterpolator.java` + `CoopMotionTimeline.java`; `coop/fleet/CoopNpcFleetSuppressor.java`; `coop/fleet/CoopMirrorTags.java` |
| Market or storage sync | `coop/campaign/CoopMarketSync.java`; `coop/campaign/CoopMarketSyncGate.java`; `coop/campaign/CoopStorageUnlockSync.java`; `coop/campaign/CoopMarketIds.java` + `CoopMemberIds.java`; `coop/campaign/CoopShipDetail.java` + `CoopPersonDetail.java` |
| Colony sync | `coop/colony/CoopColonySync.java`; `coop/colony/CoopColonyManagement.java`; `coop/colony/CoopColonyIncome.java`; `coop/rewards/CoopRewardSplitter.java`; `coop/colony/CoopExpeditionWarningSync.java`; `CoopCampaignReplicator.tickColony*` |
| Launcher setting | `coop/config/CoopOptionsRegistry.java` (add the key to the schema first); `src/launcher/java/coop/launcher/CoopLauncherConfig.java`; `src/launcher/java/coop/launcher/CoopLauncherApp.java`; `coop/CoopModPlugin.java` (`publishLauncherProperties`, `oneShotConsentKeys`); `docs/player/INSTALL.md` |
| A new hotkey or HUD line | `coop/input/CoopMarkInputListener.java` (the pattern); `CoopTimeLock.syncMarkInputListener`; `coop/ui/CoopLinkHud.java`; `coop/ui/CoopHudState.java` + `CoopHudNotice.java`; `coop/ui/CoopBitmapFont.java` for anything drawn rather than written |
| Handshake or connection refusal | `coop/handshake/CoopHandshakeManifest.java`; `coop/handshake/CoopHandshakeDiff.java`; `coop/net/CoopNetService.java` (lobby round, abuse limits, token filter); `coop/net/CoopConnectionDoctor.java` + `CoopPortMapper.java`; `coop/ui/CoopDesyncReason.java` + `CoopDesyncDialog.java` |
| Save or rejoin | `coop/save/CoopSaveCheckpoint.java`; `coop/save/CoopSaveIndex.java`; `coop/save/CoopCampaignGuard.java`; `coop/seed/CoopSeedSync.java`; `coop/net/CoopReconnectCoordinator.java`; `coop/CoopModPlugin.java` (`configureXStream`, `beforeGameSave`, `afterGameSave`); `README_DEV.md` "Save-Visible State" |
| Adding a bridge verb | `coop/debug/CoopAgentCommands.java`; `coop/debug/CoopAgentBridge.java`; `tools/starsector-mcp/lib/tools.js` + `lib/bridge-client.js`; `src/test/java/coop/debug/CoopAgentBridgeTest.java` (dormancy is pinned there); `README_DEV.md` "Agent Bridge And Starsector MCP" |
| A release | `README_DEV.md` "Release Checklist" (seven steps, in order); `mod_info.json` and `coop.version` (both bumped, cross-checked by the packager); `CHANGELOG.md` and `docs/player/`; `scripts/build.ps1` then `scripts/package-release.ps1` |

## Traps

Things that compile, pass the suite, and fail in-game.

- **Reflection and file I/O are blocked.** The script classloader refuses `java.lang.reflect.*`,
  `java.io.*` and `java.nio.file.Files`. Use `MethodHandles`. (`CLAUDE.md`, "Engine rules that
  compile fine and crash in-game"; the plan's "Runtime sandbox (hard constraint)".)
- **Dialogs are exclusive.** `showInteractionDialog` returns false whenever another dialog holds the
  slot - hence `CoopDialogArbiter` and `CoopDialogController`'s retry-until-shown loop. The guest's
  input blocker must be suspended while a blocking screen owns the keyboard or the guest is trapped
  in its own dialog. (`CoopNetPump.syncGuestInputBlocker` comment; `CoopDialogController` javadoc.)
- **The guest never calls `setPaused` to drive divergence.** It reports intent and waits for the
  host's `TIME_SNAPSHOT`. (`CoopSharedPauseCoordinator` javadoc, "Authority model".)
- **Per-second writes make the vanilla text panel flash.** Anything that updates every second is a
  HUD line, not a feed line. (`README_DEV.md`, the netfault countdown note.)
- **The seed must fit a signed 64-bit long.** `-Dcoop.newGameSeed` is `MN-` plus digits
  `Long.parseLong` accepts; an invalid value is logged once and treated as unset.
  (`CoopNetStartupConfig.validateNewGameSeed`.)
- **`autosave()` is silently a no-op while a dialog is open or the campaign is off screen.** Both
  `CoopSaveCheckpoint` and `CoopPreBattleAutosave` park and retry rather than assume it happened.
- **No mod threads.** Starsector kills mod-created networking threads without saying so; every socket
  is a state machine the pump advances. Never store a transport object in saved state - `CoopNetPump`
  is a transient script. (`CLAUDE.md`; `CoopNetService` javadoc.)
- **A link drop inside a reconnect grace is not a session edge.** Session-scoped state is held across
  the window: the host's NPC set hashes, the guest's spawner suppressor, the battle-result dedup set,
  the partner's player mirror, the NPC mirrors, the mirrored hidden bases. Only link-scoped state
  resets - datagram watermark, redundancy depth, motion timeline, accepted-stamp high-water mark. A
  resume calls `CoopNpcFleetReplicator.rearmFullBroadcast()`, never `reset()`, which would unregister
  `CoopGuestPresence` and let the vanilla fleet managers despawn the guest's fleets.
  (`CoopNetPump.syncNpcReplication` javadoc.)
- **`InputEventAPI` control names are enum constants** - `GENERAL_PAUSE`, not `PAUSE`; a wrong name
  passes the mocked tests and crashes the game. Worktree builds need
  `-PstarsectorCore=K:\Starsector\starsector-core` or the compile fails with a wall of "cannot find
  symbol". (`CLAUDE.md`; `build.gradle`'s `starsectorCoreSetting`.)
- **Two people building from their own checkouts cannot connect.** `coopGitCommit` is compared in the
  handshake, and a dirty tree reports `<hash>-dirty`. A release is one built artifact both players
  install. (`README_DEV.md`, "Release Checklist".)
- **`coop/rng/` or `coop/presence/` entries inside `coop.jar` break the forks silently.** Check with
  `jar -tf` before shipping; `package-release.ps1` refuses a broken split.
- **Every repo file is LF.** `.gitattributes` (`* text=auto eol=lf`) and `.editorconfig`
  (`end_of_line = lf`) are committed; do not write CRLF.
