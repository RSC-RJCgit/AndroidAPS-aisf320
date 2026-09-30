package app.aaps.core.objects.wizard

import kotlin.math.floor
import kotlin.math.min

/**
 * Downtrend caution rule for the Bolus Wizard dialog (added 2026-09-30, per explicit request, after a
 * real episode: BG peaked at a modest 5.8-5.9 mmol with IOB pushed to 4.21U by a manual bolus and ongoing
 * SMBs, then fell continuously for ~75 minutes down to 3.7 mmol -- right at the edge of real Alarm-hypo
 * range. So this rule is a separate, narrower caution layer from WizardRecentEntry (which only looks at
 * "was BG already low"): it looks at the CURRENT trend, a forward-looking signal, AND (see path 2 below)
 * how much IOB is already committed relative to how little BG has actually risen.
 *
 * TWO INDEPENDENT trigger paths (either alone is enough) -- added as two paths, not one AND condition,
 * specifically because at the real episode's actual peak (where the risky IOB buildup happened), live
 * Delta was still +0.22 -- deltas didn't turn negative until ~10 minutes later. A delta-only rule checked
 * at calc time would have missed the actual over-delivery; path 2 exists to catch that case directly.
 *
 * Path 1 (confirmed downtrend): ALL of
 *  - Delta <= -0.15 mmol, SDelta <= -0.10 mmol, AND LDelta <= -0.10 mmol (an ESTABLISHED three-way
 *    downtrend, not just barely negative -- calibrated against the real episode's own data: all three
 *    first went negative around minute 03:11 of that episode, barely (LDelta -0.01), but were solidly
 *    established by 03:30 (Delta -0.17, SDelta -0.13, LDelta -0.10 there) -- these thresholds are pinned
 *    to that 03:30 moment, not the earlier, thinner 03:11 crossing);
 *  - AND EITHER current BG < 6.0 mmol, OR the live HP1 (hypoPrediction1Mmol, cached by
 *    OpenAPSAutoISFPlugin every cycle into ApsAutoIsfLastCycleHp1MilliMmol -- see that LongKey's own doc
 *    comment) is < 5.0 mmol.
 *
 * Path 2 (IOB disproportionate to the actual rise): current total IOB (U) exceeds [IOB_PER_MMOL_RISE]
 * times however much BG has actually risen (mmol) from its own recent low (see recentBgRiseMgdl-style
 * lookback in WizardDialog). Real episode: BG rose only ~0.5 mmol (5.4->5.9) while IOB reached ~4.2U, a
 * ratio of ~8.4 U/mmol -- IOB_PER_MMOL_RISE=3.0 is a first-cut, deliberately not tightly calibrated
 * threshold (one anecdotal episode), picked to flag well before that ratio while still leaving real
 * headroom for an ordinary correction. Not yet checked against a case that needed full-strength dosing
 * and would have been wrongly capped by this -- watch for that before trusting the number. A rise floored
 * at 0.1 mmol avoids a division-by-tiny-number blowup when BG has barely moved off its recent low.
 *
 * When either path applies: both the max bolus in force AND wiz% (percentageCorrection) are scaled to 50%
 * for that one calculation -- max bolus the same visible-override + SafetyMaxBolus-write pattern
 * WizardRecentEntry/applyMaxBolusLowBgDefault already use; wiz% a calc-scoped-only override (no live field
 * in this dialog to show it, and no reason to touch the standing IntKey.OverviewBolusPercentage
 * preference for every future dose -- same reasoning as unreliableSmbsCheckbox's wiz%-90 override).
 *
 * Deliberately does NOT touch DelayedBolusWorker: that mechanism only ever delivers MORE later when its
 * own criteria (BG>5.0, Delta/SDelta/LDelta all RISING) are met -- the exact opposite of path 1's downtrend
 * trigger, and orthogonal to path 2. So this rule and DelayedBolusWorker work together without needing to
 * know about each other: this rule makes the immediate delivery more conservative; if BG genuinely reverses
 * and starts climbing later, DelayedBolusWorker's own rising-trend gate catches that independently and
 * tops up the difference then.
 */
object WizardDropTrendCaution {
    const val SCALE = 0.5
    const val BGL_THRESHOLD_MGDL = 108.1 // 6.0 mmol
    const val HP1_THRESHOLD_MMOL = 5.0
    const val DELTA_THRESHOLD_MGDL = -2.7 // -0.15 mmol
    const val SDELTA_THRESHOLD_MGDL = -1.8 // -0.10 mmol
    const val LDELTA_THRESHOLD_MGDL = -1.8 // -0.10 mmol
    const val IOB_PER_MMOL_RISE = 3.0
    private const val MMOL_TO_MGDL = 18.0182

    /** Path 2 -- see class doc comment. [bgRiseMgdl] may be <= 0 (BG flat or falling, not risen at all);
     *  floored at 0.1 mmol so that reads as "essentially no rise to justify this much IOB," not a divide-by-zero. */
    fun iobDisproportionateToRise(iobUnits: Double, bgRiseMgdl: Double): Boolean {
        val riseMmol = (bgRiseMgdl / MMOL_TO_MGDL).coerceAtLeast(0.1)
        return iobUnits > riseMmol * IOB_PER_MMOL_RISE
    }

    /** [hp1Mmol] null (no live HP1 cached yet, or the plugin hasn't run) never satisfies path 1's HP1 half of its OR. */
    fun applies(delta: Double, shortAvgDelta: Double, longAvgDelta: Double, bgMgdl: Double, hp1Mmol: Double?, iobUnits: Double, bgRiseMgdl: Double): Boolean {
        val allStronglyDropping = delta <= DELTA_THRESHOLD_MGDL && shortAvgDelta <= SDELTA_THRESHOLD_MGDL && longAvgDelta <= LDELTA_THRESHOLD_MGDL
        val lowOrPredictedLow = bgMgdl < BGL_THRESHOLD_MGDL || (hp1Mmol != null && hp1Mmol < HP1_THRESHOLD_MMOL)
        val path1 = allStronglyDropping && lowOrPredictedLow
        val path2 = iobDisproportionateToRise(iobUnits, bgRiseMgdl)
        return path1 || path2
    }

    /** [base] x0.5, rounded DOWN to the pump step (never below one step, never above [base]) -- same shape as WizardRecentEntry.scaledMaxBolus(). */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * SCALE / step + 1e-6) * step
        val rounded = Math.round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }

    /** Wiz% scaled to 50%, rounded to the nearest whole percent, floor 1. */
    fun scaledWizPercent(basePercent: Int): Int = (basePercent * SCALE).toInt().coerceAtLeast(1)
}
