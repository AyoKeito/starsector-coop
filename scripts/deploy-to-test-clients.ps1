[CmdletBinding()]
param(
    # Where setup-two-client-test.ps1 put the profiles. Defaults to a sibling of the Starsector
    # install this mod folder sits in; see scripts\coop-paths.ps1.
    [string] $TestRoot,
    [switch] $SkipBuild,
    [switch] $WhatIfOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

$scriptDir = $PSScriptRoot
$modRoot = Get-CoopModRoot
$testRootFull = Resolve-CoopTestRoot $TestRoot

function Assert-ProfileRoot {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'starsector.exe'))) {
        throw ("Missing test Starsector profile at $Path. Run setup-two-client-test.ps1 first," +
            " or point this script at an existing profile pair with -TestRoot '<path>'.")
    }
}

$archiveRetentionCount = 15

function Backup-ProfileLogs {
    param(
        [Parameter(Mandatory = $true)][string] $ProfileRoot,
        [Parameter(Mandatory = $true)][string] $ProfileName,
        [switch] $WhatIfOnly
    )

    $logDir = Join-Path $ProfileRoot 'starsector-core'
    $logPath = Join-Path $logDir 'starsector.log'
    if (-not (Test-Path -LiteralPath $logPath)) {
        Write-Host "[$ProfileName] skipped log archive: no starsector.log found"
        return
    }

    $logFiles = @(Get-ChildItem -LiteralPath $logDir -Filter 'starsector.log*' -File)
    $stamp = (Get-Item -LiteralPath $logPath).LastWriteTime.ToString('yyyyMMdd-HHmmss')
    $archiveRoot = Join-Path $ProfileRoot 'log-archive'
    $archiveDest = Join-Path $archiveRoot $stamp

    if (Test-Path -LiteralPath $archiveDest) {
        Write-Host "[$ProfileName] skipped log archive: $stamp already archived (no new session since last archive)"
    } else {
        $totalMb = [Math]::Round((($logFiles | Measure-Object -Property Length -Sum).Sum) / 1MB, 1)
        if ($WhatIfOnly) {
            Write-Host "[$ProfileName] would archive $($logFiles.Count) log file(s) ($totalMb MB) to $archiveDest"
        } else {
            New-Item -ItemType Directory -Force -Path $archiveDest | Out-Null
            foreach ($logFile in $logFiles) {
                Copy-Item -LiteralPath $logFile.FullName -Destination $archiveDest -Force
            }
            Write-Host "[$ProfileName] archived $($logFiles.Count) log file(s) ($totalMb MB) to $archiveDest"
        }
    }

    if (-not (Test-Path -LiteralPath $archiveRoot)) {
        return
    }
    $archiveFolders = @(Get-ChildItem -LiteralPath $archiveRoot -Directory | Sort-Object -Property Name -Descending)
    if ($archiveFolders.Count -le $archiveRetentionCount) {
        return
    }
    $toPrune = @($archiveFolders | Select-Object -Skip $archiveRetentionCount)
    if ($WhatIfOnly) {
        Write-Host "[$ProfileName] would prune $($toPrune.Count) old archive folder(s), keeping newest $archiveRetentionCount"
    } else {
        foreach ($folder in $toPrune) {
            Remove-Item -LiteralPath $folder.FullName -Recurse -Force
        }
        Write-Host "[$ProfileName] pruned $($toPrune.Count) old archive folder(s), keeping newest $archiveRetentionCount"
    }
}

if (-not $SkipBuild) {
    if ($WhatIfOnly) {
        Write-Host "Would build coop mod via scripts\build.ps1"
    } else {
        & (Join-Path $scriptDir 'build.ps1')
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}

foreach ($profile in @('host', 'guest')) {
    $profileRoot = Join-Path $testRootFull $profile
    Assert-ProfileRoot $profileRoot
    Backup-ProfileLogs -ProfileRoot $profileRoot -ProfileName $profile -WhatIfOnly:$WhatIfOnly
    $modsRoot = Join-Path $profileRoot 'mods'
    $destMod = Join-Path $modsRoot 'coop'
    $destModFull = [System.IO.Path]::GetFullPath($destMod)
    if (-not $destModFull.StartsWith($testRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to deploy outside test root: $destModFull"
    }

    if ($WhatIfOnly) {
        Write-Host "Would deploy coop mod to $destMod"
    } else {
        if (Test-Path -LiteralPath $destMod) {
            Remove-Item -LiteralPath $destMod -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $destMod | Out-Null
        Copy-Item -LiteralPath (Join-Path $modRoot 'mod_info.json') -Destination $destMod -Force
        Copy-Item -LiteralPath (Join-Path $modRoot 'jars') -Destination $destMod -Recurse -Force
        $dataDir = Join-Path $modRoot 'data'
        if (Test-Path -LiteralPath $dataDir) {
            Copy-Item -LiteralPath $dataDir -Destination $destMod -Recurse -Force
        }
        Set-Content -LiteralPath (Join-Path $modsRoot 'enabled_mods.json') -Value '{"enabledMods":["coop"]}' -Encoding ASCII
    }
}

Write-Host "Coop mod deployed to host and guest test profiles under $testRootFull"
