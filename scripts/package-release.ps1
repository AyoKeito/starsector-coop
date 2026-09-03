<#
.SYNOPSIS
Packages a release archive: coop-<version>.zip, unpacking to a folder named exactly "coop".

.DESCRIPTION
Replaces step 6 of README_DEV.md's release checklist. The archive holds exactly what a player
install needs -- mod_info.json, jars\, data\, "Coop Launcher.cmd", coop.version, LICENSE,
CHANGELOG.md, README.md, docs\player\ -- and nothing else: no build\, src\, forks\, tools\, tmp_ff_analysis
or .git.

The refusals are the point. A release is one built artifact that both players install, so the
script will not produce an archive that the handshake would reject at connect time:

  * a dirty working tree, because the commit baked into the jar would read "dev-uncommitted";
  * mod_info.json and build.gradle disagreeing on version, because the handshake compares both
    (mod_info.json via ModSpecAPI.getVersion, build.gradle via coop.build.CoopBuildInfo.VERSION);
  * jar manifests whose Coop-Git-Commit is not HEAD, which is what a build made before the
    release commit looks like;
  * coop.jar carrying coop/rng/ or coop/presence/, or coop-forks.jar missing them -- those two
    packages belong to the system classloader only and a duplicate breaks the forks silently.

.EXAMPLE
scripts\package-release.ps1

.EXAMPLE
scripts\package-release.ps1 -SkipBuild -OutDir C:\tmp\coop-dist
#>
[CmdletBinding()]
param(
    # Where the zip is written. Defaults to <modRoot>\dist (gitignored).
    [string] $OutDir,

    # Skip scripts\build.ps1 and package the jars already on disk. The manifest commit check still
    # runs, so this only saves time when the jars are already a build of HEAD.
    [switch] $SkipBuild,

    # Package despite uncommitted changes. The jars will report "dev-uncommitted" as their commit
    # and no other machine's build can match them. Local experiments only.
    [switch] $AllowDirty,

    # Skip the Coop-Git-Commit == HEAD check. Local dry runs only; an archive built this way is
    # not a release.
    [switch] $SkipCommitCheck,

    # Passed through to scripts\build.ps1.
    [string] $BaseInstallRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'coop-paths.ps1')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$modRoot = Get-CoopModRoot

function Invoke-CoopGit {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    $output = & git -C $modRoot @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("git " + ($Arguments -join ' ') + " failed in $modRoot`n" + ($output -join "`n"))
    }
    return $output
}

<#
.SYNOPSIS
The version string in mod_info.json.
#>
function Get-CoopModInfoVersion {
    param([Parameter(Mandatory = $true)][string] $Path)

    $text = Get-Content -LiteralPath $Path -Raw
    $match = [regex]::Match($text, '"version"\s*:\s*"([^"]+)"')
    if (-not $match.Success) {
        throw ("Could not find a string `"version`" field in $Path. The release checklist needs it" +
            " to agree with build.gradle's version.")
    }
    return $match.Groups[1].Value
}

<#
.SYNOPSIS
The version string in build.gradle's "version = '...'" line.
#>
function Get-CoopGradleVersion {
    param([Parameter(Mandatory = $true)][string] $Path)

    $text = Get-Content -LiteralPath $Path -Raw
    $match = [regex]::Match($text, "(?m)^\s*version\s*=\s*'([^']+)'\s*$")
    if (-not $match.Success) {
        throw ("Could not find a `"version = '...'`" line in $Path.")
    }
    return $match.Groups[1].Value
}

<#
.SYNOPSIS
All entry names inside a jar, with forward slashes.
#>
function Get-CoopJarEntryNames {
    param([Parameter(Mandatory = $true)][string] $JarPath)

    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    }
    finally {
        $archive.Dispose()
    }
}

<#
.SYNOPSIS
The value of a META-INF/MANIFEST.MF attribute inside a jar, or $null when absent.
#>
function Get-CoopJarManifestAttribute {
    param(
        [Parameter(Mandatory = $true)][string] $JarPath,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) {
            throw "$JarPath has no META-INF/MANIFEST.MF."
        }
        $stream = $entry.Open()
        try {
            $reader = New-Object System.IO.StreamReader($stream)
            try {
                $text = $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }

    # Manifest attributes wrap at 72 bytes with a leading space on continuation lines. Unwrap
    # before matching so a long value still reads correctly.
    $unwrapped = $text -replace "\r\n ", '' -replace "\n ", ''
    $match = [regex]::Match($unwrapped, "(?m)^" + [regex]::Escape($Name) + ":\s*(.+?)\s*$")
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups[1].Value
}

# ---------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $modRoot 'dist'
}
$OutDir = Resolve-CoopFullPath $OutDir

$modInfoPath = Join-Path $modRoot 'mod_info.json'
$buildGradlePath = Join-Path $modRoot 'build.gradle'
foreach ($required in @($modInfoPath, $buildGradlePath)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing $required - this script must live in the coop mod's scripts folder."
    }
}

$modInfoVersion = Get-CoopModInfoVersion -Path $modInfoPath
$gradleVersion = Get-CoopGradleVersion -Path $buildGradlePath
if ($modInfoVersion -cne $gradleVersion) {
    throw ("Version mismatch: mod_info.json says '$modInfoVersion', build.gradle says" +
        " '$gradleVersion'. The handshake compares both, so a session with mismatched files is" +
        " rejected. Make them the same string (release checklist step 1).")
}
$version = $modInfoVersion

$dirty = @(Invoke-CoopGit -Arguments @('status', '--porcelain'))
$dirty = @($dirty | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($dirty.Count -gt 0) {
    if (-not $AllowDirty) {
        throw ("The working tree has $($dirty.Count) uncommitted change(s), so the jars would" +
            " report their commit as `"dev-uncommitted`" and no other machine could match them." +
            " Commit first (release checklist step 3), or pass -AllowDirty for a local archive" +
            " that is not a release.`n  " + (($dirty | Select-Object -First 20) -join "`n  "))
    }
    Write-Warning ("-AllowDirty: packaging with $($dirty.Count) uncommitted change(s). This" +
        " archive is not a release.")
}

$headCommit = (Invoke-CoopGit -Arguments @('rev-parse', '--short=12', 'HEAD') | Select-Object -First 1).Trim()

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

if ($SkipBuild) {
    Write-Host "-SkipBuild: packaging the jars already in $modRoot\jars."
}
else {
    $buildScript = Join-Path $PSScriptRoot 'build.ps1'
    if (-not (Test-Path -LiteralPath $buildScript)) {
        throw "Missing $buildScript."
    }
    Write-Host "Building (clean, test, build)..."
    if ([string]::IsNullOrWhiteSpace($BaseInstallRoot)) {
        & $buildScript
    }
    else {
        & $buildScript -BaseInstallRoot $BaseInstallRoot
    }
    if ($LASTEXITCODE -ne 0) {
        throw "scripts\build.ps1 failed with exit code $LASTEXITCODE."
    }
}

# ---------------------------------------------------------------------------------------------
# Verify the built artifacts
# ---------------------------------------------------------------------------------------------

$jarsDir = Join-Path $modRoot 'jars'
$coopJar = Join-Path $jarsDir 'coop.jar'
$forksJar = Join-Path $jarsDir 'coop-forks.jar'
$launcherJar = Join-Path $jarsDir 'coop-launcher.jar'

foreach ($required in @($coopJar, $forksJar)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw ("Missing $required. Run scripts\build.ps1 (its `"build`" task depends on forksJar," +
            " so a `"jar`"-only run is not a release build).")
    }
}

$jarsToShip = New-Object System.Collections.Generic.List[string]
$jarsToShip.Add($coopJar)
$jarsToShip.Add($forksJar)
if (Test-Path -LiteralPath $launcherJar) {
    $jarsToShip.Add($launcherJar)
}
else {
    Write-Warning ("jars\coop-launcher.jar is missing, so the archive ships without the desktop" +
        " launcher's jar. Build with a current build.gradle to include it.")
}

if ($SkipCommitCheck) {
    Write-Warning ("################################################################")
    Write-Warning ("-SkipCommitCheck: NOT verifying that the jars were built at HEAD.")
    Write-Warning ("The handshake compares Coop-Git-Commit, so an archive made this way")
    Write-Warning ("can be refused at connect with COOP-MODS. Dry runs only, never a release.")
    Write-Warning ("################################################################")
}
else {
    foreach ($jar in $jarsToShip) {
        $jarCommit = Get-CoopJarManifestAttribute -JarPath $jar -Name 'Coop-Git-Commit'
        if ($null -eq $jarCommit) {
            throw "$jar has no Coop-Git-Commit manifest attribute. Rebuild with scripts\build.ps1."
        }
        if ($jarCommit -cne $headCommit) {
            throw ("$jar was built at commit '$jarCommit' but HEAD is '$headCommit'. The commit is" +
                " baked into the jar and compared at connect, so build after committing, not" +
                " before (release checklist steps 3 and 4). Rerun without -SkipBuild, or pass" +
                " -SkipCommitCheck for a local dry run.")
        }
    }
    Write-Host "Jar manifests all report Coop-Git-Commit $headCommit."
}

# Classloader split (release checklist step 7).
$coopJarEntries = Get-CoopJarEntryNames -JarPath $coopJar
$strays = @($coopJarEntries | Where-Object { $_ -like 'coop/rng/*' -or $_ -like 'coop/presence/*' })
if ($strays.Count -gt 0) {
    throw ("jars\coop.jar contains $($strays.Count) entry/entries under coop/rng/ or" +
        " coop/presence/. Those packages belong to the system classloader only; a duplicate in" +
        " coop.jar breaks the forks silently.`n  " + (($strays | Select-Object -First 10) -join "`n  "))
}

$forksJarEntries = Get-CoopJarEntryNames -JarPath $forksJar
foreach ($package in @('coop/rng/', 'coop/presence/')) {
    if (-not ($forksJarEntries | Where-Object { $_ -like ($package + '*') })) {
        throw ("jars\coop-forks.jar has no $package entries. The forks jar is what puts those" +
            " packages on the system classloader; without them the forked engine classes cannot" +
            " resolve them.")
    }
}
Write-Host "Classloader split verified: coop/rng/ and coop/presence/ live only in coop-forks.jar."

# ---------------------------------------------------------------------------------------------
# Stage
# ---------------------------------------------------------------------------------------------

# The archive must unpack to a folder named exactly "coop": the handshake compares the mod path as
# mods/<folder name>, so an archive that unpacks to "coop-0.1.0" is refused at connect.
$stagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("coop-release-" + [guid]::NewGuid().ToString('N'))
$stagedMod = Join-Path $stagingRoot 'coop'

try {
    New-Item -ItemType Directory -Path $stagedMod -Force | Out-Null

    # Files at the mod root.
    $rootFiles = @('mod_info.json', 'coop.version', 'LICENSE', 'CHANGELOG.md', 'README.md', 'Coop Launcher.cmd')
    foreach ($name in $rootFiles) {
        $source = Join-Path $modRoot $name
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $stagedMod $name) -Force
        }
        elseif ($name -eq 'Coop Launcher.cmd') {
            Write-Warning ("`"Coop Launcher.cmd`" is missing from the mod root, so the archive" +
                " ships without the launcher entry point.")
        }
        else {
            throw "Missing $source - the release archive has to carry it."
        }
    }

    # jars\: only the three the mod ships, never whatever else the folder accumulated.
    $stagedJars = Join-Path $stagedMod 'jars'
    New-Item -ItemType Directory -Path $stagedJars -Force | Out-Null
    foreach ($jar in $jarsToShip) {
        Copy-Item -LiteralPath $jar -Destination $stagedJars -Force
    }

    # data\: the whole tree, as deploy-to-test-clients.ps1 copies it.
    $dataDir = Join-Path $modRoot 'data'
    if (-not (Test-Path -LiteralPath $dataDir -PathType Container)) {
        throw "Missing $dataDir - the release archive has to carry it."
    }
    Copy-Item -LiteralPath $dataDir -Destination (Join-Path $stagedMod 'data') -Recurse -Force

    # docs\player\ only. The design and plan documents stay in the repo.
    $playerDocs = Join-Path $modRoot 'docs\player'
    if (-not (Test-Path -LiteralPath $playerDocs -PathType Container)) {
        throw "Missing $playerDocs - the release archive has to carry it."
    }
    $stagedDocs = Join-Path $stagedMod 'docs'
    New-Item -ItemType Directory -Path $stagedDocs -Force | Out-Null
    Copy-Item -LiteralPath $playerDocs -Destination (Join-Path $stagedDocs 'player') -Recurse -Force

    # -----------------------------------------------------------------------------------------
    # Zip
    # -----------------------------------------------------------------------------------------

    if (-not (Test-Path -LiteralPath $OutDir)) {
        New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    }
    $zipPath = Join-Path $OutDir "coop-$version.zip"
    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    # Entries are added by hand rather than with CreateFromDirectory so the names are known to use
    # forward slashes on every runtime, and so the printed listing is the archive's real content.
    $stagingRootPrefix = (Resolve-CoopFullPath $stagingRoot).TrimEnd('\') + '\'
    $entryNames = New-Object System.Collections.Generic.List[string]
    $archive = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = Get-ChildItem -LiteralPath $stagingRoot -Recurse -File | Sort-Object FullName
        foreach ($file in $files) {
            $entryName = $file.FullName.Substring($stagingRootPrefix.Length).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $entryName,
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
            $entryNames.Add($entryName)
        }
    }
    finally {
        $archive.Dispose()
    }

    $zipInfo = Get-Item -LiteralPath $zipPath
    Write-Host ""
    Write-Host "Wrote $($zipInfo.FullName)"
    Write-Host ("  size:    {0:N0} bytes ({1:N1} MiB)" -f $zipInfo.Length, ($zipInfo.Length / 1MB))
    Write-Host "  version: $version"
    Write-Host "  commit:  $headCommit"
    Write-Host "  entries: $($entryNames.Count)"
    Write-Host ""
    foreach ($entryName in $entryNames) {
        Write-Host "    $entryName"
    }
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}
