# Starsector Coop V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a v1 two-player Starsector coop mod with host-authoritative campaign play, solo own-fleet combat (each player pilots their own battles; the other is held by a shared pause and follows via a live battle-status panel), solo-fighter-keeps-own-spoils rewards, host-authoritative NPC fleet replication, seed-lock bootstrap, fleet mirroring with always-visible presence, shared first-come mission/bar pools, time lock, exact mod/version handshake, iron-mode refusal, disconnect handling, coordinated-save support, and shared-faction colonies/industries/raids (Phase 24, rescoped into v1 2026-06-10).

**Architecture:** V1 is a host-authoritative campaign sync mod. The host owns the sector clock, campaign outcomes, dynamic terrain outcomes, the NPC fleet population, shared mission/bar claims, the shared reputation table, and the canonical save, and it integrates each player's reported own-fleet battle results; the guest drives its own player fleet and pilots its own battles locally, but owns no shared campaign state. **Combat is solo own-fleet:** whichever player engages pilots that battle locally and keeps its own spoils, while the other spectates. **Joint combat (joint piloting — both players in one battle), CMC integration, relaunch-from-save rejoin, direct trade, PvP, and 3+ players are outside v1** (joint combat is a v2/v3 stretch; 3+ players is gameplay-only exclusion — the wire format ships N-ready per Phase 20.5, enablement is Phase 27). Colonies, raids, and industries are **inside v1** as a *shared* player faction (Phase 24, rescoped 2026-06-10); separate per-player factions are not.

**Tech Stack:** Starsector 0.98a-RC8, Java 17 runtime, Starsector mod API, Gradle, `java.nio` non-blocking TCP+UDP networking driven from the campaign pump thread, XStream for save-DTO serialization. (TCP for reliable control + battle status, UDP for high-frequency campaign fleet state.) **Netty was removed from the stack:** Starsector's script sandbox blocks Netty's reflection initialization (verified in Phase 3, enforced by `CoopNetServiceSandboxCompatibilityTest`), so no Netty jars are bundled or loaded.

---

## Agent Operating Rules

- Use one fresh agent per phase. Each agent reads `COOP_MP_DESIGN.md` and this file before touching code.
- **Check the Phase Status Ledger (below) before implementing anything.** Never re-implement a phase marked BUILT; their prompts/steps are kept as the historical record. Unchecked boxes inside BUILT phases are deliberate residue (deferred commits, manual smoke tests) annotated inline — do not "finish" them by redoing the phase.
- **Runtime sandbox (hard constraint):** Starsector's script classloader blocks `java.lang.reflect` and `java.io.*`/`java.nio.file.*` in mod code — such code compiles and passes unit tests but **throws in-game**. Use `java.lang.invoke` MethodHandles (copy the `CoopBarSync.resolveHandles()` lazy-resolve + Throwable-catch pattern) or the engine `SettingsAPI` file surfaces instead.
- **Flat JSON envelope (hard constraint):** the TCP/UDP envelope JSON parser has no array support — message payloads must not contain JSON arrays. Encode multi-element data as a single delimited string (the `CoopDelimited` unit-separator pattern; escaping per Phase 12b).
- **Deploy before every two-instance smoke test** with `mods/coop/scripts/deploy-to-test-clients.ps1` (never hand-copy jars), then relaunch both game instances.
- Work directly on `main` unless the user explicitly asks for a separate branch or worktree.
- Implement exactly one phase, verify it, then stop with a summary and changed files.
- Do not start the next phase until the current phase builds, loads in Starsector when applicable, and passes its smoke test.
- Keep each phase independently reviewable and **commit after each phase** with the message listed in that phase. The repo roots at `K:\Starsector\mods\coop` (origin = `github.com/AyoKeito/starsector-coop`, private) — run git from inside it: `git -C K:\Starsector\mods\coop add . && git -C K:\Starsector\mods\coop commit -m "<message>"`, then `git -C K:\Starsector\mods\coop push`. Older phase steps write the command as `git add mods/coop && git commit ...` — that form assumed a `K:\Starsector`-rooted repo that never existed; use the form above. *(History note: sessions before 2026-06-10 concluded "not a git repo" from running git outside the repo root and deferred their commits; a catch-up commit on 2026-06-10 covered Phases 9+12.)*
- Preserve v1 scope. Do not add CMC, joint piloting, direct trade UI, relaunch-from-save rejoin, PvP, or 3+ player support. (Colonies, raids, and industries were **rescoped INTO v1** as Phase 24 on 2026-06-10 — shared player faction only; separate per-player factions remain out.)
- If a phase exposes a missing Starsector API assumption, document the exact API path and stop. Do not patch around unknown engine behavior blindly.

## Implementation Order (decided 2026-06-10)

Remaining work proceeds **hardening-first** (the stability rubric): **12b → 12d → 6b → 13-remainder → 14 (its spikes first) → 15 → 16 → 17 → 18 → 29-M1 (mirror interpolation) → 12c → 24 (colonies/industries/raids) → 7b → 7c → 20 (incl. 20.6) → 29-M2 (adaptive cadence) → 21 (multiplayer UX) → 19 (final sign-off)**. Rationale: 12b fixes a live bug (silent autoresolve against mirror fleets) and Phase 13's suppressor extension stops the guest spawning its own pirate/Pather bases today — both protect every later test session. Phase 12d (split out of 12c on 2026-08-09) sits at slot 2 because it restores player-to-player item transfer, which v1 has none of today — every private soak session before it lands runs with no way for the two players to hand each other fuel, supplies, or a ship, which makes the soak unrepresentative of real co-op play. It has no dependency on Phases 13–18; only Phase 19's QA lines pinned the *rest* of 12c late. Phase 12c (extension-point closure) must land before Phase 19, whose bar-pool and market-contents QA lines depend on it; Phase 24 (rescoped into V1 on 2026-06-10) slots right after its 12c prerequisite — its milestone 1 (raids) needs no colony-lifecycle work and can start any time after 12c; 7b/7c are QoL and slot after the core gameplay phases; Phase 20 precedes the Phase 19 sign-off per its own ordering note. 2026-08-20: Phases 17 and 18 were rescoped and both shrank to small hardening passes (see their banners); their order slots are unchanged. Same day, **Phase 29 was pulled forward from post-V1 into V1** (user decision, after the jumpy-mirror root cause was verified live — see the Phase 29 banner): M1 (mirror interpolation, no dependencies beyond the built 8/9) slots right after 18 so every later soak session benefits; M2 (adaptive cadence) needs Phase 20 and slots directly after it, before the 19 sign-off. 2026-08-24: **Phase 21 was pulled from post-V1 into V1** (user decision, motivated by the host-starts-unpaused issue the lobby fixes — see the Phase 21 banner); it stays whole (split-lobby-early option declined) and slots after 29-M2, keeping 19 last.

**Post-V1 priority** (decided 2026-06-10, "public after soak"): V1 ships privately first (host + one friend); the public forum/Nexus release is the goal after the private soak. Order: **23 (Release Packaging) → public release** *(Phase 21 — multiplayer UX — led this list until it was **pulled into V1 on 2026-08-24**; it now sits in the V1 sequence above, after 29-M2)*, with 22 (co-op piloted battles), 25 (guest time-control polish: `coop.allowGuestPause` toggle + consensual fast-forward; needs 7b, promoted from the pause Maybe 2026-06-10), 26 (hyperspace ambient-world replication: slipstreams as outcome polylines + abyssal encounters as keyed re-execution; needs 13, promoted from two replication Maybe bullets 2026-06-10), 27 (multi-guest enablement, host + 2–3 guests: gameplay arbitration + QA matrix on Phase 20's N-ready transport; needs 20, promoted from the multi-guest Maybe 2026-06-10), and 28 (coop options & player-facing configuration: typed registry + settings-file stack + host-policy sync + intel options page; its milestone 1 delivers Phase 23's settings-file item and should land with 23; created 2026-06-10), after or alongside as appetite allows. (Phase 29 — campaign motion smoothness — was created here post-V1 on 2026-06-10 and **pulled into V1 on 2026-08-20**; it now sits in the V1 sequence above.) (Phase 24 — shared-faction colonies/industries/raids — was sketched here post-V1 on 2026-06-10 and **rescoped into V1 the same day**; it now sits in the V1 sequence above.)

### Phase Status Ledger (review pass, 2026-06-10)

Authoritative build status per phase. Checkbox state inside the early phases predates this tracking convention (Phases 1–6 are BUILT despite unchecked boxes); from Phase 7 onward the boxes were maintained. When this ledger and a phase's checkboxes disagree, the ledger wins.

| Phase | Status | Notes |
|---|---|---|
| 1–6 | **BUILT** | Verified in-game. Boxes unchecked (pre-convention); do not re-implement. Phase 3's Netty text is historical — see its banner. |
| 6b | **BUILT** | Smoke-verified in-game 2026-08-17. Replay-reject drill failed first run (adopt-on-absent flaw) → fixed via the mint signal + `-AdoptCampaign` rejoin switch; real checksums wired; canonical-dump drill covered by unit test only. |
| 7 | **BUILT** | FF verdict partially superseded by 7b (annotated in place). |
| 7b, 7c | NOT BUILT | V1; slot after 24. |
| 8–12 | **BUILT** | Unchecked residue = pending manual smoke tests (annotated inline). The deferred commits were resolved by the 2026-06-10 catch-up commit (repo wired to GitHub the same day). |
| 9b | **BUILT** | 2026-08-20. `aiAssignmentSummary` had been a stubbed `""` since Phase 9, so mirrored NPC fleets showed no action line on the guest. Host capture (`CoopNpcActionTextCapture`) replicates the `StandardTooltipV2$9` resolution with the host-player/guest-mirror observer rewrites; guest applies it via `setActionTextOverride` + `setNullAIActionText`. No wire-format change. Unit-tested (28 cases); PENDING the visual check in the next two-instance session. |
| 12b | **BUILT** | Smoke-verified in-game 2026-08-17. The reconnect drill failed and was fixed in-session (session-state disconnect transition + pre-session traffic gate); checksum probe verdict SUCCESS → wiring moved to 6b. |
| 12c | **BUILT** | Code-complete 2026-08-24 (4 build tasks + 3-MAJOR review fix pass, 890 tests green); two-instance smoke PENDING. |
| 12d | **BUILT** | Smoke-verified in-game 2026-08-17 (pods both directions, nav buoy, derelict regression). Split out of 12c gap 4 on 2026-08-09; restores player-to-player item transfer. |
| 13 | **BUILT** | Smoke-verified in-game 2026-08-19 (one in-game month soak). Forks/determinism 2026-05-29; suppressor extension + coverage diagnostic + `WORLD_DELTA` skeleton subtypes + full base reconstruction (fallback not needed) 2026-08-17 (`e886ef6`+`aec7f21`). Smoke test caught a session-start ordering bug (mirrors destroyed then forgotten) — fixed in `57fe7c9`. Live-verified: A→B→A objective re-flip, unknown-entity skip, 6/6 base set match, `large` MethodHandles write. DECIV/GATE applies unit-tested only (months/story-scale timers). |
| 14 | **BUILT** | Smoke-verified in-game 2026-08-19, all five scenarios: host engages (banners + hold), guest engages (shield release fix 57bcf5b), ENGAGE_GUEST chase (sentinel-overflow fix 360c02b), customs (possibly vanilla-native on guest — see spike notes known issues), disconnect drill (discard + clean reset). Spectator panel cancelled for banners by user decision; BATTLE_STATUS stream retained for Phase 22. Same-day fallout fixes: RouteManager fork presence, full-fidelity system drive, detectability operand, engagement-shield hardening. |
| 14b | **BUILT** | Smoke-verified in-game 2026-08-19 on the **primary (vanilla-signal) pursuit model** — the fallback flag was never needed. Verified: stealth pass-by, customs cat-and-mouse (chase-from-detection `d35459a` after the collision-distance report; watcher-killing immutable-set crash fixed `56b025f`; user notes pursuit motion reads below vanilla — parked in spike notes), pursuit-catch at contact, post-defeat grace (log-verified 0.88–1.21 day stamps). **Pursuit-escape scenario VERIFIED 2026-08-20** (after two deferrals): a pirate Scout chased the running guest across a jump, pursuit timer expired at its cap (`pursuitDays=3.18/2.40` → `hunting=false`, `allowedToEngage=false`, distance opening), clean disengage, no watcher errors. Same-session fallout: mirror rosters wore placeholder Nebulas for engine-inflated fleets (runtime variant ids + silent engine substitution) — root-caused via the new roster-diff diagnostic and fixed in `837f63e`+`22b7f4b`+`1b71735` (stock-id streaming, capture truncation, hash latch, live name/faction). |
| 15 | **BUILT** | Smoke-verified in-game 2026-08-19, both directions: guest-fights (BATTLE_RESULT over TCP → host despawn/roster apply, e.g. `lost=7 remaining=4` partial outcome) and host-fights (three `applied` with no inbound message — direct local reconciler call confirmed). Mirror freeze/release cycle observed; salvage-dialog freeze-expiry hole closed pre-smoke (`c23558f`). BATTLE_RESULT carries no rep by design (Phase 12 GUEST_REP_DELTA already covers it — dated note in the phase section). |
| 16 | **BUILT** | Smoke-verified in-game 2026-08-20 (commit `617e4b9`). Verified live: host save → snapshot embedded (`coop.guestFleetSnapshot`) + guest coordinated autosave (immediate and dialog-deferred ~6–9 s); quit-both → resume (campaign UUID `minted=false`, mirrors rebuilt, guest state byte-identical); D-mods on dozens of NPC fleets (per-ship counts, matching hashes); S-mods on the player mirror (`ziggurat+s1`); post-battle mirror teardown/removal unaffected. Host save is now mod-dependent (user decision, see the Design Alignment Notes revision). Fallout fix: NPC deflate/inflate flips oscillated the fleet hash → capture latch (see commit after `617e4b9`). Pursuit-escape verified later the same day in the follow-up session (see 14b row); that session also verified cross-system save/resume (host Corvus / guest Galatia, `minted=false`) and caught+fixed the NPC_FLEET_SET oversized-frame regression (`23feb5d`). |
| 17 | **BUILT** | 2026-08-20. Built to the rescoped spec: no respawn mechanics, defeat path untouched, no `setRespawnLocation` write — vanilla's `showShuttleDialog()` still does all of it. Two deliverables: the empty-roster guard (`CoopFleetMirror.shouldSkipRosterApply`, player-mirror path only, so Phase 15's NPC battle teardown still empties a destroyed mirror) and the `RESPAWN_PLAYER` banner (`CoopRespawnNotifier` detects the `setPlayerFleet()` object swap, qualified so an ordinary swap or a session start cannot fire it; the pump resets tracking whenever the session stops streaming). Unit-tested (13 new cases); PENDING the two-instance deliberate-wipe smoke test. |
| 18 | **BUILT** | 2026-08-24. Built to the rescoped spec: no `UI_LOCK_*` messages, no lock classes — the Phase 10 global gate still serializes every dialog. Four deliverables: the forced close (`CoopRejectTracker` + `CoopNetPump.forceCloseRejectedDialog`, deferred one frame out of the inbound drain and re-issued until the dialog is gone, narrowed to the entity the local player actually has open); the reject re-claim loop fix (the handler no longer drops `localInteractionEntityId`, so a lost race costs one claim, one reject, one warn instead of up to 60 msg/s of ping-pong); the `-Dcoop.debug.interactionDelayMs` host-side delay queue (dormant, release-stamped, never sleeps the campaign thread); and the storage regression fence (exclusion comment on `openMarketCargo` + a test that a snapshot apply never even reads `SUBMARKET_STORAGE`). `reportPlayerClosedMarket` now reaches the Sink (Phase 24's diff-on-close hook). Unit-tested (25 new cases); PENDING the induced-latency two-instance smoke test. |
| 19 | NOT BUILT | V1. The final sign-off; runs last, after 21. |
| 20 | NOT BUILT | V1; includes 20.6; precedes 29-M2 and 21. |
| 21 | NOT BUILT | **Pulled into V1 2026-08-24** (user decision — the lobby's force-pause-until-all-ready fixes the host-starts-unpaused-before-guest issue it now owns). Stays whole (lobby-early split declined); slots after 29-M2, before 19. Executable spec. |
| 22–28 | NOT BUILT | Post-V1. 22 has an executable spec; 23 is scope-level; 25–28 are design-complete sketches. |
| 29 | **PARTIAL** | **M1 + its wire prerequisite BUILT 2026-08-24** (`0e1871a` + `9e4f935`, same day the mechanism was revised to buffered snapshot interpolation + kinematic drive after the deep-research pass — see the research banner; pure dead reckoning rejected). Unit-tested (42 new cases); **clean-link smoke VERIFIED in-game 2026-08-24** (orbit-pair glide + ambient NPC motion smooth, zero coop errors, ~0.1 ms avg frame cost; pause-lag red herring traced to a leftover Phase 18 latency lever in vmparams, launch scripts hardened in `e4f8fc9`). Remaining M1 QA: the shaped-loopback loss/reorder pass. M2 (adaptive cadence) NOT BUILT — needs 20, slots after it. Phase history: pulled into V1 2026-08-20 after the jumpy-mirror root cause was verified live (banner has the two measured failure modes); partner-mirror scope SETTLED: included. |

## Source Layout

All implementation work for v1 goes under `K:\Starsector\mods\coop`.

```text
mods/coop/
  mod_info.json
  build.gradle
  settings.gradle
  gradlew
  gradlew.bat
  gradle/wrapper/
  src/main/java/coop/
  src/test/java/coop/
  data/config/settings.json
  data/
  jars/coop.jar
  jars/coop-forks.jar
```

> Networking uses `java.nio` (no Netty); there is no `jars/netty/` dependency. See the Tech Stack note.

Core Java modules must keep these names unless a later phase explicitly changes this document:

- `coop.CoopModPlugin`
- `coop.session.CoopSessionState`
- `coop.session.CoopIronModeGuard`
- `coop.net.CoopNetService`
- `coop.net.CoopNetPump`
- `coop.handshake.CoopHandshakeManifest`
- `coop.net.CoopMessages`
- `coop.seed.CoopSeedSync`
- `coop.rng.CoopRandom`
- `coop.rng.CoopRandomForkAudit`
- `coop.time.CoopTimeLock`
- `coop.fleet.CoopFleetMirror`
- `coop.fleet.CoopPresenceIndicator`
- `coop.fleet.CoopNpcFleetReplicator`
- `coop.fleet.CoopFleetMirrorRegistry`
- `coop.fleet.CoopNpcFleetSuppressor`
- `coop.interaction.CoopInteractionGate`
- `coop.campaign.CoopCampaignReplicator`
- `coop.campaign.CoopMissionBoardSync`
- `coop.campaign.CoopBaseAuthority`
- `coop.combat.CoopBattleBridge` *(2026-06-10: replaces `CoopCombatSpectator`/`CoopCombatSpeedLock`/`CoopCombatDisconnectProtocol` — see Phase 14 revision)*
- ~~`coop.combat.CoopBattleStatusPanel`~~ — **deleted 2026-08-19** (spectator panel cancelled in favour of banners; see the Phase 14 revision note)
- `coop.combat.CoopNpcThreatWatcher`
- `coop.time.CoopSharedPauseCoordinator`
- `coop.combat.CoopBattleResultReconciler`
- `coop.save.CoopSaveCheckpoint` *(2026-06-10: replaces `CoopSaveExport` — see Phase 16 redesign)*
- `coop.net.CoopPeerLink` *(Phase 20: per-peer transport state — N-ready, capacity 1 in v1)*
- `coop.net.CoopLinkQuality` *(Phase 20: RTT/loss tracking, LINK_STATUS, UDP→TCP fallback decision)*
- `coop.net.CoopReconnectCoordinator` *(Phase 20: in-session reconnect grace window)*
- `coop.net.CoopPortMapper` *(Phase 20: UPnP IGD / NAT-PMP port mapping + CGNAT detection)*
- `coop.ui.CoopLinkHud` *(Phase 20.6: `CampaignUIRenderingListener` ping/loss/transport-state widget)*
- `coop.ui.CoopSessionIntel` *(Phase 20.6: "Coop Session" intel entry; Phase 21 adds the stats page)*
- `coop.ui.CoopLobbyDialog` *(Phase 21, post-V1: in-campaign lobby with ready-up)*
- `coop.ui.CoopDesyncDialog` *(Phase 21, post-V1: cause+remedy dialog on loud-failure points)*
- `coop.combat.CoopRemotePilotAI` *(Phase 22, post-V1: vendored MIT CoOpCombat pilot `ShipAIPlugin`, network-fed)*
- `coop.combat.CoopVirtualKeyboard` *(Phase 22, post-V1: guest key capture + `KEY_STATE` UDP channel + host adapter)*
- `coop.combat.CoopFleetManifest` *(Phase 22, post-V1: combat-grade guest-fleet sync)*
- `coop.combat.CoopTacticalMapPanel` *(Phase 22 milestone 0, post-V1: drawn tactical-map observer)*
- `coop.rewards.CoopRewardSplitter` *(created in Phase 24 (V1): colony-income split; extended in Phase 22 (post-V1): joint-combat spoils — see note below)*
- `coop.config.CoopOptionsRegistry` *(Phase 28, post-V1: typed option schema + the not-configurable list)*
- `coop.config.CoopOptionsStore` *(Phase 28, post-V1: `-D` → `saves/common` → shipped-default precedence stack, `SettingsAPI`-only)*
- `coop.ui.CoopOptionsPage` *(Phase 28, post-V1: registry-driven intel-button options editor)*
- `coop.fleet.CoopMotionInterpolator` *(Phase 29 M1: per-mirror snapshot-interpolation buffer + kinematic drive; revised from dead reckoning 2026-08-24)*
- `coop.net.CoopCadenceController` *(Phase 29 M2: per-link adaptive cadence tiers with hysteresis)*

*Registry completion (2026-06-10 review pass — classes that already existed on disk or in phase Files blocks but were missing from this list):*

- `coop.input.CoopCampaignInputBlocker` *(Phase 7: guest campaign input blocker; modified by 10, 11, 14, 25)*
- `coop.input.CoopHostPauseInputListener` *(Phase 11: host pause-key interceptor)*
- `coop.fleet.CoopFleetCodec` *(Phase 8: delimiter-escaped fleet snapshot text codec — the flat-JSON-envelope workaround)*
- `coop.campaign.CoopBarSync` *(Phase 12: bar sync; its `resolveHandles()` (lines ~170–178 as of 2026-08-25) is the canonical sandbox-safe MethodHandles pattern cited by 7b/7c/13 — it exists, do not recreate it)*
- `coop.net.CoopNetStartupConfig` *(Phase 3/4 era: launch/connection `-D` properties; modified by 20/27/28)*
- `coop.time.CoopFastForwardLock` *(Phase 7b: shared fast-forward restore)*
- `coop.time.CoopClockReconciler` *(Phase 7c: campaign-date drift correction)*
- `coop.fleet.CoopMirrorOrphanSweeper` *(Phase 12b: solo-load orphan sweep)*
- `coop.campaign.CoopBaseRecord` *(Phase 13: base-authority record)*
- `coop.save.CoopGuestSnapshot` *(Phase 16: guest fleet/cargo/credits DTO in host save persistentData)*
- `coop.fleet.CoopRespawnNotifier` *(Phase 17, rescoped 2026-08-20: observe the vanilla shuttle respawn + notify the partner; ~~`CoopFleetWipeRespawn`~~/~~`CoopRespawnPointTracker`~~ cancelled with the old spec — see the phase banner)*
- ~~`coop.interaction.CoopDockUiLocks` + `coop.interaction.CoopUiLock`~~ — **cancelled 2026-08-20** *(Phase 18 rescoped to interaction-gate WAN hardening inside `CoopNetPump`; no lock classes — see the phase banner)*
- `coop.colony.CoopColonySync` *(Phase 24, V1: colony lifecycle + management deltas)*
- `coop.colony.CoopRaidOutcomeSync` *(Phase 24, V1: hostile-act listener capture + apply)*
- `coop.colony.CoopExpeditionWarningIntel` *(Phase 24, V1: coop-owned mirrored expedition-warning intel entry)*

> `coop.rewards.CoopRewardSplitter` has **no v1 combat role**: v1 combat is solo own-fleet (one piloting player per battle keeps their own spoils), so there is nothing to split there. **(Corrected 2026-06-10 review pass:** Phase 24 (V1) *creates* the class for its colony-income split (default 50/50, decided 2026-06-10); Phase 22 (post-V1) later extends it for joint-combat spoils. Package is `coop.rewards` everywhere.)

## Design Alignment Notes

- `COOP_MP_DESIGN.md` is canonical. If this plan and the design conflict, stop and update this plan before writing code.
- Networking is TCP plus UDP, both implemented with `java.nio` (not Netty — see Tech Stack note). Phase 3 starts with TCP ping/pong only because it is the smallest reliable vertical slice; Phase 8 adds UDP campaign state at 10 Hz; Phase 9 replicates the full NPC fleet population via reliable TCP `NPC_FLEET_SET` (membership/rosters) and extends UDP only with `NPC_FLEET_MOTION` at 10 Hz for player-occupied locations. Combat adds **no UDP**: Phase 14's battle-status stream is low-rate TCP (revised 2026-06-10 — the 60 Hz UDP combat stream died with the rendered spectator; see Phase 14 engine facts). Critical events such as session accept/reject, NPC fleet set membership, mission claims, combat begin/end, destruction, rewards, save checkpoints, and disconnect remain TCP. **WAN posture (Phase 20, 2026-06-10):** the star topology means only the *host* must be Internet-reachable (guests punch their own NAT pinholes outbound); UDP datagrams are MTU-safe (≤ ~1,200 B payload, chunked batches) and carry `senderId` + `epoch` for latest-wins reorder protection; a blocked UDP path degrades to a logged 5 Hz TCP state stream rather than a silent mirror freeze; a dropped socket enters a reconnect grace window (world paused, full rebroadcast on resume) instead of ending the session; the envelope is N-ready (`senderId` + peer table) even though v1 gameplay stays host + 1 guest.
- Targeted source forks are in scope for v1 when `COOP_MP_DESIGN.md` section 7.5 names a high-impact unseeded RNG site. Forks are compiled into a separate `mods/coop/jars/coop-forks.jar` and that jar is **prepended to vmparams `-classpath` ahead of `starfarer.api.jar`** so the JVM resolves the mod copy of any shared class name first. The `mods/coop/data/scripts/com/fs/starfarer/api/...` path was investigated and proven not to override API jar classes — Starsector's `ScriptStore` (Janino `JavaSourceClassLoader`) uses parent-first delegation, so the API jar wins; spike with `AccretionDiskGenPlugin` produced no log evidence the forked source was ever compiled. This classpath-prepend approach is not the same as Java-agent instrumentation or broad global RNG patching; those remain out of scope.

## Class Override Mechanisms (proven)

| Mechanism | Overrides | Where to use |
|---|---|---|
| `data/config/settings.json` `plugins.<key>` | Engine-instantiated plugin classes (`newGameSectorProcGen`, `combatReadinessPlugin`, etc.) | Already used for `coop.seed.CoopSectorProcGen` in Phase 6. |
| `removeScript` + `addScript` at `onGameLoad` | Long-lived `EveryFrameScript`s registered on the `Sector` (e.g. `PersonBountyManager`) | Phase 9 guest-side NPC fleet-spawner suppression and Phase 13 dynamic terrain / pirate-base authority (host-authored managers). |
| `coop-forks.jar` prepended to vmparams `-classpath` ahead of `starfarer.api.jar` | Any class in `starfarer.api.jar` (or other core jars) by FQCN match | Phase 13 procgen RNG forks (`Misc`, `StarSystemGenerator`, etc.). |
| `data/scripts/com/fs/starfarer/api/...` source-fork | **Does not work** for API jar classes (ScriptStore parent-first delegation). | Do not use; documented here to prevent re-attempts. |
- Joining player reputation starts at 0 conceptually; the host-owned shared reputation table is applied by replicated events. Mission rewards from shared mission/bar pools go to the accepting player unless Phase 15 reward rules explicitly split a combat result.
- Campaign fleet snapshots are 10 Hz state streams. Combat spectating is a **battle-status stream at 2–5 Hz over TCP** (ship list, hull/flux fractions, alive state, kill feed) rendered as a status panel — *not* a rendered live combat view. **(Revised 2026-06-10:** the original 60 Hz UDP combat snapshot + "puppet battle" spectator was verified infeasible against the engine — see Phase 14's engine-facts block. Agents must not leave these rates implicit.)
- **Host self-healing backstop (cross-cutting principle).** Because the host continuously re-broadcasts authoritative state — the NPC fleet set (Phase 9), economy/market/rep/faction relations (Phase 12) — most *un-enumerated* guest-side dialog/rules divergences self-correct on the next rebroadcast. So v1 does **not** enumerate every Starsector `rules.csv` `CommandPlugin` outcome. Explicit replication is required only where a **guest-driven** interaction produces an outcome the host cannot otherwise observe (e.g. the guest pays tribute so a fleet should leave) — those funnel through the single `WORLD_DELTA` guest→host report (Phase 12). Host-owned state that the guest only *reads* needs no per-outcome wiring; the rebroadcast is the safety net.
- **Save & session policy (v1 decision, 2026-06-10).** V1 supports exactly two players with exact-install parity (Phase 5 handshake) resuming the *same campaign* (Phase 6b campaign UUID). The **host save** is the canonical campaign and stays solo-playable **with the coop mod enabled** — it runs a complete vanilla sim; the partner mirror is removed by the Phase 12b orphan sweep. **(Revised 2026-08-20, user decision:** Phase 16 embeds the guest snapshot DTO (`coop.guestFleetSnapshot`, an XStream-aliased mod class) in the host save's persistent data, so loading the host save *without the mod* fails on class resolution. Solo-without-the-mod compatibility was deliberately dropped — it was already the policy that mod-enabled play means coop rules, and solo players disable the mod on a fresh campaign, not a coop save.) The **guest save** is a coop-session save only: **solo-loading it is unsupported and undefended**, and no enforcement or restore code is built for it (Phase 9's suppressor `removeScript`-ed the vanilla NPC spawner scripts, so they are *absent from the guest save*; markets are host snapshots; rep is host-overwritten — restoring a native sim would be real work for zero v1 value). A guest who plays a coop save solo anyway and later reconnects gets exactly what host authority provides for free: shared state is overwritten by the rebroadcast backstop, the guest's own fleet/cargo/credits are per-player state they legitimately own, and structural world divergence is hard-rejected at seed lock by the fingerprint (the Phase 6b mutability contract) — a loud reject, never a silent desync. Converse case, also by design: host solo progress that structurally changes the world (e.g. a market decivilizes) invalidates the guest's older save at reconnect; the guest rejoins from the campaign's current state instead.
- **Phase renumbers (2026-05-30):** two insertions shifted the back half of the plan. First `NPC Fleet Replication` was inserted as **Phase 9**; then `Shared Pause Coordinator` was inserted as **Phase 11**. Net effect: the originally-numbered Phase 9–15 work is now Phase 10, 12, 13, 14, 15, 16, 17 respectively (each later phase shifted up by the insertions). On-disk artifacts created by work completed *before* these renumbers keep their original names — e.g. `mods/coop/docs/phase11-rng-determinism.md` and code/comments referencing "Phase 11" belong to what is now **Phase 13 (Dynamic Terrain Authority + Random Fork List)**. Do not rename those artifacts; this note is the bridge between the old and new numbering.

## Shared Verification Commands

Use these commands unless a phase gives a narrower command.

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"
```

Expected build result:

```text
BUILD SUCCESSFUL
```

Starsector load smoke test:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector'; .\starsector.exe"
```

Expected manual result:

```text
The game opens, the coop mod is visible/enabled in the launcher, and starsector.log contains the phase startup line with no coop stack trace.
```

## Phase 1: Scaffold

**Agent prompt:**

```text
Implement Phase 1 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Create the Starsector mod scaffold under K:\Starsector\mods\coop, including Gradle, mod_info.json, jar output layout, and CoopModPlugin. Do not implement networking yet. Verify build and game-load smoke test. Stop after the Phase 1 commit.
```

**Files:**

- Create `mods/coop/mod_info.json`
- Create `mods/coop/settings.gradle`
- Create `mods/coop/build.gradle`
- Create `mods/coop/src/main/java/coop/CoopModPlugin.java`
- Create `mods/coop/src/main/java/coop/util/CoopLog.java`
- Create `mods/coop/src/test/java/coop/CoopScaffoldTest.java`

**Steps:**

- [ ] Create `mods/coop/mod_info.json` with id `coop`, name `Starsector Coop V1`, version `0.1.0`, gameVersion `0.98a-RC8`, and mod plugin `coop.CoopModPlugin`.
- [ ] Configure Gradle to compile Java 17 source against `../../starsector-core/starfarer.api.jar` and `../../starsector-core/xstream-1.4.10.jar`.
- [ ] Configure Gradle `jar` output to write `jars/coop.jar`.
- [ ] Implement `CoopLog` as a small wrapper around `Global.getLogger(...)`.
- [ ] Implement `CoopModPlugin extends BaseModPlugin` and log `CoopModPlugin loaded` in `onApplicationLoad()`.
- [ ] Add a scaffold unit test that asserts the plugin class and module package are loadable by the test JVM.
- [ ] Run `powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"`.
- [ ] Launch Starsector once and confirm the mod loads without a coop stack trace.
- [ ] Commit with `git add mods/coop && git commit -m "chore: scaffold coop mod"`.

**Acceptance:**

- `mods/coop/jars/coop.jar` exists after build.
- Starsector can start with the mod present.
- `starsector.log` contains `CoopModPlugin loaded`.

## Phase 2: Local Build Loop

**Agent prompt:**

```text
Implement Phase 2 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add repeatable local build, clean, deploy, and launch instructions/scripts for the coop mod. Do not change runtime behavior except logging build metadata if needed. Verify build commands.
```

**Files:**

- Create `mods/coop/README_DEV.md`
- Create `mods/coop/scripts/build.ps1`
- Create `mods/coop/scripts/clean.ps1`
- Modify `mods/coop/build.gradle`

**Steps:**

- [ ] Add `README_DEV.md` with exact build, test, jar, launch, and log-inspection commands.
- [ ] Add `scripts/build.ps1` that runs `.\gradlew.bat clean test build` from `mods/coop`.
- [ ] Add `scripts/clean.ps1` that removes only `mods/coop/build` and `mods/coop/jars/coop.jar`.
- [ ] Add Gradle manifest attributes: `Implementation-Title`, `Implementation-Version`, and `Coop-Build-Time`.
- [ ] Run `powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\build.ps1'`.
- [ ] Run `powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\clean.ps1'`.
- [ ] Run the build script again and confirm `jars/coop.jar` is restored.
- [ ] Commit with `git add mods/coop && git commit -m "chore: document coop build loop"`.

**Acceptance:**

- A new agent can build and clean the mod by following `README_DEV.md`.
- The clean script does not delete source files, config, saves, or non-coop game files.

## Phase 3: Net Pump Hello World

> **Superseded note (2026-06-10):** the prompt/Files/Steps below still name **Netty** — that is the historical record only. Netty was removed from the stack (its reflection init is sandbox-blocked — see the Tech Stack note; enforced by `CoopNetServiceSandboxCompatibilityTest`) and the shipped `CoopNetService` is plain `java.nio`. There is no `jars/netty/` directory. Do not bundle Netty under any circumstances; this phase is BUILT (see the Phase Status Ledger).

**Agent prompt:**

```text
Implement Phase 3 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add the minimal Netty TCP host/client service, a runWhilePaused EveryFrameScript pump, and ping/pong messages. Do not add lobby, seed, fleet, or campaign replication yet.
```

**Files:**

- Create `mods/coop/src/main/java/coop/net/CoopNetService.java`
- Create `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Create `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Create `mods/coop/src/main/java/coop/net/CoopConnectionRole.java`
- Create `mods/coop/src/test/java/coop/net/CoopMessagesTest.java`
- Modify `mods/coop/CoopModPlugin.java`
- Add Netty jars under `mods/coop/jars/netty/`
- Modify `mods/coop/build.gradle`

**Steps:**

- [ ] Bundle Netty jars under `jars/netty/` and add them to `mod_info.json` and Gradle runtime/test classpaths.
- [ ] Define `CoopMessages` envelope fields: `type`, `sessionId`, `seq`, `sentAtMillis`, `payloadJson`.
- [ ] Implement message types `HELLO`, `PING`, `PONG`, and `DISCONNECT`.
- [ ] Implement deterministic JSON encode/decode tests for `PING` and `PONG`.
- [ ] Implement `CoopNetService.startHost(port)`, `connect(host, port)`, `send(message)`, `pollInbound()`, and `shutdown()`.
- [ ] Implement `CoopNetPump implements EveryFrameScript` with `runWhilePaused() == true`, outbound drain, inbound poll, and ping timer.
- [ ] Register `CoopNetPump` from `CoopModPlugin.onGameLoad(boolean newGame)`.
- [ ] Add console/log controls by memory flags for first pass: `coop.hostPort`, `coop.connectHost`, `coop.connectPort`.
- [ ] Run unit tests for message serialization.
- [ ] Run two local instances and confirm ping/pong logs while campaign is paused and unpaused.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add coop tcp ping pump"`.

**Acceptance:**

- Host logs inbound `PING` and outbound `PONG`.
- Guest logs outbound `PING` and inbound `PONG`.
- The pump advances while paused.

## Phase 4: Session State + Lobby

**Agent prompt:**

```text
Implement Phase 4 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add explicit host/guest connection state and a minimal pre-handshake lobby over the existing TCP connection. Do not allocate canonical gameplay session state yet; Phase 5 promotes a validated connection to a session.
```

**Files:**

- Create `mods/coop/src/main/java/coop/session/CoopSessionState.java`
- Create `mods/coop/src/main/java/coop/session/CoopPlayerInfo.java`
- Create `mods/coop/src/main/java/coop/session/CoopLobbyState.java`
- Create `mods/coop/src/test/java/coop/session/CoopSessionStateTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`

**Steps:**

- [ ] Define roles `NONE`, `HOST`, `GUEST`.
- [ ] Define session fields: nullable `sessionId`, `provisionalLobbyId`, `localPlayerId`, `remotePlayerId`, `localName`, `remoteName`, `role`, `connectionState`, and `handshakeValidated`.
- [ ] Add message types `LOBBY_HELLO`, `LOBBY_ACCEPT`, and `LOBBY_REJECT`.
- [ ] Host creates a `provisionalLobbyId` and local `playerId`; guest creates local `playerId` before connecting.
- [ ] Guest sends `LOBBY_HELLO`; host replies `LOBBY_ACCEPT` with provisional lobby id and host player info.
- [ ] Keep `sessionId == null` and `handshakeValidated == false` after Phase 4; no seed, fleet, or campaign state may bind to this lobby yet.
- [ ] Add unit tests for valid transition `NONE -> HOST_WAITING -> HOST_CONNECTED` and `NONE -> GUEST_CONNECTING -> GUEST_CONNECTED`.
- [ ] Reject a second guest with `LOBBY_REJECT`.
- [ ] Run tests and a two-instance smoke test.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add coop lobby session state"`.

**Acceptance:**

- Both instances log the same `provisionalLobbyId`.
- Host and guest agree on local/remote player ids.
- A second connection attempt is rejected.
- `sessionId` is still null until Phase 5 accepts the manifest.

## Phase 5: Version + Mod Handshake

**Agent prompt:**

```text
Implement Phase 5 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add exact Starsector version, enabled mod metadata, file checksums, coop commit/build hash, and iron-mode refusal to the lobby handshake. Matching non-iron installs connect; mismatches reject with a readable diff before canonical session state is created.
```

**Files:**

- Create `mods/coop/src/main/java/coop/handshake/CoopHandshakeManifest.java`
- Create `mods/coop/src/main/java/coop/handshake/CoopHandshakeDiff.java`
- Create `mods/coop/src/main/java/coop/handshake/CoopChecksum.java`
- Create `mods/coop/src/main/java/coop/session/CoopIronModeGuard.java`
- Create `mods/coop/src/test/java/coop/handshake/CoopHandshakeManifestTest.java`
- Create `mods/coop/src/test/java/coop/session/CoopIronModeGuardTest.java`
- Modify `mods/coop/src/main/java/coop/session/CoopSessionState.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [ ] Implement `CoopHandshakeManifest.capture()` using `Global.getSettings().getGameVersion()` and `Global.getSettings().getModManager().getEnabledModsCopy()`.
- [ ] Include each enabled mod's `id`, `name`, `version`, `gameVersion`, `path`, `jars`, and SHA-256 checksums for `mod_info.json` plus listed jars. *(As-built sandbox correction: real file hashing needs `java.io`, which is runtime-blocked — checksums ship as the literal placeholder `UNAVAILABLE:script-sandbox`; see the Acceptance scope correction. The bounded `loadText` hashing spike is Phase 12b's.)*
- [ ] Include coop build version and Git commit hash from Gradle manifest when available; use `dev-uncommitted` only when no Git metadata is available.
- [ ] Implement `CoopIronModeGuard` by reading the active/new-game iron flag when available and, for loaded saves, by checking the save descriptor/persistent metadata field serialized as `isIronMode`; reject if true.
- [ ] Add `HANDSHAKE_MANIFEST` and `HANDSHAKE_RESULT` messages.
- [ ] Host compares manifests and iron-mode state, then returns either accept or reject with line-oriented diff.
- [ ] On accept only, host allocates canonical `sessionId`, sets `handshakeValidated = true`, and sends that `sessionId` in `HANDSHAKE_RESULT`.
- [ ] Add tests for matching manifests, game version mismatch, missing mod, version mismatch, checksum mismatch, iron-mode reject, and post-accept `sessionId` allocation.
- [ ] Run tests and two-instance smoke test with a deliberate manifest mismatch.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add exact coop handshake manifest"`.

**Acceptance:**

- Identical installs connect.
- Mismatched installs reject before gameplay state is created. *(Scope correction, 2026-06-10 QA pass: this holds for game version, mod id/name/version/gameVersion, coop build/git commit, and iron mode — but **file checksums are inert placeholders**. Both sides emit the literal string `UNAVAILABLE:script-sandbox` for every file (`CoopHandshakeManifest.fromModSpec`), equal strings produce no diff, so differing jar **contents** with matching version metadata connect silently. This is the documented script-sandbox limitation; the coop jar itself stays covered by the git-commit comparison and worldgen divergence by the Phase 6 fingerprint. Phase 12b carries the follow-up: a bounded `loadText` hashing spike + finalizing the docs.)*
- Reject log names the exact mismatched field.
- Iron-mode new games and iron-mode saves are refused before gameplay state is created.
- Both instances log the same canonical `sessionId` only after handshake acceptance.

## Phase 6: Seed Lock

**Agent prompt:**

```text
Implement Phase 6 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add shared new-game seed/seedString and a structural sector fingerprint. Use CharacterCreationData seed for procgen; do not rely on SectorAPI.setSeedString as the procgen driver.
```

**Files:**

- Create `mods/coop/src/main/java/coop/seed/CoopSeedSync.java`
- Create `mods/coop/src/main/java/coop/seed/CoopSectorFingerprint.java`
- Create `mods/coop/src/test/java/coop/seed/CoopSectorFingerprintTest.java`
- Modify `mods/coop/src/main/java/coop/CoopModPlugin.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [ ] Add session fields `seedLong`, `seedString`, and `sectorFingerprint`.
- [ ] Host generates `seedLong` and `seedString` before a coop new game.
- [ ] Guest receives seed data before procgen and applies the same `CharacterCreationData.setSeed(long)` and `setSeedString(String)`.
- [ ] Store seed data in `Global.getSector().getPersistentData()` after game load.
- [ ] Implement fingerprint from system ids, market ids, and hyperspace anchor coordinates rounded to stable integer values.
- [ ] Add `SEED_LOCK_REQUEST`, `SEED_LOCK_ACK`, and `SEED_LOCK_REJECT` messages.
- [ ] Add unit tests for stable sorted fingerprint output independent of insertion order.
- [ ] Run a two-instance fresh-game test and compare fingerprint logs.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add coop seed lock"`.

**Acceptance:**

- Host and guest log identical `seedString` for matching fresh games when launched with the same `-Dcoop.newGameSeed=MN-<positive long>` JVM property. Verified end-to-end without manual paste.
- Fingerprint mismatch rejects session start. **Identical fingerprints across a two-instance fresh-game test were achieved in Phase 13 (2026-05-29)** via the `coop-forks.jar` classpath-prepend forks (`Misc` + deep-space `GateHaulerLocation`/`NamelessRock`) and scoping the fingerprint to deterministic content (`CoopSectorFingerprint` excludes hidden dynamic base markets). See `mods/coop/docs/phase11-rng-determinism.md`.
- `coop.newGameSeed` JVM property overrides `CharacterCreationData.setSeed`/`setSeedString` via `coop.seed.CoopSectorProcGen` (registered through `data/config/settings.json` at `plugins.newGameSectorProcGen`). Sector label is forced post-procgen via `Global.getSector().setSeedString(...)` because vanilla locks `sector.seedString` from the new-game UI text field before procgen.
- Seed-long derivation from a seed string masks the sign bit (vanilla `SectorProcGen.prepare` skips `setSeed` when seed `<= 0`).

> **Hardening follow-up (2026-06-10):** a code audit confirmed Phase 6 as shipped is correct end-to-end (consistent SHA-256 seed derivation across `CoopSeedSync`/`CoopRandom`/`CoopSectorProcGen`, order-independent fingerprint, correct hidden-market exclusion, sound SEED_LOCK guards). It also found four gaps — a campaign-identity hole, undiagnosable fingerprint mismatches, a latent API trap, and an undocumented mutability contract — folded in as **Phase 6b** below. The audit's fifth finding (slipstream RNG divergence) is runtime randomness, out of seed scope by design; it is folded into Phase 13.

## Phase 6b: Seed Lock Hardening (Campaign Identity + Diagnosable Fingerprint)

**Agent prompt:**

```text
Implement Phase 6b from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add a per-campaign coop UUID to the seed lock, dump the canonical fingerprint text on mismatch, delete the inconsistent CoopSeedSync.seedData(long) factory, and wire real mod_info.json checksums into the handshake manifest (the 12b probe proved SettingsAPI.loadText works in the sandbox). Read the "Audit findings" and "Policy decisions" lists first and implement them as written; do not redesign them.
```

**Audit findings (verified 2026-06-10 against the shipped code — do not re-litigate):**

1. **Replay hole.** The seed lock validates `seedString` equality plus the structural `sectorFingerprint`. Both pass identically for a *fresh re-roll* of the same `-Dcoop.newGameSeed`, because both are pure functions of the seed. A guest whose save was lost can re-roll a new game with the same seed and connect "successfully" into a mid-flight campaign their save was never part of — every downstream replication layer then reconciles against a divergent world with no error raised anywhere. Nothing in the protocol identifies *the campaign* as opposed to *the worldgen*.
2. **Stored seed data is write-only.** `CoopSeedSync.storeCurrentSectorPersistentData(...)` writes `coop.seedLong`/`coop.seedString`/`coop.sectorFingerprint` into `Global.getSector().getPersistentData()` on both sides at lock time, but nothing ever reads them back (validation always recomputes live, which is correct). The campaign UUID below gives this storage its first load-bearing read.
3. **Fingerprint mismatches are undiagnosable.** The reject reason is `sectorFingerprint: host=<sha256> guest=<sha256>` — two hashes, zero clue which of the ~272 canonical entries diverged. `CoopSectorFingerprint.canonical(...)` already produces the exact line-per-entry text the SHA is computed over; it is currently unused outside tests.
4. **`CoopSeedSync.seedData(long)` builds a self-inconsistent pair.** It returns `(seedLong, formatSeedString(seedLong))`, but everywhere else the long is SHA-256-derived FROM the string (`stableSeedLong`, `CoopRandom`, `CoopSectorProcGen`), so `seedDataFromSeedString(seedData(N).seedString()).seedLong() != N`. Unused in production (test-only today). If a future "host generates the seed" change ever uses it, host and guest would procgen different sectors while logging the same seedString.
5. **The fingerprint's mutable fields are an undocumented contract.** `marketSize` and `factionId` are mutable campaign state, and the fingerprint is re-validated on every session start *including loaded-save reconnects* (`hostSeedSupplier = seedForLoadedSector`). That is deliberate and good — it is the tripwire proving both saves evolved identically — but it means **any future feature that mutates market size, faction ownership, or market existence (player colonization is the first realistic vector; decivilization the second) must ship with replication of that mutation to the guest's save, or the next reconnect hard-rejects with no heal path.** This contract must be written down where future phases will see it.

**Policy decisions (already made — implement, don't redesign):**

- **Campaign UUID, host-minted, once.** At seed-lock time the host reads `coop.campaignId` from sector persistent data; if absent it mints one (`UUID.randomUUID().toString()` — already the id pattern in `CoopSessionState`) and stores it. The same id is sent in every `SEED_LOCK_REQUEST` for the life of the campaign, across sessions and saves.
- **Guest matching rule:** no stored id → adopt the host's (store it) and continue — this covers both the first session of a campaign and the migration path for existing pre-6b saves; stored id equals host's → continue; stored id differs → **reject** with a reason naming both ids and the remedy. *(**AMENDED as-built, found by smoke step 3 (2026-08-17):** adopt-on-absent as written was self-defeating — a fresh same-seed re-roll also presents as "no stored id", so the exact replay case this phase exists to reject was waved through, and the first in-game run of step 3 connected "as if everything is fine". The amended rule uses a mint-time signal: the host sends `campaignIdMinted` in the `SEED_LOCK_REQUEST` (true only at the id's birth seed lock). Guest with no stored id: host minted now → adopt (campaign being born); host id pre-existing + save carries pre-6b coop seed markers (`coop.seedString` in persistent data) → adopt (migration); host id pre-existing + no markers → **reject** naming the adopt flag — this is also the sanctioned save-less-guest rejoin path, made one switch via `launch-guest.ps1 -AdoptCampaign`.)*
- **Explicit-consent override, not auto-heal:** relaunching the guest with `-Dcoop.adoptCampaignId=true` overwrites the guest's stored id with the host's and continues. The reject message must name this flag. No silent adoption on mismatch — a differing id means the saves genuinely belong to different campaigns, and adopting anyway is a knowing acceptance of state divergence.
- **Check order in `handleSeedLockRequest`:** campaignId (identity) → seedString (worldgen input) → fingerprint (worldgen + replicated-state output). Identity first, so "wrong save" produces the clear message instead of a confusing state diff.
- **Canonical dump instead of a diff protocol.** No new messages, no large payloads (the framed-JSON-line transport has a fixed buffer — do not push ~11 KB of canonical text through it): on a fingerprint mismatch, *each side logs its own full canonical text* and the humans diff the two log files. The guest dumps where its comparison fails; the host dumps when it sends or receives a seed-lock reject whose reason contains `sectorFingerprint`. Include the entry count in the dump header. ~272 lines once per failed session start — log it unconditionally, no `CoopDebug` gate.
- **Keep `marketSize`/`factionId` in the fingerprint.** Splitting structural vs mutable fingerprints was considered and rejected: the mutable tripwire is the mechanism that *enforces replication completeness* at reconnect. The fix for a trip is to add the missing replication, never to relax the check.
- **Delete `seedData(long)`** outright; the test using it should call `formatSeedString(...)` directly.

**Files:**

- Modify `mods/coop/src/main/java/coop/seed/CoopSeedSync.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/test/java/coop/seed/CoopSeedSyncTest.java`
- Modify `mods/coop/src/test/java/coop/net/CoopNetPumpTest.java` (existing seed-lock tests live here)
- Modify `mods/coop/src/test/java/coop/net/CoopMessagesTest.java`
- Modify `mods/coop/scripts/launch-guest.ps1` *(as-built: `-AdoptCampaign` switch for the save-less rejoin path)*
- Modify `mods/coop/docs/phase11-rng-determinism.md` (mutability contract + gen-time-only principle)

**Steps:**

- [x] `CoopSeedSync`: delete `seedData(long)`; fix `CoopSeedSyncTest.formatSeedString` coverage to call `formatSeedString` directly.
- [x] `CoopSeedSync`: add `PERSISTENT_CAMPAIGN_ID = "coop.campaignId"`, plus `currentCampaignId()` (read from sector persistent data; empty string when absent or no sector — same try/catch shape as `currentSectorSeedString`) and `storeCampaignId(String)`.
- [x] `CoopSeedSync`: add `currentSectorFingerprintCanonical()` returning `CoopSectorFingerprint.canonical(sector)` with the usual guard (empty on failure).
- [x] `CoopMessages`: add a required `campaignId` payload field to `seedLockRequest(...)`.
- [x] `CoopNetPump` host (`maybeSendSeedLockRequest`): resolve campaignId = stored-or-minted (mint + store when empty) and include it in the request. Inject as `Supplier<String> storedCampaignIdSupplier` + `Consumer<String> campaignIdStore` constructor parameters defaulting to the `CoopSeedSync` statics, following the existing supplier-injection pattern, so unit tests need no `Global`.
- [x] `CoopNetPump` guest (`handleSeedLockRequest`): campaignId check first per policy; on mismatch send `SEED_LOCK_REJECT` with reason `campaignId: host=<id> guest=<id> — guest save is not from this coop campaign; to adopt the host campaign anyway, relaunch the guest with -Dcoop.adoptCampaignId=true`; honor the adopt flag (`Boolean.parseBoolean(System.getProperty(...))`, injectable as a `BooleanSupplier` for tests); on adopt-or-absent, store the host id before continuing to the seedString check.
- [x] Canonical dump wiring: guest logs `Coop fingerprint canonical (<n> entries):\n<canonical>` when its fingerprint comparison fails; host logs the same when sending or receiving a seed-lock reject whose reason contains `sectorFingerprint`. Use an injected `Supplier<String>` for the canonical (default = the new `CoopSeedSync` helper). *(As-built: the receive-side dump fires for either role — the guest also dumps when the host's ack-check rejects, so both logs are diffable no matter which side tripped.)*
- [x] Tests: host mints once and reuses the stored id across pump restarts; guest adopts when absent; guest rejects on mismatch with reason prefix `campaignId:` naming the flag; adopt flag overrides; campaignId mismatch wins over a simultaneous fingerprint mismatch (check order); canonical dump fires on fingerprint mismatch and not on success; `seedLockRequest` round-trips `campaignId`.
- [x] **Real handshake checksums (moved in from 12b, verdict 2026-08-17):** the `CoopChecksumProbe` drill run logged `SUCCESS` on both clients with identical hashes — `SettingsAPI.loadText("mod_info.json", modId)` works inside the sandbox. Wire it: `CoopHandshakeManifest.capture` hashes each enabled mod's `mod_info.json` via `loadText` + `CoopChecksum.sha256Text` (keep the `UNAVAILABLE:script-sandbox` placeholder as the per-mod fallback when `loadText` throws, so one unreadable mod degrades that entry instead of failing capture); jar checksums stay `UNAVAILABLE` (no file API). Delete `CoopChecksumProbe` and its `CoopModPlugin` call site; update `CoopHandshakeSandboxCompatibilityTest` (it currently pins the probe arrangement) and `docs/starsector-runtime-limitations.md`. Mind the known hazard: catch `Throwable`, not `IOException` — naming the checked exception type makes the verifier resolve a `java.io` type in the calling class.
- [x] Docs: append to `mods/coop/docs/phase11-rng-determinism.md`: (a) the finding-5 mutability contract, verbatim enough that a future phase author trips over it; (b) a pointer to the gen-time-only principle now stated in Phase 13.
- [x] Run `powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"`. *(2026-08-17: green, first run.)*
- [x] Deploy with `scripts\deploy-to-test-clients.ps1`, relaunch both games, run the smoke test below.
- [x] Commit deferred until repo init; message: `feat: add coop campaign identity + diagnosable seed-lock fingerprint`. *(Landed as three commits: `b3246f2` (implementation), `6c45fea` (perf fixes found by the smoke session), `0d35349` (mint-signal policy amendment found by smoke step 3).)*

**Manual smoke test (two instances):** *(**Run 2026-08-17.** Steps 1–4 pass; step 3 FAILED on first run and was fixed live — see the amended guest matching rule above. Step 5 skipped: a genuine fingerprint mismatch was not inducible against a short-lived host campaign (the fingerprint is worldgen-derived and nothing had mutated market size/faction/existence); the dump path is pinned by unit test (`canonicalFingerprintIsDumpedOnMismatchAndNotOnSuccess`) and will get its first live firing whenever a real mismatch occurs. Bonus finding: the first save-loaded session exposed two performance defects — the mirror roster-rebuild storm and the orbit-stream balloon — fixed in `6c45fea`; guest fps recovered from 39 to ~56 of 59.)*

1. **Migration + adoption:** start a session on the existing pair of test saves (neither has a campaignId yet). Expect host log to show a minted `campaignId=<uuid>`, guest log to show adoption of the same id, and the session to proceed normally. Save on both sides. *(**Pass** (run as fresh new games — campaign birth): host minted `a657ae8c-…`, guest adopted the identical id, session proceeded.)*
2. **Identity persists:** quit and reconnect the same saves. Expect both logs to show the same campaignId as step 1 with no new minting. *(**Pass:** both reloaded processes carried `a657ae8c-…`, no "minted" line, and step 3's later host log announced it as pre-existing (`minted=false`).)*
3. **Replay reject:** on the guest, start a FRESH game with the same `-Dcoop.newGameSeed` (do not load the coop save) and connect. Expect `SEED_LOCK_REJECT` with reason starting `campaignId:` naming both ids and the adopt flag; session refused before any gameplay sync. This is the hole the phase exists to close. *(**Failed first run — connected "as if everything is fine"** because adopt-on-absent covered the fresh roll too; fixed by the mint-signal amendment (commit `0d35349`) and re-verified: reject fired with `campaignId: host=a657ae8c-… guest=<none>; this campaign is already in flight…` before any world sync.)*
4. **Consent override:** relaunch the guest from step 3 with `-Dcoop.adoptCampaignId=true`. Expect adoption and the seed lock to proceed to the seedString/fingerprint checks (which may themselves legitimately reject if host campaign state has drifted from fresh worldgen — that is correct behavior, not a bug in the flag). *(**Pass**, via the new `launch-guest.ps1 -AdoptCampaign` switch: "adopted by explicit override … fresh-start divergence knowingly accepted", then a clean seed lock and a working session.)*
5. **Canonical dump drill:** induce a seedString-equal/fingerprint-different pair (same seed, different new-game options on the guest's fresh roll — e.g., a different starting choice — combined with the adopt flag so the campaignId check passes). Expect full canonical dumps in BOTH logs; diff them and confirm the divergent entries are identifiable by line.

**Acceptance:**

- A re-rolled same-seed guest save is rejected with a `campaignId:` reason before any gameplay sync; the adopt flag is the only way through, and it works.
- Existing pre-6b saves connect seamlessly via adopt-on-absent migration.
- Any fingerprint mismatch leaves a full canonical dump in both logs, and the two dumps diff to the exact divergent entries.
- `CoopSeedSync.seedData(long)` no longer exists; the full gradle suite is green.
- The mutability contract and the gen-time-only pointer are present in `phase11-rng-determinism.md`.
- No `java.lang.reflect` / `java.io.*` in runtime code; no new TCP messages or payload bloat beyond the single `campaignId` field.

## Phase 7: Time Lock

**Agent prompt:**

```text
Implement Phase 7 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Host owns campaign pause, fast-forward, and clock. Guest mirrors host and consumes local pause/fast-forward input.
```

**Files:**

- Create `mods/coop/src/main/java/coop/time/CoopTimeLock.java`
- Create `mods/coop/src/main/java/coop/input/CoopCampaignInputBlocker.java`
- Create `mods/coop/src/test/java/coop/time/CoopTimeSnapshotTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [x] Define `TIME_SNAPSHOT` payload fields: `paused`, `fastForward`, `timestampMillis`, `campaignDay`, `sentAtMillis`.
- [x] Host sends `TIME_SNAPSHOT` at 5 Hz while connected.
- [x] Guest applies `Global.getSector().setPaused(hostPaused)` each frame. **Fast-forward correction:** the plan's `setFastForwardIteration(hostFastForward)` assumption is wrong — that flag is internal/overwritten and has no effect, and `setInFastAdvance` sticks but does not change the guest clock rate. Hold-Shift fast-forward is a 2x loop inside obfuscated `CampaignState` with no public on/off lever (verified Phase 7; see `mods/coop/docs/starsector-runtime-limitations.md`). Resolved by locking the session to 1x via `data/config/settings.json` `"campaignSpeedupMult":1` so no client can fast-forward; `apply()` still mirrors the FF flag via `setInFastAdvance` for animation/UI consistency only.
- [x] Implement `CampaignInputListener.processCampaignInputPreCore` to consume pause/fast-forward inputs on guest. **Control-name correction:** the campaign pause control is `GENERAL_PAUSE`, not `PAUSE` (passing `PAUSE` to `isControlActivated` threw `IllegalArgumentException` and crashed the client). FF control is `FAST_FORWARD`.
- [x] Register and unregister the input blocker based on session role.
- [x] **Connect-time alignment:** host holds the campaign paused until the session is active (`handshakeValidated && seedLong != null`), because there is no public clock-setter or drivable fast-advance to let a late-joining guest catch up. Prevents the multi-second-connect day gap. See `CoopNetPump.maybeHoldHostPausedUntilSessionReady`.
- [x] Add tests for serializing/deserializing time snapshots.
- [x] Run two-instance smoke test: host pauses/unpauses and guest follows; guest input does not change local pause state. Verified: pause syncs, host clock held during connect (date matches on join), `clockTs` stays 1x while `isFastForward()` is true.
- [ ] Commit with `git add mods/coop && git commit -m "feat: lock guest campaign time to host"`.

**Acceptance:**

- Guest cannot independently pause or fast-forward. *(Pause: blocked + mirrored. Fast-forward: neutralized session-wide at 1x via `campaignSpeedupMult:1`, since it cannot be blocked/mirrored via public API.)* **Note (Phase 11):** a later phase promotes a guest-requested **shared** pause (combat-start and blocking-screen) computed as an OR-of-intents by the host; that keeps both clocks locked together and does not relax this "no independent/local pause" invariant.
- Guest campaign time follows host snapshots. *(Pause mirrored; both run at 1x; host paused through connect so the guest starts aligned.)*

> **Superseded in part by Phase 7b (2026-06-10):** the "fast-forward cannot be mirrored or blocked" verdict only holds for vanilla's default *hold*-Shift input mode. Vanilla also has a *toggle* fast-forward mode (settings-menu checkbox) in which the fast-forward state is a persistent, consumable, MethodHandles-settable field — Phase 7b restores shared fast-forward through it. The `campaignSpeedupMult:1` lock built here remains the runtime fallback when Phase 7b's handles fail to resolve.

## Phase 7b: Fast-Forward Restore (Shared Time Speed)

**Agent prompt:**

```text
Implement Phase 7b from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Restore campaign fast-forward (hold-Shift time speedup) as a host-controlled shared time speed: force vanilla's toggle-fast-forward input mode during a coop session, mirror the host's fastForward state onto the guest via a MethodHandles field write, and remove the campaignSpeedupMult:1 lock. Read the "Why this is possible now" section below in full before writing any code — it contains verified engine facts you must not re-derive or second-guess from the older Phase 7 notes.
```

**Why this is possible now (verified engine facts — do not re-litigate):**

These facts were established 2026-06-10 by disassembling `starsector-core/starfarer_obf.jar` (0.98a-RC8) with `javap`; dumps are in `K:\Starsector\tmp_ff_analysis\` if you need to re-check. They supersede the "fast-forward has no public on/off setter" section of `mods/coop/docs/starsector-runtime-limitations.md`.

1. **`com.fs.starfarer.campaign.CampaignState` *is* the `CampaignUIAPI` implementation.** The object returned by `Global.getSector().getCampaignUI()` is a `CampaignState` instance. It declares a private, *unobfuscated-name* field `boolean fastForward`; the public `CampaignUIAPI.isFastForward()` simply returns that field.
2. **The speed loop reads the field and the multiplier fresh every frame.** Inside `CampaignState.advance`: `iters = fastForward ? Math.round(StarfarerSettings.getFloat("campaignSpeedupMult")) : 1`, then it calls `CampaignEngine.advance()` `iters` times. So (a) setting the field changes the speed starting next frame, and (b) the multiplier can be changed at runtime via the public `SettingsAPI.setFloat("campaignSpeedupMult", x)` (the engine does a live `getFloat` per frame — `settings.json` is only the initial value).
3. **There are TWO input paths that write `fastForward`, selected by a static boolean in `com.fs.starfarer.settings.StarfarerSettings`** (the vanilla settings-menu checkbox labeled `Campaign "speed up time" is a toggle`):
   - **Hold mode (vanilla default, toggle flag = false):** every frame, `CampaignState.processInput` overwrites `fastForward` from a *raw key-state poll*. This path cannot be blocked or out-written from a mod — any value we write is clobbered the next frame. **This is the only mode Phase 7 tested**, and every Phase 7 "impossible" conclusion is true *in this mode only*.
   - **Toggle mode (toggle flag = true):** the per-frame poll block is *skipped entirely*. `fastForward` is flipped only by a discrete `FAST_FORWARD` key *event*, and that code checks `event.isConsumed()` first. Two consequences: our existing guest input blocker (which already consumes `FAST_FORWARD` events pre-core, `CoopCampaignInputBlocker.java`) **fully blocks the guest from changing it**, and a MethodHandles write of the field **sticks** between frames.
4. **The toggle flag's accessors on 0.98a-RC8** (obfuscated names — pinned safe because the Phase 5 handshake enforces exact game-version match): getter `public static boolean Oo0000()`, setter `public static void ?00000(boolean)`, both on `com.fs.starfarer.settings.StarfarerSettings`. ⚠️ The name `?00000` is heavily overloaded on that class (e.g. `?00000(String):float` is `getFloat`); you MUST disambiguate with an exact `MethodType.methodType(void.class, boolean.class)` in `findStatic` — never look up by name alone.
5. **MethodHandles is the proven sandbox-safe access mechanism** (`java.lang.reflect.*` is hard-blocked by the script classloader and crashes in-game even though it compiles and passes unit tests). The working in-game pattern is `MethodHandles.privateLookupIn(...)` + `findGetter`/`findSetter` — see `CoopBarSync.resolveHandles()` (`mods/coop/src/main/java/coop/campaign/CoopBarSync.java:170-178`) and copy its lazy-resolve + `Throwable`-catch structure exactly.
6. **`starfarer_obf.jar` is NOT on the mod's compile classpath** (`build.gradle` lists only `starfarer.api.jar` + support jars), so you cannot write `CampaignState.class` or `StarfarerSettings.class` literals. Get the classes at runtime instead, with no compile-time dependency:
   - `CampaignState`: `Object ui = Global.getSector().getCampaignUI(); Class<?> campaignStateClass = ui.getClass();` (no name string needed at all). If `findSetter` throws `NoSuchFieldException`-wrapped errors, walk `getSuperclass()` defensively, but on 0.98a-RC8 the field is declared on the runtime class itself.
   - `StarfarerSettings`: `Class.forName("com.fs.starfarer.settings.StarfarerSettings", false, campaignStateClass.getClassLoader())`. `Class.forName` is `java.lang`, not `java.lang.reflect`, and is expected to pass the sandbox like `privateLookupIn` does — but it has not been smoke-tested before, so treat "the toggle-flag handles resolve in-game" as a thing the smoke test must explicitly confirm. **Fallback if it trips the sandbox:** add `"${starsectorCore}/starfarer_obf.jar"` to the `compileOnly`/`testImplementation` lists in `build.gradle` and use the `StarfarerSettings` class literal (its class and getter names are valid Java identifiers; only the setter needs `findStatic` by name).
7. **Failure must degrade to today's behavior, never to desync.** If any handle fails to resolve, the session must run locked at 1x exactly like current Phase 7 — achieved at runtime via the *public* `Global.getSettings().setFloat("campaignSpeedupMult", Float.valueOf(1f))` (no handles needed), since this phase removes the static `settings.json` 1x override.

**v1 policy decisions (already made — implement as stated):**

- **Host-only control.** Only the host's Shift press changes time speed; the guest's `FAST_FORWARD` input stays consumed (it already is — no new code). Guest-requested fast-forward (AND-of-intents like the Phase 11 pause OR) is explicitly deferred — sketched as post-V1 **Phase 25** (2026-06-10).
- **Fixed 2x.** Both clients force `campaignSpeedupMult` to `2f` (the engine default) for the whole session so the speed is identical even if one player has a mod/json that changed it. Dynamic/hyperspace-conditional multipliers are deferred.
- **No hyperspace-specific time acceleration.** Vanilla has no such mechanic — re-verified exhaustively 2026-08-20 after a user question (dumps in `tmp_ff_analysis\`): `CampaignClock.advance(float)` has exactly two call sites in the whole game, both in `CampaignEngine.advance`, both passing the bare frame delta with no location test; `CampaignClockAPI`/`LocationAPI` expose no rate member; no `settings.json` key ties "hyper" to time. The only `isHyperspace`-adjacent code in the advance path is the 1-in-60-frames round-robin for *non-current* locations (rate-preserving: `dt * 60`), where hyperspace is just slot 0. What makes hyperspace *feel* slower is map scale, not clock rate: fleet speed is uniform units-per-real-second everywhere, but hyperspace is 2000 units/LY on a 164000×104000 sector (82×52 LY) while a whole star system spans a few thousand units — at burn 20 that is 2 LY/day, so days accumulate in transit. Per-fleet burn modifiers (Sustained Burn, terrain penalties, slipstreams) are movement effects and already work in coop. Shared fast-forward covers the need; do not build anything hyperspace-specific in this phase (see the upgraded deferred item below).
- **The host's Shift becomes a toggle during a coop session** (tap on / tap off instead of hold). This is the vanilla accessibility behavior, not a custom scheme; document it in the player-facing notes, don't fight it.
- **Mirror-lag skew is accepted, not corrected.** The guest learns of a toggle ≤ ~200 ms (5 Hz `TIME_SNAPSHOT`) + RTT + 1 frame late. Skew from toggle-ON (guest briefly slow) is canceled by toggle-OFF (guest briefly fast), so it is bounded, not cumulative, and the existing 1 Hz orbit-angle snap (`tickOrbitSync`) already absorbs offsets of this size. Do NOT build clock correction in this phase — **Phase 7c builds it** as a separate, independent reconciler.

**Files:**

- Create `mods/coop/src/main/java/coop/time/CoopFastForwardLock.java`
- Create `mods/coop/src/test/java/coop/time/CoopFastForwardLockTest.java`
- Modify `mods/coop/src/main/java/coop/time/CoopTimeLock.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/data/config/settings.json`
- Modify `mods/coop/src/test/java/coop/seed/CoopSectorProcGenTest.java`
- Modify `mods/coop/src/test/java/coop/time/CoopTimeSnapshotTest.java` (if it asserts the old `setInFastAdvance` mirror)
- Modify `mods/coop/docs/starsector-runtime-limitations.md`

**Steps:**

- [ ] **Remove the 1x lock from `mods/coop/data/config/settings.json`:** delete the `"campaignSpeedupMult":1` line and its `comment_campaignSpeedupMult` line, keeping the `plugins` block untouched. Side benefit: fast-forward works again in *solo* games with the mod enabled (the old override disabled it everywhere). Update `CoopSectorProcGenTest` (line ~91 asserts the file contains `"campaignSpeedupMult":1`) to assert the key is now ABSENT.
- [ ] **Create `CoopFastForwardLock`** — the single home for all MethodHandles access, modeled line-for-line on `CoopBarSync.resolveHandles()`:
  - Lazy, once-only resolution guarded by a `resolved` flag; every resolve and every invoke wrapped in `catch (Throwable)`; on any failure set a sticky `available = false` and log one warning via `CoopLog.warn`. Never let a handle failure propagate into the pump.
  - Resolve, in order: `ffGetter`/`ffSetter` = `privateLookupIn(campaignStateClass, lookup()).findGetter/findSetter(campaignStateClass, "fastForward", boolean.class)` where `campaignStateClass = Global.getSector().getCampaignUI().getClass()`; `toggleGetter` = `lookup().findStatic(starfarerSettingsClass, "Oo0000", MethodType.methodType(boolean.class))`; `toggleSetter` = `lookup().findStatic(starfarerSettingsClass, "?00000", MethodType.methodType(void.class, boolean.class))` (exact `MethodType` mandatory — overloaded name, see fact 4).
  - Public surface (keep it this small): `boolean isAvailable()`; `void enforceSessionState()` — called every frame while a session is active: forces the toggle flag `true` (remembering the player's original flag value the first time, via `toggleGetter`) and `Global.getSettings().setFloat("campaignSpeedupMult", Float.valueOf(2f))`; if NOT available, instead forces `setFloat(..., 1f)` (the Phase 7 fallback lock — public API, always works); `void restoreDefaults()` — called once when the session ends: restores the remembered toggle flag and `setFloat(..., 2f)` (engine default); `void writeFastForward(boolean)` — reads via `ffGetter` first and writes via `ffSetter` only when the value differs (same idempotent-on-change discipline as `CoopTimeLock.apply`'s pause handling); no-op when unavailable.
  - Support `-Dcoop.ff.disable=true` (read once) to force `available = false` — gives the smoke test a way to exercise the fallback path without code edits.
  - Per-frame re-assertion of the toggle flag is deliberate (it is one static getter + rare setter call): the player can untick the vanilla checkbox in the settings menu mid-session, and re-forcing each frame makes that harmless.
- [ ] **Wire it into `CoopTimeLock`:** add a nullable `CoopFastForwardLock` (setter injection, mirroring `setPauseCoordinator`). In `apply(TimeSnapshot)`, DELETE the `setInFastAdvance` block (it was verified to do nothing to the clock rate) and its stale comment; instead, when the lock is non-null call `fastForwardLock.writeFastForward(snapshot.fastForward())`. `capture()` is already correct (`CampaignUIAPI.isFastForward()` reads the real field) — do not touch it. `TIME_SNAPSHOT` already carries `fastForward` — no message/protocol changes in this phase.
- [ ] **Wire it into `CoopNetPump`:** construct/inject one `CoopFastForwardLock`, hand it to `timeLock` next to the existing `setPauseCoordinator` call (`CoopNetPump.java:136`). Add a `syncFastForwardLock()` step to `advance()` right after `syncSharedPause()`: if `service.role() != NONE && isGameplaySessionActive()` → `enforceSessionState()` (BOTH roles — host and guest must run the same mult and the same input mode); otherwise, if it was enforcing last frame → `restoreDefaults()` once. Track the was-enforcing bit inside the lock so the pump code stays two branches.
- [ ] **Guest input blocking — verify, don't build:** `CoopCampaignInputBlocker` already consumes `FAST_FORWARD` events unconditionally on the guest (`CoopCampaignInputBlocker.java:20`). In toggle mode that consumption now actually prevents the guest from flipping its own field (fact 3). No new input code. The host keeps vanilla toggle behavior — its own Shift tap flips its field, `capture()` picks it up, the snapshot mirrors it to the guest. The vanilla sticky message `Speeding up time` is driven by the same field, so it appears/disappears on BOTH clients automatically — free UI, build nothing.
- [ ] **Tests** (remember: the engine classes are NOT on the test classpath, and even with the build.gradle fallback you must never *initialize* `CampaignState`/`StarfarerSettings` in a unit test — class init outside the game can crash; test the logic through injected fakes/suppliers, the way `CoopSharedPauseCoordinatorTest` fakes its collaborators):
  - `CoopFastForwardLockTest`: enforce-while-active forces toggle flag + 2x mult; unavailable → forces 1x mult instead (fallback); `restoreDefaults` restores the remembered original toggle value exactly once; `writeFastForward` writes only on change; `-Dcoop.ff.disable` honored; a resolve `Throwable` flips `available` sticky-false and never throws out.
  - Update `CoopTimeSnapshotTest`: `apply()` must no longer touch `setInFastAdvance`; it must route the snapshot's `fastForward` to the lock.
  - Keep `CoopSectorProcGenTest` green with the inverted settings.json assertion.
  - Run the full suite: `powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"`.
- [ ] **Update `mods/coop/docs/starsector-runtime-limitations.md`:** mark the `### Fast-forward has no public on/off setter` and `### Resolution adopted for v1` sections as **superseded for toggle mode** with a pointer to this phase (the hold-mode facts in them remain true and should stay — they explain why toggle mode is forced).
- [ ] **Deploy with `scripts\deploy-to-test-clients.ps1`** (full mod including `data/` — the settings.json change matters; never hand-copy jars) and relaunch both games.
- [ ] **Two-instance smoke test** (host + guest connected, session active, both fleets flying in the same system):
  1. Host taps Shift once → BOTH clients show the `Speeding up time` sticky message and visibly speed up (watch the date ticker — ~2x). Host taps Shift again → both return to normal speed and the message clears on both.
  2. Guest taps/holds Shift → nothing changes on either client (no message, no speed change). This confirms toggle mode + consumption are both in effect on the guest.
  3. Pause from either side while fast-forward is on → both clocks stop; unpause → fast-forward resumes on both (the field survives pause).
  4. Run several on/off cycles, then compare campaign dates on both clients (host date vs guest date on screen) → must match (≤1 day apart momentarily, converging — the orbit snap and bounded skew analysis above).
  5. Open the vanilla settings menu mid-session and untick `Campaign "speed up time" is a toggle` → coop behavior unchanged (per-frame re-force wins).
  6. Fallback drill: relaunch the guest with `-Dcoop.ff.disable=true` → session runs but stays at 1x for BOTH (host FF requests have no effect on the guest, so the host must also see... no: the host still speeds up locally — this is exactly why the fallback forces 1x mult, which only neutralizes the *local* loop). Verify: with the flag on the GUEST only, guest stays 1x while host fast-forwards → dates drift → this confirms why the flag is a *debug* tool, not a config option, and must log a loud warning. For a clean fallback test instead set the flag on BOTH clients → both locked 1x, dates aligned, identical to pre-7b behavior.
  7. Solo sanity check: launch one client alone (no coop session) → hold-Shift fast-forward works again (the settings.json 1x override is gone, and outside a session the toggle flag was restored).
  8. After the session ends (disconnect), tap Shift in the still-running campaign → vanilla hold-mode behavior is back (restoreDefaults ran).
- [ ] Commit with `git add mods/coop && git commit -m "feat: restore shared campaign fast-forward via toggle mode"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- Host Shift toggles ~2x time on BOTH clients within ≤ ~200 ms + RTT; guest Shift does nothing; the `Speeding up time` sticky shows on both while active.
- Campaign dates stay aligned across repeated fast-forward cycles (bounded, self-canceling skew; orbit snap absorbs the residue).
- If the MethodHandles fail to resolve in-game (or `-Dcoop.ff.disable=true`), the session degrades to the exact Phase 7 behavior: both clients locked 1x via runtime `setFloat("campaignSpeedupMult", 1f)`, no crash, one warning logged.
- Solo games with the mod enabled regain vanilla fast-forward; after a coop session ends, the player's original toggle-mode setting is restored.
- No `java.lang.reflect.*` anywhere; all engine access lives in `CoopFastForwardLock` behind `isAvailable()`.

**Deferred / out of scope (explicitly NOT in Phase 7b):**

- Guest fast-forward intent (AND-of-intents: FF active only while both players want it — would reuse the `PAUSE_INTENT` plumbing; build only if host-only control feels bad in play).
- Dynamic multipliers — cheap later thanks to fact 2, but soak fixed 2x first. **Concretized 2026-08-20 as the "hyperspace pace" candidate** (the honest answer to "time should flow differently in hyperspace", which vanilla does not do — see the policy bullet): a host-side rule raises the FF experience only when **both** player fleets are in hyperspace (AND-of-locations — the clock is one shared object, so any speed change is inherently both-players; the AND rule is what makes that fair). Two verified shapes, either rides existing plumbing: (a) auto-engage FF while both are in hyper (host writes its own `fastForward` field; the existing `TIME_SNAPSHOT.fastForward` mirrors it — zero protocol change, but it fights the host's manual toggle and needs an override rule); (b) keep FF manual and make `enforceSessionState` force `campaignSpeedupMult` to a higher value (e.g. 3f) while both-in-hyper instead of the fixed 2f — needs the host's decision mirrored (one byte on `TIME_SNAPSHOT` or guest-side derivation from its own two known fleet locations), and note `Math.round` makes the mult integer-only. Safety property that makes FF the right lever and burn boosts the wrong one: FF re-runs the entire engine N times, so fuel/supplies per day and per LY are untouched — only real-world minutes shrink; a burn-level boost would cut fuel-per-LY and change balance. Both player locations are already known to both clients (own fleet + the partner mirror's containing location). Slots naturally with Phase 25 (time-control polish) if wanted.
- Exact clock sync / late-join catch-up via the `CampaignClock` timestamp setter discovery — **now planned as Phase 7c** (clock reconciliation; late-join catch-up stays deferred).

## Phase 7c: Clock Reconciliation (Campaign-Date Drift Correction)

**Agent prompt:**

```text
Implement Phase 7c from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Build a guest-side campaign-clock reconciler: measure host-vs-guest clock drift from the TIME_SNAPSHOT stream the guest already receives, and converge the guest's CampaignClock to the host's by a bounded, monotonic (never-backward) slew, with forward-only snaps during shared pauses. Read the "Why this is possible / why drift exists" section below in full before writing any code — it contains verified engine facts you must not re-derive. This phase is independent of Phase 7b (it works whether or not 7b is implemented), but if 7b is done it also absorbs 7b's fast-forward toggle-edge skew.
```

**Measured drift magnitude (2026-08-09, first real number):** during the Phase 12 smoke session the `CoopDebug` orbit-dump caught the drift signature directly. Two entities dumped in the same pass on each client, radius and period identical on both, guest angle behind the host on both: id `2230` (period 12.69) was behind by 5.69°, id `2232` (period 37.14) by 1.94°. Period ratio 2.93, angle-delta ratio 2.93 — an **exact inverse-period match**, which is the diagnostic's documented signature for clock drift rather than orbit non-determinism. That works out to the guest clock trailing the host by **≈0.2 campaign days** after roughly two hours of session uptime. Use this as the order-of-magnitude target when picking the slew rate: the correction needed is small and slow, so a bounded monotonic slew is comfortably sufficient and a snap is not required outside shared pauses.

**Second data point (2026-08-17, active play):** during the 12b/12d drill session the two clients read Mar 19 vs Mar 21 — **~2 campaign days apart after well under an hour of active play**, roughly 10× the idle-flying rate above. Active sessions accumulate drift much faster because every dialog/market/menu open-close is a pause mirror edge (each worth up to ~half a game-hour of guest lag, per fact 1), and the drill session was dense with them. Consequences for the design: the slew must comfortably out-pace *days*-scale drift, not just the 0.2-day idle figure, and the shared-pause forward-snap matters more than the idle measurement suggested — a dialog-heavy session hands the reconciler frequent shared pauses in which to snap. (Same session, visible symptom: a derelict's on-screen orbit position differed between clients. **Correction 2026-08-25, from bytecode:** orbit angle is NOT a function of the calendar date — every orbit class (`CircularOrbit` and variants) integrates a private `currAngle` per frame from the frame dt (`currAngle -= daysPerRevolution * clock.convertToDays(amount)`; the only clock call is the pure divide `convertToDays`, never `getTimestamp()`). Clock offset and orbit offset are two independent symptoms of the same lost game-time (pause edges + truncation), not cause and effect: **a 7c clock write will not move any on-screen orbit position.** The 1 Hz orbit snap (`tickOrbitSync`) remains the only orbit-position corrector; 7c fixes the date readout and every absolute-timestamp consumer, which the orbit snap does not touch.)

**Deep-research pass (2026-08-25):** this section was revised against three sources — a survey of the mod's current code (post-29-M1), a bytecode sweep of every clock consumer in the 0.98a-RC8 engine, and the clock-sync literature (RFC 5905/NTP/chrony/linuxptp; Source `clockdriftmgr`, Quake 3 `CL_AdjustTimeDelta`, GGPO `timesync`, Unity NfE/NGO, Mirror, Overwatch/Rocket League GDC material, Riot's LoL Unified Clock). Goal upgraded per user directive the same day: **match host and guest game time as closely as possible** — steady-state target is the 0.01 game-day hysteresis exit threshold below, not merely "inside the dead zone". Design verdict from the literature: guest-only bounded slew, hard never-backward, and snap-only-in-quiescent-windows all have strong shipped precedent (Quake 3 enforces monotonicity in code; Riot clamps elapsed-time to 0 and carries the remainder; chrony/linuxptp step only at start); the parameter-level fixes it forced are folded into the policy bullets and marked *(rev 2026-08-25)*.

**Why this is possible / why drift exists (verified engine facts — do not re-litigate):**

Established 2026-06-10 from `javap` disassembly of `starsector-core/starfarer_obf.jar` (0.98a-RC8); dump: `K:\Starsector\tmp_ff_analysis\CampaignClock.javap.txt`. `com.fs.starfarer.campaign.CampaignClock` is `DoNotObfuscate` — all names below are plain and stable.

1. **Drift is structural, not a bug in our code.** `CampaignClock.advance(float realSeconds)` does `cal.add(Calendar.SECOND, (int)(realSeconds / secondsPerDay * 86400f))` — the cast to `int` **truncates the fractional calendar-seconds every frame**, and the loss depends on each machine's frame timing. Two clients at different or uneven frame rates therefore drift apart even at a perfect shared 1x with zero network skew. On top of that, every pause/unpause (and Phase 7b fast-forward) mirror edge costs the guest ~200 ms + RTT of host time — and 1 real second = 0.1 game-day (`SECONDS_PER_GAME_DAY = 10`), so each edge is worth up to ~half a game-hour. Drift is unbounded over a session; observed in local two-player testing. Reconciliation, not prevention, is the fix.
2. **The clock's source of truth is a `GregorianCalendar`, reachable via a public method.** `CampaignClock` holds `private transient GregorianCalendar cal`; ALL date getters (`getCycle/getMonth/getDay/getHour`) read `cal`, and `advance()` writes `cal` then caches `timestamp = cal.getTimeInMillis()` into the `private long timestamp` field (which backs `getTimestamp()`/`getElapsedDaysSince()`). The class has a **public `getCal()`** returning the live calendar object — so the actual clock write is plain JDK API: `cal.setTimeInMillis(target)`. No engine-field write is needed for the calendar itself.
3. **Units are exact and trivial:** the timestamp/calendar is in milliseconds with 1 game day = 86,400,000 ms (`getElapsedDaysSince` divides by `8.64E7`). At 1x, 1 real second = 0.1 game day = 8,640,000 calendar-ms. A drift of "0.05 game days" = 4,320,000 calendar-ms = half a real second of game time.
4. **Two handles are still needed, both of the proven-safe kind** (`java.lang.reflect.*` is sandbox-blocked; `MethodHandles` is the working pattern — copy `CoopBarSync.resolveHandles()`, `mods/coop/src/main/java/coop/campaign/CoopBarSync.java:170-178`):
   - `getCal()` — **verified 2026-08-20: it IS on `CampaignClockAPI`** (confirmed by javap of `starfarer.api.jar`), so call `Global.getSector().getClock().getCal()` directly; no handle, no lookup. (The fallback `findVirtual` sketch is obsolete.)
   - After every calendar write, the cached `timestamp` field must be re-synced or `getTimestamp()` is stale until the engine's next `advance()`: `MethodHandles.privateLookupIn(clock.getClass(), MethodHandles.lookup()).findSetter(clock.getClass(), "timestamp", long.class)`. **Always write `cal` and `timestamp` together, in that order, to the same value.**
5. **No compile-time dependency needed:** the clock instance comes from `Global.getSector().getClock()` and its runtime class from `getClass()` — no class-name strings, no obf jar on the classpath (same approach as Phase 7b fact 6).
6. **`TIME_SNAPSHOT` already carries everything needed to measure drift** — payload is `paused`, `fastForward`, `timestampMillis`, `campaignDay`, `sentAtMillis` (`CoopMessages.timeSnapshot`, `CoopMessages.java:157-165`), sent ≤5 Hz wall-clock (`CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS = 200`, re-armed `now + interval` so frame-quantized). **Verified 2026-08-25: `timestampMillis` and `campaignDay` are transported but never consumed** — `CoopTimeLock.apply` reads only `paused`/`fastForward`; the drift signal has been on the wire unused since Phase 7. No protocol changes.
7. **What must NEVER be used to correct the clock:** do not call extra `CampaignEngine.advance()` (it advances the entire sim, not just the clock); do not use `clock.advance()` for corrections (goes through the same int truncation, and `setTimeInMillis` is exact); do not use `SectorAPI.setInFastAdvance` (verified in Phase 7 to not change the clock rate); and do not implement "slew" by scaling any `advance(float)` dt handed to other systems (see fact 12).

Facts 8–12 established 2026-08-25 from a full-engine bytecode sweep (all 2484 classes in `starfarer_obf.jar` + all 1947 API sources; every class referencing `CampaignClock`/`CampaignClockAPI` was disassembled and its call sites classified):

8. **The blast radius of a clock write is small and fully mapped.** Immune (all driven by per-frame dt, never by the clock's absolute value): `convertToDays`/`convertToSeconds` (pure divides by `secondsPerDay`, 128 call sites), every `IntervalUtil`/`TimeoutTracker`/`rules.Memory` timer, submarket restock accumulators, and **all orbit positions** (see the 2026-08-25 correction above). Affected (shifted instantly by a forward write of Δ): absolute-`getTimestamp()`/`getElapsedDaysSince` consumers — intel ages and `isNew()` badges, event-lifetime stamps (`BaseOneTimeFactor`, raid/expedition intel), pirate-base/route-manager timestamps, `lastPlayerBattleTimestamp` (drives a 0.5-day `setNoEngaging` window), `GBEchoMovement` sensor-ghost replay, playthrough-log display; plus month/day-seeded RNG (`getMonth() * 170000` seeds submarket stock and stockpile/price rolls — **so month-number alignment between host and guest also aligns market stock seeds, a concrete correctness win for the Phase 12 market model**, and drift across a month boundary is what desyncs them today). These consumers converging to the host's values is the point of the phase, not collateral damage: the host's copies already lived through that time.
9. **There is no daily tick in the engine — the monthly tick is the one event hazard, and it hard-justifies the monotonic invariant.** The only clock-derived cached state in the entire engine outside `CampaignClock` is `ReachEconomyStepper.prevMonth` (one `int`). Month-end (`reportEconomyMonthEnd` — monthly income payout, custom production, report rollover) fires when `getMonth() != prevMonth` — an *inequality*, not a greater-than. Consequences: a **backward** write across a month boundary fires month-end immediately and then AGAIN when the clock re-crosses forward — a double income payout; this is the concrete engine mechanism behind "never backward". A **forward** jump fires month-end **at most once** no matter how many boundaries it crosses (and compresses the landing month's remaining econ-tick cadence — harmless). Economy *iteration* ticks (`reportEconomyTick`) are paced by a frame-dt accumulator and can never fire from a clock write at all. The economy does not advance while paused, so a shared-pause snap's month consequences defer to the first unpaused frame and still fire at most once. Residual accepted quirk: a multi-day forward snap silently steps over literal-date checks (`RemnantStationFleetManager`'s Dec-15-c206, `KantaCMD`'s Nov-28) — cosmetic, accept.
10. **`timestamp` is the sole persisted representation of campaign time — always-write-both is load-bearing, not hygiene.** `cal` is `transient`; save serialization writes only `timestamp` (+`secondsPerDay`), and `readResolve()` rebuilds `cal` FROM `timestamp` on load. `SaveGameData.gameDate` holds the *live* clock instance (not a copy), so a `timestamp` write also shows correctly in the load-game dialog. A `cal`-only write would be silently reverted by the next save/load cycle. (Vanilla precedent that the pair can go stale: `CampaignClock.set(int,int,int)` writes `cal` without `timestamp` — unused anywhere, do not copy.) Inside `CampaignClock` there is no other derived cache — `getCycle/getMonth/getDay/getHour` and all date strings read `cal` live.
11. **`getElapsedDaysSince` edge behavior:** `ts == 0` is a sentinel returning `Float.MAX_VALUE`; there is **no lower clamp**, so a stamp that postdates the clock yields negative days — impossible under forward-only writes, one more reason the monotonic rule is absolute.
12. **Phase 29 M1 is provably decoupled from the campaign clock — and must stay that way.** Repo-wide sweep 2026-08-25: no 29 M1 code (`CoopStreamClock`, `CoopMotionTimeline`, `CoopMotionInterpolator`, watermark/redundancy, any of `coop/net` or `coop/fleet`) reads the campaign clock; the interpolation cursor lives on sender `CoopStreamClock` stamps and advances by raw frame dt, with `sector.isPaused()` (a boolean) as the only coupling. Therefore a 7c calendar write cannot move the mirror cursor. **Invariant for the implementer: 7c writes `cal` + `timestamp` and NOTHING else — never scale or modify the dt passed to `advanceMirrorMotion`/`streamClock.advance` (a dt-scaling "slew" would silently couple the two systems and stack the reconciler's rate on top of the timeline's ±2%/4%).**

**v1 policy decisions (already made — implement as stated):**

- **Guest-only, host-authoritative.** The host's clock is never touched; only the guest converges. No lockstep, no host-waits-for-guest.
- **Monotonic guarantee — the guest clock NEVER moves backward.** Hard engine reason (fact 9): the monthly economy tick fires on `getMonth() != prevMonth`, so a backward write across a month boundary pays monthly income twice (once crossing back, once re-crossing forward). If the guest is *ahead*, it slows down (withholds part of each frame's advance) until the host catches up; it never rewinds. This invariant is non-negotiable and must be asserted in tests. Precedent (2026-08-25 survey): Quake 3 enforces exactly this in code (`if (cl.serverTime < cl.oldServerTime) cl.serverTime = cl.oldServerTime`); Riot's Unified Clock clamps negative elapsed time to 0 and carries the remainder; BSD `adjtime` and `CLOCK_MONOTONIC` define the same contract (holding still is legal, going back is not). *(rev 2026-08-25)* Sustained guest-ahead (drift more negative than the entry dead zone for > 60 real seconds) must **log a warning** — the condition is legitimate and self-healing but must be visible, never silent.
- **Slew, don't step, while running.** Corrections while unpaused are rate changes bounded to **±10%** of each frame's clock advance — the most corroborated rate in the survey (Unity Netcode for Entities ships ±10%, chrony defaults to 8.33%, Source caps at 20%). *(rev 2026-08-25)* The old ±50% fast tier is cut to **+30%, applied only while drift > 0.1 game-day, tapering back to 10% for the final approach**: no surveyed system exceeds ±30%, and finishing a correction at a fast rate combined with the median filter's ~0.4 s group delay can overshoot the guest *ahead* of the host — which the monotonic rule then cannot undo. At 1x a 10% slew corrects 0.01 game-day per real second (a full game-day of drift converges in ~100 real seconds; at the 30% tier ~33 s per day of drift down to the 0.1 taper point) and is invisible in play — the sim runs on frame dt, not the clock; only the date readout and the fact-8 timestamp consumers converge, which is the point.
- **Snap forward only, in safe windows.** During a shared pause (guest paused AND latest snapshot says host paused), the host clock is frozen and exact — absorb the *entire* positive drift in one silent write. This is the strongest part of the design (a genuine quiescent window; chrony/linuxptp only *approximate* one by stepping at boot) — prefer it. *(rev 2026-08-25)* The unpaused big-drift snap (> 2 game days) now requires **persistence**: at least 3 consecutive median estimates over threshold spanning ≥ 2 real seconds, because a single OS stall (GC pause, window drag, disk hitch) manufactures an apparent multi-day drift with no network fault — NTP's stepout timer exists for exactly this ("resists clock steps under conditions of extreme network congestion"), and its state machine always discards the first over-threshold sample. Any unpaused snap logs loudly (warn level, not diagnostics-gated). **After ANY snap, clear the sample ring** — Source and RFC 5905 both invalidate all samples after a step, otherwise pre-snap samples immediately command a second, opposite correction. Never snap backward under any condition.
- **Dead zone with hysteresis** *(rev 2026-08-25, serves the match-as-close-as-possible goal)*: **start** correcting when the median drift estimate exceeds **0.05 game-day** (≈ half a real second of game time; ~500× the LAN measurement error, comfortably above 5 Hz cadence + jitter); once correcting, **keep going until drift falls below 0.01 game-day** (≈ 8.6 game-minutes), then stop. Two thresholds instead of one: the wide entry keeps the rate at exactly 1.0 almost all the time (Mirror's stated anti-ping-pong rationale — "a consistent multiplier would never be exactly 1.0"), while the tight exit means each correction episode parks the clocks ~5× closer than the entry zone. Steady-state on-screen dates are identical either way (day-granular readout); the tight exit is what keeps both clients on the same *month number* near boundaries (fact 8's market-seed alignment).
- **Offset filter: median of a 9-sample ring** *(rev 2026-08-25; was 5)*. Median stays — our dominant error is pause-edge *step changes* in the true offset, not symmetric network jitter, and a rank filter has the best step response (no overshoot, ~0.4 s group delay at 5 Hz) where an EMA lags and min-selection would hold stale samples. But 5 was the smallest window in the entire survey (NTP 8, linuxptp 10, Source 16, GGPO 40); 9 costs nothing and survives 4 outliers instead of 2. Three sample gates in front of the ring: (a) **pause-agreement gate** — discard the sample when `snapshot.paused() != guest-local isPaused` (during a mirror edge the two clocks are *legitimately* running at different rates and the sample is meaningless; count discards under the diagnostics gate — this likely explains a chunk of the observed 2 gd/hr signal); (b) **self-scaling spike gate** — with ≥ 3 samples buffered, discard a sample whose offset differs from the current median by more than 3× the ring's RMS deviation (NTP's popcorn suppressor / `SGATE × jitter`; adapts to the link, unlike a fixed cutoff); (c) **ring clears** on session edge, on guest-dialog close (pre-dialog samples are stale), and after any snap. **Anti-windup:** when a correction is applied, subtract the applied amount from every buffered sample as well as the estimate — Source's `AdjustAverageDifferenceBy` exists solely to stop the filter re-commanding corrections it already issued; without it the 5 Hz feedback loop over-corrects by up to a full ring of stale samples.
- **Latency compensation stays 0 in v1** *(rev 2026-08-25 — grounds corrected, conclusion kept)*: LAN one-way delay ≈ 0.5 ms ≈ 0.00005 game-day — 1/1000 of the entry dead zone; compensation buys nothing. The old "no RTT accessor exists / do NOT build ping plumbing" rationale is stale: the plumbing half-exists (guest sends `PING` every 3 s, host answers `PONG` echoing `pingSeq` — `CoopNetPump.maybeSendPing`/`sendPong` — but **no `PONG` handler exists and `pingSeq` is never read**), so an RTT estimate is one small handler away *if ever needed* — still don't build it for 7c. When Phase 20 lands real RTT tracking, compensate with **half the minimum RTT over a rolling window** (chrony `mindelay` / NTP huff-n'-puff: the minimum is the only measurable part of path asymmetry), never the instantaneous RTT/2, scaled by `hostRate` (0 if snapshot says paused, else 2 if fastForward and 7b is in, else 1).
- **Failure degrades to today's behavior:** any handle-resolution or invoke `Throwable` → sticky `available = false`, one `CoopLog.warn`, reconciler becomes a no-op (uncorrected drift, exactly the current state). Debug escape hatch `-Dcoop.clock.disable=true`. Drift diagnostics (periodic drift log) go behind the existing `CoopDebug` gate (`-Dcoop.debug.diagnostics=true` or `$coopDebug`), dormant by default.

**Files:**

- Create `mods/coop/src/main/java/coop/time/CoopClockReconciler.java`
- Create `mods/coop/src/test/java/coop/time/CoopClockReconcilerTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/docs/starsector-runtime-limitations.md`

**Steps:**

- [ ] **Create `CoopClockReconciler`** — all engine access in one class, lazy once-only handle resolution modeled on `CoopBarSync.resolveHandles()`, every resolve/invoke in `catch (Throwable)` → sticky `available=false` + one `CoopLog.warn`. Public surface:
  - `void onSnapshot(long hostTimestampMillis, boolean hostPaused, boolean hostFastForward, boolean guestPaused)` — called by the pump when a `TIME_SNAPSHOT` is applied (`guestPaused` = the pump's local `sector.isPaused()` read, same source as `isSectorPausedForStream`). Computes raw drift = `hostTs − guestClock.getTimestamp()` (latency compensation = 0 in v1, see policy). Gates before the ring *(rev 2026-08-25)*: discard when `hostPaused != guestPaused` (pause-agreement gate, count under `CoopDebug`); discard when ≥ 3 samples are buffered and the sample is > 3× the ring's RMS deviation from the current median (spike gate). Surviving samples go into a **9-slot ring**; the **median** is the drift estimate. Track `hostPaused` for the shared-pause snap and maintain the > 2-day persistence counter here (consecutive over-threshold medians + first-seen time).
  - `void tick(float amountSeconds)` — called every frame from the pump while the session is active. **Units corrected 2026-08-25:** the pump's `advance(float amount)` receives REAL SECONDS, not campaign-days (verified — it is passed as `dtSeconds` into `CoopStreamClock.advance`; nothing in the module calls `convertToDays`). The frame's clock advance is `frameMs = amountSeconds × 8_640_000` calendar-ms at 1x (`secondsPerDay = 10`; under fast-forward the engine calls `advance()` once per extra iteration with the same per-call amount, so the per-call math is unchanged). Behavior, in priority order: (1) not available / disabled / not in a correction episode and median inside the 0.05-day entry zone → return; (2) shared pause AND drift > 0 → snap: write `hostTs` (cal + timestamp field), clear ring + estimate; (3) drift > 2 days AND the persistence gate is satisfied (≥ 3 consecutive over-threshold medians spanning ≥ 2 s) → forward snap likewise, warn-level log; (4) in a correction episode (entered at 0.05, exited below 0.01) and guest unpaused → slew: `correctionMs = clamp(driftMs, −rate × frameMs, +rate × frameMs)` with `rate = 0.10` (0.30 while `|drift| > 0.1` day — taper back to 0.10 for the final approach), apply `cal.setTimeInMillis(cal.getTimeInMillis() + correctionMs)` + timestamp field, then subtract the applied amount from the estimate AND from every buffered ring sample (anti-windup). The negative clamp is strictly less than the frame's own advance → the clock still moves forward every frame → monotonicity holds by construction. Track the sustained-guest-ahead condition here (negative median beyond entry zone for > 60 s → one warn).
  - `boolean isAvailable()`; `-Dcoop.clock.disable=true` read once.
  - Make the clock access injectable for tests (e.g. a tiny internal `ClockPort` interface with `long getTimestamp()` / `void setTimestamp(long)`, the real impl wrapping `getCal().setTimeInMillis` + the field setter) — **never initialize or look up engine classes in unit tests**.
- [ ] **Wire into `CoopNetPump`:** construct one reconciler; in `maybeApplyTimeSnapshot()` (guest path, `CoopNetPump.java:1287-1313`), after the snapshot is applied, call `reconciler.onSnapshot(...)` passing the pump's local `sector.isPaused()` as `guestPaused` — note this path already skips while the guest's interaction dialog is open (`isGuestInteractionDialogOpen()`), which is exactly the measurement gate we want, so add no new dialog checks there. Add one edge hook: when the dialog-open state transitions open → closed, call `reconciler.clearSamples()` (pre-dialog samples are stale — policy bullet). In `advance()`, on the guest only, while `isGameplaySessionActive()`, call `reconciler.tick(amount)` right after the time-snapshot step (slots after the `:416` `maybeApplyTimeSnapshot()` call; `amount` is the raw seconds parameter, untouched). Host role: never construct/tick (or tick is a no-op for role HOST — pick whichever matches the pump's existing role-branch style). **Do not touch `streamClock.advance` or `advanceMirrorMotion` in any way (fact 12).**
- [x] **Verify the `getCal()` exposure assumption:** verified 2026-08-20 (javap of `starfarer.api.jar`) — `getCal()` IS on `CampaignClockAPI`; use it directly, one handle dropped. The `timestamp` field setter is still required (fact 4).
- [ ] **Tests** (`CoopClockReconcilerTest`, against the fake `ClockPort` — same fake-collaborator style as `CoopSharedPauseCoordinatorTest`):
  - Dead zone + hysteresis: drift below 0.05 day with no episode active → no write; an episode entered at > 0.05 keeps correcting through the 0.02–0.05 band and stops only below 0.01.
  - Slew cap + taper: drift of +1 day → per-tick correction ≤ 30% of that tick's advance while drift > 0.1 day, then ≤ 10%; estimate decreases across ticks; no tick ever leaves the estimate negative (no overshoot past the host).
  - **Monotonicity:** for any drift (including large negative), `timestamp` after `tick` ≥ `timestamp` before the engine's advance that frame — i.e. negative corrections never exceed the frame's own advance. This is the test that guards the no-double-month-end invariant (fact 9).
  - Shared-pause snap: hostPaused + guest paused + positive drift → exact jump to host value; negative drift while paused → NO write; ring cleared after the snap.
  - Big-drift snap persistence: a single > 2-day median → NO snap; 3 consecutive over-threshold medians spanning ≥ 2 s → forward snap while unpaused; ring cleared after.
  - Median filter: one outlier snapshot among nine does not move the estimate.
  - Pause-agreement gate: a sample with `hostPaused != guestPaused` never enters the ring.
  - Spike gate: with ≥ 3 samples buffered, a sample > 3× ring RMS deviation from the median is discarded; the same sample with < 3 buffered is accepted.
  - Anti-windup: after a slew tick, buffered samples are reduced by the applied amount — feeding the SAME ring back through `tick` does not re-apply the correction.
  - Guest-ahead visibility: sustained negative median beyond the entry zone for > 60 s → exactly one warn.
  - Sticky failure: a `Throwable` from the port → `available=false` forever, no propagation; `-Dcoop.clock.disable` honored.
  - Run the full suite: `powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"`.
- [ ] **Update `mods/coop/docs/starsector-runtime-limitations.md`:** add a superseded-in-part banner to `### Connect-time clock alignment` — the "no public clock-setter" claim is wrong on 0.98a-RC8 (`getCal()` is public; `timestamp` is MethodHandles-settable); the host-pause-hold during connect REMAINS correct and primary (prevention beats correction), with Phase 7c reconciliation now handling accumulated in-session drift.
- [ ] **Deploy with `scripts\deploy-to-test-clients.ps1`** and relaunch both games.
- [ ] **Two-instance smoke test** (host + guest connected, session active):
  1. Confirm handles resolve: launch the guest with `-Dcoop.debug.diagnostics=true` and check `starsector-core/starsector.log` for the reconciler's drift line (and no `CoopLog.warn` about unavailability). This is the explicit in-game sandbox check for the `findVirtual(getCal)` lookup.
  2. Baseline convergence: play unpaused ~5 real minutes with pause toggles sprinkled in; the logged drift must stay within/return to the entry dead zone (episodes ending below the 0.01-day exit), the on-screen dates must match, and the diagnostics log should show pause-agreement discards around each pause toggle (the gate working). Do NOT judge 7c by on-screen orbit positions — a clock write cannot move them (fact 8 / the 2026-08-25 orbit correction); the 1 Hz orbit snap owns those.
  3. Induced drift: pause the GUEST's process OS-level for ~10 s (drag its window / suspend), release → guest is several game-hours behind → watch the drift log shrink at the slew rate, dates re-converge, no visible speed change in play.
  4. Shared-pause snap: induce drift as above, then pause on the host → within a snapshot interval the guest's drift log drops to ~0 in one step while paused; unpause → aligned.
  5. Guest-ahead case: pause the HOST's process OS-level ~10 s (guest gets ahead) → guest clock must NOT jump backward; the drift log shows convergence by slow-down only, the guest date never decreases, and if the condition persists past 60 s the single guest-ahead warning appears. Also confirm the host's stall did NOT trigger an unpaused snap on the guest (persistence gate absorbing the transient).
  6. (If Phase 7b is in) run several fast-forward cycles → toggle-edge skew shows up in the drift log and is absorbed back into the dead zone.
  7. Fallback drill: relaunch guest with `-Dcoop.clock.disable=true` → one warning logged, session runs exactly as pre-7c (drift uncorrected, nothing crashes).
  8. Solo sanity: a single client outside a coop session never constructs/ticks the reconciler (no log lines, no behavior change).
- [ ] Commit with `git add mods/coop && git commit -m "feat: reconcile guest campaign clock to host via bounded slew"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- After induced or naturally accumulated drift, the guest's campaign date converges to the host's; each correction episode ends below the 0.01-game-day exit threshold, and steady play stays inside the 0.05-day entry zone.
- The guest clock never moves backward — verified by test (monotonicity) and by smoke step 5 — and a sustained guest-ahead condition is logged, never silent.
- Corrections are imperceptible in normal play (no visible speed lurch); shared pauses silently absorb accumulated drift; an unpaused snap requires the persistence gate and is loudly logged.
- The Phase 29 M1 motion pipeline is byte-for-byte untouched: no change to `CoopStreamClock`, `CoopMotionTimeline`, or any dt handed to `advanceMirrorMotion` (fact 12's invariant).
- On handle failure or `-Dcoop.clock.disable=true`: one warning, reconciler no-ops, behavior identical to pre-7c. No `java.lang.reflect.*` anywhere; all engine access lives in `CoopClockReconciler` behind `isAvailable()`.
- Host clock and host-side code paths are untouched.

**Deferred / out of scope (explicitly NOT in Phase 7c):**

- Late-join catch-up (guest joining an in-progress session days behind) — the connect-time host-pause-hold still prevents this; a catch-up snap would also need world-state resync, not just the clock.
- Host-side or mutual clock adjustment, lockstep, or any host-waits-for-guest scheme.
- Fixing the engine's per-frame int-truncation itself (would need a `CampaignClock` fork; the reconciler makes it irrelevant).
- **Guest-side truncation pre-compensation** (micro-adding the truncated fractional calendar-seconds back each frame) — considered and rejected 2026-08-25: it makes the guest's clock more accurate *in the abstract* but not closer to the HOST, whose own truncation loss is a function of host frame pacing that the guest cannot predict. Only measured-offset feedback converges to the host; rate pre-compensation would just change which direction the offset accumulates.
- **Backward correction, even during a shared pause** — stays forbidden in v1. The survey's caveat stands (no production system forbids backward steps *unconditionally*; each keeps an escape hatch), so the designated future escape hatch, if sustained guest-ahead ever proves common in practice, is a backward write **during a shared pause only, behind an explicit threshold, after the month-boundary hazard (fact 9) is re-examined** — log first, build later.
- RTT plumbing (the missing `PONG` handler) — Phase 20's job; 7c runs uncompensated by design.

## Phase 8: Fleet Mirror

**Agent prompt:**

```text
Implement Phase 8 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Represent the remote player as an AI-mode CampaignFleetAPI, replicate position/roster state over the UDP campaign stream at 10 Hz, and add the always-visible presence indicator required by COOP_MP_DESIGN.md. Do not add combat or reward logic yet.
```

**Files:**

- Create `mods/coop/src/main/java/coop/fleet/CoopFleetMirror.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopFleetSnapshot.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopFleetSnapshotFactory.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopPresenceIndicator.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopPresenceIndicatorTest.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopFleetSnapshotTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetService.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`

**Steps:**

- [x] Add a UDP datagram channel to `CoopNetService` for high-frequency state packets; keep TCP for reliable control messages. **Transport correction:** the plan's "Netty UDP" assumption is dropped — Starsector's script sandbox blocks Netty's reflection (proven in Phase 3, asserted by `CoopNetServiceSandboxCompatibilityTest`), so coop networking uses `java.nio` throughout. UDP is a non-blocking `java.nio.channels.DatagramChannel` drained from the campaign pump thread (no background threads, no `java.io`). Host learns the guest's UDP return address from its first received datagram; guest sends to the known host address. Netty is no longer bundled or referenced (`mods/coop/jars/netty/` is unused; `mod_info.json` deliberately does not load it).
- [x] Define `FLEET_SNAPSHOT` fields: `playerId`, `username`, `locationId`, `x`, `y`, `velocityX`, `velocityY`, `factionId`, `transponderOn`, `fleetHash`, and `members`.
- [x] Define member fields: `fleetMemberId`, `hullId`, `variantId`, `shipName`, `captainName`, `cr`, `hullFraction`.
- [x] Define `fleetHash` as SHA-256 over sorted member records using `fleetMemberId`, `hullId`, `variantId`, `shipName`, `captainName`, rounded CR, and rounded hull fraction; do not depend on collection iteration order. **Encoding note:** the TCP envelope JSON parser is flat (no arrays), so `CoopFleetSnapshot` carries its own compact delimiter-escaped text encoding over UDP, wrapped in a `CoopMessages.datagram(sessionId, type, body)` envelope (unit-separator framed).
- [x] Host and guest both send their own local player fleet snapshot over UDP at 10 Hz.
- [x] Create or update the remote player fleet as a campaign fleet with `setAIMode(true)` (`CoopFleetMirror`).
- [x] Apply `setMoveDestinationOverride(x, y)` and periodic `setLocation(x, y)` correction (~1 Hz snap).
- [x] Apply `setNoEngaging(1f)` every update to suppress incidental engagement.
- [x] Implement `CoopPresenceIndicator` so the remote player is visible on the campaign map regardless of normal sensor range, rendered in the local player's own faction color, and labeled with the remote username.
- [x] If public fleet visibility APIs cannot force out-of-sensor visibility, render a campaign-map overlay marker anchored to the mirrored fleet location and document the exact API limitation in `CoopPresenceIndicator`. **API limitation:** 0.98a-RC8 exposes no hard "always visible" override; `CoopPresenceIndicator` forces a very large sensor profile + detected-range bonus + permanent transponder (the public-API approximation) and documents that a custom campaign-map overlay marker remains the fallback if extreme-range culling is ever observed.
- [x] Refresh roster only when `fleetHash` changes.
- [x] Add tests for stable `fleetHash` independent of member iteration order, changed hash when member hull/variant changes, and presence label/color selection. (`CoopFleetSnapshotTest`, `CoopPresenceIndicatorTest`.)
- [x] Run two-instance smoke test with both fleets moving in the same location. *(Verified in-game 2026-05-29: host↔guest mirror fleets created in `corvus` with correct username labels, 2-ship rosters mirrored, and movement confirmed synced. Host UDP bound `:7777`, guest ephemeral; continuous `TIME_SNAPSHOT`/`PING`/`PONG` flow.)*
- [ ] Commit with `git add mods/coop && git commit -m "feat: mirror remote player fleet"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- Each player sees the other player's mirrored fleet.
- The remote player remains visible when outside normal sensor range and is labeled with username.
- Movement updates continue while time is host-locked.
- Campaign position snapshots use UDP at 10 Hz; reliable session events still use TCP.

## Phase 9: NPC Fleet Replication

**Agent prompt:**

```text
Implement Phase 9 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Make the entire non-player campaign fleet population host-authoritative: the host owns the one real campaign simulation, and the guest renders every NPC fleet as an AI-mode mirror driven by host packets while running no native NPC fleet spawning or AI of its own. Reuse the Phase 8 fleet-mirror machinery (generalized to many fleets keyed by host fleet id) and the Phase 13 guest-manager-suppression pattern. Do not add combat command authority (combat stays Phase 14) or reward logic (Phase 15).
```

**Design rationale (why full population, not player-local):** `COOP_MP_DESIGN.md` §4.2 makes NPC fleets host-authoritative/guest-replicated, and §7.3 says the guest must not re-run NPC inflation locally. NPC fleets are *not* a local-to-the-player concern: a trade fleet only moves the economy by actually arriving and delivering, bases spawn raiders that roam between systems, patrols and bounty fleets cross the sector, and engine fleet identity comes from `genUID()` which differs per client (the same reason Phase 13 excludes base markets from the fingerprint). Two independent simulations therefore diverge globally, not just on screen, and there is no id to reconcile them by. The only coherent model is a **single simulation on the host**; the guest is a pure renderer of the host's fleets and consumes the host-authored economy/intel deltas from Phase 12. This is why a "player-local fleets only" scope was rejected: it would leave the guest running a second, drifting economy everywhere the players are not looking.

**Files:**

- Create `mods/coop/src/main/java/coop/fleet/CoopNpcFleetReplicator.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopNpcFleetSnapshot.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopNpcFleetSetSnapshot.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopFleetMirrorRegistry.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopNpcFleetSuppressor.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopNpcFleetSnapshotTest.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopNpcFleetSetSnapshotTest.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopFleetMirrorRegistryTest.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopNpcFleetSuppressorTest.java`
- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetMirror.java`
- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetSnapshotFactory.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetService.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`

**Authority statement:** On the guest, the only campaign fleets that may exist are (a) the local player fleet, (b) the Phase 8 remote-player mirror (`$coopMirrorFleet`), and (c) NPC mirrors created by this phase (`$coopNpcFleetId`). Every other fleet is a host-simulation artifact and must be removed. The host runs vanilla NPC simulation unchanged.

**Step 0 — Pursuit verification gate (manual, no code; do before building):** ✅ **VERIFIED PASS (2026-06-02):** on the current build, host-sim NPCs pursue both the real player and the guest mirror — no explicit-assignment nudge needed. Confirmed side effects (both expected): each client currently only sees its *own* independently-spawned NPC population (the divergence Phase 9 fixes), and an NPC catching the AI-mode mirror just "bumps" it with no combat (combat is the Phase 14 bridge). NPC behaviour toward the guest is produced for free by the host's single sim acting on the Phase 8 reverse-mirror of the guest (a `Factions.PLAYER` AI fleet that already exists on the host today). Before writing any Phase 9 code, verify the core assumption on the **current build**: launch host + guest in a shared system, move the guest next to (or aggro) a fleet hostile to the player faction, and confirm on the **host** that the hostile fleet acquires an intercept/pursuit assignment against the guest's mirror (chases the mirror, not just the host player). If it pursues → proceed. If it only goes hostile-but-passive → add a small host-side nudge (explicit `getAI().addAssignment(INTERCEPT, mirror, ...)` for NPC fleets near the guest mirror) folded into `CoopNpcFleetReplicator`. Decide after observing; do not pre-build it.

**Steps:**

- [x] Generalize `CoopFleetMirror` so it can represent any host fleet keyed by a stable `coopFleetId` (the host-side `fleet.getId()`), carrying faction id and an AI-mode flag, not just the single remote-player fleet. Keep `setAIMode(true)`, `setNoEngaging(1f)` every tick, `setMoveDestinationOverride` + periodic `setLocation` snap, and roster-refresh-on-`fleetHash`-change. Store `coopFleetId` in the mirror's memory under `$coopNpcFleetId` and tag it `setNoAutoDespawn(true)`.
- [x] Factor the per-fleet capture out of `CoopFleetSnapshotFactory` so the same member/`fleetHash` encoding is reused for NPC fleets (do not depend on collection iteration order, per Phase 8).
- [x] **Amendment 2026-08-19 — replicated ship ids must be install-stock ids, because fleet inflation replaces them with runtime ones.** When a player fleet comes near an NPC fleet the engine inflates it: `DefaultFleetInflater.inflate` autofits every ship onto a new variant named `createEmptyVariant(fleet.getId() + "_" + memberIndex, ...)` (`api_pristine/.../fleets/DefaultFleetInflater.java:476`, `VariantSource.REFIT` at `:497`) and `DModManager.setDHull` swaps the hull spec for `hullId + "_default_D"` (`:513`, `Misc.java:3552-3556`). The variant id is derived from the **sender's** fleet id and exists in no other engine. This is not a fail-safe error: `Global.getFactory().createFleetMember(SHIP, <unknown variant id>)` neither throws nor returns null — it substitutes a placeholder ship (observed: a Nebula), so the receiver silently builds N identical wrong hulls with nothing in the log. The inflater records the stock variant it autofit from in `setOriginalVariant(...)` (`:480-482`); capture prefers it, then `getHullVariantId()`, then `getSpecId()`, and streams the first the **sender's own spec store** contains (`SettingsAPI.doesVariantExist`) — sound cross-install because the handshake already requires an identical mod manifest. The receiver validates before asking and cross-checks the hull it got back. Consequence accepted for v1: mirrors of inflated fleets ride clean stock variants (no d-mods, no autofit loadout); see `docs/PHASE14_SPIKE_NOTES.md`.
- [x] **Amendment 2026-08-19 — "refresh roster only when `fleetHash` changes" is a latch, and capture must degrade per ship.** The gate above is correct as a performance rule but it commits *whatever the rebuild produced*: a truncated capture or a partially-built roster hashes the same as the good snapshot it came from, so the mirror wears it until the host fleet's own roster changes, which for a stable patrol is never. Two rules now attach to the gate. (1) Host capture degrades one ship at a time — `CoopFleetSnapshotFactory.captureMembers` catches per member, because `FleetData.syncIfNeeded` can hand back an emptied or stale member list (`nb/.../fleet/FleetData.java:630-637`, `:893-902`) and one throwing ship used to truncate the whole roster to zero. (2) The guest retries an incomplete rebuild exactly once before committing the hash (`CoopFleetMirror.shouldCommitRoster`). Mirror name/faction also follow the snapshot instead of being frozen at `ensureNpcFleet`. Diagnostic pair behind `CoopDebug`: `Coop host fleet roster …` (host, per `fleetHash` change) and `Coop mirror roster rebuilt …` (guest, per rebuild) — see `docs/PHASE14_SPIKE_NOTES.md` for the open half of that investigation.
- [x] Define `CoopNpcFleetSnapshot` per-fleet fields: `coopFleetId`, `factionId`, `name`, `locationId`, `x`, `y`, `velocityX`, `velocityY`, `transponderOn`, `aiAssignmentSummary`, `fleetHash`, and reused `members`. Use the Phase 8 compact delimiter-escaped text encoding (the TCP envelope JSON parser is flat — no arrays).
- [x] Define `CoopNpcFleetSetSnapshot` as the host's authoritative set of `coopFleetId`s plus an order-independent set hash, encoded as a single delimited string (the `BASE_SET` pattern from Phase 13).
- [x] Add `NPC_FLEET_SET` (TCP, reliable): the **full** authoritative existence/identity/roster set the guest reconciles against — rebroadcast the **whole** set whenever its hash changes (not add/remove deltas), and have the guest reconcile idempotently: add fleets present in the host set but missing locally, remove local mirrors absent from the host set. This guarantees existence parity sector-wide, including off-screen fleets. Full-set rebroadcast is chosen for v1 because it is self-correcting (no delta ordering/lost-packet bugs); if a busy sector makes the message size a problem in practice, switch to `NPC_FLEET_ADD`/`NPC_FLEET_REMOVE` deltas as a later optimization — do not pre-build deltas.
- [x] Add `NPC_FLEET_MOTION` (UDP, 10 Hz): batched `coopFleetId` + `locationId` + `x`/`y`/`velocity` records for fleets in a location where *either* player currently is (bounded bandwidth). Off-screen mirrors keep their last known position; precise off-screen motion is unnecessary because economy/intel/encounter outcomes are host-authored deltas (Phase 12).
- [x] Implement `CoopNpcFleetReplicator` (host): each tick enumerate all non-player campaign fleets (skip the local player fleet and the Phase 8 mirror), build the set snapshot, emit `NPC_FLEET_SET` on change over TCP, and emit `NPC_FLEET_MOTION` at 10 Hz for player-relevant locations over UDP.
- [x] Implement `CoopFleetMirrorRegistry` (guest): map `coopFleetId → CoopFleetMirror`; apply `NPC_FLEET_SET` reconcile (create/dispose mirrors) and route `NPC_FLEET_MOTION` records to the matching mirror. Idempotent: re-applying the same set is a no-op.
- [x] Implement `CoopNpcFleetSuppressor` (guest), two layers: (1) at `onGameLoad`, `removeScript(...)` the known sector-level NPC fleet spawners (`RouteManager`/`EconomyFleetRouteManager`/`EconomyFleetAssignmentAI`, `MercFleetManagerV2`, faction patrol/`*FleetManager`s, `RemnantFleetManager`/`RemnantSeededFleetManager`, `PersonBountyManager`, etc.) and remove any NPC fleets they already spawned; (2) each frame, sweep all locations and remove any fleet that is neither the local player fleet nor tagged `$coopMirrorFleet`/`$coopNpcFleetId` — the robust net, since enumerating every spawner is fragile.
- [x] Keep NPC mirrors non-engaging (`setNoEngaging` every tick) so the guest's *mirror* fleets never autonomously start combat. Real engagements happen on the host's authoritative copy: when either player engages an NPC fleet, that player pilots the battle locally (Phase 14, solo own-fleet combat) and the outcome — including the NPC fleet's death/damage — is reported and integrated back into this authoritative set by Phase 15, which re-broadcasts `NPC_FLEET_SET`. (NPC fleets *should* react to and pursue either player; that reaction is simulated on the host and replicated here.)
- [x] Register `CoopNpcFleetReplicator` on the host and `CoopFleetMirrorRegistry` + `CoopNpcFleetSuppressor` on the guest from the session role, alongside the existing Phase 8 pump wiring; dispose all mirrors on session end.
- [x] **Relationship to Phase 13 forks:** under full replication the guest constructs no NPC fleets, so the fleet-*construction*/*inflation* RNG forks (`FleetFactoryV3`, `DefaultFleetInflater`, `FleetEncounterContext`, etc.) are redundant. These were never built and have been **retired** in Phase 13 (see its "Forks retired" note); the only load-bearing forks are the worldgen/deep-space ones (`Misc`, `GateHaulerLocation`, `NamelessRock`) for the seed-lock fingerprint. Nothing to do here beyond not reintroducing them.
- [x] **Transponder decoupling** (`CoopPresenceIndicator` + `CoopFleetMirror`): remove the unconditional `setTransponderOn(true)` from `CoopPresenceIndicator.apply` so the presence marker's *visibility* comes only from the large `setSensorProfile` + `getDetectedRangeMod()` bonus (independent of transponder, so the partner marker stays visible even when running dark). Let `CoopFleetMirror` drive transponder purely from `snapshot.transponderOn()` (it already does in `driveMovement`). Net effect: the guest's host-side mirror reports the guest's **real** transponder state, so patrols can react to running-dark. (Cargo replication — needed for actual contraband *inspection* — stays deferred; see below.)
- [x] **Diagnostics behind `CoopDebug`:** gate an NPC-set dump behind the existing opt-in (`-Dcoop.debug.diagnostics=true` / sector flag `$coopDebug`). When on, the host logs its authoritative NPC set (count + `coopFleetId`s) and the guest logs its reconciled mirror set, so the two can be diffed during the smoke test.
- [x] Add tests: stable order-independent set hash; reconcile add/remove decisions; mirror keyed by `coopFleetId`; suppressor removes exactly untagged non-player fleets, **preserves** the player + `$coopMirrorFleet` fleets, and is a **no-op on the host role**; snapshot encode/decode round-trip.
- [x] Run a two-instance smoke test: confirm the guest shows the same NPC fleets as the host in a shared system (count, factions, rosters), that fleets the host despawns disappear on the guest, and that the guest's `starsector.log` never logs an independently-spawned NPC fleet. *(**Verified in-game 2026-08-09** on a fresh two-client session at Sindria: fleet sets matched, despawns propagated, no independently-spawned NPC fleet in the guest log.)*
- [ ] Commit with `git add mods/coop && git commit -m "feat: replicate host NPC fleet population"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- The guest renders exactly the host's NPC fleet set (existence + faction + roster), reconciled idempotently from `NPC_FLEET_SET` over TCP.
- NPC fleet motion in a player-occupied location follows host `NPC_FLEET_MOTION` at 10 Hz over UDP; off-screen fleets stay present without precise motion.
- The guest runs no native NPC fleet spawning or AI; the per-frame suppressor sweep removes any untagged non-player fleet while preserving the local player fleet and the Phase 8 remote-player mirror.
- NPC mirrors never initiate combat on the guest; combat is bridged in Phase 14.

**Deferred / out of scope (explicitly NOT delivered by Phase 9):**

- **Cargo replication + contraband/smuggling inspection against the guest.** `CoopFleetSnapshot` carries ships/roster but no cargo, so the host's reverse-mirror of the guest has empty holds and a patrol "search for banned goods" finds nothing. Phase 9 replicates the guest's *real* transponder state onto the reverse-mirror, but see the next bullet — that fidelity alone does not produce a reaction. Requires a **Phase 8 snapshot extension** (add cargo to `CoopFleetSnapshot`); track separately.
- **⚠️ Guest transponder-off / faction reactions no longer fire (regression surfaced in-game 2026-06-02).** Pre-Phase-9, the guest's *own local NPC simulation* detected it running dark and applied the confrontation/standing penalty locally (this is what the rep-sync `PLAYER_REP_SNAPSHOT` was built to overwrite — see `CoopCampaignReplicator` / `CoopRepDelta` comments). Phase 9's suppressor deliberately removes that local sim, and the host's NPCs react to the guest only via its `$coopMirrorFleet` AI fleet: **pursuit works (Step 0 verified), but the transponder-off confrontation + standing penalty is a player-fleet-specific engine event that does not fire for an AI mirror and is not replicated back.** Net: faction fleets ignore the guest running dark, and that path can no longer be used to test rep sync. **Fix lives in Phase 14** (host pushes `DIALOG_BEGIN`; guest resolves the customs/inspection locally and reports rep/fleet deltas) — see Phase 14's NPC-initiated-dialog item, now explicitly covering the transponder-off standing penalty. Until then, test rep sync via a host-authoritative trigger (host-side action or console `AdjustRelationship`). Memory: `guest-transponder-reactions-gone`.
- **The actual engagement when an NPC catches the guest** → Phase 14 (solo own-fleet combat bridge). Phase 9 makes NPCs present, reacting, and pursuing; it does not consummate the fight or any inspection dialog.
- **Integrating combat outcomes** (NPC death/damage) back into the authoritative set and re-broadcasting `NPC_FLEET_SET` → Phase 15.
- **`NPC_FLEET_ADD`/`NPC_FLEET_REMOVE` delta optimization** — only if busy-sector full-set TCP rebroadcast proves a bandwidth problem in practice. Do not pre-build.
- **Note on the "Phase 13 `BASE_SET` / suppression pattern" references above:** Phase 13 is not built yet, so those patterns are *established here in Phase 9* (the set-hash encoding and the guest-manager suppressor), not reused from existing code.

## Phase 9b: NPC Fleet Action Text (BUILT 2026-08-20)

Hovering an NPC fleet on the host reads "Unidentified Fleet — Unknown, travelling to Jangala", or ", pursuing your fleet". On the guest the same fleet read only "Unknown". The action line is not a field on the fleet: the vanilla tooltip derives it live from the fleet's `ModularFleetAI` (current assignment, tactical target, largest enemy, the fleeing/busy/maintaining-contact flags), and a guest mirror is an empty AI-mode fleet with no assignments whose tactical module never acquires a target. Phase 9 defined `CoopNpcFleetSnapshot.aiAssignmentSummary` for exactly this and the host wrote `""` into it; 9b fills it in.

Two pieces. `coop.fleet.CoopNpcActionTextCapture` (host) transcribes the resolution in `com.fs.starfarer.ui.impl.StandardTooltipV2$9` over a captured value object, with two observer rewrites, because the text is resolved on the host and read by the guest: a target that is the host's own player fleet is named with the `CoopPresenceIndicator.presenceLabel` the guest's mirror of the host already wears, and a target that is the guest's mirror on the host becomes "your fleet". `fleet.getBattle() != null` rides the wire too, since the mirror is never in a battle. On the guest, `CoopFleetMirror.applyActionText` pins the text with both `getAI().setActionTextOverride(...)` and `setNullAIActionText(...)`, because which of the two the tooltip reads depends on whether `createEmptyFleet(..., true)` gave the mirror a `ModularFleetAI`; a `CoopDebug`-gated line prints the mirror's actual AI class once so the log answers that. Empty maps to null on both setters, or the tooltip paints a dangling ", " after every idle fleet's name.

Cost and blast radius: one capture per replicated fleet per `NPC_FLEET_SET` (1 Hz), never on the 10 Hz motion path. `capture` catches `RuntimeException | LinkageError` and returns `""`, and the result is flattened to one line and capped at 80 characters, so no fleet can break a set send or inflate the message. No wire-format change: the field, its header count and its `CoopFleetCodec` escaping were already there.

## Phase 10: Interaction Gate

**Agent prompt:**

```text
Implement Phase 10 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add host arbitration for interactions and lock guest world interaction while the host is in a dialog or combat. Do not implement full dialog replication yet; v1 can block and show status.
```

**Files:**

- Create `mods/coop/src/main/java/coop/interaction/CoopInteractionGate.java`
- Create `mods/coop/src/main/java/coop/interaction/CoopInteractionClaim.java`
- Create `mods/coop/src/test/java/coop/interaction/CoopInteractionGateTest.java`
- Modify `mods/coop/src/main/java/coop/input/CoopCampaignInputBlocker.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [x] Add message types `INTERACTION_CLAIM`, `INTERACTION_ACCEPT`, `INTERACTION_REJECT`, and `INTERACTION_RELEASE`. (`CoopMessages`, with flat-JSON payload builders.)
- [x] Host accepts the first claim for an entity id and rejects later claims until release. (`CoopInteractionGate.arbitrate` assigns a monotonic `hostSeq`; reject reason `already_claimed_by:<playerId>`.)
- [x] Guest calls `CampaignUIAPI.setDisallowPlayerInteractionsForOneFrame()` every frame when blocked. (`CoopNetPump.applyLocalBlocking`, called from `syncInteractionGate` each frame.)
- [x] Guest consumes campaign input except camera movement while blocked. (`CoopCampaignInputBlocker` interaction mode consumes mouse-button + keyboard input; mouse edge-pan/zoom pass through. Toggled via `CoopTimeLock.setInteractionBlocked`.) **API note:** keyboard camera-pan keys are also consumed in v1; camera remains drivable via mouse edge-scroll + scroll-wheel zoom (`InputEventAPI.isMouseMoveEvent`/`isMouseScrollEvent`).
- [x] Add HUD/message text `Remote player is interacting: <entityName>`. (`CampaignUIAPI.addMessage`, emitted once per entity transition to avoid per-frame spam.)
- [x] Release claim when dialog closes or combat starts/ends. (`detectLocalInteraction` releases on dialog close/target change via `InteractionDialogAPI.getInteractionTarget()`; `CoopInteractionGate.releaseEntity`/`releaseAll` are available for combat start/end in Phase 14.)
- [x] Add unit tests for first-click-wins ordering by host receive sequence. (`CoopInteractionGateTest`, 9 tests incl. message round-trip; all pass.)
- [x] Run two-instance smoke test by clicking the same market/fleet from both clients. *(**Verified in-game 2026-08-09**, to the extent this test is reachable. The sequential path works: claims on `diktat_cnc` were accepted and released in both directions and the gate handed the market back and forth cleanly. Zero `INTERACTION_REJECT` across the session, and **the simultaneous-claim race was deliberately dropped as untestable by hand** — `applyLocalBlocking` (`CoopNetPump.java:1174`) calls `setDisallowPlayerInteractionsForOneFrame()` as soon as the peer's claim is known, so the loser's dialog cannot open, `detectLocalInteraction` never fires, and no claim is sent. A reject requires both dialogs to open inside the round-trip window — ~1 ms on localhost. Arbitration ordering is covered by `CoopInteractionGateTest` (pure host-side logic, no engine dependency), so in-game repetition adds nothing. **Residual gap, already a documented deferral:** `handleInteractionReject` (`:1067`) only clears the tracking id — on a lost race the guest keeps its optimistically-opened dialog and both players sit in the same one. **Forward pointer:** Phase 20 raises RTT to 50–150 ms, widening that window into one players will actually hit; revisit the deferred forced-close there.)*
- [ ] Commit with `git add mods/coop && git commit -m "feat: gate shared campaign interactions"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- Simultaneous same-entity interaction resolves to one accepted player.
- Rejected player sees a clear blocked status and cannot interact until release.

> **Note:** this phase gates *who* may interact; it does not replicate dialog *outcomes*. World-mutating outcomes (salvage/exploration entity consumption, market transactions) are replicated in Phase 12; combat outcomes in Phase 15.

## Phase 11: Shared Pause Coordinator

> **What this is:** a small, foundational extension of the Phase 7 time lock that lets either player assert a **shared** pause (both clocks stop together, never desync) via an OR-of-intents the host computes and broadcasts. It is extracted into its own phase because it is reused by two unrelated triggers — the guest opening a blocking UI screen (built here) and combat-start (Phase 14 hooks into it) — and because it can be built and verified independently of the high-risk combat phase. It depends only on Phase 7 (time lock) and Phase 10 (interaction gate's guest-dialog open/close tracking).

**Agent prompt:**

```text
Implement Phase 11 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Build CoopSharedPauseCoordinator: the host computes the effective shared pause as an OR of intents (hostPauseIntent || guestPauseIntent || eitherInCombat) and sets its own clock; the effective value rides the existing Phase 7 TIME_SNAPSHOT to the guest, whose clock always follows the host snapshot (never setPaused locally to drive divergence). Add the guest→host PAUSE_INTENT message. Wire the guest UI-screen trigger: opening any vanilla-blocking screen asserts guestPauseIntent, clearing on close. Do NOT add the combat trigger here (Phase 14 feeds combat intent into this coordinator). No guest fast-forward.
```

**Files:**

- Create `mods/coop/src/main/java/coop/time/CoopSharedPauseCoordinator.java`
- Create `mods/coop/src/test/java/coop/time/CoopSharedPauseCoordinatorTest.java`
- Modify `mods/coop/src/main/java/coop/time/CoopTimeLock.java`
- Modify `mods/coop/src/main/java/coop/input/CoopCampaignInputBlocker.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`

**Steps:**

- [x] Add a guest→host `PAUSE_INTENT` message (reliable TCP) carrying a `source` (`KEY`/`SCREEN`), `paused` (bool), and a monotonic `intentSeq` for last-writer-wins debounce (independent of the network envelope `seq`). (`CoopMessages.pauseIntent` + `CoopMessages.PauseSource`.)
- [x] Implement `CoopSharedPauseCoordinator`: the host tracks `hostPauseIntent`, `guestKeyPauseIntent`, `guestScreenPauseIntent`, and `eitherInCombat`, and computes `effectivePaused = hostPauseIntent || guestKeyPauseIntent || guestScreenPauseIntent || eitherInCombat`. The host calls `Global.getSector().setPaused(effectivePaused)`; the effective value rides the existing Phase 7 `TIME_SNAPSHOT` to the guest, whose clock follows the host (the guest never drives `setPaused` locally). **Host intent capture:** a host-side `CoopHostPauseInputListener` intercepts the host's `GENERAL_PAUSE` press *edge*, routes it to `onHostPauseKey()`, and consumes it so vanilla never flips the host clock directly (this removed a 1-frame unpause→repause flicker in the OR-override case). Vanilla *auto*-pause (combat/messages, not via the pause control) still reaches the clock and is picked up as host intent by `CoopNetPump.syncHostSharedPause` edge detection; the first active frame seeds `hostPauseIntent` from the current clock (may still be held paused from the Phase 7 connect-time hold).
- [x] **Pause-key resolves against observed state, not a blind toggle.** Both the guest and host pause keys are resolved against the locally observed pause state (`observedPaused`, updated each frame from the applied snapshot on the guest / the applied effective on the host): a tap means "give me the opposite of what I see." This fixed a confirmed bug where the guest tapping pause while the host held the world paused flipped its own intent `false→true`, queuing a phantom pause that surfaced when the host later released.
- [x] **Guest UI-screen trigger:** when the guest opens any vanilla-blocking screen, assert the screen pause and clear it on close. Detected via `CampaignUIAPI.isShowingDialog()` / `isShowingMenu()` / `getCurrentCoreTab() != null` (the latter covers map/fleet/character/refit/cargo/intel) in `CoopNetPump.isVanillaBlockingScreenOpen`; forwarded as a level only on change.
- [x] Update `CoopCampaignInputBlocker`: takes a nullable `CoopSharedPauseCoordinator`; on the guest's `GENERAL_PAUSE` press *edge* it records a pause-key press (`recordGuestPauseKeyPress`) and still consumes the event so vanilla never pauses the guest's clock locally. The pump forwards each press as `PAUSE_INTENT(KEY, !observedPaused)`. `FAST_FORWARD` stays unconditionally consumed (no guest fast-forward).
- [x] **Unpause authority (v1 decision — "override only manual guest pauses"):** each player clears its own intent; additionally the **host may force-clear the guest's *key* (manual) pause** (host pause key while paused → `onHostPauseKey` clears `hostPauseIntent` + `guestKeyPauseIntent`), but **not** the guest's *screen* pause (so a guest reading a menu is never interrupted — it clears only when the guest closes the screen). To avoid host/guest desync on a force-clear the guest keeps no sticky key bit: it sends the key intent on every press, resolved against observed state. Guest shared pause is always on in v1 (no `coop.allowGuestPause` toggle).
- [x] Debounce/serialize `PAUSE_INTENT` with `intentSeq` (last-writer-wins, shared counter across both sources): `applyGuestKeyPauseIntent`/`applyGuestScreenPauseIntent` ignore any `intentSeq <= appliedGuestSeq`. (`PAUSE_INTENT` is TCP/ordered, so this is a belt-and-suspenders guard.)
- [x] Add tests: 16-row OR truth table (host/key/screen/combat), guest screen level change-detection, guest key press forwarded as opposite-of-observed (incl. the no-phantom-pause regression), host-override-asymmetry (host clears guest key pause but not screen pause), `intentSeq` last-writer-wins debounce, `PAUSE_INTENT` round-trip (`CoopSharedPauseCoordinatorTest`), the guest pause-key press recording (`CoopCampaignInputBlockerTest`), and the host pause-key interceptor (`CoopHostPauseInputListenerTest`). Full `gradlew test build` passes.
- [x] Run a two-instance smoke test: guest opens the map → both clocks stop; guest closes it → both resume; host clock never desyncs. **Confirmed in-game (2026-05-30):** guest screen pauses both worlds and resumes on close; guest pause key cannot queue a phantom pause or unpause the host; host can override a guest *key* pause but not a guest *screen* pause; no clock flicker/desync.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add shared pause coordinator"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Files (additions to the listed set):**

- Create `mods/coop/src/main/java/coop/input/CoopHostPauseInputListener.java` (host pause-key interceptor)
- Create `mods/coop/src/test/java/coop/input/CoopHostPauseInputListenerTest.java`

**Acceptance:**

- Either player can cause a **shared** pause; the host computes `effectivePaused` as the OR of host/guest-key/guest-screen/combat intents and both clocks stop together. *(Confirmed in-game.)*
- The guest opening any vanilla-blocking screen pauses the shared world and resumes on close, with no clock desync (the guest follows the host snapshot, never a local `setPaused`). *(Confirmed.)*
- The guest still cannot *independently/locally* desync the clock or fast-forward, cannot unpause the host, and cannot queue a phantom pause; its pause is always a shared intent folded in by the host. *(Confirmed.)*
- The host can override (resume past) a guest **manual/key** pause but **not** a guest **screen** pause. *(Confirmed — v1 unpause-authority decision.)*
- The coordinator exposes the `eitherInCombat` intent hook (`setEitherInCombat`) that Phase 14 will set on `BATTLE_BEGIN`/`BATTLE_END`.

## Phase 12: Campaign State Replication (Rep, Economy, Missions, Salvage, Abilities)

> **Scope note:** this phase is the hub for replicating host-authoritative campaign state. It covers shared reputation, the shared mission/bar pool + claims, host-authored market contents + transactions, salvage/exploration outcomes, faction-to-faction relations, and world-affecting ability arbitration. It is large; if an implementing agent finds it unwieldy, it may be split along the labelled sub-sections (each is independently testable), but keep them in this order.

**Agent prompt:**

```text
Implement Phase 12 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Replicate the v1 campaign state: shared reputation, shared mission/bar pools + first-come claims, host-authoritative market contents (submarket stock + hireable officers/mercenaries) and transaction effects, salvage/exploration outcomes (own-action resolve + host-integrated world delta), faction-to-faction relations, and host-arbitrated world-affecting abilities. Keep event payloads explicit and logged.
```

**Files:**

- Create `mods/coop/src/main/java/coop/campaign/CoopCampaignReplicator.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopCampaignEventListener.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopRepDelta.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopMissionBoardSync.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopMissionClaim.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopMarketSync.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopWorldDelta.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopFactionRelations.java`
- Create `mods/coop/src/main/java/coop/campaign/CoopAbilityArbiter.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopRepDeltaTest.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopMissionBoardSyncTest.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopMarketSyncTest.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopWorldDeltaTest.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopFactionRelationsTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [x] Register a `CampaignEventListener` on game load for connected coop sessions. (`CoopCampaignEventListener extends BaseCampaignEventListener`; registered via `sector.addTransientListener` from `CoopNetPump.syncCampaignReplicator` once the session is active, removed + state cleared on session end — mirrors the fleet-mirror lifecycle.)
- [x] Initialize the guest-side conceptual personal reputation baseline to 0 before applying host-owned shared reputation events. (`CoopRepDelta.BASELINE = 0`; `relationship()` defaults unseen targets to 0.)
- [x] Capture `reportPlayerReputationChange(String faction, float delta)` and `reportPlayerReputationChange(PersonAPI person, float delta)`. (Host-only capture in `CoopCampaignReplicator`; reads the *resulting* relationship via `player.getRelationship(factionId)` / `person.getRelToPlayer().getRel()`.)
- [x] Host sends `REP_DELTA` messages with target type, target id, delta, and resulting relationship value. (`CoopMessages.repDelta`; floats ride as quoted strings since the flat envelope parser only handles longs/strings, read back via `requiredPayloadFloat`.)
- [x] Guest applies host `REP_DELTA` by setting relationship to the resulting value rather than re-running local rep logic. (`applyRepDelta` sets `player.setRelationship(targetId, resulting)` under the replay guard.)
- [x] Define `MISSION_POOL_SNAPSHOT` fields: `marketId`, `sourceType` (`BAR`, `CONTACT`, `BOUNTY`, `MISSION_BOARD`), `missionId`, `title`, `giverId`, `rewardSummary`, `acceptedByPlayerId`, and `expiresAtDay`. (`CoopMissionBoardSync.Entry`; list ships as a self-contained delimited string via `CoopDelimited`, same as the Phase 8 fleet encoding.)
- [x] Host captures the currently visible mission/bar/contact/bounty entries when a shared board opens and broadcasts `MISSION_POOL_SNAPSHOT`; guest renders/filters from the host pool instead of generating an independent pool. (`broadcastMissionPool` + guest `applyMissionPool` + `visibleEntriesFor`. **In-game extension point:** the concrete enumeration of a market's bar/mission-board offers — `BarEventManager` / mission-board intel — is wired in-game; the snapshot/claim infrastructure is complete and unit-tested.)
- [x] Define `MISSION_CLAIM_REQUEST`, `MISSION_CLAIM_ACCEPT`, and `MISSION_CLAIM_REJECT` messages.
- [x] Host accepts the first claim for an unclaimed `missionId`, records `acceptedByPlayerId`, rejects later claims with `already_claimed_by:<playerId>`, and logs the claim sequence number. (`CoopMissionBoardSync.arbitrate`, monotonic `hostSeq` — first-come by host receive order, mirroring `CoopInteractionGate`.)
- [x] Apply mission reward ownership to the accepting player. Mission rewards are never split; combat spoils go to the solo fighter (Phase 15). The two paths do not mix — there is no 50/50 splitter in v1. (Claim records `acceptedByPlayerId`; no splitter exists.)
- [x] Add same-market bar presence text so a bar listing can show the other player's presence without granting both players the same first-click claim. (`claimHolder(missionId)` surfaces the holder for presence text without granting the claim; the gate stays first-come.)
- [x] Add lightweight event messages for market open/cargo update and economy tick index to support logging and later assertions. (`reportPlayerOpenedMarket{AndCargoUpdated}` → `MARKET_SNAPSHOT`; `reportEconomyTick` drives the faction-relation diff; both logged.)

**Market contents + transactions (host-authoritative):**

- [x] **Host-authoritative market contents** (`CoopMarketSync`): the submarket ship/weapon/fighter stock and the hireable officer/mercenary pool at a market are host-owned. When either player opens a market, the host snapshots its contents and the guest renders the host's snapshot instead of generating its own (reuses the `MISSION_POOL_SNAPSHOT` pattern). This closes the "host and guest see different shop/officer stock" divergence. (Model supports `COMMODITY/SHIP/WEAPON/FIGHTER/OFFICER/MERC`; `captureMarketContents` reads submarket commodity stacks + mothballed ships from the engine. **In-game extension point:** weapon/fighter/officer/merc capture extends the same submarket loop and is wired in-game.)
- [x] **Transaction replication:** buy/sell/hire applies to the host's canonical market. The acting client sends a `MARKET_TXN` (`marketId`, kind ∈ `COMMODITY`/`SHIP`/`WEAPON`/`OFFICER`/`MERC`, item id, qty, unit price); the host applies it to the authoritative market (stock/price/availability) and re-broadcasts the resulting market delta. Credits/cargo/ships/officers land in the *acting* player's own per-player state. Simultaneous same-market use cannot arise: the Phase 10 gate is a global one-dialog-at-a-time lockout *(2026-08-20 correction — this line originally said "mutexed by Phase 18"; the rescoped Phase 18 instead closes the WAN-latency race in that gate, and the per-submarket lock system is cancelled)*. (`onPlayerMarketTransaction` → host applies locally + rebroadcasts `MARKET_SNAPSHOT`; guest sends `MARKET_TXN`, host `applyTransaction` decrements/removes stock.)

**Salvage / exploration outcomes (own-action model):**

- [x] **Outcome replication:** when either player salvages or explores a world entity (derelict, ruin, debris field, cargo pod, domain probe, research station, sensor-ghost cache), the interacting client resolves it locally via vanilla and keeps the loot in its own per-player cargo (same rule as the solo fighter keeping combat spoils); it then sends a `WORLD_DELTA` reporting the entity's consumed/looted state. The Phase 10 interaction gate already arbitrates *who* may interact; this adds the missing *outcome* so the entity is consumed on **both** clients and cannot be re-looted by the other. Loot RNG is per-player and need not match (it only fills the actor's cargo), so no determinism fork is needed. (`reportWorldDelta` → `handleWorldDelta`; first apply removes the entity from its location, host rebroadcasts, the `Ledger` makes re-apply a no-op.)
- [x] Define `WORLD_DELTA` fields: `entityId`, `kind` (`SALVAGE`,`EXPLORE`,`CONSUME`,`CONSTRUCT`,`PARLEY`), `consumed` (bool), `newStateJson`, `actingPlayerId`. This is the single guest→host channel for any guest-driven interaction that mutates shared/world/host-owned-fleet state. Host integrates into authoritative world state and re-broadcasts; guest applies idempotently (already-applied → no-op). (`CoopWorldDelta` + `CoopWorldDelta.Ledger`.)

**Faction-to-faction relations:**

- [x] **Relationship replication** (`CoopFactionRelations`): the host broadcasts `FACTION_REL_DELTA` (`factionA`, `factionB`, resulting relationship value) whenever vanilla changes inter-faction standings; the guest sets the resulting value rather than re-simulating. Keeps who-is-hostile-to-whom — and thus the guest's own market access and encounter outcomes — consistent across clients. Extends the `REP_DELTA` apply path. **No vanilla event reports inter-faction changes**, so the host diffs all faction pairs on each `reportEconomyTick` (bounded by faction count, daily cadence; player-faction pairs excluded since those ride `REP_DELTA`) and broadcasts only changed pairs. Pair key is order-independent (symmetric).

**World-affecting abilities (host-arbitrated):**

- [x] **Ability arbitration** (`CoopAbilityArbiter`): fleet abilities that touch shared/NPC state (interdiction pulse, distress call, sensor burst, etc.) are host-arbitrated — the activating client sends an `ABILITY_ACTIVATE` intent (`abilityId`, source player, target/area); the host applies the effect to the authoritative NPC fleets/world and broadcasts the result. Purely-local abilities (emergency burn, sustained burn, transponder toggle, go-dark affecting only the user's own detection) stay local and are not arbitrated. Capture via `CampaignEventListener` ability-activated where available. (`onPlayerActivatedAbility` classifies; unknown ids default to world-affecting (safe — arbitrate rather than risk a desync). Guest sends `ABILITY_ACTIVATE`; host routes/logs and the concrete per-ability NPC/world effect is applied in-game, propagating back via Phase 9 `NPC_FLEET_SET`.)

**Special bar events + rules-dialog outcomes:**

- [x] **Host-authored special bar-event pool:** beyond bar *missions*, the special bar *events* (one-time unique ship/blueprint/AI-core offers, rumor tip-offs, special recruitment — from `BarEventManager`) are host-owned. Extend `MISSION_POOL_SNAPSHOT`/the pool sync so the guest renders the host's bar-event list and one-time offers are first-come-claimed (so both players cannot take the same offer). The purchased item lands in the claiming player's own cargo; any world effect (a revealed location, a consumed offer) goes through `WORLD_DELTA`. (Bar events ride the same `MISSION_POOL_SNAPSHOT`/claim path via `SourceType.BAR`; the consumed-offer world effect uses `WORLD_DELTA`. Concrete `BarEventManager` enumeration is the in-game extension point noted above.)
- [x] **Story-point & misc dialog world-mutations:** per-player SP effects (skills, bonus XP, establish-contact) stay local. The rare dialog options that mutate *shared* world state (spend SP to make a fleet leave, spawn/grant a world item, etc.) report through the same `WORLD_DELTA` guest→host path; the host integrates and rebroadcasts. Do **not** enumerate every `CommandPlugin` — rely on the host self-healing backstop (Design Alignment Notes) for anything not explicitly wired. (`WORLD_DELTA` `kind=PARLEY` carries these; no `CommandPlugin` enumeration.)
- [x] **Stable-location construction:** building a comm relay / nav buoy / sensor array is a shared-world structure — the building client sends a `WORLD_DELTA` (`kind=CONSTRUCT`, location, structure type), the host places it in authoritative world state and rebroadcasts so it exists for both; resource/credit cost is per-player. Low-frequency; reuses the `WORLD_DELTA` path. (`kind=CONSTRUCT`; a non-consuming delta is applied without consuming an entity id.)

- [x] Add an event replay guard so applying host events does not rebroadcast them. (`CoopCampaignReplicator.ReplayGuard`, re-entrant depth counter; every engine-apply path wraps `begin()/end()` and every host-capture path skips while `isReplaying()`.)
- [x] Add tests for replay guard, final-value rep convergence, guest baseline rep 0 before host deltas, first-come mission claim acceptance, rejected duplicate claim, host market-contents render + `MARKET_TXN` apply, idempotent `WORLD_DELTA` (no double-loot), `FACTION_REL_DELTA` convergence, and world-affecting vs local ability classification. (`CoopRepDeltaTest`, `CoopFactionRelationsTest`, `CoopMissionBoardSyncTest`, `CoopMarketSyncTest`, `CoopWorldDeltaTest`, `CoopAbilityArbiterTest` — 30 tests, all green; every new message type also round-trips. Full `gradlew clean test build` → `BUILD SUCCESSFUL`.)
- [ ] Run two-instance smoke test: trigger a known rep change; race both players to accept the same bar/mission-board entry; confirm both see identical shop/officer stock and a guest purchase updates the host market; salvage a derelict on one client and confirm it is consumed (not re-lootable) on the other. *(**Partially run 2026-08-09** at Sindria — market-contents leg only; rep, mission race, purchase-propagation, and salvage legs still outstanding. Result: the snapshot pipeline itself is sound (`MARKET_SNAPSHOT market=sindria items=38 {COMMODITY=14, WEAPON=7, FIGHTER=1, SHIP=16}` captured host-side and applied guest-side on every open, no reconstruction warnings) and **commodity, weapon, and fighter stock match exactly**. Three capture-fidelity divergences found and rooted; all three are now tracked as Phase 12c gaps 2a/2b/2c below. **The purchase-propagation leg FAILED** — guest buys 50 fuel, host opens the market, stock is untouched; reproduced twice. Two causes, split by phase: the host's engine apply silently no-ops on unmaterialized submarket cargo while still logging "applied" (→ **Phase 12b**, silent-failure family), and commodity stock is a time-based regenerating stockpile that per-transaction deltas cannot durably move (→ **Phase 12c gap 2e**). **The salvage leg PASSED**: salvaging a derelict ship removed it from the map on both clients, confirming the `WORLD_DELTA(CONSUME)` watcher + `Ledger` dedup path end-to-end for `Tags.SALVAGEABLE` entities. Two earlier attempts (disassembling a makeshift nav buoy; dropping cargo pods for the other player) turned out to exercise entity types the watcher does not track at all — that is Phase 12c gap 4, not a salvage-path failure. **The rep leg PASSED**: a host-side standing loss with `remnant` logged `REP_DELTA faction=remnant delta=-0.03 resulting=-0.53` on the host and `applied REP_DELTA FACTION:remnant -> -0.53` on the guest, with no inbound `REP_DELTA` back on the host (replay guard held). The `PLAYER_REP_SNAPSHOT` heartbeat was observed at exact 30 s intervals throughout, so both halves of the `reputation-sync-model` are confirmed live. Note the guest has no *visual* confirmation for this faction — Remnants are excluded from the vanilla standings UI regardless of coop, so verify hidden factions by log. **Only the mission-race leg remains unrun, and it is blocked until 12c gap 1 lands**, since the two boards do not yet show the same offers to race for.)*
- [ ] Commit with `git add mods/coop && git commit -m "feat: replicate core campaign events"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- Shared reputation converges to the host value.
- Guest does not emit duplicate replicated rep changes while applying host packets.
- Shared mission/bar/contact/bounty entries come from the host pool.
- Simultaneous mission acceptance resolves first-come by host receive sequence, and only the accepting player receives mission rewards.
- Both players see identical market contents (submarket stock + hireable officers/mercenaries) from the host, and a transaction by either updates the host's canonical market.
- A world entity salvaged/explored by one player is consumed on both clients and cannot be re-looted by the other; loot lands only in the acting player's cargo.
- Faction-to-faction relationships converge to the host value, so both clients agree on hostility.
- World-affecting abilities are applied by the host and broadcast; purely-local abilities are not arbitrated.

> **QA follow-up (2026-06-10):** a full code-QA pass over Phases 1–12 (build + tests green; four parallel reviews, every high-severity claim re-verified against source) confirmed the implementation sound — no sandbox violations, transient-script discipline held, Phase 12's replay-guard coverage thorough, locale-safe float encoding throughout. It also found a cross-cutting robustness hole (unguarded inbound message dispatch) and a set of small, localized gaps. All fixes are batched as **Phase 12b** below. Claims examined and **rejected** during verification (do not re-flag): the shared-pause reset guard is unreachable in the flagged state (`hostSharedPauseInitialized` covers it), the fleet-mirror location-snap cadence works out to the intended ~1 Hz, the spawner-name matching is sound (the fleet managers are unobfuscated `api.impl` classes), and host-stuck-paused-after-disconnect is the intended hold-until-ready behavior (recovery belongs to the disconnect-handling phase). **Separately (2026-06-10):** the steps above checked off with an "in-game extension point" caveat (bar/mission enumeration, weapon/fighter/officer/merc market capture, ability world effects) are real unbuilt wiring, now tracked explicitly as **Phase 12c** — do not treat them as done.

## Phase 12b: QA Hardening (Cross-Phase Robustness Fixes)

**Agent prompt:**

```text
Implement Phase 12b from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Apply the batched robustness fixes from the 2026-06-10 QA pass: guard the inbound TCP dispatch loop, clear the TCP queues on shutdown, sweep orphaned coop mirror fleets on game load, retry NPC spawner suppression on failure, make the world-delta ledger idempotent for non-consuming kinds, clear replicator/mission-claim state leaks, pin and validate the UDP peer, and the smaller hardening items. Read the "QA findings" and "Policy decisions" lists first and implement them as written; do not redesign them. Every fix is localized — no new protocol messages, no new architecture.
```

**QA findings (verified against source 2026-06-10 — do not re-litigate):**

1. **Unguarded inbound dispatch (cross-phase, highest priority).** `CoopNetPump.drainInbound()` (~line 289) dispatches messages with no per-message exception guard, and handlers throw freely: `CoopMessages.requiredPayloadString/Long` throw on any missing/malformed field, `CoopMessages.PauseSource.valueOf` (~line 744) throws `IllegalArgumentException` on an unknown value, and a `HANDSHAKE_MANIFEST` arriving before the lobby completes makes `CoopSessionState.hostAcceptHandshake()` throw `IllegalStateException`. Any of these propagates out of `EveryFrameScript.advance()` — in-game that is a crash dialog or a dead pump, triggerable by a version-skewed client or any stray connection to the port.
2. **Stale TCP queues survive shutdown.** `CoopNetService.shutdownLocked()` (~line 462) clears `inboundDatagrams`/`outboundDatagrams` but not the TCP `inbound`/`outbound` queues; a session restarted within the same game process can replay leftover messages (e.g. a stale `HANDSHAKE_RESULT`) into the fresh connection.
3. **Orphaned mirror fleets in saves.** Nothing removes `$coopMirrorFleet`/`$coopNpcFleetId` fleets when a save is loaded *outside* a session — the registry that could dispose them is a fresh empty object after load. A guest who saves mid-session and later opens that save solo keeps every NPC mirror (all `setNoAutoDespawn(true)`, frozen in place) plus the partner mirror as permanent dead fleets; the host's save carries the partner reverse-mirror too.
4. **Spawner suppression never retries.** In `CoopNpcFleetSuppressor.tick()` (~lines 54–61), `spawnersSuppressed = true` is set *outside* the try block, so if `suppressSpawners()` throws once the spawner-removal layer is skipped for the whole session and the per-frame sweep silently does all the work (vanilla spawners keep producing fleets that get deleted every frame).
5. **World-delta ledger not idempotent for non-consuming kinds (latent).** `CoopWorldDelta.Ledger.apply()` (~lines 64–72) never records `consumed=false` deltas (CONSTRUCT/PARLEY), so the host's echo rebroadcast re-reports them as "first apply" to the originator. Harmless today only because `applyWorldDeltaToEngine` acts solely on `consumed=true`; it becomes a real double-apply the moment a CONSTRUCT engine effect (comm relay placement, specced in Phase 12) is wired in.
6. **Replicator state leaks across reconnect.** `CoopCampaignReplicator.dispose()` clears the world ledger but not `trackedSalvageables`/`watchedLocationId` → on reconnect in the same system, entities consumed last session are re-reported as fresh `WORLD_DELTA(CONSUME)`s. Separately, `CoopMissionBoardSync.applySnapshot()` wipes `poolByMissionId` but never purges `claimsByMissionId` entries for missions absent from the new pool → orphan claims accumulate for the session. And `onPlayerActivatedAbility` is the only capture path missing the `replayGuard.isReplaying()` entry guard (latent — currently no replay path fires abilities).
7. **UDP peer trust.** `CoopNetService.readDatagramsLocked()` (~line 184) relearns `udpRemoteAddress` from *any* received datagram when hosting — a stray LAN packet can blackhole the motion stream (host starts sending to the wrong address); the sessionId check only filters payload application, not the address capture. Also, a truncated receive (datagram ≥ the 64 KB buffer) is not detected — self-traffic is safe (60 KB send cap < 64 KB buffer), foreign packets only. *(Resolved by the 12b QA-pass commit `2a77506`: UDP sources are now pinned to the TCP peer's address with a port lock, and buffer-filling datagrams are discarded as possibly truncated. The WAN-grade remainder — sessionId check before address learning + challenge-echo for address changes — lives in Phase 20.1.)*
8. **Mirror fleets are targetable by host NPC AI (added 2026-06-10, bytecode-verified).** No coop code sets any AI-protection flag on mirror fleets (verified: zero `$cfai_*` references in `mods/coop/src`). The engine's fleet-targeting component (`StrategicModule` in the campaign fleet AI) skips a candidate target only when its memory has `$cfai_ignoredByOtherFleets` (`MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS`); without it, a hostile host-side NPC fleet can select the guest's `$coopMirrorFleet` as a target and the engine will run silent autoresolve rounds against it (`Battle.doAutoresolveRound`, no veto hook — `reportBattleOccurred` fires only *after* damage is applied). The mirror's state gets overwritten by the next snapshot, so the visible symptom is flicker/chaos or a despawned mirror, while the guest's real fleet was never in a battle. **Interim fix (12b):** set `MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS` (`"$cfai_ignoredByOtherFleets"`) on every mirror at creation, in `CoopFleetMirror` right where `setNoAutoDespawn(true)` is applied (both creation sites, ~lines 150/166 — covers the partner mirror on both clients and the guest's NPC mirrors). This stops the silent-autoresolve bug *today*, at the cost of host NPC AI also not pursuing the guest's mirror. **It is explicitly interim:** Phase 14 (revised 2026-06-10) removes the permanent flag and replaces it with contact-time protection (per-frame battle-eject via `BattleAPI.leave` + a battle-window-only flag), restoring vanilla pursuit — see Phase 14's NPC-threat design. Until Phase 14 lands, no-pursuit is the accepted interim state.
9. **Smaller items.** `CoopFleetCodec.escape()` handles `\`, `|`, `\n`, `\r` but not `U+001F` — the datagram envelope separator — and ship/fleet names are player-editable. Keepalive pings are suppressed during the seed-lock phase (`maybeSendPing`), making a half-open connection invisible exactly while the host holds paused. `CoopIronModeGuard`'s unbounded recursive scan of persistent data can false-positive on a third-party mod storing its own `isIronMode` key. Handshake checksums are inert placeholders (see the Phase 5 acceptance correction). Tidy-ups in the same areas: `handleLobbyReject` parses `reason` twice (second parse can throw after state change); `handshakeDiffFor`'s catch returns `ex.getMessage()` which can be null — use `ex.toString()`.

**Policy decisions (already made — implement as stated):**

- **Dispatch guard = log-and-drop, never disconnect.** Wrap the per-message dispatch inside `drainInbound`'s loop in `try { ... } catch (RuntimeException ex) { CoopLog.warn(..., "Coop dropped malformed/unexpected message type=" + message.type() + " seq=" + message.seq(), ex); }` so one bad message can never kill the frame or stop the drain loop. Do **not** tear down the session on a malformed message in v1 (the handshake already rejects skewed installs; anything after that is a bug to log, not a peer to punish). Additionally give `handleHandshakeManifest` an explicit connection-state guard (host not in `HOST_CONNECTED` → log + ignore) so the out-of-order case is handled deliberately, not via the catch-all.
- **Queue hygiene:** add `inbound.clear(); outbound.clear();` to `shutdownLocked()` alongside the existing datagram clears.
- **Orphan sweep runs at `onGameLoad`, always.** New small class `coop.fleet.CoopMirrorOrphanSweeper`: scan every location (all star systems + hyperspace), collect fleets whose memory contains `$coopMirrorFleet` or `$coopNpcFleetId`, remove them from their containing location, log one line with the removed count (silent when zero). Invoked from `CoopModPlugin.onGameLoad` before the pump installs — at that moment no session is active by construction, so live-session mirrors are never touched. The live session rebuilds its mirrors from the next `NPC_FLEET_SET`/`FLEET_SNAPSHOT` anyway, which also makes the sweep safe if ordering ever changes. **This sweep is primarily reconnect hygiene, not a solo-play feature:** when a guest loads its coop save to *resume* the coop campaign (the supported flow), the saved mirror fleets are unknown to the fresh, empty `CoopFleetMirrorRegistry` — the `NPC_FLEET_SET` reconcile would create brand-new mirrors for the same host fleets while the suppressor *preserves* the saved tagged orphans (they carry `$coopNpcFleetId`), so every guest save/load cycle would duplicate the entire mirror population. Cleaning saves that get loaded solo is a side benefit, not the goal.
- **Suppressor retry:** move `spawnersSuppressed = true` to the last statement *inside* the try block, so a throw leaves it false and the next tick retries.
- **Ledger idempotency for non-consuming kinds:** track them in the same set under a kind-prefixed key (`delta.kind().name() + ":" + entityId`) so a CONSTRUCT on entity X neither blocks nor is blocked by a later CONSUME on X (consumed entries stay keyed by raw `entityId` — do not migrate them). First apply of a given (kind, entity) returns `true`, every later apply returns `false`. Update the Javadoc contract sentence to cover both cases.
- **State-leak clears:** `CoopCampaignReplicator.dispose()` additionally does `trackedSalvageables.clear(); watchedLocationId = null;`. `CoopMissionBoardSync.applySnapshot()` purges `claimsByMissionId` entries whose missionId is not in the incoming entry list (host and guest run the same purge — the host's pool is canonical and claims for vanished offers are dead bookkeeping). `onPlayerActivatedAbility` gets the same `if (replayGuard.isReplaying()) return;` entry guard as every other capture path.
- **UDP peer pinning by address, not port:** when a TCP connection is established, record the peer's `InetAddress` (from the TCP channel's remote address). `readDatagramsLocked` accepts a datagram — and (on the host) learns/relearns the UDP return address — only when the datagram source's `InetAddress` equals the pinned one; otherwise drop it and log a single warning (once per session, with the offending address). The UDP source *port* stays learned from the first valid datagram (it legitimately differs from the TCP port). Truncation guard: after `channel.receive`, if `!datagramBuffer.hasRemaining()` (buffer filled to capacity) discard the datagram with a warn instead of decoding a truncated payload.
- **Codec:** add `U+001F` to `CoopFleetCodec.escape`/`unescape` (escape sequence `\x1f` style, matching the existing table).
- **Keepalive:** remove the seed-lock-phase suppression branch in `maybeSendPing` — pings flow whenever the lobby phase is past (i.e. also while `handshakeValidated && seedLong == null`). The pre-lobby suppression stays.
- **Mirror AI-protection flag:** in `CoopFleetMirror`, set `memory.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true)` immediately after each `setNoAutoDespawn(true)`. Test: mirror creation leaves the flag set on the fleet's memory (both the player-mirror and NPC-mirror paths).
- **Iron-mode scan depth:** cap the recursive `containsIronModeTrue` walk at depth 2 (top-level + one nested map/collection level) — deep third-party persistent data can no longer false-positive.
- **Checksums (bounded spike, then docs):** attempt to read each enabled mod's `mod_info.json` through the public `SettingsAPI` text-loading API from inside the sandbox and hash it with the existing `CoopChecksum.sha256Text`. If a working path exists, wire it for `mod_info.json` only (jar checksums stay `UNAVAILABLE` — no file API). If the sandbox blocks it, stop: keep the placeholders and finalize the documentation instead. Either way, update `docs/starsector-runtime-limitations.md` with the outcome and keep the Phase 5 acceptance correction accurate. Time-box this; the git-commit comparison + Phase 6 fingerprint remain the real guards.
- **Out of scope for 12b:** host-stuck-paused-after-guest-disconnect (intended hold-until-ready behavior; proper recovery belongs to the disconnect-handling phase) *(partially superseded 2026-08-17: the reconnect drill failed, so basic disconnect handling — peer-slot release, session rewind, pre-session traffic gate — landed in 12b after all; see the reconnect-fix step. The hold-until-ready pause on disconnect is now deliberate behavior, and richer recovery UX (reconnect grace, dialogs) still belongs to Phases 20/21)*, `NPC_FLEET_SET` enumeration cost (design choice, revisit only if profiling shows it), and any new protocol messages.

**Files:**

- Create `mods/coop/src/main/java/coop/fleet/CoopMirrorOrphanSweeper.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopMirrorOrphanSweeperTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/src/main/java/coop/net/CoopNetService.java`
- Modify `mods/coop/src/main/java/coop/fleet/CoopNpcFleetSuppressor.java`
- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetMirror.java` (AI-protection flag)
- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetCodec.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopWorldDelta.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignReplicator.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopMissionBoardSync.java`
- Modify `mods/coop/src/main/java/coop/session/CoopIronModeGuard.java`
- Create `mods/coop/src/main/java/coop/handshake/CoopChecksumProbe.java` *(as-built: the spike shipped as a gated probe instead of manifest wiring — see the checksum step)*
- Modify `mods/coop/src/main/java/coop/CoopModPlugin.java`
- Modify `mods/coop/docs/starsector-runtime-limitations.md`
- Modify matching tests: `CoopNetPumpTest.java`, `CoopNetServiceTest.java`, `CoopNpcFleetSuppressorTest.java`, `CoopFleetSnapshotTest.java` (codec coverage lives there), `CoopWorldDeltaTest.java`, `CoopMissionBoardSyncTest.java`, `CoopIronModeGuardTest.java`

**Steps:**

- [x] **Dispatch guard** (`CoopNetPump`): wrap the body of `drainInbound`'s while-loop switch in the log-and-drop catch; add the connection-state guard to `handleHandshakeManifest`; hoist `handleLobbyReject`'s `reason` parse into a single local; change `handshakeDiffFor`'s catch to `ex.toString()`. Tests: a message with a missing payload field, an unknown `PAUSE_INTENT` source, and a `HANDSHAKE_MANIFEST` in `HOST_WAITING` each get logged + dropped while subsequent queued messages still process.
- [x] **Queue clear** (`CoopNetService.shutdownLocked`): clear TCP `inbound`/`outbound`. Test: enqueue both directions, shutdown, restart host → `pollInbound()` empty, nothing stale flushed.
- [x] **Orphan sweep** (`CoopMirrorOrphanSweeper` + `CoopModPlugin.onGameLoad`): per the policy decision; unit test with fake locations asserting tagged fleets removed, untagged + player fleets preserved, count logged.
- [x] **Suppressor retry** (`CoopNpcFleetSuppressor.tick`): flag set moves inside the try. Test: first `suppressSpawners` throws → next tick retries and succeeds.
- [x] **Ledger** (`CoopWorldDelta.Ledger.apply`): kind-prefixed tracking for non-consuming deltas. Tests: second apply of the same CONSTRUCT returns `false`; CONSTRUCT-then-CONSUME on the same entity both return `true` first time; existing consumed-path tests stay green.
- [x] **State-leak clears** (`CoopCampaignReplicator.dispose`, `CoopMissionBoardSync.applySnapshot`, `onPlayerActivatedAbility` guard): per policy. Tests: dispose-then-retrack emits no delta for an entity consumed pre-dispose (watcher state cleared ⇒ it is never "lost" again); a claim for a mission absent from a fresh snapshot is purged; ability capture is a no-op while replaying.
- [x] **UDP pinning + truncation** (`CoopNetService`): per policy. Tests: datagram from a non-pinned address is dropped and does not update the return address; capacity-filled receive is discarded with a warn.
- [x] **Silent market-delta drop + lying log** (`CoopCampaignReplicator`) *(found in-game 2026-08-09; same silent-failure family as the dispatch guard)*: `applyItemDeltaToEngine` (`:797`) returns silently when `openMarketCargo` yields `null` — `getCargoNullOk()` returns `null` whenever the submarket's cargo has not been materialized, which is the normal case when the host is not docked at that market. Meanwhile `hostApplyMarketTxn` (`:708`) logs `Coop applied MARKET_TXN` unconditionally, so the log asserts success on a no-op. **Reproduced twice:** guest buys 50 fuel at Sindria, host opens the market, stock is untouched, and the host log still reads "applied". Fix: make the null-cargo path `warn` with market/kind/item, and move the "applied" log so it only fires when the engine mutation actually ran. If the delta must land while the host is undocked, force materialization via `getCargo()` instead of `getCargoNullOk()` — **verify first** that this does not trigger an off-screen `updateCargoPrePlayerInteraction` restock as a side effect. Test: a `MARKET_TXN` for a market with no materialized cargo logs the warn and does not log "applied".
- [x] **Codec** (`CoopFleetCodec`): escape/unescape `U+001F`; round-trip test with the separator embedded in a ship name.
- [x] **Keepalive** (`CoopNetPump.maybeSendPing`): remove the seed-lock suppression; test that pings flow in the `handshakeValidated && seedLong == null` window.
- [x] **Mirror AI-protection flag** (`CoopFleetMirror`): set `MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS` at both creation sites; unit test asserts the flag on freshly created mirrors.
- [x] **Iron-mode depth cap** (`CoopIronModeGuard`): depth-2 limit; test that a depth-3 third-party `isIronMode:true` no longer rejects while a top-level one still does.
- [x] **Checksum spike** (time-boxed): attempt the `SettingsAPI` text-load of `mod_info.json`; wire `sha256Text` if it works, otherwise document. Update `docs/starsector-runtime-limitations.md` either way. *(**Outcome 2026-08-09: not settled, deliberately not wired.** It cannot be settled outside a running game — the guard lives in the mod classloader, so the call compiles and unit-tests clean either way. There is also a specific hazard: `loadText` declares a checked `IOException`, so handling it makes the verifier resolve a `java.io` type in the calling class, the exact pattern already documented as blocked. Shipped instead as `coop.handshake.CoopChecksumProbe`, a dormant `CoopDebug`-gated probe called from `onGameLoad` that logs `SUCCESS`/`BLOCKED`; the manifest keeps its placeholders and depends on nothing. **Read the verdict off the drill-session log** (diagnostics on) and either wire the real hash or delete the probe. `CoopHandshakeSandboxCompatibilityTest` pins the arrangement.)* ***(Verdict 2026-08-17: SUCCESS on both clients** — "Coop checksum probe: SUCCESS, mod_info.json hashed to a35cede3… (275 chars)", identical hash host and guest. The sandbox does NOT block `SettingsAPI.loadText`. Real `mod_info.json` checksums are viable; the wiring is slotted into Phase 6b (next in the build order) alongside its other handshake hardening, after which the probe is deleted.)*
- [x] Run `powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"` → `BUILD SUCCESSFUL`. *(2026-08-09: green, 240 tests, up from 219.)*
- [x] Deploy with `scripts\deploy-to-test-clients.ps1` and relaunch both games.
- [x] **Two-instance smoke test:** *(**Run 2026-08-17.** Drills 1, 3, 4 passed as specced; drill 2 FAILED and was fixed live — see the follow-up step below; drill 5 verified by unit test, see note.)*
  1. **Garbage-resilience drill:** with the host waiting for a guest, send a junk line to its TCP port from a third terminal (`powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient('127.0.0.1',7777); $s=$c.GetStream(); $b=[Text.Encoding]::UTF8.GetBytes('{\"garbage\":1}'+\"`n\"); $s.Write($b,0,$b.Length); $c.Close()"`) → host logs a dropped-message warning, keeps running, and a real guest still connects and reaches seed lock afterwards. *(**Pass**, and stronger than specced: the junk arrived while a guest was already connected, so the connection-level defense fired first — "Coop TCP rejected extra connection with lobby reject" — and the payload never reached the message parser. The session was unaffected.)*
  2. **Reconnect hygiene:** connect, play a minute, quit the guest, reconnect the guest (fresh launch) → session re-establishes cleanly; no stale-message log lines, no spurious `WORLD_DELTA(CONSUME)` re-reports in the system where salvage happened pre-disconnect (run with `-Dcoop.debug.diagnostics=true` to see the delta log). *(**Failed first run, fixed, re-verified pass** — see the follow-up step below for the three stacked defects.)*
  3. **Orphan sweep:** guest saves mid-session in a busy system, both instances quit; guest loads that save solo → log shows the sweep's removed-count line, and no frozen NPC mirrors or partner fleet are visible in the system. *(**Pass:** "Coop removed 35 orphaned mirror fleet(s) left by a previous session"; on screen, no host fleet and no frozen mirrors. The system is otherwise empty of NPC traffic because the suppressor removed the spawner scripts and that state is baked into the save — the documented guest-save-is-coop-only policy, re-accepted by the user during the drill.)*
  4. **UDP pinning:** while connected, send one UDP packet to the host's port from a third terminal → host logs the ignored-source warning once; guest-side fleet motion continues unaffected. *(**Pass:** "Coop UDP ignoring datagram from non-peer source /127.0.0.1:49273 (pinned peer 127.0.0.1)" — the port-lock caught a same-address loopback intruder, exactly the case address-only pinning could not.)*
  5. **Keepalive:** watch both logs during the connect/seed-lock window → PING/PONG lines continue through it. *(PING/PONG now log at DEBUG (suppressed at the default level), so this is not observable in a normal drill log; the seed-lock-window ping flow is pinned by `guestPingsFlowDuringTheSeedLockWindow` instead. Sessions connected promptly throughout the drill session, which is the behavior the keepalive protects.)*
- [x] **Reconnect fix (found by drill 2, 2026-08-17):** the rejoining guest was refused with "Lobby already has a guest" forever — three stacked defects: (1) `CoopSessionState` had no disconnect transition, so the guest slot stayed occupied after the channel died; (2) `CoopNetPump` never watched the connected→disconnected edge, so the stale session kept `isGameplaySessionActive()` true and the host resumed streaming snapshots down the next socket to attach, handshake or not; (3) the inbound dispatch had no session gate, so the lobby-rejected guest still applied the host's `ORBIT_SNAPSHOT`/`PLAYER_REP_SNAPSHOT` stream to what was effectively a solo campaign (and a pre-session `WORLD_DELTA` could poison the consume ledger). Fixed: `CoopSessionState.onChannelDisconnected()` frees the peer slot and rewinds to the role's pre-lobby state (local identity survives; also recovers from REJECTED so a corrected peer can retry); the pump detects the edge, resets its send-once flags, and drops pre-session campaign traffic in the dispatch default case. Deliberate side effect: the connect-time pause hold re-engages on disconnect, so **the host world freezes while the guest is gone** — which also stops the rejoined guest from starting days behind. Pinned by three pump tests + five session-state tests; re-verified in-game (seed lock re-accepted, fleet counts tracking 35→34 in lockstep on both clients).
- [x] Commit with `git add mods/coop && git commit -m "fix: harden coop networking and lifecycle per QA pass"`. *(Commit was deferred at build time — resolved by the 2026-06-10 catch-up commit; the repo is live now.)*

**Acceptance:**

- A malformed, out-of-order, or unknown-content TCP message is logged and dropped without escaping `advance()`, and later messages in the same drain still process; sessions never tear down over a bad message.
- A session restarted in the same game process sees no stale TCP messages from the previous one.
- Loading any save outside an active session removes all `$coopMirrorFleet`/`$coopNpcFleetId` fleets (logged count); live sessions are unaffected.
- Spawner suppression retries after a failed attempt; the per-frame sweep is a safety net again, not the primary mechanism.
- `CoopWorldDelta.Ledger` is idempotent for every kind, including the host echo rebroadcast of CONSTRUCT/PARLEY (unit-tested), and reconnect produces no spurious CONSUME deltas; stale mission claims are purged on pool refresh.
- The host ignores UDP datagrams from non-peer addresses (single warn) and discards truncated datagrams; `U+001F` in player-editable names round-trips the codec.
- Pings flow through the seed-lock window; iron-mode detection no longer scans third-party data beyond depth 2.
- Every mirror fleet carries `$cfai_ignoredByOtherFleets` from creation, so host NPC AI can neither target nor silently autoresolve against the guest's mirror.
- The checksum spike outcome (wired for `mod_info.json` or documented as sandbox-blocked) is reflected in `docs/starsector-runtime-limitations.md` and consistent with the Phase 5 acceptance correction.
- No new message types, no `java.lang.reflect`/`java.io.*`/`java.nio.file.*` in runtime code, full `gradlew clean test build` green.

## Phase 12c: Close the Phase 12 Extension Points (Bar/Mission Pool, Full Market Capture, Ability Effects)

> **Why this phase exists (decided 2026-06-10):** three Phase 12 steps are checked off with an "in-game extension point" caveat — the snapshot/claim/arbitration infrastructure is built and unit-tested, but the concrete engine wiring is not, so those features do not actually function in-game yet. This phase makes that hidden work explicit so it cannot be silently forgotten. **It must land before the Phase 19 QA pass** (whose bar-pool and market-contents lines would otherwise fail); see the Implementation Order note for its slot. No new protocol messages are expected — this is capture/apply wiring onto the existing `MISSION_POOL_SNAPSHOT` / `MARKET_SNAPSHOT` / `ABILITY_ACTIVATE` channels.

**The three gaps (from the Phase 12 implementation notes):**

1. **Bar/mission-board enumeration is not wired.** `broadcastMissionPool`/`applyMissionPool`/`visibleEntriesFor` and the first-come claim gate exist, but nothing enumerates a market's concrete offers (`BarEventManager` events, mission-board/contact/bounty intel) into the pool — so guests do not actually see the host's offers today. This is the committed "own mirroring phase" foreshadowed by the bar-seed experiment (memory `bar-mission-seed-sync`: seeding can never equalize offers; the pool must be host-authored).
2. **Market capture is lossy.** *(Rewritten 2026-08-09 from the in-game session — the original text claimed weapons and fighters were uncaptured; they are captured and they match. The real gaps are narrower and different.)* Four sub-gaps, in descending gameplay impact:
   - **2a. Ship variant fidelity is lost.** `shipVariantId` (`CoopCampaignReplicator.java:1294`) encodes only `getVariant().getHullVariantId()`. D-mods live on a *generated* variant copy, so they are dropped in capture, and the guest rebuilds a pristine stock hull via `addMothballedShip(FleetMemberType.SHIP, variantId, null)` (`:855`) at 0% CR. Observed at Sindria: the host listed `Hammerhead (D)` at 48,966 where the guest listed a clean `Hammerhead` at 62,399. Silent — no warning fires, because every stock variant resolves cleanly. Fix must carry the D-mod/hullmod list and CR, not just the variant id.
   - **2b. Same-hull ships collapse into a quantity.** `byVariant.merge(variantId, 1, Integer::sum)` (`:1264`) folds distinct listings sharing a hull into one counted line, so three differently-D-modded Buffalos replicate as three identical ones. A per-listing encoding is required; a count cannot express it.
   - **2c. Special stacks are neither captured nor stripped.** `classify` (`:1278`) returns `null` for `isSpecialStack()` — modspecs, blueprints, AI cores. They are absent from the snapshot *and* survive the apply-time strip, so each client keeps its own locally generated set. Observed as two mismatched modspec listings at Sindria while every commodity number matched. Needs a new `SPECIAL` `ItemKind` plus strip coverage.
   - **2d. Hireable officers/mercs are still uncaptured** (`OFFICER`/`MERC` exist in the model, nothing populates them) — the one part of the original gap-2 text that still holds.
   - Note: `StockItem.unitPrice` is hardcoded `0f` for ships (`:1267`). Once 2a/2b land, price follows from the correct variant, so fix those first and re-check before adding a price field.
   - **2e. Commodity stock is a regenerating stockpile, so per-transaction deltas cannot hold** *(found in-game 2026-08-09)*. Open-market commodity quantity is not stored state — `BaseSubmarketPlugin.addAndRemoveStockpiledResources` (api_src `:820`) refills each commodity toward `getStockpileLimit(com)` at `addRate = limit / 30f` per day, scaled by `sinceLastCargoUpdate`, which `OpenMarketPlugin.updateCargoPrePlayerInteraction` zeroes on every dock. **Host and guest dock on independent schedules**, so each client's restock clock advances differently and the host arrives at its next dock with a large accumulated timer that refills away any guest-side delta. Note the engine also restocks a 50-unit fuel purchase within roughly a game-day in *solo* play — so "the purchase came back" is partly vanilla and **durable deltas are the wrong goal**. The goal is that both clients read the same number at the same time. Decide between: (i) treat commodity `MARKET_TXN` as advisory and lean on snapshot-on-open (simplest, matches the `market-sync-open-snapshot-model` decision, accepts drift between snapshots); (ii) additionally re-snapshot to the guest whenever the host's own dock regenerates stock, so the guest never renders a stale number; or (iii) suppress the guest's local `updateCargoPrePlayerInteraction` entirely so only the host's restock clock exists. **Prefer (ii)** — (iii) fights the engine and (i) alone leaves the visible mismatch that surfaced here. Note `getStockpileLimit` seeds its jitter from `market.getId().hashCode() + submarket.getSpecId().hashCode() + clock.getMonth() * 170000` (`OpenMarketPlugin:134`), which is deterministic across clients while the month matches — the limit is not a divergence source; the *timer* is.
3. **Ability effects are routed but inert.** `ABILITY_ACTIVATE` reaches the host and is logged, but no per-ability world effect (interdiction pulse, distress call, sensor burst) is applied to authoritative state.
3b. **Guest objective captures never reach the host** *(found during Phase 13 implementation, 2026-08-17)*: Phase 13's `OBJECTIVE_OWNERSHIP` capture poll is host-only, but the guest can capture a comm relay / nav buoy / sensor array through its own interaction dialog (`Objectives.control` runs locally). That flip stays guest-local until the host's war sim happens to flip the same objective and the broadcast overrides it. Fix: run the same skeleton-mutation poll guest-side and report upward on the existing `WORLD_DELTA(OBJECTIVE_OWNERSHIP)` channel — the ledger's latest-wins payload dedup already absorbs the host's echo, so only the role gate in `CoopCampaignReplicator.tickSkeletonMutations()` needs widening (objectives only; gates and deciv stay host-only, the guest's producers of those are suppressed).
4. **MOVED OUT (2026-08-09) → see Phase 12d below.** The world-delta spawn hole and the over-narrow salvage-watcher tag filter were found in this session and originally filed here as gap 4. They were split into their own phase at order slot 2 because they are a scope fix (v1 has no player-to-player item transfer without them) and they carry no dependency on Phases 13–18. The diagnosis is retained here for context only; **do not implement it from this section.**
   - **4a. No spawn replication.** `CoopWorldDelta.Kind` is `{CONSUME, CONSTRUCT, PARLEY}` — there is no way to say "an entity came into existence". Player-created **cargo pods** (jettisoned, or placed in stable orbit) are runtime entities that never come into being on the other client, so the partner sees nothing. Reproduced both ways in-game.
   - **4b. The watcher's tag filter misses most consumable entities.** `tickWorldDeltas` (`CoopCampaignReplicator.java:973`) enumerates only `location.getEntitiesWithTag(Tags.SALVAGEABLE)`. Verified against `starsector-core/data/config/custom_entities.json`: `nav_buoy_makeshift` is tagged `["nav_buoy","neutrino_high","objective","makeshift"]` and `cargo_pods` is tagged `["has_interaction_dialog","neutrino","salvage_music"]` — **neither carries `salvageable`**, so disassembling a makeshift structure or emptying a pod emits no `WORLD_DELTA` at all. **Decision (2026-08-09): widen the watcher to track removal of interactable world entities generally rather than an explicit tag allowlist** — an allowlist fails silently for every entity type nobody thought to add, which is exactly how this was missed.
   - **4c. Runtime entity ids are not comparable across clients.** The watcher's comment asserts "Deterministic worldgen means the entity id matches across both clients." That holds for worldgen derelicts and is **false** for anything created at runtime. Spawn replication must therefore carry a coop-assigned id on the wire (the `coopFleetId` pattern from Phase 9), not rely on id equality.
   - **V1 consequence + decision (2026-08-09):** v1 excludes a direct trade UI by design (Guardrails, line 24) — cargo pods were the implicit fallback for moving fuel/supplies/ships between players, and that exclusion was made assuming pods worked. With 4a unfixed, **v1 would ship with no player-to-player item transfer of any kind.** Decision: **replicate cargo pods** (add a `SPAWN` kind carrying a coop-assigned entity id plus pod contents; the partner materializes the pod, and the existing `CONSUME` path plus `Ledger` dedup handles its removal). The direct-trade-UI exclusion stands unchanged — this restores the vanilla affordance rather than adding a new surface.
5. **Survey level does not replicate — at all (added 2026-08-24, verified read-only after the ability audit flagged `remote_survey`; user decision: fix in v1 before Phase 19, as build task D of this phase).** `SurveyLevel {NONE, SEEN, PRELIMINARY, FULL}` is a single per-market engine field (shared world state, not per-player), mutated by five live paths that all run locally on whichever client acts — the survey dialog panel (sets FULL via `Misc.setFullySurveyed` + PRELIMINARY on open), `remote_survey` (PRELIMINARY), system-entry SEEN marking, and salvaged survey-data caches (`SurveyDataSpecial`, which can target planets **up to its LY range away**, so per-system capture scoping is wrong). Only the dialog path has a listener, so capture = a sector-wide diff poll folded into the existing `tickSkeletonMutations` walk (~800 map puts per 5 s, cheaper than the tag scans already on that loop) — but the survey collector must run on BOTH sides (both players survey), so the poll's host-only guard gets restructured (objectives/gates/deciv collectors keep their existing role gates). Wire = new `CoopWorldDelta.Kind.SURVEY` (entityId = planet id — gen-time, matches across clients; payload = level name), added to `latestWins()`; apply = ordinal max-wins guard (level is verified monotonic) via `Misc.setFullySurveyed` for FULL (the enum-only setter leaves market conditions hidden), plain `setSurveyLevel` below FULL, `withNotification=false`. Replicate SEEN too (system-map display reads min system level; filtering it leaves the maps visibly different). Riders: `$ruinsExplored` is the same class of bug (rules.csv sets it locally on ruins exploration) — task D adds a parallel monotonic-boolean kind on the same walk; `remote_survey` moves to `LOCAL_ABILITIES` in the arbiter (once the outcome replicates, routing it to a host handler that applies nothing is misleading log noise). NOT replicated, by design: the ability's once-per-system memory flag (unreplicated = each player can remote-survey a system once — mild balance leak; replicating it would deny a paid cooldown), XP and the survey-data commodity (acting player keeps both, same rule as salvage). Accepted consequences to note for smoke: at FULL the non-acting player's dialog goes straight to colonize (they can never collect their own survey data from that planet), and a guest's survey mission auto-completes when the host surveys the target.

**Scope boundary (resolves a Phase 12 ↔ Phase 13 tension; reaffirmed by decision 2026-08-09 after the divergent-bar screens at Sindria — sync claimable content only, do not chase line-for-line identical bar screens):** the host-authored pool covers *shared, claimable* content — mission-board/contact/bounty entries and one-time special bar offers (anything two players could race to take). Per-player bar flavor (rumor chatter, drink events with no shared consequence) and local hassles stay per-player divergent, per Phase 13's accepted-divergences table. The line: **if claiming or consuming it would change what the other player can see or take, it is pool content; otherwise it stays local.**

> **API verification pass complete (2026-08-24, three parallel read-only agents over `api_src`; no blocked paths — nothing goes to `starsector-runtime-limitations.md` as infeasible, but several scope resolutions below are accepted divergences that belong in its accepted-divergences section).** Verdicts and the decisions they force:
>
> - **Bar events: FEASIBLE, and the pool is bar-events-only.** Storage is a *global* list (`PortsideBarData.getInstance().getEvents()`, public), market-scoped only at render time via `shouldShowAtMarket`. Stable id = `getBarEventId()` (spec id for `bar_events.csv` missions, class simple-name otherwise); every concrete offer's entire content regenerates from a single `long seed` field (+ marketId hash), so a pool entry = `(barEventId, seed, marketId, daysRemaining)` and the `PersonAPI` never rides the wire. Guest render = **inject + suppress** (option a+b; the custom "pool board" dialog (c) is rejected — more work, loses vanilla accept/reward wiring): construct via public `SpecBarEventCreator(specId).createBarEvent()` / `new HubMissionBarEventWrapper(specId)`, overwrite the `protected long seed` via MethodHandles (the one non-public touch; pattern already proven in `CoopBarSync`), register via public `PortsideBarData.addEvent`, and remove the guest's `BarEventManager` from the script list (`removeScript` — it must STAY in sector memory, `BaseMissionHub`/`BarCMD` call `getInstance()` through it; bonus: its 20–40-day `updateSeed` stops firing so the synced seed stops drifting). Constraints that must survive into the build: never put injected events into `barEventCreators` (the orphan sweep at `BarEventManager.advance` would delete them); the snapshot is an **ordered** list and the guest rebuilds `PortsideBarData.active` in that exact order (`BarCMD` shuffles with the synced seed — order changes the shown subset); skip intel-backed events (`PirateBaseRumorBarEvent`/`LuddicPathBaseBarEvent` hold live intel refs — the guest generates its own from Phase 13's replicated base intel); prefer a host-side change-watcher push over the request/response market-open round trip (the pool is global, and a fast bar click can beat the reply); accept per-player offer *scaling* (Delivery quantity/reward size off the local player fleet's capacity — same offer, different numbers; carrying rolled quantities would need more MethodHandles and is not v1).
> - **Mission-board/contacts/bounties: resolved LOCAL by the scope boundary + engine evidence.** `MissionBoardAPI` is dead in 0.98a (zero live references). Contacts are per-player by design (acquisition is player-driven; their RNG folds in a mutating global uniquifier). Contact-board mission lists rebuild per-open from the already-synced `BarEventManager.seed` but fold in a per-client open-time timestamp + accept state — divergent, per-player, and claiming one changes nothing for the partner → stays local. Person bounties have no seed field (`Math.random()` throughout) and the guest's `PersonBountyManager` is already suppressed, so guests simply have **no** person-bounty offers in v1 — accepted divergence, **user-confirmed 2026-08-24** (a future mirrored-intel item in the Phase 24 expedition-warning style could restore them; out of 12c scope). Per-player offer scaling likewise user-confirmed the same day.
> - **Market capture: FEASIBLE on all five gaps, public API end to end.** D-mods are perma-mods on a REFIT-source variant **plus a `_dhull` hull-spec swap** (`DModManager.setDHull`) — both must ride the wire; there is no `Misc.getDHullMods`, the D-mod test is the `HULLMOD_DMOD` tag. Per-listing encoding keys on `member.getId()` (fixes 2b), carries name/baseCR/vents/caps/permaMods/sMods/refit mods/suppressed/weapons/wings/hullSpecId in a nested delimited blob (the existing `CoopDelimited` escaping round-trips one nesting level), and apply follows vanilla's clone-then-`setVariant` pattern + `RepairTracker.setCR` (fixes the 0% CR). `ShipVariantAPI.toJSONObject()` is one-way — never build the wire on it. Multi-module ships are an accepted gap. Specials (2c): `SPECIAL` kind via `isSpecialStack()`/`getSpecialDataIfSpecial()`, add via `addSpecial`, remove via `removeItems(SPECIAL, data, n)` with value-equality `SpecialItemData` — `getData()` is nullable and null≠"" breaks equals; the shared `classify()` also powers cargo pods, whose `spawnKindOf` currently defaults specials to COMMODITY (a live 12d bug — a jettisoned AI core mangles; fix rides along). Officers/mercs/admins (2d): enumerate via comm directory entries with `$ome_hireable` (the `available` list is protected), exact bonus/salary ints from the host's `OfficerManagerEvent` script instance (`getOfficer`/`getAdmin` — the memory copies are display strings), guest strip-then-`addAvailable` so `$ome_eventRef` points at the guest's own script; **there is no hire event** — the guest detects its own hire by diffing the hireable set at `reportPlayerClosedMarket` (already wired) and sends a `MARKET_TXN` claim; host applies `removeAvailable`, no host-side credit deduction (credits are per-player). Admins are a second pool — handle or the guest keeps phantoms. Officer generation is already market+month seeded; the real divergence source is merc level (global `Misc.random`). Restock (2e, option ii): the engine's post-restock signal already reaches the mod — `reportPlayerOpenedMarketAndCargoUpdated` is currently collapsed into the same sink as plain market-open; split the sink and have the **host** branch rebroadcast the market snapshot there. Re-entering `updateCargoPrePlayerInteraction` from the broadcast is safe (zero-day second call + the engine's sub-unit guard). Adjacent risk to log: `OpenMarketPlugin.writeReplace` clears stale (>30-day) ship/weapon stock on save — a divergence source independent of this fix.
> - **Ability effects: scope collapses to `interdiction_pulse` + `distress_call`, both ACTIVATE-ON-MIRROR.** Abilities are entity scripts with no player gate (`BaseAbilityPlugin.activate`'s `isPlayerFleet()` check only guards the *notification*, so host-side activation can't echo into the mod's own ability listener — no replay guard needed); the mirror is created with zero abilities, so `addAbility` (idempotent) precedes `getAbility(...).activate()`, guarded on `isUsable()`. None of the mirror's protective flags neuter interdiction's victim loop (it iterates location fleets directly). **Sensor burst is ALREADY COVERED** — its world effect is exactly the `detectedRangeMod` total that Phase 14b's per-frame sensor sync already pins to the guest's real value (which includes the burst); activating it on the mirror would add a second source of truth that the sync then cancels. Reclassify it LOCAL in `CoopAbilityArbiter` and delete the dead `active_sensor_burst` entry (not a real id). Interdiction residuals: the `RepActions.INTERDICTED` standing hit is player-gated and silently lost — host applies it through the existing rep path in the same handler; pulse radius/duration read `sensorRangeMod`, which the sensor sync does **not** pin (strength is pinned) → small fidelity gap, documented not fixed in v1. Distress-call residuals: responder placement reads the *host's* player fleet (cosmetic-grade), the abuse-escalation counter lives on the mirror's plugin instance and resets with it (acceptable), and vanilla would serialize the mirror's plugin into the host save via the spawned route's spawner ref — the build must hand the route a host-owned spawner or accept the dead-mirror ref. Newly flagged, deliberately out of 12c scope: `remote_survey` genuinely mutates world state (planet survey level + system memory) and is uncovered today; `generate_slipsurge` is Phase 26 territory. Both recorded so the unknown→world-affecting default stops silently eating them. **Survey follow-up (user decision 2026-08-24): verify how survey state (ability AND regular dialog surveying) is stored and whether it replicates; if broken, it becomes a dedicated small item in the build order before Phase 19.**
>
> **Build split (decided 2026-08-24): four sequential build tasks — (A) ability effects + the 3b objective role-gate, (B) market capture 2a–2e, (C) bar pool capture/inject/suppress, (D) survey-level + ruins-explored replication (gap 5, added after the follow-up verification).** Sequential because all four touch `CoopCampaignReplicator`; each lands with its own tests and commit.

**Agent prompt:**

```text
Implement Phase 12c from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Close the three Phase 12 extension points: (1) enumerate concrete bar/mission-board/contact/bounty offers into the existing MISSION_POOL_SNAPSHOT host pool + first-come claim machinery; (2) extend captureMarketContents to weapon/fighter stock and hireable officers/mercs; (3) apply concrete host-side world effects for arbitrated abilities. API-verify each capture surface FIRST (the verification step below) before wiring anything; respect the scope boundary; no new message types unless a payload physically cannot ride the existing ones.
```

**Files:**

- Modify `mods/coop/src/main/java/coop/campaign/CoopMissionBoardSync.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopMarketSync.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopAbilityArbiter.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignReplicator.java`
- Modify matching tests (`CoopMissionBoardSyncTest`, `CoopMarketSyncTest`, `CoopAbilityArbiterTest`)

**Steps:**

- [x] **API verification first, per capture surface (before any wiring)** *(done 2026-08-24 — see the verification banner above; no blocked paths, several scope resolutions)*. Original rule kept for the record: document exact API paths in the code; if a path is blocked, stop and record it in `mods/coop/docs/starsector-runtime-limitations.md` with (a) the exact class/method tried, (b) what was observed, and (c) the v1 consequence.
- [x] **Build task A — ability effects + objective role-gate:** `CoopAbilityEffectApplier` activates `interdiction_pulse`/`distress_call` on the guest mirror (addAbility → isUsable → activate) from `hostHandleAbilityActivate`; host applies the lost `RepActions.INTERDICTED` rep hit through the existing rep path; `CoopAbilityArbiter` reclassifies `sensor_burst` LOCAL and drops the dead `active_sensor_burst` id. Plus gap 3b: widen the `tickSkeletonMutations` role gate so the guest polls objectives and reports `WORLD_DELTA(OBJECTIVE_OWNERSHIP)` upward (objectives only; gates/deciv stay host-only).
- [x] **Build task B — market capture (2a–2e):** per-listing ship encoding (member id, name, base CR, perma/S/refit/suppressed mods, `_dhull` swap, weapons, wings, vents/caps) with clone-then-setVariant apply; `SPECIAL` `ItemKind` captured + stripped + transacted, incl. the `spawnKindOf` COMMODITY-default fix for pods; officer/merc/admin capture via comm directory + `OfficerManagerEvent`, hire = close-time diff → `MARKET_TXN` claim; 2e host rebroadcast on the split `reportPlayerOpenedMarketAndCargoUpdated` sink. Weapons/fighters already work — do not "fix" them.
- [x] **Build task C — bar pool:** host change-watcher push of the ordered global bar-event pool over `MISSION_POOL_SNAPSHOT` (bar events only, seeds not content); guest injects via spec creators + MethodHandles seed overwrite + `PortsideBarData.addEvent` in snapshot order, suppresses its own `BarEventManager` tick (script-list removal only — the memory instance stays); claims stay first-come; intel-backed events skipped.
- [x] **Build task D — survey replication (gap 5):** `WORLD_DELTA(SURVEY)` + ruins-explored kind on the both-sides skeleton poll, ordinal max-wins apply via `Misc.setFullySurveyed`, `remote_survey` reclassified LOCAL.
- [x] Record the accepted divergences from the verification pass (contacts/contact-board/person-bounties local, per-player offer scaling, multi-module ships, merc-level roll, interdiction sensorRangeMod fidelity, 30-day writeReplace stock clear, remote-survey once-per-system flag per player) in `docs/starsector-runtime-limitations.md`.
- [x] Unit tests for each new capture/apply path (fake collaborators, as established); full `gradlew clean test build` green — per build task.
- [ ] Two-instance smoke test: both clients list identical bar offers at the same market and a claim race resolves first-come; both see identical ship listings (D-mods + CR), specials, and officer stock, and a guest hire updates the host pool; a guest interdiction pulse visibly affects host NPC fleets on both clients; a guest comm-relay capture propagates to the host; a survey by either player (dialog and remote) raises the other's survey level for that planet.
- [x] Commit per build task (`feat: apply ability world effects + guest objective capture`, `feat: full-fidelity market capture`, `feat: bar pool enumeration + guest injection`, `feat: replicate survey levels and ruins exploration`).

**Acceptance:**

- Guests see the host's actual bar/mission-board/contact/bounty offers (not locally generated ones) for shared-claimable content; one-time offers are first-come.
- Market snapshots cover commodities, ships, weapons, fighters, officers, and mercs; transactions of every kind apply to the host's canonical market.
- Arbitrated abilities produce real host-side world effects that replicate to the guest.
- Any blocked API path is documented with exact evidence instead of worked around blindly.

## Phase 12d: World-Entity Spawn Replication (Cargo Pods + Wider Consume Watcher)

> **Why this phase exists (split out of 12c gap 4 on 2026-08-09, order slot 2):** v1 excludes a direct trade UI by design (Guardrails). That exclusion was made assuming cargo pods worked as the vanilla fallback for handing a partner fuel, supplies, or a ship. In-game testing on 2026-08-09 showed they do not replicate at all, so **v1 currently has no player-to-player item transfer of any kind**. This is a scope fix, not a polish item, and it runs early because every private soak session before it lands is unrepresentative of real co-op play. It depends on nothing beyond the existing `WORLD_DELTA` channel and the Phase 12 salvage watcher, both shipped and verified.

**The three stacked defects (all reproduced in-game 2026-08-09):**

1. **No spawn replication.** `CoopWorldDelta.Kind` is `{CONSUME, CONSTRUCT, PARLEY}` — there is no way to express "an entity came into existence". A cargo pod created by either player (jettisoned, or placed in stable orbit) is a runtime entity that never comes into being on the other client. Reproduced both directions.
2. **The consume watcher's tag filter is too narrow.** `tickWorldDeltas` (`CoopCampaignReplicator.java:973`) enumerates only `location.getEntitiesWithTag(Tags.SALVAGEABLE)`. Verified against `starsector-core/data/config/custom_entities.json`: `nav_buoy_makeshift` is tagged `["nav_buoy","neutrino_high","objective","makeshift"]`; `cargo_pods` is tagged `["has_interaction_dialog","neutrino","salvage_music"]`. **Neither carries `salvageable`**, so disassembling a makeshift structure or emptying a pod emits nothing. Note a genuine derelict-ship salvage *does* work — the `CONSUME` path itself is sound and was verified in the same session; only its input filter is wrong.
3. **Runtime entity ids are not comparable across clients.** The watcher's comment asserts "Deterministic worldgen means the entity id matches across both clients." That holds for worldgen derelicts and is false for anything created at runtime.

**Decisions already made (2026-08-09 — do not re-litigate):** replicate cargo pods rather than build a transfer UI (the direct-trade-UI exclusion stands unchanged; this restores a vanilla affordance instead of adding a surface). Widen the watcher to interactable world entities generally rather than extending the tag allowlist — an allowlist fails silently for every entity type nobody thought to add, which is exactly how this was missed.

**Agent prompt:**

```text
Implement Phase 12d from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Add a SPAWN kind to the WORLD_DELTA channel carrying a coop-assigned entity id so player-created cargo pods replicate to the other client with their contents, and widen the Phase 12 consume watcher beyond the Tags.SALVAGEABLE allowlist. API-verify the pod-creation and entity-tagging surfaces FIRST. Do not add a direct trade UI. Do not rely on engine entity ids matching across clients.
```

**Files:**

- Modify `mods/coop/src/main/java/coop/campaign/CoopWorldDelta.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignReplicator.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`
- Modify `mods/coop/src/test/java/coop/campaign/CoopWorldDeltaTest.java`

**Steps:**

- [x] **API verification first** *(done 2026-08-09, all paths confirmed in `api_src`)*: `Misc.addCargoPods` (`Misc.java:3245`) calls `addCustomEntity(null, null, Entities.CARGO_PODS, Factions.NEUTRAL)` — **engine-minted id, so ids never match across clients**, confirming gap 4c — and sets velocity from `Math.random()`, so position *and* velocity must ride the wire rather than be recomputed. Capture surface is better than a per-frame appearance watcher: `CoreScript.reportPlayerDumpedCargo` (`:818`) and `reportPlayerDidNotTakeCargo` (`:837`) both call `ListenerUtil.reportPlayerLeftCargoPods(pods)`, which dispatches to `CargoScreenListener` via `getListenerManager().getListeners(...)` — note that is the **listener manager**, not the campaign-event list, so it needs its own `addListener(listener, true)` registration alongside `addTransientListener`. Pod decay is `CargoPodsResponse`, added by the creating client only; the mirror copy deliberately gets no decay script, so decay stays single-authority and arrives as a normal `CONSUME`. Original step text: how a player-created cargo pod is constructed and what carries its contents; how to set a custom memory tag on a created entity; whether pod decay/expiry is driven by a script that must also be replicated or suppressed on the mirror side. Document exact API paths in code; if a path is blocked, record it in `docs/starsector-runtime-limitations.md` with the class/method tried, what was observed, and the v1 consequence — do not patch around it.
- [x] **`SPAWN` kind:** add to `CoopWorldDelta.Kind`, carrying coop-assigned entity id, entity type, location id, position, **velocity**, and cargo contents. *(Velocity was not in the original step and is required: `Misc.addCargoPods` sets it from `Math.random()`. Contents are keyed `KIND:id` over COMMODITY/WEAPON/FIGHTER/SHIP — a commodities-only payload silently dropped the weapons, fighters, and ships players most want to hand over. **Known limitation:** SHIP entries carry only the variant id, so hull mods, D-mods, and CR do not survive a handover; same root cause as 12c gap 2a and it wants the same fix.)* Respect the flat-JSON no-arrays constraint — encode contents as a single delimited string via the `CoopDelimited` pattern, with the Phase 12b escaping.
- [x] **Coop-assigned identity:** tag every replicated entity with its coop id (the `$coopNpcFleetId` pattern from Phase 9) and key the watcher and `Ledger` on that id rather than the engine id, so `CONSUME` matches across clients for runtime-created entities.
- [x] **Widen the watcher:** replace the `Tags.SALVAGEABLE` enumeration in `tickWorldDeltas` with interactable world entities generally. Keep the existing new-location re-seed and hyperspace-clear behaviour intact (both are load-bearing and verified).
- [x] **Host rebroadcast + idempotency:** a `SPAWN` reported by the guest is integrated by the host and rebroadcast; re-applying a `SPAWN` is a no-op via the `Ledger` (which Phase 12b makes kind-prefixed).
- [x] Unit tests: spawn round-trip with contents; re-applied `SPAWN` is idempotent; a pod consumed on one client is gone on both and not double-reported; a makeshift-structure disassembly now emits a `CONSUME`; the widened watcher still ignores the local player fleet and Phase 8/9 mirror fleets. Full `gradlew clean test build` green.
- [x] Deploy with `scripts\deploy-to-test-clients.ps1` and relaunch both games.
- [x] **Two-instance smoke test:** (a) host jettisons cargo, guest sees the pod with the same contents and can loot it; (b) guest places cargo in stable orbit, host sees it and loots it, and it is consumed on both; (c) hand a ship across and confirm it arrives intact; (d) disassemble a makeshift nav buoy on one client and confirm it disappears on the other; (e) a derelict-ship salvage still works (Phase 12 regression). *(**Run 2026-08-17: pass.** (a)/(b) pods replicated both directions with matching contents, and the loot-back `CONSUME` applied once with the host echo deduped by the ledger; (d) nav buoy disassembly propagated — the exact case that failed pre-12d; (e) derelict salvage propagated (its on-screen orbit position differed between clients — that is the Phase 7c clock drift, not a 12d defect). (c) was not separately exercised in-game; ship stacks in the payload are covered by unit tests and the pristine-arrival limitation is documented above.)*
- [x] Commit with `git -C K:\Starsector\mods\coop add . && git -C K:\Starsector\mods\coop commit -m "feat: replicate player-created world entities and widen the consume watcher"`.

**Acceptance:**

- A cargo pod created by either player (jettison or stable-orbit placement) appears for the other with the same contents, and looting it consumes it on both clients — restoring player-to-player item transfer with no direct trade UI.
- Disassembling a makeshift comm relay / nav buoy / sensor array removes it on both clients.
- Replicated entities are matched by coop-assigned id, not engine id, so runtime-created entities reconcile correctly.
- The Phase 12 derelict-salvage path still passes unchanged.

**Deferred / out of scope:**

- Direct trade UI (v1 guardrail, unchanged).
- Replicating *every* runtime-created entity type. Pods and the makeshift structures are the ones with a demonstrated gameplay consequence; anything else surfaces through the host self-healing backstop until a real case appears.

## Phase 13: Dynamic Terrain Authority + Random Fork List

**Agent prompt:**

```text
Implement the remaining Phase 13 work from COOP_MP_IMPLEMENTATION_PLAN_V1.md (the fork/CoopRandom/forksJar infrastructure and deep-space determinism are DONE). Remaining scope, per the 2026-06-10 runtime world-content inventory below: (1) extend the guest sim suppressor to the verified list of runtime-random managers the suffix matcher misses today (base managers, EncounterManager, SensorGhostManager, DecivTracker, WarSimScript, PunitiveExpeditionManager, FactionHostilityManager); (2) add the three small WORLD_DELTA subtypes for host-side skeleton mutations (DECIV, OBJECTIVE_OWNERSHIP, GATE_ACTIVATED); (3) implement host-authoritative pirate/Luddic-Path bases per the corrected spec (suppression is mandatory; guest-side reconstruction follows the corrected constructor notes); (4) document the accepted divergences (storms, flares, ghosts suppressed, officer pools, abyss) in docs/starsector-runtime-limitations.md. There is NO generic TERRAIN_EVENT channel — that abstraction was cancelled 2026-06-10. Do not use Java-agent instrumentation or broad runtime RNG patching.
```

**Status note (2026-06-10):** the original Phase 13 fork workload is **DONE** — `CoopRandom`, the `Misc`/`GateHaulerLocation`/`NamelessRock`/`AbyssalRogueStellarObjectEPEC` forks, the `forksJar` gradle task, the vmparams classpath-prepend, and the full-fingerprint match (see the DONE subsection below). The generic `CoopTerrainAuthority`/`CoopTerrainEvent`/`TERRAIN_EVENT` machinery in the original file/step lists is **cancelled** — the 2026-06-10 bytecode/API inventory (below) found no content that needs a generic terrain channel; what remains is a finite list of suppressions, three WORLD_DELTA subtypes, and the base-authority work item. Treat the inventory section as the authoritative remaining scope; the original Files/Steps lists below are kept for the audit trail with their disposition marked.

**Files** *(dispositions 2026-06-10: fork/rng/build entries are DONE; terrain entries are CANCELLED; new remaining-scope files are the suppressor/world-delta/base files named in the steps)*:

- ~~Create `mods/coop/src/main/java/coop/campaign/CoopTerrainAuthority.java`~~ — cancelled
- ~~Create `mods/coop/src/main/java/coop/campaign/CoopTerrainEvent.java`~~ — cancelled
- Create `mods/coop/src/main/java/coop/rng/CoopRandom.java` — **DONE**
- Create `mods/coop/src/main/java/coop/rng/CoopRandomForkAudit.java` — **DONE**
- ~~Create `mods/coop/src/test/java/coop/campaign/CoopTerrainEventTest.java`~~ — cancelled
- Create `mods/coop/src/test/java/coop/rng/CoopRandomTest.java` — **DONE**
- Create `mods/coop/src/test/java/coop/rng/CoopRandomForkAuditTest.java` — **DONE**
- Create fork `mods/coop/forks/com/fs/starfarer/api/util/Misc.java` (compiled into `jars/coop-forks.jar`) — **DONE**
- Create fork `mods/coop/forks/com/fs/starfarer/api/impl/campaign/world/GateHaulerLocation.java` (one-time deep-space content — compiled into `jars/coop-forks.jar`) — **DONE**
- Create fork `mods/coop/forks/com/fs/starfarer/api/impl/campaign/world/NamelessRock.java` (one-time deep-space content — compiled into `jars/coop-forks.jar`) — **DONE**
- Create fork `mods/coop/forks/com/fs/starfarer/api/impl/campaign/enc/AbyssalRogueStellarObjectEPEC.java` (abyss-exploration content — compiled into `jars/coop-forks.jar`) — **DONE**
- **Guest-presence fork family** (added 2026-08-19, out of the Phase 14 spike; not RNG forks — these teach the engine's player-proximity spawners that the guest is a player). All six share one accessor shape and one pinned-version guard, which lives in `coop.presence.CoopPresenceRegistry` (`PINNED_VERSION` + `getForFork(String)`) so a version bump has exactly one constant to change. Every edit is additive and guarded on `presence != null`; none adds an instance field (all of these are `EveryFrameScript`s XStream-serialised into saves, so a new field would change the save shape), so with no session registered every path is exactly vanilla's. — **DONE**
  - `forks/com/fs/starfarer/api/impl/campaign/fleets/RouteManager.java` — route materialisation/despawn around the guest.
  - `forks/com/fs/starfarer/api/impl/campaign/fleets/PlayerVisibleFleetManager.java` — a fleet visible to the guest is not culled.
  - `forks/com/fs/starfarer/api/impl/campaign/fleets/DisposableFleetManager.java` — `currSpawnLoc` picked by distance to the *nearest* player, so ambient pirate/Pather traffic appears in a guest-only system.
  - `forks/com/fs/starfarer/api/impl/campaign/fleets/SourceBasedFleetManager.java` — Remnant/tutorial garrison count ramp and despawn gate take the nearest player.
  - `forks/com/fs/starfarer/api/impl/campaign/intel/events/DisposableHostileActivityFleetManager.java` — overrides the picker with its own copy of the loop; same treatment.
  - `forks/com/fs/starfarer/api/impl/combat/threat/DisposableThreatFleetManager.java` — same, for abyssal Threat fleets.
- Create `mods/coop/src/main/java/coop/campaign/CoopBaseAuthority.java` (host-authoritative pirate/Luddic-Path bases)
- Create `mods/coop/src/main/java/coop/campaign/CoopBaseRecord.java`
- Create `mods/coop/src/test/java/coop/campaign/CoopBaseAuthorityTest.java`
- Modify `mods/coop/build.gradle` to add a `forksJar` task producing `jars/coop-forks.jar` distinct from `jars/coop.jar`. — **DONE**
- Modify `mods/coop/scripts/launch-host.ps1` and `scripts/launch-guest.ps1` to **prepend** `..\mods\coop\jars\coop-forks.jar;` to vmparams `-classpath` ahead of `starfarer.api.jar` (SSMSUnlock-style classpath override). — **DONE**
- Modify `mods/coop/scripts/deploy-to-test-clients.ps1` to deploy `coop-forks.jar` alongside `coop.jar`. — **DONE**
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignReplicator.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Mechanism note:** Source-forking into `data/scripts/com/fs/starfarer/api/...` was attempted in a spike against `AccretionDiskGenPlugin` and proven not to work — `ScriptStore` parent-first delegation resolves API jar classes before our forks compile. Use the `coop-forks.jar` + vmparams classpath-prepend approach instead. See the Class Override Mechanisms table in the Design Alignment Notes section.

**Forks retired (researched 2026-05-30 — do not build):** the fleet/economy/combat RNG forks originally listed here are **superseded by replication and were never built**. Evidence: the verified 272-entry seed-lock fingerprint matched host↔guest using only `Misc` + `GateHaulerLocation` + `NamelessRock` (see `mods/coop/docs/phase11-rng-determinism.md`), the fingerprint contains no fleet/economy data, and matching *without* these forks proves they are not RNG-stream-entangled with fingerprinted content. Their only purpose was making the guest *independently recompute* identical content, which the replication phases remove:
> - `FleetFactoryV3`, `DefaultFleetInflater` (fleet construction/inflation) → **superseded by Phase 9** (guest constructs no NPC fleets; it mirrors host rosters).
> - `CoreScript` `prodRandom` (economy production) → **superseded by Phase 12** (economy/market deltas are host-authored and replicated).
> - `FleetEncounterContext` (battle context, design §7.5) → **superseded by Phase 14/15** (each battle runs on one machine; its RNG only needs self-consistency there).
> - `StarSystemGenerator` static `Random` (worldgen) → **unnecessary** for a different reason: vanilla `SectorProcGen.prepare` already seeds `StarSystemGenerator.random` from the shared `CharacterCreationData` seed, and the fingerprint matched without forking it.
>
> This is the design's own hierarchy (§7.3 "replicate, don't recompute" is primary; §7.5 forks are the fallback *only* where the host has no network hook — Phases 9/11 add those hooks). The remaining load-bearing forks are the worldgen/deep-space ones (`Misc`, `GateHaulerLocation`, `NamelessRock`) plus `AbyssalRogueStellarObjectEPEC` for later abyss parity.

**Gen-time-only principle (Phase 6 audit, 2026-06-10):** the shared seed — and any seeded static such as the forked `Misc.random` — guarantees parity only while both clients execute an *identical draw sequence*. That holds during single-threaded procgen and breaks the moment the campaign runs: the guest sim is suppressed, frame timing differs, call order diverges, so the two RNG streams desynchronize after the first runtime draw. Already proven empirically by the bar-offer experiment (offers diverged despite a synced seed). Consequence: **runtime randomness can never be fixed by seeding or forking an RNG — it must be replicated.** Every future fork proposal must state whether the draw site runs at gen-time or runtime; runtime sites go to a replication phase instead. The known gen-time escape that slipped past the seed (the fringe jump-point orbit, obfuscated engine code and thus unforkable) is correctly handled by reconciliation (the Phase 12 orbit snap), which is the fallback when neither seeding nor suppression is available.

### Phase 13 — Runtime world-content inventory (bytecode/API verification, 2026-06-10)

Full pass over `CoreLifecyclePluginImpl.addScriptsIfNeeded()` (API source, lines ~592–801) plus the terrain/encounter plugins, classifying every runtime-random or runtime-mutating manager. Decision rubric (user-set): stability > features; replicate as much as possible, but really hard things get deferred or cancelled.

**Two live gaps found in implemented phases (the suffix matcher misses them — guest runs these sims TODAY):**

1. `CoopNpcFleetSuppressor.isSpawnerScriptName` matches only `*FleetManager`/`*RouteManager`/`*BountyManager` (verified at lines 153–155). `PirateBaseManager`, `PlayerRelatedPirateBaseManager`, `LuddicPathBaseManager` match none of them → **the guest is currently spawning its own pirate/Pather bases** (each with a hidden market, station entity, and `PirateActivityIntel`).
2. `EncounterManager` is also unmatched → the guest's `EncounterManager` (unseeded `new Random()`, `EncounterManager.java:66`) spawns its own pirate/merc/scavenger fleets at slipstream encounter points and generates its own abyssal temporary star systems — **an NPC-fleet-authority hole that bypasses the Phase 9 suppressor entirely.**

**Suppression-list extension (guest side — add to the suppressor's explicit class set, keep the suffix rules):**

| Manager | Why (RNG cite) | What the guest loses | Verdict |
|---|---|---|---|
| `PirateBaseManager`, `PlayerRelatedPirateBaseManager`, `LuddicPathBaseManager` | unseeded `new Random()` (`PirateBaseManager.java:66`, `PlayerRelatedPirateBaseManager.java:50`, `LuddicPathBaseManager.java:253`) | own (wrong) base sim | **SUPPRESS** + host authority (subsection below) |
| `EncounterManager` | unseeded `new Random()` (`EncounterManager.java:66`) | slipstream encounter fleets; guest-generated abyssal temp systems | **SUPPRESS** (closes the NPC-authority hole). Abyss limitation documented below |
| `SensorGhostManager` | `new Random(Misc.genRandomSeed())` (`SensorGhostManager.java:79`) | hyperspace sensor ghosts | **SUPPRESS** — several ghost types spawn real encounters/fleets (EncounterTrickster, ShipGhost) or touch story state (Ziggurat/guide ghosts); cosmetic loss only. Null the cached sector-memory handle after removal |
| `DecivTracker` | `new Random()` (`DecivTracker.java:52`, roll at :179) | independent (= divergent) deciv rolls | **SUPPRESS** + replicate host decivs via `WORLD_DELTA(DECIV)` — market-existence mutation is exactly what the Phase 6b fingerprint mutability contract requires us to replicate, or reconnect hard-rejects |
| `WarSimScript` | `Math.random()` (`WarSimScript.java:93` etc.) | local war-sim objective flips | **SUPPRESS** + replicate host objective ownership via `WORLD_DELTA(OBJECTIVE_OWNERSHIP)` (objectives are gen-time entities → ids match) |
| `PunitiveExpeditionManager` | route seeds via `Misc.genRandomSeed()` | guest-local punitive expeditions | **SUPPRESS** — expedition fleets arrive via the normal Phase 9 NPC replication |
| `FactionHostilityManager` | `new Random()` (line ~128) | guest-local war/peace rolls | **SUPPRESS** — host war/peace changes already flow through the Phase 12 faction-relations sync; residual divergence is only the hostility *intel* items (cosmetic, accepted) |
| `PersonalFleet*` story-fleet scripts, SDF spawners (`SDFHegemony` etc.) | route seeds, time-derived | named story/SDF fleets | **SUPPRESS** — but note these class names do **not** end in the matched suffixes; verify at implementation time which of them the Phase 9 smoke test actually caught (diagnostic step below) |

**Accepted divergences (document in `docs/starsector-runtime-limitations.md`, no code):**

- **Hyperspace storm cells** (`HyperspaceTerrainPlugin` + `HyperspaceAutomaton`): cell evolution is a deterministic automaton, but generation reseeds use `new Random()` (`HyperspaceAutomaton.java:150`) and strike timing/damage use `Math.random()` (`HyperspaceTerrainPlugin.java:1472,1485` etc.). Not suppressible (terrain plugin, not a script). **ACCEPT:** storm strikes only hit the fleet that is in the cell, own fleets are owner-authoritative, and NPC mirrors are position-forced echoes — no shared state is touched. Each player sees their own weather. (This answers the plan's old "classify HyperspaceTerrainPlugin" checkbox.)
- **Star-corona / pulsar flares** (`FlareManager`, `new Random()` at line ~307): same ownership argument. ACCEPT.
- **Officer pools at markets** (`OfficerManagerEvent`, `Math.random()`): each player hires from their own pool. ACCEPT for v1 (shared pool would need a market-officer sync — Phase 12 already covers the snapshot-on-open contents where wired).
- **Bar events / smuggling scans / patrol hassles of the *local* player** — per-player by design. ACCEPT (bar divergence already proven unfixable by seeding — memory `bar-mission-seed-sync`).
- **Abyss co-op parity is partial in v1.** The `AbyssalRogueStellarObjectEPEC` fork makes generated systems deterministic *per encounter-point id*, but EP **placement** comes from each client's unseeded `HyperspaceAbyssPluginImpl.random` (line ~59), so the EP *sets* differ; and with the guest's `EncounterManager` suppressed, abyssal temporary systems exist host-side only. The guest can travel the abyss but deep abyssal content (rogue stellar objects, lights, Threat encounters) is host-experienced only. Post-v1 fix is now numbered as **Phase 26 milestone 2** (2026-06-10): replicate each encounter *outcome* (EPs themselves are transient per-player probe points — corrected model in Phase 26) and let the forked EPEC regenerate identical content guest-side — the fork was built so this stays cheap.
- **Slipstreams**: already decided (see the slipstream subsection below) — ACCEPT for v1, post-v1 = suppress + replicate stream set.

**New WORLD_DELTA subtypes (host → guest, reuse the existing Phase 12 WORLD_DELTA channel — the cancelled TERRAIN_EVENT message is NOT needed):**

- `DECIV(marketId)` — host market decivilized; guest applies the same condition change/market removal. Rare (months-to-years cadence), tiny payload, and required by the Phase 6b mutability contract.
- `OBJECTIVE_OWNERSHIP(objectiveEntityId, factionId)` — comm relay / nav buoy / sensor array ownership flips from the host's war sim. Gen-time entity ids match across clients.
- `GATE_ACTIVATED(gateEntityId)` — story gate activation flag (gates are gen-time entities). Without it the guest sees inactive gates after the host activates them.

These are the **only** skeleton mutations the inventory found beyond what Phases 9/12 already replicate; orbital junk/asteroid regen on load is transient-cosmetic, and `Limbo`/`GateHaulerLocation`/`NamelessRock`/`TTBlackSite` generation is gen-time (verified `StarSystemGenerator.random` usage; `TTBlackSite`'s two `Math.random()` orbit params are cosmetic-only).

**Steps:**

*Done 2026-05-29 (kept for audit trail):* `CoopRandom.of(topic, keys)` (SHA-256, sign-bit-masked), fork audit headers, the `Misc` fork (line 241 static `random` + `genRandomSeed`), the `coop-forks.jar` gradle bundling, the vmparams classpath-prepend with probe-line verification, the `CoopRandomTest`/`CoopRandomForkAuditTest` suites, and the two-instance fingerprint match. The `StarSystemGenerator`/`FleetFactoryV3`/`DefaultFleetInflater`/`CoreScript` forks stay **retired** (see "Forks retired" note above).

*Cancelled 2026-06-10:* the generic `TERRAIN_EVENT` message (payload spec, capture points, guest overlay application, `eventId` idempotency tests) and the `CoopTerrainAuthority`/`CoopTerrainEvent` classes — superseded by the inventory section above (finite suppression list + three `WORLD_DELTA` subtypes on the existing channel, whose ledger already provides idempotency).

Remaining steps:

- [x] **Suppressor extension** *(2026-08-17: built. The 9 inventory classes + 6 story/SDF spawners found by the `addScriptsIfNeeded` sweep (`PersonalFleetHoracioCaden`, `PersonalFleetOxanaHyder`, `SDFHegemony`, `SDFLeague`, `SDFTriTachyon`, `SDFLuddicChurch`). Memory-handle policy corrected from the spec: only `$ghostManager` is cleared; the other cached handles (`$core_pirateBaseManager`, `$encounterManager`, `$core_warSimScript`, …) are KEPT and backfilled if absent because vanilla dereferences `getInstance()` without null checks — clearing `$core_pirateBaseManager` would NPE every interaction dialog. Deliberately kept: `SlipstreamManager` (v1 accept-divergence), `HostileActivityManager` (would pre-break Phase 24), `OfficerManagerEvent`, bar/mission/hassle per-player scripts.)* (extend `coop.fleet.CoopNpcFleetSuppressor` **in place** — do *not* create a sibling class; later phases (26) reference this exact name): add the explicit class set from the inventory table (`PirateBaseManager`, `PlayerRelatedPirateBaseManager`, `LuddicPathBaseManager`, `EncounterManager`, `SensorGhostManager`, `DecivTracker`, `WarSimScript`, `PunitiveExpeditionManager`, `FactionHostilityManager`) alongside the existing suffix rules. Suppression re-applies every session (vanilla `addScriptsIfNeeded` re-registers on every `onGameLoad` — verified `CoreLifecyclePluginImpl.java:519`); the existing retry pattern from 12b covers failures. Null `SensorGhostManager`'s cached sector-memory handle after removal. Unit tests: each named class matches; per-session re-suppression.
- [x] **Suppression-coverage diagnostic** *(2026-08-17: built; one compact log block, `SUPPRESSED`/`KEPT` per script with its list of origin.)* (CoopDebug-gated): at guest session start, log every sector script class name with its matched/suppressed status — this catches naming drift (`SDFHegemony`, `PersonalFleet*`, `PatrolFleetManagerV2` do not end in the matched suffixes; verify what Phase 9 actually caught and extend the explicit set accordingly).
- [x] **WORLD_DELTA subtypes** *(2026-08-17: built. DECIV captures via vanilla `ColonyDecivListener` (fires inside `DecivTracker.decivilize`, which is public static — the guest applies by calling the same routine); OBJECTIVE_OWNERSHIP + GATE_ACTIVATED via a 5 s host-side poll in `CoopSkeletonMutationWatcher` (the objective listener's only implementor is the suppressed `WarSimScript`; the gate latch `madeActive` is private+derived, so the poll reads/sets its two inputs `$gateScanned` + the sector globals — no MethodHandles). Ledger gained payload-keyed latest-wins semantics for the two re-flippable kinds so A→B→A applies all three legs while the host's echo stays inert. Guest-side objective capture is a known gap — filed as Phase 12c gap 3b.)*: add `DECIV`, `OBJECTIVE_OWNERSHIP`, `GATE_ACTIVATED` kinds to `CoopWorldDelta` (host capture: deciv via host-side market/econ listener or low-rate poll; objective ownership via low-rate poll of gen-time objective entities; gate activation via the gate's memory flag). Guest applies idempotently through the existing ledger. Unit tests per kind (apply, idempotent re-apply, unknown-entity tolerance).
- [ ] Pirate/Pather base authority — see the corrected subsection below (suppression is part of the suppressor-extension step; reconstruction is its own step list).
- [x] **Docs** *(2026-08-17: "Phase 13 — Accepted Runtime Divergences" section added, slipstreams and abyss included.)*: record the accepted divergences (storm cells, flares, officer pools, bar events, abyss partial parity, ghosts suppressed) in `docs/starsector-runtime-limitations.md`, each with its ownership argument (own-fleet effects are owner-authoritative; mirrors are position-forced echoes).
- [x] Two-instance smoke test: (a) guest log shows the extended suppression list applied and the coverage diagnostic clean; (b) guest spawns no bases/encounter fleets/ghosts over a multi-day fast-forward soak; (c) force a host objective flip (or verify via war-sim activity) and confirm the guest applies `OBJECTIVE_OWNERSHIP`; (d) fingerprint still matches on a fresh game (regression). *(2026-08-19: PASSED. (a) 31 scripts suppressed, coverage block clean — all 15 explicit + suffix classes caught, deliberate keeps confirmed; (b) one full in-game month at x1 (FF is coop-disabled until 7b), zero spontaneous guest base/encounter/ghost spawns; (c) five live war-sim/player flips applied including the A→B→A re-flip (`ancyra_relay` pirates→hegemony) and a correct unknown-entity skip for a runtime-built objective (`b5be` — minted id, guest lacks the entity by design); (d) fingerprint `93a2f8e2…` matched host↔guest. Found + fixed live (`57fe7c9`): session-start frame ordering destroyed then forgot fresh base mirrors — applySet now defers reconcile behind the suppressor pass, handleBaseSet takes the session edge eagerly, reset() preserves the guest set.)*
- [x] Commit with `git commit -m "feat: extend guest sim suppression and world-delta skeleton mutations"`. *(2026-08-17: `e886ef6`.)*

### Phase 13 — Deep-space content determinism (DONE 2026-05-29)

One-time deep-space worldgen (gate hauler, derelict rocks) ran off the shared, drifted `StarSystemGenerator.random` and diverged between clients. Forks `GateHaulerLocation` and `NamelessRock` swap in an independent topic-keyed `CoopRandom` stream for their `generate(...)` duration (restored in `finally`), making them deterministic; `AbyssalRogueStellarObjectEPEC` reseeds its `data.random` keyed by encounter-point identity for abyss-exploration parity (encounter-driven, so it does not affect the seed-lock fingerprint). With these plus `Misc`, the **full** seed-lock fingerprint matches host↔guest; the only fingerprint exclusion is hidden dynamic base markets (next item). See `mods/coop/docs/phase11-rng-determinism.md`.

### Phase 13 — Host-authoritative pirate/Luddic-Path bases

Pirate/Luddic-Path bases are dynamic, timer-placed campaign content. They are **not** reproducible from the seed (timer-driven, and their market ids come from the obfuscated engine `Sector.genUID()` so they differ per client by construction), so they are excluded from the seed-lock fingerprint via `CoopSectorFingerprint.includeMarket()` (drops `isHidden` markets) and must instead be host-owned and replicated.

**API verification (2026-06-10) — corrections to the original spec:**

- **Suppression is mandatory and currently missing** (see the inventory section: the suffix matcher misses all three managers — the guest spawns its own bases today). Suppression lands with the suppressor-extension step; additionally end any live guest-generated `PirateBaseIntel`/`LuddicPathBaseIntel` at session start.
- **`PlayerRelatedPirateBaseManager` has no `getActive()`** — it extends `EveryFrameScript` directly, not `BaseEventManager` (`PlayerRelatedPirateBaseManager.java:25`), and only creates bases in response to player colonies (none until Phase 24 lands). Suppress it guest-side; do **not** poll it. The host poll loop reads only `PirateBaseManager.getInstance().getActive()` and `LuddicPathBaseManager.getInstance().getActive()` (`BaseEventManager.java:189`, returns `List<EveryFrameScript>` — cast per element). **Phase 24 integration note (rescoped into v1 2026-06-10):** once player colonies exist, this manager goes live host-side and its bases would be missed by the manager poll — switch host capture to an intel-manager scan (`getIntelManager().getIntel(PirateBaseIntel.class)` + LP equivalent) so player-related bases join `BASE_SET`; guest suppression is unchanged.
- **The constructors are real but massively side-effectful** (`PirateBaseIntel.java:166`, `LuddicPathBaseIntel.java:119`): each one mints a fresh `genUID` market, **rolls its own orbit/placement from `Misc.random` inside the constructor** (via `BaseThemeGenerator.getLocations` + `WeightedRandomPicker`), registers the market with the economy (`getEconomy().addMarket(market, true)`), self-registers intel (`addIntel(this, true)`), creates a commander person, queues a `PirateBaseRumorBarEvent`, and `PirateBaseIntel` additionally runs `updateTarget()` → `new PirateActivityIntel(...)`. Consequences: the guest's base will sit at a **different orbit** with a different name/commander (accepted — cosmetic; the system, faction, and strength tier are what matter), and post-construction cleanup must end the auto-created `PirateActivityIntel` (it self-cleans once its source is ending, but the guest's copy targets a possibly-different system — end it immediately).
- **`LuddicPathBaseIntel` strength is `isLarge`, not tier** — `large` is rolled inside the constructor (`random.nextFloat() > 0.5f`, line ~218) and is not a parameter; it drives fleet count/quality and station type. Broadcast `isLarge` and set the `large` field post-construction via `MethodHandles` (`privateLookupIn` — the proven `CoopBarSync` pattern; no setter exists). `PirateBaseTier` enum confirmed: `TIER_1_1MODULE … TIER_5_3MODULE`. Tier does **not** set market size (always 3).
- **Cross-client base identity = `(kind, systemId)`** — vanilla holds at most one base per system per manager. Tier/isLarge are mutable attributes of that identity (pirate bases upgrade over time).
- **Ending is clean and confirmed:** `endImmediately()` → `notifyEnding()` removes the market from the economy, removes listeners, clears radio chatter (`PirateBaseIntel.java:687–697`); the rumor bar event and `PirateActivityIntel` self-remove once the intel is ending. Because the guest's managers are suppressed (nothing drains ended intel), also call `getIntelManager().removeIntel(intel)` explicitly.
- **No entity-id leakage:** base-spawned host fleets reach the guest through Phase 9 roster mirroring (memory carries only `$coopNpcFleetId`); no route/market/station id from the host's base needs to resolve guest-side (verified `DisposablePirateFleetManager.spawnFleetImpl` and the LP smuggler route path).
- **Fallback decision, pre-authorized:** SUPPRESS-ONLY is an acceptable v1 endpoint (verified brokenness = LOW: the guest sees mirrored pirate/Pather fleets near an invisible station). If the reconstruction smoke test below surfaces constructor side-effect problems, drop reconstruction, keep suppression, and document — do not fight the constructors. **Caveat (2026-06-10, Phase 24 rescope):** the LOW rating assumed no colony-crisis mechanics in v1; with Phase 24's shared colonies, `PirateBaseIntel`'s `PirateActivityIntel` (colony accessibility penalty) becomes gameplay-relevant — if SUPPRESS-ONLY was taken, revisit base reconstruction (or at minimum mirror the activity penalty) as part of Phase 24's milestone 2.

- [x] Guest session start: end + `removeIntel` any live guest-generated `PirateBaseIntel`/`LuddicPathBaseIntel` (suppression of the managers themselves lands with the suppressor-extension step above). *(2026-08-17: built into the suppressor's once-per-session path. Ordering enforced — `PirateActivityIntel` ends first because its `notifyEnding` reads the source base's markets, and it does NOT self-clean synchronously on a suppressed guest; orphaned activity intel also ended. Spec gap found and fixed: plain ending leaves the base's `MAKESHIFT_STATION` entity floating (vanilla only despawns it on destruction), so cleanup also removes `getEntity()` from its location.)*
- [x] Add `BASE_SET` TCP message. Payload is a single delimited string of base records (the envelope JSON parser is flat — no arrays): each record `kind|systemId|factionId|attr` where `kind` ∈ `PIRATE`,`PATHER`; `attr` = tier name for PIRATE, `large`/`small` for PATHER. *(2026-08-17: built; newline-joined records, fields through `CoopDelimited.field` so hostile ids can't break the framing.)*
- [x] Define `CoopBaseRecord(kind, systemId, factionId, attr)` with deterministic encode/decode and a stable set hash; do not depend on iteration order. *(2026-08-17: built; sorted-lines SHA-256 via `CoopChecksum`.)*
- [x] Host capture: poll the two pollable managers' `getActive()`, read `getSystem()` + `getTier()`/`isLarge()`, build the record set, broadcast `BASE_SET` on set-hash change. *(2026-08-17: built; 1 s poll, full rebroadcast on session (re)start. Capture distinguishes "unreadable" (null — no broadcast) from "genuinely empty", so a failed read can never order the guest to end every base.)*
- [x] Guest reconcile (idempotent, keyed by `(kind, systemId)`): construct missing bases via `new PirateBaseIntel(system, factionId, tier)` / `new LuddicPathBaseIntel(system, factionId)` — resolving `systemId` locally (sound: the static skeleton is deterministic, deep-space systems included thanks to the forks) — then post-construction: set `large` via MethodHandles for PATHER, end the auto-created `PirateActivityIntel`, accept the divergent orbit/name. End + `removeIntel` local bases absent from the host set. *(2026-08-17: built — full reconstruction; the SUPPRESS-ONLY fallback was NOT needed. Implementation findings: (a) the mirrored base's own `advanceImpl` still runs guest-side and monthly self-rolls tier changes and mints fresh `PirateActivityIntel`, so the guest re-runs the identical idempotent reconcile every 5 s and sweeps ALL `PirateActivityIntel` each pass (on a suppressed guest every one is base-derived) — message-arrival-only reconcile would drift; (b) removal must also despawn the `MAKESHIFT_STATION` entity (same gap the suppressor cleanup found); (c) tier is a genuine in-place update — `PirateBaseIntel.updateStationIfNeeded` self-corrects the station after a MethodHandles tier write — but `LuddicPathBaseIntel.updateStationIfNeeded` only runs from the constructor, so a flipped `large` fixes patrol strength but NOT the station module: accepted residual divergence; (d) faction change = destroy+recreate; failed constructions (no valid station slot in system) are remembered and not retried until the next `BASE_SET`; (e) system resolution is id-first over `getStarSystems()` — `SectorAPI.getStarSystem(String)` matches on NAME, not id; (f) residual: the mirrored base's monthly `startRaid()` fleets are culled by the Phase 9 sweep (log noise only) and its `setBounty()` intel stays guest-local/divergent; (g) orbits/names/commanders re-roll on every session start (teardown + rebuild) — accepted.)*
- [x] Add `CoopBaseAuthorityTest` for: encode/decode round-trip, order-independent set hash, reconcile add/remove/attr-change decisions keyed by `(kind, systemId)`, and post-construction cleanup invocations (against a seam interface — constructors don't run in unit tests). *(2026-08-17: 24 tests; suite total 342 green.)*
- [x] Run two-instance smoke test: host has (or dev-spawns) a pirate base and a Pather base → guest creates matching bases in the same systems (orbit divergence visible and accepted), guest log shows no independently-spawned base, host ends a base → guest's copy ends and its market leaves the economy. If constructor side effects misbehave here, invoke the pre-authorized fallback. *(2026-08-19: PASSED via log verification — vanilla's own `Added pirate base in [system], tier: X` lines made the intel-screen check unnecessary (base intel is hidden until discovered, so on-screen comparison was never viable anyway). Guest reconciled `added=6 removed=0 failed=0` matching the host's 6-base set system-for-system and tier-for-tier; MethodHandles `large` write confirmed by reconcile silence (a miss would log `updated=1` within 5 s); removal path (end + removeIntel + station despawn) exercised live by the session-start cleanup and the first session's `removed=3`. Constructors behaved — fallback NOT invoked. Host-ends-base-organically remains unobserved (months-scale timer) — the same code path as the verified removals, left to organic play.)*
- [x] Commit with `git commit -m "feat: make pirate/pather bases host-authoritative"`. *(2026-08-17: `aec7f21`.)*

### Phase 13 — Slipstream / hyperspace-weather divergence (Phase 6 audit finding, 2026-06-10)

**Verified facts** (API source `starfarer.api.zip!/com/fs/starfarer/api/impl/campaign/velfield/SlipstreamManager.java` — read it before changing anything):

- `protected Random random = new Random()` (~line 442) — unseeded wall-clock entropy, minted per client when the manager is constructed and serialized into each save. `random = Misc.random` happens only under `DebugFlags.SLIPSTREAM_DEBUG` (~line 460), so the existing `Misc` fork does **not** cover slipstreams.
- The monthly layout (config pick via `WeightedRandomPicker`, `addStream` placement, month-6/12 despawn timing) draws from that RNG inside the `interval.intervalElapsed()` branch of `advance()`. The interval is `IntervalUtil(1f, 2f)` with a random phase, so the **number and timing of draws differ per client even from identical RNG state**.
- Conclusion: slipstream networks diverge between host and guest **today**, and this is *runtime* randomness — per the gen-time-only principle above it is a replication target, not a fork target. A per-month-reseed fork (`CoopRandom.of("Slipstream", cycle, month)`) was considered and rejected: outcomes would still depend on per-client `addStream` call counts/days, and making them call-count-independent means restructuring gameplay logic, which the fork rules forbid.

**Decision (v1): accept and document.** Impact is bounded because fleets are owner-authoritative (Phase 8/9 mirroring): positions never desync — players just see different slipstream maps (one fleet appears to burn impossibly fast through empty hyperspace) and get different travel opportunities. Consistent with the v1 simplicity-first scoping.

- [x] Document slipstream divergence as a known v1 limitation in `mods/coop/docs/starsector-runtime-limitations.md`, including why it is NOT fixable via the fork list (runtime draw-timing dependence) and that the eventual fix is host-authoritative replication. *(2026-08-17: in the "Accepted Runtime Divergences" section.)*
- [x] Classify hyperspace storm cells the same way — **RESOLVED 2026-06-10** (see the inventory section above): divergent (unseeded `new Random()` generation reseeds + `Math.random()` strike timing/damage), not suppressible (terrain plugin, not a script), classified ACCEPT-DIVERGENCE on the ownership argument; goes into the same limitations note.

**Deferred fix (post-v1) — now numbered as Phase 26 milestone 1 (2026-06-10):** suppress the guest's `SlipstreamManager` via the same `removeScript`/`addScript`-at-`onGameLoad` mechanism used for the base managers above, and replicate the host's streams. The original "rebuild through `SlipstreamBuilder` from placement params" sketch was corrected during Phase 26 verification: the builder itself consumes RNG, so the replication payload is the **finished segment polyline** (see Phase 26). Do not attempt RNG alignment.

**Acceptance:**

- The guest runs none of the inventory's suppression-list managers (bases, encounters, ghosts, deciv, war sim, punitive expeditions, faction hostility rolls); the coverage diagnostic confirms it, and re-suppression holds across save/load.
- Host-side skeleton mutations replicate through the three new `WORLD_DELTA` subtypes (`DECIV`, `OBJECTIVE_OWNERSHIP`, `GATE_ACTIVATED`) idempotently; no generic `TERRAIN_EVENT` channel exists.
- The load-bearing worldgen forks (`Misc`, `GateHaulerLocation`, `NamelessRock`) are bundled into `mods/coop/jars/coop-forks.jar` and visibly active in the runtime log via probe lines. The §7.5 fleet/economy/combat forks are retired (superseded by replication — see "Forks retired" note).
- Two-instance fresh-game test produces matching `sectorFingerprint` on host and guest. **(Achieved 2026-05-29: identical full fingerprint via `Misc` + deep-space forks; only hidden dynamic base markets excluded.)**
- Guest runs no independent pirate/Luddic-Path base managers; the bases the guest shows are exactly the host's set (matched by `(kind, systemId)`, divergent orbits accepted), applied idempotently — or, if the pre-authorized fallback was invoked, suppression-only with the limitation documented.
- The accepted divergences (slipstreams, storm cells, flares, officer pools, bar events, abyss partial parity, ghosts suppressed) are documented in `docs/starsector-runtime-limitations.md` with their ownership arguments; no fork is attempted for any runtime-RNG site.
- No v1 code uses Java-agent instrumentation or broad runtime RNG monkey-patching.

## Phase 14: Own-Fleet Combat + Spectator Bridge

> **Combat model (v1):** *solo own-fleet combat.* Either player can engage; the **engaging player pilots their own battle locally** and is authoritative for that battle's outcome. Combat-start auto-asserts a **shared pause** so the other player is held and follows the battle through a **live battle-status panel (2–5 Hz)** — they never pilot. Exactly one piloting player per battle. **Joint combat** (both players piloting in one battle) stays out of v1 — do not add it or CMC here (it is sketched as **Phase 22**, post-V1). See `COOP_MP_DESIGN.md` §1, §4.2, §4.5, §8.7. **(Revised 2026-06-10:** the original "live rendered spectator at 60 Hz" was verified infeasible — see the engine-facts block — and is cancelled, recorded in Non-Goals/Maybe. The disconnect-mid-combat freeze/countdown/forced-save-exit protocol is also cancelled in favor of finish-locally + host-authority reconciliation.)

**Engine facts (bytecode/API verification, 2026-06-10 — design rests on these):**

- **Programmatic battle open works, no dialog needed:** `Global.getSector().getCampaignUI().startBattle(BattleCreationContext)` is public API; the impl (`CampaignState.startBattle`, javap line ~7097) recrews both fleets, runs the battle-creation plugin, and does a full state transition with `stateToReturnTo = "Campaign State"`. `BattleCreationContext(playerFleet, FleetGoal.ATTACK, otherFleet, FleetGoal.ATTACK)` accepts any `CampaignFleetAPI` as the enemy — including a local mirror fleet. Nearby-fleet pull-in does NOT happen automatically on the direct call; terrain battle effects come from the local location's tokens (may differ from the host's view — cosmetic/balance drift, accepted). EASY.
- **The rendered "puppet battle" spectator is INFEASIBLE:** entering the combat state requires a real player fleet (engine asserts a flagship); projectiles/beams/fighters cannot be replicated by any API; puppet ships would run their own local AI/physics between forced position writes; CR-loss and campaign-consequence hooks fire on state exit against the spectator's own fleet. Every mitigation path leads through per-frame engine internals that the sandbox cannot touch. Cancelled — do not revisit without an engine-level change.
- **The viable spectator is a campaign-side status panel:** the spectator stays in the (paused) campaign and opens a custom `InteractionDialogPlugin` via `CampaignUIAPI.showInteractionDialog(plugin, target)`; its `advance(float)` re-renders a text/bars status view from the latest `BATTLE_STATUS` message. An interaction dialog also naturally blocks campaign input, which the Phase 10 interaction gate already understands. Fallback if the panel misbehaves: plain `CampaignUIAPI.addMessage` banner + the shared pause (always achievable). *(Post-V1 note, 2026-06-10: Phase 22 milestone 0 upgrades this panel to a drawn tactical map — keep the `BATTLE_STATUS` codec extensible so optional position/facing fields are additive.)*
- **No combat speed lock is needed:** vanilla 0.98a has **no player-facing combat speedup** — combat speed is the `combatSpeedMult` settings value read each frame, plus `CombatEngineAPI.getTimeMult()` (a `MutableStat`, confirmed in the decompile) for mods/systems. With exact-install parity (Phase 5) both clients run identical settings. `CoopCombatSpeedLock` is cancelled; `getTimeMult().modifyMult(...)` is the lever if this ever changes.
- **Save yes, load no:** `CampaignUIAPI.cmdSave()` and `.autosave()` are public and synchronous (javap ~7296/~6523; `autosave()` silently skips while any dialog is open — call it after the engagement dialog closes, before `startBattle`). There is **no programmatic load** of a chosen save (`cmdLoad()` just opens the picker; `LoadGameDialog` is fully obfuscated). `cmdExitWithoutSaving()` exists (javap ~7262) if a forced-exit flow is ever wanted.
- **NPC pursuit of the guest is preserved via pre-contact handoff (revised 2026-06-10 after pushback — the original permanent-ignore design killed pursuit).** The mirror is left targetable, so vanilla hostile AI detects, hunts, and chases it like any real fleet (the mirror has a real sensor profile). What must never happen is the engine *resolving* contact itself: NPC-vs-NPC contact runs silent autoresolve with no veto hook. Protection is therefore **contact-time, not permanent**, and the API for every piece is public: pursuit intent is readable (`CampaignFleetAIAPI.getCurrentAssignment()` → `FleetAssignmentDataAPI.getTarget()`/`getAssignment()`, plus `isHostileTo(fleet)` and `pickEncounterOption(context, otherFleet)` — the actual vanilla engage-or-not decision function, side-effect-free); battle membership is detectable (`CampaignFleetAPI.getBattle()`); and a fleet can be cleanly pulled out (`BattleAPI.leave(fleet, engagedInHostilities=false)`, BattleAPI.java line 49). The flow: watcher sees a hostile NPC closing on the mirror with intercept intent → hands the battle to the guest *just before* contact (`ENGAGE_GUEST`) → flags the mirror ignored only for the duration of the guest's local battle → clears on `BATTLE_RESULT`. A per-frame battle-eject is the backstop if contact ever wins the race (autoresolve rounds fire on a seconds-scale `IntervalTracker`, so a same-frame `leave()` should always beat the first damage round — spike must verify). Patrols additionally check `isPlayerFleet()` before hassling, so customs against the guest stays host-synthesized regardless. **This replaces 12b's permanent flag, which is interim-only.**
- **Vanilla applies battle consequences locally and self-contained** (`FleetEncounterContext.processEngagementResults` / `applyAfterBattleEffectsIfThereWasABattle`): the engaging client keeping its own XP/loot/rep application needs no mod interference. Phase 15 only reports the campaign-level deltas.

**Engine-facts amendment (2026-08-19, spike-verified in-game — see `PHASE14_SPIKE_NOTES.md` for the full verdicts):**

> **⚠️ Corrected 2026-08-19 (later the same day) by Phase 14b — read that section before trusting the "never retasks to hunt the mirror" clause below.** The spike watched **fleet assignments**, and vanilla pursuit is not an assignment: `TacticalModule` never adds, removes or mutates one; it stores the quarry in `this.target` and steers with `fleet.setMoveDestination(...)` (`nb/com/fs/starfarer/campaign/ai/TacticalModule.java:673-676`, `:1049-1058`). The signal the spike watched could not have shown a native chase whether or not one was happening. Decompile of the candidate loop (`TacticalModule.advance:262-488`) also shows that **nothing excludes the mirror**: `canBeEngaged()` and the `noEngaging` fader appear nowhere in `TacticalModule` or `StrategicModule` — they are read only by `BaseLocation`'s battle-initiation code — and the one flag that does exclude a target, `$cfai_ignoredByOtherFleets` (`StrategicModule.java:505`), this phase removed from mirror creation. So native pursuit of the mirror is plausible and Phase 14b tests it in-game. Everything below about **battle formation** (the `canBeEngaged()` gate, the pull-in bypass, the eject-and-recover path) is unaffected and still stands.

- **The engine never forms an NPC-vs-mirror battle, so the pre-contact-handoff race does not exist.** Hostile AI *sees* the mirror and *judges* it normally (`pickEncounterOption(null, mirror, true)` returns ENGAGE/HOLD/DISENGAGE tracking fleet strength — the pureCheck overload is the side-effect-free one), but (1) it never retasks to hunt the mirror (vanilla detect→chase is player-fleet machinery, not generic hostility; exact retasking gate unconfirmed and not load-bearing), and (2) no battle forms even when an ENGAGE-picking hostile crosses at 10 su, or when an `INTERCEPT→mirror` assignment is injected — the interceptor genuinely chases (proven; closed 389→17 su at 151 su/s), reaches the mirror, completes the assignment harmlessly, and reverts to patrol. Consequences: the watcher becomes the **initiator** — trigger = hostile + proximity + `pickEncounterOption == ENGAGE`, firing `ENGAGE_GUEST` at a chosen distance (~400–700 su; observed closing speeds 57–193 su/s, burn-17 patrols to 340 su/s; Phase 20 re-derive note unchanged); visible pursuit is synthesized by injecting `addAssignmentAtStart(INTERCEPT, mirror, ...)` (public API, proven).
- **Mechanism (decompile-confirmed same day, bytecode-verified — corrects the first "inert AI" guess):** NPC-vs-NPC battle initiation lives **only** in `BaseLocation.advance`'s pair loop, whose first gate is `CampaignFleet.canBeEngaged()` — false while the `noCombat` Fader is live — and `CoopFleetMirror.driveMovement` re-asserts `setNoEngaging(1f)` on every snapshot apply. The mirror is excluded before distance/hostility/interaction-target are even read. The mirror's AI is fully alive (`createEmptyFleet(String,String,boolean)` builds a real `ModularFleetAI`); `$cfai_ignoredByOtherFleets` only suppresses AI target *selection* and is never consulted by battle formation. Two hardening obligations follow (built as pre-Phase-14 hardening, 2026-08-19): (1) the shield is a ~1 s side effect of the movement path — a snapshot gap (network stall, unresolvable location, transition) makes the mirror engageable, so `setNoEngaging` must be re-asserted from an **unconditional per-frame hook**, not from inside `driveMovement`; (2) **the battle pull-in path bypasses the gate entirely:** `FleetInteractionDialogPluginImpl.pullInNearbyFleets` (runs whenever the host opens any vanilla fleet dialog, before any decision to fight) joins nearby fleets **without calling `canBeEngaged()`**, honors only `$cfai_ignoreOtherFleets` (`MemFlags.FLEET_IGNORES_OTHER_FLEETS` — a *different* flag from the one 12b set), and grants player-faction fleets (the mirror) a **700 su** join radius. Mirrors must therefore carry `FLEET_IGNORES_OTHER_FLEETS` (costs nothing — it only affects the mirror's own target selection, which the mod overrides anyway), and the watcher's `getBattle() != null → leave()` is a **load-bearing eject-and-recover path** (reachable in ordinary host play), not a paranoia assertion. All other engagement paths verified safe (stations, Remnant AI, MilitaryResponseScript, raid/expedition stages — all funnel through the gated pair loop). **Amended 2026-08-19 (guest could not engage anything):** the *same* `canBeEngaged()` gate also guards **player-initiated** encounters — `BaseLocation.advance`'s "player combat initiation" block requires `playerFleet.canBeEngaged() && target.canBeEngaged()` and matches on `playerFleet.getInteractionTarget()`, so an always-shielded mirror is skipped before the dialog is ever constructed and right-clicking a pirate mirror on the guest silently did nothing. Fix: `driveMovement` no longer asserts the shield at all (the per-frame pump pass is the single authority), and that pass **releases** the shield (`setNoEngaging(0f)` — a zero-duration Fader forces straight to IDLE, so the engine nulls it next advance) for the one NPC mirror that is the guest player's current interaction target, while no dialog is open. Every other NPC mirror keeps the shield as defense-in-depth, and the **partner player mirror keeps it unconditionally on both roles** — that is the PvP block. Vanilla's `FleetInteractionDialogPluginImpl` never reads the fader or `FLEET_IGNORES_OTHER_FLEETS` for the interaction target, so releasing it is sufficient for the whole engage flow.
- **Spike a (customs) PASS with a correction:** the plan's named customs rules (`$doingCustomsInspection`, rules.csv:2749–2854) are pre-0.8 dead code — nothing sets the key and the driving rulecmds are absent from api_src. The live path is `tOffPatrolBegin` (rules.csv:3395, the running-dark stop — exactly the Phase 9 gap fix) with `cargoScanInitial` (rules.csv:3551) as fallback; both end in `CargoScan`. The full vanilla confrontation ran against a mirror end to end on the guest (hail, rep penalty, scan against real cargo), and the faction rep delta propagated guest→host through the existing Phase 12 `GUEST_REP_DELTA` path with no new code. Preconditions that silently break the path (verified): mirror needs a commander, a `$sourceMarket` pointing at a real non-free-port market (else `CargoScan` NPEs), non-hostile posture, and non-player faction.
- **Spike b (`startBattle` vs mirror) PASS:** pilotable battle, clean campaign return, and the coop session survives the combat gap — the engaging client's TCP backlog buffers during battle and flushes on return (good precedent for `BATTLE_RESULT` reliability).

**Agent prompt:**

```text
Implement Phase 14 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Read the engine-facts block first; the design rests on it. Implement solo own-fleet combat: whichever player engages pilots the battle locally; combat-start asserts a combat pause intent into the Phase 11 CoopSharedPauseCoordinator (host sets its own clock; guest sends PAUSE_INTENT so the host pauses), and the non-engaged player opens the battle-status panel (custom InteractionDialogPlugin re-rendered from BATTLE_STATUS messages at 2-5 Hz over TCP), with an addMessage banner as the fallback. Implement the host-synthesized NPC-engages-guest and customs/inspection-dialog triggers. There is NO 60 Hz combat stream, NO rendered spectator, NO combat speed lock, and NO freeze/countdown/save-exit disconnect protocol — those are cancelled (2026-06-10). Do NOT add joint combat or CMC — v2/v3 stretch.
```

**Files:**

- Create `mods/coop/src/main/java/coop/combat/CoopBattleBridge.java` (engaging-side lifecycle: BATTLE_BEGIN/END, status capture)
- Create `mods/coop/src/main/java/coop/combat/CoopBattleStatus.java` (codec for the 2–5 Hz status records)
- ~~Create `mods/coop/src/main/java/coop/combat/CoopBattleStatusPanel.java` (spectator `InteractionDialogPlugin`)~~ — built, then **deleted 2026-08-19** (see the spectator revision note in the steps)
- Create `mods/coop/src/main/java/coop/combat/CoopNpcThreatWatcher.java` (host-side engagement/customs synthesis against the guest mirror)
- Create `mods/coop/src/main/java/coop/combat/CoopPreBattleAutosave.java`
- Create `mods/coop/src/main/java/coop/combat/CoopEngageDialogStaging.java` *(added 2026-08-19: the aggressor posture the `ENGAGE_GUEST` encounter dialog needs)*
- Create `mods/coop/src/test/java/coop/combat/CoopBattleStatusTest.java`
- Create `mods/coop/src/test/java/coop/combat/CoopNpcThreatWatcherTest.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignEventListener.java`
- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetMirror.java` (remove the interim permanent `$cfai_ignoredByOtherFleets` from 12b — protection moves to the watcher's battle-window/contact-time scheme)
- Modify `mods/coop/src/main/java/coop/time/CoopSharedPauseCoordinator.java` (assert combat pause intent on battle begin/end — coordinator built in Phase 11)
- Modify `mods/coop/src/main/java/coop/net/CoopNetService.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [x] **Spike first (do this step before any other in this phase):** (a) the customs/inspection rules-dialog-against-mirror path, per the risk note below; (b) one `ENGAGE_GUEST` → `startBattle` round trip versus a mirror fleet; (c) **battle-eject timing:** let a hostile NPC contact an unshielded mirror and verify a same-frame `battle.leave(mirror, false)` lands before any autoresolve damage (check the mirror's members are untouched) — this validates the contact backstop that pursuit-preservation rests on. If (c) fails, the fallback is a larger handoff threshold + keeping the permanent flag whenever the watcher is not confident, trading some chase fidelity for safety. If (a) fails, take the custom-dialog fallback named in the customs step and document the exact API blocker. Only proceed to the implementation steps once each spike has a recorded verdict. *(Done 2026-08-19: a PASS, b PASS, c MOOT — no NPC-vs-mirror battle can form, superseding the backstop-timing question. Verdicts + harness in `PHASE14_SPIKE_NOTES.md` and the engine-facts amendment above; harness = `coop.combat.CoopCombatSpike`, throwaway, trigger-file armed.)*
- [x] Add message types `BATTLE_BEGIN`, `BATTLE_STATUS`, `BATTLE_END`, `ENGAGE_GUEST`, and `DIALOG_BEGIN` — all reliable TCP. (No UDP combat stream; no disconnect-protocol messages — disconnect is detected locally by each side. `PAUSE_INTENT` and the `CoopSharedPauseCoordinator` are built in Phase 11; this phase only feeds it.)
- [x] Player-initiated engagement needs no programmatic battle open: the engaging player fights through the normal vanilla interaction dialog. The engaging client detects battle start locally (`reportPlayerEngagement` / the dialog plugin path), becomes the battle's authority, and sends `BATTLE_BEGIN` (battle id, location, engaging player id, enemy fleet summary, and the `coopFleetId`s of any host-owned NPC fleets in the battle). The programmatic `CampaignUIAPI.startBattle(BattleCreationContext)` path is used only for the host-pushed `ENGAGE_GUEST` case below.
- [x] **Both-players-in-one-battle fallback (v1 stopgap until joint combat):** if a single engagement's participant set includes *both* player fleets, the **host** is the engagement authority and pilots the combined battle; the guest's fleet participates as a host/AI-controlled side — concretely, the **host-side Phase 8 mirror of the guest's fleet** (the `$coopMirrorFleet` living on the host engine), never anything on the guest's machine — and the guest spectates. If the guest was the triggering client, it hands the battle context to the host (host opens and pilots via `startBattle`; guest opens the spectator view). The combat-start shared pause already serializes engagements, so this can only occur at the instant of a *simultaneous* engagement — never by one player wandering into another's already-running battle. Guest piloting its own ships inside a shared battle is **joint combat**, deferred to v2/v3 (now sketched as **Phase 22**, post-V1).
- [x] **NPC-initiated engagement against the guest (not PvP) — vanilla chase, pre-contact handoff:** *(Revised 2026-08-19 by the engine-facts amendment: "vanilla chases natively" is WRONG — hostiles never retask onto the mirror and no engine battle can form against it, so the handoff race and shielding below are moot. The watcher is now the initiator: detect hostile + proximity + `pickEncounterOption(null, mirror, true) == ENGAGE`, inject `INTERCEPT→mirror` on the chaser for visible pursuit, fire `ENGAGE_GUEST` at a trigger distance of ~400–700 su (design choice, not a race; observed closings 57–340 su/s), throttled per fleet and never during another coop battle/pause. Items (2) and (3) below reduce to a cheap non-load-bearing `getBattle() != null → leave()` assertion. The permanent-flag removal stands.)* — original design, kept as record: first, *remove* 12b's interim permanent `$cfai_ignoredByOtherFleets` from mirror creation — vanilla hostile AI now detects and chases the mirror natively (pirates hunt, Remnants intercept; no synthetic chase logic needed). `CoopNpcThreatWatcher` (host, per-frame over fleets in the mirror's location — cheap) does three things: **(1) pre-contact handoff:** when a hostile, combat-capable NPC fleet has the mirror as its assignment target (`getCurrentAssignment().getTarget() == mirror`, or proximity + `pickEncounterOption` says engage) and range closes below a handoff threshold (comfortably above the engine's contact range; sized in *time-to-contact* terms so it also covers network latency — Phase 20's latency audit re-derives it as ≥ 2 × p95 RTT + processing margin, not a loopback-tuned distance. **Placeholder until Phase 20:** during the spike, measure the chaser's closing speed and the engine contact range, then set the threshold to contact range + the distance closed in ~2 s at max burn; record the chosen value and the Phase 20 re-derive obligation in a code comment — do not leave it as a bare TODO), push `ENGAGE_GUEST(coopFleetId, kind=COMBAT)` — throttled per fleet, never while another coop battle or its shared pause is active; **(2) battle-window shielding:** set `$cfai_ignoredByOtherFleets` on the mirror when `ENGAGE_GUEST` fires and clear it on `BATTLE_RESULT`/timeout, so the chaser doesn't re-form contact while the guest fights its local copy (optionally pin the chaser with a hold so it visibly waits); **(3) contact backstop:** any frame where `mirror.getBattle() != null` → immediately `battle.leave(mirror, false)` + treat as a handoff trigger — this guards the race where contact beats the threshold (autoresolve damage rounds are interval-based, so a same-frame eject wins; spike verifies). The guest then opens and **pilots** that battle locally against its local mirror of that NPC fleet via `CampaignUIAPI.startBattle(BattleCreationContext)` (engine-facts block). Never guest-vs-host-player; PvP stays disallowed. When an NPC engages the *host's* fleet, vanilla handles it locally as usual.
- [x] **The handoff opens the vanilla encounter dialog, not a battle (added 2026-08-19, user requirement).** `ENGAGE_GUEST` used to end in `CampaignUIAPI.startBattle(BattleCreationContext)`, which put the guest on the deployment screen with no say in it. Being caught by a pirate is a conversation in vanilla — the fleet moves to engage, and the player picks engage, attempt to disengage, the 1-SP clean disengage, or the comm link — and the guest now gets that instead: `CoopBattleBridge.drivePendingEngage` stages the aggressor posture (`CoopEngageDialogStaging`) and calls `showInteractionDialog(mirror)`, the same one-argument overload the customs path has used in production since the spike. Disengage rolls, pursuit and the story-point getaway are vanilla's, unaltered. The old `startBattle` call survives as an explicitly logged fallback for a dialog that will not open, so a handoff never dies silently. No `BATTLE_BEGIN` is sent at open time on the dialog path: the guest may not fight at all, and an encounter it walks away from ends with one info line and no messages, leaving the watcher's 120 s per-fleet cooldown to re-arm on its own.
- [x] **NPC-initiated forced dialog against the guest (customs scan / inspection) — host-synthesized, same watcher:** when the watcher detects a patrol with hassle posture against the guest mirror (patrol faction not hostile, guest transponder off or contraband-suspicion conditions — the mirror's transponder state must be in the fleet snapshot; verify, add if missing), the host pushes `DIALOG_BEGIN` (patrol `coopFleetId`, faction, kind ∈ `CUSTOMS`/`INSPECTION`). The guest resolves it **locally against its own per-player cargo**: set the vanilla hassle/inspection memory flags on its local mirror of that patrol and open the vanilla fleet interaction via `showInteractionDialog(target = patrol mirror)` so vanilla rules run the scan/fine/confiscation against cargo the guest already has; per-player credit/cargo results apply locally, the shared rep delta + any fleet-state change report via `WORLD_DELTA(PARLEY)`. If the guest refuses → escalates to the guest-vs-NPC battle above. **This is the riskiest item in the phase** (vanilla rules driving a dialog against a mirror fleet is unproven) — it is also the committed fix for the Phase 9 transponder-reactions gap, so prove it early with a focused spike before building the rest; if the rules path fights back, fall back to a custom coop dialog that performs the same cargo check/fine/confiscation directly and report that in the docs.
- [x] **Guest-initiated proactive parley is host-only in v1.** The guest may *initiate combat* with any fleet (own-fleet combat) but cannot proactively open tribute/demand-surrender dialogs against host-owned fleets — those require replicating full fleet disposition (≈ dialog replication, out of v1 scope). Documented as a v1 limitation in Non-Goals.
- [x] **Combat-triggered pause:** on `BATTLE_BEGIN`, the engaging client asserts a combat pause intent into the Phase 11 `CoopSharedPauseCoordinator` (host sets `hostPauseIntent`/its own clock; guest sends `PAUSE_INTENT(true)`), so the non-engaged player is held and spectates. Release on `BATTLE_END`. (The coordinator's OR-of-intents logic and the broadcast-via-`TIME_SNAPSHOT` plumbing already exist from Phase 11; this phase only sets/clears the combat intent.)
- [x] **Pre-battle autosave (insurance, not rollback):** before opening the battle, the engaging client calls `Global.getSector().getCampaignUI().autosave()` (engine-facts: skipped while a dialog is open — call it after the engagement dialog closes / before `startBattle`). It exists so that a crash or disconnect mid-battle leaves a clean recent save; nothing in the protocol loads it programmatically (impossible — engine facts).
- [x] **Revised 2026-08-19 — the panel is cancelled; the banner fallback is now the spectator UX (user decision).** In-game spectating happens over an external screen-share (Discord), so a full-screen dialog on the watching client is in the way, and the live re-render blinked even with redraw-on-change. The spectator now gets a `CampaignUIAPI.addMessage` banner on `BATTLE_BEGIN` ("Coop: <partner> is fighting <enemy> in <location>."), an outcome banner on `BATTLE_END` (win/loss/disengage plus a "last report: N of M ships standing" tail derived from the newest `BATTLE_STATUS` already received — no new message fields), and the existing connection-lost banner; the shared combat pause hold is unchanged. `CoopBattleStatusPanel.java`, its tests and its Phase 10 interaction-gate exclusion are deleted. **The `BATTLE_STATUS` capture/stream/codec and kill feed are deliberately retained** — they are cheap, they work, and they are the raw material for Phase 22 milestone 0 (tactical-map observer); the spectator logs them as one change-detected debug line. Original step text, kept as record: ~~The non-engaged client opens `CoopBattleStatusPanel` (custom `InteractionDialogPlugin` via `showInteractionDialog`): a read-only status view re-rendered in `advance(float)` from the latest `BATTLE_STATUS` — own/enemy ship list with hull/flux bars, alive/disabled/destroyed markers, and a kill feed. Only options: none (informational) plus session disconnect. The dialog itself blocks campaign input (vanilla behavior; the Phase 10 gate + Phase 12b input-blocker suspension already handle dialog-open states). Fallback if the panel proves unstable: `addMessage` banner "Partner is fighting <enemy> in <location>" + the shared pause.~~
- [x] The **engaging** client captures `BATTLE_STATUS` every 300–500 ms during combat (an `EveryFrameCombatPlugin` reading `Global.getCombatEngine()` ship list: ship id, hull id, side, hull fraction, flux fraction, alive/disabled state) and sends it over TCP. Direction follows whoever fights (host→guest or guest→host). No positions, no 60 Hz, no UDP.
- [x] Send battle lifecycle (`BATTLE_BEGIN`, `BATTLE_END`, ship-destroyed events for the kill feed) over the same TCP path; `BATTLE_STATUS` is stateless/latest-wins so a dropped-frame-equivalent costs nothing.
- [x] **Disconnect mid-combat (simplified 2026-06-10 — finish locally, reconcile by authority):** if the connection drops while a coop battle is active, the **engaging** client just finishes its battle locally (combat is fully local anyway) and then sees the normal session-loss handling in the campaign; its `BATTLE_RESULT` never sends, which is safe — on the next coop session the host's authoritative state simply resurrects whatever the report would have changed (log loudly when a battle result is discarded unsent). The **spectator** client closes the status panel with a "connection lost" message and ends the session normally (campaign was paused the whole time — nothing to unwind). No freeze, no countdown, no forced save-exit, no rollback: the cancelled protocol depended on programmatic save-loading that does not exist, and host authority + the pre-battle autosave already bound the damage. (`cmdExitWithoutSaving()` stays documented in the engine-facts block if a stricter flow is ever wanted.)
- [x] On battle end, the engaging client sends `BATTLE_END`, clears its combat pause intent, and the other client ~~closes the status panel~~ *(2026-08-19: posts the outcome banner — the panel is gone)*.
- [x] Add tests for: `BATTLE_STATUS` codec round-trip + latest-wins sequencing, combat asserting/clearing its pause intent on battle begin/end (both host-fighter and guest-fighter cases), the threat watcher's trigger predicate (hostility/intercept-target/handoff-threshold/cooldown/not-during-battle), the battle-window shield lifecycle (flag set on `ENGAGE_GUEST`, cleared on `BATTLE_RESULT`/timeout), the contact backstop (in-battle mirror → eject + handoff), and discarded-unsent-result logging. (The coordinator's OR-of-intents and UI-pause behavior are tested in Phase 11.)
- [ ] Run a two-instance smoke test for **both** directions *(2026-08-19: "status panel live" now reads "begin/end banners posted")*: (a) host engages — guest is paused, banners posted; (b) guest engages — host paused, banners posted; (c) a hostile pirate/Remnant fleet *detects and chases* the guest across the system, `ENGAGE_GUEST` fires just before contact, and the guest pilots that battle while the chaser visibly waits (no autoresolve damage on the mirror at any point — check the log for backstop ejects); (d) patrol customs against the transponder-off guest resolves locally and the rep delta reaches both clients; (e) kill the connection mid-combat — engaging side finishes and logs the discarded result, spectator side gets the connection-lost banner and ends the session cleanly.
- [ ] Commit with `git add mods/coop && git commit -m "feat: add solo own-fleet combat + battle status bridge"`.

**As built (2026-08-19) — decisions and deviations from the step text:**

- **Battle-start seam = an `EveryFrameCombatPlugin`, not a campaign callback.** Campaign
  `EveryFrameScript`s do not advance in the `COMBAT` state, so a `Global.getCurrentState()` transition
  is unobservable from the pump, and `reportPlayerEngagement` fires inside
  `processEngagementResults` — *after* the battle, far too late to pause the partner. The plugin's
  first frame is the only seam that runs at combat start, and it supplies the `CombatEngineAPI` the
  status capture needs anyway. New file `CoopBattleStatusCombatPlugin` (not in the Files list),
  registered through the mod's `data/config/settings.json` `"plugins"` map — vanilla's documented
  surface for it, and the same merge the mod already uses for `newGameSectorProcGen`.
  **Battle-end seam** = the first campaign pump frame after combat, gated on having seen a combat
  frame (`startBattle` queues the transition and the pump can run one more campaign frame first —
  observed in the spike). `reportBattleOccurred` / `reportPlayerEngagement` are wired in as
  *enrichment only* (the `BATTLE_END` outcome string), never as the trigger, because neither fires
  for an engagement the player disengaged from before contact.
- **Mid-combat send: FLUSHABLE, and done.** `CoopNetService.flushOutbound()` is called from the combat
  frame. That is not a thread hack: combat frames run on the same game thread as the pump (the two
  states never run concurrently), the outbound queue is a `ConcurrentLinkedQueue`, and every channel
  mutation is inside `lifecycleLock` on non-blocking `java.nio` channels. So `BATTLE_BEGIN` reaches the
  spectator *before* the fight rather than in the post-battle backlog the spike observed, and
  `BATTLE_STATUS` streams live at 2.5 Hz. Inbound is deliberately not drained mid-combat.
  `CoopNetService` needed **no changes**.
- ~~**The panel does not fight the clock.** It never calls `setPaused`. A vanilla interaction dialog
  pauses the campaign by itself, and `CoopNetPump.maybeApplyTimeSnapshot` already stops applying host
  snapshots while a dialog is open — the exact guard that fixed the trade-tab freeze. The guest's
  screen-pause *intent* keeps the host paused; the host spectator is held by the combat intent.
  While the session is alive the panel has no options and escape is dead, so the spectator is
  genuinely held; an escape hatch appears on connection loss (or after 10 minutes) so nobody is
  trapped. The panel is excluded from the Phase 10 interaction gate — claiming it would have locked
  the *fighting* partner out of every interaction.~~
- **Superseded 2026-08-19 — banners replaced the panel (user decision).** The panel above was built
  and worked, but it blinked on every redraw and the user spectates over Discord screen-share, so the
  whole dialog was cancelled. What replaced it: a queued `addMessage` banner on `BATTLE_BEGIN` and on
  `BATTLE_END` (queued because inbound messages can land on a pump frame with no sector; the queue is
  bounded at `MAX_PENDING_BANNERS` and flushed on the first frame with a campaign UI). The spectator's
  hold is unchanged — it was always the shared combat pause, never the dialog. Everything the panel
  paragraph above says about *not* touching the clock still applies by construction: banners are
  fire-and-forget. With the panel gone the Phase 10 interaction gate needs no exclusion at all, and
  `CoopBattleBridge.isStatusPanel` / `canDismissPanel` / the escape hatch are deleted with it.
- **No new `PauseSource`.** `BATTLE_BEGIN`/`BATTLE_END` drive `setEitherInCombat` on the host
  directly (its own battle sets it inside `beginLocalBattle`, before the pump stops advancing), so
  `CoopSharedPauseCoordinator` needed **no changes**. TCP guarantees `BATTLE_END` delivery while the
  connection lives, and a dead connection resets the session (which clears the intent), so no
  watchdog is needed; a start-timeout still releases the clock if a battle never reaches combat.
- **Both-players-in-one-battle: the stopgap collapsed into the shield.** With
  `FLEET_IGNORES_OTHER_FLEETS` mandatory on mirrors (it is what closes the `pullInNearbyFleets` path),
  the guest mirror can no longer be dragged into a host battle at all, so "the guest's fleet fights
  host/AI-controlled" is unreachable. The observable outcome is preserved — host fights, guest
  spectates via the normal `BATTLE_BEGIN` — minus the mirror's ships participating. The watcher's
  `getBattle() != null → leave()` is what enforces it, and is now a *recovery* path with a loud log.
- **Pre-battle autosave covers the `ENGAGE_GUEST` path only.** `autosave()` is silently skipped while
  a dialog is open, and a player-initiated engagement runs inside the vanilla encounter dialog right
  up to the state transition — so no autosave can land before that fight, and one landing *after* it
  is a multi-second freeze for nothing. No request is made on that path; documented in
  `CoopPreBattleAutosave`.
- **Watcher constants:** `ENGAGE_TRIGGER_SU = 500` (spike closing speeds 57–340 su/s; Phase 20
  re-derive obligation recorded in the constant's javadoc), `CHASE_INJECT_SU = 2000`,
  `CUSTOMS_TRIGGER_SU = 600`, scan cadence 250 ms (the battle-eject recovery still runs every frame),
  per-fleet cooldowns 120 s / 30 s / 600 s, plus a 15 s global handoff grace covering the
  `ENGAGE_GUEST` round trip so a second hostile cannot be handed off inside the gap.
- **`ENGAGE_GUEST` presents a vanilla encounter (2026-08-19, second revision of the day).** What the
  dialog needs is one predicate: `FleetInteractionDialogPluginImpl.otherFleetWantsToFight()`
  (FID:3483 → `fleetWantsToFight`, FID:3492-3519), an AND of hostility (FID:3514, already true for
  the pirates the watcher hands off) and `ai.pickEncounterOption(context, playerFleet) == ENGAGE`
  (FID:3510). True prints `initialAggressive` at FID:973 and routes the option panel into the
  FID:2868-2921 subtree where the free `Leave` is unreachable; false prints "assumes a neutral
  posture" and offers a plain Leave.
  **Staged: one flag.** `$cfai_makeAggressive`, written with `Misc.setFlagWithReason(mem, ...,
  "coopEngage", true, 1f)`. `TacticalModule.pickEncounterOption` checks it first (:1286) and returns
  ENGAGE outright, ahead of the strength maths at :1334-1415 and ahead of the 0.3 s decision cache.
  The staging exists because the host asks the ENGAGE question against *its* mirror of the guest
  fleet while the dialog re-asks it against the guest's *real* fleet, and those live-roster
  evaluations can disagree at the margin — a disagreement would turn a committed handoff into a
  neutral menu. Retirement is threefold and needs no mod bookkeeping:
  `MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY` (vanilla hard-unsets the flag in
  `CampaignEngine.reportBattleOccurred`), the 1-day expiry on the reason key, and an explicit
  `CoopEngageDialogStaging.clear` on the no-battle outcome. Reason-scoping via
  `Misc.setFlagWithReason` (Misc:1439-1451) is what keeps it from stomping vanilla's own reasons on
  the same flag (`"tOff"`, `"pursue"`).
  **Deliberately not staged:** `$cfai_makePreventDisengage`, which would also force ENGAGE (:1292)
  but removes the real clean-getaway mechanics — it satisfies the hostility disjunct on its own and
  flips the enemy's pursuit pick from `LET_THEM_GO` to `HARRY` (:1454). Whether the guest slips away
  stays vanilla's call: burn levels via `FleetEncounterContext.canOutrunOtherFleet`:2880, fleet size
  via `canDisengage` FID:3181, the 1-SP `CLEAN_DISENGAGE` at FID:2917. `FLEET_IGNORES_OTHER_FLEETS`
  is also left alone — the dialog reads it in one place, `pullInNearbyFleets` FID:540-542, and only
  for *bystander* candidates, never for the interaction target.
- **The engagement shield needed no change, and that was verified rather than assumed.** The pump
  puts the shield back up the instant any dialog owns the screen
  (`CoopNetPump.playerEngagementTargetOrNull` returns null at :1165), so the mirror is shielded for
  the whole encounter. It does not matter: `canBeEngaged` and the `noCombat` fader appear nowhere in
  `FleetInteractionDialogPluginImpl` (grep over the whole api_src dump returns no hits in that
  file), and the disengage/pursuit rounds are option-panel swaps inside the same plugin instance —
  `ATTEMPT_TO_DISENGAGE` (FID:1264-1314) flips the fleet goals and falls through to
  `CONTINUE_INTO_BATTLE` → `dialog.startBattle(bcc)` at FID:1228. There is no `setPursuitTargets`
  anywhere in api_src and the dialog never closes and re-enters through `BaseLocation`'s gated
  initiation block, so the "pursuit reopens the encounter and the shield blocks it" case does not
  exist. This is the identical path the guest's own right-click engagement already takes in
  production.
- **Outcomes, and the two watchdogs that stopped mattering.** Fight → the combat plugin's first
  frame sends `BATTLE_BEGIN`/`BATTLE_STATUS`/`BATTLE_END` unchanged, now tagged
  `BattleKind.ENGAGE_GUEST` and carrying the `coopFleetId` (the kind cannot be read off the engine —
  a handoff battle and a right-click battle enter through the identical seam — so the bridge holds
  it in `engageDialogFleetId` from dialog open until the encounter resolves). Disengage or leave →
  one info line, no messages, the host's per-fleet cooldown re-arms. The 15 s
  `BATTLE_START_TIMEOUT_MILLIS` cannot fire spuriously on the dialog path because no local battle is
  opened until combat starts; it still guards the `startBattle` fallback, which is unchanged. The
  host's 15 s `HANDOFF_GRACE` is now too short for the job — a player reading an encounter outlasts
  it, and `BATTLE_BEGIN` may never arrive to hold the host — so the guest refuses a second
  `ENGAGE_GUEST` while `engageDialogFleetId` is set rather than queueing itself into two fights. The
  host is held throughout by the guest's screen-pause intent, which
  `CoopNetPump.isVanillaBlockingScreenOpen`:1010 already asserts on `ui.isShowingDialog()`; the
  combat intent takes over on the same `BATTLE_BEGIN` if the guest fights. The pre-battle autosave
  ordering is untouched: `drivePendingEngage` still runs it on the dialog-free frame before opening,
  because `autosave()` no-ops behind a dialog.
- **Extra files beyond the Files list:** `CoopEngageDialogStaging` (the aggressor posture above),
  `CoopBattleStatusCombatPlugin` (above),
  `CoopCustomsDialogStaging` (the reusable customs staging the step asked for, factored out of the
  spike rather than called from it — the spike class is untouched), and `CoopBattleBridgeTest` (the
  pause/eject/discard tests would have been misfiled inside `CoopBattleStatusTest`).
  `CoopCampaignReplicator` gained only an isolated `BattleObserver` pass-through.

**Acceptance:**

- Either player can engage and **pilots their own battle locally**; the engaging client owns that battle's outcome.
- Combat-start asserts a pause intent into the Phase 11 shared-pause coordinator so the non-engaged player is held; guest battles drive the pause via `PAUSE_INTENT` to the host.
- ~~The non-engaged player follows the battle through the status panel, updated from `BATTLE_STATUS` at 2–5 Hz over TCP (sourced from whichever client fights), and cannot issue any combat command (there is no combat engine on the spectating client at all).~~ **Revised 2026-08-19 (user decision):** the non-engaged player is told about the battle by a begin banner and an outcome banner and is held by the shared pause; live in-game spectating is out of scope for v1 (it happens over an external screen-share). They still cannot issue any combat command — there is no combat engine on the spectating client at all. The 2–5 Hz `BATTLE_STATUS` stream keeps running and is retained for Phase 22 milestone 0's tactical-map observer.
- There is no combat speed lock and none is needed (vanilla has no player combat speedup; exact-install parity covers settings) — verified, not just skipped.
- Disconnect mid-combat: the engaging client finishes its battle locally and loudly logs the discarded unsent result; the spectator gets the connection-lost banner *(2026-08-19: was "exits the panel cleanly")*; the next session's host-authoritative rebroadcast reconciles any divergence. The pre-battle autosave exists on the engaging client's disk as crash insurance.
- Hostile NPC fleets **chase** the guest via watcher-injected `INTERCEPT` assignments (the permanent 12b flag is removed by this phase; *revised 2026-08-19 — native chase disproven, see engine-facts amendment; **that revision was itself wrong and the INTERCEPT injection is deleted by Phase 14b** — the spike watched assignments, vanilla pursuit is `setMoveDestination`*); the watcher fires `ENGAGE_GUEST` at its trigger distance and the guest gets the vanilla being-caught encounter against its local mirror (*2026-08-19: `showInteractionDialog`, with `startBattle` demoted to a logged fallback — the guest can fight, disengage, spend the story point, or walk away, and only the fight produces battle messages*), piloting any resulting guest-vs-NPC battle locally (not PvP); zero silent autoresolve damage on the mirror is guaranteed by the **per-frame engagement shield** (`setNoEngaging` re-asserted unconditionally — *2026-08-19: except for the single mirror the guest has actively targeted, which is released so the guest can engage it at all; see the engine-facts amendment*) plus `FLEET_IGNORES_OTHER_FLEETS` closing the pull-in path, with the watcher's `getBattle() != null → leave()` as the load-bearing eject-and-recover for anything that slips through (pull-in is reachable in ordinary host play — see mechanism note).
- A host-owned patrol/inspection fleet can stop the guest: the host pushes `DIALOG_BEGIN`, the guest resolves the customs/inspection locally against its own cargo, and reports rep/fleet deltas. **This is the fix for the Phase 9 gap where faction fleets no longer react to the guest running transponder-off** — it must cover the running-dark confrontation and its standing penalty, not just contraband cargo (the suppressor removed the guest's local sim that used to produce these; see Phase 9 "Deferred / out of scope" and memory `guest-transponder-reactions-gone`). Guest-initiated *proactive* parley is host-only (a v1 limitation).
- If a single engagement pulls **both** players into one battle, the host pilots it and the guest spectates (the guest's fleet fights host/AI-controlled); results reconcile back per Phase 15. This is the v1 stopgap until joint combat (v2/v3).
- No joint combat and no CMC classes/dependencies are added (that is a v2/v3 stretch).

## Phase 14b: Vanilla Pursuit + Sensor Fidelity (2026-08-19)

> **What this phase is.** Phase 14 shipped and its five smoke scenarios passed, but two of its conclusions rested on a misread and one of its side effects made stealth impossible. 14b deletes the synthesized chase, hands pursuit back to vanilla's own AI, gives the guest mirror the guest's real sensor identity so hostiles see (or miss) it at vanilla ranges, stops beaten fleets rematching 27 seconds later, and fixes the parked grey-fleet rendering bug — which turned out to have the same root cause as the sensor work.

**User decisions, locked 2026-08-19:**

- **Near-contact catch range.** The guest is dropped into the encounter at contact distance (both fleet radii plus a small margin), not at a chosen 400–700 su. Outrunning a chaser has to work exactly as it does in vanilla. LAN latency is accepted; the WAN re-derivation stays a Phase 20 note.
- **Full sensor-profile fidelity for the guest mirror.** Transponder, Go Dark, Sustained/Emergency Burn, Active Sensor Burst, terrain — all of it. This pulls the sensor-relevant part of Phase 12c's "ability effects" item forward.
- **Grey-fleet cosmetic issue folded in** from the parked list in `PHASE14_SPIKE_NOTES.md`.
- **Stealth works in both directions.** A guest running dark is invisible to host-side NPCs at vanilla ranges, which means no pursuit and no customs stop; the guest's own view of NPC fleets follows the same rules.

**Engine facts (decompile, 2026-08-19; `K:\Starsector\tmp_ff_analysis`):**

1. **Pursuit steering is `setMoveDestination`, not an assignment.** `TacticalModule` never adds, removes or mutates an assignment — it only reads `getCurrentAssignment()`. It stores the quarry in `this.target` (`TacticalModule.java:1049-1058`) and steers at `:673-676`. The Phase 14 spike watched assignments, so its "vanilla never retasks to hunt the mirror" verdict measured nothing.
2. **Nothing excludes the mirror from the candidate loop.** `canBeEngaged()` / `noEngaging` / `isNoEngagingSet()` appear zero times in `TacticalModule.java` and `StrategicModule.java`; the only readers are `BaseLocation` (battle initiation). The candidate source is every `CampaignFleet` in the chaser's location (`:242`), re-evaluated on a 0.05–0.1 day interval tracker (`:207-209`). Gates, in order: self/empty skip (`:269`), **visibility** (`:271-273`, `candidate.getVisibilityLevelTo(chaser) != NONE` — the only range gate in the engine, there is no distance constant), hostility from either side (`:275-279`), the `isPlayerFleet()`-guarded player block (`:282-314`, bonuses only), `pickEncounterOption` (`:316`), the `if (bl12)` hostility fork (`:335`, non-hostile candidates can never be selected), strength/personality/gang-up (`:341-359`), and finally `(ENGAGE || gangUp) && isOkToPursue` (`:364`) or `HOLD && !isPlayerFleet && isOkToPursue` (`:370`). The single flag that excludes a target is `$cfai_ignoredByOtherFleets` (`StrategicModule.java:505`), which Phase 14 removed from mirror creation.
3. **The mirror gets *less* protection than the real player fleet, not more.** `Misc.isPlayerOrCombinedPlayerPrimary(mirror)` is false, so `isHostileTo`'s "doesn't know who you are" early-out (`TacticalModule.java:1110-1112`) and the `$cfai_recentlyDefeatedByPlayer` DISENGAGE (`:321-323`, guarded by `isPlayerFleet()` at `:282`) both skip it. The mirror is hostile-to on pure faction rep.
4. **The injected `INTERCEPT` was a permanent engage licence.** `isAllowedToEngage` clears every ignore flag when the current assignment already targets the entity (`StrategicModule.java:510-512`) and short-circuits the whole give-up heuristic for an INTERCEPT aimed at its own target (`:551-553`, `:638-640`). Playtest symptom: `Coop injected INTERCEPT->guest mirror on Raiders (pirates) dist=272.3` repeating every 30 s for over ten minutes.
5. **`getDoNotAttack()` is the one post-defeat mechanism that works on an arbitrary target.** `isAllowedToEngage` consults it first and unconditionally (`StrategicModule.java:500-502`), above the assignment override that defeats the ignore flags. It is public API (`StrategicModulePlugin.getDoNotAttack()` returns `TimeoutTracker<SectorEntityToken>`, days), and `CampaignFleetAIAPI.doNotAttack(target, days)` is a max-merge convenience wrapper. Vanilla's own values: `0.5 + random()` days at `TacticalModule.java:176`, `1f` at `TutorialLeashAssignmentAI:56` and `ZigLeashAssignmentAI:59` — both paired with `getTacticalModule().setTarget(null)`, because `isAllowedToEngage` is only re-checked for a held target on the next frame (`TacticalModule.java:179-181`).
6. **`setPriorityTarget(SectorEntityToken, float days, boolean followMode)` exists** on `TacticalModulePlugin` (`TacticalModule.java:1085-1089`) and overrides candidate selection at `:461-466` without touching assignments — so `isAllowedToEngage` keeps applying, unlike the INTERCEPT injection.
7. **Detection reads exactly two things off the target.** `BaseCampaignEntity.getMaxSensorRangeToDetect(observer, target)` (`:1130-1157`) is `(target.sensorProfile + observer.sensorStrength)`, then `(base + base*(targetPct + obsPct)/100 + (targetFlat + obsFlat)) * targetMult * obsMult`, then `* target.getStats().getDynamic().getValue("detected_by_player_range_mult")` **only when the observer is the player fleet** (`:1142-1145`), capped at `sensorRangeMax` (5,000 in-system, 2,000 hyper). `getVisibilityLevelTo` (`:1182-1226`) buckets the edge-to-edge distance: faction details when the target's transponder is on and `d <= range`, or unconditionally inside `max(0.1*range, 50)`; grey `COMPOSITION_DETAILS` inside `0.5*range`; question-mark `SENSOR_CONTACT` inside `range`. Not cached — recomputed on every call.
8. **`CampaignFleet.updateCounts()` runs every frame** from `advance` (`CampaignFleet.java:794`) and rewrites both sensor fields from the roster. `setSensorProfile` is skipped when `forceNoSensorProfileUpdate` is set (`:1026-1027`); **`setSensorStrength` at `:1029` has no such flag**, so a bare `setSensorStrength(x)` survives less than one frame. The surviving route is `stats.getSensorStrengthMod()`, a `StatBonus` `updateCounts` applies rather than overwrites.
9. **Abilities and terrain write the same `detectedRangeMod` object.** Transponder `+1000` flat, Sustained Burn `+100%`, Emergency Burn `+50%`, Active Sensor Burst `+5000` flat, Go Dark `x0.5` — and every terrain plugin re-applies its own 0.1-day temporary mod every frame (`NebulaTerrainPlugin:253`, `AsteroidBeltTerrainPlugin:159`, `RingSystemTerrainPlugin:75`, `DebrisFieldTerrainPlugin:314`, `MagneticFieldTerrainPlugin:192-210`, `HyperspaceTerrainPlugin:1399-1441`). `SlipstreamTerrainPlugin` has no sensor modifiers at all. "Moving slow" (`Misc.isSlowMoving`) is not a sensor modifier either — it only gates storm strikes and the movement speed limit.
10. **The grey is literal.** `SensorContactIndicatorManager.advance` paints `COMPOSITION_DETAILS` with a hardcoded `new Color(125,125,125,255)`; only `COMPOSITION_AND_FACTION_DETAILS` uses the faction color. `SENSOR_CONTACT` renders question-mark blips and is not even click-selectable (`CampaignEngine.java:1488`).
11. **`RepairTracker.setCR(float)` invalidates nothing.** `FleetMember.getMemberStrength()` caches its CR-derived result in `cachedStrength`, cleared only by `setStatUpdateNeeded(true)`, `updateStats()` or `readResolve()` (`probe/FleetMember.java:278-284`, `:640-661`). Hull fraction needs no invalidation — `Misc.getMemberStrength` reads `getStatus().getHullFraction()` live.

**Primary model (shipped live):** the trigger is `ai.getTacticalModule().getTarget() == mirror`, ANDed with the side-effect-free `pickEncounterOption(null, mirror, true) == ENGAGE` so the maintain-contact and evade branches (which set the same target) do not produce a fight. By the time that holds, every vanilla gate in fact 2 has run. `ENGAGE_GUEST` fires once the chase reaches `chaser.getRadius() + mirror.getRadius() + 100 su`; the 100 su is one 250 ms scan at 340 su/s, the fastest chaser the Phase 14 spike clocked. The visible chase is vanilla's own `setMoveDestination`; nothing is injected.

**Fallback model (`-Dcoop.pursuit.synthesized=true`, or sector memory `$coopSynthesizedPursuit`; default off):** if the primary signal proves dead in the smoke test, this re-implements fact 2's gates over public API — visibility, `isAllowedToEngage`, and vanilla's pursuit patience (`1.5` days, `3.0` for `$isPatrol`, plus `0.1` per burn level; `StrategicModule.java:554-588`) — and steers with `setPriorityTarget` instead of an assignment. Flip the flag, do not rebuild.

**Amendment, 2026-08-19 (after the first 14b smoke): patrol inspection pursuit is synthesized too.** Stealth pass-by passed, but Hegemony patrols ignored a transponder-off guest well inside their sensor range and only hailed it on near-collision. Cause: **vanilla's inspection pursuit is player-only in three separate places.** `StrategicModule`'s `ORBIT_PASSIVE`/`FOLLOW` clause allows an engage only when the target *is the player fleet* and the chaser is `$isPatrol` (`:617-625`); the entire transponder-suspicion block in `TacticalModule.advance` — the one that sets `$cfai_makeAggressive`, `$sawPlayerTransponderOff` and `$cfai_makeHostileWhileTOff` — sits behind `campaignFleet2.isPlayerFleet()` (`:282-314`); and `Misc.isPlayerOrCombinedPlayerPrimary` is false for a mirror. No native patrol will ever hunt it, so the flat 600 su hail was all the guest ever saw.

The watcher now runs a per-patrol inspection chase whose **start gate is detection and nothing else** — from the moment `mirror.getVisibilityLevelTo(patrol) != NONE`, the patrol closes; there is deliberately no distance constant on top of visibility, which is exactly the range fix the report asked for. The stop fires at the same `radii + 100 su` contact threshold the hostile handoff uses. Give-up paths, all of which remove the assignment and stamp `CUSTOMS_COOLDOWN_MILLIS`: vanilla's own patrol patience (`3.0` days + `0.1`/burn, `StrategicModule.java:554-588`), contact lost for more than 12 scans (3 s), transponder back on, the stop fired, the patrol turning hostile, or the patrol leaving the mirror's location.

**Steering is a bounded `INTERCEPT`, not `setPriorityTarget` — decompile-decided.** The priority target is only promoted to `this.target` *after* the candidate loop (`TacticalModule.java:461-466`). On the next tactical interval (0.05–0.1 days, `:207-209`) that loop walks the mirror again; because a patrol is not hostile to it, the mirror falls through to the loop's tail, which reads `if (campaignFleet2 != this.target) continue; this.setTarget(null); this.priorityTarget = null;` (`:485-487`). **A non-hostile priority target erases itself within one interval and no chase happens.** An `INTERCEPT` steers through the assignment/navigation modules instead, which the Phase 14 spike proved works against a mirror (389 → 17 su at 151 su/s). This is safe here in a way the deleted hostile-chase injection was not: a non-hostile patrol cannot turn `isAllowedToEngage`'s assignment short-circuit (`:551-553`) into an engagement licence, because no battle can form against the mirror and the stop is our own `DIALOG_BEGIN`. The assignment is issued for `0.5` campaign days and re-issued while the chase is live, so the worst case if the watcher ever loses a patrol is half a day of orphan rather than a ten-minute siege.

**Sensor fidelity:** `coop.fleet.CoopSensorSync` captures a fleet's profile plus the three `detectedRangeMod` aggregates plus its sensor strength, and pins them on the mirror: `setForceNoSensorProfileUpdate(true)` + `setSensorProfile` for the profile, a `-100%`/flat pair on `getSensorStrengthMod()` for the strength, and a **correction** on `getDetectedRangeMod()` that strips its own modifier, reads what the local engine put there natively, and writes the difference. The correction is what absorbs fact 9's terrain: the mirror sits where the real fleet sits, so the receiving client re-applies the same terrain mods the sending client already baked into the captured aggregate. This replaces Phase 9's single folded "effective detectability" float, whose double-count was the grey-fleet root cause. The player-presence indicator moved off `setSensorProfile`/`detectedRangeMod` onto `Stats.DETECTED_BY_PLAYER_RANGE_MULT` (fact 7), which is invisible to NPC AI.

**Files:**

- **Create** `src/main/java/coop/fleet/CoopSensorSync.java` (capture, pin, wire codec)
- Modify `src/main/java/coop/combat/CoopNpcThreatWatcher.java` (rebuilt: vanilla-target trigger, contact threshold, do-not-attack queue, visibility-gated customs, dormant pursuit probe, fallback model)
- Modify `src/main/java/coop/fleet/CoopFleetMirror.java` (sensor accept + per-frame re-assert; CR stat invalidation)
- Modify `src/main/java/coop/fleet/CoopPresenceIndicator.java` (player-only detection mult)
- Modify `src/main/java/coop/fleet/CoopFleetSnapshot.java`, `CoopNpcFleetSnapshot.java`, `CoopNpcFleetMotion.java`, `CoopFleetSnapshotFactory.java`, `CoopNpcFleetReplicator.java`, `CoopNpcMirror.java`, `CoopFleetMirrorRegistry.java`, `src/main/java/coop/net/CoopNetPump.java`

**Acceptance (unchecked until the smoke session):**

- [ ] **Stealth pass-by.** Guest runs dark past a hostile pirate fleet at a range where vanilla would not detect it: no pursuit, no `ENGAGE_GUEST`, and with `-Dcoop.debug.diagnostics=true` the host's `Coop pursuit probe` lines read `visible=false hunting=false`. Transponder on at the same range flips `visible` to true.
- [ ] **Hot pursuit + escape.** Guest is detected, a hostile picks ENGAGE, and the host shows `hunting=true` with the distance closing. Guest outruns it; no `ENGAGE_GUEST` ever fires, and vanilla's own patience ends the chase (probe shows `hunting=false` again). No `Coop injected INTERCEPT` line exists anywhere in the log — that path is deleted.
- [ ] **Hot pursuit + catch.** Same chase, guest does not escape: `Coop ENGAGE_GUEST sent ... dist=<= contact vanillaHunting=true` and the guest gets the vanilla being-caught encounter against its local mirror.
- [ ] **Customs stop — chase from detection range.** Transponder-off guest detected by a Hegemony patrol at long range: `Coop customs pursuit starting ... dist=<large> patienceDays=3.0`, followed by `Coop customs pursuit chasing ...`, the patrol visibly closing on the map, and `Coop DIALOG_BEGIN sent (customs, transponder-off guest)` only once it reaches contact. The flat 600 su hail is gone.
- [ ] **Customs stop — escape and stand-down.** During that chase, (a) turn the transponder on → `Coop customs pursuit transponderOn ... assignmentsCleared=1` and the patrol breaks off; (b) go dark and open the range → `Coop customs pursuit lostContact ... unseenScans=13`; (c) simply outrun it → `Coop customs pursuit outOfPatience ... elapsedDays=3.0x`. In every case the patrol resumes its own route and does not re-acquire (customs cooldown). No patrol is ever left following the guest indefinitely.
- [ ] **Customs stop — no chase without detection.** Guest dark and outside patrol sensor range: no `Coop customs pursuit starting` line at all, and the probe reads `patrol=true visible=false customsPursuit=idle`.
- [ ] **Post-defeat grace.** Guest beats a fleet and stays next to it: `Coop post-defeat grace applied coopFleetId=... days=0.5..1.5` on the host, and that fleet does not re-engage for the stated window. The 27-second rematch does not recur.
- [ ] **Grey fleets.** NPC mirrors on the guest hold a stable identification level while the host's copy of the same fleet holds the same one; no flicker as fleets drift through nebulae or asteroid belts; an Active Sensor Burst changes the level on both clients the same way.
- [ ] **Presence.** Each player still sees their partner across the map, in their own faction color, at any range.
- [ ] **Which model is live.** Record whether `hunting=true` was ever observed. If it never was, set `-Dcoop.pursuit.synthesized=true` and re-run the pursuit scenarios; the fallback is built and tested, not a rewrite.

**Open questions the smoke has to settle (not answerable from the decompile):**

- Whether vanilla's `TacticalModule` targets the mirror in practice. Everything visible in the candidate loop says it should; only the game can confirm it.
- Whether `CoopSensorSync`'s per-frame correction fights any modded terrain plugin that reads `getDetectedRangeMod()` back rather than only writing to it.
- Whether an inspection chase reads as vanilla-paced. Vanilla's 3-day patrol patience is ~30 real seconds at default time compression, so a patrol that cannot out-burn the guest should break off on its own; if it feels too clingy or too brief, the lever is `PURSUIT_BUDGET_DAYS_PATROL`, not the start gate.
- Whether an `INTERCEPT` on a patrol visibly disrupts anything else it was doing (it is added at the start of the queue and removed on every exit, so its own route should resume, but a patrol yanked off a `MilitaryResponse` assignment is worth an eye).
- Whether the contact threshold feels right for a fleet with a large radius (a big capital fleet has a bigger radius, so it catches the guest sooner in absolute su — vanilla-correct, but worth a look).

## Phase 15: Combat Results + Campaign Reconciliation

> **v1 reward rule:** the **solo fighter keeps their own** XP, salvage, credits, and recoveries — the engaging client applies its own `EngagementResultAPI` locally (vanilla). There is **no 50/50 split in v1**, because every v1 battle has exactly one piloting player. A `CoopRewardSplitter` only becomes necessary when **joint combat** lands in v2/v3; it is explicitly deferred. This phase is therefore about **reconciling the battle's campaign-level deltas** (dead NPC fleets, shared reputation) back into the host's canonical state, not about splitting spoils.
>
> **Verified 2026-06-10:** vanilla applies all battle consequences locally and self-contained on the fighting client (`FleetEncounterContext.processEngagementResults` line ~231 and `applyAfterBattleEffectsIfThereWasABattle` line ~756, invoked from the interaction dialog's LEAVE path) — fleet losses, loot, XP, and the local rep adjustment all land without mod interference, so "let vanilla apply its own results" is sound. Also note the Phase 14 disconnect rule: a `BATTLE_RESULT` that never sends (connection died mid-battle) is *accepted* — the host's authoritative NPC set resurrects unreported kills next session, by design.
>
> **Deviation, 2026-08-19 (build): `BATTLE_RESULT` carries NO reputation, and the reconciler applies none.** The step below that says "host applies the shared faction `repDelta`" would **double-apply** it. Battle reputation on the guest already reaches the host on the Phase 12 path: vanilla's rep adjustment fires `CampaignEventListener.reportPlayerReputationChange`, which `CoopCampaignReplicator.onPlayerReputationChange` (`:249`) forwards as `GUEST_REP_DELTA` (`:266`); the host folds the increment into the canonical standing and rebroadcasts the authoritative `REP_DELTA` (`handleGuestFactionRepDelta`, `:360`). The Phase 14 customs spike verified exactly that path end to end with no new code (see `CoopCustomsDialogStaging`'s class doc). The 30 s `PLAYER_REP_SNAPSHOT` full overwrite is a second safety net. So the acceptance criterion "shared reputation converges to the host value; the spectator receives no rep delta" is **met by the Phase 12 path, not by this phase** — and `CoopBattleResultTest.carriesNoReputationAndNoSpoils` plus `CoopBattleResultReconcilerTest.theReconcilerNeverMovesSpoilsOrReputation` are the regression guards that keep a rep channel from being added here later.
>
> **Deviation, 2026-08-19 (build): no own-roster payload either.** The step's "summary of the engaging player's own fleet roster change" is left as an informational `engagingFleetSize` int only. The partner's mirror of the engaging player's fleet is already refreshed by the Phase 8 `FLEET_SNAPSHOT` UDP stream at 10 Hz, which carries the **full** roster with per-ship CR/hull and rebuilds the mirror on any structural change (`CoopFleetMirror.refreshRosterIfChanged`). Duplicating it here would be a second, slower source of truth for state that already self-heals in 100 ms.
>
> **Design note, 2026-08-19 (build): the result is built when the encounter dialog closes, not at `BATTLE_END`.** `BATTLE_END` still fires on the first campaign frame after combat (the spectator banner and the shared pause must not wait), but at that moment the vanilla post-battle dialog is still on screen and `applyAfterBattleEffectsIfThereWasABattle` — the LEAVE-path call that actually despawns losers and finalises rosters — has not run. Reading the mirror there reports a dead fleet as a survivor. `CoopBattleBridge` therefore parks a `PendingResult` at battle end and `drivePendingResult` builds/dispatches it on the first dialog-free campaign frame, with the existing 60 s `PENDING_ACTION_TIMEOUT_MILLIS` as the escape hatch. Destroyed-vs-survived is then read off the world (fleet missing / not alive / no members ⇒ destroyed), which is what makes a partial outcome — an escape or disengage leaving the NPC damaged but alive — reconcile correctly. An outcome with zero losses still sends a (mostly empty) result so the host restarts pacing deterministically.
>
> **Design note, 2026-08-19 (build): the guest's post-battle resurrection window is closed by a per-fleet mirror freeze, armed at battle START.** Any `NPC_FLEET_SET` arriving before the host has reconciled would call `applySnapshot` and recreate the fleet the guest just killed, at full roster. `CoopFleetMirrorRegistry.markPendingReconcile` makes `applySet` skip that `coopFleetId` until the host confirms — the id disappears from the set (kill confirmed → dispose), or arrives with a different `fleetHash` (roster reconciled → apply), or `PENDING_RECONCILE_TIMEOUT_MILLIS` (60 s) elapses, so a lost result can never become permanent divergence.
>
> The freeze is armed on `BATTLE_BEGIN`, not on `BATTLE_END`, and **that ordering is load-bearing**: the campaign pump does not advance during combat, so TCP piles up a backlog of `NPC_FLEET_SET` messages that `CoopNetPump.drainInbound` flushes on the first frame back — *before* `tickBattleBridge` runs. Freezing at battle end loses that race, the backlog resurrects the fleet at full roster, and the result built moments later reads the resurrected mirror and reports the kill as a survivor. For the same reason the freeze is refreshed on the battle's 2.5 Hz status cadence (`onCombatFrame`): its timeout is wall-clock and nothing else is running to renew it during a long fight.
>
> The freeze is roster-only: 10 Hz `NPC_FLEET_MOTION` is deliberately not gated by it, so a beaten survivor keeps fleeing normally. The guest only freezes mirrors for battles *it* fought; a fleet the host fought is already reconciled in the host's own world and its next set is the truth.
>
> **Fold-in, 2026-08-19 (build): the `ENGAGE_GUEST` cooldown clock restarts at battle end.** `CoopNpcThreatWatcher.noteBattleConcluded` is called for every `coopFleetId` a battle involved, from `BATTLE_END` (guest's battles) and from the reconciler (host's). `ENGAGE_COOLDOWN_MILLIS` is unchanged at 15 s — this is about *when the 15 s starts*. Previously it was stamped when the handoff was sent, so a fight lasting longer than the cooldown left the beaten fleet re-armed the instant the guest returned, while its reconciliation was still in flight.

**Agent prompt:**

```text
Implement Phase 15 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. The engaging client applies its own combat result locally (it keeps its own XP/salvage/credits/recoveries — there is NO 50/50 split in v1). Implement CoopBattleResultReconciler so the engaging client reports the battle's campaign deltas (NPC fleets destroyed/damaged keyed by coopFleetId, plus the shared faction rep delta) and the host integrates them into the authoritative NPC fleet set (Phase 9) and the shared reputation table (Phase 12), then re-broadcasts. Do NOT build the 50/50 splitter — that is a v2/v3 joint-combat concern.
```

**Files:**

- Create `mods/coop/src/main/java/coop/combat/CoopBattleResult.java`
- Create `mods/coop/src/main/java/coop/combat/CoopBattleResultReconciler.java`
- Create `mods/coop/src/test/java/coop/combat/CoopBattleResultTest.java`
- Create `mods/coop/src/test/java/coop/combat/CoopBattleResultReconcilerTest.java`
- Modify `mods/coop/src/main/java/coop/combat/CoopBattleBridge.java` *(2026-06-10: was `CoopCombatSpectator`, which the Phase 14 revision replaced)*
- Modify `mods/coop/src/main/java/coop/fleet/CoopNpcFleetReplicator.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Extra files beyond the Files list (2026-08-19 build):** `CoopFleetMirrorRegistry` (the guest-side post-battle freeze; the resurrection window lives in the mirror layer, not in the reconciler), `CoopNpcThreatWatcher` (the cooldown-restart fold-in), `CoopNetPump` (routing + the two bridge sinks + the reconciler's engine wiring), and the `CoopBattleBridgeTest` / `CoopFleetMirrorRegistryTest` / `CoopNpcThreatWatcherTest` additions that cover them. `CoopCampaignReplicator` got a one-line drive-by only (the `MARKET_OPEN for unknown market` warn is now a debug line — the guest opening an uncolonized/procgen entity is expected, not an anomaly).

**Steps:**

- [x] Add `BATTLE_RESULT` message (reliable TCP).
- [x] Define `CoopBattleResult` fields: `battleId`, `engagingPlayerId`, destroyed/disabled NPC `coopFleetId`s with post-battle survivor state, ~~the shared faction `repDelta`(s) with resulting relationship value~~ *(2026-08-19: removed — see the rep deviation note above; it would double-apply)*, and a summary of the engaging player's own fleet roster change *(2026-08-19: reduced to an informational `engagingFleetSize`; the 10 Hz `FLEET_SNAPSHOT` stream already carries the full roster)*.
- [x] Engaging client applies its own `EngagementResultAPI` locally via vanilla (own XP, salvage, credits, own/enemy recoveries) — the mod does not redistribute these. *(Nothing to build: the mod never touches the engagement result. `CoopBattleResultReconciler.AuthoritativeFleets` has no credits/XP/cargo surface at all, which is what the test asserts.)*
- [x] On battle end, the engaging client sends `BATTLE_RESULT`; if the engaging client is the guest, the host receives it and integrates; if the engaging client is the host, it integrates locally and broadcasts the resulting set/rep updates. *(The host-as-fighter path is a direct local call into `CoopBattleResultReconciler` — do NOT wire the host to send a `BATTLE_RESULT` message to itself. Verify both directions in the smoke test.)* *(2026-08-19: "on battle end" is precisely "on the first dialog-free campaign frame after battle end" — see the design note above.)*
- [x] Host integrates NPC outcomes by updating the authoritative NPC fleet set from Phase 9 (despawn destroyed `coopFleetId`s, update survivors' roster) and re-broadcasting `NPC_FLEET_SET`; the spectator's mirrors reconcile automatically. *(Despawn is vanilla's own `despawn(DESTROYED_BY_BATTLE, null)` so the owning managers hear `reportFleetDespawned` and release their handles. Survivor rosters are matched by **variant multiset**, not member id — a guest mirror's `FleetMemberAPI`s are minted locally — and removal is capped at the reported loss count so a key mismatch can never wipe a fleet.)*
- [x] ~~Host applies the shared faction `repDelta` to the shared reputation table (Phase 12 `REP_DELTA` path) and broadcasts~~; the spectator gets **no** rep delta (no participation). *(2026-08-19: **deliberately not implemented here** — the Phase 12 `GUEST_REP_DELTA` path already does exactly this and doing it twice would double-apply. Full evidence in the deviation note above.)*
- [x] Add an idempotency guard keyed by `battleId` so a re-delivered `BATTLE_RESULT` is applied once. *(Bounded to the last 64 battle ids; oldest evicted.)*
- [x] Add tests: solo fighter keeps own spoils (reconciler never moves credits/XP between players); destroyed NPC `coopFleetId`s are removed from the authoritative set; shared rep converges to the host value; spectator rep delta is zero; idempotent re-apply by `battleId`. *(The two rep assertions became "this phase carries and applies no rep at all", which is the honest form of them given the deviation. Added on top: survivor roster update, the roster-diff loss cap, the codec round trip through the real envelope, the mirror-freeze release conditions, and the watcher cooldown restart.)*
- [x] Run a battle smoke test in both directions and confirm the engaging player keeps spoils, the killed NPC fleet disappears on both clients, and shared rep converges. *(2026-08-19: guest direction — Raiders despawned + Smuggler partial `lost=7 remaining=4`; host direction — three `applied` with no inbound message, confirming the direct local call; rep converged via the Phase 12 path.)*
- [x] Commit with `git add mods/coop && git commit -m "feat: reconcile coop battle campaign results"`.

**Acceptance:**

- The engaging player keeps 100% of their own battle spoils; nothing is split (50/50 is deferred to v2/v3 joint combat).
- NPC fleets destroyed in a battle are removed from the host's authoritative NPC fleet set and disappear on both clients.
- Shared reputation converges to the host value after the engaging player's combat rep change; the spectator receives no rep delta. *(2026-08-19: satisfied by the Phase 12 `GUEST_REP_DELTA`/`PLAYER_REP_SNAPSHOT` path, which this phase deliberately does not duplicate. Still worth watching in the smoke test — if it does **not** converge, the bug is in Phase 12, not here.)*
- `EngagementResultAPI` is not used as if it had a participation fraction.
- A beaten NPC fleet does not instantly re-fire `ENGAGE_GUEST` while its result is still propagating, and the guest's mirror of a fleet it just destroyed is not resurrected by the host's stale set.

## Phase 16: Coordinated Saves + Guest Snapshot (was: Save + Guest Export)

> **Redesigned 2026-06-10.** Two findings forced it. (1) **The original file-export design is sandbox-illegal:** the guest writing `coop_player_<uuid>.dat` with a `.tmp`/`.bak` rename dance is raw `java.io`/`java.nio.file`, which the script classloader hard-blocks at runtime; the only sanctioned file surface is the `SettingsAPI` *common-folder* API (verified: `writeTextFileToCommon`/`readTextFileFromCommon`/`fileExistsInCommon`/`deleteTextFileFromCommon`, plus `writeJSONToCommon`/`readJSONFromCommon`; files land in `saves\common\`, text writes capped at 1 MB, no arbitrary paths exist — there is no `getSaveFolder()`). (2) **The export is mostly redundant under the v1 save policy:** the guest owns a real coop save (Design Alignment Notes) — guest progress already persists as a full save, strictly better than a fleet-only export file. What actually protects against "host crash loses session progress" is keeping the two saves *temporally aligned*, and `CampaignUIAPI.autosave()` is public (Phase 14 engine facts), so the guest can simply be told to autosave whenever the host saves. `CoopPaths`/`GuestFleetExportStore` and the `.dat` artifact are cancelled.

> **Fold-in (2026-08-19, user decision): D-mod replication for mirror rosters.** Phase 14b's
> stock-id streaming deliberately mirrors D-modded host fleets as clean stock loadouts (see
> `PHASE14_SPIKE_NOTES.md` known issues). Build the fidelity fix as part of this phase: (1) capture —
> per member, stream the variant's D-mod hullmod ids (stock ids, resolvable on both installs; one new
> delimited field on `CoopFleetSnapshot.Member`, bump `MEMBER_FIELD_COUNT`); (2) apply — on the guest,
> after building the stock ship, **clone the variant first** (a created member's variant can be the
> shared global spec — mutating it un-cloned would D-mod every clean hull in the guest's universe),
> set it as a runtime copy, re-add the D-mods, and run the engine's own damaged-hull swap via the
> public `DModManager` impl API; (3) tests for capture round-trip and the clone-before-mutate
> invariant. Verify in this phase's smoke: a D-modded host fleet shows damaged hulls on the guest
> (roster-diff diagnostic lines will show the dmod field). The deferred 14b pursuit-escape scenario
> also runs in this phase's smoke session.

**Agent prompt:**

```text
Implement Phase 16 from COOP_MP_IMPLEMENTATION_PLAN_V1.md (read the 2026-06-10 redesign note first, then the 2026-08-19 D-mod fold-in note). Host owns the canonical save; implement (1) the guest fleet snapshot stored in the host save's persistentData with XStream aliases, and (2) coordinated saves: after every host save, send SAVE_CHECKPOINT so the guest triggers its own vanilla autosave when no dialog is open. There is NO file export — raw file I/O is sandbox-blocked; if a guest-side artifact beyond the guest's own save ever proves necessary, the only legal channel is the SettingsAPI common-folder API (saves\common\, 1 MB text cap).
```

**Files:**

- Create `mods/coop/src/main/java/coop/save/CoopGuestSnapshot.java` (XStream-safe DTO)
- Create `mods/coop/src/main/java/coop/save/CoopSaveCheckpoint.java` (host send + guest deferred-autosave logic)
- Create `mods/coop/src/test/java/coop/save/CoopGuestSnapshotTest.java`
- Create `mods/coop/src/test/java/coop/save/CoopSaveCheckpointTest.java`
- Modify `mods/coop/src/main/java/coop/CoopModPlugin.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java`

**Steps:**

- [ ] Define `CoopGuestSnapshot` with uuid, session id, campaign UUID (Phase 6b), seed string, credits, cargo stack summaries, fleet member summaries, officers, timestamp, and format version. Plain POJO with a no-arg constructor — the bundled XStream is 1.4.10 (no records), and DTOs stay free of `CampaignFleetAPI`/`SectorAPI`/transient engine references.
- [ ] Register aliases in `CoopModPlugin.configureXStream(XStream x)` (verified `ModPlugin` hook) — e.g. `x.alias("coopGuestSnap", CoopGuestSnapshot.class)` — so save-file entries don't embed fragile package paths.
- [ ] On host `beforeGameSave()`, write the latest guest snapshot into `Global.getSector().getPersistentData()` under `coop.guestFleetSnapshot` (the documented cross-session map; survives in the host save). **Purpose (decided 2026-06-10): deliberately write-only in v1.** It is disaster-recovery raw material for a guest who loses their save (the host save then still holds the guest's fleet/cargo/credits); no v1 code reads it back, and that is intentional, not a 6b-style audit finding — the restore flow is sketched in Maybe ("Guest-save recovery"). Document the key and its purpose in `README_DEV.md` so a future audit doesn't flag it as dead state.
- [ ] **Coordinated saves:** on host `afterGameSave()` (manual or autosave), send `SAVE_CHECKPOINT` over TCP; the guest responds by calling `Global.getSector().getCampaignUI().autosave()` — deferred until no dialog is open (`autosave()` silently skips inside dialogs; retry next frame until clear, give up with a log after ~30 s). Result: host crash/disconnect loses at most the progress since the last host save *on both clients*, with no custom file format at all.
- [ ] Send a final `SAVE_CHECKPOINT` on graceful session end (before teardown), so both sides end aligned.
- [ ] Add unit tests: snapshot DTO round-trips through a real `XStream` instance with the aliases applied; checkpoint deferral logic (dialog open → retried; clear → autosave invoked once; duplicate checkpoints debounced).
- [ ] Two-instance smoke test: host manual save → guest log shows the deferred autosave firing; quit both, relaunch both, resume the session from the two saves (the supported resume flow) → seed lock + campaign UUID accept, mirrors rebuild (12b sweep), play continues.
- [ ] Commit with `git add mods/coop && git commit -m "feat: coordinate coop saves and embed guest snapshot"`.

**Acceptance:**

- The host save contains the guest snapshot DTO (XStream-aliased, engine-reference-free), refreshed on every host save.
- Every host save triggers a guest autosave (deferred around dialogs), so the two saves stay temporally aligned; an abrupt host crash loses at most the progress since the last host save on either client.
- No runtime code touches `java.io.*`/`java.nio.file.*`; no custom save-file artifacts exist outside the two vanilla saves (the common-folder API remains documented as the only legal escape hatch if one is ever needed).
- The resume-from-saves flow works end to end after a coordinated save pair.

## Phase 17: Fleet Wipe — Harden the Vanilla Respawn for Coop

> **Rescoped 2026-08-20 (user decision, research session).** The original spec (inject a `wolf_Starting` in `reportPlayerEngagement` so the defeat check never fires, then grant 5,000 credits at the last friendly station) is **cancelled — do not build it**. It rested on a wrong engine-facts block: vanilla 0.98a has a full fleet-wipe respawn, and both of its call sites are gated on `!isValidPlayerFleet()`, so the injection would have *suppressed* a vanilla flow that is strictly better than its replacement (two ships vs one, 80% of credits vs a 5,000 floor, officers/abilities/mission cargo carried, narrated dialog, bounty-level rollback). Decisions locked 2026-08-20: keep vanilla's random respawn market (no `setRespawnLocation` override, `CoopRespawnPointTracker` cancelled); the pre-battle autosave stays `ENGAGE_GUEST`-only (a `kind=PLAYER` battle gets no rollback point — accepted, since wipes are survivable).

**Agent prompt:**

```text
Implement Phase 17 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Vanilla already respawns a wiped player (read the corrected engine-facts block); build no respawn mechanics and do not touch the defeat path. Deliver the two coop gaps: the empty-roster guard in CoopFleetMirror and the RESPAWN_PLAYER partner notification.
```

**Engine facts (corrected 2026-08-20 — the 2026-06-10 block was wrong on its central claim; bytecode + live-log verified):**

- **Vanilla 0.98a-RC8 has an unconditional fleet-wipe respawn, iron and non-iron alike:** `CampaignState.showShuttleDialog()`. It fires when the player clicks LEAVE on the "no ships left" screen, and from `CampaignState.advance()` if an invalid player fleet exists outside a dialog. The old claims ("no engine respawn for non-iron play", "respawn scaffolding is story-event-driven", "the member-less fleet is despawned by `CampaignFleet.advance()`") traced the defeat path one step short: `showShuttleDialog` removes and replaces the player fleet before any despawn can run. `pickRespawnPlugin()` returning null outside the tutorial is exactly what routes into this built-in flow.
- Synchronous effects, single method: old player fleet removed; new fleet built from the player-faction stock fleet `"shuttle"` = `wayfarer_Starting` + `kite_Starting` (`starsector-core\data\world\factions\player.faction:322-346`); officers, skills, abilities, reputation and `mission_item` cargo carried by the engine; teleport to a size-weighted random friendly market (`SectorAPI.setRespawnLocation()`/`setRespawnCoordinates()` would override the pick — deliberately unused, see the banner); credits become `max(old * 0.8, 2000)`; max CR on the new members; transponder re-activated; campaign paused.
- Live confirmation: guest full wipe 2026-08-19 18:05:35 (pirate Smuggler, Askonia; respawn in Naraka ~4 s later). Host-side log signature: `roster refreshed to 0 ship(s) fleetHash=e3b0c442…` (the empty-roster hash, host log.2:60346) then `Coop mirror fleet moved to location naraka` + a new 2-ship hash (host log.2:60434-60435). The partner mirror recovered clean.
- The mod already tolerates the fleet-object swap from `setPlayerFleet()`: every `getPlayerFleet()` call site re-reads per use, and mirrors key on player id (`player:<uuid>`), not fleet id, so the cross-system teleport propagates without surgery.
- **The one defect to fix:** the wiped client streams 0-member `FLEET_SNAPSHOT`s during the wipe window (`maybeSendFleetSnapshot` has no empty-fleet guard) and the partner's mirror commits the empty roster. `CampaignFleet.advance()` despawns a 0-member fleet with `FleetDespawnReason.NO_MEMBERS`, and `setNoAutoDespawn(true)` does **not** cover that branch (it checks only `fadeAndExpire`). The 2026-08-19 event stayed clean only because the shared pause held for the whole 4 s window; at WAN latency the pause lands 200 ms + RTT late, so unpaused frames can despawn/recreate the empty mirror in a loop, spraying spurious `reportFleetDespawned` events at vanilla listeners.

**Files:**

- Modify `mods/coop/src/main/java/coop/fleet/CoopFleetMirror.java`
- Create `mods/coop/src/main/java/coop/fleet/CoopRespawnNotifier.java`
- Create `mods/coop/src/test/java/coop/fleet/CoopRespawnNotifierTest.java`
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java` (+ `CoopNetPump` wiring)

**Steps:**

- [x] **Empty-roster guard:** `CoopFleetMirror` never commits a 0-member roster **for the partner player mirror** — skip the apply, keep the last non-empty roster, log once per episode. **Scope warning (2026-08-20, Phase 16 smoke observation):** `CoopFleetMirror` also backs NPC mirrors, and the Phase 15 battle-result teardown *legitimately* commits a 0-member roster to a destroyed NPC mirror moments before removing it (guest log: `roster refreshed to 0 of 0 ship(s)` on `BATTLE_END`, then the mirror leaves the NPC set). The guard must apply only to the player-mirror path (`player:<uuid>` key), or the battle teardown path must be exempted — a blanket guard breaks destroyed-fleet cleanup. Unit tests: an empty snapshot leaves the player-mirror roster unchanged; the next non-empty snapshot applies normally; an NPC-mirror battle teardown still empties and removes.
- [x] **Respawn detection (wiped client, local):** detect the `getPlayerFleet()` object-identity swap (per-frame check in the pump path). Send `RESPAWN_PLAYER` (player id, destination market/system display name) over TCP.
- [x] **Partner banner:** on `RESPAWN_PLAYER`, show via `CampaignUIAPI.addMessage` (existing `CoopNetPump` pattern) that the partner's fleet was destroyed and where it respawned — without this the survivor's only cue is the mirror teleporting across the sector.
- [x] Build **no** respawn mechanics: no ship grant, no credit top-up, no skill/rep/officer preservation code (the engine carries them), no `setRespawnLocation` writes, nothing on the defeat path.
- [x] Smoke test (cheap since the Ziggurat preset): deliberate wipe in both directions — the vanilla shuttle dialog fires; the partner sees the banner; the partner's log shows no 0-roster commit and no despawn/recreate churn; skills/officers/rep survive. *(Verified 2026-08-24, two-instance session: guest wipe → respawn at Nachiketa + host banner; host wipe → respawn at Chicomoztoc + guest banner; each survivor logged exactly one `Coop player mirror kept its last roster` per episode against the empty-roster hash `e3b0c442…`; the only mirror creations in the whole log are the two session-start ones — no despawn/recreate churn; one `RESPAWN_PLAYER` send/receive pair per direction.)*
- [x] Commit with `git add mods/coop && git commit -m "feat: harden vanilla wipe respawn for coop"`.

**Acceptance:**

- A wiped player continues the session through the vanilla shuttle respawn, both directions.
- The partner gets the `RESPAWN_PLAYER` banner with the destination, and their mirror never commits an empty roster.

## Phase 18: Interaction-Gate WAN Race Hardening

> **Rescoped 2026-08-20 (user decision, research session).** The original "Same-Dock Shared UI Locks" (per-submarket `UI_LOCK_*` mutex, `CoopDockUiLocks`/`CoopUiLock`) is **cancelled — do not build it**. Its premise was never the shipped behavior: the Phase 10 gate is a *global* first-come lockout (`CoopInteractionGate.blockingClaimFor` returns the earliest claim held by any other player on **any** entity), so two players cannot hold dialogs at once anywhere in the sector, let alone share a shop — and Phase 24's diff-on-close colony model relies on exactly that ("no concurrent-edit conflicts by construction"). The design aspiration "both players docked at the same market" (`COOP_MP_DESIGN.md` §8.14) is deferred post-V1: if serialized docking feels bad during the soak, the follow-up is entity-scoping `blockingClaimFor`, and only then does a shop mutex earn its keep. Note the pause is not the mechanism and never was: the shared pause rides the 200 ms `TIME_SNAPSHOT` cadence, while the gate's claim goes out on the dialog-open frame over TCP.
>
> What survives is the one reachable defect, previously Phase 20's inherited item and now owned here: the guest opens its dialog **optimistically** before the claim round-trip completes, so at WAN RTT the both-in-the-same-shop state is hittable (window ≈ host frame + TCP one-way + guest frame ≈ 40–110 ms at 50–150 ms RTT), and there the market model genuinely breaks — host purchases are never pushed to an already-open guest screen (`onPlayerMarketTransaction` early-returns on the host, so a unique hull bought by both duplicates), and a guest market-open re-rolls the host's open shop underneath the host's UI (`broadcastMarketSnapshot` → `ensureOpenMarketStocked` → `updateCargoPrePlayerInteraction`). Kept as its own small phase rather than merged into 20 so it is reproducible and testable on localhost with induced latency before any WAN work exists. The 2026-06-10 API-verification notes on submarket listeners are kept below only for the `reportPlayerClosedMarket` sink gap, which is still real.

**Agent prompt:**

```text
Implement Phase 18 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Close the interaction-gate WAN race: force-close the local player's already-open dialog on INTERACTION_REJECT, stop the reject re-claim loop, add the debug latency lever, and fence storage against market-snapshot regressions. Do not build any UI lock system.
```

**Files:**

- Modify `mods/coop/src/main/java/coop/net/CoopNetPump.java`
- Modify `mods/coop/src/main/java/coop/campaign/CoopCampaignEventListener.java`
- Add/extend tests (reject handling; storage fence)

**Steps:**

- [x] **Forced close on reject:** `handleInteractionReject` (`CoopNetPump` ~1485) dismisses the local player's open dialog for the rejected entity; if mid-frame dismissal is unsafe, re-assert the block and dismiss via a one-shot `EveryFrameScript`. Show `Player <name> is using this` via `addMessage` (existing pattern, ~line 1189). *(Built 2026-08-24. Deferred, as pre-authorized — but hosted in the pump's own per-frame `syncInteractionGate` step rather than a separately registered one-shot script: `CoopNetPump` is already an `EveryFrameScript` with `runWhilePaused()`, so a second registration would have identical run conditions plus its own lifecycle to get wrong. `forceCloseRejectedDialog` runs on the frame after the reject, calls `InteractionDialogAPI.dismiss()`, and re-issues every frame until the dialog is gone while `applyLocalBlocking` re-asserts the block. Message: `<remote name> is using <entity> - try again shortly` (ASCII, per the Phase 17 glyph note). It never touches the input blocker or `setPaused`: `syncGuestInputBlocker` un-suspends by itself once the screen is gone, and the guest's screen-pause intent simply stops being sent.)*
- [x] **Reject re-claim loop:** today the reject handler nulls `localInteractionEntityId` while the dialog is still open, so `detectLocalInteraction` re-claims every frame — a claim/reject ping-pong at up to 60 msg/s over TCP plus a warn per frame. Track the rejected entity until its dialog actually closes; no re-claim, no log spam. *(Built 2026-08-24: the handler keeps `localInteractionEntityId`, so the per-frame detector's equality check already suppresses the re-claim; `CoopRejectTracker` adds the belt — `isRejected` gates `beginLocalInteraction` and `onRejected` returns false for a repeat, so the warn is logged once per lost race. "Actually closed" = the first frame on which the open dialog is not the rejected entity; `reportPlayerClosedMarket` is a secondary confirmation and is ignored once a dismissal is in flight, because vanilla reports a market close when the trade screen is left with the dialog still up.)*
- [x] **Debug latency lever:** `-Dcoop.debug.interactionDelayMs=<n>` (dormant by default, `CoopDebug` pattern) delays inbound claim processing on the host so the race reproduces on localhost. *(Built 2026-08-24: parsed once at class init and re-read on `CoopDebug`'s 300-frame poll; 0/absent/garbage/negative all mean dormant, values are clamped to 60 s. The host parks each inbound `INTERACTION_CLAIM` with a release stamp in a FIFO drained from `syncInteractionGate` — no sleeping, because the pump thread is the campaign thread. Receive order is preserved, which is the order `CoopInteractionGate` arbitrates on.)*
- [x] **Storage regression fence:** the explicit exclusion comment at the market-snapshot capture site naming the never-snapshotted submarkets (storage/black/military/local_resources — the Phase 12 path touches only `SUBMARKET_OPEN` via `CoopCampaignReplicator.openMarketCargo`, ~line 1305), plus a test asserting a snapshot apply leaves `SUBMARKET_STORAGE` cargo untouched. *(Built 2026-08-24: the comment sits on `openMarketCargo`, which every capture/pre-stock/apply/delta path funnels through, and spells out why storage is the dangerous one — the apply is a replacement, not a merge. `CoopCampaignReplicatorStorageFenceTest` asserts the apply never even **reads** the storage submarket.)*
- [x] Forward `reportPlayerClosedMarket(MarketAPI)` into `CoopCampaignEventListener.Sink` (verified 2026-06-10 as not forwarded) — used by the reject bookkeeping here and required later by Phase 24's diff-on-close. *(Built 2026-08-24: `Sink.onPlayerClosedMarket(MarketAPI)` keeps the whole market for Phase 24; the replicator resolves the primary entity id and hands both ids to the pump through `MarketCloseObserver`, mirroring the existing `BattleObserver` seam.)*
- [x] Unit tests: a reject marks the entity non-reclaimable while its dialog is open; close/release/disconnect clears it; the storage fence. *(25 new cases: 12 in `CoopRejectTrackerTest`, 6 in `CoopNetPumpTest` (forced close, re-issue-until-gone with a single message, re-claimable after close, unrelated entity untouched, lever holds then releases, dormant lever arbitrates immediately), 4 in `CoopDebugTest` (lever parse/clamp/dormant), 3 in `CoopCampaignReplicatorStorageFenceTest`.)*
- [x] Two-instance smoke with the latency lever: both dock the same market inside the widened window — the loser's dialog closes with the message; the log shows exactly one claim/reject pair. *(Verified 2026-08-24 at `corvus_hegemony_station`, host lever 1500 ms. First attempt surfaced a lever gap: `PAUSE_INTENT` rides the same guest→host leg as the claim, and undelayed it froze the host within one TIME_SNAPSHOT cadence, making the race unreachable by hand — fixed by parking `PAUSE_INTENT` in the same delay queue and draining right after `drainInbound` so released messages take a just-arrived message's frame path (commit `c92a4f1`). Rerun: guest claim parked 1500 ms, host docked and won arbitration inside the window, released claim rejected exactly once, guest logged exactly one `closing the local dialog` + one `force-closing rejected interaction dialog`, zero fail markers.)*
- [x] Commit with `git add mods/coop && git commit -m "feat: close interaction-gate WAN race"`.

**Acceptance:**

- With induced latency, a lost claim force-closes the loser's dialog; no claim/reject loop appears in the log.
- A market snapshot apply never touches `SUBMARKET_STORAGE` (test-enforced).
- No `UI_LOCK_*` messages or lock classes exist.

## Phase 19: Two-Instance QA Pass

**Agent prompt:**

```text
Implement Phase 19 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. PREREQUISITE: every other V1 phase (through Phase 20, per the Implementation Order) must already be complete - do not start Phase 19 otherwise. Add and run the final two-instance QA checklist for v1. Fix only issues required to pass the checklist; do not add v2 scope.
```

**Files:**

- Create `mods/coop/QA_V1.md`
- Create `mods/coop/scripts/tail-log.ps1`
- Create `mods/coop/scripts/qa-preflight.ps1`
- Modify only files needed to fix checklist failures.

**Steps:**

- [ ] Add `QA_V1.md` with the exact manual two-instance checklist from this phase.
- [ ] Add `scripts/qa-preflight.ps1` that verifies `jars/coop.jar`, `jars/coop-forks.jar`, `mod_info.json`, and Starsector core jars exist. *(2026-06-10: removed the stale "Netty jars" check — networking is java.nio, no Netty dependency exists; see Source Layout note.)*
- [ ] Add `scripts/tail-log.ps1` that tails `K:\Starsector\starsector-core\starsector.log` or the active log path documented in `vmparams`.
- [ ] Run preflight.
- [ ] Run iron-mode refusal test for a coop start attempt.
- [ ] Run a two-instance connect test.
- [ ] Run seed lock test and record fingerprint lines.
- [ ] Run campaign-UUID replay test (Phase 6b): a freshly re-rolled guest with the same seed attempts to connect → `SEED_LOCK_REJECT` with reason prefix `campaignId:` before any gameplay sync.
- [ ] Run HIGH-impact random fork audit and record fork paths.
- [ ] Run movement/fleet mirror test.
- [ ] Run NPC fleet replication test: confirm the guest shows the same NPC fleet set/rosters as the host in a shared system, host despawns propagate, and the guest logs no independently-spawned NPC fleet.
- [ ] Run base-authority test (Phase 13): the guest runs none of the extended suppression-list managers (coverage diagnostic clean) and shows exactly the host's pirate/Pather base set — or, if the pre-authorized SUPPRESS-ONLY fallback was taken, confirm suppression holds and the limitation is documented.
- [ ] Run shared fast-forward test (Phase 7b): host toggles FF → both clients speed up together and dates stay aligned over several FF cycles; the guest still cannot FF independently.
- [ ] Run clock-drift test (Phase 7c): induce guest date drift (e.g. OS-suspend the guest ~10 s), confirm the dates re-converge within the dead zone and the guest clock never steps backward.
- [ ] Run orphan-sweep test (Phase 12b): save mid-session in a busy system, quit both instances, load the guest save solo → the log shows the sweep's removed-count line and no frozen NPC mirrors or partner fleet are visible.
- [ ] Run out-of-sensor presence indicator test.
- [ ] Run interaction gate test.
- [ ] Run WAN claim-race test (Phase 18): with `-Dcoop.debug.interactionDelayMs` (or under the Phase 20 latency matrix), both players dock the same market inside the widened window → the loser's dialog force-closes with the in-use message and the log shows exactly one claim/reject pair (no ping-pong).
- [ ] Run shared mission/bar first-come claim test.
- [ ] Run market-contents test: both clients see identical shop stock across **all kinds — commodities, ships, weapons, fighters** (the 12c extension) — plus hireable officers/mercenaries, and a purchase/hire of every kind by either updates the host's canonical market.
- [ ] Run salvage/exploration test: one client salvages a derelict; confirm it is consumed (not re-lootable) on the other and loot landed only in the acting player's cargo.
- [ ] Run faction-relations test: trigger an inter-faction standing change and confirm both clients agree on hostility.
- [ ] Run world-affecting ability test: activate an interdiction/distress ability and confirm the host applies and broadcasts the effect.
- [ ] Run special bar-event test: confirm both clients see the same one-time bar offers and only one player can claim a given offer.
- [ ] Run NPC-initiated dialog test: a host patrol stops the guest for a customs/inspection; confirm the guest resolves it against its own cargo and rep/fleet deltas propagate.
- [ ] Run guest transponder-off reaction test (regression closed): guest runs dark near a host patrol in that faction's space → confirm the patrol confronts the guest and the standing penalty propagates to both clients (this path was removed by the Phase 9 suppressor; see Phase 9 deferred + memory `guest-transponder-reactions-gone`).
- [ ] Run own-fleet combat test in both directions: host engages (guest paused + battle-status panel live) and guest engages (host paused + panel live), confirming the combat-start shared pause and the 2–5 Hz status stream.
- [ ] Run NPC-threatens-guest test: a hostile host NPC fleet near the guest triggers `ENGAGE_GUEST` and the guest pilots that battle locally.
- [ ] Run both-players-in-one-battle test: trigger a single engagement that includes both player fleets and confirm the host pilots the combined battle while the guest spectates (guest fleet host/AI-controlled), with results reconciling back.
- [ ] Run either-player-disconnects-mid-combat test: engaging side finishes the battle locally and logs the discarded unsent result; spectator side closes the panel and ends the session cleanly; next session reconciles (resurrected NPC logged).
- [ ] Run combat result reconciliation test: solo fighter keeps own spoils, destroyed NPC fleets disappear on both clients, shared rep converges.
- [ ] Run fleet-wipe respawn test (Phase 17): deliberate full wipe in both directions → the vanilla shuttle respawn fires, the partner sees the `RESPAWN_PLAYER` banner with the destination, the partner's log shows no empty-roster commit and no mirror despawn/recreate churn, and skills/officers/rep survive.
- [ ] Run coordinated-save/resume test: host save triggers guest autosave; quit both; resume the session from the two saves (seed lock + campaign UUID accept, mirrors rebuilt).
- [ ] Run raid test (Phase 24 M1): each player raids the same NPC colony once; disruption/stability/rep converge on both clients, loot stays with the raider.
- [ ] Run colony lifecycle test (Phase 24 M2): one player colonizes; the colony exists with matching size/conditions/industries on both clients and survives the coordinated save/resume cycle.
- [ ] Run colony management + income test (Phase 24 M3): guest builds an industry → host sees it under construction; the interaction gate blocks simultaneous colony-screen access; a monthly tick splits income 50/50 with the report line on both clients; an incoming expedition shows the mirrored warning intel on the guest.
- [ ] Run disconnect test (clean session end) **and** reconnect-grace test (Phase 20.2): kill the link ~30 s mid-session → both clients show the reconnect dialog (not log-only), world held paused, session resumes with a full rebroadcast.
- [ ] Re-run/verify the Phase 20 WAN matrix results (the matrix itself is built and first run in Phase 20: clumsy-shaped loopback latency/jitter/loss/reorder, 30 s outage → reconnect grace, UDP-block → TCP fallback) plus one real-Internet or VPN session; record the connection-doctor tier and RTT/loss in `QA_V1.md`.
- [ ] Record results in `QA_V1.md`.
- [ ] Commit with `git add mods/coop && git commit -m "test: add coop v1 qa checklist"`.

**Acceptance:**

- `QA_V1.md` has a dated run with pass/fail for every v1 smoke test.
- All failures marked blocking have been fixed or have an exact API blocker documented with file/path evidence.

## Phase 20: Connectivity Hardening (LAN → Internet)

> **Goal:** the same coop session that works on localhost works reliably between two real households over the Internet, with no game-visible behavior change. Stretch: assess 3+ players (host + up to 3 guests) and make the wire format N-ready now so expansion later is not a breaking change. Guaranteed fallback if direct connectivity fails: VPN pseudo-LAN (zero code — the stack binds ordinary sockets). Direct connectivity is the preferred path and gets the engineering effort.
>
> **Ordering note:** implement Phase 20 *before* the final Phase 19 sign-off run; Phase 19's checklist gains a WAN line that depends on this phase.
>
> **Inherited item from Phase 10 (added 2026-08-09; re-homed 2026-08-20):** the forced-close of the already-open dialog on `INTERACTION_REJECT` is now owned by the rescoped Phase 18, which lands before this phase and includes a localhost latency lever for reproducing the race. Phase 20's remaining duty here: exercise the claim race at real WAN RTT in its matrix, and Phase 19 carries the checklist line.

**Verified transport facts (2026-06-10, from `CoopNetService.java` / `CoopNetPump.java` / `CoopMessages.java`; re-verified against HEAD `eb2e90c` on 2026-08-25 — items that Phase 29 M1 or the QA-pass commits made stale are corrected in place, marked with the new date):**

- Topology is already a star: guests connect outbound to the host (TCP) and send the first UDP datagram (guest binds ephemeral, host learns the return address from received traffic). **Only the host needs to be reachable from the Internet** — guests behind any NAT work as-is, because outbound TCP and guest-initiated UDP punch their own NAT pinholes. This is the single biggest thing the design already got right for Internet play.
- `MAX_DATAGRAM_BYTES = 60 * 1024` (CoopNetService.java:32). 60 KB UDP datagrams survive loopback/LAN via IP fragmentation but fragmented UDP is routinely dropped by Internet NATs/firewalls. Anything over ~1,200 bytes of payload is unsafe on WAN.
- *(2026-08-25)* The datagram envelope is no longer flat `(sessionId, type, body)`: Phase 29 M1 (commits `0e1871a`, `9e4f935`) reshaped it to `sessionId` + type + N repeating `(epoch, sentGameTimeMillis, body)` sections (CoopMessages.java:573-604), with depth-1 redundancy (`CoopDatagramRedundancy` re-sends the previous section alongside the current one) and a working drop-stale watermark in the drain path (`CoopDatagramWatermark`, session-scoped: a new sessionId clears the table). Still missing, and still 20.1's to add: `senderId` on the wire, and watermark keying per `(senderId, type)` instead of per-type. Redundancy doubles the sections per datagram, so the MTU budget applies to the *composed* datagram, not one body.
- *(2026-08-25)* Host UDP address learning is no longer from **any** packet: commit `2a77506` pins the accepted UDP source to the TCP channel's `InetAddress` and locks the full address+port after the first accepted datagram (CoopNetService.java:266-284, 312-324), with re-learn allowed after each TCP re-attach. Residual gap: before the first datagram of a connection, any packet from the pinned *address* (loopback, shared NAT) can set the return port; the transport never parses the payload, so no sessionId check happens at learn time. The 20.1 challenge-echo item closes both.
- TCP guest reconnect already retries every 500 ms forever (CoopNetService.java:33, `scheduleConnectRetryLocked`) — the *transport* survives a blip; it is the *session* layer that currently treats disconnect as session end (full lobby → handshake → seed-lock re-run on the new socket).
- *(2026-08-25)* `PING` is sent guest-only every 3 s (`PING_INTERVAL_MILLIS`, CoopNetPump.java:59) and the host answers with `PONG` — but no code handles the `PONG` (it falls through to a replicator no-op), so there is still no RTT/loss measurement, no UDP keepalive, and no link-death detection feeding the session layer. Today's ping is half-open-socket detection only.
- *(2026-08-25)* Only 2 of 44 message types travel UDP: `FLEET_SNAPSHOT` (guest↔host) and `NPC_FLEET_MOTION` (host→guest) — the only `sendDatagram` call sites. Everything else, including the large `NPC_FLEET_SET`, is TCP. Chunking and the TCP fallback therefore touch exactly two streams.
- TCP outbound is an unbounded `ConcurrentLinkedQueue` with a single pending-write buffer — a slow WAN consumer backs up snapshot traffic without coalescing.
- Single-guest couplings: one `activeChannel` + one `udpRemoteAddress` + explicit extra-connection reject (CoopNetService.java:21, 243-251); `CoopNetPump` filters on a single `sessionState.remotePlayerId()` (CoopNetPump.java:904). The envelope has no `senderId`.
- Sandbox check for everything this phase needs: `java.net`/`java.nio` sockets (incl. multicast send) are proven legal; `java.util.zip` (`Deflater`/`Inflater`) is not on the classloader denylist; no file I/O and no XML library is required (UPnP SOAP is hand-rolled strings). Nothing in this phase touches `java.io.*`/`java.lang.reflect`.

**External research pass (2026-08-25; 22 sources, 25 claims each adversarially verified by 3 independent agents, 3 claims refuted):**

- **Confirmed as designed:** the ~1,200 B payload budget (RFC 9000 sets 1200 B as QUIC's minimum datagram, derived from the IPv6 1280 B MTU floor; netcode.io caps payloads at 1200 B; Gaffer On Games records 1000–1200 B as what shipped games assume instead of path-MTU discovery). Self-contained chunk datagrams over app-layer reassembly (RFC 8900 "IP Fragmentation Considered Fragile"; at 1% loss a 256-fragment snapshot is lost 92.4% of the time, and any lost fragment discards the whole snapshot). Drop-stale watermarks as the standard latest-wins pattern (Gaffer snapshot interpolation; Quake 3: lost state is "too old anyway" to resend). UDP-primary with automatic TCP fallback (callstats.io WebRTC telemetry: ~9% of sessions could not use UDP at all — 2016 figures, corroborated by current TURN-over-TCP guidance; TCP head-of-line blocking is mitigated exactly by the bounded-queue + newest-wins coalescing below). The CGNAT tier logic (no port-mapping protocol reaches a carrier NAT, so detection + IPv6/VPN fallback is the correct remedy). Factorio-style full-state rebroadcast as the shipped-game resume pattern (FFF-188: a desynced client re-downloads the full game state, then rejoins the same session).
- **Refinement adopted into 20.1:** "validated" address re-learning should mean a challenge-echo, not a session-id match. QUIC validates a migrated path with `PATH_CHALLENGE` (8 unpredictable bytes the peer must echo; traffic to the unvalidated address is rate-limited, RFC 9000 §8.2/§9.3). A plaintext session id is visible to any on-path observer and replayable from a different source; a fresh nonce is not. See the revised return-address bullet.
- **Refinement adopted into 20.4:** the bar set by production game-networking stacks (netcode.io: ChaCha20-Poly1305 per packet; Valve GameNetworkingSockets: AES-GCM-256; QUIC/WebRTC: mandatory AEAD) is per-packet authenticated encryption; a bearer session id is below it. Deferring that stays defensible for a private 2-player session on a random high port, on three conditions now written into 20.4: the password hash is a join gate only, address re-learning uses the challenge-echo, and per-packet AEAD is recorded in the Maybe list as the upgrade required before public release.
- **Cadence check:** a 10 s UDP keepalive is under the RFC 6263/ICE 15 s default and under most measured NAT timeouts (74% ≥ 60 s in a 34-gateway study), but the same literature records a 10 s timeout floor on at least one device — 10 s sits on the tail, not below it. The probe moves to ~5 s idle cadence. Watermark epoch wraparound needs nothing (epochs are Java longs; 32-bit counters already take years to wrap at snapshot rates). Watermark reset on reconnect — the one pitfall the literature flags — is already handled by session-scoping in `CoopDatagramWatermark`, and a 20.2 resume keeps both the sessionId and the guest process, so epoch continuity holds across resume.
- **Caveat pinned for Phase 29:** drop-stale-and-supersede is only correct while snapshots are full-state. If M2+ ever moves to delta-compressed snapshots, ack-baseline machinery (the Quake 3 model) becomes mandatory, not optional.
- **New Java fact for 20.1:** the JDK documents `PortUnreachableException` for *connected* datagram sockets only, and explicitly does not guarantee it even there; on Windows an ICMP port-unreachable can surface as a plain `SocketException` (JDK-4676710). The UDP path must treat both as transient link events, never as fatal.
- **Where research came back empty** — exactly the items the plan already gates behind spikes, so the spike-first structure stands: no measured in-the-wild UPnP `AddPortMapping` success rate (and the claim that routers commonly ship with UPnP disabled was refuted 0-3 — assume neither), no adjudicated evidence on clumsy/WinDivert or representative WAN shaping parameters, nothing on per-datagram Deflate value at ~1,200 B budgets, nothing on Windows dual-stack NIO binds.

### Phase 20 — Transport correctness over WAN (20.1)

The state stream must tolerate latency (50–250 ms), jitter, ~1–3% loss, and reordering without game-visible artifacts.

- **MTU-safe datagrams.** Target ≤ 1,200 bytes of UDP payload. `NPC_FLEET_MOTION` is a batch — split it into multiple self-contained chunk datagrams (each decodes and applies independently). Measure first: add a one-session datagram-size histogram to the log (spike), then set the chunk size from data. If a single logical snapshot (e.g. a 30-ship `FLEET_SNAPSHOT`) exceeds the budget, deflate it (`java.util.zip.Deflater`, sandbox-legal); if it still exceeds ~1,400 bytes, route that message over TCP instead — the reliable path always exists. *(2026-08-25: the M1 redundancy layer packs previous+current sections into one datagram, so the histogram and the chunk budget must be measured on composed datagrams, roughly halving the per-body budget; and only `FLEET_SNAPSHOT` + `NPC_FLEET_MOTION` travel UDP at all, so this bullet's scope is exactly those two streams.)*
- **Epoch guard against reordering.** Add `senderId` + `epoch` (per-sender monotonic snapshot tick) to the datagram envelope. All chunks of one tick share an epoch. Receiver keeps a per-`(senderId, type)` watermark and drops datagrams with `epoch < watermark` (accept equal — chunks), then advances it. Loss needs no handling beyond this: every stream is latest-wins and the next tick supersedes. *(Revised 2026-08-24; landed-state check 2026-08-25: M1 shipped `epoch` + `sentGameTimeMillis` per section, the per-type session-scoped watermark (`CoopDatagramWatermark`), and depth-1 redundancy. What remains for 20.1 is `senderId`, chunking, and the per-`(senderId, type)` keying. Research verdicts: this is the standard latest-wins pattern (Gaffer, Quake 3); wraparound needs nothing at long width; the reset-on-reconnect pitfall is covered by session scoping, and a 20.2 resume keeps the sessionId so continuity holds.)*
- **Validated UDP return address (revised 2026-08-25).** The transport already pins the accepted UDP source to the TCP peer's `InetAddress` and locks the port after the first accepted datagram (commit `2a77506`). Two things remain. (1) Parse the envelope far enough to check `sessionId` *before* any address learning (`CoopNetService.setExpectedSessionId(...)` after handshake; drop non-matching with a counter). (2) Treat a **new** source address for a known session as unproven until a challenge-echo passes: send a one-shot random-nonce datagram (`PATH_PROBE`) to the new address and re-point only when the nonce comes back — the QUIC `PATH_CHALLENGE` model (RFC 9000 §8.2). A plaintext sessionId is sniffable on-path and replayable from a different source; a fresh nonce is not. Until validation completes, keep sending to the last validated address. This is what survives NAT rebinds mid-session without opening a traffic-redirect hole.
- **Keepalive + link supervision (cadences revised 2026-08-25).** UDP keepalive datagram (`UDP_PROBE`) every ~5 s each direction when idle — measured NAT UDP timeouts are mostly ≥ 60 s but the literature records a 10 s floor on at least one device, so the old ~10 s figure sat on the tail rather than under it. TCP side: the guest already pings every 3 s and the host already answers; the missing half is the `PONG` handler. Add it, add a symmetric host-side `PING` at the same 3 s cadence, and derive RTT EWMA there plus a loss estimate from datagram epoch gaps; new `LINK_STATUS` TCP message (each ~5 s) reports what each side is receiving. Link declared degraded/dead on thresholds; dead feeds the Phase 20.2 reconnect grace instead of instant session teardown.
- **ICMP tolerance on the UDP channel (added 2026-08-25).** The JDK documents `PortUnreachableException` only for connected datagram sockets and does not guarantee it even there; Windows can surface an ICMP port-unreachable as a plain `SocketException` (JDK-4676710). Wrap the UDP send/receive path so both exception types count as a transient link event: log once, keep the channel open, and let the keepalive/`LINK_STATUS` machinery decide link death. A stray ICMP burst (peer rebooting, router resetting) must not kill the socket loop.
- **UDP-blocked fallback (committed, not optional).** Some networks pass TCP but eat UDP. If the host sees no inbound UDP for 10 s while TCP is alive (known from `LINK_STATUS`), both sides switch the state stream (`FLEET_SNAPSHOT`, `NPC_FLEET_MOTION`) onto TCP at a reduced 5 Hz, keep probing UDP every 30 s, and switch back on recovery. Today this failure mode is *silent mirror freeze* — after this phase it is a logged, working degraded mode. *(Phase 29 M2 later generalizes this fixed 5 Hz reduction into the floor tier of adaptive cadence — one mechanism.)*
- **TCP backpressure.** Bound the outbound queue; when backlogged, coalesce superseded snapshot-type messages (keep only the newest `TIME_SNAPSHOT` / `NPC_FLEET_SET` / `PLAYER_REP_SNAPSHOT` / `MISSION_POOL_SNAPSHOT`), never coalesce semantic events (claims, deltas, results, world deltas, lifecycle).
- **Latency-tolerance audit** of every request/response or timing-coupled path at 200 ms RTT + 2% loss: interaction-gate claims (add a "waiting for partner" affordance rather than a stall), market open-snapshot, `PAUSE_INTENT` debounce, connect-time pause hold, and — cross-reference Phase 14 — the **pre-contact `ENGAGE_GUEST` handoff threshold must be derived in time-to-contact terms ≥ 2 × p95 RTT + processing margin**, not as a loopback-tuned distance.

**Payload diet (added 2026-08-25 — second research pass, datagram format; user decisions same day).** Byte-level measurement of the actual encoders against a real save showed the MTU problem is not hypothetical: a 30-ship `FLEET_SNAPSHOT` composes to ~4.3–5.4 KB (3–4 IP fragments per datagram, 10×/s; at 1% link loss that is ~4% effective datagram loss because losing any fragment loses the whole datagram), a motion record is 81 B so only ~7 fleets fit a 1,200 B composed datagram, and 63–95% of the fleet-snapshot body is immutable data the receiving mirror discards on 9 of 10 ticks (ids, hull/variant/name strings, and a 64-char SHA-256 hash — `CoopFleetMirror.refreshRosterIfChanged` reads only `cr`/`hullFraction` when the hash is unchanged). The remedies below are content changes; the text format stays (canon verdict: text with readable logs is a legitimate end state — Fiedler's own binary-migration criterion "if we wish to optimize any further" is not met after this diet, and binary would cost the log-diff debugging workflow for headroom we don't need). Two items were pulled forward and landed 2026-08-25, ahead of the phase (user decision): wire quantization and the wiretap diagnostic.

- **Roster split (the big one — Tribes "datablocks" / Quake 3 baselines / Source `instancebaseline` pattern).** `FLEET_SNAPSHOT` on UDP shrinks to the volatile tick: header motion/sensor/transponder fields + `fleetHash` + per-ship `{memberId, cr, hullFraction}` (~13–20 B/ship instead of 64–129 B). The immutable roster (hullId, variantId, names, captain, d-mods/s-mods) moves to a new TCP `FLEET_ROSTER` message sent on `fleetHash` change, at session start, and in the resume rebroadcast — the exact shape `NPC_FLEET_SET` (1 Hz TCP) already uses for NPC fleets. Receiver keeps its last roster when the tick's hash doesn't match and applies cr/hull by memberId until the roster arrives. Truncate `fleetHash` on the wire to 16 hex chars (change detection for one fleet's roster needs no more).
- **Motion range filter (user decision 2026-08-25).** Stream `NPC_FLEET_MOTION` only for fleets within a radius of a player position (derived from max sensor detection range with a generous margin — detection is observer-strength × target-profile, so derive from the engine's `getMaxSensorRangeToDetect` worst case, not a constant); distant fleets update from the 1 Hz `NPC_FLEET_SET` only. A fleet neither player can detect does not need 10 Hz motion. This also bounds the currently uncapped hyperspace case (one player in hyperspace makes every in-transit fleet sector-wide eligible today — 100+ fleets, no cap anywhere).
- **Sensor change-flags (ack-free delta).** The five sensor floats are ~37% of each motion record and piecewise-constant (they must stay on the 10 Hz path — abilities/terrain swing them within a second — but the *values* rarely change tick-to-tick). Add a per-fleet changed/unchanged mask against the previous section, which the M1 redundancy layer already colocates *in the same datagram* — so the delta baseline can never be lost separately from its delta. This is the ack-free half of delta compression; the general acked-baseline machinery (Quake 3 model) stays out of scope.
- **Short datagram session token.** The envelope spends 36 B/datagram on the UUID sessionId (111 B total envelope ≈ 9% of budget). Replace it on *datagrams only* with a 16-hex (64-bit) token derived from the full id at lobby time; the full UUID stays on TCP. 64 bits keeps blind spoofing infeasible for the drop-foreign-traffic role, and address hijack is covered by the challenge-echo regardless. Rides the `senderId` envelope change.
- **Wire quantization (LANDED 2026-08-25, ahead of phase).** Floats were serialized via `Float.toString` at full shortest-round-trip precision ("0.84999996", 8–11 chars). Quantize at the serialization boundary only: positions/velocities to 0.25 su (exact binary fraction → short strings; Source ships multiplayer origins at 1/8 unit for a hitscan shooter, and our mirrors interpolate 100 ms-apart samples), cr/hull to 0.001, sensor ranges to 0.1, mult to 0.001. Hash inputs stay full-precision (the rounding-in-hash rebuild-storm history stands). Bonus: logs get *more* readable.
- **Wiretap diagnostic (LANDED 2026-08-25, ahead of phase; user decision on shape).** `-Dcoop.debug.wiretap=true` (+ `coop.debug.wiretapSample=N`, default 10): logs sampled decoded payload plaintext on send and receive (newlines made visible, one log line per datagram, host/guest log-diffable) plus per-type composed-size statistics with buckets around the 1,200 B budget and 1,472 B Ethernet-fragmentation line, summarized every 60 s. **This IS the 20.1 datagram-size-histogram spike instrument** — payloads are text at the encode/decode boundary regardless of what later happens to the wire, so the dev logging workflow survives any future wire change.
- **Compression: conditional, not committed (user decision 2026-08-25).** Recipe recorded so it isn't re-researched: raw Deflate (`nowrap=true`, zero framing overhead), one long-lived `Deflater` + `reset()` per datagram (`end()` on shutdown), one-shot `finish()` (no SYNC_FLUSH), level 6 (CPU ~0.05% of a core at these rates), preset dictionary built from the field/id vocabulary (documented 30–50% on sub-1KB repetitive text — the substitute for the shared context lossy UDP forbids), applied to the *composed* datagram so LZ77 dedupes the near-identical previous section to ~1–3% of its size (32 KB window vs 1.2 KB datagram). Known trap: in raw mode `Inflater.needsDictionary()` never fires — set the dictionary unconditionally after `reset()`, and round-trip unit-test the raw+dictionary combination before building on it. Counter-evidence on file: naive gzip *grows* sub-1KB single-section payloads, and compression cannot recover what quantization deletes — which is why the content fixes above come first. **Implement only if the wiretap histogram still shows composed sizes near the budget after the diet.** Hand-rolled intra-datagram deltas and parity FEC are rejected outright (dominated: more code and a new bug class in the loss path vs ~10–15 bytes of LZ77 back-reference).

### Phase 20 — Session continuity: in-session reconnect grace (20.2)

> Revises the "No reconnect/resume" non-goal (2026-06-10): WAN blips are routine, so a dropped *socket* must not end the session. What stays out of scope is relaunch-from-save rejoin — the grace window only serves a guest whose *process is still alive* (campaign still loaded, only the link died).

- On unexpected TCP loss the host enters `RECONNECT_WAIT`: hold the shared clock paused, show a **reconnect dialog** (`CampaignUIAPI.showConfirmDialog` — verified public — with a live countdown via the 20.6 surfacing hooks and an "End session" button; the bare-banner fallback is `addMessage`), keep all session state, and accept a resume for `coop.reconnectGraceSeconds` (default 60). The guest side shows the matching "connection lost — reconnecting…" dialog over its existing 500 ms socket retry.
- Guest's existing 500 ms socket retry reconnects; it then sends `SESSION_RESUME_REQUEST` (sessionId + playerId). Host validates both and answers `SESSION_RESUME_ACCEPT`, then **forces the full rebroadcast backstop immediately** (TIME_SNAPSHOT, NPC_FLEET_SET, PLAYER_REP_SNAPSHOT, mission pool, faction relations) — the architecture is snapshot-heavy precisely so resume is "rebroadcast everything," the same flow as session start after seed lock. No save/load is involved.
- Grace expiry or a resume with the wrong sessionId → clean session end exactly as today.
- UDP after resume re-registers itself via the first validated datagram (20.1 address learning).
- Synergy with Phase 15: a battle in progress during the drop already finishes locally; with grace reconnect, the `BATTLE_RESULT` now usually arrives *in the same session* instead of the next-session reconciliation path.

### Phase 20 — Reachability: making the host connectable (20.3)

Tiered, all tiers shippable independently; the connection doctor (below) tells the host which tier they ended up on.

- **Tier 0 — VPN pseudo-LAN (guaranteed fallback, zero code).** Tailscale (recommended: free, WireGuard, does its own NAT traversal incl. relay fallback), ZeroTier, or Radmin. Document in `docs/CONNECTIVITY.md`. This is also the v1 answer for confidentiality (traffic is otherwise plaintext — see 20.4).
- **Tier 1 — IPv6 direct.** Many residential connections have public IPv6 with no NAT (only a firewall allow). Verify the wildcard bind is dual-stack (Java NIO default on Windows with `java.net.preferIPv4Stack` unset) and that `coop.connectHost` accepts an IPv6 literal; document the host-side firewall rule. Spike-first: confirm dual-stack on the actual test machines.
- **Tier 2 — Manual IPv4 port-forward.** TCP+UDP on the same port; router walkthrough + verification procedure in the doc. Always works when the host has router access and a real public IP.
- **Tier 3 — Automatic port mapping (`coop.net.CoopPortMapper`).** UPnP IGD as primary: SSDP `M-SEARCH` (send to 239.255.255.250:1900, listen for unicast responses — no multicast join needed), fetch the device descriptor over HTTP, call `AddPortMapping`/`DeletePortMapping` via hand-rolled SOAP strings; renew the lease periodically; release on shutdown; log the external `IP:port` for the host to share. NAT-PMP/PCP (trivial binary UDP) as secondary against the SSDP-discovered gateway address. **CGNAT detection:** if the mapped external address is private or in `100.64.0.0/10`, log loudly "CGNAT — direct IPv4 impossible; use IPv6 or VPN." Spike-first against the actual test routers; failure of this tier degrades to Tier 2/0 instructions, never blocks the session.
- **Tier 4 — NOT in v1:** rendezvous server for UDP hole punching, relay infrastructure. Requires hosted infra + ops; recorded in Maybe. With Tiers 0–3 plus the star topology (only the host needs reachability), the realistic coverage gap is small.
- **Connection doctor.** Host at session start logs: local v4/v6 addresses, mapping tier reached, external endpoint, CGNAT verdict. Guest on connect logs: TCP ok, UDP path ok (first `UDP_PROBE` acked) or "UDP blocked — TCP fallback active," RTT. One glance at either log answers "why can't we connect / why is it choppy."

### Phase 20 — Security minimum for an Internet-open port (20.4)

An open game port *will* be scanned. Scope is gatekeeping, not cryptography — the VPN tier is the confidentiality answer; TLS is deferred (cert UX is heavy, `SSLEngine` viability unproven in-sandbox). *(Research check 2026-08-25: deferring per-packet crypto is defensible at this threat model — a private 2-player session on a random high port — but the bar production stacks set for Internet-open game traffic is per-packet AEAD (netcode.io ChaCha20-Poly1305, Valve GameNetworkingSockets AES-GCM-256, QUIC). That upgrade is recorded in the Maybe list as required before public release; within v1, the three bullets below plus the 20.1 challenge-echo are the floor.)*

- Optional lobby password `coop.password`: guest sends `SHA-256(password + provisionalLobbyId)` in the lobby exchange; mismatch → existing `LOBBY_REJECT`. Explicitly documented as plaintext-protocol gatekeeping, not encryption.
- UDP: drop every datagram whose `sessionId` doesn't match the active session (the 128-bit session id acts as a bearer token); never re-point the return address without the 20.1 challenge-echo — a matching sessionId alone is not validation, because it is sniffable on-path.
- Rate-limit and log connection attempts and invalid frames (the tolerant frame decoder already survives garbage; add counters so abuse is visible, and cap inbound rate so a flood can't starve the pump).

### Phase 20 — Multi-guest assessment + N-ready wire format (20.5, stretch)

**Feasibility verdict: YES — up to 3 guests is architecturally sound, and cheap to leave room for.** The host-authoritative star generalizes naturally: guests never talk to each other (no mesh NAT problems — the host relays each guest's `FLEET_SNAPSHOT` to the others), reachability stays host-only, and the heavy machinery is already N-safe by construction: interaction gate and mission claims are first-come protocols; market locks are keyed per `<marketId>:<submarketSpecId>`; the mirror registry is keyed by id; rebroadcast backstops don't care how many listeners there are. Bandwidth at 10 Hz JSON × 4 players is trivial.

Single-guest couplings that would need work (verified in code, see facts block): the one-peer `CoopNetService`, `remotePlayerId()` filtering, the two-intent pause OR (→ OR over a set), `ENGAGE_GUEST` arbitration when two guests near the same NPC (host picks one, same first-claim shape as the interaction gate), both-players-in-one-battle generalization, N-way coordinated saves, and — the real cost — the QA matrix.

**v1 commitment (this phase): N-ready protocol, single-guest gameplay.**

- Add `senderId` to the TCP envelope and the datagram envelope (the wire format is the thing that's breaking to change later; ship it now).
- While `CoopNetService` is open for 20.1 surgery anyway, extract the per-peer state (channel, UDP address, queues, watermarks, link quality) into a `CoopPeerLink`; the service holds a peer table with capacity `coop.maxGuests`, **default and v1-enforced 1**. Routing distinguishes broadcast vs unicast (e.g. `MARKET_SNAPSHOT` answers only the opener).
- Actual >1 guest enablement is a post-v1 phase — **now numbered as Phase 27 (2026-06-10)** — gameplay arbitration and the QA matrix are where the real work lives, not the transport.

### Phase 20 — Link-quality surfacing in-game (20.6, pulled forward from Phase 21)

Two small UI pieces ride with this phase **deliberately**: they double as instrumentation for the WAN QA matrix (watching RTT/loss in-game during shaped-loopback runs beats tailing logs). Both use official API surfaces verified against the 0.98a API sources on 2026-06-10 (see Phase 21's UI-control facts block for the full inventory):

- **Ping/link HUD (`coop.ui.CoopLinkHud`).** A `CampaignUIRenderingListener` (sanctioned every-frame screen-space hook: `renderInUICoordsAboveUIBelowTooltips`) drawing a small display-only widget — RTT ms, loss %, and a state icon (UDP-ok / TCP-fallback / reconnecting). Raw OpenGL in UI coords; no widgets, no input — which is all this needs. Combat gets the same number via the existing battle-status panel rather than a second combat render hook.
- **"Coop Session" intel entry (`coop.ui.CoopSessionIntel`).** A permanent `IntelInfoPlugin` with `hasLargeDescription()`/`createLargeDescription(CustomPanelAPI, w, h)`: connected players, per-link RTT/loss history, reachability tier from the connection doctor, current transport mode. Fed by `LINK_STATUS` (already emitted every ~5 s by 20.1 — the data source exists by construction).
- **Connection-event feed messages.** Every logged link transition (degraded, UDP→TCP fallback, recovery, reconnect grace entered/resumed) also emits a colored `CampaignUIAPI.addMessage` — the doctor log's in-game echo.
- **Integration rule:** the reconnect/lobby dialogs are *exclusive* (`showInteractionDialog` returns false if another dialog is up) and must register with the same suspend logic as `CoopCampaignInputBlocker` (the trapped-guest fix) — a coop dialog must never be blocked by, or block, the interaction gate.

**Agent prompt:**

```text
Implement Phase 20 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Harden the coop transport for Internet play: MTU-safe sequenced UDP with validated return addresses and keepalives, TCP fallback for a blocked UDP path, bounded/coalescing TCP outbound, in-session reconnect grace (with reconnect dialog), UPnP/NAT-PMP port mapping with a connection doctor, optional lobby password, an N-ready envelope (senderId + CoopPeerLink, capacity still 1), and the 20.6 link-quality surfacing (ping HUD, session intel entry, feed messages) as QA instrumentation. Spike-first items must be proven before the dependent step. Run the clumsy-shaped loopback QA matrix. Stability over features; no gameplay behavior changes.
```

**Files:**

- Modify `src/main/java/coop/net/CoopNetService.java` (datagram size cap, epoch envelope, validated address learning, keepalives, peer-link extraction)
- Create `src/main/java/coop/net/CoopPeerLink.java`
- Create `src/main/java/coop/net/CoopLinkQuality.java` (RTT/loss tracking + `LINK_STATUS` + UDP-fallback decision)
- Create `src/main/java/coop/net/CoopReconnectCoordinator.java` (grace window + `SESSION_RESUME_*` handling)
- Create `src/main/java/coop/net/CoopPortMapper.java` (SSDP/UPnP IGD + NAT-PMP/PCP + CGNAT detection)
- Create `src/main/java/coop/ui/CoopLinkHud.java` (20.6: `CampaignUIRenderingListener` ping/state widget)
- Create `src/main/java/coop/ui/CoopSessionIntel.java` (20.6: "Coop Session" intel entry fed by `LINK_STATUS`)
- Modify `src/main/java/coop/net/CoopMessages.java` (`senderId` + 16-hex datagram session token; datagram `epoch` already landed with Phase 29 M1; new types `UDP_PROBE`, `PATH_PROBE`, `LINK_STATUS`, `FLEET_ROSTER`, `SESSION_RESUME_REQUEST/ACCEPT/REJECT`)
- Modify `src/main/java/coop/fleet/CoopFleetSnapshot.java` + `CoopFleetMirror.java` (payload diet: volatile-tick snapshot + `FLEET_ROSTER` split, hash-mismatch hold) and `coop/fleet/CoopNpcFleetReplicator.java` + `CoopNpcFleetMotion.java` (range filter, sensor change-mask)
- Modify `src/main/java/coop/net/CoopNetPump.java` (epoch watermarks, TCP-fallback routing, resume rebroadcast trigger)
- Modify `src/main/java/coop/net/CoopNetStartupConfig.java` (`coop.password`, `coop.maxGuests`, `coop.reconnectGraceSeconds`, `coop.portMapping`)
- Create `mods/coop/docs/CONNECTIVITY.md` (tiers, router/VPN walkthroughs, connection-doctor interpretation, troubleshooting)
- Unit tests for every new/modified class (epoch guard, coalescing, resume state machine, password gate, port-mapper message encoding)

**Steps:**

- [ ] **Spike: datagram size histogram.** One busy-system session on loopback; log the size distribution of `FLEET_SNAPSHOT` and `NPC_FLEET_MOTION` datagrams **as composed on the wire** (the M1 redundancy layer packs previous+current sections, roughly doubling each datagram). Pick the chunk size / decide whether the conditional compression layer is needed from data. *(Instrument LANDED 2026-08-25: the wiretap diagnostic below — run the session with `-Dcoop.debug.wiretap=true` and read the 60 s summaries.)*
- [x] **Wire quantization** (pulled forward, LANDED 2026-08-25): positions/velocities to 0.25 su, cr/hull to 0.001, sensor ranges to 0.1, mult to 0.001, at the serialization boundary only; hash inputs untouched.
- [x] **Wiretap diagnostic** (pulled forward, LANDED 2026-08-25): `-Dcoop.debug.wiretap` + `coop.debug.wiretapSample` — sampled plaintext payload logging both directions + per-type composed-size histogram summaries.
- [ ] **Roster split:** shrink `FLEET_SNAPSHOT` to the volatile tick (motion/sensor header + 16-hex `fleetHash` + per-ship memberId/cr/hull); new TCP `FLEET_ROSTER` on hash change, at session start, and in the resume rebroadcast; receiver holds last roster on hash mismatch until the roster lands. Unit-test the hash-mismatch window and the resume path.
- [ ] **Motion range filter:** stream `NPC_FLEET_MOTION` only for fleets within the derived detection radius (+margin) of a player position; distant fleets ride the 1 Hz set. Verify the hyperspace case is bounded and that a fleet crossing the radius edge starts moving smoothly (it enters with set-fed samples already buffered).
- [ ] **Sensor change-flags:** per-fleet changed/unchanged mask against the previous colocated section; unit-test the mask against a section-1-lost/section-2-applied datagram (mask must only ever reference its own datagram's baseline).
- [ ] **Spike: clumsy on loopback.** Verify clumsy (WinDivert) can shape the two-instance loopback setup (lag/jitter/drop/reorder on the coop port). This is the test harness for the whole phase.
- [ ] **Spike: UPnP `AddPortMapping` against the real test router(s);** record router model + result. **Spike: dual-stack IPv6 bind** on the test machines.
- [ ] Add `senderId` to the TCP envelope and the datagram envelope (`epoch` + `sentGameTimeMillis` already landed with Phase 29 M1); in the same change, swap the datagram-envelope UUID sessionId for the 16-hex session token (full UUID stays on TCP); bump nothing else (exact-install handshake parity means both ends always speak the same version — no wire versioning needed).
- [ ] Generalize the existing per-type `CoopDatagramWatermark` to per-`(senderId, type)`; extend its unit tests with reorder/duplicate/stale-across-senders cases.
- [ ] Cap UDP payload at the spike-derived budget (target ≤ 1,200 B composed, redundancy included); chunk `NPC_FLEET_MOTION` into self-contained datagrams; deflate-then-TCP escalation for oversized single snapshots.
- [ ] Move host UDP address learning behind sessionId validation; add `setExpectedSessionId`; drop non-matching datagrams with a counter. Add the `PATH_PROBE` nonce challenge-echo: a new source address for a known session is re-pointed to only after it echoes a fresh random nonce; until then traffic keeps flowing to the last validated address.
- [ ] Add `UDP_PROBE` keepalive (~5 s idle cadence both directions); complete the existing 3 s `PING`/`PONG` loop (add the missing `PONG` handler and a host-side `PING`) with RTT EWMA + epoch-gap loss estimate in `CoopLinkQuality`; emit `LINK_STATUS` every ~5 s. Treat `PortUnreachableException`/`SocketException` on the UDP channel as transient link events, never fatal (JDK-4676710).
- [ ] Implement the UDP-blocked TCP fallback (10 s no-inbound-UDP trigger, 5 Hz state stream over TCP, 30 s re-probe, auto-recover) — log every transition.
- [ ] Bound the TCP outbound queue with snapshot coalescing (newest-wins for snapshot types; events never dropped); unit-test the coalescing whitelist.
- [ ] Implement `CoopReconnectCoordinator`: host `RECONNECT_WAIT` (paused world + reconnect dialog with countdown and "End session" option + grace timer), `SESSION_RESUME_REQUEST/ACCEPT/REJECT`, forced full rebroadcast on accept; guest side rides the existing socket retry behind its own "reconnecting…" dialog. Default grace 60 s via `coop.reconnectGraceSeconds`. Both dialogs register with the `CoopCampaignInputBlocker` suspend logic (20.6 integration rule).
- [ ] Implement `CoopPortMapper` (UPnP primary, NAT-PMP/PCP secondary, lease renewal, shutdown release, CGNAT detection) behind `coop.portMapping=auto|off`; failures degrade to documentation pointers, never block hosting.
- [ ] Implement the connection doctor log blocks (host: addresses/tier/external endpoint/CGNAT; guest: TCP/UDP-path/RTT).
- [ ] **20.6 surfacing:** `CoopLinkHud` (`CampaignUIRenderingListener` widget: RTT/loss/transport-state), `CoopSessionIntel` intel entry (players, link history, doctor tier), and colored `addMessage` feed lines on every link transition. Build these *before* the WAN QA matrix so the matrix runs use them as instrumentation.
- [ ] Add optional `coop.password` gate (SHA-256 of password + provisionalLobbyId) into the lobby exchange → `LOBBY_REJECT` on mismatch.
- [ ] Extract `CoopPeerLink`; peer table capacity `coop.maxGuests` default 1 (hard-enforced); broadcast/unicast routing layer; keep the existing extra-connection reject behavior at capacity.
- [ ] **Latency audit:** re-derive the Phase 14 `ENGAGE_GUEST` handoff threshold in time-to-contact terms (≥ 2 × p95 RTT + margin); sweep interaction-gate/market-open/pause paths at 200 ms + 2% loss and fix stalls (add "waiting" affordances where a round-trip is user-visible).
- [ ] **WAN QA matrix on shaped loopback:** (a) 100 ms ± 30 ms jitter + 2% loss, 30-minute session; (b) 5% loss spike; (c) 30 s full outage → reconnect grace resumes the session; (d) UDP-only block → TCP fallback engages and mirrors keep moving; (e) reorder-heavy profile → no stale-snapshot rubber-banding.
- [ ] **Security smoke (20.4):** wrong password → `LOBBY_REJECT`; a UDP datagram with a stale/invalid `sessionId` from a different source address must NOT re-point the stored return address, and neither must one with a *valid* `sessionId` from a new source until its `PATH_PROBE` echo completes (compare the doctor/log endpoint lines before and after); connection attempts are rate-limited.
- [ ] **Real-Internet smoke:** one session between two real networks (or Tailscale if no port-mappable router is available), using `scripts\deploy-to-test-clients.ps1`; record tier reached + RTT/loss from the doctor logs in `docs/CONNECTIVITY.md`.
- [ ] Update `QA_V1.md` / Phase 19 checklist WAN line; commit with `git commit -m "feat: harden coop networking for internet play"`.

**Acceptance:**

- A 30-minute session at 100 ms RTT / 2% loss / jitter / reorder (shaped loopback) shows no stale-snapshot rubber-banding, no mirror freeze, no unbounded queue growth, and no session teardown.
- The wiretap histogram for that session shows **zero composed datagrams above 1,200 B** (post-diet, post-chunking) with a 30-ship fleet and a busy core system in play, and the hyperspace scenario stays bounded by the range filter.
- A 30 s mid-session outage resumes via the grace window with the world paused throughout and a full rebroadcast on resume; grace expiry still ends the session cleanly. Both players see the reconnect dialog (not just a log line), and it neither blocks nor is blocked by the interaction gate.
- RTT/loss/transport-state are visible in-game (HUD widget + "Coop Session" intel entry), and every link transition (fallback, recovery, grace) produces a feed message.
- With UDP blocked entirely, the session runs in logged TCP-fallback mode with moving mirrors.
- Host startup logs a connection-doctor block naming the reachability tier; UPnP success logs a shareable external endpoint; CGNAT is detected and named.
- A real-Internet (or VPN) two-machine session passes the Phase 19 movement/interaction smoke tests.
- Wrong-password and stale-sessionId connections are rejected; unvalidated UDP never re-points the return address — including a valid-sessionId datagram from a new source address before its challenge-echo completes.
- The envelope carries `senderId` end-to-end and `CoopPeerLink` exists with capacity enforcement — and gameplay is byte-for-byte unchanged on localhost (existing unit + smoke tests stay green).

## Phase 21: Multiplayer UX (V1 since 2026-08-24)

> **Pulled into V1, 2026-08-24 (user decision),** motivated by the deferred issue below: the lobby's
> force-pause-until-all-ready is the designated fix for the host-starts-unpaused-before-guest hole,
> and the user wants that fixed inside V1 rather than after it. Slotting decision (same day, user
> choice): the phase stays **whole** and runs **after Phase 20** (order: 20 → 29-M2 → 21 → 19) — a
> split that landed the lobby core early was offered and declined, so the doctor-verdict panel,
> desync dialog, and stats build directly on 20.6's surfaces with no stubs, and the interim answer
> to the unpaused start remains host etiquette. The 19 sign-off still runs last.

> **Goal:** purpose-built multiplayer UI on top of the working session: an in-campaign lobby with ready-up, a connecting screen, a desync dialog, and session stats. **Ordering:** ~~runs *after* V1 acceptance~~ *(superseded by the 2026-08-24 banner — in V1, after 20, before 19)* — none of this touches sim correctness (it is all read-only display + session-start choreography), so it is safe to sequence late without design debt. The two items that earn their keep earlier (ping HUD + session intel entry + reconnect dialog) were pulled forward into Phase 20.6 as QA instrumentation.

**Verified UI-control facts (2026-06-10, from the 0.98a API sources at `tmp_ff_analysis\agentC\api_src`):**

- **Title screen / main menu: NO API.** It is obfuscated code with zero modding surface, and the save-load picker is equally closed. A true pre-load lobby (before any campaign exists) **cannot be in-game UI** — launch configuration stays at the system-property level (`CoopNetStartupConfig`), optionally fronted by an external launcher script later. This is a hard engine limit, not a scoping choice.
- **One-line modals are free:** `CampaignUIAPI.showConfirmDialog(msg, ok, cancel, onOk, onCancel)` and `showMessageDialog(msg)` (CampaignUIAPI.java:98, 133).
- **Full widget panels:** `showInteractionDialog(plugin, target)` can be opened from anywhere (returns false if another dialog is up — *dialogs are exclusive*); inside one, `VisualPanelAPI.showCustomPanel(w, h, plugin)` / `InteractionDialogAPI.showCustomDialog(...)` yield a `CustomPanelAPI` whose `TooltipMakerAPI` has real interactive widgets (`addButton`, paras, images, scrollers) — enough for a lobby with ready-up buttons.
- **Intel screen pages:** `IntelInfoPlugin.hasLargeDescription()` / `createLargeDescription(CustomPanelAPI, w, h)` give a full custom page in the intel tab, plus toast notifications via `addMessage(intel)`.
- **Persistent screen-space HUD:** `CampaignUIRenderingListener` (`renderInUICoordsBelowUI` / `AboveUIBelowTooltips` / `AboveUIAndTooltips`) is a sanctioned every-frame overlay hook — raw OpenGL in UI coords, display-only (no widgets, no input).
- Everything above is official API — **no MethodHandles-into-obfuscated-UI is needed or permitted here** (that route exists but is rejected under the stability rubric; one game patch would break it).

**Deferred issue this phase owns (2026-08-24, user decision):** observed live in the Phase 29 M1
smoke sessions — a host that loads its save before the guest connects **starts unpaused and plays on
alone**; the existing connect-time hold (`maybeHoldHostPausedUntilSessionReady`) covers the
handshake-in-progress window, not the nobody-connected-yet window, so the shared world advances
without the guest. Deliberately NOT patched with another interim pause source: the lobby's
force-pause-until-all-ready below is the real fix, and the first Steps item already reuses the
connect-pause hold for exactly this. Until Phase 21 lands, the workaround is host etiquette (pause
after loading until the guest appears).

**Scope:**

- **In-campaign lobby (`coop.ui.CoopLobbyDialog`).** Host loads the save → world force-paused → lobby interaction dialog: connected players, per-player ready state, the Phase 20 connection-doctor verdict (tier, external endpoint, RTT) rendered with widgets instead of log lines, and a "Start" gated on all-ready. Guest sees the same lobby in read-mostly form after handshake; a guest connecting mid-handshake sees a "connecting…" dialog. Unpause when everyone readies. (The pause machinery, input blocker, and rebroadcast-on-start all exist — this is choreography, not new sync.)
- **Desync dialog.** Hang off the *existing* loud-failure points only (seed-lock fingerprint reject, `SESSION_RESUME_REJECT`, version/mod handshake mismatch): a dialog that names the cause and the fix (re-sync installs / reload from coordinated saves) instead of a log-only session end. No new desync *detection* is built in this phase.
- **Session stats page.** Extend `CoopSessionIntel` (Phase 20.6) with per-session counters the host already observes (battles fought, kills, missions claimed per player, credits earned, distance traveled) — host-tallied, broadcast on the existing snapshot cadence, displayed via `createLargeDescription`. Cosmetic; accept divergence on edge cases.
- **Polish pass** on Phase 20.6 surfaces: HUD widget styling/positioning option (`coop.hudCorner`), intel-entry layout, message colors.

**Integration rule (inherited from 20.6, applies to every dialog here):** dialogs are exclusive and the coop stack already manages dialog state — the lobby/desync dialogs must register with the `CoopCampaignInputBlocker` suspend logic and yield to the interaction gate, or we recreate the trapped-guest bug.

**Agent prompt:**

```text
Implement Phase 21 from COOP_MP_IMPLEMENTATION_PLAN_V1.md. Build the post-V1 multiplayer UX on official API surfaces only (no obf-UI access): in-campaign lobby dialog with ready-up gating session start, desync dialog on existing loud-failure points, session-stats extension of the Coop Session intel entry, and polish of the Phase 20.6 HUD/intel surfaces. Every dialog registers with the CoopCampaignInputBlocker suspend logic. Read-only display + session-start choreography only — no new sync protocols, no sim-affecting changes.
```

**Files:**

- Create `src/main/java/coop/ui/CoopLobbyDialog.java` (lobby `InteractionDialogPlugin` + custom panel)
- Create `src/main/java/coop/ui/CoopDesyncDialog.java` (cause + remedy dialog on loud-failure points)
- Modify `src/main/java/coop/ui/CoopSessionIntel.java` (stats page)
- Modify `src/main/java/coop/net/CoopMessages.java` (`READY_STATE`, `SESSION_STATS` — additive)
- Unit tests (ready-state gating, stats tally, dialog-suspend registration)

**Steps:**

- [ ] `READY_STATE` message + host-side all-ready gate that holds the connect-time pause until everyone readies (reuse the existing connect-pause hold — do not invent a second pause source).
- [ ] `CoopLobbyDialog` host + guest variants (players, ready buttons, doctor verdict panel); shown on `onGameLoad` for a coop session; suspend-logic registration.
- [ ] "Connecting…" dialog for the guest between socket connect and lobby admit.
- [ ] `CoopDesyncDialog` wired to seed-lock reject, resume reject, and handshake mismatch — each names cause + remedy.
- [ ] Host-tallied `SESSION_STATS` broadcast + intel stats page.
- [ ] Polish pass (HUD corner option, colors, layout); update `docs/CONNECTIVITY.md` screenshots/walkthrough.
- [ ] Two-instance QA: lobby ready-up flow, mid-handshake join, every dialog vs. interaction-gate collision matrix; commit with `git commit -m "feat: multiplayer UX (lobby, desync dialog, session stats)"`.

**Acceptance:**

- A coop session starts through the lobby: both players see each other + connection verdict, start is gated on ready-up, and the world stays paused until then.
- Seed-lock reject / resume reject / handshake mismatch each produce a dialog naming cause and remedy (log remains the detailed record).
- Session stats are visible on both clients and survive save/load of the coordinated saves.
- No dialog deadlocks: lobby/desync dialogs never trap a player with the interaction gate or input blocker (collision matrix passes).
- Solo (non-coop) launches show none of this UI and remain byte-for-byte vanilla.

## Phase 22: Co-op Piloted Battles + Tactical-Map Observer (post-V1)

> **Goal:** both players fighting in the *same* battle — the long-deferred "joint combat" — via the architecture identified 2026-06-10: **don't replicate the battle, replicate the pilot.** One combat engine (the host's) runs a real battle containing both players' ships; the guest pilots one of its *own* ships by streaming inputs over the Phase 20 UDP link into a keyboard-driven `ShipAIPlugin`. No PvP — both players are always on the player side against NPCs. **Ordering:** post-V1, after Phase 21; depends on Phase 14 (mirror battles, `BATTLE_RESULT`) and Phase 20 (sequenced UDP). Milestone 0 (the tactical-map observer) is independently valuable, lands first, and is the committed fallback if piloted-co-op latency disappoints.

**Verified facts (2026-06-10, from the MIT-licensed "Cooperative Combat" mod by Nick XR — clone at `tmp_ff_analysis\coopcombat_repo`, v3.10.0, maintained for 0.98a-RC7):**

- **Second-pilot control through official API is proven and maintained:** `CoOpShipAI implements ShipAIPlugin`, assigned with `ship.setShipAI(...)`, polls a keyboard each frame and drives the ship via `ship.giveCommand(...)` / `ship.useSystem()` / `ship.getMouseTarget()`. Up to 3 simultaneous pilots on one engine. MIT license — vendoring with attribution is allowed.
- **Every no-mouse problem is already solved** in that codebase: the active weapon group is forced to aim at `ship.getFacing()` each frame; `NEXT_TARGET`/`PREV_TARGET` cycling drives `setShipTarget`; omni shields are aimed by a helper AI at the nearest threat via `getMouseTarget()`; ship systems target the selected target; per-pilot reticle/flux/cooldown overlays draw in `renderInWorldCoords`.
- **Engine gotchas are documented there and must be preserved:** only one `TOGGLE_AUTOFIRE` command lands per frame (queue them); capture each ship's default autofire state and restore it before `resetDefaultAI()`; `giveCommand` is dead while paused; the AI is retrieved via the `Ship.ShipAIWrapper` cast.
- **The input seam is one line:** `KeyToCommand.shouldGiveCommand()` wraps `Keyboard.isKeyDown(keyIndex)`. Replacing that read with a network-fed virtual key state converts the whole design from shared-keyboard to networked input.
- **What the repo does NOT change:** it contains zero networking (verified by search) and never replicates combat state — one machine simulates everything. The Phase 14 verdict stands: rendered remote combat remains INFEASIBLE, so the guest cannot watch the host's battle through our code.

**Architecture:**

- **One engine, host-side.** The joint battle runs only on the host. The guest's fleet fights as an allied player-side participant via its Phase 14 mirror joining the `BattleAPI` (the engine supports allied fleets in player battles natively). For sessions with this phase enabled, this replaces Phase 14's both-players-in-one-battle stopgap (host pilots everything, guest spectates).
- **Combat-grade fleet manifest — the real new sync work.** The host-side mirror must carry battle-grade truth for the guest fleet: variants (hull + s-mods + weapon groups), officers + skills, CR, hull/armor fractions. `FLEET_MANIFEST` snapshot at battle start (TCP); results return through the existing `BATTLE_RESULT` path and apply to the guest's authoritative fleet.
- **Networked virtual keyboard.** The guest polls its own LWJGL keyboard and streams an action-state record (the 18 pilot actions: held-state bitmap + per-action press counters so toggle commands survive packet loss) over the Phase 20 sequenced UDP channel, on-change up to ~30 Hz, epoch latest-wins. The host feeds it into the vendored pilot AI through a `KeyToCommand`-shaped adapter whose key-read is the virtual state instead of `Keyboard.isKeyDown`. Payload ≤ ~16 B + envelope. This is the one sanctioned UDP combat stream — it carries *inputs*, not state; Phase 14's "no UDP in combat" rule was about state replication and stands.
- **The guest's eyes are out-of-band, and the docs must say so plainly:** piloted co-op requires a video stream of the host's screen (Parsec / Discord / Steam Remote Play). The control scheme survives streaming because it is keyboard-only by design — no mouse aiming to break. Expected control loop = input RTT + stream latency ≈ 100–250 ms: playable in destroyers and up, rough in frigates. Set expectations in the docs and verify in QA at shaped latency.
- **Milestone 0 — tactical-map observer (build first).** *(2026-08-19: Phase 14's `CoopBattleStatusPanel` was deleted — the spectator is banners now — so this milestone builds the panel from scratch instead of upgrading one. The `BATTLE_STATUS` capture/stream/codec/kill-feed it needs were deliberately kept alive and are still fed at 2.5 Hz.)* Build a drawn tactical map: extend `BATTLE_STATUS` with optional position/facing fields (5–10 Hz, still TCP), render ship blips + hull/flux bars with our own GL inside the custom panel (the vendored repo's `PlayerShip`/`TargetedShip`/flux-bar rendering is the cookbook; `CustomUIPanelPlugin.render` is the official hook). No projectiles — an observer doesn't need them. This is also the no-video-stream observer experience during piloted battles.
- **Possession rule:** each player may only possess ships their own fleet contributed (manifest-tagged); the host pilots normally with the mouse, the guest uses the keyboard scheme. Ship-select arbitration reuses the repo's in-use-ship list, keyed by owner.

**Integration rule:** the vendored pilot AI and the tactical panel follow the same suspend/exclusivity discipline as every coop dialog (`CoopCampaignInputBlocker` registration; the tactical panel is an `InteractionDialogPlugin` like the status panel it replaces).

**Agent prompt:**

```text
Implement Phase 22 from COOP_MP_IMPLEMENTATION_PLAN_V1.md (post-V1; requires Phases 14 and 20 complete). Build milestone 0 first (tactical-map observer upgrade of the battle-status panel), then the spikes, then piloted co-op: one battle on the host engine containing both players' ships; the guest pilots its own ship via a networked virtual keyboard (KEY_STATE over sequenced UDP, loss-safe press counters) feeding a vendored, MIT-attributed CoOpShipAI whose keyboard read is replaced by the virtual state. The guest's view is an out-of-band video stream — document that requirement; never attempt combat-state replication (verified infeasible). No PvP: both players always fight on the player side, and each player may only possess their own ships.
```

**Files:**

- Create `src/main/java/coop/combat/CoopRemotePilotAI.java` (vendored `CoOpShipAI` + helpers, MIT attribution header, virtual-key adapter seam)
- Create `src/main/java/coop/combat/CoopVirtualKeyboard.java` (guest-side capture + `KEY_STATE` codec + host-side adapter)
- Create `src/main/java/coop/combat/CoopFleetManifest.java` (combat-grade guest-fleet snapshot + apply-to-mirror)
- Create `src/main/java/coop/combat/CoopTacticalMapPanel.java` (milestone 0: drawn tactical-map observer)
- Create `src/main/java/coop/rewards/CoopRewardSplitter.java` (promoted from the v1 exclusion note — joint battles need a spoils rule)
- Modify `src/main/java/coop/net/CoopMessages.java` (`FLEET_MANIFEST` TCP, `KEY_STATE` UDP, additive `BATTLE_STATUS` position/facing fields)
- Modify `src/main/java/coop/combat/CoopBattleBridge.java` + **create** `CoopBattleStatusPanel.java` (joint-battle lifecycle, tactical panel — *2026-08-19: the Phase 14 panel of that name was deleted, so this is a new file again*)
- Unit tests (`KEY_STATE` codec + press-counter loss recovery, manifest round-trip, possession arbitration, reward split)

**Steps:**

- [ ] **Milestone 0:** additive `BATTLE_STATUS` position/facing fields + `CoopTacticalMapPanel` drawing blips/bars; ships as the observer view for all Phase 14 battles. Independently shippable.
- [ ] **Spike A (feasibility gate):** the guest's mirror fleet joins a host battle as a player-side ally via `BattleAPI` and deploys, AI-piloted — no remote pilot yet. If allied-join fights back, this phase stops at milestone 0.
- [ ] **Spike B:** vendored pilot AI drives a ship from a scripted/replayed virtual key feed on loopback (autofire queue, toggle debounce, shield AI all working without a physical keyboard).
- [ ] **Spike C (latency gate):** pilot over shaped 100/200 ms RTT + 2 % loss with a real video stream; record the verdict per hull size in the docs.
- [ ] `FLEET_MANIFEST` snapshot at battle start; mirror upgraded to battle-grade fidelity; results apply back via `BATTLE_RESULT`.
- [ ] `KEY_STATE` channel (bitmap + press counters, epoch latest-wins) + host adapter replacing `Keyboard.isKeyDown`.
- [ ] Possession arbitration (own ships only, one pilot per ship) + guest HUD overlays (reticle/flux/cooldown from the vendored renderers).
- [ ] Reward split: each player keeps own-hull damage/CR consequences; loot/credits split via `CoopRewardSplitter` (default 50/50, `coop.lootSplit` configurable on the Phase 28 options registry).
- [ ] Docs: video-stream setup guide (Parsec/Discord/Remote Play), latency expectations, keybind setup for the guest scheme.
- [ ] Two-instance QA at shaped WAN settings; commit with `git commit -m "feat: co-op piloted battles + tactical-map observer"`.

**Acceptance:**

- The observer view for any Phase 14 battle is a drawn tactical map (positions, facings, hull/flux) at 5–10 Hz, not a text list; it degrades to the text panel if position fields are absent (old peer).
- With the phase enabled, a host-engine battle contains both players' ships; the guest possesses and pilots one of its own ships end-to-end (movement, weapons, shields, system, venting, target cycling) via `KEY_STATE` over UDP.
- Toggle commands are loss-safe: 2 % packet loss produces no stuck or double-fired toggles (press counters verified under shaped loss).
- The guest's fleet takes real consequences: hull damage, CR loss, and losses from the joint battle apply to the guest's authoritative fleet; spoils split per the configured rule.
- No PvP: possession of the other player's ships is impossible; both players are always on the same side.
- With the phase disabled (or spike A failed), Phase 14 behavior is unchanged except the upgraded tactical-map observer.

## Phase 23: Public Release Packaging (post-V1)

> **Decided 2026-06-10 ("public after soak"):** V1 ships privately first (host + one friend); a public forum/Nexus release is the goal after the private soak proves stability. This phase turns the developer-grade deliverable into a releasable mod. It runs **after Phase 21** (the lobby/desync dialogs are part of what makes the mod presentable) and does **not** require Phase 22.

**Scope:**

- **Player-facing docs:** an install guide — including the vmparams classpath-prepend for `coop-forks.jar`, which is unusual for a mod and needs careful copy-pasteable instructions; a connect guide (distilled `CONNECTIVITY.md`: port-forward/UPnP/VPN walkthroughs, connection-doctor interpretation); a known-limitations page (distilled `starsector-runtime-limitations.md`: accepted divergences, the guest-save policy, the solo own-fleet combat model). Written for players, not developers.
- **Launch UX:** friendlier session start — config read from a data/config file (via the sanctioned `SettingsAPI` surfaces) instead of raw `-D` properties where feasible — **this settings-file surface is now owned by Phase 28 milestone 1** (typed options registry + `saves/common` overrides), which should land with this phase; launch scripts hardened for machines that are not the dev box (path detection, no hardcoded `K:\`). The title-screen limit is permanent (no API — Phase 21 facts), so the in-campaign lobby remains the first interactive surface.
- **Release hygiene:** version-string discipline (`mod_info.json` + the handshake build hash must agree), a player-facing CHANGELOG, license choice for the mod, MIT attribution for vendored CoOpCombat code if Phase 22 shipped, and listing text with an honest feature/limitation summary.
- **Support posture:** the Phase 21 desync/reject dialogs name cause + remedy; document how users report issues (which connection-doctor/log lines to paste) so the doctor log is the support tool it was built to be.

**Acceptance:**

- A stranger with two matching Starsector installs can download the mod, follow the docs, and reach a working session without reading developer docs; the only manual system-level step is the documented vmparams edit.
- The public listing's claims match what the mod actually does, including the limitations.

**Steps (sketch-level — refine when this phase is scheduled):**

- [ ] Write the player install guide (vmparams `coop-forks.jar` prepend with copy-pasteable text, mod enablement, exact-install-parity requirement).
- [ ] Distill `CONNECTIVITY.md` into the player connect guide (port-forward/UPnP/VPN walkthroughs, connection-doctor interpretation).
- [ ] Distill `starsector-runtime-limitations.md` into the known-limitations page (accepted divergences, guest-save policy, solo own-fleet combat model).
- [ ] Land Phase 28 milestone 1 alongside (settings-file config replaces raw `-D` properties where feasible — owned by Phase 28, delivered here).
- [ ] Harden launch scripts for non-dev machines (path detection, no hardcoded `K:\`).
- [ ] Release hygiene: version-string agreement (`mod_info.json` ↔ handshake build hash), player CHANGELOG, license + CoOpCombat MIT attribution if Phase 22 shipped, honest listing text.
- [ ] Document the issue-report flow (which doctor/log lines to paste).

## Phase 24: Shared-Faction Colonies, Industries, and Raids

> **Rescoped into V1 (2026-06-10, user decision — originally sketched post-V1 the same day).** Slots after 12c in the Implementation Order; milestone 1 (raids) needs no colony-lifecycle work and can start any time after 12c lands.
>
> **Feasibility assessed 2026-06-10 (API-verified): FEASIBLE as a shared player faction.** Both players manage the *same* colonies under the one engine player faction. Everything rides machinery V1 already builds — host-authoritative markets (12/12c), the world-skeleton delta channel (DECIV et al.), the interaction gate (10), outcome-delta capture (15's pattern), rep deltas (8), `CoopRewardSplitter` (created **by this phase** in `coop.rewards` for the income split; Phase 22 extends it post-V1). The genuinely new work is one thing: **market lifecycle replication** (a colony is a market that exists on only one engine until we create it on the other). Separate per-player factions are the stretch goal — assessed and **rejected**, see below. Prereqs: 12c (full market capture), 13 (suppressor — `PlayerRelatedPirateBaseManager` is already on its list), 16 (coordinated saves must capture colony state, which they do for free — it lives in the host save).

**Engine facts (verified against the 0.98a API sources, 2026-06-10):**

- `MarketAPI` exposes the full colony-management mutation surface as public API: `addIndustry(id)` / `removeIndustry(id, mode, forUpgrade)`, `getConstructionQueue()`, `setPlayerOwned(boolean)`, conditions, submarkets (`MarketAPI.java:278-411`). Building a market from scratch on the other engine is plain API — `Misc.java:6524-6539` even carries the recipe in commented-out form.
- `PlayerColonizationListener.reportPlayerColonizedPlanet(PlanetAPI)` fires on colonization — a clean capture hook on whichever engine the colonizing player runs.
- **Every player hostile act against a colony has a vanilla outcome listener**: `ColonyPlayerHostileActListener` provides `reportRaidForValuablesFinishedBeforeCargoShown(..., CargoAPI loot)`, `reportRaidToDisruptFinished(..., Industry)`, `reportTacticalBombardmentFinished`, `reportSaturationBombardmentFinished`; plus `GroundRaidObjectivesListener.reportRaidObjectivesAchieved(RaidResultData, ...)`. Raid resolution itself is open-source rules code (`MarketCMD`), so semantics are inspectable.
- `EconomyTickListener.reportEconomyMonthEnd()` is the colony-income capture point.
- NPC threats against player colonies (expeditions, pirate raids, Pather cells) are host-simulated already under Phase 13's suppressor model; their fleets reach the guest via Phase 9 replication.

**The model — one faction, two governors:**

1. **Colony lifecycle replication (the new core).** Colonization is a player action resolved locally by whoever performs it (vanilla dialog, marines, rolls — consistent with "replicate outcomes, not RNG"); the colonization listener fires and ships a `COLONY_FOUNDED` delta (planet id, market seed data: size, conditions, starting industries, submarkets). The host canonicalizes and rebroadcasts; the receiving engine builds the same market via the public API recipe and `setPlayerOwned(true)`. Abandonment ships the inverse delta. After creation, the colony is just another market — Phase 12/12c snapshot-on-open + transaction deltas already govern its contents.
2. **Colony management (industries).** The guest opens the mirrored player-owned market and the engine gives it the full vanilla colony UI locally — no custom UI needed. Mutations (industry add/remove/upgrade, construction queue, AI cores, improvements, free port, governor settings) are captured by **diff-on-close** (`ColonyInteractionListener.reportPlayerClosedMarket`: compare against the open-time snapshot) → `COLONY_MGMT` delta → host applies to the canonical market → rebroadcast. **Concurrency is already solved:** the Phase 10 interaction gate arbitrates one-player-per-entity dialogs, so two players cannot be inside the same colony screen at once — no concurrent-edit conflicts by construction.
3. **Income and spoils.** Monthly colony income lands on the host engine (`reportEconomyMonthEnd`). Split via `CoopRewardSplitter` policy (**50/50, decided 2026-06-10** — the class is created by this phase; same `coop.lootSplit` family Phase 22 extends later); the guest gets a monthly report line (feed message or session intel). Colony storage/local-resources submarkets are shared *claimable* content per the Phase 12c boundary — both players see and use the same storage.
4. **Player raids and bombardments on NPC colonies.** The acting player resolves the raid locally against the snapshot-fresh mirrored market (marine choices, target picks, rolls — all local); the hostile-act listeners capture the complete outcome → `RAID_RESULT` delta (commodities removed, industry + disruption days, stability hit, rep deltas via the existing `REP_DELTA` channel). Loot is per-player like salvage. Saturation-bombardment deciv flows through the existing `DECIV` skeleton delta unchanged. Host applies to the canonical market and rebroadcasts.
5. **NPC threats against the players' colonies.** Expeditions/raids targeting player colonies simulate host-side only (suppressor holds guest-side; `PlayerRelatedPirateBaseManager` suppression is already specced in Phase 13). Their fleets replicate via Phase 9; defense battles ride Phase 14 (and Phase 22 if built). Gap to close here: the *warning intel* (expedition countdown) is host-local — **decided 2026-06-10: mirror it as a coop-owned intel item** (`CoopExpeditionWarningIntel` + `EXPEDITION_WARNING`, see the settled decision points below) so the guest gets a real countdown entry, not just a feed line.

**Decision points — SETTLED (user decisions, 2026-06-10 review pass):**

- **Income split: 50/50, hardwired for V1.** Each monthly tick splits evenly; the guest receives the credit delta plus a monthly report line. `coop.incomeSplit` on the Phase 28 options registry makes it configurable post-V1.
- **Guest colonization: trusted, no host consent.** The guest colonizes like any other action; the host just canonicalizes the `COLONY_FOUNDED` delta. No confirm dialog in V1 (avoids the blocking-dialog suspend-logic trap). `coop.guestColonizationConsent` on the Phase 28 registry adds the toggle post-V1, default trusted.
- **Expedition warning: mirrored intel item** (the richer option, chosen over a feed line). Implemented as a **coop-owned** intel entry — `coop.colony.CoopExpeditionWarningIntel`, a custom `IntelInfoPlugin` (the verified surface also used by 20.6's `CoopSessionIntel`) — fed by an `EXPEDITION_WARNING` TCP message (faction, kind, target market id, ETA days, status), updated on change, removed on resolution. This mirrors the warning *data* into a coop intel item; it does **not** replicate the vanilla intel object (no generic intel-replication machinery).

**Stretch goal — separate per-player factions: assessed INFEASIBLE-IN-PRACTICE, rejected (2026-06-10).** The engine hardwires exactly one player faction: colony management UI, construction queues, income, production, storage access, commissions, and the hostility matrix all key off `Factions.PLAYER` / `market.isPlayerOwned()`. A guest-owned separate faction would have to exist as a custom *NPC* faction on the host engine — which gets **no management UI at all** (that entire surface would be custom-built), plus a permanent cross-engine identity/rep/ownership mapping layer (guest-engine player faction ↔ host-engine `coop_guest` faction) threaded through every rep, economy, targeting, and intel system. That mapping layer alone is larger than the rest of this phase combined, and every vanilla update would stress it — squarely against the stability rubric. The shared-faction model also matches the co-op fiction (one faction, two leaders). Do not relitigate without a new engine surface.

**Files:**

- Create `src/main/java/coop/colony/CoopColonySync.java` (lifecycle deltas + management diff-on-close)
- Create `src/main/java/coop/colony/CoopRaidOutcomeSync.java` (hostile-act listener capture + apply)
- Create `src/main/java/coop/colony/CoopExpeditionWarningIntel.java` (coop-owned mirrored expedition-warning intel entry — decided 2026-06-10)
- Modify `src/main/java/coop/net/CoopMessages.java` (`COLONY_FOUNDED`, `COLONY_MGMT`, `RAID_RESULT`, `EXPEDITION_WARNING` — reliable TCP)
- Create `src/main/java/coop/rewards/CoopRewardSplitter.java` (V1 scope: colony-income split policy, default 50/50 — **this phase creates the class**; Phase 22 (post-V1) later extends it for joint-combat spoils. Package is `coop.rewards`, matching the Source Layout — an earlier draft said `coop/campaign`, which was wrong)
- Unit tests (delta codecs, market-build recipe round-trip, management diff correctness, income-split math, expedition-warning intel lifecycle)

**Steps (milestone order — each independently shippable):**

- [ ] **Milestone 1 — raids first** (no lifecycle work needed: NPC markets exist on both engines already): hostile-act listener capture → `RAID_RESULT` → host apply + rebroadcast; two-instance QA of a guest raid and a host raid against the same NPC colony.
- [ ] **Milestone 2 — colony lifecycle:** `COLONY_FOUNDED`/`COLONY_ABANDONED` deltas + market-build recipe; QA: guest colonizes, host sees the colony, both reopen after save/load.
- [ ] **Milestone 3 — management + income:** diff-on-close `COLONY_MGMT` deltas, interaction-gate the colony screen, income split (50/50, via the new `coop.rewards.CoopRewardSplitter`) + monthly report line; **expedition-warning mirror:** host captures expedition/raid intel against player colonies via an intel-manager scan (the Phase 13 capture pattern) → `EXPEDITION_WARNING` TCP → guest creates/updates/removes its `CoopExpeditionWarningIntel` entry. QA: guest builds an industry, host sees it under construction; an incoming expedition shows the warning intel with countdown on the guest.
- [ ] Commit per milestone (`feat: coop raids`, `feat: shared colonies`, `feat: colony management + income`).

**Acceptance:**

- Either player can colonize; the colony exists with identical size/conditions/industries on both engines and survives the coordinated save/load cycle.
- Either player can build/upgrade/disrupt-repair industries from their own client; the other player sees the result; the interaction gate prevents simultaneous editing.
- Either player can raid or bombard an NPC colony; market disruption, stability, deciv, and rep effects converge on both clients; loot stays with the raider; income splits per the configured rule.
- An NPC expedition against a player colony is visible to both players before it arrives and resolves identically on both clients.

## Phase 25: Guest Time-Control Polish (post-V1) — Pause Strictness Toggle + Consensual Fast-Forward

> **Promoted from Maybe (2026-06-10, user decision):** the numbered home for what is genuinely left of the "Guest-originated / UI pauses" Maybe entry. Audit note: that entry's residue line was stale — Phase 11 as built ALSO shipped the discretionary manual key pause (guest `GENERAL_PAUSE` press → `PAUSE_INTENT(KEY, !observedPaused)`, confirmed in-game 2026-05-30), not just the screen/combat triggers. The true unbuilt residue is exactly two things, and this phase is them: **(A)** the `coop.allowGuestPause` strictness toggle Phase 11 deliberately skipped ("Guest shared pause is always on in v1"), and **(B)** guest-requested shared fast-forward, explicitly deferred in Phase 7b's policy decisions ("AND-of-intents like the Phase 11 pause OR"). Post-V1, small, slots "as appetite allows" — but **requires Phase 7b** (it builds on `CoopFastForwardLock`) and wants the Phase 20.6 session-intel surface (pending indicator) and the Phase 23 settings-file config surface (the toggle).

**What is already built — do not rebuild or relax:**

- Phase 11 `CoopSharedPauseCoordinator`: OR-of-intents shared pause (host/guest-key/guest-screen/combat), `intentSeq` last-writer-wins debounce, resolve-against-observed-state (the anti-phantom-pause fix), host-override asymmetry (host can force-clear a guest *key* pause, never a *screen* pause), guest clock always snapshot-driven.
- Phase 7b `CoopFastForwardLock`: vanilla toggle-FF mode forced session-wide, host Shift = the only FF input, guest `FAST_FORWARD` events consumed unconditionally, fixed 2x mult, fallback-to-1x on handle failure. The vanilla `Speeding up time` sticky message is field-driven and appears on both clients for free.
- Invariants that stay absolute: both clocks always stop/run/speed **together**; the guest never writes its own clock or `fastForward` field locally (mirror writes from the host snapshot are the only writer); combat pause intent is correctness (Phase 14 holds the non-engaged player), not courtesy.

**Part A — `coop.allowGuestPause` (strict-host toggle):**

- Host-owned, host-authoritative (the host computes `effectivePaused`), default `true` = exact Phase 11 behavior. Config rides the Phase 28 options registry (host-policy tier — Phase 28 milestone 1 is the settings-file surface this line originally pinned to Phase 23); a `-Dcoop.allowGuestPause` interim is acceptable only if this somehow lands before 28's core.
- **Gates only the guest *key* (manual) pause:** `effectivePaused = hostPauseIntent || guestScreenPauseIntent || eitherInCombat || (allowGuestPause && guestKeyPauseIntent)`. The guest *screen* pause stays always-on (it is the anti-frustration core of Phase 11 — disabling it silently punishes the guest for reading a menu), and the combat intent is never toggleable (Phase 14 correctness).
- When a guest key press is ignored under `false`, the guest must get visible feedback (session-intel line or sticky message — silent ignore reads as a broken pause key).

**Part B — guest-requested shared fast-forward (AND-of-intents):**

- **Polarity is the opposite of pause, by design:** pause is OR (either player can stop the world — stopping is safe); fast-forward is AND (both must consent to skip time — skipping is missable). `effectiveFastForward = hostFFIntent && guestFFIntent`. Free safety property: either player tapping off kills FF instantly.
- **Host side:** the host's Shift tap stops writing the vanilla field directly and becomes an intent, intercepted on the press edge exactly like `CoopHostPauseInputListener` does for `GENERAL_PAUSE` (the field turns from input into output: `CoopFastForwardLock.writeFastForward(effective)` becomes the only writer on the host too). Host FF intent resolves against *observed effective FF* — the same opposite-of-what-I-see rule that fixed the Phase 11 phantom-pause bug applies here verbatim.
- **Guest side:** `CoopCampaignInputBlocker` keeps consuming `FAST_FORWARD` (never flips the local field) but now records the press edge, mirroring `recordGuestPauseKeyPress`; the pump forwards it as a new reliable-TCP `FF_INTENT` (own monotonic seq, same last-writer-wins guard as `PAUSE_INTENT`).
- **Auto-clear guest FF intent** on combat start, on opening a blocking screen, and on disconnect; intents *survive* a shared pause (matches the 7b smoke-test contract: pause while FF on → unpause → FF resumes).
- **UX gap AND-semantics creates:** one player taps Shift and nothing happens until the partner consents. The vanilla sticky only shows when FF is actually active (correct), so a "partner wants to fast-forward" pending line goes on the Phase 20.6 session-intel/HUD surface. Without that indicator the feature feels broken — the indicator is in-scope, not optional polish.

**Decision points to settle before building:**

- Whether Part A ships at all: default-true plus zero strict-host requests so far means it may never be worth its config surface — building Part B alone is a legitimate outcome of this phase.
- Pending-indicator surface: session-intel line vs `CampaignUIRenderingListener` HUD text (whichever Phase 20.6 ended up making cheaper to extend).
- Whether the host keeps an override to force-clear a guest FF intent (symmetry with the pause key override) — likely unnecessary since AND already gives the host unilateral stop.

**Files:**

- Modify `mods/coop/src/main/java/coop/time/CoopSharedPauseCoordinator.java` (+ its test) — FF intents, `effectiveFastForward`, the Part A gate
- Modify `mods/coop/src/main/java/coop/time/CoopFastForwardLock.java` — effective-FF writer on the host
- Modify `mods/coop/src/main/java/coop/net/CoopMessages.java` (`FF_INTENT`), `coop/net/CoopNetPump.java` (forwarding, auto-clears, enforcement)
- Modify `mods/coop/src/main/java/coop/input/CoopCampaignInputBlocker.java` (guest Shift edge recording), `coop/input/CoopHostPauseInputListener.java` (host Shift edge → intent; rename if it now handles both keys)
- Config: `coop.allowGuestPause` on the Phase 28 options registry (host-policy tier)

**Steps:**

- [ ] Part A: gate formula + config read + ignored-press feedback + truth-table test rows (the Phase 11 16-row table grows a dimension); two-instance check that `false` ignores the guest key pause while screen/combat pauses still hold.
- [ ] Part B: `FF_INTENT` message + coordinator AND logic + host Shift interceptor + guest edge recording + auto-clears + pending indicator; tests mirror the Phase 11 suite (truth table, observed-state resolution incl. the phantom-FF regression analog, seq debounce, round-trip).
- [ ] Two-instance smoke: both tap Shift → ~2x both (sticky on both); either taps off → 1x both; one-sided tap → no speed change, pending line visible to both; guest opens a screen during FF → pause wins, FF resumes on close per the auto-clear/survive rules; dates match after several cycles.
- [ ] Commit `feat: guest time-control polish (pause toggle + consensual fast-forward)`.

**Acceptance:**

- With `coop.allowGuestPause=false` the guest's manual pause is ignored *with visible feedback*, screen and combat pauses still work, and the default `true` preserves Phase 11 behavior bit-for-bit.
- Fast-forward runs only while BOTH players want it; either player stops it with one tap; a one-sided request shows a pending indicator instead of silently doing nothing; no phantom-FF (observed-state resolution).
- All Phase 7/11 invariants survive: clocks never desync, the guest never drives its own clock/field locally, combat pause is unaffected by any toggle.

## Phase 26: Hyperspace Ambient-World Replication (post-V1) — Slipstreams + Abyssal Encounters

> **Promoted from two Maybe bullets** ("Slipstream replication", "Abyss encounter-point replication") **on 2026-06-10.** API verification (`velfield/SlipstreamManager.java`, `SlipstreamTerrainPlugin2.java`, `terrain/HyperspaceAbyssPluginImpl.java`, `enc/EncounterManager.java`, `enc/AbyssalRogueStellarObjectEPEC.java`, `enc/AbyssalLocationDespawner.java`) **corrected both sketches**: (1) "rebuilt guest-side through `SlipstreamBuilder`" does not give parity — the builder itself consumes RNG (angle variance, width wiggle, fluctuations), so identical placement params still produce different shapes; replicate the **finished segment polyline** instead (`addSegment(Vector2f, float)` is public, `SlipstreamTerrainPlugin2.java:344`). (2) "host broadcasts its EP set" misreads the model — encounter points are **transient per-player probe points**, regenerated in a ring around the *local* player fleet every 1000 units traveled (`HyperspaceAbyssPluginImpl.java:159–223`, field is `transient`-equivalent: regenerated, never shared world state); what must replicate is the **outcome of each encounter creation** (the temporary star system / spawned entities), with the EP identity recoverable from position. Dependencies: Phase 13 (suppressor + `coop-forks.jar` live), Phase 9 (NPC mirrors carry every encounter-spawned *fleet* already), Phase 8 (guest hyper position known host-side). Post-V1, alongside 22/25 as appetite allows.

**Verified engine facts (2026-06-10, API source — re-read before building):**

- `SlipstreamManager.advance()`: unseeded `Random` (line 442), `IntervalUtil(1f, 2f)` with random phase → draw counts differ per client even from identical RNG state (the Phase 13 rejection of seed alignment stands). Streams are built **atomically in one frame** (`addStream`: placement → `SlipstreamBuilder` → intersection fades → `spawn`) and their persistent segment state (`loc`, `width`, `bMult`, `fader`, `discovered` — all public fields) is **immutable after build** except cosmetic spawn/despawn faders: `checkIntersectionsAndFadeSections` mutates only the *new* stream's segments. A post-frame poll therefore always captures final state, one-shot.
- Of `SlipstreamParams2`, manager-built streams set only **four non-default fields**: `burnLevel`, `minSpeed`, `maxSpeed`, `lineLengthFractionOfSpeed` (`SlipstreamManager.java:556–560`) — the replication record needs just those plus the segment list. Segment spacing is 200–400 units (`SlipstreamBuilder.MIN_SPACING`/`MAX_SPACING`) → hundreds of segments per stream, single-digit-KB-to-~20 KB encoded → reliable TCP, chunk if the envelope needs it.
- Month-6/12 despawn: `plugin.despawn(delay, days, random)` — the noise argument shapes a purely cosmetic fade pattern; removal lands via `Misc.fadeAndExpire`. Mirroring despawn with a *local* Random is visually fine.
- Abyssal EP identity is **derivable from position**: `id = "abyssal_" + (int)(loc.x/1000f) + "_" + (int)(loc.y/1000f)` (`HyperspaceAbyssPluginImpl.java:193`); `depth` is a **pure function of position** (`getAbyssalDepth`, lines 72–134); `nearest`/`distToNearest` recompute from the deterministic skeleton. So a synthetic `EncounterPoint` + `AbyssalEPData` can be rebuilt guest-side from `(x, y)` alone — and the existing `AbyssalRogueStellarObjectEPEC` fork re-keys `data.random` by EP identity, which makes re-execution deterministic. **This is exactly what that fork was built for.**
- Three re-execution leaks need post-construction correction: (a) the `SYSTEM_CAN_SPAWN_THREAT` roll mixes `data.random` with the **local** pity counter `$threatSpawnsFailedToRoll` in sector memory (`AbyssalRogueStellarObjectEPEC.java:121–133`) → host ships the resolved tag, guest forces it; (b) `system.setOptionalUniqueId(Misc.genUID())` differs per client → cross-client location references need a coop key; (c) catalog names come off the drifted `Misc.random` (`Misc.genEntityCatalogId`) → divergent-cosmetic, same acceptance as base names/orbits.
- `AbyssalLocationDespawner` removes the system when the **local** player hasn't visited for 10 days AND is ≥5 LY away (lines 48–59) — host-only criteria would tear the system down while the guest explores it. Its removal code (system + hyper-side jump points + nascent wells + anchor, lines 61–88) is the recipe for guest-side `ENC_REMOVE` application.
- `EncounterManager` fires creators only at EPs just outside the **local** player's sensor range (lines 93–117). With the guest's manager suppressed (Phase 13), nothing ever triggers around a solo-traveling guest — replication restores *shared* experiences; solo-guest triggering is the stretch milestone.
- Slipstream/outside-system encounter creators (`SlipstreamPirate/LuddicPath/Mercenary/ScavengerEPEC`, `OutsideSystemRemnantEPEC`) spawn plain fleets → **already covered by Phase 9 mirroring** when the host triggers them; nothing to build here.

**Milestone 1 — Slipstream replication (restores shared travel topology):**

- Guest: add `SlipstreamManager` to the Phase 13 explicit suppression set (it is NOT in the v1 list — slipstreams were accept-divergence) + session-start sweep removing local stream terrain lacking `$coopStreamId`.
- Host capture: low-rate poll of hyperspace `getTerrainCopy()` for unledgered `SlipstreamTerrainPlugin2` entries → `STREAM_ADD(coopStreamId = host terrain id, the 4 params, segments as x|y|width|bMult quads)`; poll `isDespawning()` flip → `STREAM_DESPAWN(id, delay, days)`; terrain death → `STREAM_REMOVE(id)`. Full `STREAM_SET` reconcile on connect/reconnect.
- Guest apply: `addTerrain(Terrain.SLIPSTREAM, params)` + `setLocation(first segment)` + `addSegment` loop; for segments with `bMult == 0` set the field and `fader.forceOut()` (public); `plugin.spawn(~1f, local Random)` for the fade-in; `recomputeEncounterPoints()`; stamp `$coopStreamId`. `despawn(delay, days, local Random)` on `STREAM_DESPAWN`; remove entity on `STREAM_REMOVE`.
- Per-player map knowledge stays local: `discovered` flags and `SlipstreamVisibilityManager` are untouched.

**Milestone 2 — Abyssal temporary systems (restores co-op abyss exploration):**

- Host capture: poll `getStarSystems()` for unledgered `Tags.TEMPORARY_LOCATION` + `Tags.SYSTEM_ABYSSAL` systems → `ENC_SPAWN(kind, hyperLocX, hyperLocY, threatTag)` (threat read off the live system). Replace the system's vanilla `AbyssalLocationDespawner` with a coop-aware one that also holds removal while the guest's hyper position (Phase 8 snapshot) is within 5 LY; on removal → `ENC_REMOVE`.
- Guest apply: rebuild synthetic `EncounterPoint`/`AbyssalEPData` from `(x, y)` (id formula + pure-function depth + locally recomputed nearest), invoke the forked EPEC's `createEncounter` → identical system; then post-construction: force `SYSTEM_CAN_SPAWN_THREAT` to the host's value, strip the replica's `AbyssalLocationDespawner`, stamp `$coopEncId` (= EP id) in system memory. `ENC_REMOVE` applies the despawner's removal recipe.
- Fork coverage: `AbyssalRogueStellarObjectDireHintsEPEC` is a separate `CREATORS` entry inheriting `createEncounter` — it needs the same fork keying as its parent (verify `addSpecials` draws).
- Cross-client identity: extend Phase 9 location resolution with a `$coopEncId` fallback so fleets inside temp systems (Threat encounters!) mirror correctly despite divergent `genUID` system ids.
- Fingerprint guard: ensure `CoopSectorFingerprint` excludes `Tags.TEMPORARY_LOCATION` systems (and their hidden planet markets) or reconnect hard-rejects while a temp system lives.

**Milestone 3 (decide at build time) — Abyssal lights / dire hints:** `AbyssalLightEPEC`/`AbyssalLightDwellerEPEC` spawn interactable light entities (dweller bait, flavor); dweller *fleets* already mirror via Phase 9, but the light entities are guest-invisible without work. Options: fork both EPECs with the same EP-identity keying and re-execute (cheap, consistent — note `Misc.getPointWithinRadius(loc, spread)` draws off drifted `Misc.random` → tiny cosmetic offsets in PAIR/CLUSTER spawns), or replicate the entities directly (params object sits behind a protected field → MethodHandles), or accept-divergence. Recommendation at sketch time: forked re-execution, same shape as milestone 2.

**Stretch (separate decision, may stay unbuilt):** guest-proximity triggering — replace the host's `EncounterManager` with a coop-aware variant that also generates/range-checks EPs around the guest's mirrored fleet, so ambient encounters fire when the players travel apart. Without it, encounters trigger only around the host (today's v1 behavior minus the guest's divergent local sim). The manager is ~200 lines of pure API and replaceable by the same `removeScript`/`addScript` mechanism; the EP providers are listener-driven (`ListenerUtil.generateEncounterPoints`).

**Decision points to settle before building:**

- Message shape: dedicated `STREAM_*`/`ENC_*` family (BASE_SET-style set-reconciled, recommended) vs more `WORLD_DELTA` subtypes.
- Milestone 3 mechanism (forked re-execution vs direct entity replication vs accept-divergence) — and whether it ships at all.
- The stretch milestone: is solo-guest ambient content worth a replaced host manager, or does "encounters happen around the host" suffice for the co-op fantasy.

**Files:**

- Create `mods/coop/src/main/java/coop/campaign/CoopSlipstreamAuthority.java` (+ test: encode/decode round-trip, set reconcile, despawn/remove transitions)
- Create `mods/coop/src/main/java/coop/campaign/CoopEncounterAuthority.java` (+ test: EP-record derivation from position, threat-tag forcing, remove recipe)
- Create `mods/coop/src/main/java/coop/campaign/CoopAbyssalDespawner.java` (host-side guest-aware replacement)
- Create fork `mods/coop/forks/com/fs/starfarer/api/impl/campaign/enc/AbyssalRogueStellarObjectDireHintsEPEC.java` (+ milestone 3: the two light EPECs)
- Modify `coop/fleet/CoopNpcFleetSuppressor` (add `SlipstreamManager` — Phase 13 extends this class in place; there is no sibling suppressor class), `coop/net/CoopMessages.java`, Phase 9 location resolution (`$coopEncId` fallback), `coop/seed/CoopSectorFingerprint.java` (temp-system exclusion)

**Steps:**

- [ ] Milestone 1: suppression + sweep, poll capture, polyline apply, despawn/remove flow, `STREAM_SET` reconcile; two-instance smoke — host and guest hyperspace maps show the same streams (positions/widths/faded gaps), a fleet riding a stream on one screen rides it on the other, month-6/12 turnover mirrors, reconnect reconciles.
- [ ] Milestone 2: capture poll + coop despawner, guest re-execution + the three corrections, `$coopEncId` mapping, fingerprint exclusion; two-instance smoke — host triggers a rogue-object encounter → guest gets the same system (same object type/layout; names may differ) at the same hyper location, both can enter it together, Threat fleets mirror inside, removal happens only when both players are away, reconnect with a live temp system passes the fingerprint.
- [ ] Milestone 3 + stretch: per the decisions above.
- [ ] Document residual divergences (names/catalog ids, light-cluster offsets if M3 re-executes, solo-guest triggering if stretch unbuilt) in `docs/starsector-runtime-limitations.md`, replacing the v1 slipstream/abyss accept-divergence entries.
- [ ] Commit `feat: replicate slipstreams and abyssal encounters (hyperspace ambient world)`.

**Acceptance:**

- Both players see the same slipstream network at all times (including mid-month additions and seasonal despawns); the guest generates no local streams; reconnect reconciles the full set.
- Abyssal temporary systems exist on both engines at the same locations with identical generated content (cosmetic name divergence accepted), are jointly enterable with fleets mirrored inside, and despawn only when neither player is nearby.
- The v1 "slipstreams diverge / abyss is host-experienced-only" limitations entries are retired from the docs; whatever this phase leaves unbuilt (lights, solo-guest triggering) is documented in their place.
- No RNG alignment is attempted anywhere: streams replicate as outcome polylines, abyssal systems as keyed re-execution from replicated EP records, and every remaining draw-off-`Misc.random` divergence is cosmetic-only and documented.

## Phase 27: Multi-Guest Enablement (post-V1) — Host + 2–3 Guests

> **Promoted from the "Multi-guest enablement" Maybe entry on 2026-06-10.** Feasibility was settled by the Phase 20.5 assessment: **YES, up to 3 guests**, with the host-authoritative star topology unchanged — guests never talk to each other (the host relays guest state to the other guests), so NAT reachability stays host-only and no mesh problems exist. Phase 20 ships the transport N-ready (`senderId` in both envelopes, `CoopPeerLink` peer table with broadcast/unicast routing, capacity hard-enforced to 1), which is exactly why this phase is **gameplay arbitration + a QA matrix, not a transport rewrite**. **Hard dependency: Phase 20.** Touches Phases 24/25/22 only where they exist at build time (milestone 3). The 20.5 cost verdict stands and governs scoping: the code is mostly generalizing "the guest" to "a set of guests"; **the QA matrix is the real work — budget accordingly.**

**Verified single-guest couplings (2026-06-10, mod source — this is the work list):**

- `CoopSessionState` holds a single `remotePlayerId` (field, `CoopSessionState.java:15`; lobby accept writes it at line 61; teardown clears at 195) and the lobby state machine admits one join → becomes a per-peer session table (playerId → lobby/handshake/seed-lock state), the session-layer twin of Phase 20's `CoopPeerLink`.
- `CoopNetPump` filters inbound on the single `sessionState.remotePlayerId()` (`CoopNetPump.java:904`) → becomes `senderId`-keyed dispatch (the envelope already carries `senderId` after 20.5; nothing on the wire changes).
- Shared pause is one guest→host `PAUSE_INTENT` channel, last-writer-wins by seq (`CoopSharedPauseCoordinator.java:190`) → becomes a per-sender intent map; the host pauses on **OR over the set** (stopping is safe at any N — the Phase 11/25 principle generalizes verbatim). If Phase 7b/25 is built, shared fast-forward becomes **AND over the set** (any player's tap-off cancels).
- The player-fleet mirror is "the one remote player" on both ends (Phase 8) → becomes a playerId-keyed player-mirror registry on every client; the host relays each guest's `FLEET_SNAPSHOT` (and hyper position) to the other guests with the original `senderId` preserved. Relay fan-out adds host upload of (N−1)× guest snapshots — KB/s, trivial per the 20.5 bandwidth check.
- Phase 9 streams NPC motion only for player-occupied locations → the gate generalizes from {host location, guest location} to the set of all player locations (up to 4 concurrently streamed locations; 10 Hz JSON × 4 players verified trivial in 20.5).
- Phase 16 coordinated saves: `SAVE_CHECKPOINT` is already broadcast-shaped (host saves → every guest autosaves locally) → N-way is mechanically identical; the host-save `CoopGuestSnapshot` becomes a list keyed by guest playerId.

**Already N-safe by construction (verified in the 20.5 assessment — no work):** interaction-gate claims and mission claims are first-come host-arbitrated protocols; market/dock locks are keyed per `<marketId>:<submarketSpecId>`; the NPC mirror registry is keyed by id; rebroadcast backstops and every host-authoritative snapshot (`TIME_SNAPSHOT`, `NPC_FLEET_SET`, `PLAYER_REP_SNAPSHOT`, mission pool, `BASE_SET`, Phase 26 `STREAM_SET`) are listener-count-agnostic broadcasts.

**Milestone 1 — N-peer session core (3 instances on localhost):**

- Lift `coop.maxGuests` (default stays 1; this phase certifies 2–3) and run the per-peer session table: each guest independently completes version/mod handshake, campaign-identity check, and seed-lock fingerprint against the host — one guest's rejection never affects another's session.
- `senderId`-keyed dispatch in the pump; playerId-keyed player-mirror registry; host relay of guest fleet snapshots to the other guests (other guests' mirrors are display-only echoes, same as the host mirror today).
- Pause intents as a per-sender map with OR-over-the-set semantics; per-location NPC motion gating over the full player-location set.
- Smoke: host + 2 guests on localhost — everyone sees everyone's fleet move, any player's pause freezes all, one guest disconnecting cleanly leaves the other's session intact.

**Milestone 2 — Gameplay arbitration + lifecycle:**

- Claim-owner identity: interaction gate, mission claims, and market/dock locks record *which* playerId holds the claim (first-come rule unchanged) so a release/timeout from guest A never unlocks guest B's claim, and "waiting for partner" affordances name the holder.
- `ENGAGE_GUEST` arbitration: when one hostile NPC could engage two guests, the host awards the engagement first-claim (same shape as the interaction gate); the loser's mirror gets the standard battle-eject backstop. Exactly-one-piloting-player per battle is preserved at N — everyone else gets the 2–5 Hz battle-status panel (already a broadcast).
- Combat auto-pause holds *all* non-piloting players (falls out of OR-over-the-set).
- N-way coordinated saves: `SAVE_CHECKPOINT` fan-out + per-guest snapshots keyed by playerId in the host save.
- Reconnect grace (20.2) with N guests: one guest's drop enters `RECONNECT_WAIT` and **holds the whole session paused** (pause-is-safe principle; a feed message names the dropped player); grace expiry ends only that guest's membership, not the session — the peer table shrinks and play resumes.

**Milestone 3 — Sibling-phase generalizations (each only if that phase is built):**

- Phase 25: fast-forward = AND-over-the-set; decide `coop.allowGuestPause` granularity (global vs per-guest).
- Phase 24: `CoopRewardSplitter` income split 50/50 → equal N-way split (config for custom shares stays a Maybe).
- Phase 20.6/21 UI: session intel + lobby/ready-up render one row per peer (`LINK_STATUS` is already per-link data); ping HUD shows the worst link.
- Phase 22: joint piloted battles stay **one guest pilot + host** — multi-guest joint piloting is out of scope here (compose with 22's own stretch goals if ever).

**Milestone 4 — The QA matrix (the real cost, run at 4 players where applicable):**

- Pairwise contention: two guests claim the same NPC interaction; two guests open the same market/submarket; two guests dock at the same colony (Phase 10 global gate serializes; Phase 18 forced-close covers the race); two guests near the same hostile NPC (`ENGAGE_GUEST` award + eject backstop).
- Mixed activity: guest A in a local battle while guest B trades and guest C travels; host saves mid-battle (checkpoint defers per the Phase 16 dialog rule); pause storms (rapid conflicting intents from 3 players).
- Membership churn: one guest drops and resumes within grace (others held, then released); grace expiry removes one guest while the session continues; serial reconnects of different guests.
- Long soak: 30+ minute 4-player session on localhost, then the same with one link WAN-shaped (clumsy profile from Phase 20) — the other links must not degrade with it.

**Decision points to settle before building:**

- **Mid-session join:** recommended YES behind a config (`coop.allowMidSessionJoin` on the Phase 28 options registry) — a late guest is mechanically a 20.2 resume with no prior state (handshake → fingerprint → full rebroadcast backstop); the alternative (lobby-only joins) saves no code, only QA lines.
- **Grace semantics at N** (hold-all recommended above) — confirm against play feel; the alternative (session continues, dropped guest's mirror freezes) contradicts the pause-is-safe principle but shortens stalls.
- **Cap:** certify and hard-cap at 3 guests (the assessed bound), or certify 2 and leave 3 config-gated.

**Files:**

- Modify `src/main/java/coop/session/CoopSessionState.java` (per-peer session table replacing `remotePlayerId`)
- Modify `src/main/java/coop/net/CoopNetPump.java` (`senderId`-keyed dispatch, guest-snapshot relay)
- Modify `src/main/java/coop/net/CoopNetService.java` + `CoopNetStartupConfig.java` (lift `coop.maxGuests`, mid-session-join gate)
- Modify `src/main/java/coop/time/CoopSharedPauseCoordinator.java` (per-sender intent map, OR/AND-over-set)
- Modify the Phase 8 player-mirror classes (playerId-keyed registry), the interaction-gate/mission/market-lock claim records (owner playerId), `coop/save/CoopGuestSnapshot` handling (list keyed by playerId), `CoopReconnectCoordinator` (per-peer grace)
- Modify `scripts\deploy-to-test-clients.ps1` + launch configs for 3–4 local instances
- Unit tests: per-peer session table transitions, OR/AND-over-set truth tables, claim-owner release isolation, relay routing (broadcast vs unicast vs relay), snapshot-list keying

**Steps:**

- [ ] Milestone 1: session table, dispatch, mirrors, relay, pause set; 3-instance localhost smoke.
- [ ] Milestone 2: claim ownership, `ENGAGE_GUEST` arbitration, N-way saves, per-peer grace; targeted contention smokes.
- [ ] Milestone 3: generalize whichever of 24/25/20.6/21 exist; re-run their acceptance lines at N.
- [ ] Milestone 4: the full QA matrix above; record results in `QA_V1.md` (new multi-guest section).
- [ ] Docs: update `CONNECTIVITY.md` (host bandwidth/CPU expectations at 4 players) and the README player count.
- [ ] Commit `feat: enable multi-guest sessions (host + up to 3 guests)`.

**Acceptance:**

- A host + 2–3 guest session passes the Phase 19 movement/interaction/combat/save smoke lines for every player, with per-peer handshake/seed-lock and independent clean disconnect.
- All shared-world arbitration is first-come with named owners: no cross-guest lock leakage, exactly one pilot per battle, one `ENGAGE_GUEST` winner per NPC contact.
- Any player's pause holds everyone (and FF, if built, requires everyone); one guest's reconnect grace holds the session and its expiry removes only that guest.
- Host saves fan out checkpoints to all guests and store one snapshot per guest; localhost single-guest behavior is byte-for-byte unchanged with `coop.maxGuests=1` (all existing tests stay green).
- The milestone 4 QA matrix is recorded in `QA_V1.md`, including the one-link-shaped soak.

## Phase 28: Coop Options & Player-Facing Configuration (post-V1)

> **Created 2026-06-10 (user decision — like Phase 23, born from a request, not a Maybe promotion):** every phase has been minting its own knobs (`-Dcoop.*` properties, "behind a config" decision points, "configurable" split rules), and Phase 23 promised a settings file without owning the machinery. This phase is the single home for player-facing configuration: a typed option registry, a sandbox-safe file stack, host-policy sync to guests, and an in-game options page. Governing principle: **expose preferences, never correctness** — anything the sync design depends on (cadences, gate semantics, fingerprint checks) is deliberately not an option, and the registry records that list so it doesn't erode one knob at a time. Dependencies: milestone 1 has none and should land **with Phase 23** (it delivers 23's "settings file instead of `-D`" Launch-UX bullet); milestone 3 needs 20.6's `CoopSessionIntel`; sibling phases (22/24/25/27) are touched only as they exist.

**Engine facts (verified against the 0.98a API sources, 2026-06-10):**

- Persistence without `java.io` (the classloader sandbox holds): `SettingsAPI.writeJSONToCommon(filename, json, onlyIfChanged)` / `readJSONFromCommon(filename, putInWriteCache)` / `fileExistsInCommon` (`SettingsAPI.java:401-412, 556-562`) write under `saves/common` — which **survives mod updates** (anything inside the mod's `data/config` is overwritten on update, so user overrides cannot live there).
- Shipped defaults load through the same API: `loadJSON(path, modId)` / `getMergedJSONForMod` (`SettingsAPI.java:192, 284`).
- An in-game editor is real UI, not a hack: intel pages take interactive buttons — `IntelInfoPlugin.createLargeDescription` + `buttonPressConfirmed`/`buttonPressCancelled`, with optional per-button confirm dialogs via `doesButtonHaveConfirmDialog` (`IntelInfoPlugin.java:108-135`). This is the same verified surface `CoopSessionIntel` (20.6) already uses.
- The title screen has no API (Phase 21 fact, permanent): pre-campaign configuration can never be in-game UI — the file stack + `-D` properties remain the only pre-session surface.

**The model — three tiers (where config lives):**

1. **Launch/connection (per-client, read before any session exists).** Precedence: `-Dcoop.*` JVM property (highest — dev/debug override) → user override in `saves/common/coop_options.json` (via `readJSONFromCommon`) → shipped default in `data/config/coop_options.json`. `CoopNetStartupConfig` becomes a reader of this stack instead of raw property reads. **Deliberately `-D`-only forever:** `coop.adoptCampaignId` (one-shot explicit consent — the friction is the feature), `coop.newGameSeed` (one-shot), and the debug escape hatches (`coop.debug.diagnostics`, `coop.ff.disable`, `coop.clock.disable`).
2. **Host gameplay policy (host-authoritative, synced, stored per-campaign).** Lives in sector persistent data under `coop.options.*` (beside `coop.campaignId`), so a campaign's rules travel with its save; a new campaign seeds from the host's install-level defaults. The host broadcasts an `OPTIONS_SNAPSHOT` (flat key=value payload — the envelope parser is flat by design) at session establish and on every change; guests hold a read-only effective view, and every change emits a feed message ("Host enabled guest manual pause"). Each option declares its **apply boundary** — nothing applies retroactively.
3. **Per-client preferences (local, never synced).** Pure presentation: link-HUD visibility/corner, feed verbosity, partner presence color.

**The registry (initial inventory — each owning phase wires its key when it builds; schema entries ship now, inert until then):**

| Key | Tier | Default | Owner | Applies |
|---|---|---|---|---|
| `coop.allowGuestPause` | policy | `true` | Phase 25 | immediately |
| `coop.pauseOnGuestScreens` | policy | `true` | this phase (Phase 11 lever) | next screen open/close |
| `coop.allowMidSessionJoin` | policy | `true` | Phase 27 | next connection attempt |
| `coop.maxGuests` | policy | `1` | Phase 20.5 / 27 | next connection attempt |
| `coop.reconnectGraceSeconds` | policy | `60` | Phase 20.2 | next drop |
| `coop.lootSplit` | policy | `equal` | Phase 22 | next battle result |
| `coop.incomeSplit` | policy | `equal` (vs `host-banks`) | Phase 24 | next month tick |
| `coop.guestColonizationConsent` | policy | `false` (trusted) | Phase 24 | next colonization |
| `coop.password` | launch + policy | empty | Phase 20 | next connection attempt |
| `coop.hostPort` / `coop.connectHost` / `coop.connectPort` / `coop.portMapping` | launch | — | Phases 2 / 20 | next launch/connect |
| `coop.hud.show` / `coop.hud.corner` | client | on | Phase 20.6 | immediately |
| `coop.feedVerbosity` | client | `all` (vs `important`, `minimal`) | Phase 20.6 | immediately |
| `coop.partnerColor` | client | preset | Phase 8 presence indicator | immediately |

**`coop.pauseOnGuestScreens` — the one genuinely new lever, scoped carefully:** `false` lets the world keep running while a guest browses the vanilla auto-pause screens (map/fleet/character/refit/cargo/intel — all local-only views). It does NOT touch the two hardwired pause intents: interaction-dialog pause (the Phase 12 market open-snapshot model trades against open-time state — that pause is correctness, not comfort) and combat auto-pause (Phase 14). Phase 25's "screen pause stays always-on" line still holds for what it guarded: the `allowGuestPause` *strictness* toggle must never gate the screen pause (that would punish the guest silently). This is a separate, transparent lever — host-owned but guest-visible (read-only view + feed message), default `true` = exact Phase 11 behavior, and the options page puts a confirm dialog on it that names the trade-off ("the world moves while your partner reads").

**Explicitly not configurable (the correctness list — do not erode):** snapshot/stream cadences (10 Hz mirrors, 5 Hz time, 2–5 Hz battle status — QA'd rates, not preferences; Phase 29 later adapts the fleet-motion streams automatically between QA-certified tiers — the link picks, still never the player); fast-forward AND-over-intents semantics (forcing FF on a player skips content they can't get back); combat auto-pause; interaction-gate/claim arbitration; seed-lock, fingerprint, and campaign-identity checks; the NPC suppressor; iron-mode refusal. A request to make one of these a knob is a design change to its owning phase, not a registry entry.

**Where it's set:**

- **In-campaign (the normal path):** an "Options" page on `CoopSessionIntel`, generated from the registry — toggle/cycle/stepper buttons on the intel-button surface, risky options behind per-button confirm dialogs. The host edits policy; each client edits its own preferences; a guest sees policy read-only (buttons absent, values labeled as host settings). Outside a session the same page edits install defaults.
- **Pre-campaign:** the file stack only (the title-screen limit is permanent). The shipped `data/config/coop_options.json` is fully commented and doubles as the option reference; the Phase 23 player docs get a generated options table.
- **Dev/debug:** `-D` properties override everything, unchanged.

**Decision points to settle before building:**

- **LunaLib vs native UI:** recommend NATIVE (registry + intel page). LunaLib would give a polished settings menu for free but makes every player install a dependency mod; the option count (~a dozen) fits the intel surface the mod already owns, and Phase 23's public-release posture favors zero hard dependencies. Revisit only if the registry outgrows the intel page.
- **Policy storage:** per-campaign sector persistent data (recommended — rules travel with the save, matching `coop.campaignId`) vs per-install common file only.
- **`coop.pauseOnGuestScreens` inclusion:** recommended yes with the scope above; the conservative alternative is to drop it and keep Phase 11's always-pause.

**Files:**

- Create `src/main/java/coop/config/CoopOptionsRegistry.java` (typed schema: key, type, bounds, default, tier, owner, apply boundary; carries the not-configurable list as documentation)
- Create `src/main/java/coop/config/CoopOptionsStore.java` (precedence stack `-D` → common override → shipped default; policy tier in sector persistent data; all access through `SettingsAPI` — no `java.io` anywhere)
- Create `src/main/java/coop/ui/CoopOptionsPage.java` (registry-driven intel-button editor on `CoopSessionIntel`)
- Create `mods/coop/data/config/coop_options.json` (shipped, commented defaults)
- Modify `src/main/java/coop/net/CoopNetStartupConfig.java` (read through the store), `coop/net/CoopMessages.java` (`OPTIONS_SNAPSHOT`), `coop/ui/CoopSessionIntel.java` (options page hook), `coop/time/CoopSharedPauseCoordinator.java` (`pauseOnGuestScreens` term in the effective-pause formula)
- Unit tests: precedence stack, `OPTIONS_SNAPSHOT` codec round-trip, bounds clamping, apply-boundary enforcement, guest read-only view

**Steps (milestone order — each independently shippable):**

- [ ] **Milestone 1 — config core** (no prerequisites; lands WITH Phase 23, whose settings-file Launch-UX bullet it delivers): registry + store + precedence stack; migrate `CoopNetStartupConfig`; shipped defaults file.
- [ ] **Milestone 2 — policy sync:** `OPTIONS_SNAPSHOT` broadcast + per-campaign persistence + apply boundaries + change feed messages; `coop.pauseOnGuestScreens` wired as the first synced policy (it needs no sibling phase).
- [ ] **Milestone 3 — options page** (needs 20.6): registry-driven intel-button editor, host policy + local prefs, guest read-only view, confirm dialogs on risky knobs.
- [ ] **Milestone 4 — knob adoption + docs:** each owning phase (22/24/25/27) wires its key through the registry as it builds; generate the options reference for the Phase 23 docs set.
- [ ] Commit per milestone (`feat: coop options core`, `feat: synced host policy options`, `feat: in-game coop options page`).

**Acceptance:**

- Every launch/connection value is settable in a file that survives a mod update (`saves/common`), with `-D` still overriding for dev use; no `java.io` anywhere — only `SettingsAPI` surfaces.
- The host changes a policy mid-session → the guest's read-only view and a feed message update within one snapshot, and the change takes effect exactly at its declared apply boundary (a `pauseOnGuestScreens` flip never yanks the pause out from under a screen the guest already has open).
- Campaign policy survives save/load and reconnect; a fresh campaign seeds from install defaults; guests cannot edit policy (the button is absent, not an error).
- With every option at its default, behavior is byte-for-byte the pre-Phase-28 session (all existing tests stay green), and the not-configurable list still has zero knobs.

## Phase 29: Campaign Motion Smoothness (V1 since 2026-08-20) — Mirror Interpolation + Adaptive Cadence

> **Pulled into V1, 2026-08-20 (user decision), with the root cause now verified live** during the
> perf-debug session (the stutter fixes made the jumpiness plainly visible). The mirror drive model —
> `setMoveDestinationOverride` + `setVelocity` per 10 Hz record, with a hard `setLocation` snap every
> 10th apply (`CoopFleetMirror.LOCATION_CORRECTION_EVERY = 10`, i.e. ~1 Hz) — fails in **two measured
> modes**, both producing a visible ~1 Hz teleport:
>
> 1. **Speed-clamped chase.** The engine steers the mirror under the *mirror's* movement stats; the
>    real fleet is usually running sustained burn (or other ability modifiers) the mirror does not
>    run, so a traveling fleet's mirror physically cannot keep up. Error accumulates for a second,
>    then the snap teleports it forward.
> 2. **Arrival dead zone.** A near-stationary fleet (orbit drift) hands the mirror a destination at
>    ~its own position; the engine's fleet movement stops inside its arrival radius and the mirror
>    freezes while the real fleet drifts. Observed live: host and guest parked in orbit at the same
>    planet each saw the *other's* mirror hop once per second instead of drifting — the partner
>    player mirror is a worst-case offender, which settles the M1 scope question below.
>
> **Eliminated causes (A/B verified same session):** host-side simulation fidelity is NOT the source —
> the host flying into the guest's system changed nothing visibly; the full-fidelity guest-system
> driver was confirmed engaged (`drive engaged for <system> (guest present, host elsewhere)` +
> `npc.systemDriver` ~0.2 ms/frame in the profiler), and the sender-side `CoopNpcFleetMotionSmoother`
> covers the stride-advanced systems (hyperspace remains smoother-only by design — fixed phase slot).
> The defect is purely in guest-side application, shared by the player-mirror (`apply`) and NPC-mirror
> (`applySnapshot`/`applyMotion`) paths through `placeInLocation`/`driveMovement`.
>
> **Consequences for M1:** the "spike first" artifact characterization below is largely satisfied by
> these live observations (following-lag, correction pop, and the dead-zone freeze-hop are all
> confirmed on a clean localhost link — no shaped link needed to reproduce); the shaped-loopback pass
> remains for the loss/staleness behaviors only. The M1 acceptance gains a concrete scenario: **a host
> and guest parked in orbit see each other glide, and a stationary-drift mirror logs zero correction
> snaps.** M1's known-risk note called this "post-V1 polish rather than a V1 rescope" — superseded by
> this banner; the QA gate (no rubber-banding) stands.

> **Created 2026-06-10 (user question: "should players be able to tune the sync rate for a smoother experience? automatic? semi-automatic with a limit?"):** the answer reframes the ask. Snapshot rate is not the smoothness lever — smoothness is decided by what a mirror does *between* snapshots, and raising the rate just buys smaller position steps at more bandwidth while silently invalidating tolerances calibrated against the QA'd rates (the Phase 7c dead zone is sized against the 5 Hz `TIME_SNAPSHOT` cadence; the ~1 Hz position/orbit snaps absorb skew bounded by those same streams). So: **no player-facing rate knob, ever** — cadences stay on Phase 28's not-configurable list. This phase builds the two things that actually deliver smoothness: **(M1) client-side interpolation / dead-reckoning of mirrors** — the fix the rejected regional-authority Maybe already named — and **(M2) the "semi-automatic with a limit" idea done right:** the *link-quality machinery*, not the player, moves the UDP state streams between a few QA-certified cadence tiers. This changes **who** picks the rate (the link, within certified bounds), never whether players can. Dependencies: M1 needs only the built Phases 8/9 (plus Phase 20's clumsy shaped-loopback harness for QA); M2 needs Phase 20 (`CoopLinkQuality`, `LINK_STATUS`, `CoopPeerLink`).

> **M1 mechanism revised 2026-08-24 (deep-research pass, user-settled).** A three-track research sweep over shipped netcode and the primary literature (Valve Source networking, Unreal simulated-proxy movement, Unity Mirror / Netcode for Entities / NGO, Overwatch GDC 2017, Fiedler's snapshot-interpolation series, Gambetta, Murphy's *Believable Dead Reckoning* in Game Engine Gems 2, the DIS/IEEE 1278 dead-reckoning results) converged on three findings that change the M1 spec:
>
> 1. **Pure dead reckoning at 10 Hz is the tested-and-rejected design.** Fiedler measured extrapolate-then-correct at exactly this send rate and reported our artifacts verbatim ("extrapolate through the floor... snap to catch up"). Every shipped implementation renders non-predicted proxies from a small interpolation buffer roughly two send intervals in the past, and extrapolates only as a starvation fallback with a hard cap (Valve `cl_extrapolate_amount` 0.25 s; Unity NfE 20 ticks ≈ 0.33 s). Our cost function makes the choice one-sided: at campaign-map speeds a 200 ms render delay is invisible, and rubber-banding (extrapolation's signature failure) is the artifact this phase exists to remove.
> 2. **No shipped engine drives remote proxies through the entity's own movement AI.** Unreal sets simulated-proxy location, rotation, and velocity from replicated state with local movement simulation off; Unity forces non-authority rigidbodies kinematic "so the physics simulation runs on the authoritative instance without interference"; Mirror and FishNet interpolate the transform directly. Where steering-toward-a-target is attempted (Unity NavMeshAgent-per-proxy is the documented case), the reported failures are per-agent speed divergence and `stoppingDistance` freezes: the banner's two measured modes with the names changed.
> 3. **A correction window longer than one send interval manufactures oscillation.** At 10 Hz, a 300 ms positional blend keeps three overlapping corrections in flight and the residual error beats at the packet rate. The shipped defaults land on one interval independently (Unreal `NetworkSimulatedSmoothLocationTime` 0.100 s, Source `cl_smoothtime` 0.1). The old "blend over 100–300 ms" range is superseded; where positional correction survives at all, its window is ≤ 100 ms, and cursor drift is absorbed by time-scaling instead.
>
> **Settled 2026-08-24 (user decisions):** buffered interpolation replaces dead reckoning as the M1 mechanism; the datagram envelope gains `epoch` + host game-time send stamp *before* M1 (revising this section's old "wire format untouched by M1" line; cross-note added at Phase 20.1); motion datagrams carry the last 2–3 samples for single-loss immunity; the interpolation delay is a **fixed 200 ms in M1**, with adaptive sizing (Mirror's jitter formula) riding M2 alongside the rest of the link-quality machinery. Sources are listed at the end of this section.

**Why rate is the wrong lever (verified against the built phases):**

- Mirrors already smooth *toward* targets: `CoopFleetMirror` drives `setMoveDestinationOverride` plus a periodic (~1 Hz) `setLocation` snap (Phases 8/9), and `NPC_FLEET_MOTION` records already carry `velocityX`/`velocityY`. The visible artifacts are following-lag behind a stale destination point, the periodic correction pop, and freeze-then-jump under packet loss — none of which improves materially with a faster stream, and all of which interpolation addresses at near-zero wire cost (two envelope fields and duplicated samples — see the M1 prerequisite bullet).
- Bandwidth was never the constraint: 10 Hz JSON × 4 players was verified trivial in 20.5. The fixed snapshot *interval* is the artifact source, and extrapolation removes it.
- A free player dial would turn every desync report into "what rate were you running?" — exactly the QA-matrix explosion the Phase 28 correctness list exists to prevent. Discrete certified tiers chosen by code keep the matrix bounded: certify each tier once.

**Milestone 1 — mirror snapshot interpolation (`coop.fleet.CoopMotionInterpolator`; mechanism revised 2026-08-24, see the research banner):**

- **Prerequisite wire change (lands first, its own commit).** The UDP datagram envelope is `sessionId|type|body` today: no ordering guard of any kind, so a reordered datagram applies a stale position, and there is no time axis a buffer could sort samples onto. Add two fields: `epoch` (per-sender monotonic tick; receiver keeps a per-type watermark and drops `epoch < watermark`, accepts equal) and `sentGameTime` (host campaign-clock seconds at send; one stamp per datagram, shared by every fleet in the batch). This is the Phase 20.1 epoch guard pulled forward; 20.1 retains `senderId`, chunking, and the watermark generalization. **Loss redundancy rides the same change:** each `NPC_FLEET_MOTION` / `FLEET_SNAPSHOT` datagram carries the previous 1–2 samples alongside the current one (a few dozen bytes at our sizes, verified trivial in 20.5 terms), so a single lost packet leaves the receive buffer whole and 2% loss stops being a tuning problem.
- **Mechanism: buffered interpolation, kinematically driven.** Received samples queue per mirror, ordered by `sentGameTime`. Each frame the interpolator evaluates the trajectory at `latestHostGameTime − 200 ms` (the fixed M1 delay ≈ 2 send intervals; Valve's 2-interval rule and Fiedler's 3× rule bracket it) and writes the result via `setLocation` + `setVelocity` + `setFacing`. **Mirrors never receive `setMoveDestinationOverride` again** — both banner failure modes are engine-steering artifacts (the mirror's own movement stats clamp the chase; the arrival radius eats orbit drift) and neither exists on the kinematic path. `setVelocity` is still written every frame because engine consumers read it; **facing derives from interpolated velocity** with a speed floor below which the last facing holds (an orbit-drifting fleet must not spin) — facing stays off the wire.
- **Timeline runs on the game clock.** The render cursor advances by campaign dt, so pause freezes mirrors for free and Phase 7b fast-forward needs no special case; transport concerns (RTT EWMA, keepalives, timeouts) stay on wall time. Cursor drift against the buffer is corrected by **time-scaling, never position blending**: dead zone of ±1 send interval, ×1.02 catch-up / ×0.96 slow-down outside it, drift measured over a 1 s EMA (Mirror's shipped constants; the dead zone is its documented anti-ping-pong guard). One FF interaction to spec when 7b lands: at 2× speed a wall-clock 10 Hz stream delivers half the samples per game-second, so either the send cadence follows game time or the buffer widens under FF — carried as a 7b line item below.
- **Hermite between samples, not lerp.** Velocity is already on the wire and is exactly the Hermite endpoint tangent (tangent = velocity × segment dt — the unit-parameter form overshoots without that scaling; fall back to lerp when the spline's arc length exceeds ~1.5× the chord). Fiedler measured linear interpolation at 10 Hz visibly pulsing on curved motion, and our fleets orbit. Explicitly skipped: second-order / curvature-aware dead reckoning — computed orbit-extrapolation error at Starsector orbit scales is ≤ 0.1 su even across two lost packets, below the arrival-jitter noise floor.
- **Starvation ladder** (replaces the old "decay after ~2 missed intervals" rule): buffer dry → extrapolate the last sample's velocity, capped at 250 ms (Valve's cap) → decay velocity to zero over ~200 ms and park. On traffic resume the cursor re-seats via time-scaling; no teleport below the snap threshold. A packet-starved mirror coasts briefly and parks; it never sails off unboundedly.
- **Hard-snap rules:** `locationId` change (system jumps already change location, so the cut signal costs no wire bit) and a two-radius distance backstop — corrections below R resolve through the ladder, above ~1.5×R hard-teleport (Unreal's 256/384 uu shape); R tuned on the shaped harness. The blunt 1 Hz `LOCATION_CORRECTION_EVERY` snap is **deleted, not subsumed**: with a buffer there is nothing left for it to correct. Any decay math that does run uses the frame-rate-correct form `1 − exp(−dt/τ)`, never `lerp(k·dt)` (frame-rate-dependent); unit-tested.
- **Logical position is what gets smoothed — no render-offset split.** Engines split render from logic only when the smoothed thing carries collision; mirrors have none, and a render offset would make a guest's click miss the sprite. Worst-case logical error is about one packet interval of travel (~15 su at fleet speeds), noise against sensor ranges in the thousands. One truth to record where interactions are arbitrated: the renderer shows mirrors ~200 ms in the past; the Phase 10/18 claim gate already absorbs that race, so this is documentation, not code.
- **Sender side needs nothing beyond the envelope.** `CoopNpcFleetMotionSmoother`'s segment-average velocity is exactly what Hermite endpoint tangents want; do not re-derive velocity guest-side.
- Thresholds, the delay constant, and blend windows remain **internal tunables recorded in code, not options** — tuned once on the shaped harness, values documented here when built. The 2026-08-20 banner already covers the artifact baseline on a clean link; the shaped-loopback pass (100 ms ± jitter + 2% loss, the 20.1 clumsy harness) remains for the loss/reorder/staleness behaviors.
- Applies to every `CoopFleetMirror` on whichever side renders it (guest: NPC mirrors + host-player mirror; host: guest-player mirror).
- Known risk, updated: the old spec's rubber-banding risk was intrinsic to extrapolation-first; under buffered interpolation the residual risks are cursor ping-pong (guarded by the time-scaling dead zone) and Hermite overshoot on degenerate segments (guarded by the arc-length lerp fallback). The QA gate stands unchanged: visually continuous motion, no oscillation.

**Milestone 2 — adaptive tiered cadence (`coop.net.CoopCadenceController`):**

- A small set of **discrete QA-certified tiers** (target: 5 / 10 / 20 Hz) for the UDP state streams only (`FLEET_SNAPSHOT`, `NPC_FLEET_MOTION`). `TIME_SNAPSHOT` stays fixed at 5 Hz (the Phase 7c dead zone is calibrated against it), battle status stays 2–5 Hz as specced, and TCP semantic streams (claims, deltas, results, lifecycle) are never touched.
- **Host-decided, per link, symmetric:** the host picks each `CoopPeerLink`'s tier from `CoopLinkQuality` (RTT EWMA, epoch-gap loss, outbound queue depth) and announces it on `LINK_STATUS`; both ends of that link apply it to their UDP state sends. Per-link tiers compose naturally with Phase 27 multi-guest (each peer link already carries its own quality).
- **Hysteresis, never flapping:** downshift immediately on a loss/queue threshold; upshift only after a sustained-clean window (~30 s). This is the published asymmetry (fast attack, slow recovery — Unreal's adaptive net frequency decays over 2 s → 7 s and speeds up immediately on change; ours is coarser because tiers are discrete). Tier changes are transparent: feed message + current tier on the 20.6 link HUD and `CoopSessionIntel`.
- **Adaptive interpolation delay (moved here from M1, 2026-08-24):** once `CoopLinkQuality` exists, the M1 delay constant becomes `ceil((sendInterval + jitterStdDev) / sendInterval) + 1` send intervals, clamped to [150 ms, 500 ms] (Mirror's dynamic-adjustment formula; jitter σ over a ~2 s EMA). A LAN link settles at the M1 value; a jittery WAN link widens on its own. Same certified-tier philosophy: the link picks, never the player. *(Research flag 2026-08-25: the fixed 200 ms at 10 Hz is 2 send intervals — below Fiedler's 3×-interval rule for 10 pps (he used 300 ms + 50 ms jitter). Depth-1 redundancy is what makes 200 ms survivable against isolated loss; only consecutive loss stalls it. The adaptive formula here is the proper fix for jittery links — treat the shaped-loopback pass as the test that decides whether 200 ms needs raising before M2 lands.)*
- **Redundancy depth becomes a tunable (added 2026-08-25):** depth stays 1 by default; expose depth 2 as the internal escape hatch for links with measured burst loss ≳10% (the RED/RFC 2198 crossover — Chrome ships distance-1 audio redundancy for the same reason). If the 20.1 conditional compression layer ever lands, deeper redundancy is nearly free in bytes (LZ77 dedupes the repeated sections); without it, depth 2 costs a third section's full size — check the wiretap histogram before enabling. Internal tunable, not a player option (Phase 28 list intact).
- **Fast-forward cadence rule (spec'd here, implemented with 7b if 7b lands first):** the UDP state-stream send interval is measured in *game* time, so FF raises the wall-clock send rate proportionally and the interpolation buffer keeps the same depth in game-seconds. (The alternative — widening the buffer under FF — leaves the mirror's render delay growing with the FF factor; rejected.) Bandwidth scales with the FF factor, which the tier ceiling already bounds.
- **Unify the existing degraded mode:** 20.1's UDP-blocked TCP fallback ("state stream onto TCP at a reduced 5 Hz") becomes simply the floor tier pinned while on the TCP path — one cadence mechanism, not two.
- Upshift to 20 Hz ships only if the 20.1 datagram-size histogram shows MTU headroom at double rate (chunk volume doubles too); otherwise certify 5/10 and leave 20 dark.
- Interest partitioning is already in place and stays: Phase 9 streams motion only for locations a player occupies, which is the by-system tier the prioritization literature recommends for this topology; M2 does not add per-entity distance priority.

**Explicit non-goals:**

- **No Phase 28 registry key for any rate** — this phase must leave Phase 28's not-configurable list intact and its registry diff empty. A player who wants "smoother" gets M1 unconditionally and M2 automatically; there is nothing to set.
- No AI-intent prediction (no pathfinding/assignment replication — pure kinematic interpolation from position + velocity samples).
- No second-order or curvature-aware dead reckoning (quantified negligible at our motion scales — see the M1 Hermite bullet) and no acceleration on the wire.
- No threshold-triggered sending (the DIS "send only on model deviation" pattern): under 2% loss a missed threshold update becomes an unbounded-duration error, and the fixed 10 Hz stream doubles as the liveness heartbeat. Bandwidth was never the constraint.
- No combat application (combat is local solo own-fleet or the status panel; Phase 22's tactical map does its own drawing).

**Decision points to settle before building:**

- **Partner-mirror inclusion in M1: SETTLED 2026-08-20 — included.** The live orbit-pair observation (banner above) showed the Phase 8 partner mirror is a worst-case offender, not a masked one: the destination-override treatment is exactly what produces the dead-zone freeze-hop at drift speeds.
- **M1 mechanism: SETTLED 2026-08-24 — buffered interpolation + kinematic drive** (research banner above). With it: envelope `epoch` + `sentGameTime` land as an M1 prerequisite; datagrams carry 1–2 redundant samples; the M1 delay is a fixed 200 ms with adaptive sizing deferred to M2. All four were explicit user decisions.
- **Tier set:** recommend certifying 5/10/20 but enabling the 20 Hz upshift only on histogram headroom (above).
- **Snap-radius/threshold values:** tuned on the shaped harness, then frozen and documented here — they are calibration, not configuration.

**Files:**

- Create `src/main/java/coop/fleet/CoopMotionInterpolator.java` (per-mirror sample buffer ordered by `sentGameTime`, Hermite evaluation with arc-length lerp fallback, game-clock cursor + time-scaling dead zone, starvation ladder, two-radius snap backstop; consumes the expected interval from the active tier once M2 exists)
- Create `src/main/java/coop/net/CoopCadenceController.java` (host-side per-link tier selection: thresholds + hysteresis; TCP-fallback floor pin; adaptive interpolation-delay formula)
- Modify `src/main/java/coop/net/CoopMessages.java` (datagram envelope: `epoch` + `sentGameTime`; later the tier field on `LINK_STATUS`), `coop/fleet/CoopNpcFleetMotion.java` + `CoopFleetSnapshot.java` senders (redundant-sample packing; later tier-driven emit interval), `coop/fleet/CoopFleetMirror.java` (kinematic drive: delete `driveMovement`'s `setMoveDestinationOverride` and `LOCATION_CORRECTION_EVERY`; velocity-derived facing with speed floor), `CoopFleetMirrorRegistry.java` (route motion through the interpolator; per-frame evaluate), `coop/net/CoopNetPump.java` (epoch watermark drop on the datagram drain), `coop/net/CoopLinkQuality.java` (feed the controller), `coop/ui/CoopLinkHud.java` + `coop/ui/CoopSessionIntel.java` (tier display)
- Unit tests: epoch watermark (reordered datagram dropped, equal accepted), Hermite math (tangent scaling; arc-length fallback; straight-line reduces to lerp), cursor time-scaling (dead zone, no ping-pong at a noisy boundary, pause dt=0 freeze), starvation ladder (cap → decay-to-stop → re-seat without teleport), snap cases (`locationId` change → hard cut; two-radius backstop), redundant-sample dedup, `1 − exp(−dt/τ)` frame-rate independence, hysteresis state machine, TCP-fallback floor pin

**Steps:**

- [x] **M1 prerequisite:** datagram envelope `epoch` + `sentGameTime` + redundant-sample packing + receiver watermark. Commit `feat: sequenced, stamped state datagrams`. *(BUILT 2026-08-24, `0e1871a`: sectioned envelope in `CoopMessages`, `CoopStreamClock` / `CoopDatagramRedundancy` / `CoopDatagramWatermark`, redundancy depth = 1 previous section, watermark keyed to session id; 20 unit tests.)*
- [x] **M1:** implement `CoopMotionInterpolator` (buffer, Hermite, game-clock cursor, ladder, snap rules), convert `CoopFleetMirror` to kinematic drive for both the player-mirror and NPC paths; acceptance is visually continuous motion with no oscillation. Commit `feat: mirror snapshot interpolation`. *(BUILT 2026-08-24, `9e4f935`: `CoopMotionInterpolator` + shared `CoopMotionTimeline` cursor, `driveMovement`/`LOCATION_CORRECTION_EVERY` deleted, velocity-derived facing with 5 su/s floor, set-fed samples via the `NPC_FLEET_SET` stamp; 22 unit tests. As-built calibration: delay 200 ms, dead zone ±100 ms, timescale ×1.02/×0.96, drift EMA 1 s, cursor re-seat at 1 s drift, extrapolation cap 250 ms, decay window 200 ms, teleport radius 2000 su, tangent guard 3× chord + 1 su, buffer cap 32. **Clean-link smoke VERIFIED in-game 2026-08-24** — orbit-pair glide smooth, ambient NPC motion smooth, zero coop errors across the session, coop frame cost ~0.1 ms avg with no frame over 16.7 ms, pause edge ~205 ms log-measured (after fixing a leftover Phase 18 `interactionDelayMs=1500` lever that the launch scripts had let persist in the host vmparams — scripts now strip all `-Dcoop.*`, commit `e4f8fc9`). **Still pending: the shaped-loopback loss/reorder pass** (100 ms ± jitter + 2% loss); radii/thresholds re-tuned there if needed. **Speed-probe baseline (2026-08-24, `CoopMotionSpeedProbe`, diagnostics-gated, 10 s windows):** host ≈ 0.99–1.00 (one mostly-straight partner mirror), guest ≈ **1.08–1.11 — and that is the healthy clean-link reading, not a defect**: the ratio is biased ≥ 1.0 by construction (60 fps path integral vs 10 Hz chord sum measures any curve longer), plus su-scale Hermite bow on near-stationary orbiters whose engine velocity does not match their near-zero displacement — phantom path length, no net displacement, invisible at campaign scale and user-confirmed so. Judged not worth re-tuning a verified build; if the shaped pass ever needs it, the lever is tightening the tangent guard or lerping segments with chords under ~1 su. Compare future probe readings against this baseline, and watch for sustained readings **below ~0.97** — that direction would mean mirrors genuinely under-covering ground.)*
- [ ] **M2:** implement `CoopCadenceController` + `LINK_STATUS` tier field + adaptive interpolation delay + HUD/intel/feed surfacing + TCP-fallback floor unification + game-time send cadence (FF rule); certify each shipped tier on the shaped matrix. Commit `feat: adaptive state-stream cadence`.
- [ ] Document the frozen calibration values in this section; update the Phase 28 not-configurable list's cadence entry if wording needs the tier mechanism named.

**Acceptance:**

- On a shaped 100 ms / 2% loss / jitter link, mirrored fleets move visually continuously: no per-snapshot stepping, no periodic correction pop, no overshoot/rubber-banding; a fleet that jumps or despawns cuts instantly, never glides.
- **Orbit-pair scenario (2026-08-20, the live worst case):** host and guest parked in orbit at the same planet see each other *glide* along the drift, and a stationary-drift mirror produces zero hard correction snaps on a clean link. A sustained-burn traveling fleet's mirror tracks it without the ~1 Hz catch-up teleport.
- A single lost datagram produces no visible artifact (the redundant samples cover it); a reordered datagram is dropped at the watermark, never applied.
- A packet-starved mirror coasts for at most ~250 ms, decays to a stop, and parks; it never diverges unboundedly from the last authoritative position. On traffic resume it re-seats through time-scaling, not a teleport.
- Shared pause freezes mirrors exactly where they are (game-clock cursor, dt = 0) and unpause resumes without a hop.
- Tier changes occur only between certified tiers, per link, with hysteresis (no flapping on a noisy link), and every change is announced (feed message + HUD + intel). With UDP blocked, the TCP fallback presents as the pinned floor tier — one code path.
- `TIME_SNAPSHOT`, battle status, and all TCP semantic streams are untouched at every tier; Phase 28's registry gains no key and the not-configurable list still has zero knobs.
- On a clean LAN at the default tier, the session is behaviorally identical to pre-Phase-29 apart from the smoothing itself; the only wire differences are the two envelope fields and the redundant samples (documented above), the tier never leaves default, and all existing unit + smoke tests stay green.

**Sources (2026-08-24 research pass):**

- Fiedler, *Snapshot Interpolation* / *State Synchronization* — gafferongames.com (10 Hz budget, 3×-interval buffer rule, the failed 200 ms extrapolation experiment, error-decay constants)
- Valve, *Source Multiplayer Networking* — `cl_interp 0.1` = 2 intervals, `cl_extrapolate_amount 0.25`, `cl_smoothtime 0.1`
- Mirror `SnapshotInterpolation.cs` — bufferTime = 2× interval, ±1-interval dead zone, ×1.02/×0.96 time-scaling, 1 s drift EMA, jitter-adaptive multiplier
- Unity Netcode for Entities / NGO docs — extrapolation cap 20 ticks, kinematic non-authority rigidbodies, 1 Hz axial resync as interpolator input (not a teleport)
- Unreal networked movement docs + CMC defaults — simulated proxies set location/rotation/velocity from replication, `NetworkSimulatedSmoothLocationTime 0.100`, 256/384 uu two-radius snap, adaptive net frequency 2 s/7 s/30%
- Murphy, *Believable Dead Reckoning for Networked Games* (Game Engine Gems 2) — projective velocity blending, one-interval blend window, acceleration-noise warning (kept as the reference for the starvation-ladder correction math)
- Ryan & Oliver, DIS dead-reckoning modelling + IEEE 1278.1 Table 25 — second-order-vs-first-order PDU counts, threshold/heartbeat defaults (grounds the no-threshold-sending non-goal)
- Overwatch GDC 2017 — ~2-tick interp delay, buffer control by ~5% time dilation
- Gambetta, *Entity Interpolation* — render-in-the-past rationale for non-predicted entities

## V1 Playable Acceptance Checklist

- [ ] Mod loads cleanly with no coop stack trace in `starsector.log`.
- [ ] Host/guest handshake succeeds on identical installs.
- [ ] Host/guest handshake fails on a deliberate manifest mismatch (game version / mod id / mod version / coop commit) with a readable diff. *(File checksums are inert placeholders — script-sandbox limitation per the Phase 5 acceptance correction; Phase 12b carries the bounded hashing spike.)*
- [ ] Iron-mode coop start and iron-mode save conversion are refused.
- [ ] Fresh coop game fingerprints match after seed lock.
- [ ] HIGH-impact random forks from design section 7.5 are present and audited.
- [ ] Guest cannot *independently/locally* desync the clock or fast-forward. (Guest **can** request a **shared** pause — via combat-start or by opening a blocking screen — which the host folds into the broadcast clock so both stop together; that is shared, not independent.)
- [ ] Host fast-forward (toggle mode) mirrors to the guest — both clients speed up together and campaign dates stay aligned. *(Phase 7b)*
- [ ] After induced or accumulated drift, the guest's campaign date converges to the host's within the dead zone and never steps backward. *(Phase 7c)*
- [ ] A coop save loaded solo runs the orphan sweep (frozen NPC mirrors + partner fleet removed, logged); the host save remains fully solo-playable. *(Phase 12b)*
- [ ] Remote fleet mirror updates at campaign rate.
- [ ] Other player's fleet is visible outside normal sensor range, rendered in own faction color, and labeled with username.
- [ ] NPC fleet population is host-authoritative: the guest renders exactly the host's NPC fleet set (existence + faction + roster) and runs no native NPC fleet spawning or AI.
- [ ] Pirate/Pather bases are host-authoritative: the guest runs no base managers (extended suppression list verified by the coverage diagnostic) and shows exactly the host's base set — or the documented SUPPRESS-ONLY fallback is in effect. *(Phase 13)*
- [ ] Shared reputation converges after a host-triggered rep change.
- [ ] Guest conceptual reputation baseline starts at 0 before host-owned shared rep deltas are applied.
- [ ] Shared mission/bar/contact/bounty pool is host-authored and same mission acceptance is first-come.
- [ ] Market contents (shop stock + hireable officers/mercenaries) are host-authored and identical on both clients; transactions apply to the host's canonical market.
- [ ] Salvage/exploration of a world entity is consumed on both clients (no double-loot); loot is per-player.
- [ ] Faction-to-faction relationships converge to the host value.
- [ ] World-affecting abilities are host-arbitrated; purely-local abilities are not.
- [ ] A hostile NPC fleet can detect, chase, and engage the guest: vanilla pursuit of the targetable mirror + pre-contact `ENGAGE_GUEST` handoff; the guest pilots that battle locally (not PvP); the mirror never takes silent autoresolve damage.
- [ ] A host patrol can stop the guest for a customs/inspection dialog the guest resolves locally; guest-initiated proactive parley is host-only.
- [ ] Special bar-event offers are host-authored, identical on both clients, and one-time offers are first-come.
- [ ] Station storage is per-player and private.
- [ ] Either player can engage and pilots their own battle locally; the other player is held by a shared pause and follows via the live battle-status panel (verified for both host-fighter and guest-fighter directions).
- [ ] Combat-start pauses the shared clock; guest battles drive the pause to the host via `PAUSE_INTENT`.
- [ ] Battle status streams at 2–5 Hz over TCP from the engaging client; the non-engaged player has no combat engine running and cannot issue combat commands. *(Revised 2026-06-10 from "60 Hz combat snapshots" — rendered spectating verified infeasible, see Phase 14 engine facts.)*
- [ ] Disconnect mid-combat: the engaging side finishes locally (unsent result logged + reconciled by host authority next session); the spectator exits cleanly; a pre-battle autosave exists on the engaging client. *(Revised 2026-06-10 from freeze/countdown/save-exit/rollback — programmatic save-loading does not exist.)*
- [ ] The solo fighter keeps 100% of their own spoils (no 50/50 split in v1); NPC fleets destroyed in battle disappear on both clients and shared rep converges.
- [ ] No joint combat / CMC is present (joint piloting is Phase 22, post-V1).
- [ ] Coordinated saves work: every host save triggers a guest autosave, and the session resumes from the two saves (seed lock + campaign UUID accept). *(Revised 2026-06-10 from the cancelled file-export design — see Phase 16 redesign note.)*
- [ ] Fleet wipe respawns with Wolf + 5,000 credits.
- [ ] Same-dock shared screens are mutexed.
- [ ] Either player can raid or bombard an NPC colony; disruption, stability, deciv, and rep effects converge on both clients; loot stays with the raider. *(Phase 24, milestone 1)*
- [ ] Either player can colonize; the colony exists with identical size/conditions/industries on both engines and survives the coordinated save/load cycle. *(Phase 24)*
- [ ] Either player can manage shared-colony industries from their own client; the other player sees the result; the interaction gate prevents simultaneous editing; colony income splits per the configured rule (50/50 hardwired in V1 — decided 2026-06-10). *(Phase 24)*
- [ ] An NPC expedition against a shared colony shows the mirrored warning intel (countdown entry, not just a feed line) on both clients before it arrives. *(Phase 24, decided 2026-06-10)*
- [ ] Disconnect ends the session cleanly.
- [ ] A 30-minute session at 100 ms RTT / 2% loss / jitter / reorder (shaped loopback) plays without mirror freezes, stale-snapshot rubber-banding, or session teardown. *(Phase 20)*
- [ ] A 30 s mid-session link outage resumes through the reconnect grace window (world paused, full rebroadcast on resume); a fully blocked UDP path degrades to the logged TCP fallback with mirrors still moving. *(Phase 20)*
- [ ] The host's connection-doctor log names the reachability tier (IPv6 / port-forward / UPnP / CGNAT-detected / VPN) and a real-Internet or VPN session passes the movement/interaction smoke tests. *(Phase 20)*
- [ ] Link quality is visible in-game (ping/state HUD widget + "Coop Session" intel entry) and link transitions (TCP fallback, recovery, reconnect grace) surface as feed messages and the reconnect dialog — not log-only. *(Phase 20.6)*
- [ ] A host that loads its save lands in the lobby force-paused and the world starts only when every connected player readies up; a guest connecting mid-handshake sees the connecting dialog, not a running world. *(Phase 21 — closes the 2026-08-24 host-starts-unpaused issue)*
- [ ] Seed-lock, session-resume, and handshake failures surface the desync dialog naming cause and remedy — not a log-only session end. *(Phase 21)*

## Explicit V1 Non-Goals

- No CMC dependency.
- No joint combat / joint piloting (both players piloting in the *same* battle simultaneously) — out of V1. **Path identified 2026-06-10:** input forwarding into a single host-side battle (replicate the pilot, not the battle — proven by the MIT "Cooperative Combat" mod) makes this feasible post-V1; sketched as **Phase 22**. V1 combat stays solo own-fleet: one piloting player per battle, the other follows via the battle-status panel.
- No rendered live combat spectating (the original 60 Hz "puppet battle" view) — verified INFEASIBLE against the engine 2026-06-10 (Phase 14 engine facts): combat state requires a real player fleet, projectiles/fighters cannot be replicated, and puppet ships run local AI/physics. The v1 spectator is the 2–5 Hz battle-status panel; Phase 22's milestone 0 (post-V1) upgrades it to a drawn tactical map (positions/facings, no projectiles) — still not a rendered battle.
- ~~No NPC pursuit of the guest across the map~~ — **withdrawn 2026-06-10 (same day, after pushback):** pursuit IS in scope. The mirror stays targetable so vanilla AI chases it; silent autoresolve is prevented by Phase 14's pre-contact handoff + contact-time battle-eject instead of a permanent ignore flag (which is 12b-interim only). See Phase 14's NPC-threat design.
- No guest-initiated *proactive* parley (tribute / demand-surrender dialogs the guest opens against host-owned fleets) — host-only in v1, because it would require replicating full fleet disposition (≈ dialog replication). The guest can still initiate combat with any fleet, and NPC-initiated dialogs against the guest (customs/inspection) *are* handled (Phase 14).
- ~~No colonies, raids, or industries~~ — **withdrawn 2026-06-10 (same day, after the feasibility assessment):** rescoped INTO V1 as **Phase 24** — feasible as a *shared* player faction riding the existing market/delta/gate machinery (vanilla listeners capture every colony and raid outcome). What stays a non-goal: **separate per-player factions** — assessed and rejected in Phase 24 (engine hardwires one player faction; do not relitigate without a new engine surface).
- No Nex faction-war support beyond warn-don't-block compatibility.
- No direct player-to-player trade UI.
- ~~No reconnect/resume~~ — **scoped 2026-06-10 (Phase 20):** *in-session transport reconnect* (socket drops, guest process still alive) IS in scope via the reconnect grace window — WAN blips are routine and must not end the session. What remains out of scope is relaunch-from-save rejoin mid-session; a guest whose game *process* dies resumes via the Phase 16 coordinated-saves path next session.
- No PvP.
- No per-system / regional authority transfer. The host is the **sole** authority for all campaign and NPC-fleet state at all times, regardless of where either player is; the guest never authoritatively simulates a system. (Decided 2026-05-30: single authority is the fewest-moving-parts, least-desync-surface option, which is the v1 priority. See the Maybe entry for the rejected alternative.)
- No 3+ player *gameplay* in v1 — but assessed FEASIBLE (up to 3 guests) in Phase 20.5, and the wire format ships N-ready (`senderId` envelope + `CoopPeerLink` peer table, capacity-enforced to 1). Enablement is a post-v1 phase — see **Phase 27** (promoted from the Maybe entry 2026-06-10).
- No rendezvous/relay server infrastructure and no UDP hole punching (needs hosted infra + ops); reachability is IPv6 / port-forward / UPnP / VPN per Phase 20.3. No TLS — traffic is plaintext with password gatekeeping; the VPN tier is the confidentiality answer.
- No in-game text chat.
- No title-screen / pre-load lobby UI — the main menu and save-load picker have **no modding API** (verified 2026-06-10 against the API sources; hard engine limit, not a scoping choice). Lobby UX is in-campaign: minimal link surfacing in Phase 20.6, full lobby with ready-up in Phase 21 (in V1 since 2026-08-24, after 20). Launch configuration stays at the system-property level.
- No Java-agent instrumentation or broad global RNG patching; only targeted classloader source forks named by the v1 plan are allowed.

## Maybe (Post-V1 Ideas — Not Committed)

> Researched candidates that are out of committed v1 scope. Each needs a design decision before it becomes a phase. Do not implement from this section without promoting it into a numbered phase first.

### Maybe: Guest-originated / UI pauses

> **PROMOTED to v1 (2026-05-30):** this is now committed scope, built in Phase 11 via `CoopSharedPauseCoordinator` (Option B — shared pause as OR-of-intents). The host computes `paused = hostPauseIntent || guestPauseIntent || eitherInCombat` and broadcasts it on `TIME_SNAPSHOT`, so both clocks always stop together (no desync). Two triggers assert `guestPauseIntent`: **combat-start** and the guest **opening any vanilla-blocking screen** (map/fleet/character/refit/cargo/intel).
>
> **Residue numbered as Phase 25 (2026-06-10):** the original "what remains Maybe" note here claimed the discretionary manual guest pause was unbuilt — that was stale; Phase 11 as built ALSO ships the manual key pause (guest `GENERAL_PAUSE` → `PAUSE_INTENT(KEY, !observedPaused)`, confirmed in-game). The genuine residue — the `coop.allowGuestPause` strictness toggle and guest-requested shared **fast-forward** (AND-of-intents, deferred at Phase 7b) — is now sketched as post-V1 **Phase 25 (Guest Time-Control Polish)**. The open questions below were all settled by Phase 11's as-built decisions (unpause authority = host overrides key but not screen pauses; all blocking screens auto-pause; `intentSeq` debounce) except the toggle default, which Phase 25 carries. The text below is retained as the original analysis.

**Question (raised 2026-05-30):** Should the guest be able to pause the shared campaign — e.g. when it opens a UI screen, or by pressing pause — instead of being fully clock-locked to the host? Possibly behind a toggle.

**Why it comes up:** Phase 7 makes the host the sole clock owner. `CoopCampaignInputBlocker` consumes `GENERAL_PAUSE` on the guest, and `CoopTimeLock.apply()` calls `sector.setPaused(hostPaused)` every frame from the 5 Hz `TIME_SNAPSHOT`. So the guest cannot pause at all — including the implicit pause Starsector applies when a player opens a blocking screen (map, fleet, character, refit, cargo, intel). The guest reading a menu has no way to stop the shared world, which is the friction behind this question. This directly relaxes the v1 invariant in the playable checklist ("Guest cannot pause or fast-forward independently") and Phase 7's acceptance, so it must be a conscious change, not a silent one.

**Engine facts that constrain the options** (see `mods/coop/docs/starsector-runtime-limitations.md`):
- `setPaused`/`isPaused` is a real lever read every frame — a *shared* pause is fully enforceable on both clients.
- There is **no public clock-setter and no drivable fast-advance** *(superseded in part 2026-06-10: Phase 7c verified `getCal()` IS public and the clock timestamp IS MethodHandles-settable — see Phase 7c's engine facts; retained here as the original analysis)*, so a guest cannot "catch up" after running behind. Any pause that stops only the guest's clock while the host advances desyncs the campaign day permanently. This is the same constraint that forced `maybeHoldHostPausedUntilSessionReady` at connect time.

**Options:**
- **A — Status quo (no guest pause).** Simplest, zero desync risk, but the guest has no agency to stop the world to read/plan.
- **B — Guest-requested *shared* pause (recommended if we do this).** Treat pause as the logical OR of both players' pause intents: the shared clock runs only when **neither** player wants it paused. The guest's pause key (and optionally any guest UI screen that vanilla auto-pauses) sets a `guestPauseIntent`; the host folds it into the effective `paused` it already broadcasts via `TIME_SNAPSHOT`, so both clocks stay locked together. No desync because the host clock itself stops. Natural co-op semantics ("either player can call a timeout").
- **C — Local-only guest pause.** Rejected: with no catch-up lever, the guest falls permanently behind the host clock. Not viable in v1's architecture.

**Sketch if promoted to a phase (Option B):**
- Add guest→host `PAUSE_INTENT` (paused/unpaused) reliable TCP messages; or carry a `guestPauseRequested` field the guest reports.
- Host tracks `hostPauseIntent` + `guestPauseIntent`; effective `paused = hostPauseIntent || (allowGuestPause && guestPauseIntent)`. Keep `setPaused(effective)` host-side; existing `TIME_SNAPSHOT` mirroring already carries it to the guest unchanged.
- Stop unconditionally consuming `GENERAL_PAUSE` on the guest in `CoopCampaignInputBlocker`; instead intercept it to flip `guestPauseIntent` and forward it (do not `setPaused` locally — let the host's snapshot drive the actual clock so the two never diverge).
- Optional UI-aware variant: when the guest is showing a blocking dialog/screen (`CampaignUIAPI.isShowingDialog()` and friends), auto-assert `guestPauseIntent` so the shared world freezes while the guest is in a menu, releasing on close. Pairs cleanly with the Phase 10 interaction gate, which already tracks guest dialog open/close.
- **Toggle:** host-owned `data/config/settings.json` flag (e.g. `coop.allowGuestPause`, default TBD) so a host can keep the strict v1 behavior. Toggle is authoritative on the host since the host computes the effective pause.

**Open questions to settle before building:**
- Unpause authority: if either player paused, can either player unpause, or only the player who paused (and the host as override)? OR-of-intents means each player clears only their own intent; decide whether the host can force-clear the guest's.
- Which guest UI screens should imply a pause (all blocking screens, or only specific ones), and do we want that auto-pause at all vs. an explicit key only.
- Default for `coop.allowGuestPause` and whether it relaxes the "Guest cannot pause independently" line in the V1 Playable Acceptance Checklist (it would need an explicit edit/footnote there).
- Pause-toggle spam/race over the network (debounce; last-writer-wins per player intent keyed by a sequence).

### Maybe: Post-v1 replication candidates from the 2026-06-10 world-content inventory

Deferred-not-cancelled items, recorded with their sketches so promotion is cheap (each needs a design decision first):

- **Slipstream replication** — ~~suppress guest `SlipstreamManager` + host broadcasts the stream set (config key + per-stream placement params, rebuilt via `SlipstreamBuilder`)~~ **numbered as Phase 26 milestone 1 (2026-06-10)** with a corrected mechanism: the builder consumes RNG, so param rebuild diverges — replicate the finished segment polyline instead (`addSegment` is public, segments immutable after build).
- **Abyss encounter-point replication** — ~~host broadcasts its EP set (id + position)~~ **numbered as Phase 26 milestone 2 (2026-06-10)** with a corrected model: EPs are transient per-player probe points, not world state — replicate each encounter *outcome* (`ENC_SPAWN` with position; EP id/depth/nearest all derive from position), and the existing `AbyssalRogueStellarObjectEPEC` fork makes guest re-execution deterministic, exactly as intended. Restores co-op abyss exploration.
- **Pirate/Pather base *reconstruction*** — if Phase 13 invoked the pre-authorized SUPPRESS-ONLY fallback, the corrected reconstruction spec (constructor side-effect notes, `isLarge` via MethodHandles, post-construction `PirateActivityIntel` cleanup) stays in Phase 13 for later promotion.
- **Rendered live combat spectating** — recorded as INFEASIBLE with current engine surface (Phase 14 engine facts). Re-evaluate only if a future Starsector version adds an observer/replay combat mode; do not attempt the puppet-battle route again. The sanctioned substitutes are now numbered: Phase 22 milestone 0 (tactical-map observer) for watching, Phase 22 piloted co-op (input forwarding + out-of-band video) for participating.
- **Sensor-ghost cosmetics for the guest** — if ghost atmosphere is missed, a cosmetic-only subset (no encounter/story ghost types) could run guest-side; needs a filter inside `SensorGhostManager`'s creator list, i.e. a fork or a custom manager. Low value, only worth bundling with other work.

### Maybe: Guest-save recovery from the host's guest snapshot

**Scenario (decided "keep + sketch" 2026-06-10):** a guest loses their save. Today that can strand the campaign for the pair: a fresh re-roll with the same seed plus `-Dcoop.adoptCampaignId=true` passes the identity check but hard-rejects at the fingerprint once host campaign state has drifted (deciv, market changes) — by design (the Phase 6b mutability contract), with no heal path. The Phase 16 `CoopGuestSnapshot` (written into the host save on every host save, deliberately write-only in v1) holds the guest's fleet/cargo/credits/officers — the raw material for recovery.

**Sketch:** guest re-rolls fresh with the same seed → adopts campaignId → host pushes the snapshot to rebuild the guest's fleet/cargo/credits → host replays the world-skeleton mutations the fresh guest world is missing (the `DECIV`/`OBJECTIVE_OWNERSHIP`/`GATE_ACTIVATED` ledger, base set, market state) so the fingerprint check passes. The replay piece is the real work: the host must retain (or recompute from current state) the cumulative skeleton-delta set, not just broadcast current snapshots. Cheaper alternative to decide at promotion time: extend the adopt flag to a consent-based *fingerprint bypass* — near-zero code, but knowingly accepts world divergence that the rebroadcast backstop must then absorb (and structural divergence it cannot).

**Why deferred:** rare event, real work, and v1 policy explicitly absorbs it (guest save = coop-only; host save canonical). The write-only snapshot keeps the option open at near-zero cost.

### Maybe: Multi-guest enablement (host + 2–3 guests)

**Promoted — numbered as Phase 27 (2026-06-10).** Assessed FEASIBLE in Phase 20.5; the transport ships N-ready (`senderId` envelope, `CoopPeerLink` peer table, broadcast/unicast routing), so enablement is gameplay arbitration plus a QA matrix, not a transport rewrite. The full work list (per-peer session table, `senderId`-keyed dispatch, mirror relay, pause OR-over-set, claim-owner identity, `ENGAGE_GUEST` arbitration, N-way saves, per-peer reconnect grace, the 4-player QA matrix as the real cost) now lives in Phase 27.

### Maybe: Rendezvous / relay server + TLS

Out of v1 scope (Phase 20.3 Tier 4 / 20.4). If the IPv6 + port-forward + UPnP + VPN coverage proves insufficient in practice: a tiny hosted rendezvous service would enable UDP hole punching for hosts behind CGNAT (and could double as a lobby browser), and a TURN-style relay is the universal-but-costly fallback. TLS (`SSLEngine`) would need an in-sandbox viability spike plus a cert/trust UX decision; until then the VPN tier is the confidentiality path. All three need infrastructure or UX decisions that don't belong in v1.

**Added 2026-08-25 (research pass):** per-packet AEAD is the concrete upgrade shape, and it is *required before the mod's public release* (the v1 bearer-token + challenge-echo floor covers only the private-soak audience). Production references: netcode.io encrypts and authenticates every packet with ChaCha20-Poly1305 and binds connect tokens to the source IP:port; Valve's GameNetworkingSockets uses per-packet AES-GCM-256. For this codebase the likely path is a shared key derived at the (TCP) lobby exchange, applied per-datagram with `javax.crypto` ChaCha20-Poly1305 (Java 11+; needs a sandbox-legality spike like everything else) — a much smaller lift than `SSLEngine` TLS, and it removes the sniffable-sessionId and replay concerns in one move.

### Maybe: Per-system / regional authority (REJECTED for v1)

**Question (raised 2026-05-30):** Should the guest become authoritative for the system it is in when the host is not present there, instead of mirroring host packets?

**Decision: rejected for v1** (non-goal above). The v1 priority is ease of implementation and minimal bugs, and this is the single change that most works against both.

**Why it makes things worse, not better:**
- **The sector isn't partitionable.** NPC fleets cross hyperspace boundaries constantly (economy/trade routes span regions; patrols, raiders, bounty fleets roam; hyperspace is one shared location). Per-system authority requires an authority hand-off protocol on every boundary crossing — the MMO "zone server / interest management" problem, full of edge cases (fleet mid-jump, both players in hyperspace, multi-system routes).
- **Economy/intel are global.** Production, shortages, faction-war ticks, bounty/raid timers, and market stock are computed sector-wide on one authority (Phases 11–12). Guest authority would split the economy brain across two machines and reintroduce the exact dual-simulation divergence the single-host design exists to avoid.
- **It re-adds continuous reconciliation + save-merge.** Today only discrete combat results flow host←guest (Phase 15). Regional authority means continuous bidirectional sync of spawns/despawns/positions/economy deltas at the boundary, plus the canonical save (Phase 16) must merge a second source of truth.
- **The benefit is marginal.** The only latency-sensitive thing — the guest's own battles — is *already* local under solo own-fleet combat. Watching ambient NPC motion at 10 Hz is cosmetic, and Phase 9 already streams motion only for player-occupied locations, so there is little bandwidth to "save."

**If smoothness ever becomes the real ask (still post-v1):** add **client-side interpolation / dead-reckoning** of NPC mirrors in `CoopFleetMirror` (the mirror already carries velocity; extrapolate between snapshots, correct on the next packet). This keeps single authority and adds no reconciliation. Deferred even so, because extrapolation can introduce rubber-banding bugs and v1 prefers the dumb-but-correct 10 Hz snap. **Promoted 2026-06-10: this note is now Phase 29 milestone 1** (with adaptive QA'd-tier cadence as its milestone 2).

**When regional authority would genuinely make sense:** a future large-session / players-far-apart-for-long-stretches feature — i.e., the same territory as 3+ player support. Well past v1.
