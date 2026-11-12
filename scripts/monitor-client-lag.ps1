#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Passive', 'Launchers')] [string] $Mode = 'Passive',
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [string] $OutputDirectory,
    [ValidateRange(3, 300)] [int] $DurationSeconds = 15,
    [ValidateRange(5, 60)] [int] $BaselineSeconds = 10,
    [ValidateRange(5, 60)] [int] $SingleSeconds = 12,
    [ValidateRange(5, 30)] [int] $BothSeconds = 10,
    [ValidateRange(5, 60)] [int] $RecoverySeconds = 8,
    [switch] $Trace,
    [switch] $ElevatedTrace
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRootFull = [IO.Path]::GetFullPath($TestRoot).TrimEnd('\')
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repo ('tmp_ff_analysis\lag-monitor\' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "Output directory already exists; use a fresh path: $output" }
$null = New-Item -ItemType Directory -Path $output
$samplesPath = Join-Path $output 'samples.ndjson'
$stream = [IO.File]::Open($samplesPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
$writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true
$clock = [Diagnostics.Stopwatch]::StartNew()
$script:phase = 'setup'
$script:nextSampleMs = 0.0
$reader = $null
$heartbeat = $null
$guard = $null
$completed = $false
$failure = $null
$traceAttempted = $false
$traceStarted = $false
$traceBroker = $null
$traceName = 'CoopLag-' + [guid]::NewGuid().ToString('N')
$wprCommand = Get-Command wpr.exe -ErrorAction SilentlyContinue
$wprPath = if ($wprCommand) { $wprCommand.Source } else { $null }
$script:traceState = [ordered]@{ requested = [bool]($Trace -or $ElevatedTrace); instance = $traceName; started = $false; saved = $false; elevatedBroker = [bool]$ElevatedTrace }
$profileInfo = @()
$ownedJvms = @{}

function Write-Record([hashtable] $Record) {
    $Record.schema = 1
    $Record.utc = [DateTime]::UtcNow.ToString('o')
    $Record.elapsedMs = $clock.Elapsed.TotalMilliseconds
    $writer.WriteLine(($Record | ConvertTo-Json -Depth 14 -Compress))
}

function Set-Phase([string] $Name) {
    $script:phase = $Name
    Write-Record @{ kind = 'phase'; phase = $Name }
    Write-Output "Phase: $Name"
}

function Take-Sample {
    if ($guard.timedOut) { throw 'Safety watchdog expired; owned launchers were closed' }
    $begin = $clock.Elapsed.TotalMilliseconds
    $cost = [Diagnostics.Stopwatch]::StartNew()
    $snapshot = $reader.Sample()
    $readCost = $cost.Elapsed.TotalMilliseconds
    $pulse = $heartbeat.Drain()
    Write-Record @{
        kind = 'sample'; phase = $script:phase; sampleCostMs = $readCost
        sampleStartedMs = $begin; scheduledSampleMs = $script:nextSampleMs
        schedulingDelayMs = [Math]::Max(0, $begin - $script:nextSampleMs)
        metrics = $snapshot.metrics; gpu = @($snapshot.gpu.ToArray())
        foregroundPid = $snapshot.foregroundPid; lastInputAgeMs = $snapshot.lastInputAgeMs
        gpuStatus = $snapshot.gpuStatus; gpuTruncated = $snapshot.gpuTruncated
        processes = @($snapshot.processes.ToArray()); heartbeat = $pulse
        errors = @($snapshot.errors.ToArray()); notices = @($snapshot.notices.ToArray())
    }
    $script:nextSampleMs += 1000
    if ($script:nextSampleMs -le $clock.Elapsed.TotalMilliseconds) {
        $script:nextSampleMs = $clock.Elapsed.TotalMilliseconds + 1000
    }
}

function Run-Phase([string] $Name, [int] $Seconds) {
    Set-Phase $Name
    $until = $clock.Elapsed.TotalMilliseconds + 1000 * $Seconds
    while ($clock.Elapsed.TotalMilliseconds -lt $until) {
        if ($guard.timedOut) { throw 'Safety watchdog expired; owned launchers were closed' }
        $remaining = [Math]::Min($until, $script:nextSampleMs) - $clock.Elapsed.TotalMilliseconds
        if ($remaining -le 0) { Take-Sample }
        else { Start-Sleep -Milliseconds ([int][Math]::Max(1, [Math]::Min(100, $remaining))) }
    }
}

function Invoke-TraceCommand([string] $Label, [string[]] $Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $wprPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            throw "Own WPR command timed out: $Label"
        }
        $record = [ordered]@{ label = $Label; exitCode = $process.ExitCode; stdout = $stdout.GetAwaiter().GetResult(); stderr = $stderr.GetAwaiter().GetResult() }
        [IO.File]::WriteAllText((Join-Path $output ('wpr-' + $Label + '.json')), ($record | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
        return $record
    } finally { $process.Dispose() }
}

function Start-TestLauncher([string] $Role) {
    Set-Phase ($Role + '_starting')
    $profile = Join-Path $testRootFull $Role
    $exe = Join-Path $profile 'starsector.exe'
    $jvmExe = Join-Path $profile 'jre\bin\java.exe'
    $operation = [Diagnostics.Stopwatch]::StartNew()
    $before = [DateTime]::UtcNow
    # These are the interactive windows under test, not background helpers.
    $launcher = $guard.StartOwned($exe, $profile, '', $true)
    $matches = @()
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        $matches = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ExecutablePath -eq $jvmExe -and $_.ParentProcessId -eq $launcher.Id -and
            $_.CreationDate.ToUniversalTime() -ge $before
        })
        if ($matches.Count -eq 1) { break }
        if ($matches.Count -gt 1) { throw "Multiple new JVMs for one $Role launcher" }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($matches.Count -ne 1) { throw "Could not bind the new $Role JVM to this run's launcher" }
    $jvm = Get-Process -Id $matches[0].ProcessId
    if ([Math]::Abs($jvm.StartTime.ToUniversalTime().Ticks - $matches[0].CreationDate.ToUniversalTime().Ticks) -gt 10) {
        throw "The observed $Role child identity changed before binding"
    }
    $guard.Track($jvm, $jvmExe)
    $ownedJvms[$Role] = $jvm.Id
    Write-Record @{ kind = 'operation'; action = 'launch'; role = $Role; launcherPid = $launcher.Id; jvmPid = $jvm.Id; durationMs = $operation.Elapsed.TotalMilliseconds; owned = @($guard.owned) }
    Write-Output "Opened $Role native launcher; JVM $($jvm.Id)"
}

function Stop-TestLauncher([string] $Role) {
    if (-not $ownedJvms.ContainsKey($Role)) { return }
    $operation = [Diagnostics.Stopwatch]::StartNew()
    $guard.Stop([int]$ownedJvms[$Role])
    Write-Record @{ kind = 'operation'; action = 'close'; role = $Role; jvmPid = $ownedJvms[$Role]; durationMs = $operation.Elapsed.TotalMilliseconds; guardErrors = @($guard.errors) }
    Write-Output "Closed $Role test launcher"
}

function Record-TargetModules([string] $Role) {
    if (-not $ownedJvms.ContainsKey($Role)) { return }
    try {
        $process = Get-Process -Id $ownedJvms[$Role]
        Write-Record @{ kind = 'modules'; role = $Role; pid = $process.Id; names = @($process.Modules | ForEach-Object ModuleName) }
    } catch {
        Write-Record @{ kind = 'notice'; message = "Could not read $Role modules: $($_.Exception.Message)" }
    }
}

try {
    if ($Trace -and $ElevatedTrace) { throw 'Choose Trace or ElevatedTrace, not both' }
    if ($Mode -eq 'Launchers') {
        $running = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '^(javaw?|coopguest-java|starsector)\.exe$' -and $_.ExecutablePath -and
            $_.ExecutablePath.StartsWith($testRootFull + '\', [StringComparison]::OrdinalIgnoreCase)
        })
        if ($running.Count -ne 0) { throw 'Controlled launch capture requires both test clients closed; no existing process was stopped' }
        $watchers = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -eq 'node.exe' -and $_.CommandLine -match 'm2-hold-new-session\.mjs\s+pre-m3-session'
        })
        if ($watchers.Count -ne 0) { throw 'The old test pause watcher is still active; stop it before this controlled capture' }
        foreach ($role in @('host', 'guest')) {
            $profile = Join-Path $testRootFull $role
            foreach ($relative in @('starsector.exe', 'jre\bin\java.exe', 'vmparams')) {
                if (-not (Test-Path -LiteralPath (Join-Path $profile $relative))) { throw "Missing $role profile file: $relative" }
            }
            $vm = Get-Content -LiteralPath (Join-Path $profile 'vmparams') -Raw
            if ($vm -match '-Dcoop\.debug\.bridge=') { throw "$role bridge is enabled; this comparison requires it disabled" }
            $settingsPath = Join-Path $profile 'starsector-core\data\config\settings.json'
            $settings = Get-Content -LiteralPath $settingsPath -Raw
            $launcherMode = [regex]::Matches($settings, '(?m)^\s*"legacyLauncher"\s*:\s*(true|false)(?=\s*[,#\r\n])')
            if ($launcherMode.Count -ne 1) { throw "$role native launcher mode is missing or ambiguous" }
            $profileInfo += [ordered]@{
                role = $role; root = $profile
                vmparamsSha256 = (Get-FileHash -LiteralPath (Join-Path $profile 'vmparams') -Algorithm SHA256).Hash
                bridgeEnabled = $false; legacyLauncher = $launcherMode[0].Groups[1].Value -eq 'true'
                settingsSha256 = (Get-FileHash -LiteralPath $settingsPath -Algorithm SHA256).Hash
            }
        }
    }
    Add-Type -Path (Join-Path $repo 'tools\lag-monitor\NativeCounters.cs'), (Join-Path $repo 'tools\lag-monitor\LauncherGuard.cs')
    $reader = [CoopLagMonitor.CounterReader]::new()
    $heartbeat = [CoopLagMonitor.Heartbeat]::new(20)
    $planned = if ($Mode -eq 'Launchers') { $BaselineSeconds + $SingleSeconds + $BothSeconds + 2 * $RecoverySeconds } else { $DurationSeconds }
    $admin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($Mode -eq 'Launchers' -and $admin) { throw 'Run the launcher monitor without elevation; only the frame recorder should be elevated' }
    if ($ElevatedTrace) {
        if ($planned + 40 -gt 120) { throw 'ElevatedTrace requires a planned capture of at most 80 seconds' }
        $brokerPath = Join-Path $repo 'tools\lag-monitor\record-frame-trace.ps1'
        if (-not (Test-Path -LiteralPath $brokerPath)) { throw 'Elevated frame recorder is missing' }
        $brokerArgs = @('-NoProfile', '-File', ('"' + $brokerPath + '"'), '-OutputDirectory', ('"' + $output + '"'), '-InstanceName', $traceName, '-DurationSeconds', ($planned + 40))
        # Windows displays the UAC consent prompt. Only this recorder process is elevated.
        $traceBroker = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $brokerArgs -Verb RunAs -WindowStyle Hidden -PassThru
        $readyPath = Join-Path $output 'trace-ready.json'
        $failedPath = Join-Path $output 'trace-failed.json'
        $readyDeadline = [DateTime]::UtcNow.AddSeconds(60)
        while (-not (Test-Path -LiteralPath $readyPath)) {
            if ((Test-Path -LiteralPath $failedPath) -or $traceBroker.HasExited) {
                throw 'Elevated recorder could not start; inspect trace-failed.json or the Windows consent result'
            }
            if ([DateTime]::UtcNow -ge $readyDeadline) { throw 'Timed out waiting for the elevated recorder' }
            Start-Sleep -Milliseconds 100
        }
        $ready = $null
        for ($attempt = 0; $attempt -lt 20 -and $null -eq $ready; $attempt++) {
            try { $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json }
            catch { Start-Sleep -Milliseconds 100 }
        }
        if ($null -eq $ready -or $ready.instance -ne $traceName) { throw 'Recorder ready marker does not match this capture' }
        $traceStarted = $true
        $script:traceState.started = $true
    } elseif ($Trace -and $wprPath) {
        $traceAttempted = $true
        $traceTemp = Join-Path $output 'wpr-temp'
        $null = New-Item -ItemType Directory -Path $traceTemp
        $startTrace = Invoke-TraceCommand 'start' @('-start', 'GeneralProfile.light', '-start', 'GPU.light', '-start', 'DesktopComposition.light', '-filemode', '-recordtempto', $traceTemp, '-instancename', $traceName)
        $traceStarted = $startTrace.exitCode -eq 0
        $script:traceState.started = $traceStarted
        $script:traceState.startExitCode = $startTrace.exitCode
        if (-not $traceStarted) { Write-Output 'WPR unavailable; capturing native counters and heartbeat. See wpr-start.json.' }
    } elseif ($Trace) { $script:traceState.unavailable = 'wpr.exe missing' }
    $guard = [CoopLagMonitor.LauncherGuard]::new(1000 * ($planned + 40))
    Write-Record @{
        kind = 'metadata'; mode = $Mode; platform = [Environment]::OSVersion.VersionString
        logicalProcessorCount = [Environment]::ProcessorCount; processCpuPctBasis = 'total-capacity'
        sampleIntervalMs = 1000; phaseSettlingMs = 3000; heartbeatPeriodMs = 20
        processCounterSet = 'Process V2'; controlledFamily = 'Create suspended, assign kill-on-close job, resume'
        monitorPid = $PID; elevated = $admin; profiles = $profileInfo; trace = $script:traceState
        inputObservation = 'Foreground PID and age of last input only; no keys, text, titles or input injection'
        plannedSeconds = $planned; watchdogSeconds = $planned + 40
        gpuMeaning = 'Per-PID engine intervals; sum only shares of the identical physical engine. Never sum independent engines.'
    }
    Start-Sleep -Seconds 1
    $null = $reader.Sample() # Seed process instance-to-PID intervals before the first reported sample.
    $null = $heartbeat.Drain()
    $script:nextSampleMs = $clock.Elapsed.TotalMilliseconds + 1000
    if ($Mode -eq 'Passive') { Run-Phase 'baseline' $DurationSeconds }
    else {
        Run-Phase 'baseline' $BaselineSeconds
        Start-TestLauncher 'host'
        Run-Phase 'host' $SingleSeconds
        Record-TargetModules 'host'
        Start-TestLauncher 'guest'
        Run-Phase 'both' $BothSeconds
        Record-TargetModules 'guest'
        Stop-TestLauncher 'guest'
        Run-Phase 'host_after' $RecoverySeconds
        Stop-TestLauncher 'host'
        Run-Phase 'after' $RecoverySeconds
    }
    $completed = $true
} catch {
    $failure = $_.Exception.ToString()
    Write-Record @{ kind = 'error'; phase = $script:phase; message = $failure }
    Write-Output ('Capture error: ' + $_.Exception.Message)
} finally {
    if ($guard) {
        $guard.StopAll()
        if ($guard.timedOut -or @($guard.errors).Count -gt 0) { $completed = $false }
        Write-Record @{ kind = 'cleanup'; timedOut = $guard.timedOut; errors = @($guard.errors); owned = @($guard.owned) }
        $guard.Dispose()
    }
    if ($heartbeat) { $heartbeat.Dispose() }
    if ($reader) { $reader.Dispose() }
    if ($traceBroker) {
        try {
            $signal = @{ instance = $traceName; requestedUtc = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Compress
            $signalStream = [IO.File]::Open((Join-Path $output 'stop-request.json'), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $bytes = [Text.Encoding]::UTF8.GetBytes($signal); $signalStream.Write($bytes, 0, $bytes.Length) }
            finally { $signalStream.Dispose() }
            if (-not $traceBroker.WaitForExit(45000)) {
                $script:traceState.stopError = 'Recorder is still finalizing; its independent duration limit remains active'
            }
            $completePath = Join-Path $output 'trace-complete.json'
            if (Test-Path -LiteralPath $completePath) {
                $script:traceState.brokerResult = Get-Content -LiteralPath $completePath -Raw | ConvertFrom-Json -AsHashtable
                $result = $script:traceState.brokerResult
                if ($result.instance -cne $traceName) { throw 'Recorder completion does not match this capture' }
                $script:traceState.saved = $result.saved -eq $true -and $null -ne $result.stop -and
                    $result.stop.exitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $output 'launcher.etl'))
            } else {
                $script:traceState.stopError = 'Recorder completion is missing; no complete ETL is confirmed'
            }
        } catch { $script:traceState.stopError = $_.Exception.Message }
        finally { $traceBroker.Dispose() }
    }
    if ($traceAttempted) {
        # Unique instance name is mandatory and last. Never stop/cancel a global or other recording.
        try {
            $stopTrace = Invoke-TraceCommand 'stop' @('-stop', (Join-Path $output 'launcher.etl'), 'Starsector launcher lag', '-skipPdbGen', '-instancename', $traceName)
            $script:traceState.saved = $stopTrace.exitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $output 'launcher.etl'))
            $script:traceState.stopExitCode = $stopTrace.exitCode
        } catch { $script:traceState.stopError = $_.Exception.Message }
    }
    Write-Record @{ kind = 'metadata'; trace = $script:traceState }
    Write-Record @{ kind = 'end'; complete = $completed; failure = $failure }
    $writer.Dispose()
}

$node = Get-Command node.exe -ErrorAction SilentlyContinue
if ($node) {
    & $node.Source (Join-Path $repo 'tools\lag-monitor\analyze.mjs') --input $samplesPath --json (Join-Path $output 'report.json') --markdown (Join-Path $output 'report.md')
    if ($LASTEXITCODE -ne 0) { throw "Analyzer failed; raw capture retained at $samplesPath" }
}
Write-Output "Capture: $output"
if (-not $completed) { exit 1 }
