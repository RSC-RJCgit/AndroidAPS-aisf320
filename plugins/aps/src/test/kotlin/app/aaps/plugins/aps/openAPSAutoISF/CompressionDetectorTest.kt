package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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

    @Test fun `a flat spell then a steep fall is suspected`() {
        val s = CompressionDetector.suspect(compressionFall)
        assertThat(s).isNotNull()
        assertThat(s!!.toMgdl).isEqualTo(120.0)
        assertThat(s.steepestMgdlPerMin).isAtMost(-4.0)
    }

    @Test fun `a gradual fall is not suspected`() {
        assertThat(CompressionDetector.suspect(series(List(30) { 150.0 - it * 1.5 }))).isNull()
    }

    @Test fun `a fall that follows a trend is not suspected`() {
        assertThat(CompressionDetector.suspect(series(List(24) { if (it < 12) 190.0 - it * 3.0 else 154.0 - (it - 12) * 4.5 }))).isNull()
    }

    @Test fun `too little data is not suspected`() {
        assertThat(CompressionDetector.suspect(series(listOf(150.0, 150.0, 120.0)))).isNull()
    }

    @Test fun `a sharp rise from near the low confirms`() {
        val readings = compressionFall + listOf(
            24 * minute to 118.0, 25 * minute to 117.0, 26 * minute to 125.0, 27 * minute to 140.0, 28 * minute to 150.0
        )
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isTrue()
    }

    @Test fun `a slow recovery does not confirm`() {
        val readings = compressionFall + (0 until 12).map { (24 + it) * minute to 120.0 + it * 1.5 }
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }

    @Test fun `a rise that starts well above the low does not confirm`() {
        // The low (90) is more than 10 minutes before the rise, which starts at 110, 20 above it.
        val readings = listOf(20 * minute to 90.0) + (21..40).map { it * minute to 110.0 } +
            listOf(41 * minute to 125.0, 42 * minute to 136.0)
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }
}
