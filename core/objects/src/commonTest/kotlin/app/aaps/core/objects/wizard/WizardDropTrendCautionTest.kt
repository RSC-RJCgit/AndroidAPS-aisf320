package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardDropTrendCautionTest {

    @Test
    fun anEstablishedFallUnder6HalvesTheDose() {
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -2.8,
                shortAvgDelta = -1.9,
                longAvgDelta = -1.9,
                bgMgdl = 100.0,
                hp1Mmol = 6.0,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
    }

    @Test
    fun aThinFallDoesNotCount() {
        assertFalse(
            WizardDropTrendCaution.applies(
                delta = -1.4,
                shortAvgDelta = -7.0,
                longAvgDelta = -30.0,
                bgMgdl = 66.0,
                hp1Mmol = 3.0,
                iobUnits = 0.0,
                bgRiseMgdl = 40.0,
            )
        )
    }

    @Test
    fun anEstablishedFallWithHp1Under5CountsAbove6() {
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = -2.8,
                shortAvgDelta = -1.9,
                longAvgDelta = -1.9,
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
        assertTrue(
            WizardDropTrendCaution.applies(
                delta = 4.0,
                shortAvgDelta = 4.0,
                longAvgDelta = 4.0,
                bgMgdl = 106.0,
                hp1Mmol = 7.0,
                iobUnits = 4.2,
                bgRiseMgdl = 9.0,
            )
        )
        assertFalse(WizardDropTrendCaution.iobDisproportionateToRise(0.2, 18.0))
    }

    @Test
    fun halfTheMaxBolusAndHalfThePercent() {
        assertEquals(1.0, WizardDropTrendCaution.scaledMaxBolus(2.0, 0.05))
        assertEquals(50, WizardDropTrendCaution.scaledWizPercent(100))
        assertEquals(45, WizardDropTrendCaution.scaledWizPercent(90))
    }
}
