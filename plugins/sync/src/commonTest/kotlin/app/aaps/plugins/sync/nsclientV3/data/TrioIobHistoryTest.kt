package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrioIobHistoryTest {

    private val now = 1_800_000_000_000L

    @Test
    fun keepsZeroNegativeAndLatestDuplicateInTimeOrder() {
        val history = TrioIobHistory("site").merge(
            listOf(GraphDataPoint(now, 0.0), GraphDataPoint(now - 60_000, -0.2), GraphDataPoint(now, 1.2)), now
        )
        assertEquals(listOf(TrioIobSample(now - 60_000, -0.2), TrioIobSample(now, 1.2)), history.samples)
        assertEquals(0.0, history.merge(listOf(GraphDataPoint(now, 0.0)), now).samples.last().value)
    }

    @Test
    fun rejectsInvalidFutureAndExpiredSamplesWithoutFillingMissingValues() {
        val points = listOf(
            GraphDataPoint(now, Double.NaN), GraphDataPoint(now, Double.POSITIVE_INFINITY),
            GraphDataPoint(now + 120_000, 2.0), GraphDataPoint(now - 8 * 24 * 60 * 60_000L, 3.0)
        )
        assertTrue(TrioIobHistory("site").merge(points, now).samples.isEmpty())
    }

    @Test
    fun storedHistoryKeepsSourceAndSampleTimesAfterReload() {
        val original = TrioIobHistory("source-one").merge(listOf(GraphDataPoint(now, 0.0)), now)
        val encoded = original.encode()
        assertEquals(original, TrioIobHistory.decode(encoded))
    }

    @Test
    fun malformedCacheDoesNotCrashAndBadSamplesDoNotReplaceGoodOnes() {
        assertNull(TrioIobHistory.decode("broken"))
        assertNull(TrioIobHistory.decode("{}"))
        val decoded = TrioIobHistory.decode(
            """{"site":"one","samples":[{"timestamp":10,"value":0},{"timestamp":"bad","value":2},{"timestamp":11,"value":"NaN"}]}"""
        )
        assertEquals(listOf(TrioIobSample(10L, 0.0)), decoded?.samples)
    }
}
