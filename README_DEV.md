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
