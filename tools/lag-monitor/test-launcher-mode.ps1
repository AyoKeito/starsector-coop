#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$helper = Join-Path $repo 'scripts\set-test-launcher-mode.ps1'
$scratch = Join-Path $repo ('tmp_ff_analysis\lag-monitor\launcher-mode-fixtures-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $scratch
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$script:checks = 0
$global:coopLauncherModeFixtureProcesses = @()
function Get-CimInstance { param($ClassName) return $global:coopLauncherModeFixtureProcesses }
function Assert($Condition, [string] $Message) { $script:checks++; if (-not $Condition) { throw $Message } }
function New-Fixture([string] $Name, [byte[]] $Bytes) {
    $path = Join-Path $scratch $Name
    foreach ($role in @('host', 'guest')) {
        $config = Join-Path $path ($role + '\starsector-core\data\config')
        $null = New-Item -ItemType Directory -Path $config
        [IO.File]::WriteAllBytes((Join-Path $config 'settings.json'), $Bytes)
    }
    return $path
}
function Read-Fixture([string] $Root, [string] $Role) { return ,([IO.File]::ReadAllBytes((Join-Path $Root ($Role + '\starsector-core\data\config\settings.json')))) }
function Same-Bytes([byte[]] $First, [byte[]] $Second) { return [Convert]::ToBase64String($First) -ceq [Convert]::ToBase64String($Second) }
function Expect-Failure([scriptblock] $Action, [string] $Pattern) {
    $message = $null
    try { $null = & $Action } catch { $message = $_.Exception.Message }
    Assert ($null -ne $message -and $message -match $Pattern) "Expected failure matching $Pattern, got $message"
}

$body = "# comment λ`r`n{`r`n`t`"legacyLauncher`" : false, # keep`r`n`"other`":true`r`n}`r`n"
$original = [byte[]](@(239,187,191) + $utf8.GetBytes($body))
$expected = [byte[]](@(239,187,191) + $utf8.GetBytes($body.Replace(' : false,', ' : true,')))
$root = New-Fixture 'bom-crlf' $original
$receipt = & $helper -TestRoot $root -BackupDirectory (Join-Path $scratch 'backup-legacy')
foreach ($role in @('host', 'guest')) {
    Assert (Same-Bytes (Read-Fixture $root $role) $expected) "$role bytes were not preserved"
    Assert (Same-Bytes ([IO.File]::ReadAllBytes((Join-Path $scratch ('backup-legacy\' + $role + '-settings.json')))) $original) "$role backup differs"
}
Assert ($receipt.changed.Count -eq 2) 'Expected both profiles to change'
$null = & $helper -Mode Modern -TestRoot $root -BackupDirectory (Join-Path $scratch 'backup-modern')
Assert (Same-Bytes (Read-Fixture $root 'host') $original) 'Modern did not restore exact original bytes'
$noop = & $helper -Mode Modern -TestRoot $root -BackupDirectory (Join-Path $scratch 'backup-noop')
Assert ($noop.changed.Count -eq 0) 'No-op reported writes'
Expect-Failure { & $helper -TestRoot $root -BackupDirectory (Join-Path $scratch 'backup-noop') } 'already exists'

$lf = $utf8.GetBytes("{`n  `"legacyLauncher`":false`n}`n")
$rootLf = New-Fixture 'lf' $lf
$null = & $helper -TestRoot $rootLf -BackupDirectory (Join-Path $scratch 'backup-lf')
Assert (Same-Bytes (Read-Fixture $rootLf 'guest') ($utf8.GetBytes("{`n  `"legacyLauncher`":true`n}`n"))) 'LF/no-BOM bytes changed unexpectedly'
foreach ($case in @(@('missing', "{`n# `"legacyLauncher`":false`n}`n"), @('duplicate', "{`n`"legacyLauncher`":false,`n`"legacyLauncher`":true`n}`n"))) {
    $bad = $utf8.GetBytes($case[1])
    $badRoot = New-Fixture $case[0] $bad
    Expect-Failure { & $helper -TestRoot $badRoot -BackupDirectory (Join-Path $scratch ('backup-' + $case[0])) } 'exactly one anchored'
    Assert (Same-Bytes (Read-Fixture $badRoot 'host') $bad) 'Malformed fixture mutated'
}
foreach ($role in @('host', 'guest')) {
    $global:coopLauncherModeFixtureProcesses = @([pscustomobject]@{ Name = 'java.exe'; ExecutablePath = Join-Path $rootLf ($role + '\jre\bin\java.exe') })
    Expect-Failure { & $helper -TestRoot $rootLf -BackupDirectory (Join-Path $scratch ('backup-running-' + $role)) } 'Close both test games'
}
$global:coopLauncherModeFixtureProcesses = @()
$rollbackRoot = New-Fixture 'rollback' $lf
$guestPath = Join-Path $rollbackRoot 'guest\starsector-core\data\config\settings.json'
[IO.File]::SetAttributes($guestPath, [IO.FileAttributes]::ReadOnly)
try { Expect-Failure { & $helper -TestRoot $rollbackRoot -BackupDirectory (Join-Path $scratch 'backup-rollback') } 'Backups:' }
finally { [IO.File]::SetAttributes($guestPath, [IO.FileAttributes]::Normal) }
Assert (Same-Bytes (Read-Fixture $rollbackRoot 'host') $lf) 'Host was not rolled back after Guest failure'
Assert (Same-Bytes (Read-Fixture $rollbackRoot 'guest') $lf) 'Guest changed during failed transaction'
Assert (Test-Path -LiteralPath (Join-Path $scratch 'backup-rollback\failure.json')) 'Missing failure receipt'
[pscustomobject]@{ passed = $script:checks; scratch = $scratch }
