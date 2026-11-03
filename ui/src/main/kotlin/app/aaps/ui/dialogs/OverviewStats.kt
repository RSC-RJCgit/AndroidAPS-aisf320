package app.aaps.ui.dialogs

import kotlin.math.sqrt

/**
 * The numbers behind the overview's yellow statistics dot (2026-11-01, per explicit request), the same as v4's summary:
 * time below, in and above range, average, GMI and CV, over the glucose readings of the chosen days.
 */
data class OverviewStatsData(
    val readingCount: Int,
    val lowPercent: Double,
    val inRangePercent: Double,
    val highPercent: Double,
    val averageMgdl: Double,
    val gmiPercent: Double,
    val cvPercent: Double
) {
    val eA1cPercent: Double get() = (averageMgdl + 46.7) / 28.7
}

/** The range v4's summary uses: 70-180 mg/dL, 3.9-10.0 mmol/L */
const val OVERVIEW_STATS_LOW_MGDL = 70.0
const val OVERVIEW_STATS_HIGH_MGDL = 180.0

/** Readings below 39 mg/dL are sensor errors, not glucose */
private const val MIN_VALID_MGDL = 39.0

/**
 * @param valuesMgdl the readings of the period, in mg/dL
 * @return null when there is no valid reading
 */
fun calculateOverviewStats(valuesMgdl: List<Double>, lowMgdl: Double = OVERVIEW_STATS_LOW_MGDL, highMgdl: Double = OVERVIEW_STATS_HIGH_MGDL): OverviewStatsData? {
    val values = valuesMgdl.filter { it >= MIN_VALID_MGDL }
    if (values.isEmpty()) return null
    val count = values.size
    val average = values.average()
    val sd = sqrt(values.sumOf { (it - average) * (it - average) } / count)
    return OverviewStatsData(
        readingCount = count,
        lowPercent = values.count { it < lowMgdl } * 100.0 / count,
        inRangePercent = values.count { it in lowMgdl..highMgdl } * 100.0 / count,
        highPercent = values.count { it > highMgdl } * 100.0 / count,
        averageMgdl = average,
        // Glucose management indicator (Bergenstal 2018), from the average in mg/dL
        gmiPercent = 3.31 + 0.02392 * average,
        cvPercent = if (average > 0) sd / average * 100.0 else 0.0
    )
}
