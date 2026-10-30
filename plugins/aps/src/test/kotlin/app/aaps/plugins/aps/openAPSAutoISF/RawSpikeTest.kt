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

    /** minute, BG and raw in mmol/L */
    private fun readings(vararg rows: Triple<Int, Double, Double>) = rows.map { (minute, bg, raw) -> Triple(minute * min, bg * 18.016, raw * 18.016) }

    // BG already falling, raw drops 1.8 in 5 min to 2.1 under BG, and stays there for 10 min
    private val dipRows = arrayOf(Triple(0, 7.8, 7.5), Triple(5, 7.6, 7.0), Triple(10, 7.3, 5.2), Triple(15, 7.0, 5.4), Triple(20, 6.8, 5.5))
    private fun dipWith(index: Int, row: Triple<Int, Double, Double>) = dipRows.copyOf().also { it[index] = row }

    @Test fun `too low too quickly and staying there is a dip`() {
        val dip = findRawDip(readings(*dipRows))
        assertThat(dip).isNotNull()
        assertThat(dip!!.bottomAt).isEqualTo(10 * min)
        assertThat(dip.underBgMgdl / 18.016).isWithin(0.01).of(2.1)
    }

    @Test fun `a drop that comes back up is not a dip`() {
        assertThat(findRawDip(readings(*dipWith(3, Triple(15, 7.0, 6.5))))).isNull()
    }

    @Test fun `a drop that stays near BG is not a dip`() {
        assertThat(findRawDip(readings(*dipWith(2, Triple(10, 5.8, 5.2))))).isNull()
    }

    @Test fun `a drop while BG was not falling is not a dip`() {
        assertThat(findRawDip(readings(*dipWith(0, Triple(0, 7.4, 7.5))))).isNull()
    }

    @Test fun `a dip needs the full 10 minutes after it`() {
        assertThat(findRawDip(readings(*dipRows.copyOfRange(0, 4)))).isNull()
    }

    @Test fun `22_30 to 07_30 are compression hours`() {
        assertThat(inCompressionHours(23 * 60)).isTrue()
        assertThat(inCompressionHours(7 * 60 + 29)).isTrue()
        assertThat(inCompressionHours(7 * 60 + 30)).isFalse()
        assertThat(inCompressionHours(22 * 60 + 29)).isFalse()
    }

    // Shower12's fallback (2026-10-30): the jump without the fall after it, in the last 5 min only
    @Test fun `a raw jump of 0_9 or more counts before it falls back`() {
        // 9 Sep 08:12 kind: raw +1.7 in 5 min, nothing after it yet
        assertThat(findRawJump(series(0 to 6.0, 5 to 7.7), now = 5 * min)!! / 18.016).isWithin(0.01).of(1.7)
    }

    @Test fun `a raw jump under 0_9 or older than 5 min does not count`() {
        assertThat(findRawJump(series(0 to 6.0, 5 to 6.8), now = 5 * min)).isNull()
        assertThat(findRawJump(series(0 to 6.0, 5 to 7.7), now = 11 * min)).isNull()
    }
}
