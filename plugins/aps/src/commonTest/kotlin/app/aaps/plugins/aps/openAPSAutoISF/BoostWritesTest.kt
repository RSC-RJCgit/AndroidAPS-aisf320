package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostWritesTest {

    @Test
    fun aStrongBoostRaisesIobThresholdProfilePercentAndPostMealWeight() {
        val raise = boostRaises(strong = true, caution = false)
        assertEquals(71, raise.iobTh)
        assertEquals(110, raise.profilePercent)
        assertEquals(2, raise.profileMinutes)
        assertTrue(raise.raisePpWeight)
    }

    @Test
    fun cautionSkipsTheProfilePercentRaise() {
        val raise = boostRaises(strong = true, caution = true)
        assertEquals(71, raise.iobTh)
        assertNull(raise.profilePercent)
        assertTrue(raise.raisePpWeight)
    }

    @Test
    fun aMildBoostRaisesOnlyThePostMealWeight() {
        val raise = boostRaises(strong = false, caution = false)
        assertNull(raise.iobTh)
        assertNull(raise.profilePercent)
        assertTrue(raise.raisePpWeight)
    }

    @Test
    fun aTargetAtOrBelowTheBaselineIsNotApplied() {
        assertEquals(71, raiseAbove(71, 70))
        assertNull(raiseAbove(71, 71))
        assertNull(raiseAbove(71, 100))
        assertNull(raiseAbove(null, 70))
    }
}
