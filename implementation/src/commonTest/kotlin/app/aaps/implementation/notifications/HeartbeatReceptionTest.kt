package app.aaps.implementation.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class HeartbeatReceptionTest {
    @Test fun aConnectionWithoutAValueIsNotReceiving() {
        assertEquals(HeartbeatReception.WAITING, heartbeatReception(0L, 500_000L, true))
    }

    @Test fun aRecentValueIsReceivingOnlyWhileConnected() {
        assertEquals(HeartbeatReception.RECEIVING, heartbeatReception(400_000L, 500_000L, true))
        assertEquals(HeartbeatReception.DISCONNECTED, heartbeatReception(400_000L, 500_000L, false))
    }

    @Test fun threeMinutesWithoutAValueIsStaleEvenWhenConnected() {
        assertEquals(HeartbeatReception.RECEIVING, heartbeatReception(400_000L, 579_999L, true))
        assertEquals(HeartbeatReception.STALE, heartbeatReception(400_000L, 580_000L, true))
    }

    @Test fun aClockChangeDoesNotClaimARecentHeartbeat() {
        assertEquals(HeartbeatReception.WAITING, heartbeatReception(600_000L, 500_000L, true))
    }
}
