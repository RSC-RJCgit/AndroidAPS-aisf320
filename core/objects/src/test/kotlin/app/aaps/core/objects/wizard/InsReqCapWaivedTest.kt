package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InsReqCapWaivedTest {

    private val now = 10_000_000L

    @Test
    fun waivedAtHighBgAndSafeHp1() {
        // 26 Nov Live 11:31: BG 12.6, HP1 8.3
        assertThat(insReqCapWaived(227.0, 8_300L, now - 60_000L, now)).isTrue()
        assertThat(insReqCapWaived(135.1, 6_000L, now, now)).isTrue()
    }

    @Test
    fun capStaysBelowBgOrHp1Limits() {
        assertThat(insReqCapWaived(134.0, 8_000L, now, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 5_999L, now, now)).isFalse()
    }

    @Test
    fun capStaysWithoutFreshData() {
        assertThat(insReqCapWaived(null, 8_000L, now, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, 0L, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, now - 300_001L, now)).isFalse()
        assertThat(insReqCapWaived(200.0, 8_000L, now + 1L, now)).isFalse()
    }
}
