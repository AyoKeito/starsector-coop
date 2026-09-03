# Path detection shared by the coop scripts. Dot-source it:
#
#     . (Join-Path $PSScriptRoot 'coop-paths.ps1')
#
# Nothing here has a K:\ in it. Every default is derived from where this file sits, or from a
# Starsector install found on the machine. Every failure throws with the parameter to pass instead.

Set-StrictMode -Version Latest

function Resolve-CoopFullPath {
    param([Parameter(Mandatory = $true)][string] $Path)
    return [System.IO.Path]::GetFullPath($Path)
}

<#
.SYNOPSIS
The coop mod folder: the parent of the scripts folder this file lives in.
#>
function Get-CoopModRoot {
    return Resolve-CoopFullPath (Split-Path -Parent $PSScriptRoot)
}

<#
.SYNOPSIS
True when a folder is a Starsector install (has the launcher, vmparams and starsector-core).
#>
function Test-CoopStarsectorRoot {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }
    foreach ($required in @('starsector.exe', 'vmparams', 'starsector-core')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Path $required))) {
            return $false
        }
    }
    return $true
}

<#
.SYNOPSIS
Throws unless the folder is a Starsector install, naming the file that was missing.
#>
function Assert-CoopStarsectorRoot {
    param([Parameter(Mandatory = $true)][string] $Path)
    foreach ($required in @('starsector.exe', 'vmparams', 'starsector-core')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Path $required))) {
            throw "Missing $required under $Path - that is not a Starsector install root."
        }
    }
}

<#
.SYNOPSIS
The Starsector install this mod belongs to.

.DESCRIPTION
Two sources, in order. First the mod's own location: a mod folder sits at <install>\mods\<name>, so
two levels up from the mod root is the install. That is the answer on any machine where the repo is
checked out in place, and it is the answer on the dev box. If that fails (a repo cloned somewhere
else entirely), the usual Windows install locations are tried. If neither works the caller is told to
pass the path explicitly rather than being handed a guess.
#>
function Get-CoopInstallRoot {
    param(
        [string] $ModRoot,
        [string] $ParameterName = '-BaseInstallRoot'
    )

    if ([string]::IsNullOrWhiteSpace($ModRoot)) {
        $ModRoot = Get-CoopModRoot
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Resolve-CoopFullPath (Join-Path $ModRoot '..\..')))

    foreach ($programFiles in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
        if (-not [string]::IsNullOrWhiteSpace($programFiles)) {
            $candidates.Add((Resolve-CoopFullPath (Join-Path $programFiles 'Fractal Softworks\Starsector')))
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-CoopStarsectorRoot $candidate) {
            return $candidate
        }
    }

    throw ("Could not find a Starsector install. Looked in:`n  " + ($candidates -join "`n  ") +
        "`nPass the install root explicitly with $ParameterName '<path to Starsector>'.")
}

<#
.SYNOPSIS
Where the two-client test profiles go by default.

.DESCRIPTION
A sibling of the install named <install leaf>-coop-test, so an install at K:\Starsector gives
K:\Starsector-coop-test. The one exception is an install under Program Files, where a sibling folder
would need an elevated shell to create; those fall back to %LOCALAPPDATA%.
#>
function Get-CoopDefaultTestRoot {
    param([string] $InstallRoot)

    if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
        $InstallRoot = Get-CoopInstallRoot
    }
    $InstallRoot = (Resolve-CoopFullPath $InstallRoot).TrimEnd('\')

    $parent = Split-Path -Parent $InstallRoot
    $leaf = Split-Path -Leaf $InstallRoot

    foreach ($programFiles in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
        if ([string]::IsNullOrWhiteSpace($programFiles)) {
            continue
        }
        $prefix = (Resolve-CoopFullPath $programFiles).TrimEnd('\') + '\'
        if (($InstallRoot + '\').StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
                throw ("Starsector is installed under $programFiles and LOCALAPPDATA is not set, so" +
                    " there is no writable default for the test profiles. Pass -TestRoot '<path>'.")
            }
            return Resolve-CoopFullPath (Join-Path $env:LOCALAPPDATA ($leaf + '-coop-test'))
        }
    }

    if ([string]::IsNullOrWhiteSpace($parent)) {
        throw ("Starsector is installed at a drive root ($InstallRoot), which leaves nowhere to put a" +
            " sibling test folder. Pass -TestRoot '<path>'.")
    }
    return Resolve-CoopFullPath (Join-Path $parent ($leaf + '-coop-test'))
}

<#
.SYNOPSIS
Resolves a -TestRoot argument, filling in the derived default when it was not passed.
#>
function Resolve-CoopTestRoot {
    param([string] $TestRoot)
    if ([string]::IsNullOrWhiteSpace($TestRoot)) {
        return Get-CoopDefaultTestRoot
    }
    return Resolve-CoopFullPath $TestRoot
}
