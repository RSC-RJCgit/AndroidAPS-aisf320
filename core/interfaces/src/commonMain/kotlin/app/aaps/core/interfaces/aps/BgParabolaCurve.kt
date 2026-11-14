package app.aaps.core.interfaces.aps

import app.aaps.core.interfaces.overview.graph.GraphDataPoint

/** How far the BG parabola is drawn into the future, in minutes. The same as in AutoISF 3.2.1. */
const val BG_PARABOLA_EXTRAPOLATION_MINUTES = 20

/**
 * AutoISF fork: the BG parabola that AutoISF fitted, as points for the BG graph, in mg/dl.
 *
 * [fit] covers the minutes the parabola was fitted to, from the oldest reading used up to the newest one.
 * [extrapolation] goes on from the newest reading for [BG_PARABOLA_EXTRAPOLATION_MINUTES] minutes.
 * Both have one point per minute, so the line is smooth and with a 1 minute CGM there is a point at
 * every reading.
 */
data class BgParabolaCurve(
    val fit: List<GraphDataPoint>,
    val extrapolation: List<GraphDataPoint>
)

/**
 * The parabola of this glucose status as a curve, null when there was no fit (correlation 0).
 *
 * The fit is `BG = a0 + a1 * t + a2 * t²` with t in steps of 5 minutes and t = 0 at the newest reading
 * ([GlucoseStatusAutoIsf.date]); see `GlucoseStatusCalculatorAutoIsf`. This is the formula AutoISF 3.2.1
 * draws its pink "BG parabola" with. Display only, nothing here is used for dosing.
 */
fun GlucoseStatusAutoIsf.bgParabolaCurve(): BgParabolaCurve? {
    if (corrSqu <= 0.0 || parabolaMinutes <= 0.0) return null
    fun pointAt(minute: Int): GraphDataPoint {
        val t = minute / 5.0
        return GraphDataPoint(timestamp = date + minute * 60_000L, value = a0 + a1 * t + a2 * t * t)
    }
    return BgParabolaCurve(
        fit = (-parabolaMinutes.toInt()..0).map { pointAt(it) },
        extrapolation = (0..BG_PARABOLA_EXTRAPOLATION_MINUTES).map { pointAt(it) }
    )
}
