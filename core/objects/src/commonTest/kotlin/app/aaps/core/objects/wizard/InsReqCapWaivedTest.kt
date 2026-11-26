package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InsReqCapWaivedTest {

    private val now = 10_000_000L

    @Test
    fun waivedAtHighBgAndSafeHp1() {
        // 26 Nov Live 11:31: BG 12.6, HP1 8.3
        assertTrue(insReqCapWaived(227.0, 8_300L, now - 60_000L, now))
        assertTrue(insReqCapWaived(135.1, 6_000L, now, now))
    }

    @Test
    fun capStaysBelowBgOrHp1Limits() {
        assertFalse(insReqCapWaived(134.0, 8_000L, now, now))
        assertFalse(insReqCapWaived(200.0, 5_999L, now, now))
    }

    @Test
    fun capStaysWithoutFreshData() {
        assertFalse(insReqCapWaived(null, 8_000L, now, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, 0L, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, now - 300_001L, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, now + 1L, now))
    }
}
