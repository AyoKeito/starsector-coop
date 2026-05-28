# Coop Mod Development

Run commands from PowerShell. Repository-level instructions require the `rtk` prefix for shell commands.

## Runtime Notes

Read `docs/starsector-runtime-limitations.md` before changing campaign scripts, networking, dependencies, or save-visible state. It records the Starsector sandbox and save-serialization limits found during Phase 3 TCP testing.

## Build, Test, And Package

Use the repeatable build script:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\build.ps1'
```

Equivalent direct Gradle command:

```powershell
rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"
```

Run unit tests only:

```powershell
rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat test"
```

Build only the mod jar:

```powershell
rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat jar"
```

The packaged mod jar is written to:

```text
K:\Starsector\mods\coop\jars\coop.jar
```

Confirm the jar exists:

```powershell
rtk powershell -NoProfile -Command "Test-Path 'K:\Starsector\mods\coop\jars\coop.jar'"
```

## Clean

Use the clean script:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\clean.ps1'
```

This removes only:

```text
K:\Starsector\mods\coop\build
K:\Starsector\mods\coop\jars\coop.jar
```

## Launch Starsector

After a successful build, launch the game from the install root:

```powershell
rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector'; .\starsector.exe"
```

## Inspect Logs

Tail the active Starsector log:

```powershell
rtk powershell -NoProfile -Command "Get-Content -Tail 200 -Path 'K:\Starsector\starsector-core\starsector.log'"
```

Show recent coop log lines:

```powershell
rtk powershell -NoProfile -Command "Select-String -Path 'K:\Starsector\starsector-core\starsector.log' -Pattern 'coop|CoopModPlugin' | Select-Object -Last 50"
```

## Two-Client Local Coop Test

Create isolated host and guest Starsector copies under `K:\Starsector-coop-test`:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\setup-two-client-test.ps1'
```

Build and deploy the current coop mod into both test clients:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\deploy-to-test-clients.ps1'
```

Launch host and guest:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-host.ps1' -Port 7777
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\launch-guest.ps1' -HostAddress '127.0.0.1' -Port 7777
```

After both clients load a campaign, inspect coop log lines from both profiles:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\tail-two-client-logs.ps1'
```

Expected Phase 3 evidence:

```text
Host log: inbound PING and outbound PONG
Guest log: outbound PING and inbound PONG
```
