package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitsTest {

    @Test
    fun batteryRecoveryNeedsTheSafetyProfile() {
        assertTrue(batteryOver1ShouldFire(ready = true, running = "Current Profile50", safetyName = "Current Profile50", batteryPercent = 20))
        assertFalse(batteryOver1ShouldFire(ready = true, running = "Profile100", safetyName = "Current Profile50", batteryPercent = 20))
    }

    @Test
    fun tt57Off2IsTheFirstFullExit() {
        assertEquals("off2", tt57FullExit(
            ready = true,
            ttMgdl = 5.7 * 18.0182,
            bg = 110.0,
            delta = 0.0,
            shortDelta = 0.0,
            longDelta = 0.0,
            iob = 1.0,
            cob = 0.0,
            ukfDelta5 = -9999.0,
            steps15 = 0,
            steps30 = 0,
            steps60 = 0,
        ))
    }

    @Test
    fun tt57LightExitWhenTheFullOnesMiss() {
        assertNull(tt57FullExit(
            ready = true, ttMgdl = 5.7 * 18.0182, bg = 150.0, delta = -20.0, shortDelta = -20.0,
            longDelta = 0.0, iob = 1.0, cob = 0.0, ukfDelta5 = -9999.0, steps15 = 0, steps30 = 0, steps60 = 0,
        ))
        assertEquals("N2", tt57LightExit(ready = true, ttMgdl = 5.7 * 18.0182, bg = 150.0, iob = 1.0, cob = 0.0, mildConfirmed = false))
    }

    @Test
    fun t80NeedsTheExactTargetAndARise() {
        val tt = 8.0 * Constants.MMOLL_TO_MGDL
        assertTrue(t80OffShouldFire(ready = true, ttMgdl = tt, bg = 130.0, delta = 4.0, steps5 = 0, steps15 = 0, steps30 = 0, steps60 = 0))
        assertFalse(t80OffShouldFire(ready = true, ttMgdl = 144.0, bg = 130.0, delta = 4.0, steps5 = 0, steps15 = 0, steps30 = 0, steps60 = 0))
    }

    @Test
    fun activityTargetExitsOnARiseWithCarbs() {
        val tt = 6.8 * 18.0182
        assertEquals("1", activityTtExit(ready = true, ttMgdl = tt, bg = 170.0, delta = 2.0, iob = 1.0, cob = 8.0))
    }

    @Test
    fun carbsStop1NeedsAVeryLowTarget() {
        assertTrue(carbsStop1ShouldFire(ready = true, ttMgdl = 75.0, cob = 12.0, delta = 4.0, iob = 0.2, minuteOfDay = 12 * 60))
        assertFalse(carbsStop1ShouldFire(ready = true, ttMgdl = 90.0, cob = 12.0, delta = 4.0, iob = 0.2, minuteOfDay = 12 * 60))
    }

    @Test
    fun carbsStop57SkipsTheMildHold() {
        assertNull(carbsStop57Block(
            ready = true, ttMgdl = 90.1, cob = 20.0, iob = 3.0, bg = 100.0, delta = 1.0,
            minutesSinceBolus = 30, ownMildTt = true,
        ))
        assertEquals("3", carbsStop57Block(
            ready = true, ttMgdl = 103.0, cob = 0.0, iob = 1.0, bg = 100.0, delta = 0.0,
            minutesSinceBolus = 5, ownMildTt = false,
        ))
    }

    @Test
    fun carbsThOffBlock1OnAFall() {
        assertEquals("1", carbsThOffBlock(
            ready = true, profilePercent = 100, ttActive = false, steroidsOff = true,
            bg = 120.0, delta = -2.0, shortDelta = -2.0, acce = 0.50, iobTh = 80, minutesSinceBolus = 90,
        ))
    }
}
