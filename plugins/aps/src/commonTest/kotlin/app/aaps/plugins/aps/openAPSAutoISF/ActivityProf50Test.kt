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
    fun aSteadyActivityTargetAt100PercentFires() {
        assertTrue(cut())
    }

    @Test
    fun aRiseOrAHighOrTheWrongTargetStaysClosed() {
        assertFalse(cut(bg = 153.2))
        assertFalse(cut(delta = 1.9))
        assertFalse(cut(lowTargetMgdl = 100.0))
        assertFalse(cut(lowTargetMgdl = null))
    }

    @Test
    fun aBolusMarkOrAProfileNotAt100StaysClosed() {
        assertFalse(cut(profileIsBolus = true))
        assertFalse(cut(profilePercent = 50))
        assertFalse(cut(ready = false))
    }

    private fun cut(
        ready: Boolean = true,
        profilePercent: Int = 100,
        profileIsBolus: Boolean = false,
        lowTargetMgdl: Double? = 6.8 * 18.0,
        bg: Double = 140.0,
        delta: Double = 0.0,
    ) = activityProf50ShouldFire(ready, profilePercent, profileIsBolus, lowTargetMgdl, bg, delta)
}
