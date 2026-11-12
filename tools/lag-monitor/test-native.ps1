$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $IsWindows) { throw 'Native lag-monitor tests require Windows.' }

$clock = [Diagnostics.Stopwatch]::StartNew()
$script:checks = 0
$fixtures = [Collections.Generic.List[Diagnostics.Process]]::new()
$guards = [Collections.Generic.List[object]]::new()
$reader = $null
$heartbeat = $null
$report = $null
$temporaryFiles = [Collections.Generic.List[string]]::new()

function Assert-That([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
    $script:checks++
}

function Start-Fixture {
    $process = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 15') `
        -WindowStyle Hidden -PassThru
    # Retain the original process handle before any test can terminate it.
    $null = $process.Handle
    $null = $process.StartTime
    $fixtures.Add($process)
    return $process
}

function Assert-FiniteNonnegative($Value, [string] $Name) {
    Assert-That ($null -ne $Value) "$Name is present"
    Assert-That (-not [double]::IsNaN([double]$Value) -and
        -not [double]::IsInfinity([double]$Value) -and [double]$Value -ge 0) "$Name is finite and nonnegative"
}

try {
    Add-Type -Path @((Join-Path $PSScriptRoot 'NativeCounters.cs'), (Join-Path $PSScriptRoot 'LauncherGuard.cs'))
    $expectedExe = [IO.Path]::GetFullPath((Join-Path $PSHOME 'pwsh.exe'))
    $untouched = Start-Fixture
    $fixture = Start-Fixture

    $rejectGuard = [CoopLagMonitor.LauncherGuard]::new(30000)
    $guards.Add($rejectGuard)
    $rejected = $false
    try { $rejectGuard.Track($fixture, (Join-Path $PSScriptRoot 'not-the-fixture.exe')) }
    catch {
        if ($_.Exception.ToString() -notmatch 'Launcher executable mismatch') { throw }
        $rejected = $true
    }
    Assert-That $rejected 'wrong executable path is rejected'
    Assert-That ($rejectGuard.owned.Count -eq 0) 'rejected fixture is never registered'
    $rejectGuard.Dispose()
    Assert-That (-not $fixture.HasExited) 'rejection and guard disposal leave the unowned fixture alive'

    $stopGuard = [CoopLagMonitor.LauncherGuard]::new(30000)
    $guards.Add($stopGuard)
    $stopGuard.Track($fixture, $expectedExe)
    $stopGuard.Track($fixture, $expectedExe)
    Assert-That ($stopGuard.owned.Count -eq 1) 'duplicate tracking retains one identity'
    $identity = $stopGuard.owned[0]
    Assert-That ($identity.pid -eq $fixture.Id) 'tracked PID matches the created fixture'
    Assert-That ([string]::Equals($identity.exe, $expectedExe, [StringComparison]::OrdinalIgnoreCase)) 'tracked executable matches'
    Assert-That ([datetime]::Parse($identity.startedUtc).ToUniversalTime() -eq $fixture.StartTime.ToUniversalTime()) 'tracked creation time matches'
    $unownedRejected = $false
    try { $stopGuard.Stop($untouched.Id) }
    catch {
        if ($_.Exception.ToString() -notmatch 'PID is not owned by this capture') { throw }
        $unownedRejected = $true
    }
    Assert-That $unownedRejected 'Stop rejects an unregistered freshly created fixture'
    Assert-That (-not $untouched.HasExited) 'unregistered fixture remains alive'
    $stopGuard.Stop($fixture.Id)
    Assert-That ($fixture.WaitForExit(3000)) 'explicit Stop terminates the exact tracked fixture'
    Assert-That ($stopGuard.owned[0].stopRequested -and $stopGuard.owned[0].exited) 'explicit stop records requested and observed exit'
    Assert-That ($stopGuard.errors.Count -eq 0) "explicit stop has no native errors: $($stopGuard.errors -join '; ')"
    Assert-That (-not $untouched.HasExited) 'explicit Stop does not terminate the untracked fixture'

    $watchdog = [CoopLagMonitor.LauncherGuard]::new(500)
    $guards.Add($watchdog)
    $watchdogFixture = $watchdog.StartOwned($expectedExe, $PSScriptRoot,
        '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 15"', $false)
    $null = $watchdogFixture.Handle
    $fixtures.Add($watchdogFixture)
    Assert-That ($watchdogFixture.WaitForExit(5000)) '500 ms watchdog independently terminates its fixture'
    Assert-That $watchdog.timedOut 'watchdog reports deadline expiry'
    Assert-That ($watchdog.owned.Count -eq 1 -and $watchdog.owned[0].pid -eq $watchdogFixture.Id) 'watchdog owns only its fixture'
    Assert-That ($watchdog.owned[0].stopRequested -and $watchdog.owned[0].exited) 'watchdog records requested and observed exit'
    Assert-That ($watchdog.errors.Count -eq 0) "watchdog has no native errors: $($watchdog.errors -join '; ')"
    Assert-That (-not $untouched.HasExited) 'watchdog leaves the untracked fixture alive'

    $fixtureStem = 'coop-lag-guard-' + [guid]::NewGuid().ToString('N')
    $fixtureScript = Join-Path ([IO.Path]::GetTempPath()) ($fixtureStem + '.ps1')
    $childPidFile = Join-Path ([IO.Path]::GetTempPath()) ($fixtureStem + '.txt')
    $temporaryFiles.Add($fixtureScript)
    $temporaryFiles.Add($childPidFile)
    $escapedExe = $expectedExe.Replace("'", "''")
    $escapedPidFile = $childPidFile.Replace("'", "''")
    $childScript = @"
`$child = Start-Process -FilePath '$escapedExe' -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 15') -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText('$escapedPidFile', `$child.Id.ToString())
Start-Sleep -Seconds 15
"@
    [IO.File]::WriteAllText($fixtureScript, $childScript)
    $jobGuard = [CoopLagMonitor.LauncherGuard]::new(30000)
    $guards.Add($jobGuard)
    $jobParent = $jobGuard.StartOwned($expectedExe, $PSScriptRoot,
        ('-NoProfile -NonInteractive -File "{0}"' -f $fixtureScript), $false)
    $null = $jobParent.Handle
    $fixtures.Add($jobParent)
    $childClock = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path -LiteralPath $childPidFile) -and $childClock.ElapsedMilliseconds -lt 5000) {
        Start-Sleep -Milliseconds 50
    }
    Assert-That (Test-Path -LiteralPath $childPidFile) 'job-owned parent reports its newly created child'
    $descendantId = [int][IO.File]::ReadAllText($childPidFile)
    $descendant = [Diagnostics.Process]::GetProcessById($descendantId)
    $null = $descendant.Handle
    Assert-That ([string]::Equals($descendant.MainModule.FileName, $expectedExe, [StringComparison]::OrdinalIgnoreCase)) 'reported descendant is the harmless pwsh fixture'
    Assert-That ($descendant.StartTime.ToUniversalTime() -ge $jobParent.StartTime.ToUniversalTime()) 'descendant creation follows its owned parent'
    $fixtures.Add($descendant)
    Assert-That ($jobGuard.owned.Count -eq 1 -and $jobGuard.owned[0].pid -eq $jobParent.Id) 'descendant was never individually tracked'
    $jobGuard.StopAll()
    Assert-That ($jobParent.WaitForExit(3000) -and $descendant.WaitForExit(3000)) 'job StopAll terminates parent and untracked descendant'
    Assert-That ($jobGuard.errors.Count -eq 0) "job cleanup has no native errors: $($jobGuard.errors -join '; ')"
    Assert-That (-not $untouched.HasExited) 'job termination leaves the unrelated created fixture alive'

    $reader = [CoopLagMonitor.CounterReader]::new()
    $heartbeat = [CoopLagMonitor.Heartbeat]::new(20)
    Start-Sleep -Milliseconds 1100
    $sampleClock = [Diagnostics.Stopwatch]::StartNew()
    $first = $reader.Sample()
    $firstMs = $sampleClock.Elapsed.TotalMilliseconds
    Assert-That ($first.errors.Count -eq 0) "first PDH sample has no errors: $($first.errors -join '; ')"
    Assert-That ($first.processes.Count -gt 0) 'PDH returns process rows'
    foreach ($row in $first.processes) {
        Assert-That ($null -eq $row.cpuPct -and $null -eq $row.readMBps -and $null -eq $row.writeMBps) 'first-seen process rates are null until identity spans an interval'
    }
    Start-Sleep -Milliseconds 1100
    $sampleClock.Restart()
    $second = $reader.Sample()
    $secondMs = $sampleClock.Elapsed.TotalMilliseconds
    Assert-That ($second.errors.Count -eq 0) "second PDH sample has no errors: $($second.errors -join '; ')"
    foreach ($snapshot in @($first, $second)) {
        Assert-That ($snapshot.foregroundPid -ge 0) 'read-only foreground PID is nonnegative, including unavailable zero'
        if ($null -ne $snapshot.lastInputAgeMs) {
            Assert-FiniteNonnegative $snapshot.lastInputAgeMs 'read-only last-input age'
        }
        else { Assert-That ($null -eq $snapshot.lastInputAgeMs) 'unavailable last-input age remains null' }
    }
    foreach ($key in @('cpuPct', 'availableMB', 'dpcPct', 'interruptPct', 'cpuMaxCorePct', 'dpcMaxCorePct', 'interruptMaxCorePct')) {
        Assert-That ($second.metrics.ContainsKey($key)) "required metric $key exists"
        Assert-FiniteNonnegative $second.metrics[$key] $key
    }
    $ownRows = @($second.processes | Where-Object pid -EQ $PID)
    Assert-That ($ownRows.Count -eq 1) "collector process $PID has exactly one current identity row (matches=$($ownRows.Count), rows=$($second.processes.Count), pwsh=$(@($second.processes | Where-Object name -EQ 'pwsh' | ForEach-Object pid) -join ','))"
    Assert-FiniteNonnegative $ownRows[0].cpuPct 'second-interval process CPU'
    Assert-FiniteNonnegative $ownRows[0].readMBps 'second-interval process reads'
    Assert-FiniteNonnegative $ownRows[0].writeMBps 'second-interval process writes'
    Assert-That ($null -ne $ownRows[0].startedUtc) 'process row includes creation identity'
    $fixtureRows = @($second.processes | Where-Object pid -EQ $untouched.Id)
    Assert-That ($fixtureRows.Count -eq 1 -and -not $untouched.HasExited) 'simultaneous pwsh processes retain separate PID counter rows'
    Assert-FiniteNonnegative $fixtureRows[0].cpuPct 'second pwsh process CPU'
    Assert-That ($second.gpuStatus -in @('ok', 'unavailable')) 'GPU availability is explicit'
    $heartbeat.Dispose()
    $pulse = $heartbeat.Drain()
    Assert-That ($pulse['periodMs'] -eq 20 -and $pulse['count'] -gt 0) 'background heartbeat produced samples'
    Assert-FiniteNonnegative $pulse['maxDelayMs'] 'heartbeat maximum delay'
    Assert-FiniteNonnegative $pulse['p95DelayMs'] 'heartbeat p95 delay'
    Assert-That ($pulse['maxDelayMs'] -ge $pulse['p95DelayMs']) 'heartbeat percentile does not exceed its maximum'
    $empty = $heartbeat.Drain()
    Assert-That ($empty['count'] -eq 0 -and $null -eq $empty['maxDelayMs'] -and $null -eq $empty['p95DelayMs']) 'Drain clears heartbeat samples'
    $report = [ordered]@{ status = 'PASS'; assertions = $script:checks; fixtureCount = $fixtures.Count
        firstSampleMs = [math]::Round($firstMs, 2); secondSampleMs = [math]::Round($secondMs, 2)
        gpuStatus = $second.gpuStatus; heartbeatCount = $pulse['count'] }
}
finally {
    if ($null -ne $heartbeat) { $heartbeat.Dispose() }
    if ($null -ne $reader) { $reader.Dispose() }
    foreach ($guard in $guards) { $guard.Dispose() }
    foreach ($process in $fixtures) {
        try {
            if (-not $process.HasExited) { $process.Kill(); $null = $process.WaitForExit(3000) }
            if (-not $process.HasExited) { throw "Created fixture $($process.Id) is still running" }
        }
        finally { $process.Dispose() }
    }
    foreach ($path in $temporaryFiles) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
}
$report['elapsedMs'] = [math]::Round($clock.Elapsed.TotalMilliseconds, 2)
$report | ConvertTo-Json -Compress
