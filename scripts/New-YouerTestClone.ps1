[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceServer,

    [Parameter(Mandatory = $true)]
    [string]$YouerJar,

    [Parameter(Mandatory = $true)]
    [string]$ModJar,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$MatrixName
)

$ErrorActionPreference = 'Stop'

function Resolve-AbsoluteExistingPath([string]$Path) {
    return (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
}

function Assert-Descendant([string]$Candidate, [string]$Root) {
    $absoluteCandidate = [IO.Path]::GetFullPath($Candidate)
    $absoluteRoot = [IO.Path]::GetFullPath($Root).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = $absoluteRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $absoluteCandidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe target outside test root: $absoluteCandidate"
    }
    return $absoluteCandidate
}

$source = Resolve-AbsoluteExistingPath $SourceServer
$mod = Resolve-AbsoluteExistingPath $ModJar
$serverJar = Resolve-AbsoluteExistingPath (Join-Path $source $YouerJar)
$testRoot = Join-Path $source 'yuworldrepair-test'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Assert-Descendant (Join-Path $testRoot "$timestamp-$MatrixName") $testRoot

Write-Host "SOURCE=$source"
Write-Host "TEST_ROOT=$([IO.Path]::GetFullPath($testRoot))"
Write-Host "TARGET=$target"
Write-Host "YOUER_JAR=$serverJar"
Write-Host "YUWORLDREPAIR_JAR=$mod"

$running = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and
        $_.CommandLine -and
        $_.CommandLine.IndexOf($source, [StringComparison]::OrdinalIgnoreCase) -ge 0
    }
if ($running) {
    throw "A Java process references the source server. Stop it before cloning."
}

if (Test-Path -LiteralPath $target) {
    throw "Clone target already exists: $target"
}
New-Item -ItemType Directory -Path $target | Out-Null

$copyNames = @(
    'config',
    'defaultconfigs',
    'mods',
    'world',
    'eula.txt',
    'server.properties',
    'ops.json',
    'whitelist.json',
    'banned-ips.json',
    'banned-players.json',
    'bukkit.yml',
    'spigot.yml',
    'paper-config',
    'purpur.yml',
    'youer-config'
)

$manifestFiles = [Collections.Generic.List[object]]::new()
foreach ($name in $copyNames) {
    $sourceItem = Join-Path $source $name
    if (-not (Test-Path -LiteralPath $sourceItem)) {
        continue
    }
    $item = Get-Item -LiteralPath $sourceItem
    $reparsePoint = @($item) + @(
        if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $sourceItem -Force -Recurse |
                Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }
        }
    ) | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
        Select-Object -First 1
    if ($reparsePoint) {
        throw "Refusing to clone a reparse point: $($reparsePoint.FullName)"
    }
    $files = if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $sourceItem -File -Recurse
    } else {
        @($item)
    }
    foreach ($file in $files) {
        $hash = Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256
        $manifestFiles.Add([ordered]@{
            path = $file.FullName.Substring($source.Length).TrimStart('\', '/')
            bytes = $file.Length
            modifiedUtc = $file.LastWriteTimeUtc.ToString('o')
            sha256 = $hash.Hash.ToLowerInvariant()
        })
    }
    Copy-Item -LiteralPath $sourceItem -Destination $target -Recurse
}

Copy-Item -LiteralPath $serverJar -Destination (Join-Path $target $YouerJar)
$targetMods = Join-Path $target 'mods'
New-Item -ItemType Directory -Path $targetMods -Force | Out-Null
Copy-Item -LiteralPath $mod -Destination (Join-Path $targetMods (Split-Path $mod -Leaf))

foreach ($entry in $manifestFiles) {
    $sourceFile = Join-Path $source $entry.path
    $cloneFile = Join-Path $target $entry.path
    $sourceHashAfter = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $cloneHash = (Get-FileHash -LiteralPath $cloneFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHashAfter -ne $entry.sha256) {
        throw "Source changed while cloning: $($entry.path)"
    }
    if ($cloneHash -ne $entry.sha256) {
        throw "Clone verification failed: $($entry.path)"
    }
}

$worldCopy = Join-Path $target 'world'
if (Test-Path -LiteralPath $worldCopy -PathType Container) {
    Set-Content -LiteralPath (Join-Path $worldCopy '.yuworldrepair-world-copy') `
        -Value 'YUWORLDREPAIR_WORLD_COPY_V1' -Encoding Ascii -NoNewline
}

$manifest = [ordered]@{
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    source = $source
    target = $target
    youerJar = $YouerJar
    yuworldrepairJar = (Split-Path $mod -Leaf)
    sourceUnchangedAfterCopy = $true
    cloneHashesVerified = $true
    sourceFiles = $manifestFiles
}
$manifestPath = Join-Path $target 'yuworldrepair-input-manifest.json'
$manifest | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "Clone created. Nothing was deleted or started."
Write-Host "MANIFEST=$manifestPath"
