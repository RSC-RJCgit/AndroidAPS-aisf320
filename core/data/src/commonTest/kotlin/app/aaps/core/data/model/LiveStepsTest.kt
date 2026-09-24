package app.aaps.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveStepsTest {

    @Test
    fun reasonTextRoundTripsTheSixBuckets() {
        val text = LiveSteps.reasonText(1, 2, 3, 4, 5, 6)
        val buckets = LiveSteps.buckets("COB: 0 $text bg_acce: 1.2")
        assertEquals(mapOf(5 to 1, 10 to 2, 15 to 3, 30 to 4, 60 to 5, 180 to 6), buckets)
        assertTrue(LiveSteps.hasDosingBuckets(buckets))
    }

    @Test
    fun olderMinIsFormIsAcceptedAndAMissingBucketIsNotEnough() {
        val buckets = LiveSteps.buckets("steps5min is 9 ;; steps60min is 40 ;;")
        assertEquals(9, buckets[5])
        assertEquals(40, buckets[60])
        assertFalse(LiveSteps.hasDosingBuckets(buckets))
    }

    @Test
    fun virtualPhoneUsesTheLiveSampleAndIgnoresItsOwn() {
        val live = sample(1_000L, "openaps://live")
        val own = sample(1_500L, "openaps://this phone")
        val watch = sample(1_600L, "watch")
        val found = LiveSteps.sampleFor(2_000L, listOf(watch, own, live), fromLivePhone = true, ownDevice = "openaps://this phone")
        assertEquals(live.timestamp, found?.timestamp)
    }

    @Test
    fun sampleOlderThanTwentyMinutesIsDropped() {
        val live = sample(0L, "openaps://live")
        assertNull(LiveSteps.sampleFor(LiveSteps.MAX_AGE_MS + 1, listOf(live), fromLivePhone = true, ownDevice = "openaps://this phone"))
    }

    private fun sample(timestamp: Long, device: String) = SC(
        timestamp = timestamp,
        duration = 0L,
        steps5min = 1,
        steps10min = 1,
        steps15min = 1,
        steps30min = 1,
        steps60min = 1,
        steps180min = 1,
        device = device
    )
}
