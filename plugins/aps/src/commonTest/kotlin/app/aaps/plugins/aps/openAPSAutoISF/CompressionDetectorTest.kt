package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionDetectorTest {

    private val minute = 60_000L

    /** One reading per minute from minute 0, values in mg/dL. */
    private fun series(values: List<Double>): List<Pair<Long, Double>> = values.mapIndexed { i, v -> i * minute to v }

    /** Real shape of 8 Oct 2026 03:00-03:23: flat at about 151, then 5-8 mg/dL per minute down to 120. */
    private val compressionFall = series(
        listOf(
            157.0, 157.0, 157.0, 157.0, 157.0, 157.0, 156.0, 155.0, 153.0, 152.0, 151.0, 150.0, 151.0, 152.0, 152.0, 151.0,
            151.0, 151.0, 146.0, 140.0, 133.0, 128.0, 124.0, 120.0
        )
    )

    @Test
    fun aFlatSpellThenASteepFallIsSuspected() {
        val s = assertNotNull(CompressionDetector.suspect(compressionFall))
        assertTrue(s.toMgdl == 120.0)
        assertTrue(s.steepestMgdlPerMin <= -4.0)
    }

    @Test
    fun aGradualFallIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(List(30) { 150.0 - it * 1.5 })))
    }

    @Test
    fun aFallThatFollowsATrendIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(List(24) { if (it < 12) 190.0 - it * 3.0 else 154.0 - (it - 12) * 4.5 })))
    }

    @Test
    fun tooLittleDataIsNotSuspected() {
        assertNull(CompressionDetector.suspect(series(listOf(150.0, 150.0, 120.0))))
    }

    @Test
    fun aSharpRiseFromNearTheLowConfirms() {
        val readings = compressionFall + listOf(
            24 * minute to 118.0, 25 * minute to 117.0, 26 * minute to 125.0, 27 * minute to 140.0, 28 * minute to 150.0
        )
        assertTrue(CompressionDetector.rebounded(readings, 20 * minute))
    }

    @Test
    fun aSlowRecoveryDoesNotConfirm() {
        val readings = compressionFall + (0 until 12).map { (24 + it) * minute to 120.0 + it * 1.5 }
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
