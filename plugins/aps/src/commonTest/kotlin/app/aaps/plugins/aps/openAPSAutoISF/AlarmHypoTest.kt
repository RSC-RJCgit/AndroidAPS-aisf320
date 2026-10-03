package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmHypoTest {

    @Test
    fun aGlucoseUnder3mmolFiresEvenWhenTheWeightIsHigh() {
        assertTrue(hypo1(bg = 53.9, acceWeight = 0.70, delta = 0.0, shortDelta = 0.0))
        assertFalse(hypo1(bg = 54.0, acceWeight = 0.70, delta = 0.0, shortDelta = 0.0))
    }

    @Test
    fun aSlowDeclineNeedsTheWeightAlreadyAtOrUnder008() {
        assertTrue(hypo1(bg = 70.0, delta = -0.37, shortDelta = -0.37, acceWeight = 0.08))
        assertFalse(hypo1(bg = 70.0, delta = -0.37, shortDelta = -0.37, acceWeight = 0.09))
    }

    @Test
    fun aStepDeclineIsOpenFrom7Until23() {
        assertTrue(hypo1(bg = 70.0, delta = -0.9, shortDelta = 0.0, acceWeight = 0.08, steps60 = 102, minuteOfDay = 7 * 60))
        assertFalse(hypo1(bg = 70.0, delta = -0.9, shortDelta = 0.0, acceWeight = 0.08, steps60 = 102, minuteOfDay = 23 * 60))
        assertFalse(hypo1(bg = 70.0, delta = -0.9, shortDelta = 0.0, acceWeight = 0.08, steps60 = 101, minuteOfDay = 12 * 60))
    }

    @Test
    fun aPredictionFiresOnlyWhenBothAreLowAndNoRecentDoseOrCarbs() {
        assertTrue(hypo1(bg = 120.0, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08, hp = 3.4, hp1 = 3.8))
        assertFalse(hypo1(bg = 120.0, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08, hp = 3.4, hp1 = 3.8, recentBolusOrCarbs = true))
        assertFalse(hypo1(bg = 120.0, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08, hp = null, hp1 = 3.0))
    }

    @Test
    fun alarm2FiresOnAFlatLowWhenTheWeightIsAlreadyLow() {
        assertTrue(hypo2(bg = 77.5, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08))
        assertFalse(hypo2(bg = 77.5, delta = 0.1, shortDelta = 0.0, acceWeight = 0.08))
        assertFalse(hypo2(bg = 70.0, delta = 0.0, shortDelta = 0.0, acceWeight = 0.09))
    }

    @Test
    fun alarm2AlsoFiresUpTo55mmolAfterALotOfSteps() {
        assertTrue(hypo2(bg = 99.1, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08, steps30 = 1000))
        assertFalse(hypo2(bg = 99.1, delta = 0.0, shortDelta = 0.0, acceWeight = 0.08, steps30 = 999))
    }

    @Test
    fun aNotReadyMarkStaysClosed() {
        assertFalse(hypo1(ready = false, bg = 40.0))
        assertFalse(hypo2(ready = false, bg = 40.0, acceWeight = 0.08))
    }

    private fun hypo1(
        ready: Boolean = true,
        bg: Double = 70.0,
        delta: Double = -1.0,
        shortDelta: Double = -1.0,
        acceWeight: Double = 0.08,
        minuteOfDay: Int = 12 * 60,
        steps60: Int = 0,
        hp: Double? = null,
        hp1: Double? = null,
        recentBolusOrCarbs: Boolean = false,
    ) = alarmHypo1ShouldFire(ready, bg, delta, shortDelta, acceWeight, minuteOfDay, steps60, hp, hp1, recentBolusOrCarbs)

    private fun hypo2(
        ready: Boolean = true,
        bg: Double = 70.0,
        delta: Double = 0.0,
        shortDelta: Double = 0.0,
        acceWeight: Double = 0.08,
        steps30: Int = 0,
        hp: Double? = null,
        hp1: Double? = null,
        recentBolusOrCarbs: Boolean = false,
    ) = alarmHypo2ShouldFire(ready, bg, delta, shortDelta, acceWeight, steps30, hp, hp1, recentBolusOrCarbs)
}
