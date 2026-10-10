package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentRiseTest {

    @Test
    fun aNewRunStartsAtZeroMinutes() {
        val clock = persistentRiseClock(holding = true, startedAt = 0L, now = 1_000_000L)
        assertEquals(1_000_000L, clock.startedAt)
        assertEquals(0.0, clock.persistentMinutes)
    }

    @Test
    fun tenMinutesWithNoSmbFiresInTheDay() {
        val clock = persistentRiseClock(holding = true, startedAt = 0L, now = 600_000L)
        assertTrue(
            persistentRiseShouldFire(
                ready = true,
                boostOn = true,
                minuteOfDay = 9 * 60,
                daytimeBypass = false,
                holding = true,
                persistentMinutes = 10.0,
                onLowCurrent = false,
                mjActive = false,
                steps5 = 0,
                steps30 = 0,
            )
        )
        assertEquals(600_000L, clock.startedAt)
    }

    @Test
    fun aBrokenRiseClearsTheClockAndStaysClosed() {
        val clock = persistentRiseClock(holding = false, startedAt = 50_000L, now = 700_000L)
        assertEquals(0L, clock.startedAt)
        assertFalse(
            persistentRiseShouldFire(
                ready = true,
                boostOn = true,
                minuteOfDay = 9 * 60,
                daytimeBypass = false,
                holding = false,
                persistentMinutes = clock.persistentMinutes,
                onLowCurrent = false,
                mjActive = false,
                steps5 = 0,
                steps30 = 0,
            )
        )
    }

    @Test
    fun beforeHalfPastEightNeedsTheDayBypass() {
        assertFalse(fireAt(minuteOfDay = 8 * 60, daytimeBypass = false))
        assertTrue(fireAt(minuteOfDay = 8 * 60, daytimeBypass = true))
    }

    private fun fireAt(minuteOfDay: Int, daytimeBypass: Boolean) = persistentRiseShouldFire(
        ready = true,
        boostOn = true,
        minuteOfDay = minuteOfDay,
        daytimeBypass = daytimeBypass,
        holding = true,
        persistentMinutes = 12.0,
        onLowCurrent = false,
        mjActive = false,
        steps5 = 10,
        steps30 = 40,
    )
}
