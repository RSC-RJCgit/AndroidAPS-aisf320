package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TddFactorTest {

    @Test
    fun aLowRecentTotalIsPulledUpToTheFloor() {
        val result = blendedTddRatio(tdd7D = 100.0, tdd1D = 100.0, tddLast4H = 5.0, tddLast8to4H = 5.0)
        assertEquals(0.70, result?.ratio ?: -1.0, 0.001)
        assertEquals(100.0, result?.tdd7D ?: -1.0, 0.001)
    }

    @Test
    fun aNormalBlendStaysInsideTheWideClamp() {
        val result = blendedTddRatio(tdd7D = 40.0, tdd1D = 40.0, tddLast4H = 10.0, tddLast8to4H = 10.0)
        assertEquals(1.165, result?.ratio ?: -1.0, 0.001)
    }

    @Test
    fun missingPartsLeaveTheRatioUnset() {
        assertNull(blendedTddRatio(tdd7D = 40.0, tdd1D = null, tddLast4H = 10.0, tddLast8to4H = 10.0))
        assertNull(blendedTddRatio(tdd7D = 0.0, tdd1D = 40.0, tddLast4H = 10.0, tddLast8to4H = 10.0))
    }

    @Test
    fun theFactorClampIsTighterThanTheRatioClamp() {
        assertEquals(1.2, tddFactorValue(enabled = true, fallback = 1.0, tddRatio = 1.5), 0.001)
        assertEquals(0.8, tddFactorValue(enabled = true, fallback = 1.0, tddRatio = 0.7), 0.001)
        assertEquals(1.0, tddFactorValue(enabled = true, fallback = 0.9, tddRatio = 1.0), 0.001)
    }

    @Test
    fun theSwitchOffUsesTheFallback() {
        assertEquals(0.9, tddFactorValue(enabled = false, fallback = 0.9, tddRatio = 1.5), 0.001)
    }
}
