package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class BasalBetweenTest {

    private val hour = 3_600_000L

    // 1.0 U/h for the first hour, 0.0 for the second, 2.0 for the third. The last point only ends the previous step.
    private val points = listOf(
        GraphDataPoint(0L, 1.0),
        GraphDataPoint(hour, 0.0),
        GraphDataPoint(2 * hour, 2.0),
        GraphDataPoint(3 * hour, 2.0),
    )

    @Test
    fun wholeStepsAddUp() {
        assertEquals(3.0, basalBetween(points, 0L, 3 * hour), 1e-9)
    }

    @Test
    fun partialStepsAreProRated() {
        // Half an hour at 1.0, then half an hour at 0.0.
        assertEquals(0.5, basalBetween(points, hour / 2, hour + hour / 2), 1e-9)
    }

    @Test
    fun emptyOrReversedRangeIsZero() {
        assertEquals(0.0, basalBetween(points, hour, hour), 1e-9)
        assertEquals(0.0, basalBetween(points, 2 * hour, hour), 1e-9)
        assertEquals(0.0, basalBetween(emptyList(), 0L, hour), 1e-9)
    }
}
