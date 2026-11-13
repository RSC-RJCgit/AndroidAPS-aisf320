package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardDropTrendCautionTest {

    @Test
    fun aFastFallUnderSixHalvesTheDose() {
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -2.8,
                shortAvgDelta = -1.9,
                bgMgdl = 90.0,
                hp1Mmol = 6.0,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
        // 2026-10-22: path 1 has no 5.5 mmol ceiling any more, so HP1 4.0 is enough at 5.55 mmol
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -2.8,
                shortAvgDelta = -1.9,
                bgMgdl = 100.0,
                hp1Mmol = 4.0,
                iobUnits = 4.2,
                bgRiseMgdl = 9.0,
            )
        )
    }

    @Test
    fun the22OctMealBolusAt72WithHp14WouldHaveBeenHalved() {
        // 12:57: BG 7.2, delta -0.31, short delta -0.25, long delta only -0.02 (15 minutes of fall), HP1 4.0, IOB 3.0 U
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -0.31 * 18.0182,
                shortAvgDelta = -0.25 * 18.0182,
                bgMgdl = 7.2 * 18.0182,
                hp1Mmol = 4.0,
                iobUnits = 3.0,
                bgRiseMgdl = 0.0,
            )
        )
        // The same fall with the loop still predicting 5.0 or more above 6.0 mmol: no caution
        assertFalse(
            WizardDropTrendCaution.applies(
                delta = -0.31 * 18.0182,
                shortAvgDelta = -0.25 * 18.0182,
                bgMgdl = 7.2 * 18.0182,
                hp1Mmol = 5.2,
                iobUnits = 3.0,
                bgRiseMgdl = 0.0,
            )
        )
    }

    @Test
    fun halfTheMaxBolusOnThePumpStep() {
        assertEquals(1.25, WizardDropTrendCaution.scaledMaxBolus(2.5, 0.05), 0.0001)
        assertEquals(1.2, WizardDropTrendCaution.scaledMaxBolus(2.5, 0.1), 0.0001)
        assertEquals(0.05, WizardDropTrendCaution.scaledMaxBolus(0.05, 0.05), 0.0001)
    }

    @Test
    fun aThinFallDoesNotCount() {
        // 2026-11-14: was at 3.7 mmol, which path 3 (under 5.0 and falling) now catches; 5.3 mmol is above it
        assertFalse(
            WizardDropTrendCaution.applies(
                delta = -1.4,
                shortAvgDelta = -7.0,
                bgMgdl = 95.0,
                hp1Mmol = 3.0,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
    }

    @Test
    fun underFiveAndSlowlyFallingCounts() {
        // Path 3, prepare Set50% block 3: under 5.0 mmol with delta -0.05
        assertTrue(WizardDropTrendCaution.applies(delta = -0.9, shortAvgDelta = 0.0, bgMgdl = 88.0, hp1Mmol = 6.0, iobUnits = 0.0, bgRiseMgdl = 40.0))
        // Flat under 5.0, or falling at exactly 5.0: no
        assertFalse(WizardDropTrendCaution.applies(delta = -0.5, shortAvgDelta = 0.0, bgMgdl = 88.0, hp1Mmol = 6.0, iobUnits = 0.0, bgRiseMgdl = 40.0))
        assertFalse(WizardDropTrendCaution.applies(delta = -0.9, shortAvgDelta = 0.0, bgMgdl = 90.2, hp1Mmol = 6.0, iobUnits = 0.0, bgRiseMgdl = 40.0))
    }

    @Test
    fun anEveningFallUnderSevenCounts() {
        // Path 4, prepare Set50% block 4: 6.5 mmol, delta -0.20, short delta -0.10, HP1 still 5.5 (path 1 would miss it)
        fun at(minute: Int?, bg: Double = 6.5 * 18.0182) = WizardDropTrendCaution.applies(
            delta = -3.6, shortAvgDelta = -1.8, bgMgdl = bg, hp1Mmol = 5.5, iobUnits = 0.0, bgRiseMgdl = 40.0, minuteOfDay = minute,
        )
        assertTrue(at(21 * 60))
        assertTrue(at(23 * 60 + 59))
        assertFalse(at(20 * 60 + 59))
        assertFalse(at(0))
        assertFalse(at(null))
        assertFalse(at(22 * 60, bg = 7.1 * 18.0182))
    }

    @Test
    fun theActivityTempTargetCounts() {
        assertTrue(WizardDropTrendCaution.isActivityTempTarget(6.8 * 18.0, 60 * 60_000L))
        assertTrue(WizardDropTrendCaution.isActivityTempTarget(6.8 * 18.0182, 60 * 60_000L))
        // A 5 minute post-bolus 6.8 target is not activity; nor is another target
        assertFalse(WizardDropTrendCaution.isActivityTempTarget(6.8 * 18.0, 5 * 60_000L))
        assertFalse(WizardDropTrendCaution.isActivityTempTarget(7.0 * 18.0, 60 * 60_000L))
        // Path 5, ActivityProf50%: at or under 8.5 mmol and delta at most +0.1
        fun underActivity(tt: Boolean, bg: Double, delta: Double) = WizardDropTrendCaution.applies(
            delta = delta, shortAvgDelta = 0.0, bgMgdl = bg, hp1Mmol = 6.0, iobUnits = 0.0, bgRiseMgdl = 40.0, activityTempTarget = tt,
        )
        assertTrue(underActivity(tt = true, bg = 8.0 * 18.0182, delta = 1.8))
        assertFalse(underActivity(tt = false, bg = 8.0 * 18.0182, delta = 1.8))
        assertFalse(underActivity(tt = true, bg = 8.6 * 18.0182, delta = 0.0))
        assertFalse(underActivity(tt = true, bg = 8.0 * 18.0182, delta = 2.0))
    }

    @Test
    fun aFastFallWithHp1Under5CountsAboveFivePointFive() {
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -2.8,
                shortAvgDelta = -1.9,
                bgMgdl = 140.0,
                hp1Mmol = 4.9,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
    }

    @Test
    fun tooMuchInsulinForTheRiseCountsWhileDeltasAreStillUp() {
        assertTrue(WizardDropTrendCaution.iobDisproportionateToRise(4.2, 9.0))
        assertFalse(
            WizardDropTrendCaution.applies(
                delta = 4.0,
                shortAvgDelta = 4.0,
                bgMgdl = 106.0,
                hp1Mmol = 7.0,
                iobUnits = 4.2,
                bgRiseMgdl = 9.0,
            )
        )
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = 4.0,
                shortAvgDelta = 4.0,
                bgMgdl = 90.0,
                hp1Mmol = 7.0,
                iobUnits = 4.2,
                bgRiseMgdl = 9.0,
            )
        )
        assertFalse(WizardDropTrendCaution.iobDisproportionateToRise(0.2, 18.0))
    }

    @Test
    fun halfThePercent() {
        assertEquals(50, WizardDropTrendCaution.scaledWizPercent(100))
        assertEquals(45, WizardDropTrendCaution.scaledWizPercent(90))
    }

    @Test
    fun laterUnitsStartAThreeHourPlanAndAddOntoAnExistingOne() {
        val started = warsawPlanPlusUnits(null, 1.5)
        assertEquals(3, started?.numDoses)
        assertEquals(0.5, started?.perDoseInsulin ?: 0.0, 0.0001)
        val added = warsawPlanPlusUnits(started, 1.5)
        assertEquals(3.0, added?.totalInsulin ?: 0.0, 0.0001)
        assertEquals(1.0, added?.perDoseInsulin ?: 0.0, 0.0001)
    }
}
