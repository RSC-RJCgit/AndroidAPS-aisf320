package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class SmbStackTrimTest {

    private val now = 1_700_000_000_000L

    @Test
    fun aWideGapLeavesTheSmbAndClearsTheStack() {
        val result = smbStackTrim(1.0, smbIntervalSec = 71.0, stackStart = now - 60_000L, nowMs = now, hour = 12, iob = 2.0, maxIob = 10.0)
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals(0L, result.stackStart)
        assertEquals("", result.reason)
    }

    @Test
    fun theFirstCycleOfANewStackStaysFullSize() {
        val result = smbStackTrim(1.0, smbIntervalSec = 60.0, stackStart = 0L, nowMs = now, hour = 12, iob = 4.0, maxIob = 10.0)
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals(now, result.stackStart)
    }

    @Test
    fun anExpiredStackStartsAgainAtFullSize() {
        val result = smbStackTrim(1.0, 60.0, now - 10 * 60_000L, now, hour = 12, iob = 4.0, maxIob = 10.0)
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals(now, result.stackStart)
    }

    @Test
    fun aYoungStackLosesTenPercent() {
        val result = smbStackTrim(1.0, 60.0, now - 3 * 60_000L, now, hour = 12, iob = 4.0, maxIob = 10.0)
        assertEquals(0.9, result.microBolus, 0.001)
        assertEquals(now - 3 * 60_000L, result.stackStart)
    }

    @Test
    fun aLongDaytimeStackCutsHarderOnceIobIsHigh() {
        val modest = smbStackTrim(1.0, 60.0, now - 6 * 60_000L, now, hour = 12, iob = 1.5, maxIob = 10.0)
        val high = smbStackTrim(1.0, 60.0, now - 6 * 60_000L, now, hour = 12, iob = 3.5, maxIob = 10.0)
        assertEquals(0.9, modest.microBolus, 0.001)
        assertEquals(0.65, high.microBolus, 0.001)
    }

    @Test
    fun overnightUsesTheLowerIobBar() {
        val result = smbStackTrim(1.0, 60.0, now - 6 * 60_000L, now, hour = 23, iob = 1.5, maxIob = 10.0)
        assertEquals(0.65, result.microBolus, 0.001)
    }

    @Test
    fun theCutStopsAtFortyFivePercent() {
        val result = smbStackTrim(1.0, 60.0, now - 9 * 60_000L, now, hour = 12, iob = 4.0, maxIob = 10.0)
        assertEquals(0.55, result.microBolus, 0.001)
    }

    @Test
    fun aZeroSmbClearsAnOpenStack() {
        val result = smbStackTrim(0.0, 60.0, now - 60_000L, now, hour = 12, iob = 1.0, maxIob = 10.0)
        assertEquals(0.0, result.microBolus, 0.001)
        assertEquals(0L, result.stackStart)
    }
}
