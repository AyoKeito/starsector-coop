[CmdletBinding()]
param(
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [string] $HostAddress = '127.0.0.1',
    [int] $Port = 7777,
    [string] $SeedString = 'MN-1234567890123456789',
    [switch] $Diagnostics,
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
    $content = $content -replace '\s-Dcoop\.hostPort=\S+', ''
    $content = $content -replace '\s-Dcoop\.connectHost=\S+', ''
    $content = $content -replace '\s-Dcoop\.connectPort=\S+', ''
    $content = $content -replace '\s-Dcoop\.newGameSeed=\S+', ''
    $content = $content -replace '\s-Dcoop\.debug\.diagnostics=\S+', ''
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

if ([string]::IsNullOrWhiteSpace($HostAddress)) {
    throw "HostAddress must not be blank"
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port must be in range 1..65535"
}

$profileRoot = Join-Path ([System.IO.Path]::GetFullPath($TestRoot)) 'guest'
$exe = Join-Path $profileRoot 'starsector.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw "Missing guest test client at $exe. Run setup-two-client-test.ps1 first."
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

Set-CoopVmParams -ProfileRoot $profileRoot -JvmProperties $jvmProperties

$diagNote = if ($Diagnostics) { ' diagnostics=ON' } else { '' }

if ($PatchOnly) {
    Write-Host "Patched guest vmparams for coop.connectHost=$HostAddress coop.connectPort=$Port coop.newGameSeed=$SeedString$diagNote"
    return
}

Start-Process -FilePath $exe -WorkingDirectory $profileRoot
Write-Host "Launched coop guest test client connecting to $HostAddress`:$Port with seed $SeedString$diagNote"
