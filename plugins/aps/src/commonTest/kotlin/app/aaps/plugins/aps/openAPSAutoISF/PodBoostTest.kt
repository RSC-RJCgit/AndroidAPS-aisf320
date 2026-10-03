package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodBoostTest {

    @Test
    fun podChangeFiresOnAFreshPodInTheAfternoon() {
        assertTrue(
            podChangeHighPp130ShouldFire(
                ready = true,
                livePump = true,
                profilePercent = 100,
                libreOver12Recent = true,
                profilePp130 = true,
                minuteOfDay = 14 * 60,
                bg = 160.0,
                delta = 4.0,
                cannulaHours = 1.0,
            )
        )
    }

    @Test
    fun podChangeStaysClosedOnAVirtualPump() {
        assertFalse(
            podChangeHighPp130ShouldFire(
                ready = true,
                livePump = false,
                profilePercent = 100,
                libreOver12Recent = true,
                profilePp130 = true,
                minuteOfDay = 14 * 60,
                bg = 160.0,
                delta = 4.0,
                cannulaHours = 1.0,
            )
        )
    }

    @Test
    fun pp130OffFiresWhenTheBoostHasNoTarget() {
        assertTrue(
            highPp130OffShouldFire(
                ready = true,
                profilePercent = 130,
                bg = 200.0,
                delta = 2.0,
                ttActive = false,
            )
        )
    }

    @Test
    fun recentPodFiresWithCarbsOnANewPod() {
        assertTrue(
            recentPodShouldFire(
                ready = true,
                livePump = true,
                profilePercent = 100,
                ttActive = false,
                libreOver12Recent = true,
                bg = 120.0,
                delta = 4.0,
                cob = 20.0,
                iob = 2.0,
                minutesSinceBolus = 40,
                cannulaHours = 3.0,
            )
        )
    }

    @Test
    fun recentPodStaysClosedJustAfterAMealBolus() {
        assertFalse(
            recentPodShouldFire(
                ready = true,
                livePump = true,
                profilePercent = 100,
                ttActive = false,
                libreOver12Recent = true,
                bg = 120.0,
                delta = 4.0,
                cob = 20.0,
                iob = 2.0,
                minutesSinceBolus = 8,
                cannulaHours = 3.0,
            )
        )
    }
}
