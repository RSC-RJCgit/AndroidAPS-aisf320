package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentGraphPeakTest {
    private val now = 10_000_000L

    @Test
    fun historicalMaximumExcludesOldFutureAndInvalidValues() {
        val peak = GraphDataPoint(now - 30 * 60_000L, 5.4)
        assertEquals(peak, lastHourPeak(listOf(
            GraphDataPoint(now - 61 * 60_000L, 20.0),
            peak,
            GraphDataPoint(now - 10 * 60_000L, 3.0),
            GraphDataPoint(now - 5 * 60_000L, Double.NaN),
            GraphDataPoint(now + 60_000L, 30.0),
        ), now))
    }

    @Test
    fun emptyHourHasNoIobLabel() {
        assertNull(lastHourPeak(listOf(GraphDataPoint(now - 61 * 60_000L, 4.0)), now))
    }

    @Test
    fun stepAcrossHourBoundaryUsesItsRateAndAnchorsInsideColumn() {
        assertEquals(GraphDataPoint(now - 45 * 60_000L, 2.5), lastHourPeak(listOf(
            GraphDataPoint(now - 90 * 60_000L, 2.5),
            GraphDataPoint(now - 30 * 60_000L, 1.0),
        ), now, stepped = true))
    }

    @Test
    fun stepChangeUsesNewRateAtSharedTimestamp() {
        assertEquals(GraphDataPoint(now - 10 * 60_000L, 1.0), lastHourPeak(listOf(
            GraphDataPoint(now - 20 * 60_000L, 5.0),
            GraphDataPoint(now - 20 * 60_000L, 1.0),
        ), now, stepped = true))
    }

    @Test
    fun closingStepPointDoesNotMoveLabelToTheEndOfTheGraph() {
        assertEquals(GraphDataPoint(now - 30 * 60_000L, 2.5), lastHourPeak(listOf(
            GraphDataPoint(now - 90 * 60_000L, 2.5),
            GraphDataPoint(now, 2.5),
        ), now, stepped = true))
    }

    @Test
    fun highestIobIsSignedValueRatherThanAbsoluteMagnitude() {
        assertEquals(1.0, lastHourPeak(listOf(
            GraphDataPoint(now - 30 * 60_000L, -4.0),
            GraphDataPoint(now - 20 * 60_000L, 1.0),
        ), now)?.value)
    }

    @Test
    fun oneLabelPerHourCountedBackFromNowNewestFirst() {
        assertEquals(listOf(
            GraphDataPoint(now - 20 * 60_000L, 2.0),
            GraphDataPoint(now - 70 * 60_000L, 3.0),
            GraphDataPoint(now - 150 * 60_000L, 1.5),
        ), hourlyPeaks(listOf(
            GraphDataPoint(now - 150 * 60_000L, 1.5),
            GraphDataPoint(now - 100 * 60_000L, 0.5),
            GraphDataPoint(now - 70 * 60_000L, 3.0),
            GraphDataPoint(now - 50 * 60_000L, 1.0),
            GraphDataPoint(now - 20 * 60_000L, 2.0),
        ), now))
    }

    @Test
    fun stepLabelStaysInsideItsOwnHour() {
        // A 2.0 rate runs from 90 min ago to now. Its older hour label sits halfway between 90 and 60 min ago.
        assertEquals(listOf(
            GraphDataPoint(now - 30 * 60_000L, 2.0),
            GraphDataPoint(now - 75 * 60_000L, 2.0),
        ), hourlyPeaks(listOf(
            GraphDataPoint(now - 90 * 60_000L, 2.0),
            GraphDataPoint(now, 2.0),
        ), now, stepped = true))
    }
}
