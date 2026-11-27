package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InsReqCapWaivedTest {

    private val now = 10_000_000L

    @Test
    fun waivedAtBgFromSixPointFiveWithSafeHp1() {
        // 26 Nov Live 11:31: BG 12.6, HP1 8.3
        assertThat(insReqCapWaived(227.0, 8_300L, now - 60_000L, now)).isTrue()
        assertThat(insReqCapWaived(117.1, 6_000L, now, now)).isTrue()
    }

    @Test
    fun capStaysBelowBgOrHp1Limits() {
        assertThat(insReqCapWaived(117.0, 8_000L, now, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 5_999L, now, now)).isFalse()
    }

    @Test
    fun capStaysWithoutFreshData() {
        assertThat(insReqCapWaived(null, 8_000L, now, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, 0L, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, now - 300_001L, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, now + 1L, now)).isFalse()
    }

    @Test
    fun fullReleaseAfterTheHypoWait() {
        // 27 Nov Live 12:49 (Db20): BG 6.0 and rising
        assertThat(delayedFullRelease(108.1, 2.0, 1.5, 6_500L, now, now)).isTrue()
        assertThat(delayedFullRelease(105.0, 2.0, 1.5, 6_500L, now, now)).isFalse() // under 6.0
        assertThat(delayedFullRelease(120.0, 0.0, 1.5, 6_500L, now, now)).isFalse() // not rising
        assertThat(delayedFullRelease(120.0, 2.0, 1.5, 5_900L, now, now)).isFalse() // HP1 under 6.0
        assertThat(delayedFullRelease(120.0, 2.0, 1.5, 6_500L, now - 300_001L, now)).isFalse() // HP1 stale
    }

    @Test
    fun highFastRiseTakesTheStepsCutOff() {
        // 27 Nov Live 13:39: BG 12.4, Delta 0.39, SDelta 0.46
        assertThat(followUpHighFastRise(223.4, 7.0, 8.3)).isTrue()
        assertThat(followUpHighFastRise(150.0, 7.0, 8.3)).isFalse()
        assertThat(followUpHighFastRise(223.4, 3.0, 8.3)).isFalse()
        assertThat(followUpHighFastRise(null, 7.0, 8.3)).isFalse()
    }
}
