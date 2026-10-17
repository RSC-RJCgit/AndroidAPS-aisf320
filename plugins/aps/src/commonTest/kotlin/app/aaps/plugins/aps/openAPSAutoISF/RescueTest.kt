package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RescueTest {

    @Test
    fun stuckHighRaisesTheRatioWhenThePredictionIsHigh() {
        assertEquals(
            StuckHighBranch.RATIO,
            stuckHighBranch(ready = true, bg = 160.0, iob = 2.0, maxIob = 9.5, hp = 7.0, targetMmol = 5.0),
        )
    }

    @Test
    fun stuckHighLowersTheTargetWhenThePredictionIsNearTarget() {
        assertEquals(
            StuckHighBranch.TARGET,
            stuckHighBranch(ready = true, bg = 160.0, iob = 2.0, maxIob = 9.5, hp = 5.5, targetMmol = 5.0),
        )
        assertEquals(72.1, rescueTargetMgdl(90.0))
    }

    @Test
    fun stuckHighStaysClosedWhenIobIsAtTheCeiling() {
        assertNull(stuckHighBranch(ready = true, bg = 160.0, iob = 4.0, maxIob = 9.5, hp = 7.0, targetMmol = 5.0))
    }

    @Test
    fun stuckHighBarStaysAtEightAndAHalfEvenWhileRisingAtNight() {
        // 140 mg/dL is about 7.8 mmol: under the 8.5 bar, so the night rising flag no longer opens it.
        assertNull(stuckHighBranch(ready = true, bg = 140.0, iob = 2.0, maxIob = 9.5, hp = 7.0, targetMmol = 5.0))
        assertNull(stuckHighBranch(ready = true, bg = 140.0, iob = 2.0, maxIob = 9.5, hp = 7.0, targetMmol = 5.0, nightRising = true))
        assertEquals(
            StuckHighBranch.RATIO,
            stuckHighBranch(ready = true, bg = 155.0, iob = 2.0, maxIob = 9.5, hp = 7.0, targetMmol = 5.0, nightRising = true),
        )
    }

    @Test
    fun poorResponseStage1IsTheEarlyWindow() {
        assertEquals(1, poorResponseStage(7.0, 6.0, stillRising = true, noDecel = true, iobRoom = true, stage1Ready = true, stage2Ready = true))
        assertEquals(2, poorResponseStage(15.0, 10.0, stillRising = true, noDecel = true, iobRoom = true, stage1Ready = true, stage2Ready = true))
    }

    @Test
    fun unexplainedHighClearsWhenGlucoseFalls() {
        val started = nextUnexplainedHigh(now = 1_000L, highNow = true, mealNow = false, since = 0L, mealSeen = false)
        assertEquals(1_000L, started.since)
        val cleared = nextUnexplainedHigh(now = 2_000L, highNow = false, mealNow = false, since = started.since, mealSeen = false)
        assertEquals(0L, cleared.since)
    }

    @Test
    fun offHighBlock1NeedsAFall() {
        assertEquals("1", offHighBlock(
            ready = true,
            minuteOfDay = 2 * 60,
            bg = 120.0,
            delta = -1.0,
            shortDelta = 0.0,
            longDelta = 0.0,
            profilePercent = 100,
            onLowProfile = false,
            ttActive = false,
            steroidsOff = true,
            nightHighTag = false,
        ))
        assertTrue(offHighShouldAct(minuteOfDay = 2 * 60, hp = null))
    }

    @Test
    fun slowRiseNeedsAMealEventOfAtLeast40MinutesAndOnlyTheGentleTestAfterAnHour() {
        val gentle = 0.1 * 18.0
        val wide = 0.2 * 18.0
        fun met(d: Double, age: Int, iob: Double = 2.0) =
            slowRiseCriteriaMet(7.0 * 18.0, d, d, d, 4.0, iob, 100, 200, age)
        assertTrue(met(gentle, 40))
        assertFalse(met(gentle, 39))
        assertTrue(met(gentle, 90))
        assertTrue(met(wide, 45))
        assertFalse(met(wide, 90))
        assertTrue(slowRiseWideBandsApply(59))
        assertFalse(slowRiseWideBandsApply(60))
    }

    @Test
    fun slowRiseIobFloorIsOnePointZero() {
        val gentle = 0.1 * 18.0
        fun met(iob: Double) = slowRiseCriteriaMet(7.0 * 18.0, gentle, gentle, gentle, 4.0, iob, 100, 200, 90)
        assertTrue(met(1.0))
        assertFalse(met(0.99))
        assertTrue(met(5.5))
        assertFalse(met(5.51))
    }

    // 17 Oct Live 17:05: rising, IOB 3.13, S30 116, S60 443. 17:38: falling 0.58 mmol / 5 min, IOB 3.75, S60 581.
    @Test
    fun stuckHighIsBlockedByIobFallAndSteps() {
        assertEquals("iob", stuckHighBlockReason(iob = 3.13, maxIob = 9.5, delta = 10.0, shortDelta = 9.0, steps5 = 0, steps30 = 116, steps60 = 443))
        assertEquals("fall", stuckHighBlockReason(iob = 2.0, maxIob = 9.5, delta = -10.4, shortDelta = -9.0, steps5 = 0, steps30 = 0, steps60 = 0))
        assertEquals("steps", stuckHighBlockReason(iob = 2.0, maxIob = 9.5, delta = 5.0, shortDelta = 4.0, steps5 = 0, steps30 = 0, steps60 = 581))
        assertNull(stuckHighBlockReason(iob = 2.0, maxIob = 9.5, delta = 5.0, shortDelta = 4.0, steps5 = 10, steps30 = 100, steps60 = 300))
        // A lower max IOB still gives the lower 40% ceiling
        assertEquals("iob", stuckHighBlockReason(iob = 2.5, maxIob = 6.0, delta = 5.0, shortDelta = 4.0, steps5 = 0, steps30 = 0, steps60 = 0))
        assertNull(
            stuckHighBranch(
                ready = true, bg = 160.0, iob = 2.0, maxIob = 9.5, hp = 5.5, targetMmol = 4.0,
                delta = -10.4, shortDelta = -9.0
            )
        )
    }

    @Test
    fun fallRescueNeedsALowLongTtFastFallHighIobAndWalking() {
        val thirty = 30 * 60_000L
        // 17 Oct Live about 17:30: TT 4.0, falling 0.43 mmol / 5 min, IOB 4.2, S30 484
        assertTrue(fallRescueDue(ttLowMgdl = 72.1, ttDurationMs = thirty, iob = 4.2, delta = -7.7, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = null, ttDurationMs = 0L, iob = 4.2, delta = -7.7, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = 90.0, ttDurationMs = thirty, iob = 4.2, delta = -7.7, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = 75.6, ttDurationMs = 5 * 60_000L, iob = 4.2, delta = -7.7, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = 72.1, ttDurationMs = thirty, iob = 2.4, delta = -7.7, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = 72.1, ttDurationMs = thirty, iob = 4.2, delta = -5.0, steps30 = 484))
        assertFalse(fallRescueDue(ttLowMgdl = 72.1, ttDurationMs = thirty, iob = 4.2, delta = -7.7, steps30 = 149))
    }
}
