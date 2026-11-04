package app.aaps.ui.compose.stats

import app.aaps.core.data.model.GV

data class SensorRawTrace(val startTimestamp: Long, val hourlyMeans: List<Double?>)

data class SensorRawData(val windowHours: Int, val sensors: List<SensorRawTrace>)

/**
 * Nightscout's unfiltered channel is stored in GV.noise, and filtered in GV.raw.
 * Reject missing channels and obvious BG copies. Older records have no raw provenance, so differing
 * channels are evidence of stored signal data, not proof that the device supplied it unchanged.
 * Never use this selection for glucose statistics or dosing.
 */
internal fun sensorRawSignal(reading: GV): Double? {
    val signal = reading.noise ?: return null
    val filtered = reading.raw ?: return null
    return signal.takeIf {
        reading.isValid && it.isFinite() && it > 10.0 && filtered.isFinite() && filtered > 0.0 &&
            it != filtered && it != reading.value
    }
}

/** Hourly means on the same sensor boundaries as the BG view; empty hours stay empty. */
internal fun calculateSensorRawData(
    readings: List<GV>, starts: List<Long>, now: Long, windowHours: Int, offset: Int
): SensorRawData? {
    val newestFirst = distinctSensorStarts(starts.filter { it <= now }).asReversed()
    val shown = newestFirst.withIndex().drop(offset).take(SENSOR_CYCLE_COUNT)
    if (shown.isEmpty()) return null
    val signals = readings.mapNotNull { reading -> sensorRawSignal(reading)?.let { reading.timestamp to it } }
        .sortedBy { it.first }
    return SensorRawData(windowHours, shown.map { (index, start) ->
        val end = minOf(start + windowHours * 3_600_000L, newestFirst.getOrNull(index - 1) ?: Long.MAX_VALUE, now + 1)
        val sums = DoubleArray(windowHours)
        val counts = IntArray(windowHours)
        signals.forEach { (time, signal) ->
            if (time >= start && time < end) {
                val hour = ((time - start) / 3_600_000L).toInt()
                sums[hour] += signal
                counts[hour]++
            }
        }
        SensorRawTrace(start, (0 until windowHours).map { if (counts[it] == 0) null else sums[it] / counts[it] })
    })
}

/**
 * The readings with their raw signal in place of BG (2026-11-04, per explicit request), for the Sensor tab's views when
 * Raw is chosen: the same lines, bands and changeover, from what the sensor itself reported. Readings without a raw
 * signal are left out, so they are gaps rather than BG. Display only, like [sensorRawSignal].
 */
internal fun sensorRawReadings(readings: List<GV>): List<GV> =
    readings.mapNotNull { reading -> sensorRawSignal(reading)?.let { reading.copy(value = it) } }
