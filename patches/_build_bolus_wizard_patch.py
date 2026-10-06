#!/usr/bin/env python3
"""Build a bolus-calculator patch against AndroidAPS-3426 (3.4.2.6+aisf3.2.1)."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

OURS = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320")
# BASE must be a CLEAN 3.4.2.6+aisf3.2.1 tree (commit a14b8c7663). The AndroidAPS-3426 clone's HEAD has earlier
# versions of this patch applied ("patched" commits), so point BOLUS_PATCH_BASE at a `git archive a14b8c7663`
# extraction of the needed paths instead of the clone itself.
BASE = Path(os.environ.get("BOLUS_PATCH_BASE", r"C:\Users\arjay\StudioProjects\AndroidAPS-3426"))
OUT = OURS / "patches" / "bolus-calculator-on-3426-aisf321.17.patch"
STEPS_MIRROR_COMMIT = "ebdda50d8f"  # aisf321UK_889next: moved the wizard onto fork-only StepCountSource/LiveStepsMirror

FULL_COPY = [
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt",
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/DelayedBolusWorker.kt",
    "ui/src/main/kotlin/app/aaps/ui/dialogs/WizardDialog.kt",
    "ui/src/main/kotlin/app/aaps/ui/activities/QuickWizardListActivity.kt",
    "ui/src/main/res/layout/dialog_wizard.xml",
    "plugins/main/src/main/res/layout/dialog_quick_wizard_max_bolus.xml",
    "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/ScheduledDoseSupersession.kt",
    "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/utils/NoteTimestampAllocator.kt",
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/WizardActivitySteps.kt",
    # Added in patch .8: these three were referenced by BolusWizard.kt/WizardDialog.kt (carb-time-from-rise /
    # recent-entry features) but never added to FULL_COPY, so patch .7 shipped with two unresolved references
    # (WizardRecentEntry at BolusWizard.kt:383, showConfirmationWithView at :781) -- confirmed via a real user
    # (kebel21) build error 2026-09-29. WizardRecentEntry.kt and CarbTimeFromRise.kt are brand-new files (not in
    # BASE at all); OKDialog.kt is an existing file whose only diff from BASE is the showConfirmationWithView
    # addition (verified clean via `git diff a14b8c7663 -- core/ui/.../OKDialog.kt`).
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/WizardRecentEntry.kt",
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/CarbTimeFromRise.kt",
    "core/ui/src/main/kotlin/app/aaps/core/ui/dialogs/OKDialog.kt",
    # Added in patch .9: brand-new file (not in BASE), referenced by WizardDialog.kt's new
    # WizardDropTrendCaution checks. The new ApsAutoIsfLastCycleHp1MilliMmol key and its per-cycle write
    # are added via the existing surgical LongKey/OpenAPSAutoISFPlugin edits further down instead (both
    # files are surgical, not FULL_COPY, since OpenAPSAutoISFPlugin.kt carries many unrelated automations
    # this patch must not bundle in).
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/WizardDropTrendCaution.kt",
]

PATCH_DESCRIPTION = """\
Bolus calculator on 3.4.2.6 + AutoISF 3.2.1 (patch .17, 2026-10-06)

Apply on a CLEAN 3.4.2.6+aisf3.2.1 tree (commit a14b8c7663):
  git apply --check bolus-calculator-on-3426-aisf321.17.patch
  git apply bolus-calculator-on-3426-aisf321.17.patch
(git ignores this leading text.) Turn on Overview preference "Enable delayed bolus" for the
50%-profile / Walking soon top-up path.

Changes in patch .17 (2026-10-06):
- Fat/Protein auto-fill from carbs is now an average-meal estimate: fat = carbs x0.35, protein = carbs x0.4
  (was x1.0 / x1.5, a very heavy meal). "Unreliable SMBs / bad sensor / high protein" doubles it (fat x0.7,
  protein x0.8; was x1.5 / x2.0). New checkbox "Small meal" halves it. Ticking both multiplies the two factors,
  which gives the average again. A manually typed Fat/Protein still sticks until carbs changes.

Changes in patch .16 (2026-10-05):
- The wizard's "still moving now" step thresholds are lowered: steps over 5 minutes 100 -> 20 and steps over
  30 minutes 200 -> 100 (WizardActivitySteps.STILL_NOW_S5 / STILL_NOW_S30). They feed the Walking soon
  auto-tick, the QuickWizard "always on" walking-soon, and DelayedBolusWorker's moving (80%) / seated
  (full remainder) switch, so light walking now counts as moving. S30 alone now only counts while the
  15-minute or 5-minute steps show recent movement (S30 lingers ~25 min after you stop), so lowering it does
  not keep "moving" on after you stop; a missing 15-minute count keeps the S30-only behaviour.
- Retains all patch .15 changes below.

Changes in patch .15 (2026-10-05):
- New wizard checkbox "Delay protein/fat doses instead of cancelling" (checked by default, below
  "Auto fat/protein from carbs"). It switches the patch .14 re-check behaviour on or off for that wizard
  session: ticked, a protein/fat (Warsaw FPU) part that fails a due-time check is re-checked every 10 min
  instead of being cancelled; unticked, it is cancelled as before (plain cancel note, no deferred note).
  The choice is read when the series is scheduled and carried through every re-check
  (BolusWizard.delayFpuInsteadOfCancel -> scheduleSingleDelayedDose(retryLater)). Quick-wizard buttons
  never touch it and keep the default (ticked).
- Retains all patch .14 changes below.

Changes in patch .14 (2026-10-05):
- A protein/fat (Warsaw FPU) dose whose due-time check fails is no longer cancelled for good. If the
  profile is under 100%, the pump is suspended, a superbolus is active, the BG safety check fails, or the
  IOB-rise reduction takes the dose to 0 or less, the part is deferred and re-checked every 10 min, and is
  delivered the first time every check passes (same IOB-delta reduction as before). It is only dropped, with
  a cancel note saying it was not delivered within the retry window, once the next re-check would fall
  inside the 10 min before the NEXT part is due (the last part gets 30 min from its own due time).
  "Bolus stopped" and "superseded by a newer entry" still cancel at once, and are still polled every 2 min
  while a part waits. One CarePortal note ("D1.30" = the amount) is written when a part is first deferred.
  The dose stays in the pending-Warsaw total while it waits. The last part's zero-dose marker is written
  only at the final drop (BolusWizard.scheduleSingleDelayedDose / deferDoseNote).
- Retains all patch .13 changes below.

Changes in patch .13 (2026-10-03):
- Fix: the protein/fat (Warsaw FPU) doses fpu1..fpuN, due an hour apart, were all cancelled together
  minutes after scheduling. Each dose polls every 2 min and ran its profile/pump/superbolus cancel
  checks BEFORE checking whether it was due, so one short 50% profile switch killed doses not due for
  hours. Now only "bolus stopped" and "superseded by a newer entry" cancel early; profile, pump,
  superbolus, BG-safety and IOB checks run only when that dose is actually due
  (BolusWizard.scheduleSingleDelayedDose).
- Retains all patch .12 changes below.

Changes in patch .12 (2026-10-02):
- New wizard checkbox "Auto fat/protein from carbs" (checked by default, below "Unreliable SMBs").
  Unticking it stops the Fat/Protein auto-fill outright for that wizard session -- unlike a manual edit
  to the boxes (which a later carbs edit silently resets), it survives carbs changes. Re-ticking it
  immediately re-applies the carbs-driven suggestion. Existing Fat/Protein values are left as-is when
  unticked. Session-only, not a saved setting.
- Retains all patch .11 changes below.

Changes in patch .11 (2026-10-01):
- Corrects patch .10's "Add FPUs"/"Unreliable SMBs" checkboxes -- manual checkbox gating of FPU was
  itself never actually agreed either. Removed entirely. BolusWizard.kt now computes FPU unconditionally
  from whatever's in the protein/fat fields (plain protein x0.4/fat x0.9, exactly the pre-checkbox
  original), no toggle logic inside the wizard at all. WizardDialog.kt instead AUTO-FILLS the Fat/Protein
  boxes from carbs the moment carbs changes (fat = carbs x1.0, protein = carbs x1.5 -- or x1.5/x2.0 with
  "Unreliable SMBs", matching the KMP reference repo's fpuInstead/unreliableSmb ratios applied to the
  full entered carbs). A manual edit to either box sticks until carbs itself changes again
  (proteinFatManuallyOverridden), rather than being silently overwritten every recalculation.
- New fourth trigger for the existing DelayedBolusWorker catch-up mechanism (profile=50%/recent50/
  walkingSoon already triggered it): WizardDropTrendCaution scaling a dose down now ALSO arms it.
  WizardDropTrendCaution.fullRequiredFromScaled() recovers the true full amount from what was actually
  delivered after that rule's scaling, feeding the SAME existing 5-min-poll/80-min-window/confirmed-rise
  mechanism DelayedBolusWorker already provides for the other three triggers -- no new delivery
  mechanism built, since a real "give the withheld amount later" catch-up was worth having (previously
  that shortfall was just permanently discarded with nothing else aware it was ever owed).
- Retains all patch .10 changes below (its "Add FPUs"/"Unreliable SMBs" checkbox bullet is superseded by
  the correction above).

Changes in patch .10 (2026-10-01):
- Corrects patch .9's "Bolus fat/protein now instead of extending" checkbox -- that "now" behavior (a
  boosted ratio folded straight into the immediate bolus, insulinFromFatProteinNow) was a
  misimplementation that was never actually agreed, and has been removed entirely. In its place, one
  plain "Add FPUs" checkbox (default checked) just gates whether the existing Warsaw-FPU extended series
  (protein x0.4/fat x0.9, delivered via warsawFpuPlan()/scheduleSplitProteinFatDoses() exactly as
  before) computes at all for this calc -- nothing about "now" vs later. The "Unreliable SMBs" sub-toggle
  is kept, but now boosts that SAME extended-series ratio (protein x0.4->x2.0, fat x0.9->x1.5) instead of
  only mattering under the deleted now-path; still auto-sets wiz% to 90 for that one calculation.
- Carb-split (BolusWizard.scheduleReducedPartsSplitBolus) cancellation redesigned: dropped the "3
  consecutive unsafe BG checks" cancel entirely -- delivery itself stays exactly as gated as before
  (nothing unsafe is ever delivered), only the "give up on the residual" decision changes. Now relies
  solely on the existing overall retryDeadline, raised from 60min to 150min (2.5h). Real log data showed
  a fixed 3-check cancel (~14-21min into an unsafe run) was too short to distinguish a genuine
  hypo-trending drop (correctly cancelled) from a fat/protein-slowed rise that took ~107min to clear the
  safety band (looked like real under-dosing) -- and a small bump to 6 checks (~35-42min) didn't close
  that gap either, hence the switch to a real elapsed-time cap instead of a consecutive-check count.
- Retains all patch .9 changes below (its "Bolus fat/protein now" bullet is superseded by the correction
  above -- that checkbox's behavior as originally described never actually shipped in working form).

Changes in patch .9 (2026-09-30):
- DelayedBolusWorker fix: the live-InsReq cap used to permanently end the whole delayed sequence the
  moment InsReq capped one check below the real remaining need (or floored a check to exactly 0), silently
  discarding the undelivered rest. It now only caps that ONE check's delivery and keeps polling (same
  5-min/16-attempt cadence) for whatever's still genuinely owed, tracked via a new cumulative
  deliveredSoFar carried through each re-enqueue -- cobFraction is still recomputed fresh from live COB
  every check, so genuine absorption still legitimately shrinks what's left; only InsReq's own denial no
  longer discards it. Also fixed the plain "wait" re-enqueue path, which wasn't passing deliveredSoFar at
  all and would have silently reset progress on every ordinary wait cycle.
- New WizardDropTrendCaution safety layer: scales max bolus AND wiz% to 50% for one calculation when
  EITHER (a) Delta<=-0.15mmol, SDelta<=-0.10mmol, AND LDelta<=-0.10mmol (an established three-way downtrend)
  together with current BG<6.0mmol or the live cached HP1 hypo-prediction reading below 5.0mmol, OR (b)
  current IOB exceeds 3x however much BG has actually risen (mmol) from its own recent 60-min low. Path (b)
  is the one that matters on a clean base without the fork's full automations layer: path (a)'s HP1 half of
  its OR reads a preference (ApsAutoIsfLastCycleHp1MilliMmol) that only OpenAPSAutoISFPlugin's full coded-
  automations layer ever writes -- absent here, it just stays at its default and degrades safely to the
  plain BG<6.0mmol check -- see WizardDropTrendCaution.kt's own doc comment for the real episode this was
  built from and both thresholds' full reasoning. Neither path touches DelayedBolusWorker, which keeps
  working from its own independent rising-trend criteria regardless.
- Retains all patch .8 changes below.

Changes in patch .8 (2026-09-29):
- Fixes patch .7 shipping with two unresolved references (WizardRecentEntry at BolusWizard.kt:383,
  showConfirmationWithView at :781), found via a real user build error. WizardRecentEntry.kt and
  CarbTimeFromRise.kt (both brand-new files) and OKDialog.kt's showConfirmationWithView addition were
  referenced by BolusWizard.kt/WizardDialog.kt's carb-time-from-rise / recent-entry features but were never
  added to the patch build script's file list. No functional change from .7 -- same code, now complete.
- Retains all patch .7 changes below.

Changes in patch .7 (2026-09-27):
- A cancelled split/delayed/FPU dose (bolus stopped, superseded, profile switch, pump suspended, superbolus active, retry
  timeout, or BG safety check failed) used to leave only the coded graph note ("C1.30" etc.) -- no Treatments-tab row, no
  tappable calc description, unlike a calculated-zero part. cancelDoseNote() now optionally also inserts a real 0U bolus
  plus a full calc-description record (same followUpCalculation() construction as every other follow-up dose), at every
  cancellation site except the one that already gets its own calc description moments earlier from insertZeroDoseTreatment
  (the isLast IOB-rose branch), so that event is not recorded twice. A calculated-zero part, and every delivered (non-zero)
  split/delayed/FPU dose, already had a calc description attached (confirmed unchanged); this only fixes the cancelled case.
- Retains all patch .6 changes below.

Changes in patch .6 (2026-09-20):
- Restore Overview settings "Split bolus when over max" and "Split bolus interval (min)".
  Enable the switch to make the wizard's split controls available under their existing conditions.
- Retains all patch .5 changes below.

Changes since patch .4 (2026-09-19), i.e. patch .5 (2026-09-20) adds:
- Carb split (BolusWizard.scheduleReducedPartsSplitBolus): rounding-leftover fix. The residual 5.2 - 5.0 is 0.20000000000000018 in
  floating point, so after the 0.2 part was delivered a ~1.7e-16 U "remainder" kept scheduling a check every interval, each writing a
  0.00U bolus + calc row + S0.00 note until the 60-min deadline. The new remainder is rounded to 0.001 and anything under half a pump
  step finishes the split (log line only).
- Carb split zeros are deferred: a part skipped because IOB rose (calculated 0U) no longer writes its 0U bolus / calc row / S0.00 note
  at once. Only the LAST skip is written, once, and only if the split then ends without delivering (deadline, stop, superseded, profile
  switch, pump suspended, superbolus, or 3 unsafe BG checks). A later delivered part discards the remembered skip.
- FPU series unchanged: only the last sub-dose writes its zero marker.

Changes in patch .4 (2026-09-19), kept for reference:
- Delayed bolus criteria (DelayedBolusWorker): BG > 5.0, D > 0.10, SD > 0.10, LD > 0 (mmol/L). Was BG > 4.5,
  D > 0.10, (SD >= 0.15 or BG > 5.5), LD > 0.05. Real case: a rising 5.3 mmol waited a further 10 min because LD
  sat exactly on the old 0.05 limit. The old "SD bypass above BG 5.5" is removed.
- Delayed bolus polling: every 5 min for up to 16 checks (5..80 min), was every 10 min for 8 checks. CarePortal
  check notes are now Db5, Db10, Db15 ... Db80 (elapsed minutes; suffixes wait / covered / end / <dose>U). The
  85-min SMB block still covers the whole window.
- Delayed bolus while still moving: keeps 80% of the remaining gap via a new DELAYED_MOVING_PERCENT. The wizard's
  immediate walking-soon cut stays at the shared MOVING_PERCENT (70%).
- Warsaw/FPU series: only the LAST sub-dose writes a 0U bolus marker when it calculates to <= 0. Earlier sub-doses
  still log and write their cancel note but no longer add repeated zero markers to the graphs.
- WizardDialog: FPU preview text now says "unless setting changed above" (the duration shown is the dialog input's
  current value).
Not in this patch: the fork's StepCountSource / LiveStepsMirror steps mirroring. The patch keeps the plain
persisted-steps "still moving" check (WizardActivitySteps.stillMovingNow(persistenceLayer, now)).
"""


def rel(p: str) -> Path:
    return Path(p.replace("/", os.sep))


def read(root: Path, p: str) -> str:
    return (root / rel(p)).read_text(encoding="utf-8")


def write(root: Path, p: str, text: str) -> None:
    dest = root / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)


def copy_ours(staging: Path, p: str) -> None:
    src = OURS / rel(p)
    dest = staging / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    raw = dest.read_text(encoding="utf-8")
    dest.write_text(raw.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def copy_base(staging: Path, p: str) -> None:
    src = BASE / rel(p)
    dest = staging / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    raw = dest.read_text(encoding="utf-8")
    dest.write_text(raw.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def revert_steps_source(staging: Path) -> None:
    """The fork's wizard files (since aisf321UK_889next) read steps through StepCountSource/LiveStepsMirror,
    which the clean base does not have. Keep the plain persisted-steps check in the patch payload instead."""
    p = "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/WizardActivitySteps.kt"
    old = subprocess.run(
        ["git", "show", f"{STEPS_MIRROR_COMMIT}~1:{p}"], cwd=OURS, capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout.replace("\r\n", "\n")
    # 2026-10-05, per explicit request: lower thresholds (S5 100 -> 20, S30 200 -> 100), and carry the current fork's
    # "S30 lingers" guard (S30 alone only counts while S15 or S5 show recent steps) so lowering S30 does not leave "moving"
    # on for ~25 min after you stop. The old payload text lacks both, so they are applied here.
    old = must_replace(old, "STILL_NOW_S30 = 200", "STILL_NOW_S30 = 100", "WAS S30")
    old = must_replace(old, "STILL_NOW_S5 = 100", "STILL_NOW_S5 = 20", "WAS S5")
    old = must_replace(
        old,
        """    fun stillMovingNow(steps5min: Int, steps30min: Int): Boolean =
        steps30min >= STILL_NOW_S30 || steps5min >= STILL_NOW_S5
""",
        """    // S30 alone lingers ~25 min after you stop, so it only counts while S15 or S5 shows recent steps.
    // A missing S15 (null) keeps the S30-only behaviour.
    fun stillMovingNow(steps5min: Int, steps30min: Int, steps15min: Int? = null): Boolean =
        steps5min >= STILL_NOW_S5 ||
            (steps30min >= STILL_NOW_S30 && (steps15min == null || steps15min > 0 || steps5min > 0))
""",
        "WAS fn",
    )
    old = must_replace(
        old,
        "return stillMovingNow(sample.steps5min, sample.steps30min)",
        "return stillMovingNow(sample.steps5min, sample.steps30min, sample.steps15min)",
        "WAS persisted",
    )
    write(staging, p, old)

    p = "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/DelayedBolusWorker.kt"
    t = read(staging, p)
    t = must_replace(t, "import app.aaps.core.objects.utils.StepCountSource\n", "", "DBW import")
    t = must_replace(t, "    @Inject lateinit var stepCountSource: StepCountSource\n", "", "DBW inject")
    t = must_replace(
        t,
        """            val movingNow = WizardActivitySteps.stillMovingNow(stepCountSource, now)
            if (movingNow == null) {
                aapsLogger.info(LTag.CORE, "Delayed bolus: Live steps unavailable; no automatic dose")
                addCheckNote("$dbLabel Live steps unavailable")
                unblockSmb("Live steps unavailable")
                return Result.success()
            }
""",
        "            val movingNow = WizardActivitySteps.stillMovingNow(persistenceLayer, now)\n",
        "DBW movingNow",
    )
    # 2026-09-20: fork-only key LongNonKey.LastDelayedBolusDeliveredAt (feeds the fork's StuckRisingSlowly automation, not the
    # bolus calculator) does not exist on the clean base -- keep it out of the patch payload.
    t = must_replace(t, "import app.aaps.core.keys.LongNonKey\n", "", "DBW LongNonKey import")
    t = must_replace(
        t,
        """                        // Only pump-reported delivery qualifies, including a partial delivery.
                        // A request, waiting note, or failed zero-dose attempt must not arm slow-rise.
                        if (result.bolusDelivered > 0.0)
                            preferences.put(LongNonKey.LastDelayedBolusDeliveredAt, dateUtil.now())
""",
        "",
        "DBW LastDelayedBolusDeliveredAt",
    )
    write(staging, p, t)

    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/WizardDialog.kt"
    t = read(staging, p)
    t = must_replace(t, "import app.aaps.core.objects.utils.StepCountSource\n", "", "WD import")
    t = must_replace(t, "    @Inject lateinit var stepCountSource: StepCountSource\n", "", "WD inject")
    t = must_replace(t, "bgInput: Double): Boolean? {", "bgInput: Double): Boolean {", "WD sig")
    t = must_replace(
        t,
        "WizardActivitySteps.stillMovingNow(stepCountSource, dateUtil.now()) ?: return null",
        "WizardActivitySteps.stillMovingNow(persistenceLayer, dateUtil.now())",
        "WD moving",
    )
    t = must_replace(
        t,
        "computeWalkingSoonDefault(carbs, protein, fat, bgInput) ?: return\n",
        "computeWalkingSoonDefault(carbs, protein, fat, bgInput)\n",
        "WD want",
    )
    write(staging, p, t)


def apply_surgical(staging: Path) -> None:
    # BooleanKey
    p = "core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    WizardCorrectionPercent("wizard_correction_percent", defaultValue = false),\n    WizardIncludeCob("wizard_include_cob", defaultValue = false),',
        '    WizardCorrectionPercent("wizard_correction_percent", defaultValue = false),\n'
        '    // Delayed bolus (50%-profile wizard mechanism). Constant renamed from WizardSplitBolusEnabled;\n'
        '    // the stored key string is kept so existing users\' setting survives the rename.\n'
        '    WizardDelayedBolusEnabled("wizard_split_bolus_enabled", defaultValue = false),\n'
        '    WizardIncludeCob("wizard_include_cob", defaultValue = false),',
        "BooleanKey",
    )
    # Separate feature from WizardDelayedBolusEnabled above: "Split bolus when over max" (wizard
    # split-every-N-min row), used by WizardDialog.kt's own split-bolus-when-over-max logic. Missing
    # from an earlier generation of this script even though the FULL_COPY'd WizardDialog.kt already
    # referenced it -- same class of bug as WizardActivitySteps.kt being absent from FULL_COPY.
    t = must_replace(
        t,
        '    ApsUseAutoIsfWeights("openapsama_enable_autoISF", false, defaultedBySM = true),\n',
        '    ApsUseAutoIsfWeights("openapsama_enable_autoISF", false, defaultedBySM = true),\n'
        '    ApsAutoIsfSplitBolusEnabled("split_bolus_enabled", false, defaultedBySM = true),\n',
        "BooleanKey ApsAutoIsfSplitBolusEnabled",
    )
    write(staging, p, t)

    # IntKey -- ApsAutoIsfSplitBolusInterval, depended on by ApsAutoIsfSplitBolusEnabled above.
    # This file was never touched by an earlier generation of this script at all.
    p = "core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    ApsAutoIsfIobThPercent("iob_threshold_percent", 100, 10, 100, defaultedBySM = true),\n',
        '    ApsAutoIsfIobThPercent("iob_threshold_percent", 100, 10, 100, defaultedBySM = true),\n'
        '    ApsAutoIsfSplitBolusInterval("split_bolus_interval", 7, 1, 10, defaultedBySM = true, dependency = BooleanKey.ApsAutoIsfSplitBolusEnabled),\n',
        "IntKey ApsAutoIsfSplitBolusInterval",
    )
    write(staging, p, t)

    # LongKey
    p = "core/keys/src/main/kotlin/app/aaps/core/keys/LongKey.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    AppStart("app_start_time", 0, defaultedBySM = true),\n',
        '    AppStart("app_start_time", 0, defaultedBySM = true),\n'
        '    DelayedBolusBlockSmbUntil("delayed_bolus_block_smb_until", 0, defaultedBySM = true),\n'
        '    SplitBolusBlockSmbUntil("split_bolus_block_smb_until", 0, defaultedBySM = true),\n'
        '    ApsAutoIsfLastCycleInsulinReqMilliU("autoisf_last_cycle_insulin_req_milliu", 0, defaultedBySM = true),\n'
        '    ApsAutoIsfPendingSplitRemainingMilliU("autoisf_pending_split_remaining_milliu", 0, defaultedBySM = true),\n'
        '    ApsAutoIsfPendingWarsawRemainingMilliU("autoisf_pending_warsaw_remaining_milliu", 0, defaultedBySM = true),\n'
        '    ApsAutoIsfLastCycleHp1MilliMmol("autoisf_last_cycle_hp1_milli_mmol", 0, defaultedBySM = true),\n',
        "LongKey",
    )
    write(staging, p, t)

    # CoreModule
    p = "core/objects/src/main/kotlin/app/aaps/core/objects/di/CoreModule.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.objects.wizard.QuickWizardEntry\n",
        "import app.aaps.core.objects.wizard.QuickWizardEntry\nimport app.aaps.core.objects.wizard.DelayedBolusWorker\n",
        "CoreModule import",
    )
    t = must_replace(
        t,
        "        @ContributesAndroidInjector fun quickWizardEntryInjector(): QuickWizardEntry\n",
        "        @ContributesAndroidInjector fun quickWizardEntryInjector(): QuickWizardEntry\n"
        "        @ContributesAndroidInjector fun delayedBolusWorkerInjector(): DelayedBolusWorker\n",
        "CoreModule injector",
    )
    write(staging, p, t)

    # BolusProgressData
    p = "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/BolusProgressData.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "        stopPressed = false\n        status = \"\"",
        "        stopPressed = false\n        followUpBolusCancelled = false\n        status = \"\"",
        "BolusProgressData set()",
    )
    t = must_replace(
        t,
        "    var stopPressed = false\n}",
        "    var stopPressed = false\n\n"
        "    /**\n"
        "     * Set when stop is pressed — prevents any scheduled follow-up doses from firing.\n"
        "     * Cleared when a new bolus starts via set(). See ScheduledDoseSupersession for the\n"
        "     * separate mechanism that detects a newer bolus/carbs entry.\n"
        "     */\n"
        "    var followUpBolusCancelled = false\n}",
        "BolusProgressData field",
    )
    write(staging, p, t)

    # QuickWizardEntry
    p = "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/QuickWizardEntry.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            quickWizard = true,\n            positiveIOBOnly = uPositiveIOBOnly\n        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard\n    }",
        "            quickWizard = true,\n            positiveIOBOnly = uPositiveIOBOnly,\n"
        "            // Fixed per-button protein/fat (added 2026-09-16). Unlike split-bolus below, these ARE real\n"
        "            // doCalc() parameters -- doCalc() uses them internally to compute insulinFromProteinOnly/\n"
        "            // insulinFromFatOnly (protein*0.4/ic, fat*0.9/ic) itself, so they must go in here, not be set\n"
        "            // on the returned wizard afterward (that would be too late -- those two fields are already\n"
        "            // computed by the time doCalc() returns). Previously QuickWizard had no way to supply these\n"
        "            // at all, so the Warsaw-FPU extended series could never engage for a QuickWizard-triggered\n"
        "            // bolus regardless of BolusWizard's own logic.\n"
        "            protein = protein(),\n"
        "            fat = fat(),\n"
        "            // \"Always on\" is live S30 (S5 OR watch only), not a hard 50%. Seated press uses standing wiz%.\n"
        "            walkingSoon = useWalkingSoon() == YES &&\n"
        "                WizardActivitySteps.stillMovingNow(persistenceLayer, dateUtil.now())\n"
        "        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard\n"
        "        wizard.manualSplitBolusEnabled = useSplitBolus() == YES\n"
        "        wizard.manualSplitBolusIntervalMins = splitBolusIntervalMins()\n"
        "        // Protein+fat extended-series duration (2026-09-16): a direct per-button setting, same\n"
        "        // reasoning/timing as split-bolus above -- warsawFpuPlan() only runs later, on demand, so this\n"
        "        // is safe to set post-doCalc() (unlike protein/fat themselves further up).\n"
        "        wizard.warsawDurationHours = warsawDurationHours()\n"
        "        return wizard\n    }",
        "QuickWizardEntry doCalc",
    )
    t = must_replace(
        t,
        "        val percentage = if (usePercentage() == DEFAULT) preferences.get(IntKey.OverviewBolusPercentage) else percentage()\n        return bolusWizardProvider.get().doCalc(",
        "        val percentage = if (usePercentage() == DEFAULT) preferences.get(IntKey.OverviewBolusPercentage) else percentage()\n        val wizard = bolusWizardProvider.get().doCalc(",
        "QuickWizardEntry val wizard",
    )
    # safeGetDouble import for warsawDurationHours() below (added 2026-09-16)
    t = must_replace(
        t,
        "import app.aaps.core.utils.JsonHelper.safeGetInt\n",
        "import app.aaps.core.utils.JsonHelper.safeGetDouble\nimport app.aaps.core.utils.JsonHelper.safeGetInt\n",
        "QuickWizardEntry safeGetDouble import",
    )
    # Fixed per-button protein/fat/warsawDurationHours getters (added 2026-09-16) -- see doCalc()'s own
    # comment above for why protein/fat are real doCalc() params while warsawDurationHours is set post-calc.
    t = must_replace(
        t,
        '    fun carbs(): Int = safeGetInt(storage, "carbs")\n',
        '    fun carbs(): Int = safeGetInt(storage, "carbs")\n\n'
        '    fun protein(): Int = safeGetInt(storage, "protein")\n\n'
        '    fun fat(): Int = safeGetInt(storage, "fat")\n\n'
        '    fun warsawDurationHours(): Double = safeGetDouble(storage, "warsawDurationHours", 5.0)\n',
        "QuickWizardEntry protein/fat/warsawDurationHours getters",
    )
    t = must_replace(
        t,
        '    fun useAlarm(): Int = safeGetInt(storage, "useAlarm", NO)\n}',
        '    fun useAlarm(): Int = safeGetInt(storage, "useAlarm", NO)\n\n'
        '    fun useWalkingSoon(): Int = safeGetInt(storage, "useWalkingSoon", NO)\n\n'
        '    fun useSplitBolus(): Int = safeGetInt(storage, "useSplitBolus", NO)\n\n'
        '    fun splitBolusIntervalMins(): Int = safeGetInt(storage, "splitBolusIntervalMins", 7)\n}',
        "QuickWizardEntry getters",
    )
    write(staging, p, t)

    # OverviewPlugin
    p = "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewPlugin.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusAdvisor, summary = R.string.enable_bolus_advisor_summary, title = R.string.enable_bolus_advisor))\n            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusReminder, summary = R.string.enablebolusreminder_summary, title = R.string.enablebolusreminder))",
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusAdvisor, summary = R.string.enable_bolus_advisor_summary, title = R.string.enable_bolus_advisor))\n"
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.WizardDelayedBolusEnabled, summary = R.string.wizard_split_bolus_summary, title = R.string.wizard_split_bolus_title))\n"
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfSplitBolusEnabled, summary = R.string.split_bolus_enabled_summary, title = R.string.split_bolus_enabled_title))\n"
        "            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfSplitBolusInterval, dialogMessage = R.string.split_bolus_interval_summary, title = R.string.split_bolus_interval_title))\n"
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusReminder, summary = R.string.enablebolusreminder_summary, title = R.string.enablebolusreminder))",
        "OverviewPlugin pref",
    )
    write(staging, p, t)

    # strings
    p = "plugins/main/src/main/res/values/strings.xml"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    <string name="enable_bolus_advisor">Enable bolus advisor</string>\n',
        '    <string name="quick_wizard_max_bolus_title">Quick Wizard bolus limit</string>\n'
        '    <string name="quick_wizard_max_bolus_this_bolus">Max bolus (this bolus only)</string>\n'
        '    <string name="quick_wizard_max_bolus_not_saved">This temporary limit is restored after this Quick Wizard attempt.</string>\n'
        '    <string name="quick_wizard_max_bolus_summary">Calculated: %1$.2f U\\nAllowed now: %2$.2f U</string>\n'
        '    <string name="wizard_split_bolus_title">Enable delayed bolus</string>\n'
        '    <string name="split_bolus_enabled_title">Split bolus when over max</string>\n'
        '    <string name="split_bolus_enabled_summary">When profile is 100% and the wizard result exceeds max bolus, show the split-every-N-min controls.</string>\n'
        '    <string name="split_bolus_interval_title">Split bolus interval (min)</string>\n'
        '    <string name="split_bolus_interval_summary">Default minutes between split parts (1–10). The wizard can still change this per bolus.</string>\n'
        '    <string name="wizard_split_bolus_summary">When profile is 50%: deliver the remaining gap ×90% at +10/20/30 min once BGL rising criteria are met. SMBs are blocked during the check window.</string>\n'
        '    <string name="enable_bolus_advisor">Enable bolus advisor</string>\n',
        "strings.xml",
    )
    write(staging, p, t)

    # OpenAPSAutoISFPlugin SMB block
    p = "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100\n        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()",
        "        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100\n"
        "        val delayedBolusBlockUntil = preferences.get(LongKey.DelayedBolusBlockSmbUntil)\n"
        "        val microBolusAllowed = if (delayedBolusBlockUntil > dateUtil.now()) {\n"
        "            inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, \"Delayed bolus active — SMBs blocked until ${dateUtil.timeString(delayedBolusBlockUntil)}\", this) })\n"
        "            false\n"
        "        } else {\n"
        "            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()\n"
        "        }",
        "OpenAPSAutoISFPlugin microBolusAllowed",
    )
    # Mirrors RT.insulinReq into a preference every cycle so DelayedBolusWorker (a lower module
    # that can't reach into this plugin directly) can cap its delayed dose against the loop's
    # CURRENT insulin requirement instead of only the wizard-time fullRequired estimate. See
    # DelayedBolusWorker's own doc comment (2026-09-15 follow-up) for the full reasoning.
    t = must_replace(
        t,
        "            lastAPSResult = determineBasalResult\n            lastAPSRun = now\n",
        "            lastAPSResult = determineBasalResult\n"
        "            lastAPSRun = now\n"
        "            preferences.put(LongKey.ApsAutoIsfLastCycleInsulinReqMilliU, Math.round((it.insulinReq ?: 0.0) * 1000))\n",
        "OpenAPSAutoISFPlugin insulinReq mirror",
    )
    # NOTE (2026-09-30): NOT adding a surgical hp1-cache mirror here, unlike the insulinReq one above --
    # hypoPrediction1Mmol() is a fork-only function (confirmed absent from a clean tobias/3.4.2.6+aisf3.2.1
    # checkout) that only exists once this repo's full coded-automations layer is also present, which this
    # patch does not carry. ApsAutoIsfLastCycleHp1MilliMmol (added to LongKey above) simply stays at its
    # default 0 on a clean base -- WizardDropTrendCaution.applies() already treats that as hp1Mmol=null and
    # just falls back to its BGL<6 / IOB-vs-rise checks, so this degrades safely rather than failing to compile.
    write(staging, p, t)

    # OverviewFragment
    p = "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.objects.wizard.QuickWizard\n",
        "import app.aaps.core.objects.wizard.BolusWizard\n"
        "import app.aaps.core.objects.wizard.QuickWizard\n"
        "import app.aaps.core.objects.wizard.QuickWizardEntry\n",
        "OverviewFragment wizard imports",
    )
    t = must_replace(
        t,
        "import app.aaps.plugins.main.databinding.OverviewFragmentBinding\n",
        "import app.aaps.plugins.main.databinding.DialogQuickWizardMaxBolusBinding\n"
        "import app.aaps.plugins.main.databinding.OverviewFragmentBinding\n",
        "OverviewFragment binding import",
    )
    t = must_replace(
        t,
        "    private var _binding: OverviewFragmentBinding? = null\n",
        "    private var _binding: OverviewFragmentBinding? = null\n"
        "    private var quickWizardMaxBolusDialog: androidx.appcompat.app.AlertDialog? = null\n"
        "    private var quickWizardOriginalSafetyMaxBolus: Double? = null\n",
        "OverviewFragment fields",
    )
    t = must_replace(
        t,
        "    override fun onDestroy() {\n        super.onDestroy()\n        handler.removeCallbacksAndMessages(null)\n        handler.looper.quitSafely()\n    }",
        "    override fun onDestroy() {\n"
        "        super.onDestroy()\n"
        "        quickWizardMaxBolusDialog?.dismiss()\n"
        "        quickWizardMaxBolusDialog = null\n"
        "        restoreQuickWizardSafetyMaxBolus()\n"
        "        handler.removeCallbacksAndMessages(null)\n"
        "        handler.looper.quitSafely()\n    }",
        "OverviewFragment onDestroy",
    )
    t = must_replace(
        t,
        """    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val pump = activePlugin.activePump
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints) || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    wizard.confirmAndExecute(it, quickWizardEntry)
                }
            }
        }
    }
""",
        """    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    val maxBolusAllowed = constraintChecker.getMaxBolusAllowed().value()
                    if (wizard.calculatedTotalInsulin > maxBolusAllowed && maxBolusAllowed > 0.0) {
                        showQuickWizardMaxBolusDialog(it, quickWizardEntry)
                    } else {
                        wizard.confirmAndExecute(it, quickWizardEntry)
                    }
                }
            }
        }
    }

    private fun showQuickWizardMaxBolusDialog(
        activity: androidx.fragment.app.FragmentActivity,
        quickWizardEntry: QuickWizardEntry
    ) {
        quickWizardMaxBolusDialog?.dismiss()
        restoreQuickWizardSafetyMaxBolus()

        val originalMaxBolus = preferences.get(DoubleKey.SafetyMaxBolus)
        quickWizardOriginalSafetyMaxBolus = originalMaxBolus
        val dialogBinding = DialogQuickWizardMaxBolusBinding.inflate(layoutInflater)
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep.takeIf { it > 0.0 } ?: 0.05

        fun recalculateAndShowSummary(): BolusWizard? {
            val currentProfile = profileFunction.getProfile() ?: return null
            val currentBg = iobCobCalculator.ads.actualBg() ?: return null
            val recalculated = quickWizardEntry.doCalc(currentProfile, profileFunction.getProfileName(), currentBg)
            dialogBinding.summary.text = rh.gs(
                R.string.quick_wizard_max_bolus_summary,
                recalculated.calculatedTotalInsulin,
                recalculated.insulinAfterConstraints
            )
            return recalculated
        }

        dialogBinding.maxBolusInput.setParams(
            originalMaxBolus,
            0.1,
            60.0,
            bolusStep,
            decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
            false,
            null
        )
        dialogBinding.maxBolusInput.setOnValueChangedListener {
            preferences.put(DoubleKey.SafetyMaxBolus, dialogBinding.maxBolusInput.value)
            recalculateAndShowSummary()
        }
        recalculateAndShowSummary()

        quickWizardMaxBolusDialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(rh.gs(R.string.quick_wizard_max_bolus_title))
            .setView(dialogBinding.root)
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .setPositiveButton(app.aaps.core.ui.R.string.ok) { _, _ ->
                recalculateAndShowSummary()?.confirmAndExecute(activity, quickWizardEntry)
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    restoreQuickWizardSafetyMaxBolus()
                    quickWizardMaxBolusDialog = null
                }
                dialog.show()
            }
    }

    private fun restoreQuickWizardSafetyMaxBolus() {
        quickWizardOriginalSafetyMaxBolus?.let { preferences.put(DoubleKey.SafetyMaxBolus, it) }
        quickWizardOriginalSafetyMaxBolus = null
    }
""",
        "OverviewFragment onClickQuickWizard",
    )
    t = must_replace(
        t,
        "wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0",
        "wizard.calculatedTotalInsulin > 0.0 || quickWizardEntry.carbs() > 0.0",
        "QuickWizard carbs-only execution",
    )
    t = must_replace(
        t,
        "if (wizard.calculatedTotalInsulin <= 0) binding.buttonsLayout.quickWizardButton.visibility",
        "if (wizard.calculatedTotalInsulin <= 0 && quickWizardEntry.carbs() <= 0) binding.buttonsLayout.quickWizardButton.visibility",
        "QuickWizard carbs-only visibility",
    )
    write(staging, p, t)

    # EditQuickWizardDialog.kt
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/EditQuickWizardDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '                    entry.storage.put("carbs2", carbs2)\n                } catch (e: JSONException) {',
        '                    entry.storage.put("carbs2", carbs2)\n'
        '                    entry.storage.put("useWalkingSoon", checkBoxToRadioNumbers(binding.walkingSoonCheckbox.isChecked))\n'
        '                    entry.storage.put("useSplitBolus", checkBoxToRadioNumbers(binding.splitBolusCheckbox.isChecked))\n'
        '                    entry.storage.put("splitBolusIntervalMins", binding.splitBolusIntervalInput.value.toInt())\n'
        '                    entry.storage.put("protein", binding.proteinInput.value.toInt())\n'
        '                    entry.storage.put("fat", binding.fatInput.value.toInt())\n'
        '                    entry.storage.put("warsawDurationHours", binding.warsawDurationInput.value)\n'
        "                } catch (e: JSONException) {",
        "EditQuickWizardDialog save",
    )
    t = must_replace(
        t,
        """        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -60.0, 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )

        binding.correctionInput.value = entry.percentage().toDouble()
""",
        """        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -60.0, 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )

        binding.splitBolusIntervalInput.setParams(
            savedInstanceState?.getDouble("split_bolus_interval_input")
                ?: 7.0, 1.0, 60.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.proteinInput.setParams(
            savedInstanceState?.getDouble("protein_input")
                ?: 0.0, 0.0, 200.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.fatInput.setParams(
            savedInstanceState?.getDouble("fat_input")
                ?: 0.0, 0.0, 200.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.warsawDurationInput.setParams(
            savedInstanceState?.getDouble("warsaw_duration_input")
                ?: 5.0, 0.0, 24.0, 0.5, DecimalFormat("0.0"), false, binding.okcancel.ok, textWatcher
        )

        binding.correctionInput.value = entry.percentage().toDouble()
""",
        "EditQuickWizardDialog setParams",
    )
    t = must_replace(
        t,
        "        useECarbs(radioNumbersToCheckBox(entry.useEcarbs()))\n\n        binding.useCob.setOnCheckedChangeListener { _, _ -> processCob() }",
        "        useECarbs(radioNumbersToCheckBox(entry.useEcarbs()))\n\n"
        "        binding.walkingSoonCheckbox.isChecked = radioNumbersToCheckBox(entry.useWalkingSoon())\n"
        "        binding.splitBolusCheckbox.isChecked = radioNumbersToCheckBox(entry.useSplitBolus())\n"
        "        binding.splitBolusIntervalInput.value = SafeParse.stringToDouble(entry.splitBolusIntervalMins().toString())\n"
        "        processSplitBolus()\n"
        "        binding.splitBolusCheckbox.setOnCheckedChangeListener { _, _ -> processSplitBolus() }\n\n"
        "        binding.proteinInput.value = SafeParse.stringToDouble(entry.protein().toString())\n"
        "        binding.fatInput.value = SafeParse.stringToDouble(entry.fat().toString())\n"
        "        binding.warsawDurationInput.value = entry.warsawDurationHours()\n\n"
        "        binding.useCob.setOnCheckedChangeListener { _, _ -> processCob() }",
        "EditQuickWizardDialog load",
    )
    t = must_replace(
        t,
        "    override fun onClick(v: View?) {\n        //\n    }",
        """    private fun processSplitBolus() {
        val visibility = if (binding.splitBolusCheckbox.isChecked) View.VISIBLE else View.GONE
        binding.splitBolusIntervalInput.visibility = visibility
        binding.splitBolusIntervalUnit.visibility = visibility
    }

    override fun onClick(v: View?) {
        //
    }""",
        "EditQuickWizardDialog processSplitBolus",
    )
    write(staging, p, t)

    # dialog_edit_quickwizard.xml
    p = "ui/src/main/res/layout/dialog_edit_quickwizard.xml"
    t = read(BASE, p)
    t = must_replace(
        t,
        """            <CheckBox
                android:id="@+id/use_ecarbs"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="@string/additional_ecarbs"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium" />

        </LinearLayout>
""",
        """            <CheckBox
                android:id="@+id/use_ecarbs"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="@string/additional_ecarbs"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium" />

            <CheckBox
                android:id="@+id/walking_soon_checkbox"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="Walking soon (50% now, rest later if needed)"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium"
                tools:ignore="HardcodedText" />

            <LinearLayout
                android:id="@+id/split_bolus_row"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:orientation="horizontal">

                <CheckBox
                    android:id="@+id/split_bolus_checkbox"
                    style="@style/Widget.App.CheckBox"
                    android:layout_width="wrap_content"
                    android:layout_height="match_parent"
                    android:gravity="start|center_vertical"
                    android:lines="1"
                    android:text="Split bolus every"
                    android:textAppearance="@style/TextAppearance.AppCompat.Medium"
                    tools:ignore="HardcodedText" />

                <app.aaps.core.ui.elements.NumberPicker
                    android:id="@+id/split_bolus_interval_input"
                    android:layout_width="100dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp" />

                <TextView
                    android:id="@+id/split_bolus_interval_unit"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:text="@string/unit_minute_short"
                    android:textAppearance="?android:attr/textAppearanceSmall" />

            </LinearLayout>

            <LinearLayout
                android:id="@+id/protein_fat_row"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:labelFor="@id/protein_input"
                    android:text="Protein"
                    android:textAppearance="?android:attr/textAppearanceSmall"
                    tools:ignore="HardcodedText" />

                <app.aaps.core.ui.elements.NumberPicker
                    android:id="@+id/protein_input"
                    android:layout_width="100dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:text="@string/shortgramm"
                    android:textAppearance="?android:attr/textAppearanceSmall" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:layout_marginStart="12dp"
                    android:labelFor="@id/fat_input"
                    android:text="Fat"
                    android:textAppearance="?android:attr/textAppearanceSmall"
                    tools:ignore="HardcodedText" />

                <app.aaps.core.ui.elements.NumberPicker
                    android:id="@+id/fat_input"
                    android:layout_width="100dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:text="@string/shortgramm"
                    android:textAppearance="?android:attr/textAppearanceSmall" />

            </LinearLayout>

            <LinearLayout
                android:id="@+id/warsaw_duration_row"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:labelFor="@id/warsaw_duration_input"
                    android:text="Protein+Fat duration"
                    android:textAppearance="?android:attr/textAppearanceSmall"
                    tools:ignore="HardcodedText" />

                <app.aaps.core.ui.elements.NumberPicker
                    android:id="@+id/warsaw_duration_input"
                    android:layout_width="100dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:text="@string/unit_hour_short"
                    android:textAppearance="?android:attr/textAppearanceSmall" />

            </LinearLayout>

            <TextView
                android:id="@+id/warsaw_duration_warning"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:paddingTop="2dp"
                android:paddingBottom="4dp"
                android:text="Caps how many fixed hourly doses fire (only above 3 FPU) — insulin for hours beyond this cap is not delivered. Total given is reduced, not made up later."
                android:textAppearance="?android:attr/textAppearanceSmall"
                android:textColor="?attr/warningColor"
                tools:ignore="HardcodedText" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:paddingBottom="4dp"
                android:text="Pending delayed doses (this and carb-split) are lost with no note if AAPS restarts or updates before they fire."
                android:textAppearance="?android:attr/textAppearanceSmall"
                android:textColor="?attr/warningColor"
                tools:ignore="HardcodedText" />

        </LinearLayout>
""",
        "dialog_edit_quickwizard.xml",
    )
    write(staging, p, t)

    # ui strings.xml -- unit_hour_short, used by the new warsaw_duration_row/input unit labels in
    # dialog_wizard.xml (FULL_COPY) and dialog_edit_quickwizard.xml above. Added 2026-09-16.
    p = "ui/src/main/res/values/strings.xml"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    <string name="unit_minute_short">min</string>\n',
        '    <string name="unit_minute_short">min</string>\n'
        '    <string name="unit_hour_short">h</string>\n',
        "ui strings.xml unit_hour_short",
    )
    write(staging, p, t)

    # BolusProgressDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/BolusProgressDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            BolusProgressData.stopPressed = true\n            binding.stopPressed.visibility = View.VISIBLE",
        "            BolusProgressData.stopPressed = true\n            BolusProgressData.followUpBolusCancelled = true\n            binding.stopPressed.visibility = View.VISIBLE",
        "BolusProgressDialog stop",
    )
    write(staging, p, t)

    # InsulinDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/InsulinDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "InsulinDialog import",
    )
    t = must_replace(
        t,
        """                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                        } else {""",
        """                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                            ScheduledDoseSupersession.bump()
                        } else {""",
        "InsulinDialog record-only bump",
    )
    t = must_replace(
        t,
        """                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                    }""",
        """                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                        ScheduledDoseSupersession.bump()
                                    }""",
        "InsulinDialog delivery bump",
    )
    write(staging, p, t)

    # CarbsDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/CarbsDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "CarbsDialog import",
    )
    t = must_replace(
        t,
        """                                } else if (preferences.get(BooleanKey.OverviewUseBolusReminder) && remindBolus)
                                    automation.scheduleAutomationEventBolusReminder()""",
        """                                } else {
                                    if (preferences.get(BooleanKey.OverviewUseBolusReminder) && remindBolus)
                                        automation.scheduleAutomationEventBolusReminder()
                                    ScheduledDoseSupersession.bump()
                                }""",
        "CarbsDialog bump",
    )
    write(staging, p, t)

    # TreatmentDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/TreatmentDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "TreatmentDialog import",
    )
    t = must_replace(
        t,
        """                            ).subscribe()
                    } else {
                        if (detailedBolusInfo.insulin > 0) {""",
        """                            ).subscribe()
                        if (detailedBolusInfo.insulin > 0 || detailedBolusInfo.carbs > 0) ScheduledDoseSupersession.bump()
                    } else {
                        if (detailedBolusInfo.insulin > 0) {""",
        "TreatmentDialog record bump",
    )
    t = must_replace(
        t,
        """                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    }
                                }
                            })
                        } else {
                            if (detailedBolusInfo.carbs > 0)
                                disposable += persistenceLayer.insertOrUpdateCarbs(
                                    detailedBolusInfo.createCarbs(),
                                    action = action,
                                    source = Sources.TreatmentDialog
                                ).subscribe()
                        }""",
        """                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    } else {
                                        ScheduledDoseSupersession.bump()
                                    }
                                }
                            })
                        } else {
                            if (detailedBolusInfo.carbs > 0) {
                                disposable += persistenceLayer.insertOrUpdateCarbs(
                                    detailedBolusInfo.createCarbs(),
                                    action = action,
                                    source = Sources.TreatmentDialog
                                ).subscribe()
                                ScheduledDoseSupersession.bump()
                            }
                        }""",
        "TreatmentDialog delivery bump",
    )
    write(staging, p, t)

    # TreatmentsBolusCarbsFragment
    p = "ui/src/main/kotlin/app/aaps/ui/activities/fragments/TreatmentsBolusCarbsFragment.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.db.PersistenceLayer\n",
        "import app.aaps.core.interfaces.db.PersistenceLayer\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "TreatmentsBolusCarbsFragment import",
    )
    t = must_replace(
        t,
        """            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
                selectedItems.forEach { _, ml ->""",
        """            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
                var removingBolusOrCarbs = false
                selectedItems.forEach { _, ml -> if (ml.bolus != null || ml.carbs != null) removingBolusOrCarbs = true }
                if (removingBolusOrCarbs) ScheduledDoseSupersession.bump()
                selectedItems.forEach { _, ml ->""",
        "TreatmentsBolusCarbsFragment bump",
    )
    write(staging, p, t)


def git_diff(old: Path, new: Path, relpath: str, is_new: bool) -> str:
    empty = None
    try:
        if is_new:
            empty = tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", newline="\n")
            empty.write("")
            empty.close()
            old_arg = empty.name
        else:
            old_arg = str(old)
        r = subprocess.run(
            ["git", "diff", "--no-index", "--", old_arg, str(new)],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        text = r.stdout
        if not text.strip():
            if r.returncode not in (0, 1):
                raise SystemExit(f"git diff failed for {relpath}: {r.stderr}")
            return ""
        text = text.replace("\r\n", "\n")
        # Rewrite path headers to repo-relative a/ b/ form.
        lines = text.splitlines(keepends=True)
        out = []
        inserted_mode = False
        for line in lines:
            if line.startswith("diff --git "):
                out.append(f"diff --git a/{relpath} b/{relpath}\n")
                if is_new and not inserted_mode:
                    out.append("new file mode 100644\n")
                    inserted_mode = True
            elif line.startswith("index ") and is_new:
                out.append("index 0000000000..1111111111\n")
            elif line.startswith("--- "):
                out.append("--- /dev/null\n" if is_new else f"--- a/{relpath}\n")
            elif line.startswith("+++ "):
                out.append(f"+++ b/{relpath}\n")
            else:
                out.append(line)
        if not out[-1].endswith("\n"):
            out.append("\n")
        return "".join(out)
    finally:
        if empty is not None:
            os.unlink(empty.name)


def collect_relpaths(staging: Path) -> list[str]:
    files = []
    for p in staging.rglob("*"):
        if p.is_file():
            files.append(p.relative_to(staging).as_posix())
    return sorted(files)


def main() -> None:
    if not BASE.exists():
        raise SystemExit(f"base clone missing: {BASE}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        old = tmp_path / "old"
        new = tmp_path / "new"
        old.mkdir()
        new.mkdir()

        surgical = [
            "core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt",
            "core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt",
            "core/keys/src/main/kotlin/app/aaps/core/keys/LongKey.kt",
            "core/objects/src/main/kotlin/app/aaps/core/objects/di/CoreModule.kt",
            "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/BolusProgressData.kt",
            "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/QuickWizardEntry.kt",
            "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewPlugin.kt",
            "plugins/main/src/main/res/values/strings.xml",
            "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt",
            "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/EditQuickWizardDialog.kt",
            "ui/src/main/res/layout/dialog_edit_quickwizard.xml",
            "ui/src/main/res/values/strings.xml",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/BolusProgressDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/InsulinDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/CarbsDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/TreatmentDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/activities/fragments/TreatmentsBolusCarbsFragment.kt",
        ]

        for p in surgical:
            copy_base(old, p)
        apply_surgical(new)
        # apply_surgical wrote into `new`; also seed `new` from BASE then apply? It wrote from BASE text.
        # Full copies: old only if exists in BASE
        for p in FULL_COPY:
            src = BASE / rel(p)
            if src.exists():
                copy_base(old, p)
            copy_ours(new, p)

        revert_steps_source(new)

        # Strip unused IntKey import from BolusWizard in the patch payload
        bw = new / rel("core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt")
        bw.write_text(
            bw.read_text(encoding="utf-8").replace("import app.aaps.core.keys.IntKey\n", ""),
            encoding="utf-8",
            newline="\n",
        )

        relpaths = sorted(set(collect_relpaths(old)) | set(collect_relpaths(new)))
        chunks = []
        for relpath in relpaths:
            old_f = old / Path(relpath)
            new_f = new / Path(relpath)
            is_new = not old_f.exists()
            if not new_f.exists():
                raise SystemExit(f"missing new file {relpath}")
            chunk = git_diff(old_f if old_f.exists() else new_f, new_f, relpath, is_new)
            if chunk:
                chunks.append(chunk)
        if not chunks:
            raise SystemExit("empty patch")
        header = PATCH_DESCRIPTION.rstrip("\n") + "\n\n"
        OUT.write_text(header + "".join(chunks), encoding="utf-8", newline="\n")
        print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(chunks)} files)")
        for relpath in relpaths:
            print(" ", relpath)


if __name__ == "__main__":
    main()
