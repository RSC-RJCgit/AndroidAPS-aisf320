package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopBandScaleTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun rangeUsesOnlyTheLast24Hours() {
        val now = 100 * hour
        val points = listOf(
            (now - 30 * hour) to 9.0, // older than 24 hours, ignored
            (now - 10 * hour) to 0.2,
            (now - 1 * hour) to 0.4,
        )
        val range = assertNotNull(bandRange(points, now))
        assertEquals(0.2, range.min)
        assertEquals(0.4, range.max)
    }

    @Test
    fun rangeFallsBackToAllPointsWhenNothingIsRecent() {
        val now = 100 * hour
        val range = assertNotNull(bandRange(listOf((now - 40 * hour) to 1.0, (now - 30 * hour) to 3.0), now))
        assertEquals(1.0, range.min)
        assertEquals(3.0, range.max)
    }

    @Test
    fun noPointsMeansNoRange() {
        assertNull(bandRange(emptyList(), 0L))
    }

    @Test
    fun lowestSitsOnTheBottomHighestOnTheTopAndFlatInTheMiddle() {
        val range = BandRange(0.1, 0.3)
        assertEquals(0.0, range.fraction(0.1))
        assertEquals(1.0, range.fraction(0.3))
        assertEquals(0.5, range.fraction(0.2), 1e-9)
        assertEquals(0.5, BandRange(0.2, 0.2).fraction(0.2))
        assertEquals(1.0, range.fraction(5.0))
    }

    @Test
    fun legendLabelDropsNeedlessZeros() {
        assertEquals("0.1-0.3", BandRange(0.1, 0.3).label())
        assertEquals("0.25-1", BandRange(0.254, 1.0).label())
    }

    @Test
    fun activityChartTopMatches3426() {
        assertEquals(14.0, activityChartTop(10.0, 10.0, mgdl = false))
        assertEquals(14.0, activityChartTop(9.0, 10.0, mgdl = false)) // the high mark is the floor
        assertEquals(18.0, activityChartTop(13.6, 10.0, mgdl = false))
        assertEquals(280.0, activityChartTop(180.0, 180.0, mgdl = true)) // 180 / 40 = 4.5 rounds up to 5
        // 80% of the panel is above the green range top
        assertTrue(0.8 * activityChartTop(10.0, 10.0, mgdl = false) > 10.0)
        assertTrue(0.8 * activityChartTop(180.0, 180.0, mgdl = true) > 180.0)
    }

    @Test
    fun bandSitsAboveTheHighestValueThatMustStayClear() {
        listOf(
            Triple(70.0, 180.0, 0.28),
            Triple(40.0, 250.0, 0.28),
            Triple(3.9, 10.0, 0.28),
            Triple(3.0, 14.2, 0.28),
        ).forEach { (low, high, fraction) ->
            val scale = axisWithTopBand(low, high, fraction)
            val bandBottom = scale.max - fraction * (scale.max - scale.min)
            assertTrue(bandBottom >= high - 1e-6, "band bottom $bandBottom must be at or above $high for $low..$high")
            assertTrue(scale.min <= low)
        }
    }
}
