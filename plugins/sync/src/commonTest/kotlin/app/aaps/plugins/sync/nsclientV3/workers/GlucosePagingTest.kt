package app.aaps.plugins.sync.nsclientV3.workers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlucosePagingTest {

    @Test
    fun aFullOldestPageMovesForwardAndAShortPageStops() {
        assertEquals(1_000L, nextGlucoseFrom(true, 100L, 500, 500, 1_000L, 900L))
        assertNull(nextGlucoseFrom(true, 1_000L, 200, 500, 1_200L, 1_100L))
        assertNull(nextGlucoseFrom(true, 1_000L, 500, 500, 1_000L, 1_100L))
    }

    @Test
    fun aLaterPollPagesByModifiedTime() {
        assertEquals(5_000L, nextGlucoseFrom(false, 4_000L, 500, 500, 9_000L, 5_000L))
        assertNull(nextGlucoseFrom(false, 5_000L, 10, 500, 9_000L, 5_100L))
    }

    @Test
    fun zeroIsNotStoredAndAPartialFirstWalkDoesNotStore() {
        assertNull(glucoseCursorToStore(0L, 0L, firstCatchUp = true, reachedNewest = true))
        assertNull(glucoseCursorToStore(0L, 5_000L, firstCatchUp = true, reachedNewest = false))
        assertEquals(5_000L, glucoseCursorToStore(0L, 5_000L, firstCatchUp = true, reachedNewest = true))
        assertEquals(6_000L, glucoseCursorToStore(5_000L, 6_000L, firstCatchUp = false, reachedNewest = true))
        assertNull(glucoseCursorToStore(6_000L, 6_000L, firstCatchUp = false, reachedNewest = true))
    }
}
