[CmdletBinding()]
param(
    # The Starsector install whose starsector-core holds the compile-time jars. Defaults to the
    # install this mod folder sits in, which build.gradle reads as ../../starsector-core; a value
    # given here is passed to gradle as -PstarsectorCore, so the compile really uses it.
    [string] $BaseInstallRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

$modRoot = Get-CoopModRoot

$gradlew = Join-Path $modRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "Missing $gradlew - this script must live in the coop mod's scripts folder."
}

# build.gradle compiles against ../../starsector-core unless it is told otherwise, so by default the
# mod has to be inside the install it is built for. Checking here turns a wall of Gradle "cannot find
# symbol" errors into one sentence.
$coreOverride = $null
if ([string]::IsNullOrWhiteSpace($BaseInstallRoot)) {
    $BaseInstallRoot = Resolve-CoopFullPath (Join-Path $modRoot '..\..')
}
else {
    # Checking one folder and compiling against another is how the check came to pass on a build that
    # then failed to find a single engine class. The same path goes to both.
    $BaseInstallRoot = Resolve-CoopFullPath $BaseInstallRoot
    $coreOverride = Join-Path $BaseInstallRoot 'starsector-core'
}
$core = Join-Path $BaseInstallRoot 'starsector-core'
if (-not (Test-Path -LiteralPath (Join-Path $core 'starfarer.api.jar'))) {
    throw ("Missing $core\starfarer.api.jar. build.gradle compiles against ../../starsector-core," +
        " so the coop folder has to sit in <Starsector>\mods\coop. Move the checkout there," +
        " or symlink it.")
}

Set-Location -LiteralPath $modRoot
if ($null -eq $coreOverride) {
    & $gradlew clean test build
}
else {
    & $gradlew "-PstarsectorCore=$coreOverride" clean test build
}

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
