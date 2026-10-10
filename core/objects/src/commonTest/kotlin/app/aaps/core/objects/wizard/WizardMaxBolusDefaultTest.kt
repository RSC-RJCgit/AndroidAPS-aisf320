package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals

class WizardMaxBolusDefaultTest {

    @Test
    fun lowGlucoseStartsAtTwoUnlessTheSavedLimitIsLower() {
        assertEquals(2.0, wizardMaxBolusDefault(3.0, 100.0, recentLowEntry = false, bolusStep = 0.1), 0.0001)
        assertEquals(1.5, wizardMaxBolusDefault(1.5, 100.0, recentLowEntry = false, bolusStep = 0.1), 0.0001)
    }

    @Test
    fun glucoseAtOrAboveSixKeepsTheSavedLimit() {
        assertEquals(3.0, wizardMaxBolusDefault(3.0, 108.1, recentLowEntry = false, bolusStep = 0.1), 0.0001)
    }

    @Test
    fun recentLowEntryTakesTwentyPercentOff() {
        assertEquals(1.6, wizardMaxBolusDefault(3.0, 100.0, recentLowEntry = true, bolusStep = 0.1), 0.0001)
    }
}
