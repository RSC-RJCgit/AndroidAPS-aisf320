package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitSafetyTest {
    @Test fun mealCreditIsRemovedFromRemainingBudget() {
        val budget = splitBolusBudget(1.95, 2.0, 2.10, 3.65, 0.05)
        assertEquals(1.55, budget.creditedIob, 0.000001)
        assertEquals(0.40, budget.remaining, 0.000001)
        assertEquals(0.40, budget.dose, 0.000001)
        assertEquals(0.0, budget.remaining - budget.dose, 0.000001)
    }

    @Test fun skippedCreditIsNotCountedAgain() {
        val first = splitBolusBudget(2.0, 0.5, 2.0, 2.8, 0.05)
        val retry = splitBolusBudget(first.remaining, 0.5, 2.8, 2.8, 0.05)
        assertEquals(0.0, first.dose)
        assertEquals(first.remaining, retry.remaining)
        assertEquals(0.0, retry.creditedIob)
        assertEquals(0.4, splitBolusBudget(0.43, 0.5, 2.0, 2.0, 0.05).dose, 0.000001)
        assertEquals(0.0, splitBolusBudget(1.0, 0.5, 2.0, 3.5, 0.05).remaining)
        assertEquals(0.4, splitBolusBudget(0.4, 0.45, 4.1, 3.8, 0.05).remaining)
    }

    @Test fun hpGateRequiresFreshPrediction() {
        assertEquals(SplitHpCheck.Cancel, splitHpCheck(3800L, 1_000_000L, 1_000_000L))
        assertEquals(SplitHpCheck.Allow, splitHpCheck(4000L, 1_000_000L, 1_300_000L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 0L, 1_000_000L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 1_000_000L, 1_300_001L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 1_000_001L, 1_000_000L))
    }
}
