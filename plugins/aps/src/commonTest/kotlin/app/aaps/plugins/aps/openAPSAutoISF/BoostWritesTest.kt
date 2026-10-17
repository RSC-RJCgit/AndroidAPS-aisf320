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

    @Test
    fun theDeliveryRatioAddsTheRiseOnTopOfTheMildBase() {
        assertEquals(0.23, boostedDeliveryRatio(0.20, strong = true, caution = false), 0.0001)
        assertEquals(0.215, boostedDeliveryRatio(0.20, strong = true, caution = true), 0.0001)
        assertEquals(0.35, boostedDeliveryRatio(0.20, strong = false, caution = false), 0.0001)
        assertEquals(0.275, boostedDeliveryRatio(0.20, strong = false, caution = true), 0.0001)
    }

    @Test
    fun theActivityFactorIsZeroAtHighIobAndHalfWhileWalking() {
        assertEquals(0.0, activityFactor(iob = 3.8, maxIob = 9.5, steps5 = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.5, activityFactor(iob = 2.0, maxIob = 9.5, steps5 = 0, steps30 = 0, steps60 = 301))
        assertEquals(0.5, activityFactor(iob = 2.0, maxIob = 9.5, steps5 = 21, steps30 = 0, steps60 = 0))
        assertEquals(1.0, activityFactor(iob = 2.0, maxIob = 9.5, steps5 = 20, steps30 = 100, steps60 = 300))
        assertEquals(0.275, boostedDeliveryRatio(0.20, strong = false, caution = false, activity = 0.5), 0.0001)
        assertNull(activityFactorNote(1.0))
        assertEquals("ActF.5", activityFactorNote(0.5))
        assertEquals("ActF0", activityFactorNote(0.0))
    }
}
