package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Not50RecentlyTest {

    @Test
    fun aRisingRecoveryAt100PercentClearsTheFlag() {
        assertTrue(clear())
    }

    @Test
    fun stillLowOrStillFallingStaysFlagged() {
        assertFalse(clear(bg = 99.0))
        assertFalse(clear(delta = 1.7))
    }

    @Test
    fun aProfileAt50OrNoFlagStaysClosed() {
        assertFalse(clear(profilePercent = 50))
        assertFalse(clear(lowBgRecent = false))
        assertFalse(clear(ready = false))
    }

    private fun clear(
        ready: Boolean = true,
        profilePercent: Int = 100,
        lowBgRecent: Boolean = true,
        delta: Double = 1.8,
        bg: Double = 99.1,
    ) = not50RecentlyShouldFire(ready, profilePercent, lowBgRecent, delta, bg)
}
