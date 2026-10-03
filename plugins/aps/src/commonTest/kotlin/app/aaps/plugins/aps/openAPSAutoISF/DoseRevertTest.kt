package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoseRevertTest {

    @Test
    fun aRaisedIobThresholdReturnsWhenGlucoseIsUnder85() {
        val decision = sample(currentIobTh = 71, glucoseMgdl = 140.0)
        assertTrue(decision.restoreIobTh)
        assertFalse(decision.restoreProfilePercent)
        assertEquals("bg", decision.reason)
    }

    @Test
    fun aRaisedProfilePercentReturnsAndALowerOneStays() {
        val raised = sample(currentProfilePercent = 110, baselineProfilePercent = 100, glucoseMgdl = 140.0)
        val lowered = sample(currentProfilePercent = 50, baselineProfilePercent = 100, glucoseMgdl = 140.0)
        assertTrue(raised.restoreProfilePercent)
        assertFalse(lowered.restoreProfilePercent)
        assertFalse(lowered.restoreIobTh)
    }

    @Test
    fun aRecentBoostBlocksBothRestores() {
        val decision = sample(currentIobTh = 71, currentProfilePercent = 110, glucoseMgdl = 140.0, recentBoost = true)
        assertFalse(decision.restoreIobTh)
        assertFalse(decision.restoreProfilePercent)
    }

    @Test
    fun aHighQuietDayWithARecentHighDoesNotRestore() {
        val decision = sample(
            currentIobTh = 71,
            currentProfilePercent = 110,
            glucoseMgdl = 200.0,
            steps5 = 0,
            steps30 = 0,
            steps60 = 0,
            noRecentHigh = false,
        )
        assertFalse(decision.restoreIobTh)
        assertFalse(decision.restoreProfilePercent)
        assertEquals("", decision.reason)
    }

    @Test
    fun movementRestoresWhenGlucoseIsHigh() {
        val decision = sample(currentIobTh = 71, glucoseMgdl = 200.0, steps5 = 101, noRecentHigh = false)
        assertTrue(decision.restoreIobTh)
        assertEquals("activity", decision.reason)
    }

    @Test
    fun aValueAtTheBaselineStays() {
        val decision = sample(glucoseMgdl = 140.0)
        assertFalse(decision.restoreIobTh)
        assertFalse(decision.restoreProfilePercent)
    }

    private fun sample(
        currentIobTh: Int = 70,
        baselineIobTh: Int = 70,
        currentProfilePercent: Int = 100,
        baselineProfilePercent: Int = 100,
        glucoseMgdl: Double = 180.0,
        steps5: Int = 0,
        steps30: Int = 0,
        steps60: Int = 0,
        noRecentHigh: Boolean = true,
        recentBoost: Boolean = false,
    ) = iobProfileRevert(
        currentIobTh = currentIobTh,
        baselineIobTh = baselineIobTh,
        currentProfilePercent = currentProfilePercent,
        baselineProfilePercent = baselineProfilePercent,
        glucoseMgdl = glucoseMgdl,
        steps5 = steps5,
        steps30 = steps30,
        steps60 = steps60,
        noRecentHigh = noRecentHigh,
        recentBoost = recentBoost,
    )
}
