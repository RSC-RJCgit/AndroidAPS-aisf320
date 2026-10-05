package app.aaps.core.objects.wizard

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarsawFpuTest {

    @Test
    fun anEarlierDoseIsRetriedUntilTenMinutesBeforeTheNextOne() {
        val hour = 60 * 60_000L
        val deadline = warsawRetryDeadlineMs(firstDueAt = hour, nextDueAt = 2 * hour)
        assertEquals(2 * hour - 10 * 60_000L, deadline)
        // Re-checks at +10, +20, +30, +40 and +50 minutes are allowed. The one at +60 is not.
        assertTrue(warsawCanRetry(now = hour + 40 * 60_000L, firstDueAt = hour, nextDueAt = 2 * hour))
        assertFalse(warsawCanRetry(now = hour + 41 * 60_000L, firstDueAt = hour, nextDueAt = 2 * hour))
    }

    @Test
    fun theLastDoseGetsThirtyMinutesFromItsOwnHour() {
        val hour = 60 * 60_000L
        assertEquals(hour + 30 * 60_000L, warsawRetryDeadlineMs(firstDueAt = hour, nextDueAt = null))
        assertTrue(warsawCanRetry(now = hour + 20 * 60_000L, firstDueAt = hour, nextDueAt = null))
        assertFalse(warsawCanRetry(now = hour + 21 * 60_000L, firstDueAt = hour, nextDueAt = null))
    }

    @Test
    fun noteAmountsKeepTwoDecimals() {
        assertEquals("1.30", warsawNoteAmount(1.3))
        assertEquals("0.05", warsawNoteAmount(0.05))
        assertEquals("2.00", warsawNoteAmount(2.0))
    }

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
        val mmol = Constants.MMOLL_TO_MGDL
        assertTrue(warsawDoseBgAllows(5.6 * mmol, 0.3 * mmol, 0.3 * mmol))
        assertFalse(warsawDoseBgAllows(5.5 * mmol, 0.3 * mmol, 0.3 * mmol))
        assertFalse(warsawDoseBgAllows(6.0 * mmol, 0.2 * mmol, 0.3 * mmol))
        assertFalse(warsawDoseBgAllows(6.0 * mmol, 0.3 * mmol, 0.2 * mmol))
        assertFalse(warsawDoseBgAllows(null, 0.3 * mmol, 0.3 * mmol))
        assertEquals(0.2, warsawDoseAfterIobRise(0.5, 2.0, 2.3, 0.1), 0.0001)
        assertTrue(warsawDoseAfterIobRise(0.5, 2.0, 2.6, 0.1) <= 0.0)
        assertEquals(0.5, warsawDoseAfterIobRise(0.5, 2.0, 1.5, 0.1), 0.0001)
    }
}
