package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

/** The dura boost is halved while walking (2026-10-24). */
class DuraWalkingTest {

    @Test
    fun sittingKeepsTheFullBoost() {
        assertEquals(1.0, duraWalkingFactor(steps5 = 20, steps30 = 100, steps60 = 300), 0.001)
        assertEquals(1.0, duraWalkingFactor(steps5 = 0, steps30 = 0, steps60 = 0), 0.001)
    }

    @Test
    fun anyOfTheThreeStepCountsHalvesIt() {
        assertEquals(0.5, duraWalkingFactor(steps5 = 21, steps30 = 0, steps60 = 0), 0.001)
        assertEquals(0.5, duraWalkingFactor(steps5 = 0, steps30 = 101, steps60 = 0), 0.001)
        assertEquals(0.5, duraWalkingFactor(steps5 = 0, steps30 = 0, steps60 = 301), 0.001)
    }

    @Test
    fun theStartOfThe24OctWalkIsHalved() {
        // 10:20 on Live: S5 0, S30 328, S60 328
        assertEquals(0.5, duraWalkingFactor(steps5 = 0, steps30 = 328, steps60 = 328), 0.001)
    }
}
