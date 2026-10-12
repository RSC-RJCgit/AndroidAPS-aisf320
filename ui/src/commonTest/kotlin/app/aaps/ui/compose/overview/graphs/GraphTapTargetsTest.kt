package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphTapTargetsTest {
    @Test fun sameTimestampIsPreferredOverNearbyCalculation() {
        assertEquals(100_000L, matchingCalculationTimestamp(100_000L, listOf(99_500L, 100_000L)))
    }

    @Test fun ambiguousOrUnrelatedRecordsDoNotOpenTheWrongCalculation() {
        assertNull(matchingCalculationTimestamp(100_000L, listOf(99_500L, 100_500L)))
        assertNull(matchingCalculationTimestamp(100_000L, listOf(100_000L, 100_000L)))
        assertNull(matchingCalculationTimestamp(100_000L, listOf(101_001L)))
        assertEquals(100_500L, matchingCalculationTimestamp(100_000L, listOf(100_500L)))
    }
}
