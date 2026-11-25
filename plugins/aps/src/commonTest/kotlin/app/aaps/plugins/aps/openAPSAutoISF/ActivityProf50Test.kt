package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityProf50Test {

    @Test
    fun theActivityTargetMatchesEitherStoredForm() {
        assertTrue(isActivityTempTarget(6.8 * 18.0))
        assertTrue(isActivityTempTarget(6.8 * Constants.MMOLL_TO_MGDL))
        assertFalse(isActivityTempTarget(122.5))
    }

    @Test
    fun anActivityTargetAt100PercentFires() {
        assertTrue(cut())
    }

    @Test
    fun aRisingDeltaNoLongerBlocks() {
        // The delta check was dropped on 2026-11-25: only BG <= 8.5 mmol remains.
        assertTrue(cut(bg = 153.1))
    }

    @Test
    fun aHighOrNoActivityTargetStaysClosed() {
        assertFalse(cut(bg = 153.2))
        assertFalse(cut(activityTt = false))
    }

    @Test
    fun aBolusMarkAProfileNotAt100OrAnActiveDropStaysClosed() {
        assertFalse(cut(profileIsBolus = true))
        assertFalse(cut(profilePercent = 50))
        assertFalse(cut(dropActive = true))
        assertFalse(cut(ready = false))
    }

    private fun cut(
        ready: Boolean = true,
        profilePercent: Int = 100,
        profileIsBolus: Boolean = false,
        dropActive: Boolean = false,
        activityTt: Boolean = true,
        bg: Double = 140.0,
    ) = activityProf50ShouldFire(ready, profilePercent, profileIsBolus, dropActive, activityTt, bg)
}
