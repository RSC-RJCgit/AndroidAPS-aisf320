package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mcap (2026-10-26): profile strength from the real ISF values, the name only as a fallback, and the cap from it. */
class McapStrengthTest {

    @Test
    fun quietRecentStepsRestoreTheUsualCap() {
        // The uploaded 14:45 row: S5=0, S30=35, S60=858. The old hourly activity must not halve this cap.
        val walking = mcapWalking(steps5 = 0, steps30 = 35)
        assertFalse(walking)
        assertEquals(2.1, mcapSmbCap30Min(100.0, walking), 0.001)
    }

    @Test
    fun recentWalkingStillHalvesTheCapAtTheExistingThresholds() {
        assertFalse(mcapWalking(steps5 = 20, steps30 = 100))
        assertTrue(mcapWalking(steps5 = 21, steps30 = 0))
        assertTrue(mcapWalking(steps5 = 0, steps30 = 101))
        assertEquals(1.05, mcapSmbCap30Min(100.0, mcapWalking(28, 617)), 0.001)
    }

    @Test
    fun strengthComesFromTheRealIsf() {
        // Standard Tier A ISF 63 mg/dL (3.5 mmol), current 42 mg/dL: 150 %, whatever the name says
        assertEquals(150.0, profileStrengthPercent(63.0, 42.0, "Profile130", 100), 0.01)
    }

    @Test
    fun theNameIsOnlyTheFallback() {
        assertEquals(130.0, profileStrengthPercent(null, 42.0, "Profile130", 100), 0.01)
        assertEquals(165.0, profileStrengthPercent(63.0, null, "Profile150", 110), 0.01)
        assertEquals(120.0, profileStrengthPercent(null, null, "Weekend", 120), 0.01)
    }

    @Test
    fun theCapShrinksForAStrongProfileAndWhileWalking() {
        assertEquals(2.1, mcapSmbCap30Min(100.0, walking = false), 0.001)
        assertEquals(2.1, mcapSmbCap30Min(70.0, walking = false), 0.001)
        assertEquals(1.4, mcapSmbCap30Min(150.0, walking = false), 0.001)
        assertEquals(0.7, mcapSmbCap30Min(150.0, walking = true), 0.001)
    }
}
