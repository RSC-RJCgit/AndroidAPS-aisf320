package app.aaps.plugins.sync.nsclientV3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoBgReloadRuleTest {

    private val now = 10_000_000L

    @Test
    fun reloadsAfterTwoMinutesWithoutBg() {
        assertFalse(noBgReloadDue(lastBgAt = now - 119_000L, lastReloadAt = 0L, now = now))
        assertTrue(noBgReloadDue(lastBgAt = now - 120_000L, lastReloadAt = 0L, now = now))
    }

    @Test
    fun atMostOneReloadAMinute() {
        assertFalse(noBgReloadDue(lastBgAt = now - 300_000L, lastReloadAt = now - 59_000L, now = now))
        assertTrue(noBgReloadDue(lastBgAt = now - 300_000L, lastReloadAt = now - 60_000L, now = now))
    }

    @Test
    fun noBgAtAllDoesNotReload() {
        assertFalse(noBgReloadDue(lastBgAt = null, lastReloadAt = 0L, now = now))
    }
}
