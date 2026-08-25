param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath
)

$ErrorActionPreference = "Stop"
$toolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $toolRoot "..\..")).Path
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
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
    & $pythonExecutable @pythonArguments `
        -m replay.autoisf_baseline $inputFile `
        --adapter-command $gradleWrapper :plugins:aps:runAutoIsfReplayAdapter --quiet --console=plain
    if ($LASTEXITCODE -ne 0) {
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
