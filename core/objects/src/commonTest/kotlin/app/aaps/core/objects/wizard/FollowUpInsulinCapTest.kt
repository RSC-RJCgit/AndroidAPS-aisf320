package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowUpInsulinCapTest {
    @Test fun followUpDoseAppliesHalfOfInsulinRequirementReduction() {
        assertEquals(0.95, followUpInsulinCap(1.20, 700L, 1_000_000L, 1_000_000L, 0.05)!!, 0.000001)
        assertEquals(0.75, followUpInsulinCap(1.13, 430L, 1_000_000L, 1_000_000L, 0.05)!!, 0.000001)
        assertEquals(0.40, followUpInsulinCap(0.43, 2000L, 1_000_000L, 1_000_000L, 0.05)!!, 0.000001)
    }

    @Test fun fullCapForProteinFatParts() {
        assertEquals(0.70, followUpInsulinCap(1.20, 700L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)!!, 0.000001)
        assertEquals(0.05, followUpInsulinCap(0.50, 90L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)!!, 0.000001)
        assertEquals(0.50, followUpInsulinCap(0.50, 1420L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)!!, 0.000001)
    }

    @Test fun iobRiseHoldAbove1point2UnitsIn15Minutes() {
        assertTrue(fpuIobRiseHolds(1.21, 0.0))
        assertFalse(fpuIobRiseHolds(1.19, 0.0))
        assertTrue(fpuIobRiseHolds(2.04, 0.11))   // 20 Nov 20:08, part 7 of 8
        assertFalse(fpuIobRiseHolds(1.44, 0.29))  // 15 Nov 16:45, +1.15 U, BG stayed above 7
    }

    @Test fun zeroNegativeAndSubStepRequirementsDoNotDeliver() {
        for (requirement in listOf(0L, -1000L, 49L))
            assertEquals(0.0, followUpInsulinCap(1.13, requirement, 1_000_000L, 1_000_000L, 0.05))
    }

    @Test fun freshnessIncludesFiveMinutesAndRejectsMissingStaleOrFuture() {
        assertEquals(0.75, followUpInsulinCap(1.0, 500L, 1_000_000L, 1_300_000L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 0L, 1_300_000L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 1_000_000L, 1_300_001L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 1_300_001L, 1_300_000L, 0.05))
    }
}
