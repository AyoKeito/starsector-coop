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

Everything the mod writes into the sector's persistent data is in the table below. It all survives in the host save; none of it is removed by the Phase 12b orphan sweep. The point of the list is that a later audit can tell mod state from dead state, and that a rename of any class named here has to bring an XStream alias with it.

| Key | Written by | Read by |
| --- | --- | --- |
| `coop.seedLong`, `coop.seedString`, `coop.sectorFingerprint`, `coop.campaignId` | `coop.seed.CoopSeedSync` | seed lock (Phase 6b) |
| `coop.localPlayerId` | `coop.seed.CoopSeedSync.storeLocalPlayerId`, on the first co-op launch of the save | `CoopSeedSync.currentLocalPlayerId`, so a relaunch keeps the same player id |
| `coop.guestFleetSnapshot` | `coop.save.CoopGuestSnapshotStore`, from `CoopModPlugin.beforeGameSave()` | **nothing — deliberately write-only** |
| `coop.sessionStats` | `coop.stats.CoopSessionStats.writeInto`, via `CoopSessionStatsStore` | `CoopNetPump.sessionStats()`, which is what makes the stats page survive a reload |
| `coop.options.<key>`, `coop.optionsPolicyVersion` | `coop.config.CoopOptionsPolicy.persist` (host only) | `CoopOptionsPolicy.ensureSeeded`, where a stored value beats the install default |

Two sector-memory flags ride alongside: `$coopStatsPinned` (`CoopSessionStatsIntel`) and `$coopOptionsPinned` (`CoopOptionsPage`) remember whether the player pinned those intel pages.

`coop.sessionStats` holds a `CoopSessionStats` bean, XStream-aliased `coopStats`/`coopStatsPlayer`/`coopStatsLoss` in `CoopModPlugin.configureXStream`. The options keys and the two flags are plain strings, so they cost nothing to rename beyond the migration every existing save would need.

One class reaches a save without a key of its own: `coop.colony.CoopExpeditionWarningIntel` is an intel entry that also registers itself with `sector.addScript(this)`, so it serializes under its own class name with **no alias**. Renaming or moving it breaks every save that carries one. Guest saves are also missing the vanilla spawner and `BarEventManager` scripts the Phase 13 suppressors remove, which is why a guest save is co-op-only (see the plan's guest-save policy).

`coop.guestFleetSnapshot` holds a `CoopGuestSnapshot`: the guest's fleet, cargo, credits and officers as the host last received them, XStream-aliased as `coopGuestSnap` (plus `coopGuestSnapShip`/`coopGuestSnapStack`/`coopGuestSnapOfficer`). It is refreshed on every host save and **never read back by v1 code. That is a decision (2026-06-10), not dead state — do not delete it.**

It exists for one scenario: a guest who loses their save. A fresh same-seed re-roll passes the campaign-id check but hard-rejects at the fingerprint once host campaign state has drifted, with no heal path, so the host save is the only surviving record of what the guest owned. The restore flow that would consume it is sketched in the plan's Maybe list ("Guest-save recovery"). Two consequences worth knowing:

- The store's `clear()` drops only the in-memory copy. It never removes the key from a save — an older snapshot is still the recovery material.
- A snapshot only appears once a guest has connected and sent one (`GUEST_SNAPSHOT`, every 30 s). A host that saves before that leaves whatever the previous session wrote.

### Files in `saves/common`

Two, both written through `SettingsAPI`'s `...Common` calls, which append `.data` to every name they
are given. Neither is sector data, so neither costs a save migration or an XStream alias.

| File | Written by | Holds |
| --- | --- | --- |
| `coop_options.json.data` | `coop.config.CoopOptionsStore.writeOverrides` and the Phase 31 launcher | the player's option overrides, plus the launcher's one-shot `-D`-only keys |
| `coop_saves.json.data` | `coop.save.CoopSaveIndex.recordCurrentSave`, from `CoopModPlugin.afterGameSave` | one row per save the mod watched being written: `campaignId`, `saveDirName`, `characterName`, `level`, `gameDateTimestamp`, `gameDate`, `savedAtMillis`, `autosave`, `role`, `seedString` |

The save index is what lets the launcher answer "which save is this invite's campaign in?" — the
engine's own save list has no campaign id in it. Retention is 8 rows per campaign and 16 campaigns,
so the file stays three orders of magnitude under the engine's 1 MB write cap. `saveDirName` is read
inside the hook every time through a `MethodHandle` on `CampaignEngine.getSaveDirName()` and never
cached: the engine swaps that field to the folder being written for the duration of an autosave or a
save-into-a-new-slot. Rows are allowed to name folders the engine has since pruned; the reader stats
them. A row is skipped entirely when the sector has no `coop.campaignId`.

The launcher writes `coop.expectedCampaignId` into `coop_options.json.data` to say which campaign an
invite is for. `CoopModPlugin.publishLauncherProperties` republishes it as a system property and then
strikes it out of the file, exactly as it does `coop.adoptCampaignId`; `coop.save.CoopCampaignGuard`
reads it at `onGameLoad`, and warns without blocking when the loaded save belongs to a different
campaign.

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

### Launcher

`Coop Launcher.cmd` at the mod root is what a player starts. It resolves the install root two levels
up from itself and runs `<install>\jre\bin\javaw.exe`, so there is no JRE to install and no
execution-policy prompt.

| Thing | Where |
| --- | --- |
| Source set | `src/launcher/java`, package `coop.launcher` |
| Tests | `src/test/java/coop/launcher`, in the main test source set |
| Jar | `jars/coop-launcher.jar`, from `launcherJar`; `build` depends on it |
| Runtime classpath | `jars/coop-launcher.jar`, `jars/flatlaf.jar`, `jars/coop.jar`, `starsector-core/json.jar`, `starsector-core/log4j-1.2.9.jar` |
| Log | `mods/coop/coop-launcher.log`, next to the `.cmd` |

FlatLaf (Apache-2.0) is fetched by Gradle into `jars/flatlaf.jar` via `copyLauncherLibs` and sits on
the `.cmd`'s classpath alongside `coop-launcher.jar`.

**The launcher writes two files outside `saves/common`.** `CoopInstallFixer` applies the two install
edits the mod itself cannot reach, on a **Fix** button hanging off the row that failed:

- `<install>\vmparams`, ported from `Set-CoopVmParams` in `scripts/launch-host.ps1`. Copy to
  `vmparams.backup` unless one exists, drop every existing `coop-forks.jar` entry in any spelling
  `CoopVmparamsText.isForksEntry` accepts, insert `..\mods\coop\jars\coop-forks.jar;` after the
  ` -classpath ` marker, write the bytes back through `ISO-8859-1` with no trailing newline. Refused
  when the file has no ` -classpath `, spans several lines, or the install has no `jre\bin\javaw.exe`
  (a modded-JRE `.bat` setup, whose `vmparams` the JVM never reads). `-Dcoop.*` is never stripped;
  that row stays a warning about flags somebody set on purpose.
- `<install>\mods\enabled_mods.json`, re-emitted in the vanilla two-space shape with `"coop"`
  appended and the other ids in their original order. Created when absent; refused when the file is
  there and does not parse.

An `AccessDeniedException` (a game under `Program Files`) offers an elevated relaunch:
`powershell -NoProfile -Command Start-Process -Verb RunAs` on `java.home\bin\javaw.exe` with the
running `java.class.path` and `CoopLauncherApp.APPLY_FIX_FLAG`, after which the unelevated instance
closes. The classpath's double quotes are built PowerShell-side with `[char]34` so `ProcessBuilder`
never sees a double quote to swallow. `CoopLauncherApp.elevatedRelaunchCommand` is unit-tested;
`elevatedRelaunchFailure` (the UAC answer) is not testable and is kept to one `waitFor` and a log
line.

**`starfarer.api.jar` is not on that classpath, on purpose.** The launcher reuses `CoopPortMapper`,
`CoopConnectionDoctor` and `CoopOptionsRegistry` out of `coop.jar`, and none of them link to the game
API on the paths the launcher walks. Any launcher code path that reaches the API is a bug in the
launcher, not a missing entry in the classpath: fix the code. `sourceSets.launcher.compileClasspath`
in `build.gradle` leaves the API jar out so the compiler catches it first.

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

On screen, both clients draw a coop line under the vanilla fps overlay (top-right): `HOST · session active`, `GUEST · session active · paused by host`, and on the guest the clock drift in whole game-hours once it reaches an hour. `-Dcoop.hud.disable=true` turns it off. The guest's New Game dialog shows the join target on its Continue option and pins seed, sector size and star age; `-Dcoop.sectorSize=small|normal` and `-Dcoop.sectorAge=young|average|old|mixed` override the panel defaults on both roles when a non-default world is wanted. `-Dcoop.clock.disable=true` turns off the Phase 7c clock reconciler (guest only). The Phase 20 networking properties (`coop.hostPort`, `coop.connectHost`, `coop.connectPort`, `coop.password`, `coop.maxGuests`, `coop.reconnectGraceSeconds`, `coop.portMapping`, `coop.debug.wiretap`, `coop.debug.wiretapSample`) are documented with their defaults in `docs/CONNECTIVITY.md`, "Configuration".

Rejoining after the guest quits: load the guest's coordinated autosave, not New Game. A fresh same-seed campaign is rejected at seed lock unless the guest is launched with `-AdoptCampaign`, which discards the guest's progress.

Expected Phase 3 evidence:

```text
Host log: inbound PING and outbound PONG
Guest log: outbound PING and inbound PONG
```

## Agent Bridge And Starsector MCP (Phase 30)

`coop.debug.CoopAgentBridge` is a dormant localhost TCP listener for driving smoke checks from an agent instead of from two pairs of eyes. It is gated on `-Dcoop.debug.bridge=<port>`: with the property absent, unparsable, or `0`, no socket is opened and nothing is logged. It binds 127.0.0.1 only, accepts up to four clients at once, and services a few commands per frame on the campaign thread.

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

Nineteen verbs: `status`, `fleets`, `cargo`, `market`, `markets`, `barpool`, `survey`, `visibility`, `colonizable`, `landmarks` read; `teleport`, `pause`, `ability`, `setcr`, `give`, `addship`, `objective`, `surveyset`, `expedition` act. Seven of them carry shapes worth knowing before you diff two dumps:

| verb | shape |
| --- | --- |
| `status` | adds `pause`: `blockingScreenOpen` on both roles, plus `hostIntent` / `guestIntent` / `guestKeyIntent` / `guestScreenIntent` / `eitherInCombat` / `effective` on the host. When an advance stalls, that block says which term of the coordinator's OR is holding the clock. |
| `fleets` | a player fleet and its mirror on the other client are both keyed `coopFleetId: "player:<playerId>"`, so one logical fleet is one row on both instances. The local engine id stays in `engineId`. |
| `cargo` | the local player fleet's load and its three independent limits — `cargoSpace`, `fuelSpace`, `personnel`, each `capacity` / `used` / `free`, where `free` is vanilla's own `$cargoRoom`-style subtraction and goes negative when the fleet is over. `overloaded` is the OR of the three and `over` names which. Each instance answers for its own fleet, so a `cargo` diff is two different fleets, not a desync check. |
| `visibility` | `lines` is the probe's text dump; `view` is the same computation as a `coopFleetId` to visibility-level map, guest-actual against host-estimate, so the two sides' maps are directly comparable. |
| `markets` | enumeration only (`marketId`, `name`, `factionId`, `size`, `locationId`). It does not stock anything — that is `market`'s documented host-side dock equivalence. |
| `colonizable` | the uncolonized planets nearest the local player fleet, `limit` (default 10), `maxLy` and `neutralOnly` optional. Rows carry the planet's location-local `x`/`y` — hand `teleport` those with the `systemId`, or hand it the `planetId` as its `entityId`; planets orbit, so deriving the pair from the orbit definition is work with a wrong answer at the end of it. `marketsInSystem` counts economy markets in the planet's system and `neutralOnly` keeps only the 0 rows, which is "somewhere no faction is sitting" without a cross-reference against `markets`. `distanceLy` is 0 inside the fleet's own system and `distanceSu` is 0 outside it, so the pair sorts "here first, then nearest". The filter is vanilla's, and its authority is the core UI class `PlanetSurveyPanel` rather than `rules.csv` — two of the four location gates (`system_abyssal`, deep space) exist in no rule and in no API source. Survey level and unexplored ruins are reported rather than filtered: both block vanilla's colonize button, and both are things the run clears itself. |
| `landmarks` | hypershunts, cryosleepers, gates, stable locations and the gate hauler, filterable with `kinds`. Not all one-per-sector — a stock sector has 15-20 gates and more stable locations, which is what `kinds` and the default limit of 25 are for. The two colony-relevance ranges are read live off `ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS` and `Cryorevival.MAX_BONUS_DIST_LY` rather than copied, and are omitted rather than guessed if the read fails; vanilla measures them from the colony, not the fleet, so the row's `distanceLy` is not the distance the game tests. There is no `occupied` field on a stable location because vanilla deletes the entity when you build on it, so one that still exists is free. |

`ability` takes an optional `on`: absent is the plain toolbar press, `on: true` / `on: false` is an idempotent level for toggles like the transponder, which `activate()` alone can only re-arm.

`addship{variantId, count?}` adds combat-ready ships to the local player fleet through the engine's fleet factory — `give`'s counterpart, and the way out of a one-ship test fleet that overloads at 200 supplies. The variant is validated against the spec store first, because `createFleetMember` substitutes a placeholder hull for an unknown id instead of refusing it. `count` defaults to 1 and is capped at 20. The fleet hash goes to the log before and after, since that hash is what makes the replicator resend the roster.

The agent side is `tools/starsector-mcp`, a Node stdio MCP server that wraps both ports into `ss_status`, `ss_dump`, `ss_diff`, `ss_act` and `ss_advance_days`. All state comparison lives there; the bridge only serializes. `ss_diff` excludes `role`, `engineId` and `lines` by default (per-instance by nature, and the diff drowns in them otherwise); its `ignore` argument replaces that list. Setup, the `.mcp.json` registration block, the port env overrides and a worked example per tool are in `tools/starsector-mcp/README.md`. It is not part of the mod jar and ships no runtime dependency into the game.

Four things are deliberately not bridge verbs: market buy/sell, officer hire, bar-offer accept, and market open/close. Each of those is on the smoke checklist because a UI listener drives it (`PlayerMarketTransaction`, the dialog close-diff that produces a hire claim, snapshot-on-open). A bridge verb would call the engine method underneath the listener, which passes whether or not the listener is still wired up, so it would green-light exactly the breakage the check exists to catch. Those four stay manual, alongside the claim race and the motion and stealth feel passes.

## Release Checklist

**The handshake compares two version strings, and they live in two files.** `CoopHandshakeManifest`
captures `coopBuildVersion` from `coop.build.CoopBuildInfo.VERSION`, which `generateCoopBuildInfo`
writes from `build.gradle`'s `version` property; separately, the engine's `ModSpecAPI.getVersion()`
supplies the `version` field of the `coop` entry in `enabledMods`, and that one comes from
`mod_info.json`, alongside a SHA-256 of the whole `mod_info.json` text taken through
`SettingsAPI.loadText`. `CoopHandshakeDiff.compare` checks all three, and any single difference
rejects the session. So a release bump is two edits that have to agree: `mod_info.json`'s `version`
and `build.gradle`'s `version`. `coopGitCommit` is compared too and comes from
`git rev-parse --short=12 HEAD`, which has a consequence worth stating plainly: two people who each
build the mod from their own checkout will be refused even at an identical version. A build from a
modified working tree reports `<hash>-dirty`, so it will not match a clean build of the same commit
either; `dev-uncommitted` is now only what a checkout with no git metadata at all reports. A release
is one built artifact that both players install, not a version number both players reproduce.

Steps:

1. Bump `version` in `mod_info.json` and in `build.gradle`. Same string. Bump `modVersion`'s
   `major`/`minor`/`patch` in `coop.version` to match, so Version Checker reports the release
   players actually have.
2. Update `CHANGELOG.md` and anything in `docs/player/` the release changes.
   `LICENSE` (CC BY-NC 4.0 with the Fractal Softworks exception) ships in the release archive; any
   code vendored since the last release needs its own notice next to it and a line in `LICENSE`'s
   "does not cover" list.
3. Commit. The commit hash is baked into the jar and compared at connect, so build after committing,
   not before.
4. `scripts\build.ps1` (clean, test, build). Confirm `jars\coop.jar`, `jars\coop-forks.jar` and
   `jars\coop-launcher.jar` all have the new timestamp; `build` depends on `forksJar` and
   `launcherJar`, so a `jar`-only run is not a release build.
5. `scripts\deploy-to-test-clients.ps1`, then a two-client session: handshake accepted, both status
   lines read `session active`, and the host log's connection-doctor block names a tier.
6. `scripts\package-release.ps1` writes `dist\coop-<version>.zip`. It runs step 4's build itself
   unless you pass `-SkipBuild`, and it refuses to package a dirty tree, a `mod_info.json`,
   `build.gradle` or `coop.version` that disagree on the version, jars whose baked-in commit is not
   `HEAD`, or a broken classloader split. What it ships:
   `mod_info.json`, `jars\`, `data\`, `Coop Launcher.cmd`, `coop.version`, `LICENSE`,
   `CHANGELOG.md`, `docs\player\`. Not `build\`, not `src\`, not `forks\`, not `tools\`, not
   `tmp_ff_analysis`. The archive unpacks to a folder named `coop`, because the handshake compares
   the mod path as `mods/<folder name>`.
7. Check `jar -tf jars\coop.jar` has no `coop/rng/` or `coop/presence/` entries and
   `jar -tf jars\coop-forks.jar` does. Those two packages belong to the system classloader only; a
   duplicate in `coop.jar` breaks the forks silently.

**Phase 30 dormancy holds in the release build; confirm it, do not strip it.** With
`-Dcoop.debug.bridge` absent the agent bridge opens no socket and writes no log line.
`CoopAgentBridge.configuredPort()` returns 0 and `createIfEnabled()` returns null, so no instance is
built at all, and `install(null)` returns before it touches the sector. The same is true for `0`, a
negative number, blank, garbage and out-of-range values. Both facts are pinned by
`src/test/java/coop/debug/CoopAgentBridgeTest.java`
(`withoutThePropertyNothingIsBuiltAndNothingBinds`, which also binds the port itself afterwards to
prove nothing took it, and `zeroAndGarbageAndOutOfRangeValuesAreAllDormant`). Both run in
`scripts\build.ps1`, so a green build is the confirmation.
