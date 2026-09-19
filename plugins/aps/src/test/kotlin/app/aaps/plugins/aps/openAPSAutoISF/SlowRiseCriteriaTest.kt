package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SlowRiseCriteriaTest {
    @Test fun `both actual events must be within their own windows`() {
        val now = 10_000_000L
        assertThat(slowRiseRecentEvents(now, now - 60 * 60_000L, now - 30 * 60_000L)).isTrue()
        assertThat(slowRiseRecentEvents(now, now - 60 * 60_000L - 1, now)).isFalse()
        assertThat(slowRiseRecentEvents(now, now, now - 30 * 60_000L - 1)).isFalse()
        assertThat(slowRiseRecentEvents(now, 0, now)).isFalse()
        assertThat(slowRiseRecentEvents(now, now, 0)).isFalse()
        assertThat(slowRiseRecentEvents(now, now + 1, now)).isFalse()
        assertThat(slowRiseRecentEvents(now, now, now + 1)).isFalse()
    }

    private fun eligible(s60: Int? = 599, s180: Int? = 999, bolus: Int? = 40, carbs: Int? = 1,
                         d: Double = 3.6, sd: Double = 3.6, ld: Double = 3.6) =
        slowRiseCriteriaMet(144.0, d, sd, ld, 8.0, 1.2, s60, s180, bolus, carbs)

    @Test fun `step limits are strict and missing counts block`() {
        assertThat(eligible()).isTrue()
        assertThat(eligible(s60 = 600)).isFalse()
        assertThat(eligible(s180 = 1000)).isFalse()
        assertThat(eligible(s60 = null)).isFalse()
        assertThat(eligible(s180 = null)).isFalse()
    }

    @Test fun `treatment age uses OR and unknown is not old`() {
        assertThat(eligible(bolus = null, carbs = 40)).isTrue()
        assertThat(eligible(bolus = 39, carbs = 39)).isFalse()
        assertThat(eligible(bolus = null, carbs = null)).isFalse()
    }

    @Test fun `mixed distant delta bands must not pass`() {
        assertThat(eligible(d = 0.15 * 18, sd = 0.25 * 18, ld = 0.35 * 18)).isFalse()
        assertThat(eligible(d = 0.25 * 18, sd = 0.30 * 18, ld = 0.35 * 18)).isTrue()
        assertThat(eligible(d = Double.NaN)).isFalse()
    }
}
