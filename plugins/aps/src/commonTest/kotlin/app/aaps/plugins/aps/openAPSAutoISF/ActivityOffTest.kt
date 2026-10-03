package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityOffTest {

    @Test
    fun aClimbOnTheActivityTargetEndsTheCut() {
        assertTrue(exit(bg = 171.2))
        assertFalse(exit(bg = 171.1))
    }

    @Test
    fun aTargetThatHasEndedCanRestoreTheProfile() {
        assertTrue(
            exit(
                lowTargetMgdl = null,
                bg = 140.0,
                delta = 1.8,
                cannulaHours = 3.0,
            )
        )
        assertFalse(exit(lowTargetMgdl = null, bg = 140.0, delta = 1.8, cannulaHours = 2.9))
        assertFalse(exit(lowTargetMgdl = null, bg = 153.2, delta = 1.8, cannulaHours = 3.0))
        assertFalse(exit(lowTargetMgdl = null, bg = 108.0, delta = 1.8, cannulaHours = 3.0))
        assertFalse(exit(lowTargetMgdl = null, bg = 140.0, delta = 1.7, cannulaHours = 3.0))
    }

    @Test
    fun aBolusInTheLastTenMinutesEndsTheCut() {
        assertTrue(exit(lastBolusMinutes = 10))
        assertFalse(exit(lastBolusMinutes = 11))
        assertFalse(exit(lastBolusMinutes = 10, delayedBolusPending = true))
    }

    @Test
    fun aProfileThatIsNotAt50StaysClosed() {
        assertFalse(exit(profilePercent = 100))
        assertFalse(exit(ready = false))
    }

    private fun exit(
        ready: Boolean = true,
        profilePercent: Int = 50,
        lowTargetMgdl: Double? = 6.8 * 18.0,
        bg: Double = 140.0,
        delta: Double = 0.0,
        cannulaHours: Double = 0.0,
        lastBolusMinutes: Int = Int.MAX_VALUE,
        delayedBolusPending: Boolean = false,
    ) = activityOffShouldFire(
        ready,
        profilePercent,
        lowTargetMgdl,
        bg,
        delta,
        cannulaHours,
        lastBolusMinutes,
        delayedBolusPending,
    )
}
