package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CarbTimeFromRiseTest {

    private val now = 1_800_000_000_000L
    private val mmol = 18.0182

    /** One reading every 5 minutes, oldest first, the last one at [now]. */
    private fun series(vararg mmolValues: Double): List<Pair<Long, Double>> =
        mmolValues.mapIndexed { i, v -> now - (mmolValues.size - 1 - i) * 5 * 60_000L to v * mmol }

    @Test
    fun aRiseThatBeganThirtyMinutesAgoDatesTheCarbsForty() {
        // Flat at 5.5, then rising from 30 minutes ago to 8.0
        val readings = series(5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.9, 6.4, 6.9, 7.3, 7.7, 8.0)
        val estimate = assertNotNull(CarbTimeFromRise.estimateFromSeries(now, readings))
        assertEquals(30, estimate.minutesSinceOnset)
        assertEquals(-40, estimate.offsetMinutes)
    }

    @Test
    fun noEstimateWhenTheRiseIsTooSmallOrHasStopped() {
        // Up 1.0 only
        assertNull(CarbTimeFromRise.estimateFromSeries(now, series(5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.7, 5.9, 6.1, 6.3, 6.4, 6.5)))
        // Up 2.5 but flat over the last 5 minutes
        assertNull(CarbTimeFromRise.estimateFromSeries(now, series(5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 6.2, 7.0, 7.6, 8.0, 8.0, 8.0)))
    }

    @Test
    fun noEstimateWithOldOrTooFewReadings() {
        val readings = series(5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.5, 5.9, 6.4, 6.9, 7.3, 7.7, 8.0)
        assertNull(CarbTimeFromRise.estimateFromSeries(now + 11 * 60_000L, readings))
        assertNull(CarbTimeFromRise.estimateFromSeries(now, readings.takeLast(9)))
    }

    @Test
    fun theOffsetStopsAtMinusSixty() {
        // Rising slowly for 75 minutes
        val rising = (0..15).map { 5.0 + it * 0.2 }.toDoubleArray()
        val readings = series(4.9, 4.9, 4.9, *rising)
        val estimate = assertNotNull(CarbTimeFromRise.estimateFromSeries(now, readings))
        assertEquals(-60, estimate.offsetMinutes)
    }
}
