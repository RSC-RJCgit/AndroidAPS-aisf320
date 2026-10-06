package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SlowRiseCriteriaTest {
    @Test fun `wide delta bands apply only while the meal event is under 60 min old`() {
        assertThat(slowRiseWideBandsApply(40)).isTrue()
        assertThat(slowRiseWideBandsApply(59)).isTrue()
        assertThat(slowRiseWideBandsApply(60)).isFalse()
        assertThat(slowRiseWideBandsApply(null)).isFalse()
    }

    @Test fun `gentle deltas always pass and wide bands only when allowed`() {
        val gentle = 0.1 * 18.0
        val wide = 0.2 * 18.0
        assertThat(slowRiseCriteriaMet(144.0, gentle, gentle, gentle, 0.0, 1.2, 0, 0, 90, 90, gentleDeltasOnly = true)).isTrue()
        assertThat(slowRiseCriteriaMet(144.0, gentle, gentle, gentle, 0.0, 1.2, 0, 0, 45, 45, gentleDeltasOnly = false)).isTrue()
        assertThat(slowRiseCriteriaMet(144.0, wide, wide, wide, 0.0, 1.2, 0, 0, 45, 45, gentleDeltasOnly = false)).isTrue()
        assertThat(slowRiseCriteriaMet(144.0, wide, wide, wide, 0.0, 1.2, 0, 0, 90, 90, gentleDeltasOnly = true)).isFalse()
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

    @Test fun `iob floor is 1_0 U and cap stays 5_5 U`() {
        val d = 3.6
        assertThat(slowRiseCriteriaMet(144.0, d, d, d, 8.0, 1.0, 599, 999, 40, 1)).isTrue()
        assertThat(slowRiseCriteriaMet(144.0, d, d, d, 8.0, 0.99, 599, 999, 40, 1)).isFalse()
        assertThat(slowRiseCriteriaMet(144.0, d, d, d, 8.0, 5.5, 599, 999, 40, 1)).isTrue()
        assertThat(slowRiseCriteriaMet(144.0, d, d, d, 8.0, 5.51, 599, 999, 40, 1)).isFalse()
    }

    @Test fun `bg floor is 6_5 mmol and cap stays 9_0`() {
        val d = 3.6
        assertThat(slowRiseCriteriaMet(6.5 * 18.0, d, d, d, 8.0, 1.2, 599, 999, 40, 1)).isTrue()
        assertThat(slowRiseCriteriaMet(6.5 * 18.0 - 0.1, d, d, d, 8.0, 1.2, 599, 999, 40, 1)).isFalse()
        assertThat(slowRiseCriteriaMet(9.0 * 18.0, d, d, d, 8.0, 1.2, 599, 999, 40, 1)).isTrue()
        assertThat(slowRiseCriteriaMet(9.0 * 18.0 + 0.1, d, d, d, 8.0, 1.2, 599, 999, 40, 1)).isFalse()
    }
}
