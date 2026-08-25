[CmdletBinding()]
param(
    [string] $BaseInstallRoot,
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [switch] $WhatIfOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
if ([string]::IsNullOrWhiteSpace($BaseInstallRoot)) {
    $BaseInstallRoot = Join-Path $scriptDir '..\..\..'
}

function Resolve-FullPath {
    param([Parameter(Mandatory = $true)][string] $Path)
    return [System.IO.Path]::GetFullPath($Path)
}

function Assert-StarsectorRoot {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'starsector.exe'))) {
        throw "Missing starsector.exe under $Path"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'vmparams'))) {
        throw "Missing vmparams under $Path"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'starsector-core'))) {
        throw "Missing starsector-core under $Path"
    }
}

function Invoke-RobocopyChecked {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Destination
    )

    $args = @(
        $Source,
        $Destination,
        '/E',
        '/XD', 'mods', 'saves', 'screenshots',
        '/XF', 'starsector.log',
        '/R:2',
        '/W:1',
        '/NP'
    )

    if ($WhatIfOnly) {
        Write-Host "Would run: robocopy $($args -join ' ')"
        return
    }

    & robocopy @args
    $code = $LASTEXITCODE
    if ($code -gt 7) {
        throw "robocopy failed with exit code $code while copying $Source to $Destination"
    }
}

$baseRoot = Resolve-FullPath $BaseInstallRoot
$testRootFull = Resolve-FullPath $TestRoot
$baseComparable = $baseRoot.TrimEnd('\')
$testComparable = $testRootFull.TrimEnd('\')

Assert-StarsectorRoot $baseRoot

if ($testComparable.Equals($baseComparable, [System.StringComparison]::OrdinalIgnoreCase) -or
    $testComparable.StartsWith($baseComparable + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Test root must not be the base install or inside it: $testRootFull"
}

foreach ($profile in @('host', 'guest')) {
    $profileRoot = Join-Path $testRootFull $profile
    Write-Host "Preparing $profile profile at $profileRoot"
    Invoke-RobocopyChecked -Source $baseRoot -Destination $profileRoot

    foreach ($dir in @('mods', 'saves', 'screenshots')) {
        $path = Join-Path $profileRoot $dir
        if ($WhatIfOnly) {
            Write-Host "Would ensure directory: $path"
        } else {
            New-Item -ItemType Directory -Force -Path $path | Out-Null
        }
    }

    # Campaign help popups block the shared pause during automated bridge runs (Phase 30),
    # so test profiles start with them off. Seed only when the game hasn't written its own
    # settings yet - a later in-game change wins over this default.
    $commonDir = Join-Path $profileRoot 'saves\common'
    $sharedSettings = Join-Path $commonDir 'core_shared_settings.json.data'
    if ($WhatIfOnly) {
        Write-Host "Would seed help-popups-off: $sharedSettings"
    } elseif (-not (Test-Path -LiteralPath $sharedSettings)) {
        New-Item -ItemType Directory -Force -Path $commonDir | Out-Null
        Set-Content -LiteralPath $sharedSettings -Value '{"campaignHelpPopupsOptionChecked": false}' -Encoding UTF8
    }
}

Write-Host "Two-client test profiles are ready under $testRootFull"
