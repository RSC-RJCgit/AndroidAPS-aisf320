# Pre-build guard: every automation Trigger*/Action* class must be registered
# in AutomationModule.kt (@ContributesAndroidInjector). A missing binding is NOT
# a compile error - it crashes at runtime when the choose-trigger/action dialog
# instantiates every type to build its menu (see AU256 TriggerSmbDeliveryRatio /
# ActionSetSmbDeliveryRatio incident, fixed AU267/AU268).
#
# Usage:  .\check_di_bindings.ps1        (from repo root; exits 1 if anything is unbound)

$repo       = $PSScriptRoot
$automation = Join-Path $repo 'plugins\automation\src\main\kotlin\app\aaps\plugins\automation'
$moduleFile = Join-Path $automation 'di\AutomationModule.kt'

if (-not (Test-Path $moduleFile)) { Write-Host "AutomationModule.kt not found - wrong directory?" -ForegroundColor Red; exit 1 }
$module = Get-Content $moduleFile -Raw

$missing = @()
foreach ($set in @(
    @{ Dir = 'triggers'; Pattern = 'Trigger*.kt' },
    @{ Dir = 'actions';  Pattern = 'Action*.kt'  }
)) {
    Get-ChildItem (Join-Path $automation $set.Dir) -Filter $set.Pattern | ForEach-Object {
        $name = $_.BaseName
        if ($module -notmatch "\b$name\b") { $missing += "$($set.Dir)\$name" }
    }
}

if ($missing.Count -gt 0) {
    Write-Host 'MISSING DI BINDINGS in AutomationModule.kt:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host 'Add an import + @ContributesAndroidInjector line for each before building.' -ForegroundColor Yellow
    exit 1
} else {
    Write-Host 'DI bindings OK: every automation trigger/action is registered.' -ForegroundColor Green
    exit 0
}
