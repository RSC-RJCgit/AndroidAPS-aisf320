# Finds the most recently modified AutoISF digital-twin trace (*-autoisf-replay.jsonl) across
# every C:\backup\AAPS\logs* device folder, replays it via replay-autoisf-baseline.ps1, then opens
# the saved results file in Notepad. Meant to be launched via the desktop shortcut/.bat wrapper,
# not run on any schedule -- purely on-demand.

$ErrorActionPreference = "Stop"
$toolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$searchRoot = "C:\backup\AAPS"

$candidates = Get-ChildItem -Path (Join-Path $searchRoot "logs*") -Filter "*-autoisf-replay.jsonl" -Recurse -ErrorAction SilentlyContinue
if (-not $candidates -or $candidates.Count -eq 0) {
    [System.Windows.Forms.MessageBox]::LoadWithPartialName("System.Windows.Forms") | Out-Null
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(
        "No AutoISF replay trace (*-autoisf-replay.jsonl) found under $searchRoot`n`nImport one first with import-autoisf-trace.ps1 against an exported AAPS log.",
        "AutoISF Twin Replay",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning
    ) | Out-Null
    exit 1
}

$newest = $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputPath = "$($newest.FullName)-baseline-$timestamp.txt"

& (Join-Path $toolRoot "replay-autoisf-baseline.ps1") -InputPath $newest.FullName -OutputPath $outputPath
# Deliberately ignore the script's own exit code here -- exit 1 just means some records didn't
# match (a real, useful result to go look at), not that the run itself failed to produce output.

if (Test-Path -LiteralPath $outputPath) {
    Start-Process notepad.exe $outputPath
} else {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(
        "The replay run did not produce a results file (it may have failed before completing). Trace used:`n$($newest.FullName)",
        "AutoISF Twin Replay",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error
    ) | Out-Null
}
