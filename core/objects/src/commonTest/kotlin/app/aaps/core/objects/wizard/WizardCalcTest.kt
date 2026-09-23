package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardCalcTest {

    @Test
    fun aProfileAbove100UsesTheBaseRates() {
        assertEquals(1.3, wizardProfileBaseScale(130), 0.0001)
        assertEquals(1.0, wizardProfileBaseScale(100), 0.0001)
        assertEquals(1.0, wizardProfileBaseScale(50), 0.0001)
    }

    @Test
    fun aRecentLowHalvesCarbsOnlyWhileGlucoseIsStillFalling() {
        assertTrue(halve())
        assertFalse(halve(profilePercent = 50))
        assertFalse(halve(lowBgRecent = false, wizardBgMgdl = 0.0))
        assertFalse(halve(delta = 0.0, shortDelta = 0.0, longDelta = 0.0))
        assertFalse(halve(hasGlucose = false))
    }

    @Test
    fun theRecent50FlagIsReadFromTheStateJson() {
        assertTrue(lowBgIsRecent50(true, """{"LowBG":"50recent"}"""))
        assertFalse(lowBgIsRecent50(false, """{"LowBG":"50recent"}"""))
        assertFalse(lowBgIsRecent50(true, """{"LowBG":"NO50rec"}"""))
    }

    @Test
    fun hpSafetyRemovesCobInsulinThenTakesAQuarterOff() {
        val cut = wizardHpCut(
            dose = 1.0,
            insulinFromCob = 0.4,
            percentage = 100.0,
            projectedHp = 6.0,
            bgMmol = 9.0,
            deltaMmol = 0.6,
            shortDeltaMmol = 0.6,
            positiveIob = 2.0,
        )
        assertTrue(cut.applied)
        assertEquals(0.4, cut.cobRemoved, 0.0001)
        assertEquals(0.45, cut.dose, 0.0001)
        assertFalse(
            wizardHpCut(1.0, 0.4, 100.0, 6.6, 9.0, 0.6, 0.6, 2.0).applied
        )
    }

    @Test
    fun aClearRiseAbove8MultipliesTheDose() {
        assertEquals(1.33, wizardRiseBoost(1.0, false, 8.1, 0.3, 0.3, 0.3)!!, 0.0001)
        assertNull(wizardRiseBoost(1.0, true, 8.1, 0.3, 0.3, 0.3))
        assertNull(wizardRiseBoost(1.0, false, 8.0, 0.3, 0.3, 0.3))
        assertNull(wizardRiseBoost(1.0, false, 8.1, 0.2, 0.3, 0.3))
    }

    private fun halve(
        profilePercent: Int = 100,
        lowBgRecent: Boolean = true,
        wizardBgMgdl: Double = 100.0,
        glucoseMgdl: Double = 100.0,
        delta: Double = -2.0,
        shortDelta: Double = -2.0,
        longDelta: Double = -2.0,
        hasGlucose: Boolean = true,
    ) = recent50ShouldHalveCarbs(
        profilePercent, lowBgRecent, wizardBgMgdl, glucoseMgdl, delta, shortDelta, longDelta, hasGlucose
    )
}
