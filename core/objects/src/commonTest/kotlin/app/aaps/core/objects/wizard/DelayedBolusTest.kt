package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelayedBolusTest {

    private val mmol = 18.0182

    @Test
    fun theHeldPartIsTheCarbsAtTheStandardRatioLessTheCarbInsulinGivenNow() {
        // 30 g, Standard IC 5.0 = 6.0 U; on a 50% profile (IC 10) the calculator gave 3.0 U of carb insulin, at 80%
        assertEquals(2.4, DelayedBolus.heldUnits(carbs = 30, standardIc = 5.0, carbInsulinNow = 3.0, percent = 80.0), 1e-9)
        // Live, 50% on Profile70: own IC 6.8 at 100% vs Standard 4.76. The Standard ratio gives about 43% more
        val onLow = DelayedBolus.heldUnits(30, 6.8, 30 / (6.8 * 2), 100.0)
        val onStandard = DelayedBolus.heldUnits(30, 4.76, 30 / (6.8 * 2), 100.0)
        assertTrue(onStandard > onLow * 1.6)
    }

    @Test
    fun bolusIobIsNotPartOfTheHeldPart() {
        // The 3426 app added bolus IOB here (about 2 x IOB too much). The held part depends on the carbs only:
        // Live 11:16, 12 g at IC 5.7 with 0.47 U IOB, the 3426 app's "full need" was (12 / 5.7 + 0.47) x 0.8 = 2.06 U.
        assertEquals((12 / 5.7 - 1.05) * 0.8, DelayedBolus.heldUnits(12, 5.7, 1.05, 80.0), 1e-9)
    }

    @Test
    fun nothingIsHeldWithoutCarbsOrWhenTheGivenCarbInsulinCoversIt() {
        assertEquals(0.0, DelayedBolus.heldUnits(0, 5.0, 0.0, 100.0))
        assertEquals(0.0, DelayedBolus.heldUnits(30, 0.0, 0.0, 100.0))
        assertEquals(0.0, DelayedBolus.heldUnits(30, 10.0, 4.0, 100.0))
    }

    @Test
    fun theGateNeedsFreshBgOverFiveAndAllDeltasRising() {
        assertTrue(DelayedBolus.gateOk(5.3 * mmol, 0.15 * mmol, 0.12 * mmol, 0.01 * mmol, 60_000L))
        // BG not over 5.0
        assertFalse(DelayedBolus.gateOk(5.0 * mmol, 0.15 * mmol, 0.12 * mmol, 0.01 * mmol, 60_000L))
        // delta exactly 0.10 is not over it
        assertFalse(DelayedBolus.gateOk(6.0 * mmol, 0.10 * mmol, 0.12 * mmol, 0.01 * mmol, 60_000L))
        // long delta not rising (the 3426 app's 19 Sep 2026 rule: LD over 0)
        assertFalse(DelayedBolus.gateOk(6.0 * mmol, 0.15 * mmol, 0.12 * mmol, 0.0, 60_000L))
        // BG older than 5 minutes, or missing
        assertFalse(DelayedBolus.gateOk(6.0 * mmol, 0.15 * mmol, 0.12 * mmol, 0.01 * mmol, 6 * 60_000L))
        assertFalse(DelayedBolus.gateOk(null, 0.15 * mmol, 0.12 * mmol, 0.01 * mmol, 60_000L))
    }

    @Test
    fun theDoseIsWhatIsLeftScaledByTheCarbsStillOnBoard() {
        assertEquals(0.5, DelayedBolus.cobFraction(15.0, 30))
        assertEquals(1.0, DelayedBolus.cobFraction(40.0, 30))
        assertEquals(0.0, DelayedBolus.cobFraction(-1.0, 30))
        assertEquals(1.0, DelayedBolus.cobFraction(10.0, 0))
        // 2.4 U held, 0.8 U given at an earlier insulinReq-limited check, half the carbs left
        assertEquals(0.8, DelayedBolus.checkDose(2.4, 0.8, 0.5), 1e-9)
        assertEquals(0.0, DelayedBolus.checkDose(2.4, 3.0, 1.0))
    }

    @Test
    fun checksRunEveryFiveMinutesForEightyMinutes() {
        assertEquals(5, DelayedBolus.checkMinutes(1))
        assertEquals(80, DelayedBolus.checkMinutes(DelayedBolus.MAX_ATTEMPTS))
    }
}
