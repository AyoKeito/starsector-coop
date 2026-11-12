# Launcher lag monitor

Run from the repository in PowerShell 7 on Windows 11. Node.js generates the report.
The collector uses Windows' Process V2 counters so two `java.exe` processes retain separate identities.

```powershell
pwsh -NoProfile -File scripts/monitor-client-lag.ps1 -Mode Passive -DurationSeconds 15
pwsh -NoProfile -File scripts/monitor-client-lag.ps1 -Mode Launchers
```

`Launchers` requires both test clients closed and their MCP bridges disabled. It leaves `vmparams`
and game settings unchanged, opens the native Starsector launchers, and captures these phases:

| Phase | Default duration |
| --- | ---: |
| Idle baseline | 10 seconds |
| Host | 12 seconds |
| Both launchers | 10 seconds |
| Host after Guest closes | 8 seconds |
| Recovery after Host closes | 8 seconds |

Leave the windows at their launchers; do not press Play. `-BothSeconds 30` extends the interaction
window. `-BaselineSeconds`, `-SingleSeconds` and `-RecoverySeconds` control the other phases.

Each run creates a fresh folder under `tmp_ff_analysis/lag-monitor/`, containing `samples.ndjson`,
`report.json` and `report.md`. `-OutputDirectory` chooses a new folder; existing folders are refused.
Metadata records each profile's native launcher mode, settings SHA-256 and `vmparams` SHA-256.

The native collector samples once per second:

- Total and busiest-core CPU, privileged time, DPC and interrupt time.
- Available memory, paging, commit use and disk queue/latency.
- GPU engine use, preserving physical engine and per-process contributors.
- Selected processes' CPU, memory and I/O, including both Java clients, DWM and the monitor.
- Foreground process ID and age of the last user input. It records no keys, text or window titles.

A separate thread measures 20 ms heartbeat wake delays. It changes no thread priorities or timer
resolution. Normal sleep quantization is compared with the baseline. Counter read cost is reported
separately; a slow query is not automatically treated as a whole-system stall. Reports preserve
startup spikes and also summarize steady windows after the first three seconds of each phase.

The launch guard creates native processes suspended and assigns them to a private kill-on-close
Windows job before resuming them. It closes only that run's processes and descendants. A separate
deadline closes them if the main collector blocks. Individual stops use retained native handles,
verified executable paths and creation times. Pre-existing test clients are never adopted or killed.

`-Trace` also attempts a uniquely named Windows Performance Recorder capture using
`GeneralProfile.light`, `GPU.light` and `DesktopComposition.light`. If Windows denies profiling
privileges, native metrics still run and the error is saved. It never stops another recording,
requests UAC or cancels a global trace. The script does not edit registry or graphics settings.
To record frame timing with a normal Windows account, use `-ElevatedTrace` instead:

```powershell
pwsh -NoProfile -File scripts/monitor-client-lag.ps1 -Mode Launchers -ElevatedTrace -BaselineSeconds 6 -SingleSeconds 6 -BothSeconds 30 -RecoverySeconds 6
```

Windows requests UAC consent for `record-frame-trace.ps1`. Only that recorder is elevated;
the monitor and launchers retain normal permissions. The recorder writes an ETL and handshake
results into the run folder, stops its own uniquely named session on request or after at most
120 seconds of recording, and refuses to overwrite artifacts. WPR startup or finalization can
take additional time. It cancels only its own named session if saving fails. Never run the
launcher monitor itself as administrator.

An ETL supports analysis of DWM composition, software presentation timing, scheduling and driver
work. It contains system profiling data, including process names and paths. Ordinary counters
alone cannot identify the exact driver or overlay responsible for a presentation stall. Neither
capture measures physical display latency.

## Legacy launcher workaround

The 2026-10-18 active-interaction comparison reproduced desktop stalls with the modern launcher
and a responsive desktop with the built-in legacy launcher. The maximum strictly contained 4K DWM
flip gap fell from 6,933.46 ms to 38.02 ms. The legacy run had completed-frame coverage across all
capture phases, with no gaps over 100 ms on either tracked render target during the both-launcher
phase. This validates a workaround for the test setup; it does not identify a defective driver.

With both test clients closed:

```powershell
pwsh -NoProfile -File scripts/set-test-launcher-mode.ps1 -Mode Legacy
```

Use `-Mode Modern` to switch back. `-TestRoot` selects the two-profile root; `-BackupDirectory`
selects a fresh backup folder. The default backup folder is under ignored `tmp_ff_analysis/lag-monitor/`.
Both files are backed up before mutation; receipts contain original/updated hashes. The helper
replaces only the anchored `legacyLauncher` boolean, preserves the other bytes, refuses running
test games or reparse paths, and rolls back its own writes on failure.

The legacy launcher retains the main native controls but omits modern memory/AA/scaling controls.
It removes the continuous OpenGL launcher render loop; a brief AA capability query can still use
OpenGL at startup. [Official Starsector support recommendation](https://fractalsoftworks.com/forum/index.php?topic=31967.0).

Tests:

```powershell
node --test tools/lag-monitor/test.mjs tools/lag-monitor/test-focus.mjs
pwsh -NoProfile -File tools/lag-monitor/test-native.ps1
pwsh -NoProfile -File tools/lag-monitor/test-launcher-mode.ps1
```

The native tests use fresh hidden PowerShell sleep processes and clean them up. They do not operate games.

References: [PDH formatted arrays](https://learn.microsoft.com/en-us/windows/win32/api/pdh/nf-pdh-pdhgetformattedcounterarrayw),
[unique Process V2 identities](https://learn.microsoft.com/en-us/windows/win32/perfctrs/about-performance-counters),
[WPR commands](https://learn.microsoft.com/en-us/windows-hardware/test/wpt/wpr-command-line-options).
