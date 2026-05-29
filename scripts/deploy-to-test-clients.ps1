[CmdletBinding()]
param(
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [switch] $SkipBuild,
    [switch] $WhatIfOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
$modRoot = Split-Path -Parent $scriptDir
$testRootFull = [System.IO.Path]::GetFullPath($TestRoot)

function Assert-ProfileRoot {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'starsector.exe'))) {
        throw "Missing test Starsector profile at $Path. Run setup-two-client-test.ps1 first."
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
