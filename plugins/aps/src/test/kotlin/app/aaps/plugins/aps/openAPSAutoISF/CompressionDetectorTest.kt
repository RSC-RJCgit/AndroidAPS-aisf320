package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class CompressionDetectorTest {

    private val minute = 60_000L

    /** One reading per minute from minute 0, values in mg/dL. */
    private fun series(values: List<Double>): List<Pair<Long, Double>> = values.mapIndexed { i, v -> i * minute to v }

    /** Flat for 45 minutes, then 2.5 mg/dL per minute for 20 minutes: the shape of the 8 Oct 03:14-03:34 UKF fall, scaled. */
    private fun fall(flat: Double = 150.0, minutes: Int = 20, perMinute: Double = 2.5): List<Double> =
        List(45) { flat } + List(minutes) { flat - perMinute * (it + 1) }

    @Test fun `a long unbroken fall after a quiet spell ending low is suspected`() {
        val s = CompressionDetector.suspect(series(fall()))
        assertThat(s).isNotNull()
        assertThat(s!!.fromMgdl).isEqualTo(150.0)
        assertThat(s.toMgdl).isEqualTo(100.0)
        assertThat(s.fallMinutes).isAtLeast(15L)
    }

    @Test fun `a short sharp fall is not suspected`() {
        assertThat(CompressionDetector.suspect(series(fall(flat = 140.0, minutes = 10, perMinute = 4.0)))).isNull()
    }

    @Test fun `a long but shallow fall is not suspected`() {
        assertThat(CompressionDetector.suspect(series(fall(flat = 120.0, minutes = 25, perMinute = 0.6)))).isNull()
    }

    @Test fun `a fall that is still above 6 mmol is not suspected`() {
        assertThat(CompressionDetector.suspect(series(fall(flat = 190.0)))).isNull()
    }

    @Test fun `a repeating swing is not suspected`() {
        // 8 Oct 12:00-12:25 shape: about 16 minutes between troughs, 1.5 mmol each way, ending near the bottom of a swing.
        val swing = List(90) { 90.0 + 14.0 * sin(2 * PI * it / 16.0) }
        assertThat(CompressionDetector.suspect(series(swing))).isNull()
    }

    @Test fun `a noisy 30 minutes before the fall is not suspected`() {
        val noisy = List(15) { 150.0 } + List(10) { 150.0 + 2.5 * (it + 1) } + List(10) { 175.0 - 2.5 * (it + 1) } +
            List(10) { 150.0 } + List(20) { 150.0 - 2.5 * (it + 1) }
        assertThat(CompressionDetector.suspect(series(noisy))).isNull()
    }

    @Test fun `too little data is not suspected`() {
        assertThat(CompressionDetector.suspect(series(listOf(150.0, 150.0, 100.0)))).isNull()
    }

    private val lowThenRebound = series(List(30) { 100.0 })

    @Test fun `a sharp rise from near the low confirms`() {
        val readings = lowThenRebound + listOf(
            30 * minute to 102.0, 31 * minute to 101.0, 32 * minute to 110.0, 33 * minute to 125.0, 34 * minute to 135.0
        )
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isTrue()
    }

    @Test fun `a slow recovery does not confirm`() {
        val readings = lowThenRebound + (0 until 12).map { (30 + it) * minute to 100.0 + it * 1.5 }
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }

    @Test fun `a rise that starts well above the low does not confirm`() {
        // The low (90) is more than 10 minutes before the rise, which starts at 110, 20 above it.
        val readings = listOf(20 * minute to 90.0) + (21..40).map { it * minute to 110.0 } +
            listOf(41 * minute to 125.0, 42 * minute to 136.0)
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }
}
