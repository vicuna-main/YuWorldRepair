[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Scan', 'Prepare', 'Apply', 'Verify', 'Rollback', 'VerifyRollback', 'Status')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$ToolJar,

    [string]$WorldCopy,
    [string]$JobRoot,
    [string]$Job,
    [string]$IceAndFireJar,
    [string]$ConfirmToken,
    [string]$MinecraftVersion,
    [string]$NeoForgeVersion,
    [string]$YouerVersion,
    [string[]]$ProtectedRoot = @(),
    [switch]$DryRun,
    [string]$JavaExe = 'java'
)

$ErrorActionPreference = 'Stop'

function Resolve-AbsoluteExisting([string]$Value, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Value) -or -not [IO.Path]::IsPathFullyQualified($Value)) {
        throw "$Description must be an absolute path."
    }
    return (Resolve-Path -LiteralPath $Value -ErrorAction Stop).Path
}

function Resolve-Absolute([string]$Value, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Value) -or -not [IO.Path]::IsPathFullyQualified($Value)) {
        throw "$Description must be an absolute path."
    }
    return [IO.Path]::GetFullPath($Value)
}

function Test-IsSameOrChild([string]$Candidate, [string]$Root) {
    $normalizedCandidate = [IO.Path]::GetFullPath($Candidate).TrimEnd('\', '/')
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    return $normalizedCandidate.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedCandidate.StartsWith(
            $normalizedRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )
}

function Assert-NotProtected([string]$Candidate, [string[]]$Roots) {
    foreach ($root in $Roots) {
        if (Test-IsSameOrChild $Candidate $root) {
            throw "Refusing path inside protected root: $Candidate"
        }
    }
}

$tool = Resolve-AbsoluteExisting $ToolJar 'ToolJar'
if (-not ([IO.Path]::GetFileName($tool) -like 'YuWorldRepair-world-tool-1.1.0-experimental+mc1.21.1*.jar') -or
    [IO.Path]::GetFileName($tool) -like '*-sources.jar') {
    throw "ToolJar is not the explicitly experimental offline world-tool artifact."
}

$protectedRoots = [Collections.Generic.List[string]]::new()
foreach ($root in $ProtectedRoot) {
    $protectedRoots.Add((Resolve-AbsoluteExisting $root 'ProtectedRoot'))
}

$arguments = [Collections.Generic.List[string]]::new()
if ($protectedRoots.Count -gt 0) {
    $propertyValue = [string]::Join([IO.Path]::PathSeparator, $protectedRoots)
    $arguments.Add("-Dyuworldrepair.protectedRoots=$propertyValue")
}
$arguments.Add('-jar')
$arguments.Add($tool)

switch ($Action) {
    'Scan' {
        $world = Resolve-AbsoluteExisting $WorldCopy 'WorldCopy'
        $jobs = Resolve-Absolute $JobRoot 'JobRoot'
        $ice = Resolve-AbsoluteExisting $IceAndFireJar 'IceAndFireJar'
        Assert-NotProtected $world $protectedRoots
        Assert-NotProtected $jobs $protectedRoots
        if (Test-IsSameOrChild $jobs $world) {
            throw "JobRoot must be outside WorldCopy."
        }
        $marker = Join-Path $world '.yuworldrepair-world-copy'
        if ((Get-Content -LiteralPath $marker -Raw -ErrorAction Stop).Trim() -ne
            'YUWORLDREPAIR_WORLD_COPY_V1') {
            throw "WorldCopy does not have the exact YuWorldRepair copy marker."
        }

        Write-Host "ACTION=scan"
        Write-Host "TOOL=$tool"
        Write-Host "WORLD_COPY=$world"
        Write-Host "JOB_ROOT=$jobs"
        Write-Host "ICE_AND_FIRE_JAR=$ice"
        Write-Host "PROTECTED_ROOTS=$([string]::Join(';', $protectedRoots))"

        $arguments.Add('scan')
        $arguments.Add('--world-copy')
        $arguments.Add($world)
        $arguments.Add('--job-root')
        $arguments.Add($jobs)
        $arguments.Add('--iceandfire-jar')
        $arguments.Add($ice)
        $arguments.Add('--adapter')
        $arguments.Add('iceandfire-chicken-data')
        if ($DryRun) {
            $arguments.Add('--dry-run')
        }
        $metadataItems = @(
            [pscustomobject]@{ Option = '--minecraft-version'; Value = $MinecraftVersion }
            [pscustomobject]@{ Option = '--neoforge-version'; Value = $NeoForgeVersion }
            [pscustomobject]@{ Option = '--youer-version'; Value = $YouerVersion }
        )
        foreach ($metadata in $metadataItems) {
            if (-not [string]::IsNullOrWhiteSpace($metadata.Value)) {
                $arguments.Add($metadata.Option)
                $arguments.Add($metadata.Value)
            }
        }
    }
    default {
        $jobPath = Resolve-AbsoluteExisting $Job 'Job'
        Assert-NotProtected $jobPath $protectedRoots
        Write-Host "ACTION=$($Action.ToLowerInvariant())"
        Write-Host "TOOL=$tool"
        Write-Host "JOB=$jobPath"

        $command = switch ($Action) {
            'Prepare' { 'prepare' }
            'Apply' { 'apply' }
            'Verify' { 'verify' }
            'Rollback' { 'rollback' }
            'VerifyRollback' { 'verify-rollback' }
            'Status' { 'status' }
        }
        $arguments.Add($command)
        $arguments.Add('--job')
        $arguments.Add($jobPath)
        if ($Action -in @('Apply', 'Rollback')) {
            if ([string]::IsNullOrWhiteSpace($ConfirmToken)) {
                throw "$Action requires ConfirmToken."
            }
            $arguments.Add('--confirm')
            $arguments.Add($ConfirmToken)
        } elseif (-not [string]::IsNullOrWhiteSpace($ConfirmToken)) {
            throw "ConfirmToken is only accepted by Apply or Rollback."
        }
        if ($DryRun) {
            throw "DryRun is only valid with Scan."
        }
    }
}

& $JavaExe @arguments
exit $LASTEXITCODE
