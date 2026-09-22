package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class LoRebLookbackTest {

    @Test
    fun confirmedRisingRecoverySelectsThirtyMinutes() {
        assertEquals(30, loRebLookbackMinutes(126.0, 3.6, 3.6))
    }

    @Test
    fun exactThresholdsRetainSixtyMinutes() {
        assertEquals(60, loRebLookbackMinutes(108.0, 3.6, 3.6))
        assertEquals(60, loRebLookbackMinutes(126.0, 1.8, 3.6))
        assertEquals(60, loRebLookbackMinutes(126.0, 3.6, 1.8))
    }

    @Test
    fun slowingOrFallingRecoveryRestoresSixtyMinutes() {
        assertEquals(60, loRebLookbackMinutes(126.0, -1.0, 3.6))
        assertEquals(60, loRebLookbackMinutes(126.0, 3.6, 0.0))
    }

    @Test
    fun invalidLiveValuesRetainSixtyMinutes() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(60, loRebLookbackMinutes(value, 3.6, 3.6))
            assertEquals(60, loRebLookbackMinutes(126.0, value, 3.6))
            assertEquals(60, loRebLookbackMinutes(126.0, 3.6, value))
        }
    }
}
