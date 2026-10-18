package app.aaps.implementation.steps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneStepBlocksTest {

    private val min = 60_000L

    // A time exactly on a block number, so "now" is in block 1000.
    private val t0 = 1000 * 5 * min

    @Test
    fun firstCounterValueIsOnlyTheStartPoint() {
        val b = PhoneStepBlocks()
        b.onStepCounter(5000, t0 - 5 * min)
        assertEquals(0, b.steps5Min(t0))
    }

    @Test
    fun shortValuesAreSingleBlocksAndLongValuesAreTotals() {
        val b = PhoneStepBlocks()
        b.onStepCounter(1000, t0 - 20 * min)
        b.onStepCounter(1100, t0 - 15 * min) // block 997: 100
        b.onStepCounter(1300, t0 - 10 * min) // block 998: 200
        b.onStepCounter(1600, t0 - 5 * min)  // block 999: 300
        assertEquals(300, b.steps5Min(t0))
        assertEquals(200, b.steps10Min(t0))
        assertEquals(100, b.steps15Min(t0))
        assertEquals(600, b.steps30Min(t0))
        assertEquals(600, b.steps60Min(t0))
    }

    @Test
    fun stepsInTheSameBlockAddUp() {
        val b = PhoneStepBlocks()
        b.onStepCounter(0, t0 - 5 * min)
        b.onStepCounter(40, t0 - 5 * min + 10_000)
        b.onStepCounter(70, t0 - 5 * min + 20_000)
        assertEquals(70, b.steps5Min(t0))
    }

    @Test
    fun movementCountsFor15Minutes() {
        val b = PhoneStepBlocks()
        assertFalse(b.phoneMoved(t0))
        b.onAcceleration(0f, 0f, 9.80665f, t0)  // lying still: exactly 1 g
        assertFalse(b.phoneMoved(t0))
        b.onAcceleration(3f, 0f, 10f, t0)       // above 1.05 g
        assertTrue(b.phoneMoved(t0 + 14 * min))
        assertFalse(b.phoneMoved(t0 + 15 * min))
    }
}
