package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class OvernightSmbGuardsTest {

    @Test
    fun theDeliveryRatioIsThreeHundredthsLowerFromMidnightUntilEight() {
        assertEquals(0.07, overnightSmbDeliveryRatio(0.10, hour = 0), 1e-9)
        assertEquals(0.07, overnightSmbDeliveryRatio(0.10, hour = 7), 1e-9)
    }

    @Test
    fun theDeliveryRatioIsUntouchedFromEightAndBeforeMidnight() {
        assertEquals(0.10, overnightSmbDeliveryRatio(0.10, hour = 8), 1e-9)
        assertEquals(0.10, overnightSmbDeliveryRatio(0.10, hour = 23), 1e-9)
    }

    @Test
    fun theDeliveryRatioNeverGoesBelowZero() {
        assertEquals(0.0, overnightSmbDeliveryRatio(0.02, hour = 3), 1e-9)
    }

    @Test
    fun theCadenceFloorMakesItThreeMinutesBetweenTenAndFourUnderEightMmol() {
        assertEquals(180.0, overnightSmbIntervalSec(60.0, minuteOfDay = 22 * 60, bgMgdl = 130.0), 1e-9)
        assertEquals(180.0, overnightSmbIntervalSec(60.0, minuteOfDay = 3 * 60 + 59, bgMgdl = 130.0), 1e-9)
    }

    @Test
    fun theCadenceFloorIsOffOutsideTheWindowAndAtOrAboveEightMmol() {
        assertEquals(60.0, overnightSmbIntervalSec(60.0, minuteOfDay = 4 * 60, bgMgdl = 130.0), 1e-9)
        assertEquals(60.0, overnightSmbIntervalSec(60.0, minuteOfDay = 21 * 60 + 59, bgMgdl = 130.0), 1e-9)
        assertEquals(60.0, overnightSmbIntervalSec(60.0, minuteOfDay = 2 * 60, bgMgdl = 144.0), 1e-9)
    }

    @Test
    fun theCadenceFloorNeverShortensALongerProfileInterval() {
        assertEquals(300.0, overnightSmbIntervalSec(300.0, minuteOfDay = 2 * 60, bgMgdl = 110.0), 1e-9)
    }
}
