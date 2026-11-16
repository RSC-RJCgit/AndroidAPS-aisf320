package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/** The basal scale fits the basal in view, including the rate already running at the left edge (2026-11-16). */
class BasalAxisMaxTest {

    private val hour = 3_600_000L

    // 0.5 U/h until hour 6, a 3.0 U/h temp from hour 6 to 7, then 0.5 U/h again up to hour 12.
    private val data = BasalGraphData(
        profileBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(12 * hour, 0.5)),
        actualBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(6 * hour, 3.0), GraphDataPoint(7 * hour, 0.5), GraphDataPoint(12 * hour, 0.5)),
        maxBasal = 3.0
    )

    @Test
    fun aTempJustOffScreenToTheRightDoesNotCount() {
        // View 3..5 h: the temp starts at 6 h, outside the view.
        assertEquals(0.5 / 0.5, basalAxisMax(data, 3 * hour to 5 * hour, 0.5), 1e-9)
    }

    @Test
    fun aTempJustOffScreenToTheLeftDoesNotCount() {
        // View 8..10 h: the temp ended at 7 h.
        assertEquals(0.5 / 0.5, basalAxisMax(data, 8 * hour to 10 * hour, 0.5), 1e-9)
    }

    @Test
    fun aTempInViewCounts() {
        // View 5..8 h: the temp at 6..7 h is in view.
        assertEquals(3.0 / 0.5, basalAxisMax(data, 5 * hour to 8 * hour, 0.5), 1e-9)
    }

    @Test
    fun aTempRunningAtTheLeftEdgeCounts() {
        // View 6.5..8 h: the temp started before the view and is still running at its left edge.
        assertEquals(3.0 / 0.5, basalAxisMax(data, (6.5 * hour).toLong() to 8 * hour, 0.5), 1e-9)
    }

    @Test
    fun noWindowUsesTheHighestBasal() {
        assertEquals(3.0 / 0.22, basalAxisMax(data, null, 0.22), 1e-9)
    }
}
