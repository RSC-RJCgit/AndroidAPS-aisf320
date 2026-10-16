package app.aaps.ui.compose.overview.graphs

import app.aaps.core.data.configuration.Constants
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MainGraphScaleTest {

    // The main glucose graph always shows 2.0 to 12.0 mmol/L (2026-10-16). Graph 5 does not use this.
    @Test
    fun mmolGraphIsTwoToTwelve() {
        val scale = mainGraphScale(mgdl = false)
        assertThat(scale.min).isEqualTo(2.0)
        assertThat(scale.max).isEqualTo(12.0)
    }

    @Test
    fun mgdlGraphIsTheSameRangeConverted() {
        val scale = mainGraphScale(mgdl = true)
        assertThat(scale.min).isWithin(1e-9).of(2.0 * Constants.MMOLL_TO_MGDL)
        assertThat(scale.max).isWithin(1e-9).of(12.0 * Constants.MMOLL_TO_MGDL)
    }
}
