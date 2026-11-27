package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IobThDaytimeFloorTest {

    @Test
    fun aLowDaytimeThresholdReturnsTo70WhenGlucoseIsSteady() {
        assertTrue(floor())
        assertTrue(floor(minuteOfDay = 8 * 60, iobTh = 18, bg = 117.1, delta = 0.0))
        assertTrue(floor(minuteOfDay = 21 * 60 + 59))
    }

    @Test
    fun theWindowIsClosedBefore8AndAt22() {
        assertFalse(floor(minuteOfDay = 7 * 60 + 59))
        assertFalse(floor(minuteOfDay = 22 * 60))
    }

    @Test
    fun aThresholdAlreadyAbove50Stays() {
        assertFalse(floor(iobTh = 51))
    }

    @Test
    fun aLowOrAFallOrATempTargetKeepsItClosed() {
        assertFalse(floor(bg = 117.0))
        assertFalse(floor(delta = -0.1))
        assertFalse(floor(tempTargetSet = true))
        assertFalse(floor(steroidsOff = false))
        assertFalse(floor(lowBgClear = false))
        assertFalse(floor(profilePercent = 110))
        assertFalse(floor(ready = false))
    }

    private fun floor(
        ready: Boolean = true,
        minuteOfDay: Int = 12 * 60,
        profilePercent: Int = 100,
        lowBgClear: Boolean = true,
        steroidsOff: Boolean = true,
        tempTargetSet: Boolean = false,
        iobTh: Int = 50,
        bg: Double = 120.0,
        delta: Double = 1.0,
    ) = iobThDaytimeFloorShouldFire(
        ready, minuteOfDay, profilePercent, lowBgClear, steroidsOff, tempTargetSet, iobTh, bg, delta
    )

    @Test
    fun steroidFloorRestoresTheTierLevel() {
        fun t(level: Int?, iobTh: Int, minute: Int = 12 * 60, bg: Double = 140.0, delta: Double = 1.0, pct: Int = 100, tt: Boolean = false) =
            steroidIobThFloorTarget(true, minute, pct, lowBgClear = true, tempTargetSet = tt, steroidLevel = level, iobTh = iobTh, bg = bg, delta = delta)
        kotlin.test.assertEquals(81, t(81, 50))          // Steroid 150 left at 50 after a Set50
        kotlin.test.assertEquals(null, t(81, 81))        // already there
        kotlin.test.assertEquals(null, t(null, 50))      // no Steroid profile
        kotlin.test.assertEquals(null, t(81, 50, minute = 23 * 60))  // night
        kotlin.test.assertEquals(null, t(81, 50, bg = 110.0))        // under 6.5
        kotlin.test.assertEquals(null, t(81, 50, delta = -0.5))      // falling
        kotlin.test.assertEquals(null, t(81, 50, pct = 50))          // hypo 50% running
        kotlin.test.assertEquals(null, t(81, 50, tt = true))         // TT set
    }
}
