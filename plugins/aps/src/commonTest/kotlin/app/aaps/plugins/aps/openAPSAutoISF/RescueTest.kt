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
    fun slowRiseNeedsADelayedBolusStamp() {
        assertFalse(slowRiseRecentEvents(now = 3_600_000L, delayedDeliveredAt = 0L))
        assertTrue(slowRiseRecentEvents(now = 3_600_000L, delayedDeliveredAt = 1_000L))
    }

    @Test
    fun slowRiseAcceptsOneSharedDeltaBand() {
        val band = 0.2 * 18.0
        assertTrue(
            slowRiseCriteriaMet(
                bg = 7.0 * 18.0,
                delta = band,
                shortDelta = band,
                longDelta = band,
                cob = 4.0,
                iob = 2.0,
                steps60 = 100,
                steps180 = 200,
                bolusAgeMinutes = 50,
                carbAgeMinutes = null,
            )
        )
    }
}
