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
        assertTrue(insReqCapWaived(117.1, 6_000L, now, now))
    }

    @Test
    fun capStaysBelowBgOrHp1Limits() {
        assertFalse(insReqCapWaived(117.0, 8_000L, now, now))
        assertFalse(insReqCapWaived(200.0, 5_999L, now, now))
    }

    @Test
    fun capStaysWithoutFreshData() {
        assertFalse(insReqCapWaived(null, 8_000L, now, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, 0L, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, now - 300_001L, now))
        assertFalse(insReqCapWaived(200.0, 8_000L, now + 1L, now))
    }

    @Test
    fun fullReleaseAfterTheHypoWait() {
        // 27 Nov Live 12:49 (Db20): BG 6.0 and rising
        assertTrue(delayedFullRelease(108.1, 2.0, 1.5, 6_500L, now, now))
        assertFalse(delayedFullRelease(105.0, 2.0, 1.5, 6_500L, now, now)) // under 6.0
        assertFalse(delayedFullRelease(120.0, 0.0, 1.5, 6_500L, now, now)) // not rising
        assertFalse(delayedFullRelease(120.0, 2.0, 1.5, 5_900L, now, now)) // HP1 under 6.0
        assertFalse(delayedFullRelease(120.0, 2.0, 1.5, 6_500L, now - 300_001L, now)) // HP1 stale
    }

    @Test
    fun highFastRiseTakesTheStepsCutOff() {
        // 27 Nov Live 13:39: BG 12.4, Delta 0.39, SDelta 0.46
        assertTrue(followUpHighFastRise(223.4, 7.0, 8.3))
        assertFalse(followUpHighFastRise(150.0, 7.0, 8.3))
        assertFalse(followUpHighFastRise(223.4, 3.0, 8.3))
        assertFalse(followUpHighFastRise(null, 7.0, 8.3))
    }
}
