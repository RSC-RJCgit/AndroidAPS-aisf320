package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighNightTest {

    @Test
    fun aConfirmedRiseFiresFrom2Until7() {
        assertTrue(night())
        assertTrue(night(minuteOfDay = 2 * 60))
        assertTrue(night(minuteOfDay = 6 * 60 + 59))
    }

    @Test
    fun theWindowIsClosedBefore2AndAt7() {
        assertFalse(night(minuteOfDay = 1 * 60 + 59))
        assertFalse(night(minuteOfDay = 7 * 60))
    }

    @Test
    fun oneFlatDeltaOrGlucoseAt65mmolStaysClosed() {
        assertFalse(night(bg = 117.1))
        assertFalse(night(delta = 1.7))
        assertFalse(night(shortDelta = 1.7))
        assertFalse(night(longDelta = 1.7))
    }

    @Test
    fun aTempTargetOrSteroidsOrARecentRunStaysClosed() {
        assertFalse(night(tempTargetSet = true))
        assertFalse(night(steroidsOff = false))
        assertFalse(night(ready = false))
    }

    private fun night(
        ready: Boolean = true,
        tempTargetSet: Boolean = false,
        steroidsOff: Boolean = true,
        minuteOfDay: Int = 3 * 60,
        bg: Double = 130.0,
        delta: Double = 1.8,
        shortDelta: Double = 1.8,
        longDelta: Double = 1.8,
    ) = highNightShouldFire(ready, tempTargetSet, steroidsOff, minuteOfDay, bg, delta, shortDelta, longDelta)
}
