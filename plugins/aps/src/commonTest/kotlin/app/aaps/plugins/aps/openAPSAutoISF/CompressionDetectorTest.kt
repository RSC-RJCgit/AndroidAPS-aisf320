package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The drop-only rules of 2026-11-19: a 1.0 mmol fall in 5 minutes after a flat or rising 15 minutes. */
class CompressionDetectorTest {

    private val minute = 60_000L

    /** One reading per minute from minute 0, values in mg/dL. */
    private fun series(values: List<Double>): List<Pair<Long, Double>> = values.mapIndexed { i, v -> i * minute to v }

    /** Flat for 25 minutes, then a drop of [perMinute] mg/dL per minute for [minutes] minutes. */
    private fun drop(flat: Double = 150.0, minutes: Int = 5, perMinute: Double = 4.0): List<Double> =
        List(25) { flat } + List(minutes) { flat - perMinute * (it + 1) }

    @Test
    fun aSuddenDropOutOfAFlatSpellIsSuspected() {
        val s = assertNotNull(CompressionDetector.suspect(series(drop())))
        assertEquals(150.0, s.fromMgdl)
        assertEquals(130.0, s.toMgdl)
        assertEquals(5L, s.fallMinutes)
        assertTrue(abs(s.steepestMgdlPerMin + 4.0) < 0.001)
    }

    @Test
    fun aDropAfterARiseIsSuspected() {
        // 19 Nov 06:34: the 15 minutes before were a rebound climbing from 5.1 to 7.2 mmol, then 2.6 mmol down in 5 minutes
        val rising = List(10) { 92.0 } + List(15) { 92.0 + 2.5 * (it + 1) } + List(5) { 129.5 - 9.0 * (it + 1) }
        assertNotNull(CompressionDetector.suspect(series(rising)))
    }

    @Test
    fun aDropAfterTheEarlierDipHasSettledIsSuspected() {
        // 19 Nov 02:20: a rebound overshoot to 8.2 came down to 6.8 and held there 15 minutes, then 1.2 mmol in 5 minutes
        val values = List(10) { 148.0 } + List(10) { 148.0 - 2.5 * (it + 1) } + List(15) { 123.0 } + List(5) { 123.0 - 4.4 * (it + 1) }
        assertNotNull(CompressionDetector.suspect(series(values)))
    }

    @Test
    fun aSlowSlideIsNotSuspected() {
        // 8.2 to 5.9 mmol over 40 minutes, about -0.15 mmol per 5 minutes
        val slide = List(20) { 148.0 } + List(40) { 148.0 - (148.0 - 106.0) * (it + 1) / 40.0 }
        assertNull(CompressionDetector.suspect(series(slide)))
    }

    @Test
    fun aDropOnTopOfAFallIsNotSuspected() {
        // The 15 minutes before already fell 0.7 mmol per 5 minutes: not flat or rising
        val values = List(10) { 160.0 } + List(15) { 160.0 - 2.5 * (it + 1) } + List(5) { 122.5 - 4.0 * (it + 1) }
        assertNull(CompressionDetector.suspect(series(values)))
    }

    @Test
    fun aSlowNetFallBeforeIsNotSuspected() {
        // 0.8 mmol net fall over the 15 minutes before, never more than 0.3 per 5 minutes
        val values = List(10) { 160.0 } + List(15) { 160.0 - 1.0 * (it + 1) } + List(5) { 145.0 - 4.0 * (it + 1) }
        assertNull(CompressionDetector.suspect(series(values)))
    }

    @Test
    fun aLongFallIsNotATriggerOnceItIsUnderway() {
        assertNull(CompressionDetector.suspect(series(drop(minutes = 30, perMinute = 2.0))))
    }

    @Test
    fun aDropUnder1MmolIn5MinutesIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(drop(perMinute = 3.4))))
    }

    @Test
    fun aDropTheInsulinOnBoardExplainsIsNotSuspected() {
        // 20 mg/dL in 5 minutes against 17 explained: under 1.25 times, so the insulin accounts for it.
        assertNull(CompressionDetector.suspect(series(drop()), explainedFall5Mgdl = 17.0))
        // Against 10 explained it is 2 times, above the 1.25 bar.
        assertNotNull(CompressionDetector.suspect(series(drop()), explainedFall5Mgdl = 10.0))
    }

    @Test
    fun aDropAtAnyGlucoseLevelCounts() {
        assertNotNull(CompressionDetector.suspect(series(drop(flat = 220.0))))
        assertNotNull(CompressionDetector.suspect(series(drop(flat = 110.0))))
    }

    @Test
    fun aRepeatingSwingIsNotSuspected() {
        // About 16 minutes between troughs, 1.5 mmol each way: the minutes before each fall are falling too
        val swing = List(90) { 90.0 + 14.0 * sin(2 * PI * it / 16.0) }
        assertNull(CompressionDetector.suspect(series(swing)))
    }

    @Test
    fun aDropThatFinishedEarlierAndHasSettledIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(drop() + List(10) { 130.0 })))
    }

    @Test
    fun tooLittleDataIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(listOf(150.0, 150.0, 100.0))))
    }

    @Test
    fun theHoursAre2230To0800() {
        assertFalse(CompressionDetector.inHours(22 * 60 + 29))
        assertTrue(CompressionDetector.inHours(22 * 60 + 30))
        assertTrue(CompressionDetector.inHours(3 * 60))
        assertTrue(CompressionDetector.inHours(7 * 60 + 59))
        assertFalse(CompressionDetector.inHours(8 * 60))
    }

    private val lowThenRebound = series(List(30) { 100.0 })

    @Test
    fun aSharpRiseFromNearTheLowConfirms() {
        val readings = lowThenRebound + listOf(
            30 * minute to 102.0, 31 * minute to 101.0, 32 * minute to 110.0, 33 * minute to 125.0, 34 * minute to 135.0
        )
        assertTrue(CompressionDetector.rebounded(readings, 20 * minute))
    }

    @Test
    fun aSlowRecoveryDoesNotConfirm() {
        val readings = lowThenRebound + (0 until 12).map { (30 + it) * minute to 100.0 + it * 1.5 }
        assertFalse(CompressionDetector.rebounded(readings, 20 * minute))
    }

    @Test
    fun aRiseThatStartsWellAboveTheLowDoesNotConfirm() {
        // The low (90) is more than 10 minutes before the rise, which starts at 110, 20 above it.
        val readings = listOf(20 * minute to 90.0) + (21..40).map { it * minute to 110.0 } +
            listOf(41 * minute to 125.0, 42 * minute to 136.0)
        assertFalse(CompressionDetector.rebounded(readings, 20 * minute))
    }
}
