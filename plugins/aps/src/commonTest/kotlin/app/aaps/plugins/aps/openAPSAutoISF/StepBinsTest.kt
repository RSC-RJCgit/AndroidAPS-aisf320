package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SC
import kotlin.test.Test
import kotlin.test.assertEquals

class StepBinsTest {

    private fun row(s5: Int, s10: Int, s15: Int, device: String) = SC(
        duration = 5 * 60_000L, timestamp = 1_000_000L, steps5min = s5, steps10min = s10, steps15min = s15,
        steps30min = 0, steps60min = 0, steps180min = 0, device = device, utcOffset = 0L
    )

    @Test
    fun aLiveRowIsAlreadyBins() {
        val live = row(s5 = 40, s10 = 25, s15 = 10, device = "openaps://SM-A366B")
        assertEquals(25, steps10Bin(live))
        assertEquals(10, steps15Bin(live))
    }

    @Test
    fun aWatchRowIsTurnedIntoBins() {
        // Watch totals: 40 in the last 5 min, 65 in the last 10, 75 in the last 15
        val watch = row(s5 = 40, s10 = 65, s15 = 75, device = "Galaxy Watch")
        assertEquals(25, steps10Bin(watch))
        assertEquals(10, steps15Bin(watch))
    }

    @Test
    fun aWatchRowWithoutTheLongerWindowsGivesZeroNotANegativeCount() {
        // The watch's first rows store 0 for the windows it has not filled yet
        val watch = row(s5 = 40, s10 = 0, s15 = 0, device = "Galaxy Watch")
        assertEquals(0, steps10Bin(watch))
        assertEquals(0, steps15Bin(watch))
    }
}
