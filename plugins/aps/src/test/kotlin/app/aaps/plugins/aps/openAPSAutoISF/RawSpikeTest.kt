package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The raw values are 28 Oct Live (Client mirror), sensor day 4, in mmol/L as the AIV export shows them. */
class RawSpikeTest {

    private val min = 60_000L
    private fun series(vararg mmol: Pair<Int, Double>) = mmol.map { (minute, value) -> minute * min to value * 18.016 }

    @Test fun `the 22_51 jump that fell back is a spike`() {
        // 22:46 11.1, 22:51 12.0, 22:56 9.5, 22:58 8.5
        val spike = findRawSpike(series(0 to 11.1, 5 to 12.0, 10 to 9.5, 12 to 8.5))
        assertThat(spike).isNotNull()
        assertThat(spike!!.peakAt).isEqualTo(5 * min)
        assertThat(spike.fallMgdl / 18.016).isWithin(0.01).of(-3.5)
    }

    /** A real rise keeps going: the 21:00-21:15 rebound from the low rose 1.4 a time and did not fall back */
    @Test fun `a fast rise that does not fall back is not a spike`() {
        assertThat(findRawSpike(series(0 to 4.3, 5 to 4.9, 10 to 6.2, 15 to 7.5, 21 to 8.1))).isNull()
    }

    @Test fun `a fall of less than 1_5 is not a spike`() {
        assertThat(findRawSpike(series(0 to 11.1, 5 to 12.0, 10 to 10.6))).isNull()
    }

    @Test fun `a jump of less than 0_9 is not a spike`() {
        assertThat(findRawSpike(series(0 to 11.2, 5 to 12.0, 10 to 9.5))).isNull()
    }

    /** 21:15: raw +1.39 in 5 min while BG rose 0.74 */
    @Test fun `raw rising more than twice as fast as BG`() {
        assertThat(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 0.6 * 18.016)).isTrue()
    }

    /** Calibration alone makes raw rise 1.33x as fast as BG */
    @Test fun `raw rising in step with BG is not too fast`() {
        assertThat(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 1.05 * 18.016)).isFalse()
    }

    @Test fun `nothing while BG is not rising`() {
        assertThat(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 0.0)).isFalse()
    }

    /** Already falling, a drop of 1.0 in 5 min, then back up 1.6 */
    @Test fun `a drop during a fall that bounces back is a dip`() {
        val dip = findRawDip(series(0 to 7.2, 5 to 7.0, 10 to 6.0, 15 to 7.6))
        assertThat(dip).isNotNull()
        assertThat(dip!!.bottomAt).isEqualTo(10 * min)
    }

    @Test fun `a drop out of a flat or rising stretch is not a dip`() {
        assertThat(findRawDip(series(0 to 6.9, 5 to 7.0, 10 to 6.0, 15 to 7.6))).isNull()
    }

    @Test fun `a drop that does not bounce back is not a dip`() {
        assertThat(findRawDip(series(0 to 7.2, 5 to 7.0, 10 to 6.0, 15 to 7.0))).isNull()
    }

    @Test fun `22_30 to 07_30 are compression hours`() {
        assertThat(inCompressionHours(23 * 60)).isTrue()
        assertThat(inCompressionHours(7 * 60 + 29)).isTrue()
        assertThat(inCompressionHours(7 * 60 + 30)).isFalse()
        assertThat(inCompressionHours(22 * 60 + 29)).isFalse()
    }
}
