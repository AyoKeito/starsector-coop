[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
$modRoot = Split-Path -Parent $scriptDir

$buildDir = Join-Path $modRoot 'build'
$gradleDir = Join-Path $modRoot '.gradle'
$distDir = Join-Path $modRoot 'dist'
$coopJar = Join-Path $modRoot 'jars\coop.jar'
$coopForksJar = Join-Path $modRoot 'jars\coop-forks.jar'
$launcherJar = Join-Path $modRoot 'jars\coop-launcher.jar'
$flatlafJar = Join-Path $modRoot 'jars\flatlaf.jar'

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}

if (Test-Path -LiteralPath $gradleDir) {
    Remove-Item -LiteralPath $gradleDir -Recurse -Force
}

if (Test-Path -LiteralPath $distDir) {
    Remove-Item -LiteralPath $distDir -Recurse -Force
}

# Only the built jars go; jars\FLATLAF-LICENSE.txt is committed and stays.
if (Test-Path -LiteralPath $coopJar) {
    Remove-Item -LiteralPath $coopJar -Force
}

if (Test-Path -LiteralPath $coopForksJar) {
    Remove-Item -LiteralPath $coopForksJar -Force
}

if (Test-Path -LiteralPath $launcherJar) {
    Remove-Item -LiteralPath $launcherJar -Force
}

if (Test-Path -LiteralPath $flatlafJar) {
    Remove-Item -LiteralPath $flatlafJar -Force
}

Write-Host "Cleaned coop build outputs:"
Write-Host "  $buildDir"
Write-Host "  $gradleDir"
Write-Host "  $distDir"
Write-Host "  $coopJar"
Write-Host "  $coopForksJar"
Write-Host "  $launcherJar"
Write-Host "  $flatlafJar"
