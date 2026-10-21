package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphTapTargetsTest {
    private val bolus = 1_000_000L

    @Test fun sameTimestampIsPreferredOverNearbyCalculation() {
        assertEquals(bolus, matchingCalculationTimestamp(bolus, 1.0, listOf(bolus - 500L to 1.0, bolus to 3.0)))
    }

    @Test fun calculationSavedBeforeThePumpConfirmedIsFound() {
        // OK pressed 40 s before the pump confirmed the bolus.
        assertEquals(bolus - 40_000L, matchingCalculationTimestamp(bolus, 1.2, listOf(bolus - 40_000L to 1.2)))
    }

    @Test fun closestTimeWithEnoughInsulinWins() {
        val calculations = listOf(bolus - 120_000L to 2.0, bolus - 60_000L to 0.8, bolus - 30_000L to 0.85)
        assertEquals(bolus - 30_000L, matchingCalculationTimestamp(bolus, 0.85, calculations))
        assertEquals(bolus - 120_000L, matchingCalculationTimestamp(bolus, 2.0, calculations))
    }

    @Test fun doseCutByMaxBolusFindsItsOwnCalculation() {
        // 22 Oct: 08:06 calculation 3.70 U cancelled, 08:16 calculation 3.75 U given as 2.50 U (max bolus), 6 s later.
        val calculations = listOf(bolus - 606_000L + 6_000L to 3.70, bolus - 6_000L to 3.75)
        assertEquals(bolus - 6_000L, matchingCalculationTimestamp(bolus, 2.50, calculations))
    }

    @Test fun bolusLargerThanEveryCalculationTakesTheClosestInTime() {
        val calculations = listOf(bolus - 300_000L to 1.0, bolus - 20_000L to 0.5)
        assertEquals(bolus - 20_000L, matchingCalculationTimestamp(bolus, 2.0, calculations))
    }

    @Test fun calculationsOutsideTheWindowAreIgnored() {
        assertNull(matchingCalculationTimestamp(bolus, 1.0, listOf(bolus - CALCULATION_BEFORE_BOLUS_MS - 1L to 1.0)))
        assertNull(matchingCalculationTimestamp(bolus, 1.0, listOf(bolus + CALCULATION_AFTER_BOLUS_MS + 1L to 1.0)))
        assertNull(matchingCalculationTimestamp(bolus, 1.0, emptyList()))
    }
}
