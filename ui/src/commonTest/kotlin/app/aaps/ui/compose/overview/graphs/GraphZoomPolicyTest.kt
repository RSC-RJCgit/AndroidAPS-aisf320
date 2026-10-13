package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphZoomPolicyTest {
    @Test
    fun shorterDefaultStillKeepsThreeHoursNormal() {
        assertFalse(useCleanGraphForWindow(180.0, 60.0))
        assertTrue(useCleanGraphForWindow(181.0, 60.0))
    }

    @Test
    fun largerDefaultSetsTheThresholdAndZoomingBackRestoresNormal() {
        assertFalse(useCleanGraphForWindow(300.0, 360.0))
        assertFalse(useCleanGraphForWindow(360.0, 360.0))
        assertTrue(useCleanGraphForWindow(361.0, 360.0))
        assertFalse(useCleanGraphForWindow(359.0, 360.0))
    }

    @Test
    fun missingOrInvalidWindowDoesNotHideGraphDetails() {
        assertFalse(useCleanGraphForWindow(null, 180.0))
        assertFalse(useCleanGraphForWindow(Double.NaN, 180.0))
        assertFalse(useCleanGraphForWindow(Double.POSITIVE_INFINITY, 180.0))
    }
}
