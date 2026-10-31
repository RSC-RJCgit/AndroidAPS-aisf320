package app.aaps.ui.compose.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor

/**
 * Sensor cycle overlay (2026-10-31, per explicit request): glucose laid over the life of each CGM sensor,
 * aligned to its sensor change, the way the Cycle tab lays insulin totals over 28-day cycles.
 *
 * Two views of the same sensors:
 *  - whole sensor: day 0 to the end of the sensor's life, one line per sensor and their average
 *  - changeover: the last days of one sensor and the first days of the next, pooled over the changes into
 *    median and percentile bands, with the average and time in range before and after (as Nocturne's
 *    "Site Change Impact" report does for pump sites)
 */
enum class SensorCycleView {
    WHOLE_SENSOR,
    CHANGEOVER
}

/** One sensor: its mean glucose per bin from its start, null where it has no readings. */
data class SensorTrace(
    val startTimestamp: Long,
    val binMeansMgdl: List<Double?>
)

/** Pooled readings of one hour around the changes. [hourFromChange] is negative before the change. */
data class ChangeoverPercentile(
    val hourFromChange: Int,
    val p10Mgdl: Double,
    val p25Mgdl: Double,
    val medianMgdl: Double,
    val p75Mgdl: Double,
    val p90Mgdl: Double
)

data class SensorChangeoverData(
    val changeTimestamps: List<Long>,
    val hoursEachSide: Int,
    val percentiles: List<ChangeoverPercentile>,
    val averageBeforeMgdl: Double?,
    val averageAfterMgdl: Double?,
    val tirBeforePercent: Double?,
    val tirAfterPercent: Double?
)

data class SensorCycleData(
    val windowHours: Int,
    val binHours: Int,
    /** Newest first, as the Cycle tab orders its cycles. */
    val sensors: List<SensorTrace>,
    val averageMgdl: List<Double?>,
    /** Sensor changes found in the history read, so the offset can be capped. */
    val sensorsAvailable: Int,
    val lowMgdl: Double,
    val highMgdl: Double,
    val changeover: SensorChangeoverData?
)

/**
 * How long a sensor of this type can last, in hours: the length of the whole sensor view.
 *
 * Not stored anywhere in AAPS, so from the makers' figures. Libre 2 and 3 take the 15 days of their Plus
 * versions, as the source does not tell them apart; a 14 day sensor simply ends at the next change. Any other
 * sensor, or one that lasts months (Eversense), gets 15 days.
 */
internal fun sensorWindowHours(sensor: SourceSensor?): Int = when (sensor) {
    SourceSensor.DEXCOM_G6                                  -> 10 * 24
    SourceSensor.DEXCOM_G7                                  -> 10 * 24 + 12 // ten days and the 12 hour grace period
    SourceSensor.LIBRE_2, SourceSensor.LIBRE_3              -> 15 * 24
    SourceSensor.MM_SIMPLERA                                -> 7 * 24
    SourceSensor.MEDTRUM_A6, SourceSensor.MEDTRUM_UNKNOWN   -> 14 * 24
    else                                                    -> DEFAULT_WINDOW_HOURS
}

/**
 * The sensor changes, oldest first, with duplicates merged: the same change can arrive twice (entered here and
 * synced back from Nightscout), a few seconds or minutes apart.
 */
internal fun distinctSensorStarts(starts: List<Long>): List<Long> {
    val out = mutableListOf<Long>()
    for (start in starts.sorted()) {
        if (out.isEmpty() || start - out.last() >= DUPLICATE_START_MS) out.add(start)
    }
    return out
}

/**
 * The time span of readings [calculateSensorCycleData] needs for [offset], so only that is read from the
 * database: from 5 days before the oldest change shown to the end of the newest sensor shown.
 * Null when there are no sensors at that offset.
 */
internal fun sensorCycleReadingSpan(
    starts: List<Long>,
    now: Long,
    windowHours: Int,
    offset: Int,
    sensorCount: Int = SENSOR_CYCLE_COUNT,
    changeoverHours: Int = SENSOR_CHANGEOVER_HOURS
): LongRange? {
    val distinct = distinctSensorStarts(starts)
    val shown = distinct.asReversed().drop(offset).take(sensorCount)
    if (shown.isEmpty()) return null
    val from = shown.last() - changeoverHours * HOUR_MS
    val to = minOf(now, shown.first() + maxOf(windowHours, changeoverHours) * HOUR_MS)
    return from..to
}

/**
 * Lays the readings over the sensors.
 *
 * @param readings glucose readings, at least the [sensorCycleReadingSpan] for this offset
 * @param starts sensor change times, in any order and with duplicates
 * @param offset how many of the newest sensors to skip
 * @return null when there is no sensor change at that offset
 */
internal fun calculateSensorCycleData(
    readings: List<GV>,
    starts: List<Long>,
    now: Long,
    windowHours: Int,
    offset: Int,
    lowMgdl: Double,
    highMgdl: Double,
    sensorCount: Int = SENSOR_CYCLE_COUNT,
    binHours: Int = SENSOR_CYCLE_BIN_HOURS,
    changeoverHours: Int = SENSOR_CHANGEOVER_HOURS
): SensorCycleData? {
    val distinct = distinctSensorStarts(starts)
    // Newest first; each start knows the next (newer) one, where its own sensor ended
    val newestFirst = distinct.asReversed()
    val shown = newestFirst.withIndex().drop(offset).take(sensorCount)
    if (shown.isEmpty()) return null

    val valid = readings.filter { it.isValid && it.value >= MIN_VALID_BG }.sortedBy { it.timestamp }
    val binMs = binHours * HOUR_MS
    val binCount = (windowHours + binHours - 1) / binHours

    val sensors = shown.map { (index, start) ->
        val nextStart = newestFirst.getOrNull(index - 1) ?: Long.MAX_VALUE
        val end = minOf(start + windowHours * HOUR_MS, nextStart, now + 1)
        val sums = DoubleArray(binCount)
        val counts = IntArray(binCount)
        for (reading in valid) {
            if (reading.timestamp < start || reading.timestamp >= end) continue
            val bin = ((reading.timestamp - start) / binMs).toInt()
            if (bin >= binCount) continue
            sums[bin] += reading.value
            counts[bin]++
        }
        SensorTrace(
            startTimestamp = start,
            binMeansMgdl = (0 until binCount).map { if (counts[it] > 0) sums[it] / counts[it] else null }
        )
    }

    // Mean of the sensors that have a value in the bin: a gap, or the sensor still running, is not a zero
    val average = (0 until binCount).map { bin ->
        sensors.mapNotNull { it.binMeansMgdl[bin] }.takeIf { it.isNotEmpty() }?.average()
    }

    return SensorCycleData(
        windowHours = windowHours,
        binHours = binHours,
        sensors = sensors,
        averageMgdl = average,
        sensorsAvailable = distinct.size,
        lowMgdl = lowMgdl,
        highMgdl = highMgdl,
        changeover = calculateChangeover(valid, newestFirst, offset, sensorCount, changeoverHours, now, lowMgdl, highMgdl)
    )
}

/**
 * The changes at [offset] that have a sensor before them, each looking [hoursEachSide] back and forward. A side
 * stops at the change before or after it, so a short sensor never lends its readings to the wrong change.
 */
private fun calculateChangeover(
    valid: List<GV>,
    newestFirst: List<Long>,
    offset: Int,
    sensorCount: Int,
    hoursEachSide: Int,
    now: Long,
    lowMgdl: Double,
    highMgdl: Double
): SensorChangeoverData? {
    val changes = newestFirst.withIndex().drop(offset).take(sensorCount)
        .filter { (index, _) -> index + 1 < newestFirst.size }
    if (changes.isEmpty()) return null

    val sideMs = hoursEachSide * HOUR_MS
    val byHour = mutableMapOf<Int, MutableList<Double>>()
    val before = mutableListOf<Double>()
    val after = mutableListOf<Double>()
    for ((index, change) in changes) {
        val from = maxOf(change - sideMs, newestFirst[index + 1])
        val to = minOf(change + sideMs, newestFirst.getOrNull(index - 1) ?: Long.MAX_VALUE, now + 1)
        for (reading in valid) {
            if (reading.timestamp < from || reading.timestamp >= to) continue
            val sinceChange = reading.timestamp - change
            val hour = sinceChange.floorDiv(HOUR_MS).toInt()
            byHour.getOrPut(hour) { mutableListOf() }.add(reading.value)
            if (sinceChange < 0) before.add(reading.value) else after.add(reading.value)
        }
    }

    return SensorChangeoverData(
        changeTimestamps = changes.map { it.value },
        hoursEachSide = hoursEachSide,
        percentiles = byHour.entries.sortedBy { it.key }.map { (hour, values) ->
            val sorted = values.sorted()
            ChangeoverPercentile(
                hourFromChange = hour,
                p10Mgdl = percentileOf(sorted, 0.10),
                p25Mgdl = percentileOf(sorted, 0.25),
                medianMgdl = percentileOf(sorted, 0.50),
                p75Mgdl = percentileOf(sorted, 0.75),
                p90Mgdl = percentileOf(sorted, 0.90)
            )
        },
        averageBeforeMgdl = before.takeIf { it.isNotEmpty() }?.average(),
        averageAfterMgdl = after.takeIf { it.isNotEmpty() }?.average(),
        tirBeforePercent = before.takeIf { it.isNotEmpty() }?.let { values -> values.count { it in lowMgdl..highMgdl } * 100.0 / values.size },
        tirAfterPercent = after.takeIf { it.isNotEmpty() }?.let { values -> values.count { it in lowMgdl..highMgdl } * 100.0 / values.size }
    )
}

/** Linear interpolation between ranks, the same as the AGP. */
private fun percentileOf(sorted: List<Double>, fraction: Double): Double {
    val position = fraction * sorted.lastIndex
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val weight = position - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
}

internal const val SENSOR_CYCLE_COUNT = 3
internal const val SENSOR_CYCLE_BIN_HOURS = 3
internal const val SENSOR_CHANGEOVER_HOURS = 5 * 24
private const val DEFAULT_WINDOW_HOURS = 15 * 24
private const val MIN_VALID_BG = 39.0
private const val HOUR_MS = 60L * 60L * 1000L
private const val DUPLICATE_START_MS = 2 * HOUR_MS
