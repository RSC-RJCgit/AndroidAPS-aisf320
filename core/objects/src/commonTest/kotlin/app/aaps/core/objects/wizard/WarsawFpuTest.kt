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
        assertEquals(8, plan.numDoses)
        assertEquals(240, plan.durationMinutes)
        assertEquals(0.1625, plan.perDoseInsulin, 0.0001)
        assertEquals(1.3, plan.totalInsulin, 0.0001)
        assertFalse(plan.capped)
    }

    @Test
    fun topTierCapDropsLaterHoursAndKeepsTheDoseSize() {
        val plan = warsawFpuPlan(proteinGrams = 0, fatGrams = 50, ic = 10.0, durationHoursCap = 5.0)
        requireNotNull(plan)
        assertEquals(4.5, plan.fpu, 0.0001)
        assertEquals(16, (plan.fullTierInsulin / plan.perDoseInsulin).toInt())
        assertEquals(10, plan.numDoses)
        assertEquals(0.28125, plan.perDoseInsulin, 0.0001)
        assertEquals(2.8125, plan.totalInsulin, 0.0001)
        assertTrue(plan.capped)
    }

    @Test
    fun emptyMealHasNoPlan() {
        assertNull(warsawFpuPlan(0, 0, 10.0))
        assertNull(warsawFpuPlan(10, 10, 0.0))
    }

    @Test
    fun delaysAreThirtyMinutesApart() {
        assertEquals(30, warsawDoseDelayMinutes(1, 8, 240))
        assertEquals(60, warsawDoseDelayMinutes(2, 8, 240))
        assertEquals(240, warsawDoseDelayMinutes(8, 8, 240))
        assertEquals(180, warsawDoseDelayMinutes(1, 1, 180))
    }

    @Test
    fun halfHourCapAndExtraInsulinKeepThirtyMinuteParts() {
        val capped = requireNotNull(warsawFpuPlan(0, 50, 10.0, durationHoursCap = 5.5))
        assertEquals(11, capped.numDoses)
        assertEquals(330, capped.durationMinutes)
        assertEquals(0.28125, capped.perDoseInsulin, 0.0001)
        val extra = requireNotNull(warsawPlanPlusUnits(null, 1.2))
        assertEquals(6, extra.numDoses)
        assertEquals(180, extra.durationMinutes)
        assertEquals(0.2, extra.perDoseInsulin, 0.0001)
        assertEquals(30, warsawDoseDelayMinutes(1, extra.numDoses, extra.durationMinutes))
    }

    @Test
    fun retryWindowFitsBetweenHalfHourParts() {
        val first = 30 * 60_000L
        val next = 60 * 60_000L
        assertTrue(warsawCanRetry(first, first, next))
        assertTrue(warsawCanRetry(first + 10 * 60_000L, first, next))
        assertFalse(warsawCanRetry(first + 11 * 60_000L, first, next))
    }

    @Test
    fun bgGateAndIobRise() {
        val mmol = Constants.MMOLL_TO_MGDL
        assertTrue(warsawDoseBgAllows(5.6 * mmol, 0.3 * mmol, 0.3 * mmol))
        assertFalse(warsawDoseBgAllows(5.5 * mmol, 0.3 * mmol, 0.3 * mmol))
        // 2026-10-18: at least 0.05 mmol on both deltas (was above 0.2)
        assertTrue(warsawDoseBgAllows(6.0 * mmol, 0.06 * mmol, 0.06 * mmol))
        assertFalse(warsawDoseBgAllows(6.0 * mmol, 0.04 * mmol, 0.3 * mmol))
        assertFalse(warsawDoseBgAllows(6.0 * mmol, 0.3 * mmol, 0.04 * mmol))
        assertFalse(warsawDoseBgAllows(null, 0.3 * mmol, 0.3 * mmol))
    }
}
