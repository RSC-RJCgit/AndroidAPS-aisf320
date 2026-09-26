package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class High6Test {

    @Test
    fun high6FiresOnASlowDaytimeRiseWithALowTarget() {
        assertTrue(
            high6ppShouldFire(
                ready = true,
                profilePercent = 100,
                minuteOfDay = 11 * 60,
                libreOver12Recent = true,
                bg = 150.0,
                delta = 3.0,
                shortDelta = 1.0,
                longDelta = 1.0,
                ttLowMgdl = 72.0,
                cob = 0.0,
            )
        )
    }

    @Test
    fun high6StaysClosedWithoutARecentLibreHigh() {
        assertFalse(
            high6ppShouldFire(
                ready = true,
                profilePercent = 100,
                minuteOfDay = 11 * 60,
                libreOver12Recent = false,
                bg = 150.0,
                delta = 3.0,
                shortDelta = 1.0,
                longDelta = 1.0,
                ttLowMgdl = 72.0,
                cob = 0.0,
            )
        )
    }

    @Test
    fun high6OffFiresWhenTheBoostHasNoTarget() {
        assertTrue(
            high6ppOffShouldFire(
                ready = true,
                profilePercent = 120,
                minuteOfDay = 15 * 60,
                bg = 160.0,
                delta = 4.0,
                ttActive = false,
            )
        )
    }

    @Test
    fun oldPodFiresOnAStaleCannula() {
        assertTrue(
            highOldPodShouldFire(
                ready = true,
                profilePercent = 100,
                ttActive = false,
                noMjRemains = true,
                bg = 190.0,
                delta = 3.0,
                shortDelta = 2.0,
                longDelta = 1.0,
                minutesSinceBolus = 120,
                cannulaHours = 70.0,
            )
        )
    }

    @Test
    fun oldPodStaysClosedSoonAfterAMealBolus() {
        assertFalse(
            highOldPodShouldFire(
                ready = true,
                profilePercent = 100,
                ttActive = false,
                noMjRemains = true,
                bg = 190.0,
                delta = 3.0,
                shortDelta = 2.0,
                longDelta = 1.0,
                minutesSinceBolus = 30,
                cannulaHours = 70.0,
            )
        )
    }
}
