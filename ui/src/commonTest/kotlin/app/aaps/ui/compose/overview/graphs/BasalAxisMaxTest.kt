package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/** The basal scale looks one screen-width beyond each side of the view, so scrolling does not run into a taller basal. */
class BasalAxisMaxTest {

    private val hour = 3_600_000L

    // 0.5 U/h until hour 6, a 3.0 U/h temp from hour 6 to 7, then 0.5 U/h again up to hour 12.
    private val data = BasalGraphData(
        profileBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(12 * hour, 0.5)),
        actualBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(6 * hour, 3.0), GraphDataPoint(7 * hour, 0.5), GraphDataPoint(12 * hour, 0.5)),
        maxBasal = 3.0
    )

    @Test
    fun aTempWithinOneScreenToTheRightCountsAlready() {
        // View 3..5 h (2 h wide): the temp at 6 h is inside the 2 h look-ahead.
        assertEquals(3.0 / 0.5, basalAxisMax(data, 3 * hour to 5 * hour, 0.5), 1e-9)
    }

    @Test
    fun aTempWithinOneScreenToTheLeftCountsAlready() {
        // View 8..10 h: the temp at 6..7 h is inside the 2 h look-back.
        assertEquals(3.0 / 0.5, basalAxisMax(data, 8 * hour to 10 * hour, 0.5), 1e-9)
    }

    @Test
    fun aTempFurtherAwayDoesNotCount() {
        // View 0..1 h (1 h wide): the look-ahead reaches 2 h, the temp starts at 6 h.
        assertEquals(0.5 / 0.5, basalAxisMax(data, 0L to hour, 0.5), 1e-9)
    }

    @Test
    fun noWindowUsesTheHighestBasal() {
        assertEquals(3.0 / 0.22, basalAxisMax(data, null, 0.22), 1e-9)
    }
}
