package app.aaps.implementation.wizard

import app.aaps.core.objects.wizard.splitBolusBudget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SplitBolusBudgetTest {
    @Test fun doseStaysOnPumpStepWithoutExceedingBudget() {
        val budget = splitBolusBudget(0.43, 0.50, 2.0, 2.0, 0.05)
        assertThat(budget.remaining).isWithin(0.000001).of(0.43)
        assertThat(budget.dose).isWithin(0.000001).of(0.40)
    }

    @Test fun mealRecordDoesNotDeliverCreditedInsulinLater() {
        val budget = splitBolusBudget(1.95, 2.0, 2.10, 3.65, 0.05)
        assertThat(budget.creditedIob).isWithin(0.000001).of(1.55)
        assertThat(budget.remaining).isWithin(0.000001).of(0.40)
        assertThat(budget.dose).isWithin(0.000001).of(0.40)
        assertThat(budget.remaining - budget.dose).isWithin(0.000001).of(0.0)
    }

    @Test fun creditCanFinishSplitWithoutDelivery() {
        val budget = splitBolusBudget(1.0, 0.5, 2.0, 3.5, 0.05)
        assertThat(budget.remaining).isEqualTo(0.0)
        assertThat(budget.dose).isEqualTo(0.0)
        assertThat(budget.creditedIob).isEqualTo(1.0)
    }

    @Test fun retryDoesNotCreditSameRiseTwice() {
        val first = splitBolusBudget(2.0, 0.5, 2.0, 2.8, 0.05)
        val retry = splitBolusBudget(first.remaining, 0.5, 2.8, 2.8, 0.05)
        assertThat(first.dose).isEqualTo(0.0)
        assertThat(retry.remaining).isEqualTo(first.remaining)
        assertThat(retry.creditedIob).isEqualTo(0.0)
    }

    @Test fun decayDoesNotRestoreRemovedBudget() {
        val budget = splitBolusBudget(0.40, 0.45, 4.10, 3.8, 0.05)
        assertThat(budget.remaining).isWithin(0.000001).of(0.40)
        assertThat(budget.dose).isWithin(0.000001).of(0.40)
    }
}
