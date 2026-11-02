#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Legacy', 'Modern')] [string] $Mode = 'Legacy',
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [string] $BackupDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baseInstall = [IO.Path]::GetFullPath((Join-Path $repo '..\..')).TrimEnd('\')
$root = [IO.Path]::GetFullPath($TestRoot).TrimEnd('\')
if ($root -eq $baseInstall) { throw 'TestRoot must not be the base game installation' }
if (-not $BackupDirectory) {
    $BackupDirectory = Join-Path $repo ('tmp_ff_analysis\lag-monitor\config-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N'))
}
$backup = [IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$desired = if ($Mode -eq 'Legacy') { 'true' } else { 'false' }

function Assert-NoReparse([string] $Path, [bool] $MustExist = $true) {
    $cursor = $Path
    while (-not (Test-Path -LiteralPath $cursor)) {
        if ($MustExist) { throw "Required path does not exist: $Path" }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
        if (-not $cursor) { throw "No existing ancestor for $Path" }
    }
    $item = Get-Item -LiteralPath $cursor -Force
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Reparse path rejected: $($item.FullName)" }
        $item = if ($item -is [IO.DirectoryInfo]) { $item.Parent } else { $item.Directory }
    }
}
function Get-Sha([byte[]] $Bytes) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
function Write-NewFile([string] $Path, [byte[]] $Bytes) {
    Assert-NoReparse ([IO.Path]::GetDirectoryName($Path))
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($Bytes, 0, $Bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}
function Assert-TestGamesStopped {
    $roleRoots = @(((Join-Path $root 'host') + '\'), ((Join-Path $root 'guest') + '\'))
    $running = @(Get-CimInstance Win32_Process | Where-Object {
        if ($_.Name -notmatch '^(javaw?|coopguest-java|coophost-java|starsector)\.exe$' -or -not $_.ExecutablePath) { return $false }
        $exe = [IO.Path]::GetFullPath($_.ExecutablePath)
        return $exe.StartsWith($roleRoots[0], [StringComparison]::OrdinalIgnoreCase) -or $exe.StartsWith($roleRoots[1], [StringComparison]::OrdinalIgnoreCase)
    })
    if ($running.Count -ne 0) { throw 'Close both test games before changing launcher mode; no processes were stopped' }
}
function Set-AtomicBytes($Row, [switch] $Rollback) {
    Assert-NoReparse $Row.path
    $bytes = if ($Rollback) { $Row.original } else { $Row.updated }
    $expected = if ($Rollback) { $Row.updatedSha256 } else { $Row.originalSha256 }
    $wanted = if ($Rollback) { $Row.originalSha256 } else { $Row.updatedSha256 }
    $temp = Join-Path ([IO.Path]::GetDirectoryName($Row.path)) ('.coop-launcher-mode-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        Write-NewFile $temp $bytes
        if ((Get-Sha ([IO.File]::ReadAllBytes($Row.path))) -ne $expected) { throw "Settings changed concurrently: $($Row.path)" }
        # Replacing the directory entry also leaves any other hard links to the original file untouched.
        [IO.File]::Replace($temp, $Row.path, [NullString]::Value)
        $Row.applied = -not $Rollback
        if ((Get-Sha ([IO.File]::ReadAllBytes($Row.path))) -ne $wanted) { throw "Settings verification failed: $($Row.path)" }
    } finally {
        if ((Test-Path -LiteralPath $temp) -and (Get-Sha ([IO.File]::ReadAllBytes($temp))) -eq (Get-Sha $bytes)) { [IO.File]::Delete($temp) }
    }
}

Assert-NoReparse $root
Assert-TestGamesStopped
if (Test-Path -LiteralPath $backup) { throw "Backup directory already exists; choose a fresh directory: $backup" }
Assert-NoReparse $backup $false
$rows = @()
foreach ($role in @('host', 'guest')) {
    $roleRoot = [IO.Path]::GetFullPath((Join-Path $root $role)).TrimEnd('\')
    if ($roleRoot -eq $baseInstall -or -not $roleRoot.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe $role profile root" }
    $path = Join-Path $roleRoot 'starsector-core\data\config\settings.json'
    Assert-NoReparse $path
    $original = [IO.File]::ReadAllBytes($path)
    $bom = if ($original.Length -ge 3 -and $original[0] -eq 239 -and $original[1] -eq 187 -and $original[2] -eq 191) { 3 } else { 0 }
    $text = $utf8.GetString($original, $bom, $original.Length - $bom)
    $matches = [regex]::Matches($text, '(?m)^[ \t]*"legacyLauncher"[ \t]*:[ \t]*(?<mode>true|false)(?=[ \t]*(?:,|#|//|\r?$))')
    if ($matches.Count -ne 1) { throw "Expected exactly one anchored legacyLauncher boolean in $path; found $($matches.Count)" }
    $token = $matches[0].Groups['mode']
    $offset = $bom + $utf8.GetByteCount($text.Substring(0, $token.Index))
    $replacement = $utf8.GetBytes($desired)
    $updated = [byte[]]::new($original.Length - $token.Length + $replacement.Length)
    [Array]::Copy($original, 0, $updated, 0, $offset)
    [Array]::Copy($replacement, 0, $updated, $offset, $replacement.Length)
    [Array]::Copy($original, $offset + $token.Length, $updated, $offset + $replacement.Length, $original.Length - $offset - $token.Length)
    $rows += [pscustomobject]@{ role = $role; path = $path; original = $original; updated = $updated; originalSha256 = Get-Sha $original; updatedSha256 = Get-Sha $updated; previousMode = if ($token.Value -eq 'true') { 'Legacy' } else { 'Modern' }; applied = $false }
}
$null = New-Item -ItemType Directory -Path $backup
Assert-NoReparse $backup
foreach ($row in $rows) { Write-NewFile (Join-Path $backup ($row.role + '-settings.json')) $row.original }
$receipt = @{ mode = $Mode; testRoot = $root; createdUtc = [DateTime]::UtcNow.ToString('o'); files = @($rows | ForEach-Object { @{ role = $_.role; path = $_.path; backup = Join-Path $backup ($_.role + '-settings.json'); previousMode = $_.previousMode; originalSha256 = $_.originalSha256; updatedSha256 = $_.updatedSha256 } }) }
Write-NewFile (Join-Path $backup 'receipt.json') ($utf8.GetBytes(($receipt | ConvertTo-Json -Depth 6)))
$failure = $null
$rollbackErrors = @()
try {
    Assert-TestGamesStopped
    foreach ($row in $rows) {
        if ((Get-Sha ([IO.File]::ReadAllBytes($row.path))) -ne $row.originalSha256) { throw "Settings changed after backup: $($row.path)" }
    }
    foreach ($row in $rows) {
        if ($row.originalSha256 -ne $row.updatedSha256) { Set-AtomicBytes $row }
    }
    Write-NewFile (Join-Path $backup 'result.json') ($utf8.GetBytes((@{ success = $true; mode = $Mode; applied = @($rows | Where-Object applied | ForEach-Object role) } | ConvertTo-Json)))
} catch {
    $failure = $_.Exception.Message
    for ($index = $rows.Count - 1; $index -ge 0; $index--) {
        $row = $rows[$index]
        if ($row.applied) {
            try { Set-AtomicBytes $row -Rollback }
            catch { $rollbackErrors += "$($row.role): $($_.Exception.Message)" }
        }
    }
    try { Write-NewFile (Join-Path $backup 'failure.json') ($utf8.GetBytes((@{ message = $failure; rollbackErrors = $rollbackErrors } | ConvertTo-Json))) }
    catch { $rollbackErrors += "Failure receipt: $($_.Exception.Message)" }
}
if ($failure) { throw "$failure. Backups: $backup. Rollback errors: $($rollbackErrors -join '; ')" }
[pscustomobject]@{ mode = $Mode; changed = @($rows | Where-Object applied | ForEach-Object role); receipt = Join-Path $backup 'receipt.json'; result = Join-Path $backup 'result.json' }
