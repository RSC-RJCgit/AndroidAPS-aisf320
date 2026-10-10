package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EveningTest {

    @Test
    fun mj4ClearsThatState() {
        assertTrue(mj4ShouldClear(ready = true, mj4 = true))
        assertFalse(mj5ShouldClear(ready = true, mj5 = false))
    }

    @Test
    fun mjOffDaytimeNeedsHighGlucose() {
        assertEquals("1", mjOffBlock(ready = true, mj3 = true, minuteOfDay = 15 * 60, bg = 190.0))
        assertNull(mjOffBlock(ready = true, mj3 = true, minuteOfDay = 15 * 60, bg = 160.0))
    }

    @Test
    fun mjOffMidnightDoesNotNeedGlucose() {
        assertEquals("2", mjOffBlock(ready = true, mj3 = true, minuteOfDay = 30, bg = 100.0))
    }

    @Test
    fun earlyDawnFiresOnASlowClimb() {
        assertTrue(
            earlyDawnShouldFire(
                ready = true,
                minuteOfDay = 5 * 60,
                ttActive = false,
                bg = 120.0,
                delta = 1.0,
                shortDelta = 1.0,
                longDelta = 1.0,
            )
        )
    }

    @Test
    fun earlyDawnStaysClosedWhenATargetIsOn() {
        assertFalse(
            earlyDawnShouldFire(
                ready = true,
                minuteOfDay = 5 * 60,
                ttActive = true,
                bg = 120.0,
                delta = 1.0,
                shortDelta = 1.0,
                longDelta = 1.0,
            )
        )
    }

    @Test
    fun eveningCeilingLowersTo45() {
        assertEquals(45, eveningIobCap(ready = true, minuteOfDay = 22 * 60 + 30, noMjRemains = true, iobTh = 70, cobSustained = false))
    }

    @Test
    fun eveningCeilingRelaxesTo60AfterSustainedCarbs() {
        assertEquals(60, eveningIobCap(ready = true, minuteOfDay = 23 * 60, noMjRemains = true, iobTh = 70, cobSustained = true))
        assertNull(eveningIobCap(ready = true, minuteOfDay = 23 * 60, noMjRemains = true, iobTh = 50, cobSustained = true))
    }

    @Test
    fun eveningThBlock1FiresUnder45() {
        assertEquals("1", eveningTh())
    }

    @Test
    fun eveningThSwitchesLowAfter10pmWithoutAPrediction() {
        assertTrue(eveningThShouldSwitchLow(minuteOfDay = 22 * 60 + 10, hp = null))
        assertFalse(eveningThShouldSwitchLow(minuteOfDay = 21 * 60, hp = null))
        assertTrue(eveningThShouldSwitchLow(minuteOfDay = 21 * 60, hp = 4.5))
    }

    private fun eveningTh(): String? =
        eveningThBlock(
            ready = true,
            minuteOfDay = 21 * 60,
            bg = 120.0,
            delta = 1.0,
            iobTh = 40,
            cob = 0.0,
            ttActive = false,
            minutesSinceBolus = 100,
            mjActive = false,
            onRoleProfile = true,
            profilePercent = 100,
        )
}
