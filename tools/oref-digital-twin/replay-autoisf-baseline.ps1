param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    # Added 2026-08-26: results previously only ever appeared in the live console -- nothing was
    # ever saved, so there was no way to reopen a past run's output. Defaults to a timestamped file
    # right next to the input JSONL; pass an explicit path to control the location, or "" to skip
    # saving and only print to the console (the old behavior).
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$toolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $toolRoot "..\..")).Path
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if (-not $PSBoundParameters.ContainsKey('OutputPath')) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = "$inputFile-baseline-$timestamp.txt"
}
$codexBundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
$py = Get-Command py -ErrorAction SilentlyContinue
$python = Get-Command python -ErrorAction SilentlyContinue

if (Test-Path -LiteralPath $codexBundledPython) {
    $pythonExecutable = $codexBundledPython
    $pythonArguments = @()
} elseif ($py) {
    $pythonExecutable = $py.Source
    $pythonArguments = @("-3")
} elseif ($python) {
    $pythonExecutable = $python.Source
    $pythonArguments = @()
} else {
    throw "Python 3 was not found."
}

# gradlew.bat otherwise inherits whichever legacy java.exe happens to be first on PATH. Use the
# same bundled JBR as the running Android Studio process, scoped to this PowerShell child only.
$studioProcess = Get-Process -Name studio64 -ErrorAction SilentlyContinue | Select-Object -First 1
$studioJbr = if ($studioProcess -and $studioProcess.Path) {
    Join-Path (Split-Path -Parent (Split-Path -Parent $studioProcess.Path)) "jbr"
} else {
    "C:\Program Files\Android\Android Studio\jbr"
}
if (-not (Test-Path -LiteralPath (Join-Path $studioJbr "bin\java.exe"))) {
    throw "Android Studio's bundled Java runtime was not found: $studioJbr"
}
$previousJavaHome = $env:JAVA_HOME

Push-Location $toolRoot
try {
    $env:JAVA_HOME = $studioJbr
    if ($OutputPath) {
        # No stderr redirect here (e.g. "2>$null") -- PowerShell 5.1 wraps a native command's
        # stderr lines as ErrorRecord/NativeCommandError objects on ANY stream-2 redirect, which
        # combined with this script's $ErrorActionPreference = "Stop" can abort the script outright
        # even on an exit code of 0. A plain rev-parse on a real repo doesn't write to stderr anyway.
        $commitHash = & git -C $repoRoot rev-parse --short HEAD
        $header = @(
            "AutoISF replay baseline run"
            "input:      $inputFile"
            "run at:     $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
            "repo HEAD:  $commitHash"
            ("-" * 40)
        )
        $header | Set-Content -LiteralPath $OutputPath -Encoding utf8
        # Capture stderr too (a real crash's traceback is stderr, and that's exactly what a saved
        # record should preserve) but only for this one call -- temporarily relax
        # ErrorActionPreference so PowerShell 5.1's stderr-redirect wrapping (see above) can't abort
        # the whole script; $LASTEXITCODE below is unaffected by this and stays the real exit code.
        $savedEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $pythonExecutable @pythonArguments `
            -m replay.autoisf_baseline $inputFile `
            --adapter-command $gradleWrapper :plugins:aps:runAutoIsfReplayAdapter --quiet --console=plain 2>&1 |
            Tee-Object -FilePath $OutputPath -Append
        $ErrorActionPreference = $savedEap
        $exitCode = $LASTEXITCODE
        Write-Output "Saved to: $OutputPath"
    } else {
        & $pythonExecutable @pythonArguments `
            -m replay.autoisf_baseline $inputFile `
            --adapter-command $gradleWrapper :plugins:aps:runAutoIsfReplayAdapter --quiet --console=plain
        $exitCode = $LASTEXITCODE
    }
    if ($exitCode -ne 0) {
        throw "AutoISF Kotlin baseline did not match. Counterfactual replay remains disabled."
    }
} finally {
    if ($null -eq $previousJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME = $previousJavaHome
    }
    Pop-Location
}
