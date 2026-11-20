package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every basal column above the profile basal gets a label, as well as the hourly peaks (2026-11-20). */
class AboveProfileBasalTest {

    private val min = 60_000L

    // Profile 0.5 U/h throughout. Columns: 0.5 (0-10), 1.2 (10-20), 0.0 (20-30), 0.8 (30-40), 0.5 (40-60)
    private val profile = listOf(GraphDataPoint(0L, 0.5))
    private val actual = listOf(
        GraphDataPoint(0L, 0.5),
        GraphDataPoint(10 * min, 1.2),
        GraphDataPoint(20 * min, 0.0),
        GraphDataPoint(30 * min, 0.8),
        GraphDataPoint(40 * min, 0.5),
    )

    @Test
    fun onlyColumnsAboveTheProfileAreLabelledInTheirMiddleNewestFirst() {
        assertEquals(
            listOf(GraphDataPoint(35 * min, 0.8), GraphDataPoint(15 * min, 1.2)),
            aboveProfileBasalColumns(actual, profile, 60 * min)
        )
    }

    @Test
    fun aColumnAtTheProfileRateIsNotLabelled() {
        assertEquals(emptyList(), aboveProfileBasalColumns(listOf(GraphDataPoint(0L, 0.5)), profile, 60 * min))
    }

    @Test
    fun theProfileRateAtTheColumnsMiddleCounts() {
        // Profile goes up to 1.0 at 12 min; the 0.8 column (10-20, middle 15) is under it
        val stepped = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(12 * min, 1.0))
        val columns = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(10 * min, 0.8), GraphDataPoint(20 * min, 1.0))
        assertEquals(emptyList(), aboveProfileBasalColumns(columns, stepped, 30 * min))
    }

    @Test
    fun theHourlyPeakAndTheAboveProfileColumnsAreJoinedOnce() {
        // The hour's peak is the 1.2 column (middle 15 min), also above the profile: it is listed once
        assertEquals(
            listOf(GraphDataPoint(35 * min, 0.8), GraphDataPoint(15 * min, 1.2)),
            basalLabelPoints(actual, profile, 60 * min)
        )
    }
}
