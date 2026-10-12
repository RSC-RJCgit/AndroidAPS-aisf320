package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FollowUpInsulinCapTest {
    @Test fun doseIsCappedAndRoundedDown() {
        assertEquals(0.40, followUpInsulinCap(1.13, 430L, 1_000_000L, 1_000_000L, 0.05)!!, 0.000001)
        assertEquals(0.40, followUpInsulinCap(0.43, 2000L, 1_000_000L, 1_000_000L, 0.05)!!, 0.000001)
    }

    @Test fun zeroNegativeAndSubStepRequirementsDoNotDeliver() {
        for (requirement in listOf(0L, -1000L, 49L))
            assertEquals(0.0, followUpInsulinCap(1.13, requirement, 1_000_000L, 1_000_000L, 0.05))
    }

    @Test fun freshnessIncludesFiveMinutesAndRejectsMissingStaleOrFuture() {
        assertEquals(0.5, followUpInsulinCap(1.0, 500L, 1_000_000L, 1_300_000L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 0L, 1_300_000L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 1_000_000L, 1_300_001L, 0.05))
        assertNull(followUpInsulinCap(1.0, 500L, 1_300_001L, 1_300_000L, 0.05))
    }
}
