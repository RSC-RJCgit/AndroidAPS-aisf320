package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionDetectorTest {

    private val minute = 60_000L

    /** One reading per minute from minute 0, values in mg/dL. */
    private fun series(values: List<Double>): List<Pair<Long, Double>> = values.mapIndexed { i, v -> i * minute to v }

    /** Flat for 45 minutes, then 2.5 mg/dL per minute for 20 minutes: the shape of the 8 Oct 03:14-03:34 UKF fall, scaled. */
    private fun fall(flat: Double = 150.0, minutes: Int = 20, perMinute: Double = 2.5): List<Double> =
        List(45) { flat } + List(minutes) { flat - perMinute * (it + 1) }

    @Test
    fun aLongUnbrokenFallAfterAQuietSpellEndingLowIsSuspected() {
        val s = assertNotNull(CompressionDetector.suspect(series(fall())))
        assertTrue(s.fromMgdl == 150.0)
        assertTrue(s.toMgdl == 100.0)
        assertTrue(s.fallMinutes >= 15L)
    }

    @Test
    fun aShortSharpFallIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(fall(flat = 140.0, minutes = 10, perMinute = 4.0))))
    }

    @Test
    fun aLongButShallowFallIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(fall(flat = 120.0, minutes = 25, perMinute = 0.6))))
    }

    @Test
    fun aFallThatIsStillAboveSixMmolIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(fall(flat = 190.0))))
    }

    @Test
    fun aRepeatingSwingIsNotSuspected() {
        // 8 Oct 12:00-12:25 shape: about 16 minutes between troughs, 1.5 mmol each way, ending near the bottom of a swing.
        val swing = List(90) { 90.0 + 14.0 * sin(2 * PI * it / 16.0) }
        assertNull(CompressionDetector.suspect(series(swing)))
    }

    @Test
    fun aNoisy30MinutesBeforeTheFallIsNotSuspected() {
        val noisy = List(15) { 150.0 } + List(10) { 150.0 + 2.5 * (it + 1) } + List(10) { 175.0 - 2.5 * (it + 1) } +
            List(10) { 150.0 } + List(20) { 150.0 - 2.5 * (it + 1) }
        assertNull(CompressionDetector.suspect(series(noisy)))
    }

    @Test
    fun tooLittleDataIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(listOf(150.0, 150.0, 100.0))))
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
