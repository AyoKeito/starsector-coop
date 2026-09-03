[CmdletBinding()]
param(
    # The Starsector install to clone. Defaults to the install this mod folder lives in.
    [string] $BaseInstallRoot,
    # Where the host and guest profiles go. Defaults to a sibling of the install named
    # <install leaf>-coop-test; see scripts\coop-paths.ps1.
    [string] $TestRoot,
    [switch] $WhatIfOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

if ([string]::IsNullOrWhiteSpace($BaseInstallRoot)) {
    $BaseInstallRoot = Get-CoopInstallRoot
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

$baseRoot = Resolve-CoopFullPath $BaseInstallRoot
Assert-CoopStarsectorRoot $baseRoot

# Derived from the install being cloned, not from the mod's own install: -BaseInstallRoot may point
# somewhere else entirely, and the profiles belong next to what they are copies of.
$testRootFull = if ([string]::IsNullOrWhiteSpace($TestRoot)) {
    Get-CoopDefaultTestRoot -InstallRoot $baseRoot
} else {
    Resolve-CoopFullPath $TestRoot
}
$baseComparable = $baseRoot.TrimEnd('\')
$testComparable = $testRootFull.TrimEnd('\')

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
