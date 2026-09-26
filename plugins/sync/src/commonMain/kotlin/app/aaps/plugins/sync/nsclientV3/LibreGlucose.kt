package app.aaps.plugins.sync.nsclientV3

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Slope and offset applied to a raw Libre value. The result stays at least 40 mg/dL. */
internal fun calibratedLibre(raw: Double, slope: Double, offset: Double, unitFactor: Double): Double =
    max(40.0, raw * slope + offset * unitFactor)

/**
 * LibreSpecial. One exponential step from the previous smooth value.
 * [alpha] is the usual 0.3 and [maxGap] is the usual 20 minutes.
 */
internal fun libreSpecial(
    calibrated: Double,
    lastSmooth: Double,
    elapsedMinutes: Double,
    alpha: Double = 0.3,
    maxGap: Double = 20.0,
): Double {
    val gap = max(maxGap - 1.0, 0.001)
    val rise = (max(0.0, elapsedMinutes - 1.0) / gap).pow(2.0)
    val effectiveAlpha = min(1.0, alpha + (1.0 - alpha) * rise)
    return if (lastSmooth > 0.0) lastSmooth + effectiveAlpha * (calibrated - lastSmooth) else calibrated
}
