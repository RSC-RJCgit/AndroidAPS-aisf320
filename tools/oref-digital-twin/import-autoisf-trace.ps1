param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$toolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
if (-not $OutputPath) {
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($inputFile)
    $OutputPath = Join-Path (Split-Path -Parent $inputFile) "$baseName-autoisf-replay.jsonl"
}
$outputFile = [System.IO.Path]::GetFullPath($OutputPath)

$py = Get-Command py -ErrorAction SilentlyContinue
$python = Get-Command python -ErrorAction SilentlyContinue
$codexBundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"

Push-Location $toolRoot
try {
    if ($py) {
        & $py.Source -3 -m replay.autoisf_trace $inputFile --output $outputFile
    } elseif ($python) {
        & $python.Source -m replay.autoisf_trace $inputFile --output $outputFile
    } elseif (Test-Path -LiteralPath $codexBundledPython) {
        & $codexBundledPython -m replay.autoisf_trace $inputFile --output $outputFile
    } else {
        throw "Python 3 was not found. Install Python 3 or run replay.autoisf_trace with its full executable path."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Trace import failed with exit code $LASTEXITCODE. See the summary above."
    }
    Write-Output "Validated replay records: $outputFile"
} finally {
    Pop-Location
}
