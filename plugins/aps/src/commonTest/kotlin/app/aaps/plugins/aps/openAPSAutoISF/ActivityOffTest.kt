package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityOffTest {

    @Test
    fun aClimbOnTheActivityTargetEndsTheDrop() {
        assertTrue(exit(bg = 171.2))
        assertFalse(exit(bg = 171.1))
        assertFalse(exit(bg = 171.2, activityTt = false))
    }

    @Test
    fun aTargetThatHasEndedCanRestoreTheRoles() {
        assertTrue(exit(activityTt = false, ttRunning = false, bg = 140.0, delta = 1.8, cannulaHours = 3.0))
        assertFalse(exit(activityTt = false, ttRunning = false, bg = 140.0, delta = 1.8, cannulaHours = 2.9))
        assertFalse(exit(activityTt = false, ttRunning = false, bg = 153.2, delta = 1.8, cannulaHours = 3.0))
        assertFalse(exit(activityTt = false, ttRunning = false, bg = 108.0, delta = 1.8, cannulaHours = 3.0))
        assertFalse(exit(activityTt = false, ttRunning = false, bg = 140.0, delta = 1.7, cannulaHours = 3.0))
    }

    @Test
    fun aBolusInTheLastTenMinutesEndsTheDrop() {
        assertTrue(exit(lastBolusMinutes = 10))
        assertFalse(exit(lastBolusMinutes = 11))
        assertFalse(exit(lastBolusMinutes = 10, delayedBolusPending = true))
    }

    @Test
    fun noActiveDropOrAProfileNotAt100StaysClosed() {
        assertFalse(exit(dropActive = false, bg = 171.2))
        assertFalse(exit(profilePercent = 50, bg = 171.2))
        assertFalse(exit(ready = false, bg = 171.2))
    }

    private fun exit(
        ready: Boolean = true,
        dropActive: Boolean = true,
        profilePercent: Int = 100,
        activityTt: Boolean = true,
        ttRunning: Boolean = true,
        bg: Double = 140.0,
        delta: Double = 0.0,
        cannulaHours: Double = 0.0,
        lastBolusMinutes: Int = Int.MAX_VALUE,
        delayedBolusPending: Boolean = false,
    ) = activityOffShouldFire(
        ready,
        dropActive,
        profilePercent,
        activityTt,
        ttRunning,
        bg,
        delta,
        cannulaHours,
        lastBolusMinutes,
        delayedBolusPending,
    )
}
