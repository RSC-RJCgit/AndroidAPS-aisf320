package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarsawFpuTest {

    @Test
    fun midTierSplitsTheTotalAcrossItsOwnHours() {
        val plan = warsawFpuPlan(proteinGrams = 10, fatGrams = 10, ic = 10.0)
        requireNotNull(plan)
        assertEquals(1.3, plan.fpu, 0.0001)
        assertEquals(4, plan.numDoses)
        assertEquals(240, plan.durationMinutes)
        assertEquals(0.325, plan.perDoseInsulin, 0.0001)
        assertEquals(1.3, plan.totalInsulin, 0.0001)
        assertFalse(plan.capped)
    }

    @Test
    fun topTierCapDropsLaterHoursAndKeepsTheDoseSize() {
        val plan = warsawFpuPlan(proteinGrams = 0, fatGrams = 50, ic = 10.0, durationHoursCap = 5.0)
        requireNotNull(plan)
        assertEquals(4.5, plan.fpu, 0.0001)
        assertEquals(8, (plan.fullTierInsulin / plan.perDoseInsulin).toInt())
        assertEquals(5, plan.numDoses)
        assertEquals(0.5625, plan.perDoseInsulin, 0.0001)
        assertEquals(2.8125, plan.totalInsulin, 0.0001)
        assertTrue(plan.capped)
    }

    @Test
    fun emptyMealHasNoPlan() {
        assertNull(warsawFpuPlan(0, 0, 10.0))
        assertNull(warsawFpuPlan(10, 10, 0.0))
    }

    @Test
    fun delaysAreOneHourApart() {
        assertEquals(60, warsawDoseDelayMinutes(1, 4, 240))
        assertEquals(240, warsawDoseDelayMinutes(4, 4, 240))
        assertEquals(180, warsawDoseDelayMinutes(1, 1, 180))
    }

    @Test
    fun bgGateAndIobRise() {
        assertTrue(warsawDoseBgAllows(126.1, -0.89, -0.89))
        assertFalse(warsawDoseBgAllows(126.0, 0.0, 0.0))
        assertFalse(warsawDoseBgAllows(140.0, -0.90, 0.0))
        assertFalse(warsawDoseBgAllows(null, 0.0, 0.0))
        assertEquals(0.2, warsawDoseAfterIobRise(0.5, 2.0, 2.3, 0.1), 0.0001)
        assertTrue(warsawDoseAfterIobRise(0.5, 2.0, 2.6, 0.1) <= 0.0)
        assertEquals(0.5, warsawDoseAfterIobRise(0.5, 2.0, 1.5, 0.1), 0.0001)
    }
}
