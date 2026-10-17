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

    @Test
    fun wideDesktopWindowStaysNormalLonger() {
        // 1600 dp: clean only above 800 minutes
        assertFalse(useCleanGraphForDesktopWindow(360.0, 180.0, 1600.0))
        assertFalse(useCleanGraphForDesktopWindow(800.0, 180.0, 1600.0))
        assertTrue(useCleanGraphForDesktopWindow(801.0, 180.0, 1600.0))
    }

    @Test
    fun narrowDesktopWindowStillKeepsThreeHoursNormal() {
        assertFalse(useCleanGraphForDesktopWindow(180.0, 180.0, 200.0))
        assertTrue(useCleanGraphForDesktopWindow(181.0, 180.0, 200.0))
        assertFalse(useCleanGraphForDesktopWindow(null, 180.0, 200.0))
    }
}
