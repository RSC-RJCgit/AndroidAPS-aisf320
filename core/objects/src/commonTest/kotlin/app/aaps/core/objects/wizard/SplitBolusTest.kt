package app.aaps.core.objects.wizard

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitBolusTest {

    @Test
    fun leftoverIsThePartAboveWhatWasDelivered() {
        assertEquals(3.0, splitLeftover(requested = 8.0, delivered = 5.0, bolusStep = 0.05))
    }

    @Test
    fun noLeftoverWhenTheDoseFitsOrNothingWasDelivered() {
        assertNull(splitLeftover(requested = 5.0, delivered = 5.0, bolusStep = 0.05))
        assertNull(splitLeftover(requested = 8.0, delivered = 0.0, bolusStep = 0.05))
        assertNull(splitLeftover(requested = 5.02, delivered = 5.0, bolusStep = 0.05))
    }

    @Test
    fun nextPartShrinksWhenInsulinOnBoardHasRisen() {
        assertEquals(0.4, splitNextDose(previousPart = 1.0, remaining = 3.0, iobBaseline = 2.0, liveIob = 2.6, bolusStep = 0.05))
    }

    @Test
    fun nextPartIsZeroWhenTheRiseCoversIt() {
        assertEquals(0.0, splitNextDose(previousPart = 1.0, remaining = 3.0, iobBaseline = 2.0, liveIob = 4.0, bolusStep = 0.05))
    }

    @Test
    fun glucoseGateMatchesTheSplitLimits() {
        assertEquals(SplitBgCheck.Missing, splitBgCheck(null, 0.0, 0.0))
        assertEquals(SplitBgCheck.Unsafe, splitBgCheck(100.0, 0.0, 0.0))
        assertEquals(SplitBgCheck.Unsafe, splitBgCheck(130.0, -1.0, 0.0))
        assertEquals(SplitBgCheck.Allowed, splitBgCheck(160.0, -1.0, -1.0))
        assertEquals(SplitBgCheck.Allowed, splitBgCheck(130.0, 0.0, 0.0))
    }

    @Test
    fun rapidFallNeedsAllThreeDeltasUnderPointOne() {
        val under = -0.2 * Constants.MMOLL_TO_MGDL
        val over = -0.05 * Constants.MMOLL_TO_MGDL
        val exact = -0.1 * Constants.MMOLL_TO_MGDL
        assertTrue(splitRapidFall(under, under, under))
        assertFalse(splitRapidFall(under, under, over))
        assertFalse(splitRapidFall(exact, exact, exact))
        assertFalse(splitRapidFall(null, under, under))
    }
}
