package app.aaps.implementation.wizard

import app.aaps.core.objects.wizard.splitBolusBudget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SplitBolusBudgetTest {
    @Test fun doseStaysOnPumpStepWithoutExceedingBudget() {
        val budget = splitBolusBudget(0.43, 0.50, 0.05)
        assertThat(budget.remaining).isWithin(0.000001).of(0.43)
        assertThat(budget.dose).isWithin(0.000001).of(0.40)
    }

    // 2026-10-18: no IOB credit; the part is the previous part's size while enough is left.
    @Test fun nextPartIsThePreviousPartSize() {
        val budget = splitBolusBudget(1.95, 0.5, 0.05)
        assertThat(budget.remaining).isWithin(0.000001).of(1.95)
        assertThat(budget.dose).isWithin(0.000001).of(0.5)
    }

    @Test fun nothingLeftGivesNoDose() {
        assertThat(splitBolusBudget(0.0, 0.5, 0.05).dose).isEqualTo(0.0)
    }
}
