# Starsector co-op mod

Java mod for Starsector 0.98a-RC8 that runs one shared campaign across two clients: the host owns the simulation, the guest mirrors it. `README_DEV.md` has the full commands; this file is the part a session gets wrong without being told.

## Repo and layout

- This directory is the git repo (`origin` = github.com/AyoKeito/starsector-coop, public, `main`). Run git from here; `K:\Starsector` is not a repo.
- Never commit `tmp_ff_analysis` (decompiled game sources, scratch runbooks), `jars/` or `build/`.
- `src/main/java/coop` is the mod, `src/launcher` is the desktop launcher (must stay free of Starsector API types), `forks/` holds classpath-shadow copies of vanilla classes built into `coop-forks.jar`, `tools/starsector-mcp` is the MCP server that drives the in-game agent bridge.
- Worktree builds need `-PstarsectorCore=K:\Starsector\starsector-core`.

## Docs: what is canonical where

- `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md`: phase ledger and every agreed decision. Fold decisions and deferred items in here, not into scratch files.
- `docs/starsector-runtime-limitations.md`: engine and sandbox facts plus accepted divergences. Read it before touching campaign scripts, networking, save-visible state or dependencies; add to it when you hit a new engine limit. Keep it current facts only: remove an entry when its fix lands.
- `docs/roadmap.html` is generated; never edit it. Before every push: refresh `hereHead`, `here`, `attention` and the bucket notes in `docs/roadmap.data.json`, then run `node docs/roadmap_gen.js` and commit both. "You are here" states where the project stands today and what comes next, nothing else. Every ATTENTION entry is an open item with a concrete action and a pass criterion; when it lands, delete it. History belongs in the plan's ledger, not here.
- `docs/COOP_MP_DESIGN.md` is the design rationale; `docs/player/CONNECT.md` is the player-facing networking guide.

## Build, test, deploy

- `.\gradlew.bat clean test build` from this directory (or `scripts\build.ps1`). Tests must stay green; the suite is the only safety net between a change and a two-instance smoke.
- Deploy with `scripts\deploy-to-test-clients.ps1` (copies the whole mod, `data/` included, into `K:\Starsector-coop-test\host` and `\guest`). Never hand-copy jars. Relaunch both games after deploying.
- Launch: `scripts\launch-host.ps1 -Port 7777` and `scripts\launch-guest.ps1 -HostAddress 127.0.0.1 -Port 7777`; add `-Diagnostics -Bridge` for a smoke session. Logs: `K:\Starsector-coop-test\<role>\starsector-core\starsector.log`.
- Commit per phase or per fix pass, push when the user says so. Releases are never marked prerelease (the launcher reads `releases/latest`).

## Engine rules that compile fine and crash in-game

- The script classloader refuses `java.io.*` (allow-list aside), `java.nio.file.Files`, and `java.lang.reflect.*`. Use `java.lang.invoke.MethodHandles` for field access, catch `Throwable` around engine calls that declare `IOException`, and keep to plain arrays and strings in network code.
- No threads: every socket is non-blocking and pumped from `EveryFrameScript.advance()` on the campaign thread. Mod threads die silently mid-session.
- Never store transport objects in saved state; `CoopNetPump` is a transient script.
- `InputEventAPI` control names are enum constants (`GENERAL_PAUSE`, not `PAUSE`); a wrong name passes the mocked tests and crashes the game.
- Seeds are gen-time only. Runtime randomness is replicated from the host or suppressed on the guest, never re-seeded.
- Forks under `forks/` are re-applied on an engine update by copying the vanilla source over byte-identically and re-applying only the `COOP FORK` hunks; `CoopPresenceRegistry.PINNED_VERSION` is the one constant to bump.

## Smoke sessions

- The user drives both games and reports on-screen mismatches; the agent owns deploy, launch flags, bridge queries and log tailing. Verify in this order: bridge state, then log lines, then eyes.
- Say "let time pass" when asking the user to advance the clock.
- Separate a real defect from vanilla behaviour before calling anything a bug; if it is vanilla, it goes in the limitations doc, not a fix.
- End every handoff with concrete manual test steps: what to launch, what to do, what pass and fail look like.
