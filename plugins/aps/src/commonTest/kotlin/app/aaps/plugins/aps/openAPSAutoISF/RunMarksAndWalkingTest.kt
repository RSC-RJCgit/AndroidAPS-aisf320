package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunMarksAndWalkingTest {

    @Test
    fun aFreshMarkIsNotReadyUntilTheWindowEnds() {
        val marks = RunMarks()
        val now = 1_000_000L
        marks.mark(RunMark.SUB75, now)
        assertFalse(marks.ready(RunMark.SUB75, 10, now + 9 * 60_000L))
        assertTrue(marks.ready(RunMark.SUB75, 10, now + 10 * 60_000L))
        assertEquals(9, marks.minutesAgo(RunMark.SUB75, now + 9 * 60_000L))
    }

    @Test
    fun anUnmarkedKeyIsReadyAndHasNoAge() {
        val marks = RunMarks()
        val now = 1_700_000_000_000L
        assertTrue(marks.ready(RunMark.UAM_BST, 20, now))
        assertFalse(marks.recent(RunMark.UAM_BST, 20, now))
        assertNull(marks.minutesAgo(RunMark.UAM_BST, now))
    }

    @Test
    fun heavySmbUnderSevenPointFiveArmsOnce() {
        val marks = RunMarks()
        val now = 1_700_000_000_000L
        assertEquals("arm", updateSub75Mark(marks, now, bg = 130.0, delta = 0.0, smbSum10 = 1.6))
        assertEquals("", updateSub75Mark(marks, now + 60_000L, bg = 130.0, delta = 0.0, smbSum10 = 1.6))
        assertTrue(marks.recent(RunMark.SUB75, 10, now + 60_000L))
    }

    @Test
    fun aRisingRecoveryClearsTheUnderSevenPointFiveMark() {
        val marks = RunMarks()
        val now = 1_700_000_000_000L
        marks.mark(RunMark.SUB75, now)
        assertEquals("clear", updateSub75Mark(marks, now + 60_000L, bg = 140.0, delta = 6.0, smbSum10 = 0.0))
        assertTrue(marks.ready(RunMark.SUB75, 10, now + 60_000L))
    }

    @Test
    fun aFlatRecoveryDoesNotClearTheMark() {
        val marks = RunMarks()
        val now = 1_700_000_000_000L
        marks.mark(RunMark.SUB75, now)
        assertEquals("", updateSub75Mark(marks, now + 60_000L, bg = 140.0, delta = 5.4, smbSum10 = 0.0))
    }

    @Test
    fun littleWalkingCutsAReducedDaytimeSmbWhenALowIsPredicted() {
        val result = littleWalkingSmbCut(
            microBolus = 1.0,
            uncappedMicroBolus = 2.0,
            hour = 12,
            steps5 = 11,
            steps15 = 0,
            steps30 = 0,
            steps60 = 0,
            hypoPrediction2 = 6.5,
        )
        assertEquals(0.75, result.microBolus, 0.001)
    }

    @Test
    fun littleWalkingStaysOffAtNightOrWithoutAPrediction() {
        val night = littleWalkingSmbCut(1.0, 2.0, hour = 8, steps5 = 20, steps15 = 0, steps30 = 0, steps60 = 0, hypoPrediction2 = 5.0)
        val noPrediction = littleWalkingSmbCut(1.0, 2.0, hour = 12, steps5 = 20, steps15 = 0, steps30 = 0, steps60 = 0, hypoPrediction2 = null)
        val stillFull = littleWalkingSmbCut(2.0, 2.0, hour = 12, steps5 = 20, steps15 = 0, steps30 = 0, steps60 = 0, hypoPrediction2 = 5.0)
        assertEquals(1.0, night.microBolus, 0.001)
        assertEquals(1.0, noPrediction.microBolus, 0.001)
        assertEquals(2.0, stillFull.microBolus, 0.001)
    }

    @Test
    fun aRecentBoostPutsTheUncappedSmbBack() {
        val result = fastRiseBoostRestore(
            microBolus = 0.5,
            uncappedMicroBolus = 2.0,
            smbBoostRecent = true,
            nightFrSkipActive = false,
            uamBoostRecent = false,
            uamBstMinutesAgo = Int.MAX_VALUE,
            iob = 1.0,
            maxIob = 10.0,
            cob = 0.0,
        )
        assertEquals(2.0, result.microBolus, 0.001)
    }

    @Test
    fun highIobAfterUamBstHoldsTheRestoreThenLetsAQuarterBack() {
        val held = fastRiseBoostRestore(0.4, 2.0, true, false, true, uamBstMinutesAgo = 20, iob = 2.0, maxIob = 10.0, cob = 0.0)
        val partial = fastRiseBoostRestore(0.4, 2.0, true, false, true, uamBstMinutesAgo = 10, iob = 2.0, maxIob = 10.0, cob = 0.0)
        assertEquals(0.4, held.microBolus, 0.001)
        assertEquals(1.6, partial.microBolus, 0.001)
    }

    @Test
    fun aMealWithARealRiseCountsAsARecentBoost() {
        assertTrue(smbBoostRecentNow(false, false, false, false, cob = 9.0, rawDelta5 = 1.8, longAvgDelta = 0.0))
        assertFalse(smbBoostRecentNow(true, false, false, false, cob = 0.0, rawDelta5 = null, longAvgDelta = 0.0))
        assertFalse(smbBoostRecentNow(false, false, false, false, cob = 9.0, rawDelta5 = 1.8, longAvgDelta = -1.8))
    }

    @Test
    fun theUnderSevenPointFivePauseZerosTheSmb() {
        val paused = sub75CooldownCut(1.2, cooldown = true, bg = 135.0)
        val recovered = sub75CooldownCut(1.2, cooldown = true, bg = 135.1)
        assertEquals(0.0, paused.microBolus, 0.001)
        assertEquals(1.2, recovered.microBolus, 0.001)
    }

    @Test
    fun predictedLowUsesTheRawFiveMinuteChange() {
        val predicted = hypoPrediction2Mmol(bg = 144.0, shortDelta = 0.0, rawDelta5 = 0.0, iob = 1.0, cob = 0.0)
        assertEquals(144.0 / 18.0182 - 1.0, predicted, 0.001)
    }
}
