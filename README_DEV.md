# Coop Mod Development

Run commands from PowerShell.

## Repository & Docs

This directory is the git repo (origin: `https://github.com/AyoKeito/starsector-coop`, **private**). The canonical project documents live in `docs/`:

- `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md` — the phased implementation plan (canonical; moved into the repo 2026-06-10 — pointer files remain at the old `K:\Starsector\` paths)
- `docs/COOP_MP_DESIGN.md` — the design document
- `docs/starsector-runtime-limitations.md` — engine/sandbox limits found during implementation
- `docs/phase11-rng-determinism.md` — RNG determinism evidence (pre-renumber name; belongs to what is now Phase 13)

Git workflow: run git from this directory (running it from `K:\Starsector` fails — that is not a repo, which is why pre-2026-06-10 sessions deferred their commits). Commit after each plan phase with the message listed in that phase, then push:

```powershell
git -C K:\Starsector\mods\coop add .
git -C K:\Starsector\mods\coop commit -m "<message from the phase>"
git -C K:\Starsector\mods\coop push
```

Do not commit `jars/` or `build/` (gitignored), and never commit decompiled game sources (`tmp_ff_analysis` stays outside the repo).

## Runtime Notes

Read `docs/starsector-runtime-limitations.md` before changing campaign scripts, networking, dependencies, or save-visible state. It records the Starsector sandbox and save-serialization limits found during Phase 3 TCP testing.

## Save-Visible State

The mod writes two things into the sector's persistent data. Both survive in the host save; neither is removed by the Phase 12b orphan sweep.

| Key | Written by | Read by |
| --- | --- | --- |
| `coop.seedLong`, `coop.seedString`, `coop.sectorFingerprint`, `coop.campaignId` | `coop.seed.CoopSeedSync` | seed lock (Phase 6b) |
| `coop.guestFleetSnapshot` | `coop.save.CoopGuestSnapshotStore`, from `CoopModPlugin.beforeGameSave()` | **nothing — deliberately write-only** |

`coop.guestFleetSnapshot` holds a `CoopGuestSnapshot`: the guest's fleet, cargo, credits and officers as the host last received them, XStream-aliased as `coopGuestSnap` (plus `coopGuestSnapShip`/`coopGuestSnapStack`/`coopGuestSnapOfficer`). It is refreshed on every host save and **never read back by v1 code. That is a decision (2026-06-10), not dead state — do not delete it.**

It exists for one scenario: a guest who loses their save. A fresh same-seed re-roll passes the campaign-id check but hard-rejects at the fingerprint once host campaign state has drifted, with no heal path, so the host save is the only surviving record of what the guest owned. The restore flow that would consume it is sketched in the plan's Maybe list ("Guest-save recovery"). Two consequences worth knowing:

- The store's `clear()` drops only the in-memory copy. It never removes the key from a save — an older snapshot is still the recovery material.
- A snapshot only appears once a guest has connected and sent one (`GUEST_SNAPSHOT`, every 30 s). A host that saves before that leaves whatever the previous session wrote.

## Build, Test, And Package

Use the repeatable build script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\build.ps1'
```

Equivalent direct Gradle command:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"
```

Run unit tests only:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat test"
```

Build only the mod jar:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat jar"
```

The packaged mod jar is written to:

```text
K:\Starsector\mods\coop\jars\coop.jar
```

Confirm the jar exists:

```powershell
powershell -NoProfile -Command "Test-Path 'K:\Starsector\mods\coop\jars\coop.jar'"
```

## Clean

Use the clean script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\clean.ps1'
```

This removes only:

```text
K:\Starsector\mods\coop\build
K:\Starsector\mods\coop\jars\coop.jar
```

## Launch Starsector

After a successful build, launch the game from the install root:

```powershell
powershell -NoProfile -Command "Set-Location 'K:\Starsector'; .\starsector.exe"
```

## Inspect Logs

Tail the active Starsector log:

```powershell
powershell -NoProfile -Command "Get-Content -Tail 200 -Path 'K:\Starsector\starsector-core\starsector.log'"
```

Show recent coop log lines:

```powershell
powershell -NoProfile -Command "Select-String -Path 'K:\Starsector\starsector-core\starsector.log' -Pattern 'coop|CoopModPlugin' | Select-Object -Last 50"
```

## Two-Client Local Coop Test

Create isolated host and guest Starsector copies under `K:\Starsector-coop-test`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\setup-two-client-test.ps1'
```

Build and deploy the current coop mod into both test clients:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\deploy-to-test-clients.ps1'
```

Launch host and guest:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-host.ps1' -Port 7777
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-guest.ps1' -HostAddress '127.0.0.1' -Port 7777
```

After both clients load a campaign, inspect coop log lines from both profiles:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\tail-two-client-logs.ps1'
```

Expected Phase 3 evidence:

```text
Host log: inbound PING and outbound PONG
Guest log: outbound PING and inbound PONG
```

## Agent Bridge And Starsector MCP (Phase 30)

`coop.debug.CoopAgentBridge` is a dormant localhost TCP listener for driving smoke checks from an agent instead of from two pairs of eyes. It is gated on `-Dcoop.debug.bridge=<port>`: with the property absent, unparsable, or `0`, no socket is opened and nothing is logged. It binds 127.0.0.1 only, accepts one client, and services a few commands per frame on the campaign thread.

Add the switch to either launch script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-host.ps1' -Bridge
powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-guest.ps1' -Bridge
```

| Instance | Property appended | Port |
| --- | --- | --- |
| host | `-Dcoop.debug.bridge=7801` | 127.0.0.1:7801 |
| guest | `-Dcoop.debug.bridge=7802` | 127.0.0.1:7802 |

The switch goes through the existing `-ExtraJvmProps` path, so the catch-all `-Dcoop.*` strip in `Set-CoopVmParams` clears a stale port from the previous run. A launch without `-Bridge` leaves no bridge property behind.

Seventeen verbs: `status`, `fleets`, `market`, `markets`, `barpool`, `survey`, `visibility`, `colonizable`, `landmarks` read; `teleport`, `pause`, `ability`, `setcr`, `give`, `objective`, `surveyset`, `expedition` act. Six of them carry shapes worth knowing before you diff two dumps:

| verb | shape |
| --- | --- |
| `status` | adds `pause`: `blockingScreenOpen` on both roles, plus `hostIntent` / `guestIntent` / `guestKeyIntent` / `guestScreenIntent` / `eitherInCombat` / `effective` on the host. When an advance stalls, that block says which term of the coordinator's OR is holding the clock. |
| `fleets` | a player fleet and its mirror on the other client are both keyed `coopFleetId: "player:<playerId>"`, so one logical fleet is one row on both instances. The local engine id stays in `engineId`. |
| `visibility` | `lines` is the probe's text dump; `view` is the same computation as a `coopFleetId` to visibility-level map, guest-actual against host-estimate, so the two sides' maps are directly comparable. |
| `markets` | enumeration only (`marketId`, `name`, `factionId`, `size`, `locationId`). It does not stock anything — that is `market`'s documented host-side dock equivalence. |
| `colonizable` | the uncolonized planets nearest the local player fleet, `limit` (default 10) and `maxLy` optional. `distanceLy` is 0 inside the fleet's own system and `distanceSu` is 0 outside it, so the pair sorts "here first, then nearest". The filter is vanilla's, and its authority is the core UI class `PlanetSurveyPanel` rather than `rules.csv` — two of the four location gates (`system_abyssal`, deep space) exist in no rule and in no API source. Survey level and unexplored ruins are reported rather than filtered: both block vanilla's colonize button, and both are things the run clears itself. |
| `landmarks` | hypershunts, cryosleepers, gates, stable locations and the gate hauler, filterable with `kinds`. Not all one-per-sector — a stock sector has 15-20 gates and more stable locations, which is what `kinds` and the default limit of 25 are for. The two colony-relevance ranges are read live off `ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS` and `Cryorevival.MAX_BONUS_DIST_LY` rather than copied, and are omitted rather than guessed if the read fails; vanilla measures them from the colony, not the fleet, so the row's `distanceLy` is not the distance the game tests. There is no `occupied` field on a stable location because vanilla deletes the entity when you build on it, so one that still exists is free. |

`ability` takes an optional `on`: absent is the plain toolbar press, `on: true` / `on: false` is an idempotent level for toggles like the transponder, which `activate()` alone can only re-arm.

The agent side is `tools/starsector-mcp`, a Node stdio MCP server that wraps both ports into `ss_status`, `ss_dump`, `ss_diff`, `ss_act` and `ss_advance_days`. All state comparison lives there; the bridge only serializes. `ss_diff` excludes `role`, `engineId` and `lines` by default (per-instance by nature, and the diff drowns in them otherwise); its `ignore` argument replaces that list. Setup, the `.mcp.json` registration block, the port env overrides and a worked example per tool are in `tools/starsector-mcp/README.md`. It is not part of the mod jar and ships no runtime dependency into the game.

Four things are deliberately not bridge verbs: market buy/sell, officer hire, bar-offer accept, and market open/close. Each of those is on the smoke checklist because a UI listener drives it (`PlayerMarketTransaction`, the dialog close-diff that produces a hire claim, snapshot-on-open). A bridge verb would call the engine method underneath the listener, which passes whether or not the listener is still wired up, so it would green-light exactly the breakage the check exists to catch. Those four stay manual, alongside the claim race and the motion and stealth feel passes.
