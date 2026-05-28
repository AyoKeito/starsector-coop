[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
$modRoot = Split-Path -Parent $scriptDir

Set-Location -LiteralPath $modRoot
& .\gradlew.bat clean test build

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
