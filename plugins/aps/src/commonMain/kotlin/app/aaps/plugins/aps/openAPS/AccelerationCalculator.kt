package app.aaps.plugins.aps.openAPS

import dev.zacsweers.metro.Inject
import kotlin.math.pow

/**
 * Fits a parabola to a newest-first glucose series and reports acceleration and the 5-minute slopes.
 * The same fit the loop uses, so another glucose series can be compared with it.
 */
@Inject
class AccelerationCalculator {

    data class ParabolaFitResult(
        val bgAcceleration: Double,
        val deltaPl: Double,
        val deltaPn: Double,
        val windowMinutes: Double,
        val corrSqu: Double
    )

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
                break
            } else if (ti < tiLast - gapBreakMinutes * 60) {
                if (i < 3 || -ti < minFitMinutes * 60) {
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
