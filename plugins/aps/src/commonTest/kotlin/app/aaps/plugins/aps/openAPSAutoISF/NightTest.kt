package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightTest {

    @Test
    fun acceUpFiresInTheDaytime() {
        assertTrue(acceUp(minuteOfDay = 10 * 60))
        assertFalse(acceUp(minuteOfDay = 3 * 60))
    }

    @Test
    fun exerciseLimitNeedsAFastRiseAndManySteps() {
        assertTrue(exerciseLimitShouldFire(ready = true, bg = 130.0, delta = 8.0, steps60 = 1000))
        assertFalse(exerciseLimitShouldFire(ready = true, bg = 130.0, delta = 8.0, steps60 = 999))
    }

    @Test
    fun nightCeilingLowersBoth() {
        val cap = nightIobCeiling(
            ready = true,
            minuteOfDay = 3 * 60,
            steps60 = 0,
            iobTh = 70,
            acce = 0.50,
            cobSustained = false,
        )
        assertEquals(22, cap?.iob)
        assertEquals(0.35, cap?.acce)
    }

    @Test
    fun nightCeilingRelaxesIobOnly() {
        val cap = nightIobCeiling(
            ready = true,
            minuteOfDay = 3 * 60,
            steps60 = 0,
            iobTh = 70,
            acce = 0.50,
            cobSustained = true,
        )
        assertEquals(35, cap?.iob)
        assertEquals(0.35, cap?.acce)
    }

    @Test
    fun nightCeilingYieldsToSteps() {
        assertNull(
            nightIobCeiling(
                ready = true,
                minuteOfDay = 5 * 60,
                steps60 = 100,
                iobTh = 70,
                acce = 0.50,
                cobSustained = false,
            )
        )
    }

    @Test
    fun nightAcceStaysClosedOnceTheBandIs22() {
        assertTrue(nightAcce(iobTh = 30))
        assertFalse(nightAcce(iobTh = 22))
    }

    @Test
    fun twilightNeedsAQuietFall() {
        assertTrue(
            twilightTh15ShouldFire(
                ready = true,
                minuteOfDay = 7 * 60,
                steps60 = 0,
                bg = 110.0,
                ttActive = false,
                iobTh = 22,
                steroidsOff = true,
                delta = 0.0,
            )
        )
    }

    @Test
    fun semiTwilightHighGlucoseBlock() {
        assertEquals("2", semiTwilightBlock(
            ready = true,
            minuteOfDay = 4 * 60,
            steps180 = 0,
            bg = 190.0,
            delta = 0.0,
            shortDelta = 0.0,
            longDelta = 0.0,
            ttActive = false,
            iobTh = 22,
            acce = 0.02,
            steroidsOff = true,
        ))
    }

    private fun acceUp(minuteOfDay: Int): Boolean =
        acceUpShouldFire(
            ready = true,
            minuteOfDay = minuteOfDay,
            acce = 0.45,
            bg = 120.0,
            profilePercent = 100,
            ttActive = false,
            noMjRemains = true,
            steroidsOn = false,
        )

    private fun nightAcce(iobTh: Int): Boolean =
        nightAcceShouldFire(
            ready = true,
            minuteOfDay = 2 * 60,
            iobTh = iobTh,
            cob = 0.0,
            ttActive = false,
            bg = 100.0,
            steroidsOff = true,
            acce = 0.50,
        )
}
