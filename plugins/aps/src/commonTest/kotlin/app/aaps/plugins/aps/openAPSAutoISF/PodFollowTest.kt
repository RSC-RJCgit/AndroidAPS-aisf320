package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodFollowTest {

    @Test
    fun recentPodOffFiresAfterTheBoostTargetEnds() {
        assertTrue(
            recentPodOffShouldFire(
                ready = true,
                acceWeight = 0.95,
                acceHigh = 0.95,
                ttActive = false,
                podBoostRecent = true,
            )
        )
    }

    @Test
    fun recentPodOffStaysClosedWithoutARecentPodBoost() {
        assertFalse(
            recentPodOffShouldFire(
                ready = true,
                acceWeight = 0.95,
                acceHigh = 0.95,
                ttActive = false,
                podBoostRecent = false,
            )
        )
    }

    @Test
    fun oldPod2NeverFires() {
        assertFalse(oldPod2ShouldFire())
    }

    @Test
    fun pod2FiresInTheNarrowEveningWindow() {
        assertTrue(
            pod2ShouldFire(
                ready = true,
                livePump = true,
                cannulaHours = 78.05,
                minuteOfDay = 20 * 60,
            )
        )
    }

    @Test
    fun pod1StaysClosedOutsideItsHour() {
        assertFalse(
            pod1ShouldFire(
                ready = true,
                livePump = true,
                cannulaHours = 78.5,
                minuteOfDay = 12 * 60,
            )
        )
    }

    @Test
    fun shower12FiresOnAQuietMorningRise() {
        assertTrue(shower12(steps60 = 0))
    }

    @Test
    fun shower12StaysClosedWhenStepsAreUp() {
        assertFalse(shower12(steps60 = 10))
    }

    @Test
    fun shower12OpensUsual2OnlyForARecentMark() {
        assertTrue(shower12OpensUsual2(20))
        assertFalse(shower12OpensUsual2(5))
        assertFalse(shower12OpensUsual2(300))
        assertFalse(shower12OpensUsual2(null))
    }

    @Test
    fun bolus2StaysClosedWhileDisabled() {
        assertFalse(
            bolus2ShouldFire(
                enabled = false,
                ready = true,
                ttActive = false,
                bg = 110.0,
                delta = -6.0,
                shortDelta = -4.0,
                iob = 1.0,
                cob = 12.0,
                minutesSinceBolus = 3,
                mjActive = false,
            )
        )
    }

    @Test
    fun bolus2WouldFireOnAFastFallIfItWereEnabled() {
        assertTrue(
            bolus2ShouldFire(
                enabled = true,
                ready = true,
                ttActive = false,
                bg = 110.0,
                delta = -6.0,
                shortDelta = -4.0,
                iob = 1.0,
                cob = 12.0,
                minutesSinceBolus = 3,
                mjActive = false,
            )
        )
    }

    private fun shower12(steps60: Int): Boolean =
        shower12ShouldFire(
            ready = true,
            profilePercent = 100,
            iobThPercent = 70,
            steroidsOff = true,
            minuteOfDay = 6 * 60,
            bg = 130.0,
            delta = 7.0,
            shortDelta = 5.0,
            steps60 = steps60,
            cob = 0.0,
            minutesSinceBolus = 200,
            ttLowMgdl = null,
        )
}
