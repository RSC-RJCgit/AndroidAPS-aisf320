package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mcap (2026-10-26): profile strength from the real ISF values, the name only as a fallback, and the cap from it. */
class McapStrengthTest {

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
