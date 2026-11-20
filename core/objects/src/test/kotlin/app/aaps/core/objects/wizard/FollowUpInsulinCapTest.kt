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
    fun fullCapForProteinFatParts() {
        assertThat(followUpInsulinCap(1.20, 700L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)).isWithin(0.000001).of(0.70)
        assertThat(followUpInsulinCap(0.50, 90L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)).isWithin(0.000001).of(0.05)
        assertThat(followUpInsulinCap(0.50, 1420L, 1_000_000L, 1_000_000L, 0.05, halfReduction = false)).isWithin(0.000001).of(0.50)
    }

    @Test
    fun iobRiseHoldAbove1point2UnitsIn15Minutes() {
        assertThat(fpuIobRiseHolds(1.21, 0.0)).isTrue()
        assertThat(fpuIobRiseHolds(1.19, 0.0)).isFalse()
        assertThat(fpuIobRiseHolds(2.04, 0.11)).isTrue()   // 20 Nov 20:08, part 7 of 8
        assertThat(fpuIobRiseHolds(1.44, 0.29)).isFalse()  // 15 Nov 16:45, +1.15 U, BG stayed above 7
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
