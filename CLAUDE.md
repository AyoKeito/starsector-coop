# Starsector co-op mod

Java mod for Starsector 0.98.5a-RC5 that runs one shared campaign across two clients. The host owns the simulation and the guest mirrors it. `README_DEV.md` has the full commands.

## Repo and layout

- This directory is the git repo (`origin` = github.com/AyoKeito/starsector-coop, public, `main`). Run git from here; `K:\Starsector` is not a repo.
- Never commit `tmp_ff_analysis` (decompiled game sources, scratch runbooks), `jars/` or `build/`.
- `src/main/java/coop` is the mod, `src/launcher` is the desktop launcher (must stay free of Starsector API types), `forks/` holds classpath-shadow copies of vanilla classes built into `coop-forks.jar`, `tools/starsector-mcp` is the MCP server that drives the in-game agent bridge.
- Build against `-PstarsectorCore=C:\Program Files (x86)\Starsector\starsector-core`. The game at `K:\Starsector` is the old RC8 install.

## Docs: what is canonical where

- `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md`: phase ledger and every agreed decision. Fold decisions and deferred items in here, not into scratch files.
- `docs/starsector-runtime-limitations.md`: engine and sandbox facts plus accepted divergences. Read it before touching campaign scripts, networking, save-visible state or dependencies; add to it when you hit a new engine limit. Keep it current facts only: remove an entry when its fix lands.
- `docs/roadmap.html` is generated; never edit it. Before every push: refresh `hereHead`, `here`, `attention` and the bucket notes in `docs/roadmap.data.json`, then run `node docs/roadmap_gen.js` and commit both. "You are here" states where the project stands today and what comes next, nothing else. Every ATTENTION entry is an open item with a concrete action and a pass criterion; when it lands, delete it. History belongs in the plan's ledger, not here.
- Keep roadmap entries compact whenever editing them: target 80-150 visible words per phase, never exceed 200, with at most six short capability chips and three remaining-work bullets. Replace the current summary instead of appending updates. Keep the overview under 60 words, each attention body under 60 words, and bucket notes to one sentence. Class inventories, protocol details, test counts, commit hashes and test history belong in the implementation plan or acceptance reports, not the roadmap.
- `docs/CODEBASE_MAP.md` is the orientation map: packages, per-frame pump order, wire protocol contract, read-first files by task, in-game traps. Read it before exploring the source; update its "Last checked" line when you add a package, message type or script.
- `docs/COOP_MP_DESIGN.md` is the design rationale; `docs/player/CONNECT.md` is the player-facing networking guide.
- Standing rule for every release: verify each file in `docs/player/` against the code and fix it in place, and update `docs/CODEBASE_MAP.md` at every version bump. These docs and the map are current instructions, not an archive. Edit wrong text, delete stale text, never append a new paragraph where an old one should change, and never let them grow with history.

## Build, test, deploy

- Run `scripts\build.ps1 -BaseInstallRoot 'C:\Program Files (x86)\Starsector'` to compile and test against RC5.
- Deploy with `scripts\deploy-to-test-clients.ps1 -SkipBuild -TestRoot <RC5 test root>`. Relaunch both test games after deploying.
- Pass the same `-TestRoot` to both launch scripts. Add `-Diagnostics -Bridge` for a smoke session and read `<RC5 test root>\<role>\starsector-core\starsector.log`.
- Commit per phase or per fix pass and push when the user says so. Publish requested previews as GitHub prereleases. The launcher reads `releases/latest` for stable updates, and Version Checker reads `main/coop.version` directly.

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
