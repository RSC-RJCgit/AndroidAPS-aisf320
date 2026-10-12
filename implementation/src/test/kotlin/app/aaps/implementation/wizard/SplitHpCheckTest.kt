package app.aaps.implementation.wizard

import app.aaps.core.objects.wizard.SplitHpCheck
import app.aaps.core.objects.wizard.splitHpCheck
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SplitHpCheckTest {
    @Test fun freshLowPredictionCancels() {
        assertThat(splitHpCheck(3800L, 1_000_000L, 1_000_000L)).isEqualTo(SplitHpCheck.Cancel)
        assertThat(splitHpCheck(4000L, 1_000_000L, 1_000_000L)).isEqualTo(SplitHpCheck.Allow)
    }

    @Test fun unavailableOrStalePredictionWaits() {
        assertThat(splitHpCheck(3800L, 0L, 1_000_000L)).isEqualTo(SplitHpCheck.Wait)
        assertThat(splitHpCheck(3800L, 1_000_000L, 1_300_001L)).isEqualTo(SplitHpCheck.Wait)
        assertThat(splitHpCheck(3800L, 1_000_001L, 1_000_000L)).isEqualTo(SplitHpCheck.Wait)
        assertThat(splitHpCheck(4000L, 1_000_000L, 1_300_000L)).isEqualTo(SplitHpCheck.Allow)
    }
}
