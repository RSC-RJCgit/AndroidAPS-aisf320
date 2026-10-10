package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeightRevertTest {

    @Test
    fun aDifferentPostMealWeightReturnsWhenGlucoseIsUnder85() {
        val decision = sample(currentPp = 0.15, glucoseMgdl = 140.0)
        assertTrue(decision.restorePp)
        assertFalse(decision.restoreAcce)
        assertEquals("bg", decision.reason)
    }

    @Test
    fun aRaisedAccelerationWeightReturnsAndALowerOneStays() {
        val raised = sample(currentAcce = 0.95, baselineAcce = 0.70, glucoseMgdl = 140.0)
        val lowered = sample(currentAcce = 0.35, baselineAcce = 0.70, glucoseMgdl = 140.0)
        assertTrue(raised.restoreAcce)
        assertFalse(lowered.restoreAcce)
    }

    @Test
    fun aRecentBoostBlocksBothRestores() {
        val decision = sample(currentPp = 0.15, currentAcce = 0.95, glucoseMgdl = 140.0, recentBoost = true)
        assertFalse(decision.restorePp)
        assertFalse(decision.restoreAcce)
        assertEquals("", decision.reason)
    }

    @Test
    fun aWeightAlreadyAtBaselineStays() {
        val decision = sample(currentPp = 0.0805, baselinePp = 0.08, glucoseMgdl = 140.0)
        assertFalse(decision.restorePp)
    }

    @Test
    fun highGlucoseWithNoOtherTriggerStaysRaised() {
        val decision = sample(
            currentPp = 0.15,
            currentAcce = 0.95,
            glucoseMgdl = 180.0,
            noRecentHigh = false,
        )
        assertFalse(decision.restorePp)
        assertFalse(decision.restoreAcce)
    }

    @Test
    fun stepsCanRestoreWhileGlucoseIsHigh() {
        val decision = sample(
            currentPp = 0.15,
            glucoseMgdl = 180.0,
            steps5 = 101,
            noRecentHigh = false,
        )
        assertTrue(decision.restorePp)
        assertEquals("activity", decision.reason)
    }

    private fun sample(
        currentPp: Double = 0.08,
        baselinePp: Double = 0.08,
        currentAcce: Double = 0.70,
        baselineAcce: Double = 0.70,
        glucoseMgdl: Double = 140.0,
        steps5: Int = 0,
        steps30: Int = 0,
        steps60: Int = 0,
        noRecentHigh: Boolean = false,
        recentBoost: Boolean = false,
    ) = ppAcceWeightRevert(
        currentPp = currentPp,
        baselinePp = baselinePp,
        currentAcce = currentAcce,
        baselineAcce = baselineAcce,
        glucoseMgdl = glucoseMgdl,
        steps5 = steps5,
        steps30 = steps30,
        steps60 = steps60,
        noRecentHigh = noRecentHigh,
        recentBoost = recentBoost,
    )
}
