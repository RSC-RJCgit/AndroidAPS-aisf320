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
}
