package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FollowUpInsulinCapTest {

    @Test
    fun appliesHalfOfInsulinRequirementReductionToFollowUpDose() {
        assertThat(followUpInsulinCap(1.20, 700L, 1_000_000L, 1_000_000L, 0.05)).isWithin(0.000001).of(0.95)
        assertThat(followUpInsulinCap(1.13, 430L, 1_000_000L, 1_000_000L, 0.05)).isWithin(0.000001).of(0.75)
    }

    @Test
    fun doesNotIncreaseDoseWhenRequirementExceedsPlan() {
        assertThat(followUpInsulinCap(0.43, 2000L, 1_000_000L, 1_000_000L, 0.05)).isWithin(0.000001).of(0.40)
    }

    @Test
    fun preservesZeroAndSubStepRequirementBehavior() {
        for (requirement in listOf(0L, -1000L, 49L)) {
            assertThat(followUpInsulinCap(1.13, requirement, 1_000_000L, 1_000_000L, 0.05)).isWithin(0.000001).of(0.0)
        }
    }

    @Test
    fun rejectsMissingStaleAndFutureRequirements() {
        assertThat(followUpInsulinCap(1.0, 500L, 0L, 1_300_000L, 0.05)).isNull()
        assertThat(followUpInsulinCap(1.0, 500L, 1_000_000L, 1_300_001L, 0.05)).isNull()
        assertThat(followUpInsulinCap(1.0, 500L, 1_300_001L, 1_300_000L, 0.05)).isNull()
    }
}
