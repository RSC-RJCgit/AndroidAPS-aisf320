package app.aaps.plugins.sync.nsclientV3.workers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** The full sync guard: a reading within 30 s of one this phone already has is not stored again. */
    @Test
    fun aReadingNearAnExistingOneIsFound() {
        val existing = longArrayOf(60_000L, 120_000L, 240_000L)
        assertTrue(hasReadingNear(existing, 120_000L, 30_000L)) // same time
        assertTrue(hasReadingNear(existing, 149_000L, 30_000L)) // 29 s after
        assertTrue(hasReadingNear(existing, 31_000L, 30_000L)) // 29 s before the first
        assertFalse(hasReadingNear(existing, 180_000L, 30_000L)) // a gap: 60 s from both neighbours
        assertFalse(hasReadingNear(existing, 300_000L, 30_000L)) // a gap after the last
        assertFalse(hasReadingNear(longArrayOf(), 120_000L, 30_000L)) // nothing stored yet
    }
}
