[CmdletBinding()]
param(
    [string] $TestRoot = 'K:\Starsector-coop-test',
    [int] $Tail = 120,
    [string] $Pattern = 'coop|Coop'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testRootFull = [System.IO.Path]::GetFullPath($TestRoot)

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
