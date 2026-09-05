# Starsector 2-Player Coop Multiplayer Mod — Design & Findings

**Document status:** complete reference, self-contained. Updated 2026-05-28.
**Target:** Starsector 0.98a (current at time of writing).
**Audience:** the author (across machines), future Claude sessions, anyone joining the project.

This document is the canonical source for the design. Any earlier files / memory entries / chat history that conflict with this doc are out of date — this wins.

---

## Table of contents

1. [Project scope and decision log](#1-project-scope-and-decision-log)
2. [Design principles](#2-design-principles)
3. [Prior art (read this first if you're new)](#3-prior-art)
4. [Architecture summary](#4-architecture-summary)
5. [Starsector moddability baseline](#5-starsector-moddability-baseline)
6. [API surface inventory](#6-api-surface-inventory)
7. [Determinism strategy](#7-determinism-strategy)
8. [Subsystem-by-subsystem plan](#8-subsystem-by-subsystem-plan)
9. [Risk register](#9-risk-register)
10. [Research items (not decisions)](#10-research-items-not-decisions)
11. [Execution phase sketch](#11-execution-phase-sketch)
12. [Open questions for v2+](#12-open-questions-for-v2)
13. [Glossary](#13-glossary)
14. [Appendix: paths & resources](#14-appendix-paths--resources)

---

## 1. Project scope and decision log

### Core scope (locked)

| Aspect | Decision |
|---|---|
| Players | Exactly 2 |
| Spatial model | Independent fleets, free roam (each player can be anywhere in the sector) |
| Authority | Host-authoritative |
| PvP | Disallowed |
| Concurrent battles | Max 1 active; non-engaged player spectates. The engaged player's combat-start auto-pauses the **shared** clock so the other player is held, not running ahead. If a single engagement would pull *both* players' fleets into one battle (before v2/v3 joint combat), the **host controls the engagement** — the host pilots the combined battle, the guest's fleet fights host/AI-controlled, and the guest spectates |
| Combat target | **Solo own-fleet combat in v1:** each player pilots their *own* fleet in their *own* battles; the non-engaged player spectates live. The engaging client is authoritative for that battle's outcome and reports the result for the host to integrate. **Joint combat** (both players piloting in *one* battle) via tomatopaste's CMC architecture stays a **v2/v3 stretch** — not v1 |
| Time / clock | Host clock is absolute. Guest cannot fast-forward and cannot freely/manually pause, **except** that entering combat auto-asserts a shared pause (guest→host pause-intent) so a solo battle holds the other player. Voice channel (Discord) still handles discretionary pause requests |
| Spectator UX | Read-only camera + own UI menus (Intel, Cargo, Officers, Refit on docked state). Cannot move fleet or interact with NPCs while spectating |
| Both-players-present rule | **Hard.** No solo continuation. Disconnect ends session |
| Mod compatibility | Vanilla + utility mods. Handshake uses Starsector's enabled mod list plus file checksums. Best-effort with Nex (warn-don't-block) |
| Disconnect handling | Drop immediately, end session. No reconnect grace, no resume |
| Build system | Gradle to `.jar`, Netty bundled in `jars/`, tomatopaste's project layout as template |
| Source repo | Public GitHub from day 1 |

### Per-player state ownership

| What | Per-player or shared? |
|---|---|
| Player fleet (ships, hull state) | Per-player |
| Credits and cargo | Per-player |
| Officers, character XP/skills | Per-player |
| Faction reputation | **Shared** — single rep table. Either player's actions feed in. (Reversed from initial "independent" pick; see [§2 design principles](#2-design-principles).) |
| Faction commission | Host's commission, guest inherits station access. Guest's own rep stays per-player conceptually but is updated against the shared table |
| Story missions / progression | Host-only (guest is narratively a sidekick) |
| Mission boards (bar, contacts, bounties) | Shared pool, first-come-first-served. Mission rewards to accepting player |
| Market inventory (submarket ship/weapon/fighter stock, hireable officers/mercenaries) | **Shared** — host-authoritative; both players see the same contents, and buy/sell/hire transactions apply to the host's canonical market and re-broadcast |
| Station storage | **Per-player** — each player has an independent stash, persisted in their own save/export. A private screen, not a shared mutex |
| Faction-to-faction relationships | **Shared** — host-authoritative, replicated like player rep so both clients agree on who is hostile to whom |
| Salvage / exploration loot | Loot lands in the salvaging player's own cargo (per-player, own-action resolve like combat spoils); the *world entity's* consumed/looted state is **shared** and host-integrated so it is consumed on both clients |
| Hyperspace storm cells, slipstreams, abyss layout | Host-authoritative after initial seed-sync; replicate dynamic terrain mutations/outcomes |
| Time-sensitive event timers (bounty expiry, raid cooldown, faction war ticks) | Shared via sector clock |
| `PersistentUIDataAPI` (ability slots, refit tags, course target, control groups) | Per-player (local-only) |
| Camera / UI tab state / sound | Per-player (local-only) |

### Combat decisions

| Aspect | Decision |
|---|---|
| Triggering combat | Either player can engage. The **engaging player pilots their own battle locally** on their own client and is authoritative for that battle's outcome; the host integrates the reported result into the canonical campaign |
| Spectator behavior | The non-engaged player spectates live (60 Hz stream sourced from the engaging client). **Joint combat** (both in one battle) is a v2/v3 stretch |
| Spoils (XP, salvage, credits, recoveries) | **Solo fighter keeps their own** XP, salvage, credits, and recoveries — the engaging client applies its own `EngagementResultAPI` locally (vanilla). There is **no 50/50 split in v1**, because every v1 battle has exactly one piloting player; a `CoopRewardSplitter` is only needed once **joint combat** lands in v2/v3 |
| Faction reputation deltas | The engaging (piloting) player's combat rep changes apply to the shared table. The spectator gets 0 rep delta (no participation) |
| Fleet wipe | Vanilla respawn (corrected 2026-08-20: 0.98a `CampaignState.showShuttleDialog()` handles wipes natively — Wayfarer + Kite stock fleet at a size-weighted random friendly market, credits `max(old*0.8, 2000)`, officers/skills/rep carried by the engine). The mod adds only a partner notification and an empty-roster mirror guard (plan Phase 17). Session continues |
| Iron mode | Disabled in coop sessions. Cannot be enabled at session start; mod refuses to convert existing iron saves (moot since fresh games only in v1) |
| Combat speed multiplier | Forced to vanilla 1.0x in coop. Combat-speed settings/mods ignored |
| Host disconnects mid-combat | Combat freezes immediately. 5s banner countdown. Then "Save & Exit" dialog. Guest's fleet rolls back to last campaign-side autosave (pre-battle) |

### Session lifecycle decisions

| Aspect | Decision |
|---|---|
| Starting a session (v1) | Fresh New Coop Game only. Both players do character creation. Seed shared at session start. Existing solo saves remain solo, no conversion |
| Version handshake | Exact match: Starsector version + runtime enabled mod manifest + checksums + this coop mod's commit hash. Any mismatch → refuse to connect, show diff |
| Save file | Host owns canonical save. On session end, host serializes guest's fleet state via XStream and sends it back to guest as a `GuestFleetExport` blob. Guest writes to `saves/coop_player_<uuid>.dat` for next session |
| Same-market dock UI | Serialized in v1 (decided 2026-08-20): the global interaction gate admits one player to any dialog at a time, so shared-screen conflicts cannot arise; the WAN claim race is force-closed (plan Phase 18). Concurrent docking (private screens parallel + shop mutex) deferred post-V1 — requires entity-scoping the gate first; see §8.14 |
| Concurrent interaction with same entity | First-click wins per packet timestamp. Other player sees "Player X is interacting with this" + wait/move-on options |
| Player-to-player trade | Out of scope for v1. Cargo-dump-and-pickup is the workaround. Direct trade UI deferred to v2 |
| In-game text chat | None in v1. Players use Discord/voice |
| Presence indicator | Other player's fleet always visible on campaign map regardless of sensor range. Rendered in own faction color, labeled with username |
| Idle-world digest | Not needed. With sector pause + clock fully synced, no "guest missed things" gap exists |

### Out of scope for v1 (deferred to v2 or beyond)

- Joint combat / joint piloting / CMC integration (v2/v3 stretch)
- Colonies, ground raids, industries (deferred to v3)
- Faction war (Nex compatibility) (deferred to v2)
- Player-to-player direct trade UI (v2)
- Iron mode coop (no plan)
- Save conversion from existing solo save (v2)
- Reconnect / resume mid-session (v2 if needed)
- 3+ players (no plan)
- PvP (no plan)
- In-game chat (v2 candidate)
- Cross-version play (no plan — exact match enforced)

---

## 2. Design principles

These principles emerged during design discussion. They are the lens through which to resolve future ambiguity.

### 2.1 Function-richness is the goal, bounded by cost

> Keep as many gameplay functions as possible. When choosing between "build this feature" and "cut for simplicity," prefer build — but only when the cost is bounded. Some features (colonies, full Nex) are too expensive for v1 and are explicitly deferred.

### 2.2 PvE, not PvP

> Both players are friends cooperating against the world. No fairness arbitration. No anti-cheat. No contested-action mechanics. Voice channel is assumed for coordination.

### 2.3 Host-authoritative for choices

> When a choice / arbitration is needed, host decides. Voice channel handles social coordination beforehand. Don't build voting / consent UI.

### 2.4 Seed-sync makes initial convergence cheap; dynamic divergence is expensive

> **This is the most important architectural principle.** Both clients run identical seed-based worldgen at session start. That gives them the same broad sector structure without serializing the whole `Sector`.
>
> That does **not** make the whole campaign deterministic. Current 0.98a-RC8 has gameplay-visible unseeded/random-seed sites in open scripts and API-source implementations. Therefore: prefer **shared host-authoritative outcomes** over per-player recomputation by default. The cost of "make both clients agree on this outcome" is bounded. The cost of "let them disagree, then reconcile" is high.
>
> Pick per-player state only when gameplay genuinely demands it (e.g., per-player cargo means no atomic-transfer protocol; per-player fleet means each player has agency).

This principle drove these decisions (and reversals):
- **Reputation: shared** (originally picked independent; reversed when cost picture clarified)
- **Mission boards: shared first-come** (over independent boards, which would have required forking mission gen + maintaining per-player pools)
- **Hyperspace storms/slipstreams/abyss: host-authoritative after initial seed-sync**
- **Commission: host-owned shared access** (over independent commissions)

### 2.5 Replicate, don't recompute

> Host owns event outcomes. Guest receives outcomes. Guest's local code never re-runs randomness for events that originated on host. Avoids most divergence in practice.

Applied to: NPC fleet inflation, salvage rolls, bounty levels, encounter spawns, mission generation, storm strike resolution.

### 2.6 Adopt prior art aggressively

> The joint-piloting combat netcode is already solved well enough for v2 (tomatopaste's CMC v3.10). Don't reinvent it when that scope starts. V1 should spend invention budget on the campaign layer, spectator bridge, save-export, and host-authoritative event replication.

---

## 3. Prior art

**Read these before writing any code.** Both projects already solved problems we'd otherwise re-derive.

### 3.1 CMC — Cooperative Multiplayer Combat (tomatopaste / automatopaste)

- **Forum thread**: https://fractalsoftworks.com/forum/index.php?topic=11598.0 (v3.10 dated 2026-05-02, targets 0.98a-RC8). Forum is Cloudflare-gated — if WebFetch hits 402, paste content manually or use `cf_clearance` cookie.
- **Source repo**: https://github.com/automatopaste/Multiplayer
- **Companion library**: https://github.com/automatopaste/CMUtils (UI widgets, debug overlays — v2 dependency if adopting CMC)

**Scope**: combat only, no campaign.

**Architecture**:
- Server-authoritative, per-ship state replication, client-side interpolation
- Activation trick: in refit, name a ship `player2` → that ship becomes the joinable one
- Network: Netty 4.1.69. TCP for handshake, lobby, variant data. UDP for high-frequency deltas
- Tick rates: server 60 Hz, client→server input ~30 Hz, server→client state 60 Hz
- Threading: `ServerConnectionManager` runs on its own thread at fixed tick rate; combat plugin runs on game thread; duplex buffers shared between

**Per-ship sync payload** (proven shape, we should match):
- Fleet id, hull id, position, velocity, facing, angular velocity
- Hull%, flux, CR (combat readiness)
- Vent/overload state, engine commands (bitmask), mouse target, owner, armor deltas, engine-disabled flags
- Per-weapon: fire state, aim angle (16-bit precision for beams), autofire flags, group
- Shield: on/off, facing, arc
- Per-projectile: position/velocity/facing/owner/hp + weapon spec id + missile engine flags

**Ship handoff**: `PlayerShips` server-side manages who controls what. Client claim → server removes AI from ship. Client relinquish → server reattaches AI. The "player2" ship name triggers handoff.

**Known broken / inherited limits**:
- `MPDefaultShipAIPlugin`, `MPDefaultAutofireAIPlugin`, `MPDefaultMissileAIPlugin` are empty stubs. Non-piloted ships don't act on client side; autofire effectively disabled on client. Server runs the AI, client renders replicated state. This is acceptable for v2 joint piloting if the server remains authoritative
- No UDP loss recovery → projectile/ship desync under packet loss. Tolerable on LAN / home internet
- Concurrency races between Netty threads and combat thread; minimal sync
- No auth, no timeouts
- Touches obfuscated internals: `com.fs.starfarer.combat.entities.{BallisticProjectile, MovingRay, Missile, DamagingExplosion}`. Breakage likely on Starsector version bumps
- Depends on CMUtils, LazyLib, Console Commands (v2/v3 joint-combat concern, not v1 solo own-fleet scope)

### 3.2 Matlabmaster's Multiplayer (campaign POC, unfinished)

- **Source**: https://github.com/moi75ts/Multiplayer (GitHub handle `moi75ts`, mod author `MatlabMaster`, package path `src/matlabmaster/multiplayer/`)
- **Fork with docs**: https://github.com/kirpoly/Multiplayer_Starsector — has `docs/Multiplayer_Mod_Documentation.md`. Note: that doc actually describes tomatopaste's *combat* mod, not matlabmaster's campaign side; the repo blends both

**Scope**: experimental campaign POC, never shipped formally.

**Key insight we adopt**: **matching seed at game start.** Both clients re-run worldgen from identical seed, so they have structurally identical galaxies without serializing `Sector` over the wire. This single trick removes the worst sync problem.

**Why it's unfinished**: AI stubs empty, save sync ad hoc, no proper handoff between campaign↔combat. We pick up where they stopped.

---

## 4. Architecture summary

### 4.1 Network topology

- **2-player peer-to-peer, one designated host.** No relay server.
- **Single Netty connection**, TCP + UDP. TCP for handshake, lobby, snapshots, guest fleet export. UDP for high-frequency state stream
- **No NAT punching for v1.** Use Hamachi / ZeroTier / port forwarding
- **Drop-on-disconnect**: any connection loss ends session immediately

### 4.2 Authority model

| Subsystem | Host | Guest |
|---|---|---|
| Campaign clock | drives | reads only |
| Pause / fast-forward | drives | locked out |
| NPC fleets, market state, intel, economy, hyperspace storms | authoritative | replicated |
| Host's player fleet | authoritative | mirror as AI-mode fleet |
| Guest's player fleet | mirror of guest's intent | drives locally, sends intent |
| Combat sim | runs and pilots its *own* battles locally; integrates the other player's reported battle results into the canonical campaign | runs and pilots its *own* battles locally; renders a replicated 60 Hz spectator stream of the other player's battle. v2/v3 joint combat: both pilot in one battle |
| Shared reputation table | authoritative | reads only, applies host's broadcasts |
| Save file | owns canonical | exports own fleet snapshot at session end |
| Dialogs (interactions) | own dialog instance | own dialog instance, separately gated |

### 4.3 The two-fleet trick

On each client, `Global.getSector().getPlayerFleet()` returns *that client's* own fleet. The other player's fleet appears as a `CampaignFleetAPI` with:
- `setAIMode(true)` — disables supplies/fuel/accidents/crew requirements
- Custom assignment whose movement is driven each tick by network packets via `setMoveDestinationOverride(x, y)`
- Faction matching the remote player's faction
- `setNoEngaging(seconds)` or similar to suppress hostile NPC engagement against it

Mechanically a normal NPC fleet that mirrors the remote player's actions.

### 4.4 Matching-seed worldgen

At session start, both clients run `Sector` procgen from the same new-game seed. In the current 0.98a-RC8 API source, `SectorProcGen.prepare(CharacterCreationData data)` seeds `StarSystemGenerator.random` from `CharacterCreationData.getSeed()`; `SectorAPI.setSeedString()` exists, but should not be assumed to drive procgen by itself. Both clients should set/verify the same character-creation seed and seed string, then compare a small structural fingerprint of the generated sector.

Seed sync avoids serializing `Sector` for initial join, but it is only a bootstrap. Dynamic events, terrain mutations, encounters, market transactions, intel events, and rep changes are host-authored deltas.

### 4.5 Campaign↔combat bridge

Solo own-fleet model: the player who engages runs and pilots the battle on **their own** client. The other player is held (shared pause) and spectates that battle live; they do not pilot anything. Joint combat (both piloting one battle) is a v2/v3 stretch.

**Both-players-in-one-battle fallback (v1):** if a single engagement's participants include *both* player fleets, the **host controls the engagement** — it pilots the combined battle, the guest's fleet fights as a host/AI-controlled side, and the guest spectates. Because combat-start asserts the shared pause, engagements are serialized, so this only arises from a *simultaneous* trigger (one enemy engaging both fleets at once), never from one player entering another's running battle. This is the v1 stopgap; letting the guest pilot its own ships inside that shared battle is joint combat (v2/v3).

```
Player X triggers combat (interaction or NPC fleet AI reacts to X)
  ↓
[CampaignEventListener.reportPlayerEngagement on X's client]
  ↓
X asserts a SHARED pause (X is host → set its own clock; X is guest → send PAUSE_INTENT, host pauses its clock)
X opens the battle locally via InteractionDialogAPI.startBattle() and PILOTS it
  ↓
Other player Y:
  - is held by the shared pause (campaign frozen)
  - opens a spectator combat dialog (custom InteractionDialogPlugin)
  - streams combat state from X's client at 60 Hz (camera-only; cannot issue orders)
  ↓
[combat runs and is piloted on X's client; Y spectates]
  ↓
On combat end (EngagementResultAPI on X's client):
  - X applies its OWN result locally (vanilla): keeps its own XP, salvage, credits, recoveries
  - X reports campaign deltas for the host to integrate:
      * NPC fleets destroyed/damaged → host updates the authoritative NPC fleet set (§Phase 9) and re-broadcasts
      * shared faction rep delta from the battle (spectator Y gets none)
  - X releases the shared pause; both clients resume
```

---

## 5. Starsector moddability baseline

Recreate this on a new PC by extracting the API source from the install.

### 5.1 What's open vs closed

**Available to mods (source or near-source):**
- `starsector-core/starfarer.api.zip` — 2,034 zip entries / 1,947 `.java` files in audited 0.98a-RC8 install, full modding API
- `starsector-core/data/scripts/world/SectorGen.java`, `data/scripts/plugins/LevelupPluginImpl.java` — base game's own sector gen and level-up logic ships as raw editable `.java`
- `starsector-core/data/` — CSV/JSON for ships, weapons, hullmods, hulls, ship systems, factions, missions, codex entries
- `starsector-core/janino.jar` + `commons-compiler.jar` — in-game Java compiler; mods can drop `.java` files and the game compiles at runtime
- `graphics/`, `sounds/` — PNG/OGG, swappable

**Closed:**
- `starsector-core/starfarer_obf.jar` (~6 MB, 2,818 classes) — obfuscated engine (rendering, combat sim, AI internals, campaign loop, RNG, serializer). Class paths use 200-576 char names with mostly `O` characters specifically to break standard filesystem extraction (Windows 260-char limit). Decompilable for *understanding* (CFR/Procyon) but extraction requires long-path mode

### 5.2 Engine RE — paths and verdicts

**Reflection by name**: impossible (obfuscated names are deliberately unstable).

**Reflection by class structure/signature**: works. Tomatopaste does this for `com.fs.starfarer.combat.entities.{BallisticProjectile, MovingRay, Missile, DamagingExplosion}`. Brittle across versions.

**Java Agent (`-javaagent:` in `vmparams`)**: theoretically can patch any engine method. Investigated for PRNG determinism — verdict: **not worth it.** Most randomness lives in open `.java` files, not the engine. Engine-internal randomness (combat noise) doesn't need determinism in a server-authoritative model. Agent stays in reserve as an "if we hit a wall" escape hatch, not the default plan.

**Decompiling `starfarer_obf.jar` for read-only understanding**: always fine. Use CFR or Procyon. Helps figure out *why* an API behaves how it does so we pick the right hook. No code from the decompile gets redistributed.

### 5.3 Simulation timing model

- **Variable-dt-per-frame, not fixed-tick.** `advance(amount)` called once per render frame with elapsed real seconds
- Default frame cap is 60 FPS (configurable in `settings.json: frameRateLimit`). Effective sim rate ≈ frame rate
- Pause = `advance` not called
- Fast-forward = same tick rate, larger `amount` per tick (engine ticks at frame rate but covers more simulated time per tick)
- For MP networking: 30 Hz state stream + client interpolation = "smooth but slightly floaty". 60 Hz = native feel, more bandwidth. CMC uses 60 Hz server tick, 30 Hz client→server

---

## 6. API surface inventory

Where in `com.fs.starfarer.api` we'll hang each subsystem. Line refs from extracted API source.

### 6.1 Mod plugin lifecycle

- `BaseModPlugin` / `ModPlugin.java` — extend `BaseModPlugin`. Overrides:
  - `onApplicationLoad()` — once at game launch
  - `onGameLoad(boolean newGame)` — every load (incl new game)
  - `onNewGame()` — only at new game
  - `beforeGameSave()` / `afterGameSave()` / `onGameSaveFailed()`
  - `configureXStream(XStream)` — register custom converters/aliases for save serialization
- `Global.java:65 getSector()`, `Global.java:69 getCombatEngine()` — singletons. `getCombatEngine()` is null outside combat

### 6.2 Per-frame hooks

- `EveryFrameScript.java`:
  - `advance(float seconds)` — per-frame
  - `runWhilePaused()` — boolean: does this script tick while sector paused?
  - `isDone()` — return true to be cleaned up
  - Register: `Sector.addScript()` (persisted) or `Sector.addTransientScript()` (not persisted)
- `combat/EveryFrameCombatPlugin.java`:
  - `processInputPreCoreControls(amount, events)` — **raw input before engine sees it.** Consume via `event.consume()` to swallow
  - `advance(amount, events)`, `renderInWorldCoords(viewport)`, `renderInUICoords(viewport)`
- `campaign/listeners/CampaignInputListener.java`:
  - `processCampaignInputPreCore(events)`, `processCampaignInputPreFleetControl(events)`, `processCampaignInputPostCore(events)`

### 6.3 Time control

- `campaign/SectorAPI.java:84 setPaused(bool)` / `:85 isPaused()`
- `campaign/SectorAPI.java:347 setFastForwardIteration(bool)` / `:346 isFastForwardIteration()`
- `campaign/CampaignUIAPI.java:129 isFastForward()`
- `combat/CombatEngineAPI.java:275 setPaused(bool)` — combat-side pause. Used to turn guest's combat engine into a renderer

### 6.4 Combat read/write

- `combat/CombatEngineAPI.java`:
  - `:47 getAllShips()`, `:50 getShips()`, `:51 getMissiles()`, `:53 getAsteroids()`, `:55 getBeams()`, `:57 getProjectiles()` — full state read
  - `:68 getPlayerShip()`, `:247 setPlayerShipExternal(ShipAPI)` — swap which ship is "the player's"
  - `:238 addPlugin(EveryFrameCombatPlugin)`, `:240 removePlugin(...)`
  - `:72 endCombat(float delay)`, `:74 endCombat(float delay, FleetSide winner)`, `:73 setDoNotEndCombat(bool)`
  - `:65 getFleetManager(FleetSide)`, `:66 getFleetManager(int owner)`
- `combat/InputEventAPI.java` (in `input/` package): rich event with `consume()`, `isLMBEvent()`, `isControlDownEvent()`, etc.

### 6.5 Campaign state read/write

- `campaign/SectorAPI.java`:
  - `:46 getPersistentData()` — `Map<String,Object>` for cross-session mod data
  - `:53 getStarSystems()`, `:282 getAllLocations()`
  - `:70 getPlayerFleet()`, `:304 setPlayerFleet(CampaignFleetAPI)`
  - `:130 getMemory()`, `:131 getMemoryWithoutUpdate()`, `:353 getPlayerMemoryWithoutUpdate()`
  - `:152 getEconomy()`, `:133 getIntel()`, `:326 getIntelManager()`
  - `:293 getSeedString()` / `:294 setSeedString(String)` — seed string metadata; current procgen seed is driven by `CharacterCreationData.getSeed()`
  - `:332 getPlayerBattleSeed()`, `:333 setPlayerBattleSeed(long)` — battle-outcome seed
- `characters/CharacterCreationData.java`:
  - `:38 getSeedString()` / `:39 setSeedString(String)`, `:40 getSeed()` / `:41 setSeed(long)` — new-game procgen seed inputs
- `campaign/CampaignFleetAPI.java`:
  - `:36 setLocation(x,y)`, `:60 getLocation()`
  - `:211 setVelocity(x,y)`, `:52 getVelocity()`
  - `:118 setMoveDestination(x,y)`, `:125 setMoveDestinationOverride(x,y)` — drive fleet, override AI
  - `:142 setAIMode(bool)` — for mirror fleet
  - `:64 getContainingLocation()`, `:28 isInCurrentLocation()`, `:29 isInHyperspace()`
  - `:71 getFleetData()`, `:68 getFlagship()`, `:66 getCommander()`
  - `:131 getInteractionTarget()`, `:133 setInteractionTarget(...)`

### 6.6 Combat entry

- `campaign/InteractionDialogAPI.java:52 startBattle(BattleCreationContext)`
- `campaign/CampaignUIAPI.java:35 startBattle(BattleCreationContext)`
- `campaign/InteractionDialogPlugin.java:16 backFromEngagement(EngagementResultAPI)`
- `campaign/BattleAPI.java` — battle object with side composition, snapshots, primary winner

### 6.7 Listeners

- `campaign/CampaignEventListener.java` — battle events, fleet despawned/spawned/jumped/reached entity, market transactions, dialog shown, ability activated, rep changes, economy ticks. Register: `Sector.addListener()`
- `campaign/listeners/*` package — `FleetEventListener`, `ColonyInteractionListener`, `EconomyTickListener`, `DetectedEntityListener`, `GateTransitListener`, `DiscoverEntityListener`, etc. Register: `Sector.getListenerManager().addListener()`

### 6.8 Persistence

- `ModPlugin.java:132 configureXStream(XStream x)` — register custom converters for our packet types and guest-fleet export
- `Sector.getPersistentData()` — `Map<String,Object>` saved with the game

### 6.9 UI gating

- `campaign/CampaignUIAPI.java:64 setDisallowPlayerInteractionsForOneFrame()` — call every frame while spectator-locked
- `:34 isShowingDialog()`, `:62 getCurrentInteractionDialog()` — detect open dialog
- `:42 showInteractionDialog(plugin, target)`, `:51 showInteractionDialog(target)` — open programmatically

---

## 7. Determinism strategy

### 7.1 The two layers of randomness

**Layer A — obfuscated engine** (`starfarer_obf.jar`): combat sim noise, damage variance, particle positions, AI noise. Closed.

**Layer B — game-logic code** (in `starsector-core/data/scripts/` and `starfarer.api.zip`): fleet generation, salvage, encounters, market generation, mission outcomes, sector worldgen. **Open source.** Some important paths use seeded `Random` (`Misc.getRandom`, battle seeds, salvage seeds), but 0.98a-RC8 also has gameplay-visible unseeded sites (`Math.random()`, `new Random()`, `Misc.genRandomSeed()`). Do not assume Layer B is deterministic unless the call path has been audited.

### 7.2 Why no Java Agent

- Engine (Layer A) doesn't need to be deterministic across clients — each battle runs and is piloted on a single client, and its outcome is *replicated as data* (not recomputed), so combat RNG only has to be self-consistent on the one machine that ran that battle
- Layer B is open enough to audit and override, but not fully seeded. Prefer host-authored event results over broad RNG patching
- Agent path is weeks of RE + ongoing maintenance + still doesn't solve threading/HashMap/float-op non-determinism
- **Verdict**: Agent in reserve only

### 7.3 Strategy: replicate, don't recompute (primary)

> Host computes outcomes. Guest receives outcomes. Guest's local code never re-runs randomness for events that originated on host.

Practically:
- **NPC fleet inflation**: host snapshots dmods/variants/officers; sends full packet. Guest does NOT call `FleetInflater` locally
- **Salvage / exploration outcomes**: own-action model (consistent with solo own-fleet combat) — the player performing the salvage/exploration resolves it locally via vanilla and keeps the loot in their own cargo; they report only the *world delta* (the entity is now consumed/looted) for the host to integrate and re-broadcast so it is consumed on both clients. The loot RNG is per-player and need not match. (This supersedes the earlier "host rolls all salvage" framing, which predated the own-fleet combat pivot.)
- **Bounty levels, encounter pirate counts, mine spawn counts**: host rolls, broadcasts
- **Special bar events & market contents**: host-authored pool (like missions) — both clients see the same one-time offers / shop stock / hireable officers, with first-come claims; the item lands in the acting player's own cargo
- **NPC-initiated dialogs (customs / inspection)**: when a host-owned patrol stops the *guest*, the host pushes the dialog; the guest resolves it against its **own** cargo (local) and reports rep/fleet deltas. *Guest-initiated proactive parley* (tribute/demand-surrender) is **host-only** in v1 (avoids replicating full fleet disposition ≈ dialog replication)
- **Self-healing backstop**: because the host continuously re-broadcasts authoritative state (NPC fleet set, economy, rep, faction relations), most *un-enumerated* rules-dialog divergences self-correct on the next rebroadcast. v1 does **not** enumerate every `rules.csv` `CommandPlugin`; explicit replication is only for guest-driven outcomes the host can't otherwise observe, funneled through one `WORLD_DELTA` guest→host report
- **Worldgen**: same new-game seed → both clients arrive at the same broad world independently; verify with a structural fingerprint and host-author dynamic changes after that

### 7.4 Strategy: matching-seed worldgen (foundation)

Both clients must use the same `CharacterCreationData` seed for procgen. `SectorAPI.setSeedString(sharedSeed)` should still be set for save/debug metadata, but current open-source procgen uses `CharacterCreationData.getSeed()` to seed `StarSystemGenerator.random`. Identical worlds are expected only for audited deterministic parts of new-game generation; dynamic campaign outcomes remain host-authoritative.

### 7.5 The fork list (unseeded `Random` sites)

For cases where host can't broadcast (because the call site doesn't have a network hook) and guest must compute the same result, we fork the file and replace `new Random()` with a seeded version.

Convention: place forked `.java` under `mods/coop/data/scripts/com/fs/starfarer/api/impl/.../FileName.java`. Starsector's classloader picks the mod's class over the core's when package paths match.

**Seeding helper** (define once in our mod):
```java
public class CoopRandom {
  public static Random of(String topic, Object... keys) {
    long seed = topic.hashCode();
    for (Object k : keys) seed = seed * 31 + (k == null ? 0 : k.hashCode());
    seed ^= Global.getSector().getClock().getTimestamp();
    return new Random(seed);
  }
}
```

**Forked file pattern**:
```java
// before:
Random random = new Random();
// after:
Random random = CoopRandom.of("FleetFactoryV3.create", fleetId);
```

#### HIGH-impact files to fork

Locations are `com/fs/starfarer/api/impl/`:

- `campaign/procgen/StarSystemGenerator.java:161` — *static* `Random` used by worldgen. Critical
- `util/Misc.java:241` — `public static Random random = new Random();` — used widely as default. Single line covers many call sites
- `util/Misc.java:3481-3482` — `genRandomSeed()` uses `System.nanoTime()`. Critical when callers affect shared gameplay
- `campaign/fleets/FleetFactoryV3.java:249, 1167` — fleet construction
- `campaign/fleets/DefaultFleetInflater.java:226, 235, 654` — variant/dmod assignment during inflation. Critical (without this, two clients see different dmods on the same fleet)
- `campaign/fleets/RouteManager.java:341`
- `campaign/fleets/EconomyFleetAssignmentAI.java:216`
- `campaign/fleets/EconomyFleetRouteManager.java:506`
- `campaign/fleets/MercFleetManagerV2.java:39`
- `campaign/CoreScript.java:843, 875` — `prodRandom` for economy production
- `campaign/FleetEncounterContext.java:1269, 1825` — battle context

#### MEDIUM-impact files (fork if convenient)

- `campaign/abilities/DistressCallAbility.java:204, 402` — pirate response counts
- `campaign/abilities/GenerateSlipsurgeAbility.java:224, 472`
- `campaign/fleets/EconomyFleetRouteManager.java:116` — `Misc.genRandomSeed()` caller
- `campaign/ghosts/SensorGhostManager.java:79` — `Misc.genRandomSeed()` caller
- `campaign/intel/bar/events/BarEventManager.java:60, 85` — `Misc.genRandomSeed()` caller
- `hullmods/StealthMinefield.java:92`
- `campaign/events/nearby/NearbyEventsEvent.java:170, 346, 381, 486, 500`
- `campaign/intel/PersonBountyIntel.java:170` — bounty level
- `data/scripts/world/systems/Galatia.java:390-391` — fixed-system derelict orbit uses `Math.random()`
- `campaign/terrain/AsteroidFieldTerrainPlugin.java:46`
- `campaign/terrain/HyperspaceAutomaton.java:147, 150`
- `campaign/velfield/SlipstreamManager.java:442`
- `campaign/terrain/BaseTiledTerrain.java:74`
- `campaign/terrain/HyperspaceAbyssPluginImpl.java:59, 67`
- `campaign/terrain/FlareManager.java:307`
- `campaign/submarkets/BaseSubmarketPlugin.java:83` — `itemGenRandom`
- `campaign/HassleNPCScript.java:140`
- `campaign/missions/hub/BaseMissionHub.java:289`
- `campaign/rulecmd/salvage/CargoPods.java:251`
- `campaign/rulecmd/NGCAddStandardStartingScript.java:129, 258`

#### LOW-impact / cosmetic (skip)

- `impl/combat/BlinkerEffect.java:29` — visual blinker
- `campaign/tutorial/*` — tutorial-only
- `impl/SimulatorPluginImpl.java:886, 947, 1010, 1016, 1023, 1263` — simulator only
- `campaign/rulecmd/AddText.java:24`, `AddTextSmall.java:27` — random NPC chat lines
- `campaign/events/TradeInfoUpdateEvent.java:120`

#### Null-fallback sites (audit individually)

Pattern `if (random == null) random = new Random();` only fires when no seed is passed. Audit whether the caller seeds before this triggers. Mostly safe but spot-check:

`AICoreOfficerPluginImpl:52`, `CoreScript:870`, `DModManager:67,176`, `DerelictShipEntityPlugin:66,86,97,110,183`, `OfficerManagerEvent:323,476,610`, `OfficerLevelupPluginImpl:104,168,231`, `MilitaryBase:552`, `BaseEventManager:41`, `RaidIntel:641`, `PunitiveExpeditionIntel:539`, `RemnantSeededFleetManager:274`, `RemnantOfficerGeneratorPlugin:347`, `MarkovNames:141`, `DropGroupRow:331`, `OmegaOfficerGeneratorPlugin:73`, `SalvageSpecialAssigner:212,218,580`, `RuinsFleetRouteManager:132`, `BaseSalvageSpecial:137`, `SalvageEntity:780,932`, `MarketCMD:1547`, `FleetFactoryV3:1164`

### 7.6 Residual non-determinism

Even with seeded RNG, divergence sources remain:
- `IdentityHashMap` / `System.identityHashCode` — different `Object` instances have different hashes across JVMs
- HashMap iteration order with same-hash collisions
- Float ops sensitive to JIT decisions (rare in practice)
- Multi-threading interleaving (Starsector has render thread + main thread)

**Mitigation**: replicate-don't-recompute covers most of these. Where it doesn't, document the divergence class when first observed and adjust. Don't pre-emptively fight every theoretical case.

---

## 8. Subsystem-by-subsystem plan

### 8.1 Net pump (Netty embedded in mod)

Bundle Netty 4.1.69 jars under `mods/coop/jars/netty/` and reference in `mod_info.json`. CMC proves it works in Starsector's classloader.

- `CoopNetPump` — `EveryFrameScript` with `runWhilePaused() == true`. Drives outbound queue, consumes inbound packets at campaign layer
- TCP channel: handshake, lobby, snapshot pull (initial join), guest-fleet export, version check
- UDP channel: state stream (campaign 10 Hz, combat 60 Hz)
- Single connection, no relay. Host opens socket; guest connects to host:port

Confidence: ★★★★★. Proven by CMC.

### 8.2 Time arbitration

Host owns the clock. Guest's `EveryFrameScript`:
- Read host's pause state from latest packet → call `Sector.setPaused(hostPaused)` on guest each frame
- Read host's fast-forward state from latest packet → mirror via `Sector.setFastForwardIteration(hostFF)`
- Intercept guest's input via `CampaignInputListener.processCampaignInputPreCore` and consume any pause/FF keystrokes
- Combat speed forced to 1.0x on both clients during coop combat

Confidence: ★★★★★. API-only.

### 8.3 Campaign state replication

**At session start (matching-seed worldgen)**:
1. Host generates a new character-creation seed and `seedString`. Sends seed + seed string + game version + enabled mod/checksum manifest to guest in handshake
2. Guest applies the same `CharacterCreationData` seed before procgen; mod also stores `seedString` in sector metadata
3. Both clients run new-game worldgen and compare a structural fingerprint (system ids, market ids, hyperspace anchor positions). Mismatch refuses session start

**Per-tick (campaign, ~10 Hz)**:
- Host → Guest: snapshot of all `CampaignFleetAPI` in guest's sensor range (position, velocity, faction, AI mode, visibility) — delta-encoded
- Host → Guest: market state changes, economy ticks, intel events, shared rep changes
- Host → Guest: pause/FF state, sector time
- Guest → Host: own fleet `MoveIntent{x,y,abilities[],course}`, interaction requests, dialog selections

**Per-event (TCP)**:
- Interaction request → host opens dialog → host runs RuleCmd → host pushes dialog state to guest
- Ability activate → host applies, broadcasts effect
- Dynamic terrain/abyss/slipstream mutations and sensor-ghost outcomes originate on host and are broadcast; guest does not rely on local RNG for these

Confidence: ★★★. Real work, less prior art for campaign side.

### 8.4 Fleet mirroring

On each client:
- Local player fleet = `Global.getSector().getPlayerFleet()` — the actual player's fleet
- Other player's fleet = a `CampaignFleetAPI` maintained by mod:
  - `setAIMode(true)`
  - `setMoveDestinationOverride(x, y)` each tick from packets
  - Faction matches remote player's faction
  - Roster updates lazily — only when remote player's fleet composition changes (battles, refits, dock transactions)
  - Hostility suppression via `setNoEngaging` or faction trickery

Confidence: ★★★. Edge cases around transponder, faction hostility resolution.

### 8.5 Interaction gating

When host is in combat or dialog:
- Guest's `CampaignInputListener.processCampaignInputPreCore` consumes input except camera-pan
- Guest's `CampaignUIAPI.setDisallowPlayerInteractionsForOneFrame()` called every frame
- Guest's HUD shows "Host in combat — spectating" banner
- Race resolution: first-click wins per packet timestamp. Loser sees "PlayerX is interacting with this" + wait/move-on options

Confidence: ★★★★★.

### 8.6 Combat handoff (campaign → combat → campaign)

See [§4.5 architecture](#45-campaigncombat-bridge). Highest-risk new subsystem.

Confidence: ★★★. New code, complex state transfer.

### 8.7 Spectator combat

Implementation:
- Open spectator `InteractionDialogPlugin` on the non-engaged player
- Receive ship + projectile + beam + missile state from the **engaging client** (whichever player is piloting the battle — host or guest) at 60 Hz
- Interpolate between latest two received frames; brief extrapolation if packet drop
- Render directly via `EveryFrameCombatPlugin`. Camera-only input. All other input consumed
- Overlay: "Spectating PlayerName's battle" + disconnect option

Strategy: open a real `CombatEngine` and use `setPaused(true)` + manual entity manipulation. Engine renders normally; we just drive entity transforms ourselves.

Confidence: ★★★★. CMC's state stream is a useful reference, but v1 should not require CMC.

### 8.8 Joint combat (joint piloting)

**Deferred to v2/v3 stretch.** v1 ships with *solo own-fleet* combat: each player pilots their own battles and the other spectates. Joint combat — **both players piloting their own flagships in the *same* battle simultaneously** — is the harder real-time problem and is explicitly out of v1. CMC remains the preferred path when this stretch is taken, but it is not on the v1 critical path.

v2/v3 direction:
1. Depend on CMC mod (or fork if upstream stalls)
2. Wire CMC's "player2" detection through our coop session — when a battle starts and guest opts to join, mark guest's flagship as the "player2" ship
3. CMC handles input replication, ship handoff, AI removal
4. We add: guest's `processInputPreCoreControls` captures all combat input → ships to host. Host applies guest input to guest's flagship. State streams back

**Accepted limitations**:
- Guest's flagship has ~50-100ms input lag (RTT/2). Tolerable in Starsector
- Non-piloted ships rendered from host state; guest can't issue orders to its own AI ships during battle
- Autofire on guest's flagship runs server-side
- No rollback, no determinism. Server-authoritative collisions; host's truth wins on desync

Confidence for v2: ★★★★. Proven by CMC v3.10 in May 2026, but not part of v1.

### 8.9 Save / reload (fresh games v1)

**At session start**:
- Host clicks "New Coop Game", generates seed, opens lobby
- Guest connects, both do character creation
- Both clients write fresh game; mod stamps `Sector.getPersistentData()` with session id, seed, host/guest role

**Host save (during session)**:
- Standard Starsector save. Includes mod's persistent data and the guest fleet snapshot
- On `beforeGameSave`: serialize guest fleet via XStream into `persistentData["coop.guestFleetSnapshot"]`

**Guest export (at session end)**:
- Host serializes guest's fleet via XStream → sends to guest as `GuestFleetExport`
- Guest writes to `saves/coop_player_<uuid>.dat`
- Next session: guest's mod loads this and re-injects into the fresh sector

**Reload**:
- Host loads save, opens session
- Guest connects → host pushes session id + seed
- Guest applies own `coop_player_<uuid>.dat` to local sector
- Version handshake validates exact match from Starsector version + enabled mod manifest/checksums + coop mod commit hash

Confidence: ★★. XStream is historic source of obscure bugs. Reduced by only persisting deltas + guest fleet, not whole `Sector`.

### 8.10 UI / lobby

**Pre-game**:
- "Host coop session" — opens port, shows IP/port for connect
- "Join coop session" — enter IP:port, optional guest-fleet import file picker
- Seed shown for verification

**In-game HUD**:
- Connection status indicator (top-right)
- Other player's location pill ("Player2 — Corvus II, 12.4 LY away")
- Other player's fleet always visible on campaign map regardless of sensor range, own faction color, username label
- No text chat (Discord covers it)

**Version handshake**: exact match. Use `Global.getSettings().getGameVersion()`, `Global.getSettings().getModManager().getEnabledModsCopy()`, each enabled mod's id/name/version/path/jar list, file checksums for relevant mod files, and this coop mod's commit hash. `enabled_mods.json` format is `{"enabledMods":[]}` in the reference install, but runtime `ModManagerAPI` should be the source of truth. Mismatch → refuse to connect, show diff.

Confidence: ★★★★.

### 8.11 Shared reputation table

- Reputation managed via the standard `SectorAPI.adjustPlayerReputation(...)` calls
- Mod intercepts rep changes (via `CampaignEventListener.reportPlayerReputationChange`) and broadcasts deltas to guest
- Both clients apply deltas to their local `Sector` rep table — they converge
- In v1 battles: only triggering player's rep changes are processed. Spectating player → no rep delta

Confidence: ★★★★.

### 8.12 Hyperspace / dynamic terrain

- Initial hyperspace layout comes from seed-sync, then host owns dynamic terrain state
- Storm cells, slipstreams, abyss effects, abyssal lights, sensor ghosts, and abyssal contacts are not assumed seed-deterministic in 0.98a-RC8
- Lightning strike on a player fleet resolves on host (CR damage etc); discharge state and damage result replicate to guest
- Guest applies host terrain/event packets and does not attempt to re-roll dynamic terrain outcomes locally

Confidence: ★★★.

### 8.13 Fleet wipe / respawn

> **Corrected 2026-08-20.** The bullets below assumed the mod must build a respawn; vanilla 0.98a already has one (`CampaignState.showShuttleDialog()`, fires on LEAVE after "no ships left", iron and non-iron alike): removes the wiped fleet, grants the `"shuttle"` stock fleet (Wayfarer + Kite), teleports to a size-weighted random friendly market, credits `max(old*0.8, 2000)`, carries officers/skills/abilities/rep/mission cargo. Confirmed live in the 2026-08-19 session (guest wipe, partner mirror recovered clean). Building the planned Wolf + 5k injection would have suppressed this flow, since its call sites gate on `!isValidPlayerFleet()`. Decided 2026-08-20: ride vanilla unchanged, keep the random destination (no `setRespawnLocation` override). The mod's remaining work is coop plumbing only — see plan Phase 17: an empty-roster mirror guard (a 0-member mirror despawns as `NO_MEMBERS` on any unpaused frame; `setNoAutoDespawn` does not cover that branch) and a `RESPAWN_PLAYER` banner so the partner learns where the wiped player reappeared.

- ~~Detect when a player's fleet is empty (all ships destroyed/lost)~~ vanilla detects
- ~~Apply respawn: Wolf-class frigate + 5k credits, place at last visited friendly station~~ vanilla respawns (Wayfarer + Kite, random friendly market)
- Preserve: officers, character skills, shared rep — vanilla carries all of these
- Session continues; partner is notified and the mirror never commits an empty roster

Confidence: ★★★★★ (observed live).

### 8.14 Same-dock concurrent UI

> **Deferred post-V1 (decided 2026-08-20).** V1 ships serialized docking instead: the plan's Phase 10 gate is a global one-dialog-at-a-time lockout, so no two players are ever inside dock UI at once, and the market sync model depends on that (host purchases are not pushed to an already-open guest screen). The rescoped plan Phase 18 closes the WAN-latency race where a rejected dialog stayed open. The bullets below remain the design for the post-V1 follow-up, which starts by entity-scoping the gate.

- Both players can be docked at the same station simultaneously
- Private screens (own refit, own officers, own cargo, own intel) concurrent — no conflicts
- Shared-state screens (shop inventory, submarkets — host-authoritative market contents) mutually exclusive — only one player at a time. Other sees "PlayerX is using the shop" + wait/move-on options. ~~Station storage is per-player (private), so it is not mutexed~~ *(2026-09-05, plan Phase 32: storage is shared and host-canonical like the other submarkets, so it is inside the same lockout as the rest of the dock)*
- Bar listing shows other player's presence

Confidence: ★★★★.

### 8.15 Build system

- Gradle project. Mod compiles to `.jar` placed in `jars/`
- Netty 4.1.69 jars bundled under `jars/netty/`
- IDE: IntelliJ project (use CMC's setup as template)
- Iteration: edit → `./gradlew build` → restart game (~10-15s total)

---

## 9. Risk register

Ordered by combined likelihood × impact:

| # | Risk | Mitigation |
|---|---|---|
| 1 | **Campaign↔combat handoff bugs.** New code, complex state transfer, no prior art for this seam | V1 is solo own-fleet combat (exactly one piloting player per battle; the other spectates). Heavy testing on canonical scenarios before adding the v2/v3 joint-combat stretch |
| 2 | **State divergence between host and guest over a long session** (economy, faction state, intel) | Periodic full resync. Replicate-don't-recompute principle |
| 3 | **Starsector version bumps break CMC.** Inherited obfuscated-class dependence once v2 joint piloting starts | Pin to specific Starsector version. Track upstream. V1 should not depend on CMC |
| 4 | **Save/reload of guest fleet corrupts state.** XStream surprises with object graph identity | Test save→reload→save round-trip extensively. Keep guest fleet snapshot minimal (no transient/cached fields) |
| 5 | **Network packet loss desyncs combat** (UDP without retransmit) | TCP for critical events (destruction, weapon swaps). UDP only for high-freq smoothable state |
| 6 | **HashMap/identity hash divergence between clients** | Document; investigate first time observed. Use `LinkedHashMap` and id-based comparisons rather than identity |
| 7 | **CMC's known concurrency races** — inherited in v2 joint piloting | Audit duplex-buffer access when CMC integration starts. Add `synchronized` blocks if needed |
| 8 | **Best-effort Nex compatibility breaks** in subtle ways | "Warn, don't block" at connect time. Document known interactions. Don't promise Nex support |
| 9 | **Guest's fleet export file corrupted between sessions** | Backup-before-write on guest side. Fallback: regenerate starter fleet (Wolf + 5k) |
| 10 | **Required deps drift** (Netty in v1; CMUtils, LazyLib, Console Commands once v2 CMC starts) | Pin dependency versions in `mod_info.json` |

---

## 10. Research items (not decisions)

These are do-when-relevant, not user-decisions:

- **Bandwidth budget**: estimate KB/s for a heavy combat sim. CMC's design doc hints at sizes; verify with quick math. Rough: ~40 ships × 80 bytes × 60Hz = ~190 KB/s per direction at peak. Should be fine on home internet
- **CMC license (v2)**: confirm fork-or-depend permission. Tomatopaste's GitHub repo license file
- **CMC 0.98a compatibility (v2)**: verify v3.10 actually builds & runs against 0.98a. The forum thread says "targets 0.98a-RC8" but spot-check before depending
- **Performance impact**: ballpark CPU/memory cost on host running combat sim + serializing state. Probably negligible on modern hardware but worth a single profiling pass
- **Mod checksum manifest**: `enabled_mods.json` format is confirmed as `{"enabledMods":[]}` in the reference install; implementation should prefer `Global.getSettings().getModManager().getEnabledModsCopy()` plus checksums of each enabled mod's `mod_info.json`, jar list, and relevant data files
- **XStream config**: investigate which classes need explicit aliasing; CMC's `MPModPlugin.configureXStream` is the reference
- **Two-instance testing setup on one PC**: separate save dirs, separate settings dirs, two Starsector windows. Practical for solo dev

---

## 11. Execution phase sketch

Multi-stage plan to be derived separately. This is a sketch.

- **Phase 0 — Setup & first contact.** Mod skeleton, Gradle project, `BaseModPlugin`, Netty embed, "hello world" ping between two local Starsector instances. (~1 week)
- **Phase 1 — Seed lock.** Both clients use the same character-creation seed, then compare a structural sector fingerprint. (~3 days)
- **Phase 2 — Time lock.** Host clock authoritative. Guest pause/FF locked. Visual indicators. (~2 days)
- **Phase 3 — Mirror fleet.** Other player visible as AI-mode fleet on each client, position-replicated. Both fleets share same sector. (~1 week)
- **Phase 4 — Shared rep table.** Both clients converge on a single rep table. Host broadcasts deltas. (~3 days)
- **Phase 5 — UI gating.** Block guest interaction while host in dialog/combat. Spectator banner. (~3 days)
- **Phase 6 — Combat handoff (solo own-fleet).** Either player enters combat and pilots it locally; the other is held by the shared pause and opens a spectator dialog, seeing combat unfolding. No joint combat yet (v2/v3). (~2 weeks)
- **Phase 7 — Combat results propagate.** The solo fighter keeps its own salvage/XP/credits/recoveries (applied locally); the engaging client reports campaign deltas (destroyed NPC fleets, shared rep) for the host to integrate and re-broadcast. No 50/50 split in v1 (that arrives with v2/v3 joint combat). (~1 week)
- **Phase 8 — Dynamic terrain/event authority.** Host-authoritative storm/slipstream/abyss/sensor-ghost outcomes. (~1 week)
- **Phase 9 — Markets / economy / intel sync.** Replicate-don't-recompute pattern for ~10 listener events. Shared mission boards with first-come acceptance. (~2 weeks)
- **Phase 10 — Random fork list.** Apply seeded forks to HIGH-impact files in [§7.5](#75-the-fork-list-unseeded-random-sites). (~3 days)
- **Phase 11 — Same-dock concurrent UI.** Private screens parallel (incl. per-player storage); shop/submarket mutex. (~3 days)
- **Phase 12 — Hyperspace storm polish.** Visual consistency and edge-case resync for host-authored terrain packets. (~3 days)
- **Phase 13 — Fleet wipe / respawn.** Detect, Wolf + 5k + station. (~3 days)
- **Phase 14 — Save / reload + guest fleet export.** XStream config, export blob, import flow. (~1-2 weeks)
- **Phase 15 — Version handshake.** Exact-match check at connect time. (~3 days)
- **Phase 16 — Polish, error handling, UX.** Long tail

**Realistic single-developer-with-AI estimate to private playable v1 prototype**: **8-13 weeks part-time.** Phases 6-9 dominate; v2 joint piloting is not included in this estimate.

---

## 12. Open questions for v2+

Explicitly out of scope for v1, but worth designing extensibility hooks for now:

- **Joint combat / joint piloting / CMC integration** — v2/v3 stretch
- **Colonies, ground raids, industries** — v3. Architecture should not hard-code "no colonies" assumptions
- **Faction war (Nex compatibility)** — v2
- **Player-to-player trade UI** — v2
- **Save conversion** from existing solo save — v2
- **Reconnect / resume mid-session** — v2 if user demand
- **In-game text chat** — v2 candidate (adopt CMC's combat chatbox if integrating CMC anyway)
- **3+ players** — no plan
- **PvP** — no plan
- **Mod compatibility beyond vanilla + utility + best-effort-Nex** — case-by-case

---

## 13. Glossary

- **CMC** — Cooperative Multiplayer Combat, tomatopaste's mod ([§3.1](#31-cmc--cooperative-multiplayer-combat-tomatopaste--automatopaste))
- **The two-fleet trick** — local player fleet is real, remote player's fleet is an `AI-mode CampaignFleetAPI` ([§4.3](#43-the-two-fleet-trick))
- **Matching-seed worldgen** — both clients regenerate same sector from shared seed at session start ([§4.4](#44-matching-seed-worldgen))
- **Replicate-don't-recompute** — host owns outcomes, guest receives them, guest's local code doesn't reroll ([§7.3](#73-strategy-replicate-dont-recompute-primary))
- **Joint combat / joint piloting** — v2/v3 stretch where both players fly their own flagships in the *same* battle simultaneously ([§8.8](#88-joint-combat-joint-piloting)). v1 is *solo own-fleet* combat instead: one piloting player per battle, the other spectates
- **Host-authoritative** — host's state is canonical, guest reconciles to it
- **Layer A / Layer B** — Layer A = obfuscated engine, Layer B = open game-logic scripts ([§7.1](#71-the-two-layers-of-randomness))
- **Seed-sync bootstrap** — same new-game seed gives both clients the same broad initial sector; host-authoritative replication handles dynamic outcomes ([§2.4](#24-seed-sync-makes-initial-convergence-cheap-dynamic-divergence-is-expensive))

---

## 14. Appendix: paths & resources

### 14.1 Machine-specific paths (current PC: Windows)

Adjust for your install. These are not portable across machines.

| What | Path |
|---|---|
| Game install under audit | `K:\Starsector\` |
| Reference/modded install | `C:\Program Files (x86)\Fractal Softworks\Starsector\` |
| Core jar (obfuscated) | `<install>\starsector-core\starfarer_obf.jar` |
| API jar (compiled) | `<install>\starsector-core\starfarer.api.jar` |
| API source zip | `<install>\starsector-core\starfarer.api.zip` (2,034 zip entries; 1,947 `.java` files in 0.98a-RC8) |
| Mods folder | `<install>\mods\` |
| Saves folder | `<install>\saves\` |
| API source extracted (this session) | `C:\Users\mistd\AppData\Local\Temp\ssapi_extract_current\com\fs\starfarer\api\` (temporary; regenerate as needed) |
| Project plan dir (this PC) | `K:\Starsector\` |

Local audit note: `C:\Program Files (x86)\Fractal Softworks\Starsector\mods\enabled_mods.json` exists and currently contains `{"enabledMods":[]}`; no `mod_info.json` files were present in that reference install during the 2026-05-28 audit.

To extract API source on a new PC:
```
mkdir <somewhere>/ssapi_extract
cd <somewhere>/ssapi_extract
unzip "<install>/starsector-core/starfarer.api.zip"
```

### 14.2 External resources (portable)

- **Tomatopaste's CMC source**: https://github.com/automatopaste/Multiplayer
- **CMUtils (CMC dep)**: https://github.com/automatopaste/CMUtils
- **CMC forum thread**: https://fractalsoftworks.com/forum/index.php?topic=11598.0 (Cloudflare-gated; use `cf_clearance` cookie or paste content)
- **Matlabmaster campaign POC**: https://github.com/moi75ts/Multiplayer
- **Kirpoly's fork (has docs)**: https://github.com/kirpoly/Multiplayer_Starsector
- **Mod design doc (in kirpoly's repo)**: `docs/Multiplayer_Mod_Documentation.md`
- **Starsector forum modding board**: https://fractalsoftworks.com/forum/index.php?board=8.0
- **Starsector wiki (modding)**: https://starsector.wiki.gg/

### 14.3 Memory files (also on this PC)

The Claude memory directory `<userprofile>/.claude/projects/.../memory/` holds:
- `project_coop_mp_mod.md` — scope & decisions (this doc supersedes it)
- `reference_starsector_mp_priors.md` — prior art (this doc has the canonical version)
- `reference_starsector_moddability.md` — moddability baseline (this doc has the canonical version)
- `MEMORY.md` — index

These memory files are a *convenience* for future Claude sessions on this machine. This doc is the canonical reference. If they conflict, this doc wins.

### 14.4 Bringing this project to a new machine

1. Install Starsector at any path
2. Extract `starfarer.api.zip` somewhere readable (for source navigation, optional)
3. Clone CMC and CMUtils repos for v2 joint-piloting reference when needed
4. Clone (or create) this project's repo
5. Drop this doc in the project root
6. (Optional) Create the Claude memory files using this doc as the source
7. Update [§14.1 paths](#141-machine-specific-paths-current-pc-windows) for the new machine

---

*End of document.*
