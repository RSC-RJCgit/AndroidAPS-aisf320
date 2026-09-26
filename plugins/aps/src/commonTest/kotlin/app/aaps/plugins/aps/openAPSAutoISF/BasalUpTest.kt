package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasalUpTest {

    @Test
    fun afternoonRiseOnALowProfileFires() {
        assertTrue(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 4.0,
                profilePercent = 100,
                minuteOfDay = 13 * 60,
                steps60 = 100,
                steps30 = 40,
                podHours = 20.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = false,
            )
        )
    }

    @Test
    fun beforeSevenAndAFlatDeltaStayClosed() {
        assertFalse(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 4.0,
                profilePercent = 100,
                minuteOfDay = 6 * 60 + 59,
                steps60 = 0,
                steps30 = 0,
                podHours = 80.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = true,
            )
        )
        assertFalse(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 3.5,
                profilePercent = 100,
                minuteOfDay = 8 * 60,
                steps60 = 0,
                steps30 = 0,
                podHours = 1.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = true,
            )
        )
    }
}
