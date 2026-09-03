[CmdletBinding()]
param(
    # Where setup-two-client-test.ps1 put the profiles. Defaults to a sibling of the Starsector
    # install this mod folder sits in; see scripts\coop-paths.ps1.
    [string] $TestRoot,
    [int] $Tail = 120,
    [string] $Pattern = 'coop|Coop'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

$testRootFull = Resolve-CoopTestRoot $TestRoot

foreach ($profile in @('host', 'guest')) {
    $logPath = Join-Path $testRootFull "$profile\starsector-core\starsector.log"
    Write-Host ""
    Write-Host "== $profile log: $logPath =="
    if (-not (Test-Path -LiteralPath $logPath)) {
        Write-Host "Log does not exist yet."
        continue
    }

    Get-Content -LiteralPath $logPath -Tail $Tail |
        Select-String -Pattern $Pattern
}
