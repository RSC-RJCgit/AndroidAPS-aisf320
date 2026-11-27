package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class Set50RestoreTest {

    private val now = 100_000_000L

    @Test
    fun daytimeGoesBackToStandard() {
        // 27 Nov 07:57 Live: Usual2 on Low Tier A after an overnight Set50 from Standard
        assertEquals(Set50Restore.Standard, set50Restore(now - 9 * 3_600_000L, now, runningLowRole = true, minuteOfDay = 7 * 60 + 57))
        assertEquals(Set50Restore.Standard, set50Restore(now - 60_000L, now, runningLowRole = true, minuteOfDay = 6 * 60))
    }

    @Test
    fun overnightStaysLowAndKeepsTheMark() {
        assertEquals(Set50Restore.KeepLow, set50Restore(now - 60_000L, now, runningLowRole = true, minuteOfDay = 23 * 60 + 10))
        assertEquals(Set50Restore.KeepLow, set50Restore(now - 60_000L, now, runningLowRole = true, minuteOfDay = 5 * 60 + 59))
    }

    @Test
    fun noMarkOldMarkOrNotOnLowWorksAsBefore() {
        assertEquals(Set50Restore.None, set50Restore(0L, now, runningLowRole = true, minuteOfDay = 12 * 60))
        assertEquals(Set50Restore.None, set50Restore(now - SET50_RESTORE_MAX_MS - 1L, now, runningLowRole = true, minuteOfDay = 12 * 60))
        assertEquals(Set50Restore.None, set50Restore(now + 1L, now, runningLowRole = true, minuteOfDay = 12 * 60))
        assertEquals(Set50Restore.None, set50Restore(now - 60_000L, now, runningLowRole = false, minuteOfDay = 12 * 60))
    }
}
