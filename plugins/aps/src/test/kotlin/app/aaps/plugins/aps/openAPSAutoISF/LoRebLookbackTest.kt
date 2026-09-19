package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LoRebLookbackTest {
    @Test fun `confirmed rising recovery selects thirty minutes`() {
        assertThat(loRebLookbackMinutes(126.0, 3.6, 3.6, 101.0)).isEqualTo(30)
    }

    @Test fun `exact thresholds retain sixty minutes`() {
        assertThat(loRebLookbackMinutes(108.0, 3.6, 3.6, 101.0)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 1.8, 3.6, 101.0)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 3.6, 1.8, 101.0)).isEqualTo(60)
    }

    @Test fun `slowing or falling recovery restores sixty minutes`() {
        assertThat(loRebLookbackMinutes(126.0, -1.0, 3.6, 101.0)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 3.6, 0.0, 101.0)).isEqualTo(60)
    }

    @Test fun `missing or invalid history cannot shorten protection`() {
        for (minimum in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0)) {
            assertThat(loRebLookbackMinutes(126.0, 3.6, 3.6, minimum)).isEqualTo(60)
        }
    }

    @Test fun `recent low remains in selected window`() {
        // Selecting 30 minutes does not itself bypass the low guard: its minimum remains low.
        assertThat(loRebLookbackMinutes(126.0, 3.6, 3.6, 70.0)).isEqualTo(30)
    }
}
