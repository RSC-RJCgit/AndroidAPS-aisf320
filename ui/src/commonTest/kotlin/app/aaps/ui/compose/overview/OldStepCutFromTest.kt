package app.aaps.ui.compose.overview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Steps arrow fallback: reasons from v3 masters before "StepCut=" only name the direct step cuts in their own words.
 */
class OldStepCutFromTest {

    @Test
    fun theTokenWinsOverTheOldText() {
        assertEquals(0.12, stepCutFrom("microBolus = microBolus * 0.7 extra; microBolus = 0.7 Microbolusing 0.7U. StepCut=0.12 ", oldReasons = true), 1e-9)
    }

    @Test
    fun withoutTheSwitchAnOldReasonGivesNothing() {
        assertEquals(0.0, stepCutFrom("microBolus = microBolus * 0.7 extra; microBolus = 0.7 ", oldReasons = false))
    }

    @Test
    fun highStepsTookThreeTenthsOfTheUncutSmb() {
        assertEquals(0.3, oldStepCutFrom("microBolus = microBolus * 0.7 extra; microBolus = 0.7 Microbolusing 0.7U. "), 1e-9)
    }

    @Test
    fun littleWalkingTookAQuarterOfTheUncutSmb() {
        assertEquals(0.2, oldStepCutFrom("microBolus = microBolus * 0.75 low-steps fast-rise extra cut (HP2=5.9); microBolus = 0.6 "), 1e-9)
    }

    @Test
    fun aBoostThatSkippedTheFastRiseCapsGaveTheStepCutBack() {
        val reason = "microBolus = microBolus * 0.7 extra; microBolus = 0.7  fast-rise caps skipped (BolusGiven/Mild/Tier3 boost within 30 min): microBolus 0.7 -> 1.0 "
        assertEquals(0.0, oldStepCutFrom(reason))
    }

    @Test
    fun aTaperGaveBackItsShare() {
        val reason = "microBolus = microBolus * 0.7 extra; microBolus = 0.7  fast-rise caps tapered after UamBst (8min, x0.75, IOB 1.2 COB 0.0): microBolus 0.7 -> 0.93 "
        assertEquals(0.075, oldStepCutFrom(reason), 1e-9)
    }

    @Test
    fun theShowerCapPrintsItsOwnChange() {
        assertEquals(0.15, oldStepCutFrom("microBolus + IOB ov iobTHvirtualHARD fast rise 0.714 shower = -0.15 diff = "), 1e-9)
    }

    @Test
    fun theWalkingThirtyMinuteCapCountsOnlyWhatTheHalvingHeldBack() {
        // Unhalved allowance 2.1 * 2 - 1.0 - 0.5 = 2.7, so the whole 0.8 -> 0.55 trim was the walking half
        val reason = " 30min SMB cap: 0.8 -> 0.55 (last30min 1.0U + 0.5U bolus of 2.1U cap, profile 100%, walking) "
        assertEquals(0.25, oldStepCutFrom(reason), 1e-9)
    }

    @Test
    fun aThirtyMinuteCapWithoutWalkingIsNotAStepCut() {
        assertEquals(0.0, oldStepCutFrom(" 30min SMB cap: 0.8 -> 0.55 (last30min 1.0U of 2.1U cap, profile 100%) "))
    }
}
