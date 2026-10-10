package app.aaps.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarbModelTest {

    @Test
    fun peakIsAt90MinutesAndTheTailsAreQuiet() {
        val peak = carbModelRatePer5Min(10.0, 90.0)
        assertTrue(peak > carbModelRatePer5Min(10.0, 30.0))
        assertTrue(peak > carbModelRatePer5Min(10.0, 180.0))
        assertEquals(0.0, carbModelRatePer5Min(10.0, 0.0))
        assertEquals(0.0, carbModelRatePer5Min(10.0, -5.0))
        assertEquals(0.0, carbModelRatePer5Min(10.0, 361.0))
        assertEquals(0.0, carbModelRatePer5Min(0.0, 90.0))
    }
}
