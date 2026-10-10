package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Extra50Test {

    @Test
    fun aSteepFallDuringMjIsBlock1() {
        assertEquals("1", extra(bg = 150.0, delta = -6.3, shortDelta = -4.5, longDelta = -3.6, mjNotRemaining = true))
    }

    @Test
    fun aModerateFallAt65mmolIsBlock2() {
        assertEquals("2", extra(bg = 117.1, delta = -3.6, shortDelta = -1.8, longDelta = -2.7, mjNotRemaining = true))
    }

    @Test
    fun anOvernightFallIsBlock3WithoutMj() {
        assertEquals(
            "3",
            extra(minuteOfDay = 1 * 60, bg = 135.1, delta = -3.7, shortDelta = -3.7, longDelta = 0.0, mjNotRemaining = false)
        )
        assertNull(extra(minuteOfDay = 5 * 60, bg = 135.1, delta = -3.7, shortDelta = -3.7, longDelta = 0.0))
        assertNull(extra(minuteOfDay = 2 * 60, bg = 135.1, delta = -3.6, shortDelta = -3.6, longDelta = 0.0))
    }

    @Test
    fun blocks1And2StayClosedWhenMjIsResting() {
        assertNull(extra(bg = 100.0, delta = -8.0, shortDelta = -8.0, longDelta = -8.0, mjNotRemaining = false, minuteOfDay = 12 * 60))
    }

    @Test
    fun aProfileNotAt100OrARecentLowStaysClosed() {
        assertNull(extra(profilePercent = 50))
        assertNull(extra(lowBgClear = false))
        assertNull(extra(ready = false))
    }

    private fun extra(
        ready: Boolean = true,
        profilePercent: Int = 100,
        lowBgClear: Boolean = true,
        mjNotRemaining: Boolean = true,
        minuteOfDay: Int = 12 * 60,
        bg: Double = 140.0,
        delta: Double = -7.0,
        shortDelta: Double = -5.0,
        longDelta: Double = -4.0,
    ) = extra50Block(ready, profilePercent, lowBgClear, mjNotRemaining, minuteOfDay, bg, delta, shortDelta, longDelta)
}
