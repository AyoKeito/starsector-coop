[CmdletBinding()]
param(
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [string] $HostAddress = '127.0.0.1',
    [int] $Port = 7777,
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

    $classpathMarker = ' -classpath '
    $index = $content.IndexOf($classpathMarker, [System.StringComparison]::Ordinal)
    if ($index -lt 0) {
        throw "Could not find -classpath in $vmparams"
    }

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

Set-CoopVmParams -ProfileRoot $profileRoot -JvmProperties @(
    "-Dcoop.connectHost=$HostAddress",
    "-Dcoop.connectPort=$Port"
)

if ($PatchOnly) {
    Write-Host "Patched guest vmparams for coop.connectHost=$HostAddress coop.connectPort=$Port"
    return
}

Start-Process -FilePath $exe -WorkingDirectory $profileRoot
Write-Host "Launched coop guest test client connecting to $HostAddress`:$Port"
