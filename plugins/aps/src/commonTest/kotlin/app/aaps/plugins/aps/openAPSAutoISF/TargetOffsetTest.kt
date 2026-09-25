package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetOffsetTest {

    @Test
    fun mildOffsetZeroClearsTheRatioOffset() {
        val open = targetOffset(
            smbDeliveryRatioMax = 0.5,
            todOffsetMgdl = 0.0,
            mildOffsetZero = false,
            tempTargetSet = false,
            minBg = 90.0,
            hour = 12,
            bg = 95.0,
            cob = 0.0,
            carbAgeMin = 363.0,
        )
        assertEquals(9.0, open.varOffset)
        assertEquals(99.0, open.targetBgOffset)
        assertTrue(open.offsetSoZeroSmb)

        val closed = targetOffset(
            smbDeliveryRatioMax = 0.5,
            todOffsetMgdl = 0.0,
            mildOffsetZero = true,
            tempTargetSet = false,
            minBg = 90.0,
            hour = 12,
            bg = 95.0,
            cob = 0.0,
            carbAgeMin = 363.0,
        )
        assertEquals(0.0, closed.varOffset)
        assertEquals(90.0, closed.targetBgOffset)
        assertFalse(closed.offsetSoZeroSmb)
        val underTarget = targetOffset(
            smbDeliveryRatioMax = 0.5,
            todOffsetMgdl = 0.0,
            mildOffsetZero = true,
            tempTargetSet = false,
            minBg = 90.0,
            hour = 12,
            bg = 85.0,
            cob = 0.0,
            carbAgeMin = 363.0,
        )
        assertTrue(underTarget.offsetSoZeroSmb)
    }

    @Test
    fun carbsInTheBandHalveTheSmbUnlessARiseFired() {
        val offset = targetOffset(
            smbDeliveryRatioMax = 1.0,
            todOffsetMgdl = 0.0,
            mildOffsetZero = false,
            tempTargetSet = false,
            minBg = 90.0,
            hour = 12,
            bg = 100.0,
            cob = 20.0,
            carbAgeMin = 10.0,
        )
        val halved = applyTargetOffsetToSmb(0.2, 100.0, offset, 20.0, skipCarbBand = false, mildOffsetZero = false)
        assertEquals(0.1, halved.first)
        val kept = applyTargetOffsetToSmb(0.2, 100.0, offset, 20.0, skipCarbBand = true, mildOffsetZero = false)
        assertEquals(0.2, kept.first)
    }
}
