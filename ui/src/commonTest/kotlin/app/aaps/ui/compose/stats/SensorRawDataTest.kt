package app.aaps.ui.compose.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Sensor tab's Raw view (2026-11-04): the sensor's raw signal in place of BG, readings without it left out */
class SensorRawDataTest {

    private fun reading(time: Long, bg: Double, filtered: Double?, unfiltered: Double?) = GV(
        timestamp = time, raw = filtered, value = bg, trendArrow = TrendArrow.NONE, noise = unfiltered, sourceSensor = SourceSensor.UNKNOWN
    )

    @Test
    fun theRawSignalReplacesBg() {
        val raw = sensorRawReadings(listOf(reading(1L, bg = 120.0, filtered = 118.0, unfiltered = 131.0)))

        assertEquals(listOf(131.0), raw.map { it.value })
        assertEquals(listOf(1L), raw.map { it.timestamp })
    }

    @Test
    fun readingsWithoutARawSignalAreGaps() {
        val raw = sensorRawReadings(
            listOf(
                reading(1L, bg = 120.0, filtered = null, unfiltered = 131.0),   // no filtered channel
                reading(2L, bg = 120.0, filtered = 118.0, unfiltered = null),   // no unfiltered channel
                reading(3L, bg = 120.0, filtered = 118.0, unfiltered = 120.0),  // a copy of BG
                reading(4L, bg = 120.0, filtered = 118.0, unfiltered = 5.0),    // below 10, not a signal
                reading(5L, bg = 120.0, filtered = 118.0, unfiltered = 140.0)
            )
        )

        assertEquals(listOf(5L), raw.map { it.timestamp })
    }
}
