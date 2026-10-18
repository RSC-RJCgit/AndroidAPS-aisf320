package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitSafetyTest {
    // 2026-10-18: no IOB credit any more; the part is the previous part's size, at most what is left, on the pump step.
    @Test fun nextPartIsThePreviousPartAtMostWhatIsLeft() {
        val budget = splitBolusBudget(1.95, 0.5, 0.05)
        assertEquals(1.95, budget.remaining, 0.000001)
        assertEquals(0.5, budget.dose, 0.000001)
        assertEquals(0.4, splitBolusBudget(0.43, 0.5, 0.05).dose, 0.000001)
        assertEquals(0.4, splitBolusBudget(0.4, 0.45, 0.05).dose, 0.000001)
        assertEquals(0.0, splitBolusBudget(0.0, 0.5, 0.05).dose)
    }

    @Test fun hpGateRequiresFreshPrediction() {
        assertEquals(SplitHpCheck.Cancel, splitHpCheck(3800L, 1_000_000L, 1_000_000L))
        assertEquals(SplitHpCheck.Allow, splitHpCheck(4000L, 1_000_000L, 1_300_000L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 0L, 1_000_000L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 1_000_000L, 1_300_001L))
        assertEquals(SplitHpCheck.Wait, splitHpCheck(3800L, 1_000_001L, 1_000_000L))
    }
}
