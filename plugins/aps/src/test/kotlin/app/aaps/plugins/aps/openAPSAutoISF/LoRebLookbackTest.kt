package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LoRebLookbackTest {
    @Test fun `confirmed rising recovery selects thirty minutes`() {
        assertThat(loRebLookbackMinutes(126.0, 3.6, 3.6)).isEqualTo(30)
    }

    @Test fun `exact thresholds retain sixty minutes`() {
        assertThat(loRebLookbackMinutes(108.0, 3.6, 3.6)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 1.8, 3.6)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 3.6, 1.8)).isEqualTo(60)
    }

    @Test fun `slowing or falling recovery restores sixty minutes`() {
        assertThat(loRebLookbackMinutes(126.0, -1.0, 3.6)).isEqualTo(60)
        assertThat(loRebLookbackMinutes(126.0, 3.6, 0.0)).isEqualTo(60)
    }

    @Test fun `invalid live values retain sixty minutes`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(loRebLookbackMinutes(value, 3.6, 3.6)).isEqualTo(60)
            assertThat(loRebLookbackMinutes(126.0, value, 3.6)).isEqualTo(60)
            assertThat(loRebLookbackMinutes(126.0, 3.6, value)).isEqualTo(60)
        }
    }
}
