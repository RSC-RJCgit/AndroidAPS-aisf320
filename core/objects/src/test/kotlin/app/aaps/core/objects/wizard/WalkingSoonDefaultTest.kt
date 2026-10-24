package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The Walking soon auto-tick (2026-10-24): hard walking up to BG 10.0, light walking below BG 8.0, never while rising fast. */
class WalkingSoonDefaultTest {

    @Test
    fun hardWalkingTicksItUpTo10() {
        // 24 Oct 14:21: S30 1375, S15 224, BG 7.5, falling
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 224, 1375, 136.0, -2.0)).isTrue()
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 50, 1000, 180.0, 0.0)).isTrue()
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 50, 1200, 185.0, 0.0)).isFalse()
    }

    @Test
    fun aLingeringS30DoesNotCount() {
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 0, 1200, 120.0, 0.0)).isFalse()
    }

    @Test
    fun lightWalkingTicksItOnlyBelow8() {
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 200, 300, 140.0, 0.0)).isTrue()
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 200, 300, 145.0, 0.0)).isFalse()
        assertThat(WizardActivitySteps.walkingSoonDefault(50, 199, 300, 100.0, 0.0)).isFalse()
    }

    @Test
    fun neverWhileRisingFast() {
        assertThat(WizardActivitySteps.walkingSoonDefault(0, 300, 1200, 100.0, 1.0)).isFalse()
    }
}
