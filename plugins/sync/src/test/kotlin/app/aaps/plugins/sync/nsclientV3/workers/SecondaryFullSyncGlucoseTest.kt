package app.aaps.plugins.sync.nsclientV3.workers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The full sync guard: a reading within 30 s of one this phone already has is not stored again. */
class SecondaryFullSyncGlucoseTest {

    @Test
    fun aReadingNearAnExistingOneIsFound() {
        val existing = longArrayOf(60_000L, 120_000L, 240_000L)
        assertThat(hasReadingNear(existing, 120_000L, 30_000L)).isTrue() // same time
        assertThat(hasReadingNear(existing, 149_000L, 30_000L)).isTrue() // 29 s after
        assertThat(hasReadingNear(existing, 31_000L, 30_000L)).isTrue() // 29 s before the first
        assertThat(hasReadingNear(existing, 180_000L, 30_000L)).isFalse() // a gap: 60 s from both neighbours
        assertThat(hasReadingNear(existing, 300_000L, 30_000L)).isFalse() // a gap after the last
        assertThat(hasReadingNear(longArrayOf(), 120_000L, 30_000L)).isFalse() // nothing stored yet
    }
}
