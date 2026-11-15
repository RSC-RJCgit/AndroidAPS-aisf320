package app.aaps.core.objects.wizard

import kotlin.test.Test
import kotlin.test.assertEquals

class DelayedDoseStepsTest {

    @Test
    fun sittingStillGivesTheFullDose() {
        assertEquals(1.0, delayedDoseWalkingFactor(steps5 = 10, steps10Bin = 10, steps15Bin = 30, steps30 = 100, steps60 = 300))
    }

    @Test
    fun littleWalkingCutsToThreeQuarters() {
        assertEquals(0.75, delayedDoseWalkingFactor(steps5 = 11, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.75, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 31, steps30 = 0, steps60 = 0))
        assertEquals(0.75, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 101, steps60 = 0))
        assertEquals(0.75, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 301))
    }

    @Test
    fun activityCutsToHalf() {
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 201, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 501, steps60 = 0))
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 801))
    }

    @Test
    fun heavyActivityCutsToAQuarter() {
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 301, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 1501, steps60 = 0))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 2501))
    }
}
