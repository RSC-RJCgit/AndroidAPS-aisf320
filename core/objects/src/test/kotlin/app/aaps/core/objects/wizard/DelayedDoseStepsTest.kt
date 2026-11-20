package app.aaps.core.objects.wizard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // 2026-11-20: activity from S5 or a bin over 50, S30 over 200, S60 over 400
    @Test
    fun activityCutsToHalf() {
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 51, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 51, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 201, steps60 = 0))
        assertEquals(0.5, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 401))
    }

    // 2026-11-20: heavy activity from S5 or a bin over 100, S30 over 300, S60 over 500
    @Test
    fun heavyActivityCutsToAQuarter() {
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 101, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 101, steps30 = 0, steps60 = 0))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 301, steps60 = 0))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 501))
        // 20 Nov 13:39: S5 199, S30 495, S60 495
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 199, steps10Bin = 0, steps15Bin = 0, steps30 = 495, steps60 = 495))
    }

    // 2026-11-20: super heavy activity, the loop's old heavy-activity limits; the dose factor stays x0.25
    @Test
    fun superHeavyActivityFromTheOldHeavyLimits() {
        assertTrue(delayedDoseSuperHeavy(steps5 = 301, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertTrue(delayedDoseSuperHeavy(steps5 = 0, steps10Bin = 301, steps15Bin = 0, steps30 = 0, steps60 = 0))
        assertTrue(delayedDoseSuperHeavy(steps5 = 0, steps10Bin = 0, steps15Bin = 301, steps30 = 0, steps60 = 0))
        assertTrue(delayedDoseSuperHeavy(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 1501, steps60 = 0))
        assertTrue(delayedDoseSuperHeavy(steps5 = 0, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 2501))
        assertEquals(0.25, delayedDoseWalkingFactor(steps5 = 301, steps10Bin = 0, steps15Bin = 0, steps30 = 0, steps60 = 0))
    }

    // 20 Nov 13:39 (S5 199, S30 495, S60 495) is heavy, not super heavy: the remaining FPU parts are not cancelled
    @Test
    fun heavyActivityIsNotSuperHeavy() {
        assertFalse(delayedDoseSuperHeavy(steps5 = 199, steps10Bin = 0, steps15Bin = 0, steps30 = 495, steps60 = 495))
        assertFalse(delayedDoseSuperHeavy(steps5 = 300, steps10Bin = 300, steps15Bin = 300, steps30 = 1500, steps60 = 2500))
    }

    @Test
    fun superHeavyCancelMarksOnlyItsOwnSeries() {
        SuperHeavyFpuCancel.cancel(42L)
        assertTrue(SuperHeavyFpuCancel.isCancelled(42L))
        assertFalse(SuperHeavyFpuCancel.isCancelled(43L))
    }
}
