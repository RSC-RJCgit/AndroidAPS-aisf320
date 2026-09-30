package app.aaps.core.objects.wizard

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

/**
 * Extra caution for one wizard calculation. Either path is enough.
 *
 * Path 1 is an established fall: delta at or below -0.15 mmol, short delta at or below -0.10 mmol,
 * and long delta at or below -0.10 mmol, and glucose under 6.0 mmol or the loop HP1 under 5.0 mmol.
 * Path 2 is insulin on board above 3.0 units for each mmol of rise from the lowest glucose in the last hour.
 * A rise under 0.1 mmol counts as 0.1. The 3.0 figure is from one episode and is not proven for a full meal dose.
 *
 * Both the max bolus and the wizard percent are halved. Later doses are left to their own checks.
 */
object WizardDropTrendCaution {

    const val SCALE = 0.5
    const val BGL_THRESHOLD_MGDL = 108.1
    const val HP1_THRESHOLD_MMOL = 5.0
    const val DELTA_THRESHOLD_MGDL = -2.7
    const val SDELTA_THRESHOLD_MGDL = -1.8
    const val LDELTA_THRESHOLD_MGDL = -1.8
    const val IOB_PER_MMOL_RISE = 3.0
    private const val MMOL_TO_MGDL = 18.0182

    fun iobDisproportionateToRise(iobUnits: Double, bgRiseMgdl: Double): Boolean {
        val riseMmol = (bgRiseMgdl / MMOL_TO_MGDL).coerceAtLeast(0.1)
        return iobUnits > riseMmol * IOB_PER_MMOL_RISE
    }

    fun applies(
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        bgMgdl: Double,
        hp1Mmol: Double?,
        iobUnits: Double,
        bgRiseMgdl: Double,
    ): Boolean {
        val establishedFall = delta <= DELTA_THRESHOLD_MGDL &&
            shortAvgDelta <= SDELTA_THRESHOLD_MGDL &&
            longAvgDelta <= LDELTA_THRESHOLD_MGDL
        val lowOrPredictedLow = bgMgdl < BGL_THRESHOLD_MGDL || (hp1Mmol != null && hp1Mmol < HP1_THRESHOLD_MMOL)
        return (establishedFall && lowOrPredictedLow) || iobDisproportionateToRise(iobUnits, bgRiseMgdl)
    }

    /** [base] times 0.5, rounded down to the pump step. Never above [base], and never below one step unless [base] is smaller. */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * SCALE / step + 1e-6) * step
        val rounded = round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }

    /** Half of [basePercent], at least 1. */
    fun scaledWizPercent(basePercent: Int): Int = (basePercent * SCALE).toInt().coerceAtLeast(1)
}
