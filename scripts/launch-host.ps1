[CmdletBinding()]
param(
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [int] $Port = 7777,
    [string] $SeedString = 'MN-1234567890123456789',
    [switch] $Diagnostics,
    # Opens the dormant agent bridge on 127.0.0.1:7801 for tools/starsector-mcp
    [switch] $Bridge,
    # Extra -D JVM properties appended verbatim, e.g. -ExtraJvmProps '-Dcoop.debug.interactionDelayMs=1500'
    [string[]] $ExtraJvmProps = @(),
    [switch] $PatchOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Set-CoopVmParams {
    param(
        [Parameter(Mandatory = $true)][string] $ProfileRoot,
        [Parameter(Mandatory = $true)][string[]] $JvmProperties
    )

    $vmparams = Join-Path $ProfileRoot 'vmparams'
    $backup = Join-Path $ProfileRoot 'vmparams.coop-test-base'
    if (-not (Test-Path -LiteralPath $vmparams)) {
        throw "Missing vmparams under $ProfileRoot"
    }
    if (-not (Test-Path -LiteralPath $backup)) {
        Copy-Item -LiteralPath $vmparams -Destination $backup -Force
    }

    $content = Get-Content -LiteralPath $vmparams -Raw
    # Strip EVERY coop property, not an enumerated list: script-managed ones are re-added below and
    # -ExtraJvmProps levers are launch-scoped by design. The enumerated list let a previous run's
    # lever (e.g. -Dcoop.debug.interactionDelayMs) silently persist into later sessions — found live
    # 2026-08-24 when a leftover 1500 ms delay queue showed up as pause lag in the Phase 29 smoke test.
    $content = $content -replace '\s-Dcoop\.\S+', ''
    # Remove any prior coop-forks classpath entry so re-patching stays idempotent.
    $content = $content -replace '\.\.\\mods\\coop\\jars\\coop-forks\.jar;', ''

    $classpathMarker = ' -classpath '
    $index = $content.IndexOf($classpathMarker, [System.StringComparison]::Ordinal)
    if ($index -lt 0) {
        throw "Could not find -classpath in $vmparams"
    }

    # Prepend coop-forks.jar to the FRONT of the classpath so the JVM system classloader resolves
    # our forked engine classes (e.g. com.fs.starfarer.api.util.Misc) ahead of starfarer.api.jar.
    # Path is relative to the JVM working directory (starsector-core); '..' -> install root.
    $forksEntry = '..\mods\coop\jars\coop-forks.jar;'
    $cpValueStart = $index + $classpathMarker.Length
    $content = $content.Substring(0, $cpValueStart) + $forksEntry + $content.Substring($cpValueStart)

    # Insert coop JVM properties just before -classpath.
    $index = $content.IndexOf($classpathMarker, [System.StringComparison]::Ordinal)
    $insertion = ' ' + ($JvmProperties -join ' ')
    $content = $content.Substring(0, $index) + $insertion + $content.Substring($index)
    Set-Content -LiteralPath $vmparams -Value $content -NoNewline -Encoding ASCII
}

if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port must be in range 1..65535"
}

# Agent bridge port for the host instance; tools/starsector-mcp maps 'host' to this.
$BridgePort = 7801

$profileRoot = Join-Path ([System.IO.Path]::GetFullPath($TestRoot)) 'host'
$exe = Join-Path $profileRoot 'starsector.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Missing host test client at $exe. Run setup-two-client-test.ps1 first."
}

$jvmProperties = @("-Dcoop.hostPort=$Port")
if (-not [string]::IsNullOrWhiteSpace($SeedString)) {
    $jvmProperties += "-Dcoop.newGameSeed=$SeedString"
}
if ($Diagnostics) {
    $jvmProperties += "-Dcoop.debug.diagnostics=true"
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
if ($Bridge) { $diagNote += " bridge=$BridgePort" }

if ($PatchOnly) {
    Write-Host "Patched host vmparams for coop.hostPort=$Port coop.newGameSeed=$SeedString$diagNote"
    return
}

Start-Process -FilePath $exe -WorkingDirectory $profileRoot
Write-Host "Launched coop host test client on port $Port with seed $SeedString$diagNote"
