# Starsector 2-Player Coop Multiplayer Mod: Design & Findings

**Document status:** describes the code at HEAD (`e089b8f`, 2026-09-19, mod version 0.1.2 as declared in `mod_info.json`). This file is canonical for *design rationale*: why the shipped architecture is shaped the way it is, and which alternatives were rejected. It is not the build tracker. The **Phase Status Ledger** at the top of `COOP_MP_IMPLEMENTATION_PLAN_V1.md` is canonical for what is BUILT, specced or cancelled.
**Target:** Starsector 0.98a-RC8. `CoopPresenceRegistry.PINNED_VERSION` holds that string; the handshake refuses any other version unless `-Dcoop.allowGameVersionMismatch=true` is set.
**Audience:** the author across machines, future Claude sessions, anyone joining the project.

Player-facing behaviour lives in `docs/player/LIMITATIONS.md`, `docs/player/CONNECT.md` and `docs/player/INSTALL.md`. Engine and sandbox facts live in `docs/starsector-runtime-limitations.md`. This document does not repeat them.

---

## Table of contents

1. [Project scope and decision log](#1-project-scope-and-decision-log)
2. [Design principles](#2-design-principles)
3. [Prior art](#3-prior-art)
4. [Architecture summary](#4-architecture-summary)
5. [Starsector moddability baseline](#5-starsector-moddability-baseline)
6. [API surfaces the mod uses](#6-api-surfaces-the-mod-uses)
7. [Determinism strategy](#7-determinism-strategy)
8. [Subsystem-by-subsystem, as built](#8-subsystem-by-subsystem-as-built)
9. [Risk register](#9-risk-register)
10. [Research items, answered](#10-research-items-answered)
11. [Execution order that shipped](#11-execution-order-that-shipped)
12. [Still open](#12-still-open)
13. [Glossary](#13-glossary)
14. [Appendix: paths & resources](#14-appendix-paths--resources)

---

## 1. Project scope and decision log

> **This section is the record of decisions made**, starting 2026-05-28. Rows later reversed carry a dated `Reversed:` note in place rather than being deleted, because the reason for the reversal is usually the useful part.

### Core scope

| Aspect | Decision |
|---|---|
| Players | Exactly 2 in gameplay terms. The wire format is N-ready since Phase 20.5 (`senderId`, the `CoopPeerLink` peer table, `send` as broadcast and `sendTo` as unicast); `coop.maxGuests` ships at `1`. Lifting that clamp is Phase 27, not built. |
| Spatial model | Independent fleets, free roam. Either player can be anywhere in the sector. |
| Authority | Host-authoritative for everything shared: clock, NPC fleet population, markets, economy, intel, faction relations, colonies, interaction claims. |
| PvP | Disallowed. No arbitration, no anti-cheat. |
| Concurrent battles | Each player fights their own battle on their own machine. Combat start asserts the shared pause, so the other player's campaign is frozen for the duration. `CoopNpcThreatWatcher` hands a host-owned hostile that is chasing the guest mirror over to the guest as `ENGAGE_GUEST` before contact, so the two player fleets are never pulled into one engagement. |
| Combat target | Solo own-fleet combat. Joint piloting is post-V1 and is now a two-step track: Phase 33 (AI-ally battles, the partner's ships fight under your admiral) then Phase 22 (tactical orders over those ships). Neither is built. |
| Time / clock | One shared clock. The host applies the effective pause and the guest follows `TIME_SNAPSHOT` at `CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS` = 200 ms, so 5 Hz. *Reversed 2026-09-02 (Phase 7b):* the original "guest cannot fast-forward and cannot pause" became "fast-forward is a shared speed" once vanilla's toggle-mode FF field proved writable, and `coop.allowGuestPause` (default `true`) lets the guest assert a pause intent. |
| Spectator UX | *Reversed 2026-08-19.* The live spectator screen was cut. The non-engaged player gets two `CampaignUIAPI.addMessage` banners, one at battle start and one at battle end carrying the last reported survivor counts. The reason is recorded in `CoopBattleBridge`: real spectating happens over a Discord screen share, and a full-screen dialog on the watching client is in the way. The `BATTLE_STATUS` stream (`STATUS_INTERVAL_MILLIS` = 400 ms, so 2.5 Hz) and its kill feed are still sent and still parsed; the spectator logs them at debug level. |
| Both-players-present rule | *Reversed 2026-09-02 (Phase 20.2).* A dropped socket no longer ends the session. `CoopReconnectCoordinator` holds the session and the clock for `coop.reconnectGraceSeconds` (default 60). A peer whose process is still alive resumes the same session; a relaunched peer cannot, because the session id exists only in the dead process's memory, so it gets an ordinary lobby round instead. An authenticated `LOBBY_HELLO` ends the host's wait immediately. |
| Mod compatibility | Exact match. `CoopHandshakeManifest` carries game version, coop build version, coop git commit, the `coop-forks.jar` build stamp and the enabled-mod list with checksums; `CoopHandshakeDiff` renders the mismatch. No Nex support is claimed. |
| Disconnect handling | See the reconnect grace above. When the grace expires the session ends, and a reliable message that can never be delivered (a credit transfer, for instance) is refunded with a line in the message feed. |
| Build system | Gradle produces `jars/coop.jar`, `jars/coop-forks.jar` (the classpath forks, `forksJar` task), `jars/coop-launcher.jar` and `jars/flatlaf.jar`. *Reversed 2026-06-10:* Netty was never bundled. The transport is plain `java.nio` non-blocking channels in `CoopNetService`, because mod-created threads die silently mid-session and the script sandbox blocks `java.io`. |
| Source repo | Public GitHub (`github.com/AyoKeito/starsector-coop`). Licence CC BY-NC 4.0 with a Fractal Softworks exemption; see `LICENSE`. |

### Per-player state ownership

| What | Per-player or shared? |
|---|---|
| Player fleet (ships, hull state) | Per-player. Each client's `getPlayerFleet()` is its own; the partner is a mirror. |
| Credits and cargo | Per-player. Phase 32 added `CoopCreditTransfer`, a send-credits row on the Coop Options page. |
| Officers, character XP/skills | Per-player. |
| Faction reputation | **Shared.** Host-authoritative full overwrite via `PLAYER_REP_SNAPSHOT`, plus event-driven `REP_DELTA` and `GUEST_REP_DELTA`. Person and contact reputation is deliberately not synced (cosmetic, and it drifts harmlessly). |
| Faction commission | Host's commission. `CoopCommissionSync` replicates the access so the guest can buy commission-gated military stock; salary and commission bounties stay with the host, and the guest's sign/resign dialog options are removed by the rules.csv rows `CoopStoryChainGate` describes. |
| Story missions / progression | Host-only. `CoopStoryChainGate` publishes "this client is the guest" into sector memory, and the mod's `data/campaign/rules.csv` re-declares the Galatia Academy rows with that extra condition. The chain's state is entirely per-client memory (`$player.metBaird`, `$global.gaKA_completed`, the `$global.ga*_ref` mission references) and none of it is on the wire, so a guest who started it would get a private second storyline. |
| Mission boards (bar, contacts, bounties) | Shared pool, first-come. `CoopMissionBoardSync` and `CoopMissionClaim` (`MISSION_POOL_SNAPSHOT`, `MISSION_CLAIM_REQUEST` / `_ACCEPT` / `_REJECT`); `CoopBarPoolCapture`, `CoopBarPoolInjector` and `CoopBarGenerationSuppressor` do the same for bar events. Bounty payouts to the guest are Phase 34, not built. |
| Market inventory (submarket stock, hireable officers) | **Shared**, host-canonical, keyed by (market, submarket) since Phase 32: open, black, military and the storage locker are four inventories. `CoopMarketSync` snapshots on open (`MARKET_SNAPSHOT`) and the guest applies it as a full replacement, plus per-transaction `MARKET_TXN` deltas. |
| Station storage | *Reversed 2026-09-05 (Phase 32).* Was per-player. Now shared and host-canonical like any other submarket: either player's 5000-credit unlock opens it for both (`CoopStorageUnlock`, `CoopStorageUnlockSync`), stored hulls carry ids of the form `c_<player>_<id>`, apply is a reconcile rather than a wipe, and each game still bills its own monthly fee. |
| Faction-to-faction relationships | **Shared**, host-authoritative (`CoopFactionRelations`, `FACTION_REL_DELTA`). |
| Colonies, industries, ground raids | **Shared** (Phase 24, pulled into V1 on 2026-06-10). One shared player faction, not two. Income is split 50/50 of the local net (`CoopColonyIncome` through `CoopRewardSplitter`, which hardwires the equal split; `coop.incomeSplit` and `coop.lootSplit` ship inert and the registry says so by name). |
| Salvage / exploration loot | Loot lands in the acting player's own cargo. The world entity's consumed state is shared: `CoopSkeletonMutationWatcher` plus the `WORLD_DELTA(CONSUME)` report make the entity disappear on both clients, with a ledger dedup so the echo does not loop. |
| Hyperspace storms, slipstreams, abyss layout | Host-owned in principle, still divergent per client in practice. This is an accepted limitation, not a solved problem; Phase 26 owns the fix and is not built. |
| Time-sensitive event timers | Shared via the sector clock, with `CoopClockReconciler` keeping the guest converged. |
| `PersistentUIDataAPI`, camera, UI tab state, sound | Per-player, local only. |

### Combat decisions

| Aspect | Decision |
|---|---|
| Triggering combat | Either player can engage. The engaging client pilots the battle locally and is authoritative for its outcome; it reports campaign deltas over TCP (`BATTLE_RESULT`) and `CoopBattleResultReconciler` folds them into the host's world. |
| Partner's view | Banners only; see the scope table above. |
| Spoils | The fighter keeps its own XP, salvage, credits and recoveries, applied locally by vanilla `EngagementResultAPI`. There is no split, because every battle has exactly one piloting player. `CoopRewardSplitter` exists but serves colony income, not combat. |
| Faction reputation deltas | The piloting player's combat rep applies to the shared table. The partner gets none. |
| Fleet wipe | *Corrected 2026-08-20.* Vanilla `CampaignState.showShuttleDialog()` already handles wipes: Wayfarer plus Kite at a size-weighted random friendly market, credits `max(old*0.8, 2000)`, officers, skills and rep carried by the engine. The mod adds only `CoopFleetMirror`'s empty-roster guard (a 0-member mirror despawns as `NO_MEMBERS` on any unpaused frame, and `setNoAutoDespawn` does not cover that branch) and a `RESPAWN_PLAYER` banner. The planned Wolf-plus-5k injection would have suppressed the vanilla flow, whose call sites gate on `!isValidPlayerFleet()`. |
| Iron mode | Refused. `CoopIronModeGuard` reads the `isIronMode` field and blocks the session. |
| Campaign speed multiplier | Forced to `CoopFastForwardLock.SESSION_MULT` = 2 (the engine default) for the session, so both clients run identically regardless of local `settings.json`. If a MethodHandles lookup fails, or `-Dcoop.ff.disable=true` is set, the lock degrades to `FALLBACK_MULT` = 1 through public `SettingsAPI` only, which is the old Phase 7 behaviour. |
| Host disconnects mid-combat | *Reversed 2026-06-10.* The freeze, countdown and roll-back protocol was cancelled: the API has no programmatic save **load**, so rollback was never implementable. Instead the result message is discarded and logged loudly, the partner gets a connection-lost banner, and the reconnect grace decides whether the session survives. |

### Session lifecycle decisions

| Aspect | Decision |
|---|---|
| Starting a session | Either a fresh coop campaign or loading an existing one. The desktop launcher (Phase 31) writes connection and world settings to the settings file and produces an invite string; `CoopNewGameDialogPlugin` is the coop-aware New Game dialog, and `CoopLobbyDialog` is the in-campaign lobby with per-player ready state (`READY_STATE`). |
| Version handshake | Exact match on the manifest above; a mismatch shows a diff and refuses. |
| Save file | *Reversed 2026-08-20 (Phase 16).* The `GuestFleetExport` blob and `saves/coop_player_<uuid>.dat` were cancelled. The guest already owns a real save and `CampaignUIAPI.autosave()` is public, so the host's save triggers a coordinated guest autosave (`SAVE_CHECKPOINT`). `autosave()` silently does nothing while a dialog is open, so the checkpoint parks and retries every frame up to `CoopSaveCheckpoint.SAFETY_CAP_MILLIS` (ten minutes), reporting back over `SAVE_CHECKPOINT_RESULT`. The host's save still embeds `coop.guestFleetSnapshot` as its record of what the guest owned. |
| Rejoining | The guest loads its own coordinated save; `coop.campaignId` matches and the seed lock accepts. Starting a new game on the same seed is refused by the Phase 6b lock ("already in flight") unless launched with `-AdoptCampaign`, a fresh-start escape hatch that loses guest progress. `CoopCampaignGuard` warns, never blocks, when the loaded save's campaign id is not the one the invite named. |
| Same-market docking | Serialized. `CoopInteractionGate` admits one player to any interaction at a time, globally, first-come by host receive sequence (`hostSeq`), not by sender wall clock. |
| Concurrent interaction with the same entity | Rejected claims carry `already_claimed_by:<id>`; `CoopClaimWaitTracker` shows the wait, and `CoopRejectTracker` force-closes a dialog that opened optimistically before the host's rejection arrived (Phase 18). |
| Player-to-player transfer | Cargo jettison and pickup (Phase 12d), ships through the shared storage locker (Phase 32), credits through the Coop Options page (Phase 32 addition B). A direct trade dialog stays out of V1: it needs an interaction dialog on the partner's mirror plus an escrow protocol. |
| In-game text chat | None. What shipped in 0.1.2 instead is the `COOP-MARK` log marker: a configurable key (`coop.markKey`, default F11) writes one identical line into both players' logs with a marker number, campaign day, location and sync sequence, plus an optional typed note when the campaign map is free. `CoopMarkService` writes the local line before attempting anything else, so every later step is allowed to fail. |
| Presence indicator | The partner's fleet is always visible on the campaign map regardless of sensor range, in the local player's faction colour, labelled `partner <Name>`. Vanilla prefixes player-faction fleets with the article "Your" and that label is unrenamable, which is why the partner form reads the way it does. |

### Out of scope for V1

Each has a phase number in the plan. None are built.

- Joint piloting: Phase 33 (AI-ally battles) then Phase 22 (tactical orders). 22 is gated on 33 being built and smoke-passed.
- Hyperspace and abyss ambient replication: Phase 26, ordered first among post-V1 work.
- Guest-side bounty payouts: Phase 34.
- Finer time control, including a guest fast-forward intent policy: Phase 25.
- Three or more players: Phase 27. The wire is ready; the clamp is not lifted.
- PvP, cross-version play, converting an existing solo save: no plan.
- Nex or broad mod compatibility: no plan. The handshake demands identical mod lists.

---

## 2. Design principles

These are the lens for resolving future ambiguity. All five still hold in the shipped code.

### 2.1 Function-richness is the goal, bounded by cost

Prefer building a feature over cutting it when the cost is bounded. Colonies were the test case: deferred to v3 in the original scope, then measured and pulled into V1 as Phase 24 on 2026-06-10, because one shared player faction turned out to be much cheaper than two.

### 2.2 PvE, not PvP

Both players are friends. No fairness arbitration, no anti-cheat, no contested-action mechanics. A voice channel is assumed for coordination, which is also why there is no text chat.

### 2.3 Host-authoritative for choices

Where a choice needs arbitration, the host decides and the guest reconciles. No voting or consent UI exists anywhere in the mod.

### 2.4 Seed-sync makes initial convergence cheap; dynamic divergence is expensive

Both clients run identical seed-based worldgen at session start. That gives the same sector structure without serializing `Sector` over the wire. It does not make the campaign deterministic: 0.98a-RC8 has gameplay-visible unseeded sites in open scripts (`Math.random()`, `new Random()`, `Misc.genRandomSeed()`). So the default is shared host-authoritative outcomes, not per-client recomputation.

This principle drove these decisions and reversals: reputation shared (originally independent); mission and bar boards shared first-come, over per-player pools that would have meant forking mission generation; commission host-owned; storage shared (reversed 2026-09-05, the same logic applied one layer deeper); hyperspace terrain host-owned in principle, though Phase 26 has yet to make that true in practice.

### 2.5 Replicate, don't recompute

The host owns event outcomes; the guest receives them and never re-rolls. Applied to NPC fleet inflation, bounty levels, encounter spawns, mission and bar generation, market stock, faction relations, colony state.

### 2.6 Adopt prior art aggressively, and drop it when it stops paying

The original plan was to depend on tomatopaste's CMC for joint combat. *Revised 2026-09-05:* the Phase 22 source pass (`docs/PHASE22_TACTICAL_FEASIBILITY.md`) found that tactical orders over your own joined ships are reachable through public API, using the side-0 ally task manager, admiral silence, a GL panel and input handling. The joint-combat track was therefore rescoped away from CMC's obfuscated-internals dependency and onto a host-engine joint battle. Nothing from CMC ships in this mod.

---

## 3. Prior art

> **History.** This is the record of what was read before writing code in May 2026. Neither project is a dependency of the shipped mod; one idea was taken from each.

### 3.1 CMC, Cooperative Multiplayer Combat (tomatopaste / automatopaste)

- Forum thread: https://fractalsoftworks.com/forum/index.php?topic=11598.0 (v3.10, 2026-05-02, targets 0.98a-RC8). Cloudflare-gated.
- Source: https://github.com/automatopaste/Multiplayer. Companion library: https://github.com/automatopaste/CMUtils.

Combat only, no campaign. Server-authoritative per-ship state replication with client-side interpolation; Netty 4.1.69, TCP for handshake and lobby, UDP for deltas; server tick 60 Hz, client input 30 Hz. Activation trick: name a ship `player2` in refit and it becomes the joinable one. Known limits: the `MPDefault*AIPlugin` classes are empty stubs, there is no UDP loss recovery, and it reaches into obfuscated combat entity classes (`com.fs.starfarer.combat.entities.{BallisticProjectile, MovingRay, Missile, DamagingExplosion}`), so it breaks on version bumps.

**What was taken:** the per-ship sync payload shape, as a reference for what a combat state stream has to carry. **What was not:** the dependency. See §2.6.

### 3.2 Matlabmaster's Multiplayer (campaign POC, unfinished)

- Source: https://github.com/moi75ts/Multiplayer. Fork with docs: https://github.com/kirpoly/Multiplayer_Starsector, whose `docs/Multiplayer_Mod_Documentation.md` actually describes tomatopaste's combat mod rather than the campaign side.

**What was taken:** matching seed at game start, so both clients re-run worldgen instead of serializing `Sector`. That one idea removed the worst sync problem and is still the bootstrap this mod uses (§4.4). Its own campaign sync was ad hoc and never finished.

---

## 4. Architecture summary

### 4.1 Network topology

Two-player star topology with one designated host and no relay server. `CoopNetService` owns a listening TCP socket, one shared UDP `DatagramChannel`, the session token and the sender id; per-peer state is a `CoopPeerLink` in a table sized by `coop.maxGuests`.

- **TCP control channel**, reliable, newline-delimited JSON: lobby, handshake, seed lock, battle messages, market snapshots and transactions, claims, options, saves. Reliable one-shot messages are acknowledged (`RELIABLE_ACK`), kept until the ack returns, and resent after a reconnect, so they land exactly once.
- **UDP state stream**: `FLEET_SNAPSHOT` and `NPC_FLEET_MOTION`, at a cadence `CoopCadenceController` chooses per link from p50 RTT, loss and outbound backlog. `CoopCadenceTier` defines `FLOOR` (5 Hz), `DEFAULT` (10 Hz) and `TOP` (20 Hz); `TOP_TIER_ENABLED` is `false`, so the ladder stops at 10. Intervals are measured in game time, so under fast-forward the wall-clock send rate rises with the FF factor and the receiver's interpolation buffer keeps the same depth in game seconds.
- **TCP fallback**: when UDP is blocked outright, state datagrams are wrapped over the control channel and the cadence pins to the floor.
- **Inbound UDP filter**, in order: source address pinned from the established TCP connection, then a prefix-only envelope parse (`CoopMessages.parseDatagramHeader`), then the session token. Each rejection increments a counter in `datagramStats()` and warns at most once per reason, so a flood is visible without being able to write the log.
- **Return-address validation**: a token-valid datagram from a new source is accepted inbound (the watermark defeats replay) but only becomes a candidate send target after a `PATH_PROBE` nonce round trip, which is QUIC's `PATH_CHALLENGE` model (RFC 9000 section 8.2). An off-path attacker cannot redirect the stream; a genuine NAT rebind recovers within one round trip.
- **Reachability tiers** (`docs/player/CONNECT.md`, `CoopPortMapper`, `CoopConnectionDoctor`): tier 0 a VPN pseudo-LAN, tier 1 IPv6, tier 2 a manually forwarded port, tier 3 UPnP or NAT-PMP done by the game. Only the host needs to be reachable. Tier 0 cannot be auto-detected; every other tier shows up in the `tier reached` log line.
- **Password**: `coop.password` gates `LOBBY_HELLO`, including during a reconnect grace, so a stranger cannot end the host's wait early.

Everything is non-blocking and pumped from `CoopNetPump.advance()` on the campaign thread. There are no mod threads, because mod-created threads have been observed dying mid-session in this codebase.

### 4.2 Authority model

| Subsystem | Host | Guest |
|---|---|---|
| Campaign clock | drives, applies the effective pause | follows `TIME_SNAPSHOT`, converged forward-only by `CoopClockReconciler` |
| Pause | computes `effectivePaused` as the OR of host intent, guest key intent, guest screen intent and either-in-combat | sends `PAUSE_INTENT`, never calls `setPaused` to drive divergence |
| Fast-forward | owns the `CampaignState.fastForward` field | receives the bit on `TIME_SNAPSHOT` and writes it through `CoopFastForwardLock` |
| NPC fleets, markets, intel, economy, faction relations, colonies | authoritative | replicated, with local spawners suppressed |
| Host's player fleet | authoritative | rendered as an AI-mode mirror |
| Guest's player fleet | rendered as an AI-mode mirror, and exposed to the forks through `CoopPresenceRegistry` | drives locally, sends `FLEET_SNAPSHOT` |
| Combat | pilots its own battles; integrates the guest's reported `BATTLE_RESULT` | pilots its own battles; reports the result |
| Shared reputation | authoritative (`PLAYER_REP_SNAPSHOT` overwrite plus deltas) | applies the host's broadcasts |
| Save | owns the canonical save | takes a coordinated autosave on the host's `SAVE_CHECKPOINT` |
| Interactions | arbitrates every claim | mirrors the host's accept and release decisions |

### 4.3 The two-fleet trick

On each client `Global.getSector().getPlayerFleet()` is that client's own fleet. The partner is a `CampaignFleetAPI` maintained by `CoopFleetMirror`, tagged `$coopMirrorFleet`, with `setAIMode(true)`, the remote player's faction colour and always-visible presence styling from `CoopPresenceIndicator`. The same class serves NPC mirrors (tagged `$coopNpcFleetId`, real faction, normal sensor visibility).

Two details are load-bearing and differ from the original sketch:

- **Motion is kinematic, not steered.** *Reversed 2026-09-03 (Phase 29 M1):* `setMoveDestinationOverride` is never called on a mirror. Received samples queue in a `CoopMotionInterpolator` and `advanceMotion` places the fleet on the buffered trajectory about 200 ms behind the sender. Engine steering ran under the mirror's own movement stats and arrival radius, which produced two measured teleport modes at roughly 1 Hz.
- **The engagement shield is re-asserted every frame.** `assertEngagementShield()` refreshes `setNoEngaging` because the engine's fader expires after about a second. The mirror never starts combat itself; real engagements happen on the authoritative copy.

Roster rebuilds happen only when the snapshot's `fleetHash` changes.

### 4.4 Matching-seed worldgen

`CoopSectorProcGen` extends the engine's `SectorProcGen` and applies the shared seed to `CharacterCreationData` in both `prepare` and `generate`, then forces the seed string onto the sector afterwards. `CoopSeedSync` persists `coop.seedLong`, `coop.seedString`, `coop.sectorFingerprint`, `coop.campaignId` and this save's own player id; `CoopSectorFingerprint` computes the structural fingerprint that both clients compare, scoped to deterministic content (hidden dynamic base markets are excluded).

The campaign id is what distinguishes "the same campaign, resumed" from "a fresh re-roll of the same seed", because the seed string and the fingerprint are pure functions of the seed and match in both cases. It is minted once by the host at first seed lock and adopted by the guest.

Seed sync is a bootstrap only. Everything dynamic afterwards is a host-authored delta.

### 4.5 Campaign to combat bridge

Solo own-fleet model. The player who engages runs and pilots the battle on their own client. The other player is held by the shared pause and receives banners.

The "both fleets in one battle" case from the original design does not arise, because `CoopNpcThreatWatcher` intercepts before contact. Vanilla pursuit is not an assignment change: `TacticalModule` steers with `fleet.setMoveDestination(...)` after storing the quarry in its own `target` field. The watcher reads that signal and issues `ENGAGE_GUEST` so the guest opens the battle locally.

```
Player X's fleet is engaged, or X initiates
  |
  v
X asserts the shared pause (host: applies it; guest: sends PAUSE_INTENT)
X opens the battle locally and pilots it
  |
  v
Partner Y:
  - campaign frozen by the shared pause
  - gets a "battle started" banner
  - receives BATTLE_STATUS every 400 ms and logs it at debug
  |
  v
On combat end (EngagementResultAPI on X's client):
  - X applies its own result locally: XP, salvage, credits, recoveries
  - X sends BATTLE_RESULT over reliable TCP
  - CoopBattleResultReconciler applies the campaign deltas host-side:
    destroyed/damaged NPC fleets, shared faction rep
  - X releases the shared pause; Y gets the end banner with survivor counts
```

If the result never arrives, it is discarded with a loud log line and the partner gets a connection-lost banner. There is no rollback: the API has no programmatic save load.

---

## 5. Starsector moddability baseline

### 5.1 What is open and what is closed

Verified against the 0.98a-RC8 install at `K:\Starsector`.

**Available to mods:**
- `starsector-core/starfarer.api.zip`, 2,034 zip entries of which 1,947 are `.java` files: the full modding API as source.
- `starsector-core/data/scripts/world/SectorGen.java` and `data/scripts/plugins/LevelupPluginImpl.java`: the base game's own sector gen and level-up logic as raw editable source.
- `starsector-core/data/`: CSV and JSON for ships, weapons, hullmods, systems, factions, missions.
- `starsector-core/janino.jar` and `commons-compiler.jar`: the in-game compiler for `data/scripts`.
- `graphics/` and `sounds/`.

**Closed:** `starsector-core/starfarer_obf.jar`, the obfuscated engine (rendering, combat sim, AI internals, campaign loop, serializer). Class paths use very long names built mostly from `O` characters, which defeats plain filesystem extraction on Windows. Decompilable for understanding with CFR or Procyon; no decompiled code is redistributed.

### 5.2 Class override: what works

**The mechanism that shipped is a classpath prepend.** `jars/coop-forks.jar` goes in front of `starfarer.api.jar` on the `vmparams` `-classpath`, so the JVM resolves the mod's copy of any shared FQCN first. The launcher does this edit, with a backup of `vmparams`.

**The `data/scripts/com/fs/starfarer/api/...` override path does not work.** A spike against `AccretionDiskGenPlugin` produced no log evidence the forked source was ever compiled: Starsector's `ScriptStore` (a Janino `JavaSourceClassLoader`) delegates parent-first, so the API jar always wins.

**Reflection by name is impossible** (obfuscated names are unstable) **and `java.lang.reflect` is blocked outright at runtime** by the script classloader, along with `java.io.*` and `java.nio.file.Files`. Such code compiles and passes unit tests and then throws in-game. Field access goes through `java.lang.invoke.MethodHandles` with a lazy resolve and a `Throwable` catch; `CoopBarSync.resolveHandles()` is the pattern every later user copied.

**A Java agent was investigated and rejected.** It stays in reserve as an escape hatch. See §7.2.

### 5.3 Simulation timing model

- Variable dt per frame, not a fixed tick. `advance(amount)` is called once per render frame with elapsed real seconds.
- Default frame cap 60 FPS (`settings.json: frameRateLimit`). Pause means `advance` is not called.
- Fast-forward means the same tick rate with a larger `amount`, scaled by `campaignSpeedupMult`.
- `CampaignClock.advance(float)` does `cal.add(Calendar.SECOND, (int)(...))`, truncating the fractional calendar-seconds every frame. Two clients therefore drift apart at a shared 1x with zero network fault: about 0.2 game-days over two idle hours, about 2 game-days over a dialog-heavy hour, plus roughly 200 ms plus RTT per pause mirror edge. Prevention is impossible; §8.2 covers the reconciler.

---

## 6. API surfaces the mod uses

The original version of this section listed line numbers from an extracted API source tree. Line numbers age badly and cannot be checked from the repo, so this is now a map from engine surface to the coop class that uses it.

### 6.1 Mod plugin lifecycle

`CoopModPlugin` extends `BaseModPlugin`: `onApplicationLoad()` republishes launcher-written settings as system properties, `onGameLoad(boolean)` installs the pump, `configureXStream(XStream)` registers the save-visible types. `Global.getSector()` and `Global.getCombatEngine()` are the singletons; the combat engine is null outside combat.

### 6.2 Per-frame hooks

- `EveryFrameScript` with `runWhilePaused() == true`: `CoopNetPump` and every subsystem it drives. Registered as a **transient** script (`addTransientScript`), never a persisted one, because transport objects must not enter the save.
- `EveryFrameCombatPlugin`: `CoopBattleStatusCombatPlugin` captures the battle snapshot on the engaging client.
- `CampaignInputListener`: `CoopCampaignInputBlocker` (control names are enum constants on the obfuscated control enum, so the pause control is `GENERAL_PAUSE`, not `PAUSE`; a wrong name throws `IllegalArgumentException` in-game while passing mocked tests), plus `CoopHostPauseInputListener` and `CoopMarkInputListener`.
- `CampaignUIRenderingListener`: the screen-space HUD (`CoopLinkHud`, `CoopHudNotice`), drawn with `CoopBitmapFont` over a vanilla `.fnt` and raw GL11. No LazyLib dependency.

### 6.3 Time control

`SectorAPI.setPaused(boolean)` / `isPaused()` is the whole public surface used for pause. Fast-forward is **not** driven through `setFastForwardIteration`: `CoopFastForwardLock` forces vanilla's toggle-mode input mode on and writes the `CampaignState.fastForward` field through MethodHandles, because in the default hold-Shift mode `CampaignState.processInput` re-polls the raw key every frame and clobbers any value a mod writes. `campaignSpeedupMult` is set through public `SettingsAPI`.

### 6.4 Combat read and write

`CombatEngineAPI.getShips()` and the related collections feed `CoopBattleStatus`; destroyed ships leave `getShips()` entirely, so the kill feed is computed as a set difference between consecutive snapshots. `setPlayerShipExternal` and the projectile-level collections are unused, because no combat state is replicated for rendering.

### 6.5 Campaign state read and write

- `SectorAPI.getPersistentData()` is the save-visible map, used by roughly twenty call sites (`coop.seedLong`, `coop.campaignId`, `coop.guestFleetSnapshot`, the session stats, the options policy).
- `getMemoryWithoutUpdate()` carries the guest flag that `rules.csv` reads (§1, story gate) and the `$coopDebug` diagnostics flag.
- `CampaignFleetAPI`: `setLocation`, `setVelocity`, `setAIMode`, `setNoEngaging`, `getFleetData`, `getContainingLocation`. `setMoveDestinationOverride` appears once, in a comment explaining why mirrors do not use it.
- `CharacterCreationData.getSeed()` / `setSeed(long)` is what actually seeds procgen; `SectorAPI.setSeedString` is metadata and is set for save and debug purposes only.

### 6.6 Combat entry

`InteractionDialogAPI.startBattle(BattleCreationContext)` and `CampaignUIAPI.startBattle(...)` open battles; `CoopEngageDialogStaging` and `CoopCustomsDialogStaging` synthesize the dialog the guest needs when the host hands an engagement over.

### 6.7 Listeners

`CampaignEventListener` through `CoopCampaignEventListener` (battle events, fleet spawn and despawn, market transactions, dialog shown, reputation changes, economy ticks). The `campaign/listeners/*` package supplies the narrower interfaces registered through `Sector.getListenerManager()`.

### 6.8 Persistence

`configureXStream` plus `getPersistentData()`. Save-visible types are plain mutable beans rather than records, because XStream 1.4.10 does not handle records. Transport objects are never stored.

### 6.9 UI surfaces

Verified in the 0.98a API source: there is **no title-screen API**, which is why every launch-tier setting is a file or a `-D` and the launcher exists at all. What does exist and is used: `showConfirmDialog` and `showMessageDialog`; custom widget panels inside dialogs (`VisualPanelAPI.showCustomPanel(width, height, plugin)`, used by `CoopLiveDialogLine` for numbers that change while a dialog is open, because writing to `TextPanelAPI` once a second makes the panel visibly blink); intel `createLargeDescription` pages (`CoopSessionIntel`, `CoopOptionsPage`, `CoopSessionStatsIntel`); `CampaignUIAPI.addMessage` banners; and `setDisallowPlayerInteractionsForOneFrame()`. Dialogs are exclusive, which is the source of the suspend-logic trap in §8.5.

---

## 7. Determinism strategy

### 7.1 The two layers of randomness

**Layer A, the obfuscated engine:** combat sim noise, damage variance, particle positions, AI noise. Closed.

**Layer B, open game-logic code** in `data/scripts/` and `starfarer.api.zip`: fleet generation, salvage, encounters, market generation, worldgen. Some paths use seeded `Random`; 0.98a-RC8 also has gameplay-visible unseeded sites. Do not assume Layer B is deterministic unless the call path has been audited.

### 7.2 Why no Java agent

- Layer A does not need to be deterministic across clients. Each battle runs on exactly one machine and its outcome is replicated as data, so combat RNG only has to be self-consistent on the machine that ran it.
- Layer B is open enough to audit and override by FQCN through the classpath prepend, which is a narrower tool with a shorter failure mode than instrumentation.
- An agent is weeks of reverse engineering plus ongoing maintenance and still does not solve threading, hash-order or float-op non-determinism.

Verdict held: agent in reserve only, and it has not been needed.

### 7.3 Replicate, don't recompute

The primary strategy, expressed on the wire.

- **NPC fleets**: the host sends `NPC_FLEET_SET` (full set) and `NPC_FLEET_MOTION` (high-frequency positions). The guest never calls `FleetInflater`. `CoopNpcFleetSuppressor` stops guest-side spawners from creating fleets of their own, with a coverage diagnostic to catch spawners it does not yet know about.
- **Salvage and exploration**: own-action. The acting player resolves it locally with vanilla and keeps the loot; only the world delta (this entity is consumed) is reported, integrated by the host and re-broadcast, with a ledger dedup.
- **Bar events, mission offers, market stock, hireable officers**: host-authored pools with first-come claims. The item lands in the acting player's own cargo.
- **NPC-initiated dialogs (customs, inspection)**: `DIALOG_BEGIN` from the host, resolved by the guest against its own cargo, with rep and fleet deltas reported back.
- **Self-healing backstop**: the host re-broadcasts authoritative state continuously (NPC set, economy, rep, faction relations), so most un-enumerated rules-dialog divergences self-correct on the next rebroadcast. V1 deliberately does not enumerate every `rules.csv` `CommandPlugin`; explicit replication exists only for guest-driven outcomes the host cannot otherwise observe, funnelled through `WORLD_DELTA`.
- **Worldgen**: same seed, then a structural fingerprint comparison, then host-authored deltas (§4.4).

### 7.4 Matching-seed worldgen, and how the forks see the seed

The seed the forks read is **not** an API call. `CoopRandom` lives in `coop-forks.jar` and is loaded by the system classloader alongside `starfarer.api.jar`, so it cannot see anything in `coop.jar`. It reads `-Dcoop.newGameSeed`, the JVM property the launch scripts and the launcher set. `CoopPresenceRegistry` has the same constraint and the same reason, which is why it lives in its own package.

Seed derivation: SHA-256 over UTF-8 text, first 8 bytes big-endian, sign bit masked, because vanilla `SectorProcGen.prepare` skips `setSeed` when the seed is `<= 0`. Per-topic streams hash `seedString \0 topic \0 key0 \0 key1 ...`. `CoopRandom.ofOrDefault(topic, keys...)` returns a plain unseeded `new Random()` outside a coop session, so a non-coop launch is byte-for-byte vanilla, including from a forked static initializer.

### 7.5 The forks that shipped

*Superseded 2026-05-29 and again 2026-09-03.* The original plan here was a long list of Layer B files to fork for RNG seeding, graded HIGH / MEDIUM / LOW. Most of that list is **retired**: replication (§7.3) made the fleet, economy and combat entries unnecessary, and each retired fork is one less file to re-apply on an engine update. Ten files are forked, in two families.

**RNG determinism, so both clients generate the same one-time world content:**

| Fork | Why |
|---|---|
| `util/Misc.java` | The static `random` field and `genRandomSeed()` (which used `System.nanoTime()`). One file covers a large number of call sites. |
| `impl/campaign/world/GateHaulerLocation.java`, `NamelessRock.java` | One-time deep-space content whose placement must match. |
| `impl/campaign/enc/AbyssalRogueStellarObjectEPEC.java` | Abyss exploration content; reseeds the encounter's RNG from an independent session-seeded stream. |

**Guest presence, so the host engine treats the guest as a player:**

| Fork | Why |
|---|---|
| `impl/campaign/fleets/RouteManager.java` | Spawn and despawn tests become "nearest player" rather than "the host", and `daysSinceSeenByPlayer` resets for fleets the guest sees. |
| `impl/campaign/fleets/SourceBasedFleetManager.java`, `DisposableFleetManager.java` | Same min-over-players change, so the system the guest is in keeps its population. `DisposableFleetManager` also fixes a spawn position set synchronously by the AI constructor. |
| `impl/campaign/fleets/PlayerVisibleFleetManager.java` | A fleet the guest can see is being watched by a real player and must not be culled. |
| `impl/campaign/intel/events/DisposableHostileActivityFleetManager.java`, `impl/combat/threat/DisposableThreatFleetManager.java` | The same rule for the two event-driven managers. |

Every edit is tagged `COOP FORK` inline and every file carries an audit header. `CoopPresenceRegistry` is one static slot defaulting to null: null means "no second player" and every guarded fork behaves exactly as vanilla, which is what makes solo play, the guest side and a launch without `coop-forks.jar` provably unchanged. The slot holds a live engine entity, is re-asserted every tick, released on the first frame the assert stops arriving, and is never persisted.

Re-applying on an engine update: copy the vanilla source over byte-identically, re-apply only the `COOP FORK` hunks, bump `CoopPresenceRegistry.PINNED_VERSION`.

### 7.6 Residual divergence, as observed

The theoretical list (identity hashes, hash iteration order, float ops, thread interleaving) never became the practical problem. What actually diverges in play is enumerated in `docs/player/LIMITATIONS.md` and `docs/starsector-runtime-limitations.md`: hyperspace storms, star flares and slipstreams differ per client (Phase 26); sensor ghosts and deep-space content are host-only; per-engine commodity and shortage display can differ at a colony, with the host canonical; `SystemBountyManager` state diverges (Phase 34).

The policy that held: document a divergence class when first observed, decide whether it is a defect or vanilla behaviour, and only then decide whether to fix it. Do not pre-emptively fight theoretical cases.

---

## 8. Subsystem-by-subsystem, as built

### 8.1 Net pump

`CoopNetPump` is the transient `EveryFrameScript` with `runWhilePaused() == true` that drives everything: the outbound queue, the inbound dispatch, the reliable-message ledger, the reconnect state machine, the port mapper and every subsystem below. `CoopNetService` is the socket layer (§4.1). `CoopNetPumpInstaller` puts it in place on game load.

The flat JSON envelope has one hard constraint: **the parser has no array support**, so message payloads must not contain JSON arrays. Multi-element data is encoded as a single delimited string through `CoopDelimited` (unit separator, with escaping).

### 8.2 Time arbitration

Three pieces, added in this order.

- **`CoopTimeLock` (Phase 7)**: the host captures pause and fast-forward state into `TIME_SNAPSHOT` every 200 ms; the guest applies it. `CoopCampaignInputBlocker` intercepts `GENERAL_PAUSE` and `FAST_FORWARD`.
- **`CoopSharedPauseCoordinator` (Phase 11)**: `effectivePaused = hostPauseIntent || guestKeyPauseIntent || guestScreenPauseIntent || eitherInCombat`. The two guest intents differ: a **key pause** is casual and the host may override it, a **screen pause** (map, fleet, character, refit, cargo, intel, menu, dialog) cannot be overridden, because it exists so the guest can read and plan without the world running on. The guest keeps no sticky copy of the key bit and resolves each press against the observed pause state, so a host override can never leave the two out of sync.
- **`CoopClockReconciler` (Phase 7c)**: converges the guest's clock onto the host's, under three rules. Guest-only, since the host clock is authoritative. **Never backward**, because the engine's month-end fires on `getMonth() != prevMonth`, an inequality, so a backward write across a month boundary pays monthly income twice while a forward jump fires at most once. **Slew while running, snap only when quiescent**: unpaused corrections are bounded to a fraction of the frame's own advance so monotonicity holds by construction, and full snaps happen during a shared pause or, with a persistence gate and a loud log, when unpaused drift exceeds two game-days.

`CoopFastForwardLock` (Phase 7b) is described in §6.3 and §1.

### 8.3 Campaign state replication

`CoopCampaignReplicator` is the largest single class and owns the host-to-guest campaign stream: market snapshots and transactions, reputation, faction relations, intel, economy ticks, world deltas, colony messages, credit grants. `CoopMarketSyncGate` decides when a snapshot is owed. `CoopOrbitSync` resolves the jump-point orbit non-determinism found in Phase 8 by matching id first and resetting the full orbit.

Guest to host: `FLEET_SNAPSHOT` for its own fleet, interaction claims, `WORLD_DELTA` reports, `BATTLE_RESULT`, `GUEST_REP_DELTA`.

### 8.4 Fleet mirroring

See §4.3. `CoopFleetMirrorRegistry` tracks the live mirrors, `CoopMirrorOrphanSweeper` removes mirrors whose source is gone, `CoopNpcActionTextCapture` replicates the action line vanilla resolves in `StandardTooltipV2` so a mirrored NPC fleet does not show a blank one, and `CoopSensorSync` carries the visibility model (detection is `dist <= observer.getMaxSensorRangeToDetect(target)`).

### 8.5 Interaction gating

`CoopInteractionGate` (§1). Two traps are worth keeping in view because both produced live bugs:

- The guest's `CoopCampaignInputBlocker` must be **suspended** while a blocking screen or dialog is open, or the guest is trapped with no options and a dead Escape key.
- The guest must not force `setPaused` while its own interaction dialog is open, and pause must be applied only on change. Doing otherwise froze the trade-tab exit and produced blank option lists.

### 8.6 Combat handoff

`CoopBattleBridge` owns both halves (§4.5). `CoopPreBattleAutosave` takes the pre-battle checkpoint, deferring while a dialog is open for the same engine reason as the coordinated save. `CoopNpcThreatWatcher` is the pre-contact handoff. `CoopBattleResultReconciler` applies the reported deltas.

One accepted hole closed in Phase 13 and re-verified since: a mirror must not be engageable. The gate is `driveMovement`'s `setNoEngaging` and `canBeEngaged`, and the pull-in path bypasses it, so the mirror also carries a per-frame shield, the `FLEET_IGNORES_OTHER_FLEETS` flag and a load-bearing `leave()`.

### 8.7 Partner battle reporting

*Renamed from "Spectator combat", reversed 2026-08-19.* What ships is two banners plus a `BATTLE_STATUS` stream the spectator logs rather than renders. The stream and codec were kept deliberately, so the panel can come back without re-deriving the wire format. `CoopBattleBridge.STATUS_INTERVAL_MILLIS` is 400 ms and the silence timeout is 75 intervals.

### 8.8 Joint combat

Post-V1, and no longer a CMC integration (§2.6). The track is Phase 33 then Phase 22: real losses, an owner-side toggle ability, deploy everything, no spoils split. `CoopAllyPullInSpike` is the on-main debug switch (`-Dcoop.debug.allyPullIn`) for the live feasibility run.

### 8.9 Save and reload

`CoopSaveCheckpoint` (§1), `CoopGuestSnapshot` and `CoopGuestSnapshotFactory` for the `coop.guestFleetSnapshot` blob, `CoopSaveIndex` and `CoopSaveIndexSchema` for the launcher's save finder (the launcher must stay free of Starsector API types, so the schema is shared as plain data), `CoopCampaignGuard` for the wrong-save warning.

Guest saves are co-op only: the scripts that generate the world are not in them, so opening one without a host is unsupported.

### 8.10 UI and lobby

- **Launcher (Phase 31)**: a dark FlatLaf desktop app. Invite with seed and world settings, every `-Dcoop.*` flag in Advanced, the install check and Fix button (including the `vmparams` classpath edit with a backup), a connection check, a bug-report ZIP, and an update check. It reads `releases/latest`, which is why releases are never marked prerelease.
- **In-campaign lobby (Phase 21)**: `CoopLobbyDialog`, a plain interaction dialog because `advance()` ticks every frame while a dialog is open, the option panel can be rebuilt in place, and the text panel has `clear()`. Per-player ready state, a two-stage start gate.
- **HUD**: `CoopLinkHud` in a configurable corner (`coop.hudCorner`), drawn with `CoopBitmapFont` over GL11.
- **Intel pages**: Coop Session (`CoopSessionIntel`), Coop Options (`CoopOptionsPage`), Coop Stats (`CoopSessionStatsIntel`).
- **Options (Phase 28)**: `CoopOptionsRegistry` is the typed schema, `CoopOptionsStore` the file reader, `CoopOptionsPolicy` the host-authoritative per-campaign tier. The governing rule is **expose preferences, never correctness**: cadences, gate semantics and fingerprint checks are absent by design, and `notConfigurable()` records that list by name so it cannot erode one knob at a time. Precedence is `-D`, then `saves/common/coop_options.json.data`, then the shipped `data/config/coop_options.json`, then the compiled default.

### 8.11 Shared reputation

`CoopRepDelta` plus the host's `PLAYER_REP_SNAPSHOT` overwrite. Faction standings only; person and contact reputation is not synced. The overwrite model is what fixed guest-side drift from transponder-off events.

### 8.12 Hyperspace and dynamic terrain

The design intent is host-authoritative after the seed bootstrap. The shipped state is weaker: storms, flares and slipstreams are each client's own. The Phase 26 review (2026-09-05) corrected two assumptions worth keeping here, because they are why the naive fix does not work:

- Rebuilding a slipstream guest-side through `SlipstreamBuilder` does not give parity, because the builder itself consumes RNG (angle variance, width wiggle, fluctuations). The finished segment polyline has to be replicated instead; `addSegment(Vector2f, float)` is public.
- Abyss encounter points are transient per-player probe points, regenerated in a ring around the local fleet every 1000 units travelled. They are not shared world state. What must replicate is the outcome of each encounter creation, with the point's identity recoverable from position.

### 8.13 Fleet wipe and respawn

Vanilla does the respawn (§1). The mod contributes the empty-roster mirror guard and the `RESPAWN_PLAYER` banner. Observed live 2026-08-19: a guest wipe, with the partner's mirror recovering clean.

### 8.14 Same-dock concurrent UI

*Cancelled as designed, 2026-08-20.* There are no `UI_LOCK_*` messages and no lock classes. The Phase 10 global gate serializes every dialog instead, and the market sync model depends on that: host purchases are not pushed into an already-open guest screen. Phase 18 closed the WAN-latency race where a rejected dialog stayed open.

The post-V1 follow-up, if ever taken, starts by entity-scoping the gate so private screens (own refit, officers, cargo, intel) can run in parallel while shared-state screens stay mutually exclusive. Note that storage is now inside the shared set (§1), so it would be inside the mutex rather than outside it.

### 8.15 Build system

Gradle, four jars (§1). `.\gradlew.bat clean test build` from the repo root, or `scripts\build.ps1`. Worktree builds need `-PstarsectorCore=K:\Starsector\starsector-core`. Deploy with `scripts\deploy-to-test-clients.ps1`, which copies the whole mod including `data/` into `K:\Starsector-coop-test\host` and `\guest`; never hand-copy jars. `README_DEV.md` has the full commands.

### 8.16 Shared-faction colonies (Phase 24)

One shared player faction. `CoopColonySync` replicates the colony set and its lifecycle, `CoopColonyManagement` mirrors the management screen both ways, `CoopColonyIncome` splits the local net 50/50 at month end with each side deducting its own half, `CoopRaidOutcomeSync` covers raids in both directions, and `CoopExpeditionWarning` plus its intel entry is coop-owned rather than vanilla-owned. Per-player factions were rejected.

### 8.17 Shared markets, storage and credits (Phase 32)

Market stock is keyed by (market, submarket): open, black, military and the storage locker. Storage apply is a reconcile rather than a wipe, so a concurrent local change is not destroyed; hull ids are `c_<player>_<id>`; credits refund on discard. Pirate and Luddic Path hidden bases share their shop, and their traffic is held until both sides have the base paired, because each game names and orbits its base itself. `CoopCreditTransfer` is the send-credits row: the money leaves on send, lands once, and a transfer made while the link is down goes through on resume or is refunded at session end.

### 8.18 Dev tooling (Phase 30)

`CoopAgentBridge` and `CoopAgentCommands` expose campaign state to `tools/starsector-mcp`, which is how a smoke session is verified without reading the screen. The verification ladder is bridge state first, then log lines, then eyes. `CoopWiretap`, `CoopFrameProfiler` and `CoopOwnFleetProbe` are the opt-in diagnostics; all of it is dormant unless `-Dcoop.debug.diagnostics=true` or the `$coopDebug` sector flag is set.

---

## 9. Risk register

Ordered as originally written, with what actually happened.

| # | Risk | Outcome |
|---|---|---|
| 1 | Campaign to combat handoff bugs, no prior art for this seam | Materialized and was resolved. Phase 14 shipped after five live scenarios, with two in-session fixes (a shield release and a sentinel overflow). Phase 14b then rebuilt the pursuit model after the original one watched the wrong engine signal (§4.5). |
| 2 | State divergence over a long session | Managed by continuous host re-broadcast plus full snapshots on open. The residue is enumerated, not open-ended (§7.6). |
| 3 | Starsector version bumps break an obfuscated-class dependency | Reduced by dropping CMC. What remains version-pinned is the fork set, the MethodHandles field names and the control enum names, all behind the exact-version handshake and one `PINNED_VERSION` constant. |
| 4 | XStream surprises with the guest fleet snapshot | Real, and shaped the code: save-visible types are plain mutable beans, not records. The bigger mitigation was cancelling the custom export format entirely (§1). |
| 5 | Packet loss desyncs state | Handled at two layers: reliable acked TCP for anything that must land exactly once, and adaptive cadence with depth-2 redundancy on the lossy floor for the UDP stream. A confirmed 0.1.0 bug (three messages lost across a drop: a purchase, a 3,000 credit transfer, a 50 supply deposit) is what forced the reliable layer. |
| 6 | Hash and identity divergence | Did not materialize in play. |
| 7 | CMC concurrency races | Moot; CMC is not a dependency. |
| 8 | Nex compatibility | Moot; the handshake demands identical mod lists. |
| 9 | Guest export file corruption | Moot; there is no export file. |
| 10 | Dependency drift | Only FlatLaf, and only in the launcher. The mod itself has no runtime dependency beyond the game. |

The risk the register did not anticipate: **the script sandbox**. `java.io`, `java.nio.file` and `java.lang.reflect` being blocked at runtime while compiling and unit-testing green cost more rework than any item above, and is the reason `CoopPortMapper` is a per-frame NIO state machine rather than twenty lines with a socket timeout.

---

## 10. Research items, answered

- **Bandwidth budget.** Measured rather than estimated. Phase 20.1 found datagrams three to four times over the 1.2 KB budget and put them on a diet: roster split, range filter, sensor change flags, short tokens, quantized positions. The state stream now runs at 5 or 10 Hz (§4.1), not the 60 Hz the combat-spectator design assumed.
- **CMC licence and 0.98a compatibility.** Not needed. CMC is not a dependency (§2.6).
- **Performance impact.** `CoopFrameProfiler` exists for this and the answer has been "not the bottleneck" every time it was run. The measured cost that did matter was per-frame work in the mirror path, fixed by kinematic interpolation (§4.3).
- **Mod checksum manifest.** Answered: `Global.getSettings().getModManager().getEnabledModsCopy()` plus checksums, assembled by `CoopHandshakeManifest`. `enabled_mods.json` is not read at runtime.
- **XStream config.** Answered: register through `configureXStream`, and keep save-visible types as plain mutable beans (XStream 1.4.10 does not handle records).
- **Two-instance testing on one PC.** Solved by `scripts\deploy-to-test-clients.ps1` into `K:\Starsector-coop-test\host` and `\guest`, launched with `scripts\launch-host.ps1` and `scripts\launch-guest.ps1`, with `-Diagnostics -Bridge` for a verified session. Network conditions are shaped with clumsy 0.3 on the loopback port.

---

## 11. Execution order that shipped

The plan's **Phase Status Ledger** is the authoritative record, phase by phase, with dates and commits. What follows is only the shape of the order, for orientation.

Foundations first (1 to 6b): mod skeleton, transport, seed lock with campaign identity, time lock, mirror fleet, shared reputation, interaction gate. Then the campaign layer (8 to 12d): fleet and NPC replication, markets, missions, bar pools, world deltas, item transfer. Then combat (13 to 15): the suppressor and forks, the battle bridge, the pursuit handoff, result reconciliation. Then sessions (16 to 18): coordinated saves, wipe handling, the claim-race force close.

Two blocks were pulled forward out of "v2 or v3" into V1 on user decision: **colonies** (Phase 24, 2026-06-10) and **player-facing docs plus options plus the launcher** (Phases 23, 28, 31, 2026-09-03). Networking (Phase 20) and adaptive cadence (Phase 29) landed in early September, followed by the lobby (21) and the shared-market and storage work (32).

Release 0.1.0 shipped 2026-09-05, 0.1.1 on 2026-09-15 after five two-player test sessions, 0.1.2 on 2026-09-19 with the log marker. **Phase 19, the final sign-off, is still the last unbuilt V1 item.**

The original estimate in this section was 8 to 13 weeks part-time to a private playable prototype. Actual: first commit to 0.1.0 was roughly fourteen weeks, with the scope grown by colonies, the launcher, WAN networking and the options system, none of which were in the estimate.

---

## 12. Still open

Each item names the phase that owns it. Anything not listed here is either built or has no plan (§1).

- **Joint piloting.** Phase 33 (AI-ally battles) then Phase 22 (tactical orders over your own joined ships). 22 is gated on 33 being built and smoke-passed. The live `-Dcoop.debug.allyPullIn` spike run has not happened yet.
- **Hyperspace and abyss ambience.** Phase 26, milestones 1 and 2 in scope, milestone 3 and the storms stretch still open decisions. Ordered first among post-V1 work, after the Phase 19 sign-off.
- **Guest bounty payouts.** Phase 34. Person and system bounties replicated to the guest, paid from the reconciled battle result through a pre-reconcile hook and `CREDITS_GRANT`.
- **Finer time control.** Phase 25. The `FF_INTENT` message and a guest fast-forward policy row; the key is already inert in the options registry.
- **Three or more players.** Phase 27. The wire is N-ready; the `coop.maxGuests` clamp and the gameplay questions are not.
- **Concurrent docking.** Post-V1 and unowned by a phase number; it starts by entity-scoping the interaction gate (§8.14).
- **Hidden-base names on discovery.** Open question from the Phase 24 smoke: each engine names its own hidden bases, so the two can disagree on a name the players say out loud.

---

## 13. Glossary

- **CMC**: Cooperative Multiplayer Combat, tomatopaste's combat-only mod ([§3.1](#31-cmc-cooperative-multiplayer-combat-tomatopaste--automatopaste)). Read as prior art, not used as a dependency.
- **The two-fleet trick**: the local player fleet is real, the partner is an AI-mode `CampaignFleetAPI` driven by interpolated snapshots ([§4.3](#43-the-two-fleet-trick)).
- **Matching-seed worldgen**: both clients regenerate the same sector from a shared seed at session start, then compare a structural fingerprint ([§4.4](#44-matching-seed-worldgen)).
- **Replicate, don't recompute**: the host owns outcomes, the guest applies them and never re-rolls ([§7.3](#73-replicate-dont-recompute)).
- **Solo own-fleet combat**: the shipped combat model. One piloting player per battle; the partner is held by the shared pause and gets banners.
- **Joint piloting**: both players flying in one battle. Post-V1, Phase 33 then 22 ([§8.8](#88-joint-combat)).
- **Host-authoritative**: the host's state is canonical and the guest reconciles to it.
- **Layer A / Layer B**: Layer A is the obfuscated engine, Layer B the open game-logic scripts ([§7.1](#71-the-two-layers-of-randomness)).
- **Classpath fork**: a vanilla class copied into `forks/`, edited only inside `COOP FORK` hunks, compiled into `jars/coop-forks.jar` and resolved ahead of `starfarer.api.jar` by a `vmparams` classpath prepend ([§5.2](#52-class-override-what-works), [§7.5](#75-the-forks-that-shipped)).
- **Pause holder**: whichever intent is currently keeping `effectivePaused` true ([§8.2](#82-time-arbitration)). The agent bridge reports it by name.
- **COOP-MARK**: the log marker written into both players' logs by the `coop.markKey` hotkey ([§1](#session-lifecycle-decisions)).

---

## 14. Appendix: paths & resources

### 14.1 Machine-specific paths

Not portable. Verified present 2026-09-19.

| What | Path |
|---|---|
| Game install under audit | `K:\Starsector\` |
| Repo (this project) | `K:\Starsector\mods\coop\` |
| Core jar (obfuscated) | `K:\Starsector\starsector-core\starfarer_obf.jar` |
| API jar (compiled) | `K:\Starsector\starsector-core\starfarer.api.jar` |
| API source zip | `K:\Starsector\starsector-core\starfarer.api.zip` (2,034 entries, 1,947 `.java`) |
| Mods folder | `K:\Starsector\mods\` |
| Saves folder | `K:\Starsector\saves\` |
| Two-instance test installs | `K:\Starsector-coop-test\host\` and `K:\Starsector-coop-test\guest\` |
| Test-client logs | `K:\Starsector-coop-test\<role>\starsector-core\starsector.log` |
| Reference vanilla install | `C:\Program Files (x86)\Fractal Softworks\Starsector\` |

To read the API source on a new machine, extract `starfarer.api.zip` anywhere readable.

### 14.2 Documents in this repo

| File | What it is canonical for |
|---|---|
| `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md` | Phase ledger and every agreed decision. |
| `docs/COOP_MP_DESIGN.md` | This file. Design rationale. |
| `docs/starsector-runtime-limitations.md` | Engine and sandbox facts, accepted divergences. Current facts only. |
| `docs/CODEBASE_MAP.md` | Orientation map: which package and class owns what. |
| `docs/PHASE22_TACTICAL_FEASIBILITY.md` | The 2026-09-05 source pass behind the Phase 22 rescope. |
| `docs/PHASE20_SPIKE_RESULTS.md` | Connectivity spike measurements. |
| `docs/player/INSTALL.md`, `CONNECT.md`, `LIMITATIONS.md`, `REPORTING.md`, `LISTING.md` | Player-facing install, networking, what is shared, bug reports, forum listing. |
| `docs/roadmap.html` | Generated from `roadmap.data.json` by `roadmap_gen.js`. Never hand-edited. |
| `README.md`, `README_DEV.md`, `CHANGELOG.md` | Player overview, developer commands, release notes. |

### 14.3 External resources

- Tomatopaste's CMC source: https://github.com/automatopaste/Multiplayer
- CMUtils: https://github.com/automatopaste/CMUtils
- CMC forum thread: https://fractalsoftworks.com/forum/index.php?topic=11598.0 (Cloudflare-gated)
- Matlabmaster campaign POC: https://github.com/moi75ts/Multiplayer
- Kirpoly's fork with docs: https://github.com/kirpoly/Multiplayer_Starsector
- Starsector modding board: https://fractalsoftworks.com/forum/index.php?board=8.0
- Starsector wiki: https://starsector.wiki.gg/
- This project: https://github.com/AyoKeito/starsector-coop

### 14.4 Bringing this project to a new machine

1. Install Starsector 0.98a-RC8.
2. Clone the repo into `<install>\mods\coop`.
3. Build with `.\gradlew.bat clean test build`.
4. Prepend `..\mods\coop\jars\coop-forks.jar;` to the `-classpath` in `vmparams`, or let the launcher's Fix button do it.
5. Update the paths in [§14.1](#141-machine-specific-paths) and the `starsectorCore` Gradle property.
6. Read `README_DEV.md` for the two-client setup, the agent bridge and release packaging.

---

*End of document.*
