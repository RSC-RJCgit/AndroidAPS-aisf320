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
        assertFalse(
            WizardDropTrendCaution.applies(
                delta = -1.4,
                shortAvgDelta = -7.0,
                bgMgdl = 66.0,
                hp1Mmol = 3.0,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
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
