package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.google.common.truth.Truth.assertThat
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression coverage for [SecondaryGraphCompose]'s dual-axis / windowing helper functions,
 * extracted (private -> internal) during the Step 4 graph-scale refactor specifically so they
 * could be unit tested. Each test ties back to a specific bug found and fixed during that work —
 * see the KDoc on the function under test for the full rationale.
 */
internal class SecondaryGraphScaleHelpersTest {

    @Nested
    inner class WindowedYTest {

        @Test
        fun filtersPointsToTheVisibleWindow() {
            val points = listOf(0.0 to 1.0, 5.0 to 2.0, 10.0 to 3.0)
            assertThat(windowedY(points, 4.0, 11.0)).containsExactly(2.0, 3.0)
        }

        @Test
        fun fallsBackToTheFullListWhenTheWindowHasNoPoints() {
            val points = listOf(0.0 to 1.0, 5.0 to 2.0)
            assertThat(windowedY(points, 100.0, 200.0)).containsExactly(1.0, 2.0)
        }

        @Test
        fun nullWindowBoundsReturnEveryPointUnfiltered() {
            val points = listOf(0.0 to 1.0, 5.0 to 2.0)
            assertThat(windowedY(points, null, null)).containsExactly(1.0, 2.0)
        }
    }

    @Nested
    inner class StepValueAtTest {

        @Test
        fun returnsTheValueInEffectAtXFromTheMostRecentPointAtOrBeforeIt() {
            val points = listOf(0.0 to 1.0, 60.0 to 2.0, 120.0 to 3.0)
            assertThat(stepValueAt(points, 90.0)).isEqualTo(2.0)
        }

        @Test
        fun aPointExactlyAtXCountsAsInEffect() {
            val points = listOf(0.0 to 1.0, 60.0 to 2.0, 120.0 to 3.0)
            assertThat(stepValueAt(points, 60.0)).isEqualTo(2.0)
        }

        @Test
        fun fallsBackToTheFirstPointWhenXPrecedesAllData() {
            // The basal-profile-in-a-narrow-zoom-window fix: even scrolled before any recorded
            // change point, the series still has a well-defined value.
            val points = listOf(60.0 to 2.0, 120.0 to 3.0)
            assertThat(stepValueAt(points, 10.0)).isEqualTo(2.0)
        }

        @Test
        fun emptySeriesHasNoValueInEffect() {
            assertThat(stepValueAt(emptyList(), 10.0)).isNull()
        }
    }

    @Nested
    inner class ProcessStepPointsTest {

        /** x is whole minutes from minTimestamp, so a minute count is the readable way to write one. */
        private fun min(m: Long) = m * 60_000L

        @Test
        fun aClosingPointPastTheRightEdgeIsClampedToTheEdgeNotDropped() {
            // The reported "missing basal" bug. rebuildBasalGraph closes the series at the newest
            // prediction time, which is hours past an axis that ends at TimeRange.toTime while the
            // predictions overlay is off. Dropping that point left the line stopping at the last
            // basal change instead of at the edge.
            val points = listOf(
                GraphDataPoint(min(0), 1.0),
                GraphDataPoint(min(30), 2.0),
                GraphDataPoint(min(300), 2.0) // closing point, far past the edge
            )
            val result = processStepPoints(points, minTimestamp = 0L, minX = 0.0, maxX = 60.0)
            assertThat(result).containsExactly(0.0 to 1.0, 30.0 to 2.0, 60.0 to 2.0).inOrder()
        }

        @Test
        fun theClampedPointCarriesTheValueInEffectAtTheEdgeNotTheLastValueOverall() {
            val points = listOf(
                GraphDataPoint(min(0), 1.0),
                GraphDataPoint(min(30), 2.0),
                GraphDataPoint(min(90), 3.0), // a change after the edge must not leak back into view
                GraphDataPoint(min(300), 3.0)
            )
            val result = processStepPoints(points, minTimestamp = 0L, minX = 0.0, maxX = 60.0)
            assertThat(result).containsExactly(0.0 to 1.0, 30.0 to 2.0, 60.0 to 2.0).inOrder()
        }

        @Test
        fun aSeriesThatAlreadyEndsExactlyAtTheEdgeIsLeftAlone() {
            val points = listOf(GraphDataPoint(min(0), 1.0), GraphDataPoint(min(60), 1.0))
            val result = processStepPoints(points, minTimestamp = 0L, minX = 0.0, maxX = 60.0)
            assertThat(result).containsExactly(0.0 to 1.0, 60.0 to 1.0).inOrder()
        }

        @Test
        fun anEmptySeriesStaysEmpty() {
            assertThat(processStepPoints(emptyList(), minTimestamp = 0L, minX = 0.0, maxX = 60.0)).isEmpty()
        }

        @Test
        fun aSeriesLyingEntirelyPastTheEdgeContributesNothing() {
            val points = listOf(GraphDataPoint(min(90), 1.0), GraphDataPoint(min(120), 2.0))
            assertThat(processStepPoints(points, minTimestamp = 0L, minX = 0.0, maxX = 60.0)).isEmpty()
        }
    }

    @Nested
    inner class WindowedPrimaryYTest {

        @Test
        fun unionsYValuesAcrossAllPrimaryLayerSeriesWindowed() {
            val iob = listOf(0.0 to 1.0, 10.0 to 2.0)
            val cob = listOf(0.0 to 5.0, 10.0 to 6.0)
            val simple = listOf(SeriesType.BGI to listOf(0.0 to 7.0, 10.0 to 8.0))
            val devSlopeMin = listOf(0.0 to -1.0, 10.0 to -2.0)
            val deviations = ProcessedDeviationLines(allX = listOf(0.0, 10.0), series = mapOf(DeviationType.POSITIVE to listOf(3.0, 4.0)))

            val result = windowedPrimaryY(5.0, 10.0, iob, cob, emptyList(), simple, devSlopeMin, deviations)

            assertThat(result).containsExactly(2.0, 6.0, 8.0, -2.0, 4.0)
        }

        @Test
        fun aSeriesWithNothingInWindowContributesNothingWithoutTriggeringAWholeSetFallback() {
            // Documents the key property: windowed points from one series are never mixed with
            // unwindowed points from another. IOB has no point in [5,10]; COB does. The union as a
            // whole is non-empty (thanks to COB), so IOB must NOT fall back to its own full range.
            val iob = listOf(0.0 to 1.0, 2.0 to 1.5)
            val cob = listOf(0.0 to 5.0, 10.0 to 6.0)

            val result = windowedPrimaryY(5.0, 10.0, iob, cob, emptyList(), emptyList(), emptyList(), null)

            assertThat(result).containsExactly(6.0)
        }

        /**
         * Carb markers count towards the axis, because they are drawn on it.
         *
         * `hasPrimaryData` is taken from the model, which includes the carb markers, so a graph
         * holding nothing else still claims to have data - and the "no data" branch that would
         * anchor a 0..1 axis is skipped. If the range union left the markers out it was empty, so
         * every branch needing it returned null too and the axis fell through to Vico's raw
         * auto-range over the model.
         *
         * That gap is open on every cold start: carb markers come straight from the database while
         * COB waits for the calculation workflow. Measured on a Pixel with COB+ABS after a restart -
         * `primaryY=[null..null] n=0` against `modelY=[0.0..43.0] slots=[CarbsMarker]` - and the COB
         * curve, once it arrived at 148, was drawn straight through the top of a 0..44 axis.
         */
        @Test
        fun carbMarkersAloneStillGiveTheAxisSomethingToScaleTo() {
            val carbs = listOf(6.0 to 36.0, 8.0 to 43.0)

            val result = windowedPrimaryY(5.0, 10.0, emptyList(), emptyList(), carbs, emptyList(), emptyList(), null)

            assertThat(result).containsExactly(36.0, 43.0)
        }

        @Test
        fun fallsBackToTheFullUnionOnlyWhenEverySeriesIsEmptyInWindow() {
            val iob = listOf(0.0 to 1.0, 10.0 to 2.0)
            val cob = listOf(0.0 to 5.0, 10.0 to 6.0)

            val result = windowedPrimaryY(1000.0, 2000.0, iob, cob, emptyList(), emptyList(), emptyList(), null)

            assertThat(result).containsExactly(1.0, 2.0, 5.0, 6.0)
        }
    }

    @Nested
    inner class FractionAlignedRangeTest {

        @Test
        fun zeroFloorDataMin0IsStretchedDownwardToHitTheTargetFraction() {
            val (min, max) = fractionAlignedRange(dataMin = 0.0, dataMax = 82.0, targetFraction = 0.2)
            assertThat(min).isEqualTo(-20.5)
            assertThat(max).isEqualTo(82.0)
        }

        @Test
        fun tightenAtMinBranchKeepsTheRealMinAndOnlyExpandsTheMax() {
            val (min, max) = fractionAlignedRange(dataMin = -5.0, dataMax = 100.0, targetFraction = 0.03)
            assertThat(min).isEqualTo(-5.0)
            assertThat(max).isWithin(1e-9).of(161.66666666666666)
        }

        @Test
        fun zeroTargetFractionKeepsTheRealDataUnchangedExceptCoercingAPositiveMinTo0() {
            assertThat(fractionAlignedRange(5.0, 82.0, 0.0)).isEqualTo(0.0 to 82.0)
            assertThat(fractionAlignedRange(-3.0, 82.0, 0.0)).isEqualTo(-3.0 to 82.0)
        }

        @Test
        fun neverClipsTheRealDataAcrossARangeOfFractionsAndDataShapes() {
            val cases = listOf(
                Triple(0.0, 82.0, 0.2),
                Triple(-5.0, 100.0, 0.03),
                Triple(-1.0, 1.0, 0.5),
                Triple(0.0, 0.1, 0.4),
            )
            for ((dataMin, dataMax, fraction) in cases) {
                val (min, max) = fractionAlignedRange(dataMin, dataMax, fraction)
                assertThat(min).isAtMost(dataMin)
                assertThat(max).isAtLeast(dataMax)
            }
        }
    }

    @Nested
    inner class FractionAlignedNiceRangeTest {

        @Test
        fun stretchedBoundIsNiceIfiedNotLeftAsARawFractionResult() {
            val (min, max) = fractionAlignedNiceRange(niceMin = 0.0, niceMax = 100.0, targetFraction = 0.2)
            assertThat(min).isEqualTo(-25.0)
            assertThat(max).isEqualTo(100.0)
        }

        @Test
        fun largerFractionStretchesFurtherStillNice() {
            val (min, max) = fractionAlignedNiceRange(niceMin = 0.0, niceMax = 50.0, targetFraction = 0.5)
            assertThat(min).isEqualTo(-50.0)
            assertThat(max).isEqualTo(50.0)
        }

        @Test
        fun zeroTargetFractionLeavesTheNiceRangeUntouched() {
            assertThat(fractionAlignedNiceRange(0.0, 100.0, 0.0)).isEqualTo(0.0 to 100.0)
        }

        @Test
        fun neverClipsTheOriginalNiceBounds() {
            val (min, max) = fractionAlignedNiceRange(niceMin = -10.0, niceMax = 100.0, targetFraction = 0.3)
            assertThat(min).isAtMost(-10.0)
            assertThat(max).isAtLeast(100.0)
        }
    }

    @Nested
    inner class PivotZeroFloorPairingTest {

        @Test
        fun sENSPivotFlatAt100PairedWithCOBSnapsTo95100105COBHalfCoversItsOwnMax() {
            val pairing = pivotZeroFloorPairing(
                pivotY = listOf(100.0, 100.0), zeroFloorY = listOf(0.0, 82.0), pivot = 100.0, minDeviation = 5.0, pivotTickCount = 3
            )
            assertThat(pairing.pivotNice.min).isEqualTo(95.0)
            assertThat(pairing.pivotNice.max).isEqualTo(105.0)
            assertThat(pairing.zeroFloorHalf).isEqualTo(100.0)
            assertThat(pairing.zeroFloorMin).isEqualTo(0.0)
        }

        @Test
        fun dEVSLOPEPivotPairedWithABGIStyleSeriesThatHasItsOwnSmallNegativeSliver() {
            val pairing = pivotZeroFloorPairing(
                pivotY = listOf(-2.0, 3.0), zeroFloorY = listOf(-0.5, 12.0), pivot = 0.0, minDeviation = 0.0, pivotTickCount = 5
            )
            assertThat(pairing.pivotNice.min).isEqualTo(-6.0)
            assertThat(pairing.pivotNice.max).isEqualTo(6.0)
            assertThat(pairing.zeroFloorHalf).isEqualTo(20.0)
            // The zero-floor side's own negative sliver must be preserved, not silently discarded
            assertThat(pairing.zeroFloorMin).isEqualTo(-0.5)
        }

        @Test
        fun pivotSideIsAlwaysExactlyCenteredOnItsOwnPivot() {
            val pairing = pivotZeroFloorPairing(
                pivotY = listOf(60.0, 108.0), zeroFloorY = listOf(0.0, 20.0), pivot = 100.0, minDeviation = 5.0, pivotTickCount = 5
            )
            assertThat((pairing.pivotNice.min + pairing.pivotNice.max) / 2.0).isEqualTo(100.0)
        }
    }

    @Nested
    inner class AlignZerosTest {

        @Test
        fun bothSeriesCrossingZeroSymmetrizeIndependentlyZeroAt50PercentOfEachOwnAxis() {
            val result = alignZerosAtOrigin(aMin = -2.0, aMax = 8.0, bMin = -1.0, bMax = 1.0)
            assertThat(result).isEqualTo(AlignedRanges(-8.0, 8.0, -1.0, 1.0))
        }

        @Test
        fun bothStrictlyPositivePinsZeroToTheBottomOfBothAxes() {
            val result = alignZerosAtOrigin(aMin = 1.0, aMax = 5.0, bMin = 2.0, bMax = 10.0)
            assertThat(result).isEqualTo(AlignedRanges(0.0, 5.0, 0.0, 10.0))
        }

        @Test
        fun bothStrictlyNegativePinsZeroToTheTopOfBothAxes() {
            val result = alignZerosAtOrigin(aMin = -5.0, aMax = -1.0, bMin = -10.0, bMax = -2.0)
            assertThat(result).isEqualTo(AlignedRanges(-5.0, 0.0, -10.0, 0.0))
        }

        @Test
        fun oneAllPositiveAndOneAllNegativeHasNoUsefulSharedAlignment() {
            assertThat(alignZerosAtOrigin(aMin = 1.0, aMax = 5.0, bMin = -10.0, bMax = -2.0)).isNull()
        }

        @Test
        fun bothCompletelyFlatAtZeroIsDegenerateAndReturnsNull() {
            assertThat(alignZerosAtOrigin(aMin = 0.0, aMax = 0.0, bMin = 0.0, bMax = 0.0)).isNull()
        }

        @Test
        fun oneSideFlatAtZeroBorrowsTheOtherSideSHalfSoTheZeroLineStillLandsAtMid() {
            val result = alignZerosAtOrigin(aMin = 0.0, aMax = 0.0, bMin = -5.0, bMax = 5.0)
            assertThat(result).isEqualTo(AlignedRanges(-5.0, 5.0, -5.0, 5.0))
        }

        @Test
        fun pivotShiftedAlignmentLandsThePivotAndTheZeroAtTheSameFraction() {
            // SENS (90-110%, pivot 100) combined with IOB (-5..50, pivot 0)
            val result = alignZeros(aMin = 90.0, aMax = 110.0, bMin = -5.0, bMax = 50.0, aPivot = 100.0, bPivot = 0.0)
            assertThat(result).isEqualTo(AlignedRanges(90.0, 110.0, -50.0, 50.0))
            val nonNull = requireNotNull(result)
            val pivotFraction = (100.0 - nonNull.aMin) / (nonNull.aMax - nonNull.aMin)
            val zeroFraction = (0.0 - nonNull.bMin) / (nonNull.bMax - nonNull.bMin)
            assertThat(pivotFraction).isWithin(1e-9).of(zeroFraction)
        }
    }

    @Nested
    inner class ClampedVerticalAxisItemPlacerTest {

        private val context = mock<CartesianDrawingContext>()

        @Test
        fun visibleMaxClampDropsLabelsAndLinesAboveTheBound() {
            val delegate = mock<VerticalAxis.ItemPlacer>()
            whenever(delegate.getLabelValues(any(), any(), any(), any())).thenReturn(listOf(0.0, 1.0, 2.0, 3.0))
            whenever(delegate.getLineValues(any(), any(), any(), any())).thenReturn(listOf(0.0, 1.0, 2.0, 3.0))
            val placer = ClampedVerticalAxisItemPlacer(delegate, visibleMax = { 2.0 })

            assertThat(placer.getLabelValues(context, 100f, 20f, Axis.Position.Vertical.Start)).containsExactly(0.0, 1.0, 2.0)
            assertThat(placer.getLineValues(context, 100f, 20f, Axis.Position.Vertical.Start)).containsExactly(0.0, 1.0, 2.0)
        }

        @Test
        fun visibleMinClampDropsLabelsBelowTheBoundHidesAFakeNegativeTick() {
            val delegate = mock<VerticalAxis.ItemPlacer>()
            whenever(delegate.getLabelValues(any(), any(), any(), any())).thenReturn(listOf(-82.0, -41.0, 0.0, 41.0, 82.0))
            val placer = ClampedVerticalAxisItemPlacer(delegate, visibleMin = { 0.0 })

            assertThat(placer.getLabelValues(context, 100f, 20f, Axis.Position.Vertical.Start)).containsExactly(0.0, 41.0, 82.0)
        }

        @Test
        fun noBoundsConfiguredPassesEverythingThroughUnfiltered() {
            val delegate = mock<VerticalAxis.ItemPlacer>()
            whenever(delegate.getLabelValues(any(), any(), any(), any())).thenReturn(listOf(-5.0, 0.0, 5.0))
            val placer = ClampedVerticalAxisItemPlacer(delegate)

            assertThat(placer.getLabelValues(context, 100f, 20f, Axis.Position.Vertical.Start)).containsExactly(-5.0, 0.0, 5.0)
        }

        @Test
        fun nullLineValuesFromTheDelegatePassThroughAsNull() {
            val delegate = mock<VerticalAxis.ItemPlacer>()
            whenever(delegate.getLineValues(any(), any(), any(), any())).thenReturn(null)
            val placer = ClampedVerticalAxisItemPlacer(delegate, visibleMax = { 2.0 })

            assertThat(placer.getLineValues(context, 100f, 20f, Axis.Position.Vertical.Start)).isNull()
        }
    }
}
