package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DuraCap (2026-10-21): no SMB and no high temp basal while dura alone pushes insulin at a flat BG. */
class DuraFlatCapTest {

    private val minute = 60_000L
    private val now = 1_000 * minute

    private fun factors(dura: Double = 2.6, acce: Double = 1.1, bg: Double = 1.12, pp: Double = 1.07) =
        AutoIsfFactors().apply {
            duraIsf = dura
            acceIsf = acce
            bgIsf = bg
            ppIsf = pp
        }

    @Test
    fun the21OctoberMorningIsCapped() {
        // 08:01: BG 6.4, IOB 1.06, no carbs, flat since 07:40
        assertTrue(duraFlatCapRule(factors(), 115.0, 1.06, 0.0, now - 21 * minute, now))
    }

    @Test
    fun aBgOf68OrMoreIsNotCapped() {
        assertFalse(duraFlatCapRule(factors(), 122.5, 1.2, 0.0, now - 30 * minute, now))
    }

    @Test
    fun whileWalkingTheLimitIs90() {
        // 24 Oct 10:20: BG 7.5, IOB 2.2, flat, walking
        assertTrue(duraFlatCapRule(factors(dura = 1.9), 135.0, 2.2, 0.0, now - 20 * minute, now, walking = true))
        assertFalse(duraFlatCapRule(factors(dura = 1.9), 135.0, 2.2, 0.0, now - 20 * minute, now, walking = false))
        assertFalse(duraFlatCapRule(factors(dura = 1.9), 162.2, 2.2, 0.0, now - 20 * minute, now, walking = true))
    }

    @Test
    fun lessThan15FlatMinutesIsNotCapped() {
        assertFalse(duraFlatCapRule(factors(), 115.0, 1.2, 0.0, now - 10 * minute, now))
        assertFalse(duraFlatCapRule(factors(), 115.0, 1.2, 0.0, 0L, now))
    }

    @Test
    fun lowIobOrCarbsAreNotCapped() {
        assertFalse(duraFlatCapRule(factors(), 115.0, 0.9, 0.0, now - 30 * minute, now))
        assertFalse(duraFlatCapRule(factors(), 115.0, 1.2, 5.0, now - 30 * minute, now))
    }

    @Test
    fun anotherLargerFactorOrASmallDuraIsNotCapped() {
        assertFalse(duraFlatCapRule(factors(dura = 1.5, acce = 2.0), 115.0, 1.2, 0.0, now - 30 * minute, now))
        assertFalse(duraFlatCapRule(factors(dura = 1.2), 115.0, 1.2, 0.0, now - 30 * minute, now))
    }
}
