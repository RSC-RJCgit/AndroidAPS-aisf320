package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecondaryBgRelayTest {
    private val now = 200_000_000L
    private val due = now - BG_REPLAY_DELAY_MS

    private fun reading(at: Long, modified: Long? = null) = NSSgvV3(
        date = at, device = "CGM", identifier = "secondary-id", srvModified = modified,
        srvCreated = modified, utcOffset = 0L, subject = "secondary-user", isValid = true,
        sgv = 6.5, units = NsUnits.MMOL_L, direction = null, noise = null, filtered = null, unfiltered = null
    )

    @Test
    fun oneDueReadingIsShiftedExactlyFortyEightHoursWithoutChangingItsValueOrDevice() {
        val original = reading(due)
        val replay = nextReplayReading(listOf(original), emptyList(), now)!!
        assertEquals(now, replay.date)
        assertEquals(original.sgv, replay.sgv)
        assertEquals(original.units, replay.units)
        assertEquals(original.device, replay.device)
        assertNull(replay.identifier)
        assertNull(replay.srvModified)
        assertNull(replay.subject)
    }

    @Test
    fun missedHistoryIsNotUploadedAsABatchAndFutureSourceReadingsWait() {
        val replay = nextReplayReading(listOf(reading(due - 60_000L), reading(due), reading(due + 1)), emptyList(), now)!!
        assertEquals(now, replay.date)
        assertNull(nextReplayReading(listOf(reading(due + 1)), emptyList(), now))
        assertNull(nextReplayReading(listOf(reading(due - BG_REPLAY_SAMPLE_WINDOW_MS)), emptyList(), now))
    }

    @Test
    fun alreadyReplayedTimestampIsNotUploadedAgainAfterRestart() {
        assertNull(nextReplayReading(listOf(reading(due)), listOf(reading(now)), now))
    }

    @Test
    fun invalidReadingsAreNotReplayed() {
        assertNull(nextReplayReading(listOf(reading(due).copy(isValid = false)), emptyList(), now))
        assertNull(nextReplayReading(listOf(reading(due).copy(sgv = Double.NaN)), emptyList(), now))
    }

    @Test
    fun normalFreshV3PausesReplayButV1AndOldReadingsDoNot() {
        assertTrue(normalBgResumed(listOf(reading(now, now)), now))
        assertFalse(normalBgResumed(listOf(reading(now)), now))
        assertFalse(normalBgResumed(listOf(reading(now - BG_RELAY_PAUSE_MS, now)), now))
        assertFalse(normalBgResumed(listOf(reading(now + 1, now)), now))
    }
}
