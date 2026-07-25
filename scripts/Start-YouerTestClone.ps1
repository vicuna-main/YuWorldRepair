[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ClonePath,

    [Parameter(Mandatory = $true)]
    [string]$YouerJar,

    [string]$Java = 'java',
    [int]$MaxSeconds = 300
)

$ErrorActionPreference = 'Stop'
$clone = (Resolve-Path -LiteralPath $ClonePath).Path
if ($clone -notmatch '[\\/]yuworldrepair-test[\\/]') {
    throw "Refusing to start a directory outside yuworldrepair-test: $clone"
}
$jar = (Resolve-Path -LiteralPath (Join-Path $clone $YouerJar)).Path
$stdout = Join-Path $clone 'yuworldrepair-smoke.out.log'
$stderr = Join-Path $clone 'yuworldrepair-smoke.err.log'

Write-Host "CLONE=$clone"
Write-Host "JAR=$jar"
Write-Host "STDOUT=$stdout"
Write-Host "STDERR=$stderr"

$process = Start-Process `
    -FilePath $Java `
    -ArgumentList @('-Xms2G', '-Xmx2G', '-jar', $jar, 'nogui') `
    -WorkingDirectory $clone `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru

$deadline = (Get-Date).AddSeconds($MaxSeconds)
$started = $false
while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
    if (Test-Path -LiteralPath $stdout) {
        $started = Select-String -LiteralPath $stdout -SimpleMatch 'Done (' -Quiet
        if ($started) {
            break
        }
    }
    $process.Refresh()
}

if ($started) {
    Write-Host 'SERVER_READY=true'
} elseif ($process.HasExited) {
    Write-Host "SERVER_READY=false EXIT_CODE=$($process.ExitCode)"
} else {
    Write-Host 'SERVER_READY=false TIMEOUT=true'
}

if (-not $process.HasExited) {
    Stop-Process -Id $process.Id
    $process.WaitForExit()
}
Write-Host "EXIT_CODE=$($process.ExitCode)"
