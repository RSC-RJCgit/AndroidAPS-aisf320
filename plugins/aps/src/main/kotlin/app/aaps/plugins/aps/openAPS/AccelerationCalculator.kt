package app.aaps.plugins.aps.openAPS

import dagger.Reusable
import javax.inject.Inject
import kotlin.math.pow

/**
 * Generalized version of GlucoseStatusCalculatorAutoIsf's parabola-fit section (the "calculate best
 * parabola and determine delta by extending it 5 minutes into the future" block, after
 * https://www.codeproject.com/Articles/63170/Least-Squares-Regression-for-Quadratic-Curve-Fitti) --
 * decoupled from InMemoryGlucoseValue and the raw-vs-bucketed (use1MinuteRaw) branching, so any
 * smoothing type's own (timestamp, value) history can be run through the identical fit. Added
 * 2026-08-15 for the per-type delta/acceleration comparison work on the UKF3426 branch, alongside
 * DeltaCalculator.calculateDeltasGeneric().
 *
 * scaleTime/scaleBg from the original are both hardcoded to 1.0 there (the comments there note they
 * "were" other values) -- dropped here entirely as a no-op simplification, not a behavior change.
 *
 * Same algorithm as the original: tests progressively longer lookback windows (up to
 * [maxLookbackMinutes]), keeps whichever gives the best R^2 fit once at least [minFitMinutes] of
 * history is covered, and stops early on a gap wider than [gapBreakMinutes] -- with the original's
 * same edge case preserved: if that gap is hit before [minFitMinutes] of continuous history had
 * accumulated, any preliminary result found is discarded (reset to zero) rather than kept as a
 * low-confidence fit. bgAcceleration = 2*a of the winning fit; deltaPl/deltaPn are the parabola's own
 * extrapolated 5-min-backward/-forward slopes at t=0 (the newest point) -- same units/scale as
 * DeltaCalculator's delta (mg/dL per 5 min).
 */
@Reusable
class AccelerationCalculator @Inject constructor() {

    data class ParabolaFitResult(
        val bgAcceleration: Double,
        val deltaPl: Double,
        val deltaPn: Double,
        val windowMinutes: Double,
        val corrSqu: Double
    )

    /**
     * @param data A list of (timestamp, value) pairs, sorted from newest to oldest (data[0] = "now").
     */
    fun fitBestParabola(
        data: List<Pair<Long, Double>>,
        minFitMinutes: Double = 15.0,
        maxLookbackMinutes: Double = 47.0,
        gapBreakMinutes: Double = 11.0
    ): ParabolaFitResult {
        if (data.size <= 3) return ParabolaFitResult(0.0, 0.0, 0.0, 0.0, 0.0)

        var deltaPl = 0.0
        var deltaPn = 0.0
        var bgAcceleration = 0.0
        var corrMax = 0.0
        var windowMinutes = 0.0

        var sy = 0.0
        var sx = 0.0
        var sx2 = 0.0
        var sx3 = 0.0
        var sx4 = 0.0
        var sxy = 0.0
        var sx2y = 0.0
        val time0 = data[0].first
        var tiLast = 0.0
        var n = 0

        for (i in data.indices) {
            val (thenDate, bg) = data[i]
            if (bg <= minBgValue) continue
            n += 1
            val ti = (thenDate - time0) / 1000.0
            if (-ti > maxLookbackMinutes * 60) {
                break // skip records older than maxLookbackMinutes
            } else if (ti < tiLast - gapBreakMinutes * 60) { // stop scan if a gap wider than gapBreakMinutes is detected
                if (i < 3 || -ti < minFitMinutes * 60) { // history before this gap was too short for a trustworthy fit
                    deltaPl = 0.0
                    deltaPn = 0.0
                    bgAcceleration = 0.0
                    corrMax = 0.0
                    windowMinutes = 0.0
                }
                break
            }
            tiLast = ti
            sx += ti
            sx2 += ti.pow(2.0)
            sx3 += ti.pow(3.0)
            sx4 += ti.pow(4.0)
            sy += bg
            sxy += ti * bg
            sx2y += ti.pow(2.0) * bg

            if (n > 3 && -ti > minFitMinutes * 60) {
                val detH = sx4 * (sx2 * n - sx * sx) - sx3 * (sx3 * n - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
                val detA = sx2y * (sx2 * n - sx * sx) - sxy * (sx3 * n - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)
                val detB = sx4 * (sxy * n - sy * sx) - sx3 * (sx2y * n - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)
                val detC = sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)
                if (detH != 0.0) {
                    val a = detA / detH * 300.0.pow(2.0)
                    val b = detB / detH * 300.0
                    val c = detC / detH
                    val yMean = sy / n
                    var sSquares = 0.0
                    var sResidualSquares = 0.0
                    // Unconditional over 0..i, same as the original -- does NOT re-apply the
                    // minBgValue filter here, matching the original's own (mild) inconsistency
                    // between the fit-accumulation loop and this residual-check loop.
                    for (j in 0..i) {
                        val (beforeDate, beforeBg) = data[j]
                        sSquares += (beforeBg - yMean).pow(2.0)
                        val deltaT = (beforeDate - time0) / 1000.0 / 300.0
                        val bgj = a * deltaT.pow(2.0) + b * deltaT + c
                        sResidualSquares += (beforeBg - bgj).pow(2.0)
                    }
                    val rSqu = if (sSquares != 0.0) 1 - sResidualSquares / sSquares else 0.0
                    if (rSqu >= corrMax) {
                        corrMax = rSqu
                        windowMinutes = -ti / 60.0
                        val delta5Min = 1.0
                        deltaPl = -(a * (-delta5Min).pow(2.0) - b * delta5Min)
                        deltaPn = a * delta5Min.pow(2.0) + b * delta5Min
                        bgAcceleration = 2 * a
                    }
                }
            }
        }
        return ParabolaFitResult(bgAcceleration, deltaPl, deltaPn, windowMinutes, corrMax)
    }

    companion object {

        private const val minBgValue = 39.0
    }
}
