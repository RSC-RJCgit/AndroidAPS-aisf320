package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MjCycleTest {

    @Test
    fun pp50OffRecoversInTheDaytime() {
        assertEquals("1", pp50Off())
    }

    @Test
    fun pp50OffStaysClosedBelow6Mmol() {
        assertNull(pp50Off(bg = 100.0))
    }

    @Test
    fun moreMjHypoPathSetsMj2() {
        assertEquals("MJ2", moreMj())
    }

    @Test
    fun moreMjNoHighPathSetsMj3() {
        assertEquals("MJ3", moreMj(alarmRecent = false, noRecentHigh = true, bg = 140.0, profilePercent = 100))
    }

    @Test
    fun moreMjHypoPathWinsWhenBothMatch() {
        assertEquals("MJ2", moreMj(noRecentHigh = true))
    }

    @Test
    fun moreMjStaysClosedBeforeSix() {
        assertNull(moreMj(minuteOfDay = 5 * 60))
    }

    @Test
    fun morningRoleStepsUpFromA() {
        assertEquals(1, morning(sourceIndex = 0, hp = 6.5, noMjRemains = true, recentBgHigh = true))
    }

    @Test
    fun morningRoleStaysClosedWithoutAPrediction() {
        assertNull(morning(sourceIndex = 0, hp = null, noMjRemains = true, recentBgHigh = true))
    }

    @Test
    fun mjRecentSwitchesLowOvernight() {
        val tune = mjRecentShouldTune(
            ready = true,
            steroidsOff = true,
            mjCycleOn = true,
            ttActive = false,
            profilePercent = 100,
            minuteOfDay = 2 * 60,
            bg = 120.0,
            delta = 0.0,
            cannulaHours = 10.0,
        )
        assertTrue(tune)
        assertTrue(mjRecentShouldSwitchLow(tune, minuteOfDay = 2 * 60, hp = null, rescueActive = false))
        assertFalse(mjRecentShouldSwitchLow(tune, minuteOfDay = 2 * 60, hp = null, rescueActive = true))
    }

    @Test
    fun mj2OldFiresInItsWindow() {
        assertTrue(mj2OldShouldFire(ready = true, mjActive = true, minuteOfDay = 2 * 60 + 30))
        assertFalse(mj3OldShouldFire(ready = true, mj2 = true, minuteOfDay = 2 * 60 + 30))
    }

    private fun pp50Off(bg: Double = 120.0): String? =
        pp50OffBlock(
            ready = true,
            lowBgRecent = true,
            minuteOfDay = 10 * 60,
            bg = bg,
            delta = 0.0,
            shortDelta = 0.0,
            longDelta = 0.0,
            iob = 1.0,
            cob = 0.0,
            cannulaHours = 1.0,
            minutesSinceBolus = 100,
            acceWeight = 0.5,
        )

    private fun moreMj(
        minuteOfDay: Int = 12 * 60,
        alarmRecent: Boolean = true,
        noRecentHigh: Boolean = false,
        bg: Double = 90.0,
        profilePercent: Int = 50,
    ): String? =
        moreMjTarget(
            ready = true,
            mjOffReady = true,
            minuteOfDay = minuteOfDay,
            acceWeight = 0.05,
            steps180 = 0,
            steps60 = 0,
            noMjRemains = true,
            bg = bg,
            profilePercent = profilePercent,
            cob = 0.0,
            minutesSinceBolus = 300,
            alarmRecent = alarmRecent,
            iob = 0.4,
            noRecentHigh = noRecentHigh,
        )

    private fun morning(
        sourceIndex: Int,
        hp: Double?,
        noMjRemains: Boolean,
        recentBgHigh: Boolean,
    ): Int? =
        morningRoleIndex(
            ready = true,
            steroidsOff = true,
            minuteOfDay = 4 * 60,
        hp = hp,
        mjKnown = true,
        noMjRemains = noMjRemains,
            recentBgHigh = recentBgHigh,
            recentBgNormal = false,
            sourceIndex = sourceIndex,
            ladderSize = 3,
        )
}
