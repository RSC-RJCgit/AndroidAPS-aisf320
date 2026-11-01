// Ported from v4 (AaAPS-kmp-beta1merge, ui/compose/stats) on 2026-11-01, per explicit request: the same numbers as
// v4's Statistics tabs, drawn with Android views in v3. Keep the two in step when one changes.
package app.aaps.ui.stats

import app.aaps.core.data.model.TDD

/**
 * The Cycle tab: daily insulin totals laid over 28-day cycles, newest first, with their average per cycle day.
 * v3's daily totals have no insulin-for-carbs figure, so there is no "cleaned" line here as in v4.
 */
data class TddCyclePatternData(
    val cycles: List<CycleSeries>,
    val average: List<Double>,
    val cycleCount: Int,
    val totalDaysAvailable: Int
)

data class CycleSeries(
    val dayValues: List<Double>,
    val cycleIndex: Int,
    val startDate: String
)

/** One cycle day across the shown cycles, for the ambulatory view */
data class CycleDayBand(
    val day: Int,
    val p10: Double,
    val p25: Double,
    val median: Double,
    val p75: Double,
    val p90: Double
)

/**
 * @param dailyTdds daily totals, newest first
 * @param offset days to skip from the newest
 * @return null with fewer than two whole cycles
 */
internal fun computeCycleData(dailyTdds: List<TDD>, offset: Int, dateString: (Long) -> String): TddCyclePatternData? {
    if (dailyTdds.size < 2 * CYCLE_LENGTH) return null
    val usable = dailyTdds.drop(offset)
    val cycleCount = usable.size / CYCLE_LENGTH
    if (cycleCount < 2) return null
    val cycles = (0 until cycleCount).map { c ->
        val chunk = usable.subList(c * CYCLE_LENGTH, (c + 1) * CYCLE_LENGTH).reversed() // oldest first: day 1..28
        CycleSeries(dayValues = chunk.map { it.totalAmount }, cycleIndex = c, startDate = dateString(chunk.first().timestamp))
    }
    return TddCyclePatternData(
        cycles = cycles,
        average = (0 until CYCLE_LENGTH).map { day -> cycles.map { it.dayValues[day] }.average() },
        cycleCount = cycleCount,
        totalDaysAvailable = dailyTdds.size
    )
}

/** The cycles' values for each cycle day as percentiles. Days no cycle has are left out. */
internal fun cycleDayBands(cycles: List<CycleSeries>): List<CycleDayBand> {
    val days = cycles.maxOfOrNull { it.dayValues.size } ?: 0
    return (0 until days).mapNotNull { day ->
        val sorted = cycles.mapNotNull { it.dayValues.getOrNull(day) }.sorted()
        if (sorted.isEmpty()) return@mapNotNull null
        CycleDayBand(day, percentile(sorted, 0.10), percentile(sorted, 0.25), percentile(sorted, 0.50), percentile(sorted, 0.75), percentile(sorted, 0.90))
    }
}

/** The average daily total of each week of the cycle over all shown cycles. Null for a week no cycle has a day of. */
internal fun cycleWeekAverages(cycles: List<CycleSeries>, weeks: Int = 4, daysPerWeek: Int = 7): List<Double?> =
    (0 until weeks).map { week ->
        val values = cycles.flatMap { cycle -> (week * daysPerWeek until (week + 1) * daysPerWeek).mapNotNull { cycle.dayValues.getOrNull(it) } }
        values.takeIf { it.isNotEmpty() }?.average()
    }

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    val position = fraction * sorted.lastIndex
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

internal const val CYCLE_LENGTH = 28
