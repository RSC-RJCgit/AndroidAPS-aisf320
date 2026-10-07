package app.aaps.ui.compose.overview.graphs

import kotlin.math.floor
import kotlin.math.round

/** Each Graph 5 top band line is scaled to its own lowest and highest value over this long, ending now. */
internal const val TOP_BAND_WINDOW_MS = 24 * 60 * 60 * 1000L

/** Lowest and highest value of one top band line over the last 24 hours. The lowest sits on the lane bottom, the highest on its top. */
internal class BandRange(val min: Double, val max: Double) {

    /** 0..1 position of [value] inside the range. A flat line (lowest = highest) sits in the middle of its lane. */
    fun fraction(value: Double): Double =
        if (max - min < 1e-9) 0.5 else ((value - min) / (max - min)).coerceIn(0.0, 1.0)

    /** Legend text for the range, e.g. "0.1-0.3". */
    fun label(): String = "${tidy(min)}-${tidy(max)}"

    private fun tidy(value: Double): String = (round(value * 100.0) / 100.0).toString().removeSuffix(".0")
}

/** One top band line: its points (time to value) and its last-24-hour range. [range] is null when there are no points. */
internal class TopBandLine(val points: List<Pair<Long, Double>>, val range: BandRange?)

/**
 * Range over the last 24 hours up to [now]. If nothing falls in that window (old data only), all [points] are used so
 * the line is still drawn.
 */
internal fun bandRange(points: List<Pair<Long, Double>>, now: Long): BandRange? {
    if (points.isEmpty()) return null
    val recent = points.filter { it.first >= now - TOP_BAND_WINDOW_MS }.ifEmpty { points }
    return BandRange(recent.minOf { it.second }, recent.maxOf { it.second })
}

internal fun topBandLine(points: List<Pair<Long, Double>>, now: Long): TopBandLine =
    TopBandLine(points.sortedBy { it.first }, bandRange(points, now))

/**
 * Panel top the 3426 graph uses (PrepareBgDataWorker.addUpperChartMargin): the highest glucose over the loaded range, or the
 * high mark if that is higher, rounded (mg/dL to 40, mmol/L to 2) and raised by a margin (mg/dL +80, mmol/L +4).
 * With the insulin activity peak at 80% of this, the peak sits above the green range.
 */
internal fun activityChartTop(maxBg: Double, highMark: Double, mgdl: Boolean): Double {
    val top = maxOf(maxBg, highMark)
    // Half rounds up, as the 3426 Round.roundTo does (kotlin.math.round would round 4.5 to 4).
    fun roundTo(value: Double, step: Double) = floor(value / step + 0.5) * step
    return if (mgdl) roundTo(top, 40.0) + 80.0 else roundTo(top, 2.0) + 4.0
}

/**
 * BG axis that leaves the top [bandFraction] of its height free above the data and the target range, so the top band
 * lines sit above the green range and above the curve instead of on top of them. [dataMax] is the highest value that
 * must stay below the band (the high mark, or the highest glucose in view when that is higher).
 */
internal fun axisWithTopBand(dataMin: Double, dataMax: Double, bandFraction: Double): NiceScale {
    var scale = niceScale(dataMin, dataMax)
    // Rounding the top up to a clean number can also change the step and the bottom, so check again a few times.
    repeat(4) {
        val bandBottom = scale.max - bandFraction * (scale.max - scale.min)
        if (bandBottom >= dataMax - 1e-9) return scale
        val needed = (dataMax - bandFraction * scale.min) / (1.0 - bandFraction)
        scale = niceScale(dataMin, needed)
    }
    return scale
}
