package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The raw values are 28 Oct Live (Client mirror), sensor day 4, in mmol/L as the AIV export shows them. */
class RawSpikeTest {

    private val min = 60_000L
    private val hour = 60 * min
    private fun series(vararg mmol: Pair<Int, Double>) = mmol.map { (minute, value) -> minute * min to value * 18.016 }

    @Test
    fun theJumpAt2251ThatFellBackIsASpike() {
        // 22:46 11.1, 22:51 12.0, 22:56 9.5, 22:58 8.5
        val spike = assertNotNull(findRawSpike(series(0 to 11.1, 5 to 12.0, 10 to 9.5, 12 to 8.5)))
        assertEquals(5 * min, spike.peakAt)
        assertEquals(-3.5, spike.fallMgdl / 18.016, 0.01)
    }

    /** A real rise keeps going: the 21:00-21:15 rebound from the low rose 1.3 a time and did not fall back */
    @Test
    fun aFastRiseThatDoesNotFallBackIsNotASpike() {
        assertNull(findRawSpike(series(0 to 4.3, 5 to 4.9, 10 to 6.2, 15 to 7.5, 21 to 8.1)))
    }

    @Test
    fun aSmallJumpOrASmallFallIsNotASpike() {
        assertNull(findRawSpike(series(0 to 11.2, 5 to 12.0, 10 to 9.5)))
        assertNull(findRawSpike(series(0 to 11.1, 5 to 12.0, 10 to 10.6)))
    }

    /** 21:15: raw +1.4 in 5 min while BG rose 0.6. Calibration alone makes raw rise 1.33x as fast as BG. */
    @Test
    fun rawRisingMoreThanTwiceAsFastAsBg() {
        assertTrue(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 0.6 * 18.016))
        assertFalse(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 1.05 * 18.016))
        assertFalse(rawRisingTooFast(series(0 to 6.2, 5 to 7.6), bgDeltaMgdl = 0.0))
    }

    /** 28 Oct 07:39 came during the morning shower */
    @Test
    fun aSpikeInTheTwoHoursAfterAShowerDoesNotCount() {
        val now = 10 * hour
        assertFalse(rawSpikeCounts(spikeAt = now - 30 * min, now = now, showerMinutesAgo = 60))
        assertTrue(rawSpikeCounts(spikeAt = now - 30 * min, now = now, showerMinutesAgo = 200))
        assertTrue(rawSpikeCounts(spikeAt = now - 30 * min, now = now, showerMinutesAgo = null))
    }

    @Test
    fun theWindowLastsSixHours() {
        val spikeAt = 10 * hour
        assertTrue(rawSpikeWindowOpen(spikeAt, spikeAt + 5 * hour))
        assertFalse(rawSpikeWindowOpen(spikeAt, spikeAt + 6 * hour))
        assertFalse(rawSpikeWindowOpen(0L, spikeAt))
    }

    /** 28 Oct was day 4: NewDay3 now runs to the end of it */
    @Test
    fun day4UsesTheNewDay3Tier() {
        assertEquals("NewDay3", oldSensorTier(sensorDays = 3.4, podHours = 20.0, slopeBase = 0.75, offsetBase = 1.4)?.name)
        assertNull(oldSensorTier(sensorDays = 4.1, podHours = 20.0, slopeBase = 0.75, offsetBase = 1.4))
    }

    /** minute, BG and raw in mmol/L */
    private fun readings(vararg rows: Triple<Int, Double, Double>) = rows.map { (minute, bg, raw) -> Triple(minute * min, bg * 18.016, raw * 18.016) }

    // BG already falling, raw drops 1.8 in 5 min to 2.1 under BG, and stays there for 10 min
    private val dipRows = arrayOf(Triple(0, 7.8, 7.5), Triple(5, 7.6, 7.0), Triple(10, 7.3, 5.2), Triple(15, 7.0, 5.4), Triple(20, 6.8, 5.5))
    private fun dipWith(index: Int, row: Triple<Int, Double, Double>) = dipRows.copyOf().also { it[index] = row }

    @Test
    fun tooLowTooQuicklyAndStayingThereIsADip() {
        val dip = assertNotNull(findRawDip(readings(*dipRows)))
        assertEquals(10 * min, dip.bottomAt)
        assertEquals(2.1, dip.underBgMgdl / 18.016, 0.01)
    }

    @Test
    fun aDropThatComesBackUpOrStaysNearBgIsNotADip() {
        assertNull(findRawDip(readings(*dipWith(3, Triple(15, 7.0, 6.5)))))
        assertNull(findRawDip(readings(*dipWith(2, Triple(10, 5.8, 5.2)))))
    }

    @Test
    fun aDipNeedsBgFallingAndTheFullTenMinutesAfter() {
        assertNull(findRawDip(readings(*dipWith(0, Triple(0, 7.4, 7.5)))))
        assertNull(findRawDip(readings(*dipRows.copyOfRange(0, 4))))
    }

    @Test
    fun theCompressionHoursAre2230To0730() {
        assertTrue(inCompressionHours(23 * 60))
        assertTrue(inCompressionHours(7 * 60 + 29))
        assertFalse(inCompressionHours(7 * 60 + 30))
        assertFalse(inCompressionHours(22 * 60 + 29))
    }

    // Shower12's fallback (2026-10-30): the jump without the fall after it, in the last 5 min only
    @Test
    fun aRawJumpOf09OrMoreCountsBeforeItFallsBack() {
        // 9 Sep 08:12 kind: raw +1.7 in 5 min, nothing after it yet
        assertEquals(1.7, assertNotNull(findRawJump(series(0 to 6.0, 5 to 7.7), now = 5 * min)) / 18.016, 0.01)
    }

    @Test
    fun aRawJumpUnder09OrOlderThan5MinDoesNotCount() {
        assertNull(findRawJump(series(0 to 6.0, 5 to 6.8), now = 5 * min))
        assertNull(findRawJump(series(0 to 6.0, 5 to 7.7), now = 11 * min))
    }
}
