[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
$modRoot = Split-Path -Parent $scriptDir

$buildDir = Join-Path $modRoot 'build'
$coopJar = Join-Path $modRoot 'jars\coop.jar'
$coopForksJar = Join-Path $modRoot 'jars\coop-forks.jar'

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}

if (Test-Path -LiteralPath $coopJar) {
    Remove-Item -LiteralPath $coopJar -Force
}

if (Test-Path -LiteralPath $coopForksJar) {
    Remove-Item -LiteralPath $coopForksJar -Force
}

Write-Host "Cleaned coop build outputs:"
Write-Host "  $buildDir"
Write-Host "  $coopJar"
Write-Host "  $coopForksJar"
