package app.aaps.core.objects.wizard

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

/**
 * Extra caution for one wizard calculation. Either path is enough.
 *
 * Path 1 is a fast fall: delta at or below -0.15 mmol and short delta at or below -0.10 mmol, with glucose under
 * 6.0 mmol or the loop HP1 under 5.0 mmol. 2026-10-22, per explicit request: the long delta is no longer needed, and
 * path 1 has no 5.5 mmol ceiling (as on 3426). The 22 Oct hypo: a 2.5 U meal bolus at 7.2 mmol with 3.0 U on board,
 * delta -0.31, short delta -0.25, HP1 4.0, but long delta only -0.02 after 15 minutes of fall.
 * Path 2 is insulin on board above 3.0 units for each mmol of rise from the lowest glucose in the last hour, only
 * under 5.5 mmol. A rise under 0.1 mmol counts as 0.1. The 3.0 figure is from one episode and is not proven for a full
 * meal dose.
 *
 * 2026-11-14, per explicit request: three more paths, for when the loop would soon switch to the 50% profile but has not
 * yet (a meal's carbs stop prepare Set50% firing, so it may never switch). Their limits are the loop's own:
 * Path 3 is prepare Set50% block 3: glucose under 5.0 mmol and delta at or below -0.05 mmol.
 * Path 4 is prepare Set50% block 4: 21:00 to midnight, glucose at or under 7.0 mmol, delta at or below -0.20 and short
 * delta at or below -0.10 mmol.
 * Path 5 is ActivityProf50%: the 6.8 mmol activity temp target running (15 minutes or longer, so not a short
 * post-bolus one), glucose at or under 8.5 mmol and delta at or below +0.10 mmol.
 *
 * Both the max bolus ([scaledMaxBolus], for this calculation and its commit) and the wizard percent are halved. The
 * fat and protein plan is left as it is. Later doses are left to their own checks.
 */
object WizardDropTrendCaution {

    const val SCALE = 0.5
    /** 5.5 mmol/L. At this value and above, path 2 (insulin high for the rise) stays off. */
    const val HALF_ONLY_BELOW_MGDL = 99.1
    const val BGL_THRESHOLD_MGDL = 108.1
    const val HP1_THRESHOLD_MMOL = 5.0
    const val DELTA_THRESHOLD_MGDL = -2.7
    const val SDELTA_THRESHOLD_MGDL = -1.8
    const val IOB_PER_MMOL_RISE = 3.0
    private const val MMOL_TO_MGDL = 18.0182

    // Path 3 (prepare Set50% block 3)
    const val LOW_FALL_BG_MGDL = 90.1 // 5.0 mmol
    const val LOW_FALL_DELTA_MGDL = -0.90 // -0.05 mmol
    // Path 4 (prepare Set50% block 4)
    const val EVENING_START_MINUTE = 21 * 60
    const val EVENING_BG_MGDL = 126.1 // 7.0 mmol
    const val EVENING_DELTA_MGDL = -3.60 // -0.20 mmol
    const val EVENING_SDELTA_MGDL = -1.80 // -0.10 mmol
    // Path 5 (ActivityProf50%)
    const val ACTIVITY_BG_MGDL = 153.1 // 8.5 mmol
    const val ACTIVITY_DELTA_MGDL = 1.8 // +0.1 mmol
    const val ACTIVITY_TT_MIN_DURATION_MS = 15 * 60_000L

    /**
     * True when a temp target with [lowTargetMgdl] and [durationMs] is the 6.8 mmol/L activity target. 6.8 x 18.0 is the
     * value the old app stored; this app may store 6.8 x its own factor. A shorter one is a post-bolus target, not activity.
     */
    fun isActivityTempTarget(lowTargetMgdl: Double, durationMs: Long): Boolean =
        durationMs >= ACTIVITY_TT_MIN_DURATION_MS &&
            (abs(lowTargetMgdl - 6.8 * 18.0) <= 0.001 || abs(lowTargetMgdl - 6.8 * MMOL_TO_MGDL) <= 0.001)

    fun iobDisproportionateToRise(iobUnits: Double, bgRiseMgdl: Double): Boolean {
        val riseMmol = (bgRiseMgdl / MMOL_TO_MGDL).coerceAtLeast(0.1)
        return iobUnits > riseMmol * IOB_PER_MMOL_RISE
    }

    fun applies(
        delta: Double,
        shortAvgDelta: Double,
        bgMgdl: Double,
        hp1Mmol: Double?,
        iobUnits: Double,
        bgRiseMgdl: Double,
        /** Minutes since local midnight; null skips path 4. */
        minuteOfDay: Int? = null,
        activityTempTarget: Boolean = false,
    ): Boolean {
        val fastFall = delta <= DELTA_THRESHOLD_MGDL && shortAvgDelta <= SDELTA_THRESHOLD_MGDL
        val lowOrPredictedLow = bgMgdl < BGL_THRESHOLD_MGDL || (hp1Mmol != null && hp1Mmol < HP1_THRESHOLD_MMOL)
        val insulinHighForRise = bgMgdl < HALF_ONLY_BELOW_MGDL && iobDisproportionateToRise(iobUnits, bgRiseMgdl)
        val lowAndFalling = bgMgdl < LOW_FALL_BG_MGDL && delta <= LOW_FALL_DELTA_MGDL
        val eveningFall = minuteOfDay != null && minuteOfDay >= EVENING_START_MINUTE &&
            bgMgdl <= EVENING_BG_MGDL && delta <= EVENING_DELTA_MGDL && shortAvgDelta <= EVENING_SDELTA_MGDL
        val activity = activityTempTarget && bgMgdl <= ACTIVITY_BG_MGDL && delta <= ACTIVITY_DELTA_MGDL
        return (fastFall && lowOrPredictedLow) || insulinHighForRise || lowAndFalling || eveningFall || activity
    }

    /** Half of [base], rounded down to the pump step, at least one step and never above [base]. */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * SCALE / step + 1e-6) * step
        val rounded = round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }

    /** Half of [basePercent], at least 1. */
    fun scaledWizPercent(basePercent: Int): Int = (basePercent * SCALE).toInt().coerceAtLeast(1)
}
