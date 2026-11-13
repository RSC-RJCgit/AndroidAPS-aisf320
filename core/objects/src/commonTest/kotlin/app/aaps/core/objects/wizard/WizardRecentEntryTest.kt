package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardRecentEntryTest {

    @Test
    fun bgMustHaveStayedUnderSixSinceTheEntry() {
        assertTrue(WizardRecentEntry.stayedLow(listOf(100.0, 95.0, 104.0)))
        assertFalse(WizardRecentEntry.stayedLow(listOf(100.0, 110.0, 104.0)))
        // No readings at all never starts the rule
        assertFalse(WizardRecentEntry.stayedLow(emptyList()))
    }

    @Test
    fun maxBolusIsCutToEightyPercentDownToThePumpStep() {
        assertEquals(2.4, WizardRecentEntry.scaledMaxBolus(3.0, 0.05), 1e-9)
        // 2.5 x 0.8 = 2.0
        assertEquals(2.0, WizardRecentEntry.scaledMaxBolus(2.5, 0.1), 1e-9)
        // 2.6 x 0.8 = 2.08, down to the 0.1 step
        assertEquals(2.0, WizardRecentEntry.scaledMaxBolus(2.6, 0.1), 1e-9)
        // Never under one step, never over the base
        assertEquals(0.05, WizardRecentEntry.scaledMaxBolus(0.05, 0.05), 1e-9)
        assertEquals(0.04, WizardRecentEntry.scaledMaxBolus(0.04, 0.05), 1e-9)
    }
}
