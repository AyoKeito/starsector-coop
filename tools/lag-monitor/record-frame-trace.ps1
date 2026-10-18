#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [Parameter(Mandatory)] [ValidatePattern('\ACoopLag-[0-9a-fA-F]{32}\z')] [string] $InstanceName,
    [ValidateRange(1, 120)] [int] $DurationSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\tmp_ff_analysis\lag-monitor')).TrimEnd('\')
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
if (-not $output.StartsWith($allowed + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Trace output must be an existing run folder beneath repo/tmp_ff_analysis/lag-monitor'
}
function Assert-SafeDirectory {
    $item = Get-Item -LiteralPath $output -Force
    if (-not $item.PSIsContainer) { throw 'Trace output is not a directory' }
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Reparse ancestor rejected: $($item.FullName)" }
        $item = $item.Parent
    }
}
function Write-NewJson([string] $Name, [hashtable] $Value) {
    Assert-SafeDirectory
    $Value.instance = $InstanceName
    $Value.pid = $PID
    $Value.utc = [DateTime]::UtcNow.ToString('o')
    $file = [IO.File]::Open((Join-Path $output $Name), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 8 -Compress))
        $file.Write($bytes, 0, $bytes.Length)
    } finally { $file.Dispose() }
}
function Invoke-OwnWpr([string[]] $Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path ([Environment]::SystemDirectory) 'wpr.exe'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $info.ArgumentList.Add('-instancename')
    $info.ArgumentList.Add($InstanceName)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        return @{ exitCode = $process.ExitCode; stdout = $stdout.GetAwaiter().GetResult(); stderr = $stderr.GetAwaiter().GetResult(); durationMs = $clock.Elapsed.TotalMilliseconds }
    } finally { $process.Dispose() }
}

Assert-SafeDirectory
foreach ($name in @('trace-ready.json', 'trace-failed.json', 'trace-complete.json', 'stop-request.json', 'launcher.etl', 'trace-temp')) {
    if (Test-Path -LiteralPath (Join-Path $output $name)) { throw "Trace artifact already exists: $name" }
}
$attempted = $false
$started = $false
$failed = $false
$status = $null
$start = $null
$stopReason = 'deadline'
$requestError = $null
try {
    $admin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) { throw 'Only this trace recorder broker requires administrator privileges' }
    $status = Invoke-OwnWpr @('-status')
    if ($status.exitCode -ne 0 -or ($status.stdout + $status.stderr) -notmatch '(?i)\bWPR is not recording\b') {
        throw 'The requested named WPR instance is not confirmed idle; refusing to alter it'
    }
    Assert-SafeDirectory
    $temp = Join-Path $output 'trace-temp'
    $null = New-Item -ItemType Directory -Path $temp
    $attempted = $true
    $start = Invoke-OwnWpr @('-start', 'GeneralProfile.light', '-start', 'GPU.light', '-start', 'DesktopComposition.light', '-filemode', '-recordtempto', $temp)
    if ($start.exitCode -ne 0) { throw 'Named WPR start failed' }
    $started = $true
    Write-NewJson 'trace-ready.json' @{ startedUtc = [DateTime]::UtcNow.ToString('o'); durationSeconds = $DurationSeconds; start = $start }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt $DurationSeconds) {
        Assert-SafeDirectory
        $requestPath = Join-Path $output 'stop-request.json'
        if (Test-Path -LiteralPath $requestPath) {
            try {
                $requestFile = Get-Item -LiteralPath $requestPath -Force
                if (($requestFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $requestFile.Length -gt 4096) { throw 'Invalid stop request file' }
                $request = Get-Content -LiteralPath $requestPath -Raw | ConvertFrom-Json -AsHashtable
                if ($request -is [Collections.IDictionary] -and $request.instance -is [string] -and $request.instance -ceq $InstanceName) {
                    $stopReason = 'request'
                    break
                }
                $requestError = 'Stop request did not match this instance'
            } catch { $requestError = $_.Exception.Message }
        }
        Start-Sleep -Milliseconds 250
    }
} catch {
    $failed = $true
    Write-NewJson 'trace-failed.json' @{ message = $_.Exception.Message; status = $status; start = $start }
} finally {
    if ($attempted) {
        $stop = $null
        $stopError = $null
        $cancel = $null
        $cancelError = $null
        $etl = Join-Path $output 'launcher.etl'
        try {
            Assert-SafeDirectory
            if (Test-Path -LiteralPath $etl) { throw 'ETL already exists; refusing overwrite' }
            $stop = Invoke-OwnWpr @('-stop', $etl, 'Starsector launcher frame trace', '-skipPdbGen')
            if ($stop.exitCode -ne 0) { $failed = $true }
        } catch { $failed = $true; $stopError = $_.Exception.Message }
        if ($null -eq $stop -or $stop.exitCode -ne 0) {
            # A failed save must not leave our recording active; this command also names only our instance.
            try { $cancel = Invoke-OwnWpr @('-cancel') } catch { $cancelError = $_.Exception.Message }
        }
        $saved = $null -ne $stop -and $stop.exitCode -eq 0 -and (Test-Path -LiteralPath $etl)
        if (-not $saved) { $failed = $true }
        Write-NewJson 'trace-complete.json' @{ started = $started; stopReason = $stopReason; stop = $stop; stopError = $stopError; cancel = $cancel; cancelError = $cancelError; requestError = $requestError; saved = $saved }
    }
}
if ($failed) { exit 1 }
