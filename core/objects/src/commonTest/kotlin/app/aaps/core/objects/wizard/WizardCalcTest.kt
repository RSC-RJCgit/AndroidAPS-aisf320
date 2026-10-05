package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardCalcTest {

    @Test
    fun mealExtraTurnsExtendedCarbsIntoLaterFatAndProtein() {
        val plain = mealExtra(
            fpuInstead = false,
            unreliableSmb = false,
            carbs = 40,
            typedFat = 3,
            typedProtein = 4,
            percentage = 100,
            extendedCarbPercent = 35,
        )
        assertEquals(3, plain.fatGrams)
        assertEquals(4, plain.proteinGrams)
        assertEquals(14, plain.extendedCarbs)
        assertFalse(plain.useSavedMaxBolus)

        val later = mealExtra(
            fpuInstead = true,
            unreliableSmb = false,
            carbs = 40,
            typedFat = 3,
            typedProtein = 4,
            percentage = 80,
            extendedCarbPercent = 35,
        )
        assertEquals(14, later.fatGrams)
        assertEquals(16, later.proteinGrams)
        assertEquals(0, later.extendedCarbs)
        assertEquals(80, later.percentage)
        assertFalse(later.useSavedMaxBolus)

        val unreliable = mealExtra(
            fpuInstead = true,
            unreliableSmb = true,
            carbs = 40,
            typedFat = 3,
            typedProtein = 4,
            percentage = 100,
            extendedCarbPercent = 20,
        )
        assertEquals(28, unreliable.fatGrams)
        assertEquals(32, unreliable.proteinGrams)
        assertEquals(90, unreliable.percentage)
        assertEquals(0, unreliable.extendedCarbs)
        assertTrue(unreliable.useSavedMaxBolus)
        assertEquals(100, unreliable.immediateCarbPercent)
        assertEquals(null, later.immediateCarbPercent)

        val small = mealExtra(
            fpuInstead = true, unreliableSmb = false, carbs = 40, typedFat = 3, typedProtein = 4,
            percentage = 100, extendedCarbPercent = 35, smallMeal = true,
        )
        assertEquals(7, small.fatGrams)
        assertEquals(8, small.proteinGrams)

        val both = mealExtra(
            fpuInstead = false, unreliableSmb = true, carbs = 40, typedFat = 3, typedProtein = 4,
            percentage = 100, extendedCarbPercent = 20, smallMeal = true,
        )
        assertEquals(14, both.fatGrams)
        assertEquals(16, both.proteinGrams)
    }


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

    @Test
    fun walkingSoonCutsThePercentTo70AndStartsTickedOnlyWhenMovingAndLow() {
        assertEquals(100.0, walkingSoonImmediatePercent(false, 100.0), 0.0001)
        assertEquals(70.0, walkingSoonImmediatePercent(true, 100.0), 0.0001)
        assertEquals(50.0, walkingSoonImmediatePercent(true, 50.0), 0.0001)
        assertEquals(3.0, walkingSoonHeldUnits(true, 100.0, 10.0), 0.0001)
        assertEquals(1.0, walkingSoonHeldUnits(true, 80.0, 10.0), 0.0001)
        assertEquals(0.0, walkingSoonHeldUnits(true, 50.0, 10.0), 0.0001)
        assertEquals(0.0, walkingSoonHeldUnits(false, 100.0, 10.0), 0.0001)
        assertEquals(true, walkingSoonDefault(80, 200, 100.0, 0.0))
        assertEquals(false, walkingSoonDefault(0, 0, 100.0, 0.0))
        assertEquals(false, walkingSoonDefault(80, 200, 120.0, 0.0))
        assertEquals(false, walkingSoonDefault(80, 200, 100.0, 2.0))
        assertNull(walkingSoonDefault(null, null, 100.0, 0.0))
        // Thresholds are 20 for 5 minutes and 100 for 30 minutes. S30 alone does not count once S15 and S5 are both 0.
        assertEquals(true, walkingSoonDefault(20, 0, 100.0, 0.0))
        assertEquals(false, walkingSoonDefault(19, 99, 100.0, 0.0))
        assertEquals(true, walkingSoonDefault(10, 100, 100.0, 0.0, steps15 = 15))
        assertEquals(false, walkingSoonDefault(0, 100, 100.0, 0.0, steps15 = 0))
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
