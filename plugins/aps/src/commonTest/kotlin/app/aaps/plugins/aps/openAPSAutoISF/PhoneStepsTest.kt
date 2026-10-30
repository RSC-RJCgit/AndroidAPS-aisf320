package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The phone's own step counter, ported from v3's StepService (2026-10-30). */
class PhoneStepsTest {

    private val bin = 5 * 60_000L
    private val start = 1_000L * bin // the start of a bin

    @AfterTest
    fun cleanUp() = PhoneSteps.reset()

    @Test
    fun nothingUntilTheSensorReported() {
        assertNull(PhoneSteps.row(start))
    }

    @Test
    fun theFirstTotalIsTheStartingPointNotSteps() {
        PhoneSteps.onTotal(5000, start)
        assertEquals(0, PhoneSteps.row(start + bin)!!.steps5min)
    }

    @Test
    fun theLastThreeFinishedBinsAreS5S10AndS15AsTotals() {
        PhoneSteps.onTotal(1000, start)
        PhoneSteps.onTotal(1030, start + 10_000L)           // 30 in the bin 3 before
        PhoneSteps.onTotal(1050, start + bin + 10_000L)     // 20 in the bin 2 before
        PhoneSteps.onTotal(1060, start + 2 * bin + 10_000L) // 10 in the last finished bin
        val row = PhoneSteps.row(start + 3 * bin + 10_000L)!!
        assertEquals(10, row.steps5min)
        assertEquals(30, row.steps10min)
        assertEquals(60, row.steps15min)
        assertEquals(60, row.steps30min)
        assertEquals(PhoneSteps.DEVICE, row.device)
    }

    @Test
    fun aRestartedTotalCountsFromZero() {
        PhoneSteps.onTotal(1000, start)
        PhoneSteps.onTotal(40, start + 10_000L)
        assertEquals(40, PhoneSteps.row(start + bin)!!.steps5min)
    }

    @Test
    fun theThreeHourCountCoversThreeHours() {
        PhoneSteps.onTotal(0, start)
        PhoneSteps.onTotal(100, start + 10_000L)
        // 35 bins later the first bin is still inside the last 180 minutes, one more and it is not
        assertEquals(100, PhoneSteps.row(start + 35 * bin)!!.steps180min)
        assertEquals(0, PhoneSteps.row(start + 36 * bin)!!.steps180min)
        assertEquals(0, PhoneSteps.row(start + 35 * bin)!!.steps60min)
    }
}
