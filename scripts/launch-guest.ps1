[CmdletBinding()]
param(
    # Where setup-two-client-test.ps1 put the profiles. Defaults to a sibling of the Starsector
    # install this mod folder sits in; see scripts\coop-paths.ps1.
    [string] $TestRoot,
    [string] $HostAddress = '127.0.0.1',
    [int] $Port = 7777,
    [string] $SeedString = 'MN-1234567890123456789',
    [switch] $Diagnostics,
    # Opens the dormant agent bridge on 127.0.0.1:7802 for tools/starsector-mcp
    [switch] $Bridge,
    # Extra -D JVM properties appended verbatim, e.g. -ExtraJvmProps '-Dcoop.debug.interactionDelayMs=1500'
    [string[]] $ExtraJvmProps = @(),
    # Phase 20 WAN smoke: skip starsector.exe and run the JVM directly as jre\bin\coopguest-java.exe
    # (a copy of java.exe) so a per-app proxy that matches by executable name can route only this client.
    [switch] $ProxiedJvm,
    # Explicit consent to join an in-flight campaign with a save that does not belong to it
    # (fresh re-roll or wrong save). This is the supported save-less-guest rejoin path.
    [switch] $AdoptCampaign,
    [switch] $PatchOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

if ([string]::IsNullOrWhiteSpace($HostAddress)) {
    throw "HostAddress must not be blank"
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port must be in range 1..65535"
}
Assert-CoopSeedString $SeedString

# Agent bridge port for the guest instance; tools/starsector-mcp maps 'guest' to this.
$BridgePort = 7802

$testRootFull = Resolve-CoopTestRoot $TestRoot
$profileRoot = Join-Path $testRootFull 'guest'
$exe = Join-Path $profileRoot 'starsector.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw ("Missing guest test client at $exe. Run setup-two-client-test.ps1 first," +
        " or point this script at an existing profile pair with -TestRoot '<path>'.")
}

$jvmProperties = @(
    "-Dcoop.connectHost=$HostAddress",
    "-Dcoop.connectPort=$Port"
)
if (-not [string]::IsNullOrWhiteSpace($SeedString)) {
    $jvmProperties += "-Dcoop.newGameSeed=$SeedString"
}
if ($Diagnostics) {
    $jvmProperties += "-Dcoop.debug.diagnostics=true"
}
if ($AdoptCampaign) {
    $jvmProperties += "-Dcoop.adoptCampaignId=true"
}
if ($Bridge) {
    # Routed through -ExtraJvmProps on purpose: the catch-all -Dcoop.* strip above already
    # clears a stale bridge port from a previous run, so the switch stays launch-scoped.
    $ExtraJvmProps += "-Dcoop.debug.bridge=$BridgePort"
}
if ($ExtraJvmProps.Count -gt 0) {
    $jvmProperties += $ExtraJvmProps
}

Set-CoopVmParams -ProfileRoot $profileRoot -JvmProperties $jvmProperties

$diagNote = if ($Diagnostics) { ' diagnostics=ON' } else { '' }
if ($AdoptCampaign) { $diagNote += ' adoptCampaign=ON' }
if ($Bridge) { $diagNote += " bridge=$BridgePort" }

if ($PatchOnly) {
    Write-Host "Patched guest vmparams for coop.connectHost=$HostAddress coop.connectPort=$Port coop.newGameSeed=$SeedString$diagNote"
    return
}

if ($ProxiedJvm) {
    $jvm = Join-Path $profileRoot 'jre\bin\coopguest-java.exe'
    if (-not (Test-Path -LiteralPath $jvm)) {
        Copy-Item -LiteralPath (Join-Path $profileRoot 'jre\bin\java.exe') -Destination $jvm
    }
    # vmparams is the exact java.exe command line the launcher runs from starsector-core.
    $vmArgs = (Get-Content -LiteralPath (Join-Path $profileRoot 'vmparams') -Raw).Trim()
    $vmArgs = $vmArgs -replace '^java\.exe\s+', ''
    Start-Process -FilePath $jvm -ArgumentList $vmArgs -WorkingDirectory (Join-Path $profileRoot 'starsector-core')
    $diagNote += ' proxiedJvm=ON'
} else {
    Start-Process -FilePath $exe -WorkingDirectory $profileRoot
}
Write-Host "Launched coop guest test client connecting to $HostAddress`:$Port with seed $SeedString$diagNote"
