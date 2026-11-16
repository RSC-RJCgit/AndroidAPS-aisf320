package app.aaps.implementation.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartbeatSyncGateTest {
    @Test fun theFirstHeartbeatRequestsASyncImmediately() {
        val gate = HeartbeatSyncGate()
        assertTrue(gate.shouldRequestSync(0L))
        assertEquals(0L, gate.lastRequestAt)
    }

    @Test fun everyMinuteTickRequestsASync() {
        val gate = HeartbeatSyncGate()
        assertTrue(gate.shouldRequestSync(100_000L))
        for (minute in 1..4) assertTrue(gate.shouldRequestSync(100_000L + minute * 60_000L))
        assertEquals(340_000L, gate.lastRequestAt)
    }

    @Test fun aTickAFewSecondsEarlyIsNotSkipped() {
        val gate = HeartbeatSyncGate()
        assertTrue(gate.shouldRequestSync(100_000L))
        assertFalse(gate.shouldRequestSync(149_999L))
        assertTrue(gate.shouldRequestSync(155_000L))
    }

    @Test fun switchingHeartbeatSourcesDoesNotStartAnotherSync() {
        val gate = HeartbeatSyncGate()
        assertTrue(gate.shouldRequestSync(100_000L))
        assertFalse(gate.shouldRequestSync(100_000L))
        assertFalse(gate.shouldRequestSync(130_000L))
    }

    @Test fun aClockCorrectionOrRestartDoesNotBlockSync() {
        val gate = HeartbeatSyncGate()
        assertTrue(gate.shouldRequestSync(500_000L))
        assertTrue(gate.shouldRequestSync(400_000L))
        assertFalse(gate.shouldRequestSync(430_000L))
        assertTrue(HeartbeatSyncGate().shouldRequestSync(430_000L))
    }
}
