package app.aaps.implementation.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryFallRuleTest {

    private val min = 60_000L
    private val now = 1_000 * min

    @Test
    fun alarmsUnder50WhenItFellFiveOrMoreIn30Minutes() {
        val samples = listOf(now - 25 * min to 52, now - 10 * min to 49, now to 46)
        assertEquals(6, batteryFallAlarmDue(samples, now, lastAlarmAt = 0L))
    }

    @Test
    fun noAlarmAt50OrAbove() {
        val samples = listOf(now - 20 * min to 60, now to 50)
        assertNull(batteryFallAlarmDue(samples, now, lastAlarmAt = 0L))
    }

    @Test
    fun noAlarmForASlowFall() {
        val samples = listOf(now - 20 * min to 49, now to 45)
        assertNull(batteryFallAlarmDue(samples, now, lastAlarmAt = 0L))
    }

    @Test
    fun aFallOlderThan30MinutesDoesNotCount() {
        val samples = listOf(now - 40 * min to 60, now - 20 * min to 47, now to 45)
        assertNull(batteryFallAlarmDue(samples, now, lastAlarmAt = 0L))
    }

    @Test
    fun quietForAnHourAfterAnAlarm() {
        val samples = listOf(now - 25 * min to 55, now to 40)
        assertNull(batteryFallAlarmDue(samples, now, lastAlarmAt = now - 59 * min))
        assertEquals(15, batteryFallAlarmDue(samples, now, lastAlarmAt = now - 61 * min))
    }

    @Test
    fun noSamplesNoAlarm() {
        assertNull(batteryFallAlarmDue(emptyList(), now, lastAlarmAt = 0L))
    }
}
