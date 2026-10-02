package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals

class BottomHalfScaleTest {

    @Test
    fun zeroSitsOnTheBottom() {
        assertEquals(-4.0, bottomHalfY(0.0, SMB_DELIVERY_SCALE_MAX, -4.0))
    }

    @Test
    fun fullScaleSitsOnTheMiddle() {
        assertEquals(0.0, bottomHalfY(1.0, SMB_DELIVERY_SCALE_MAX, -4.0))
        assertEquals(0.0, bottomHalfY(0.15, PP_WEIGHT_SCALE_MAX, -4.0))
    }

    @Test
    fun halfScaleSitsHalfwayUpTheBottomHalf() {
        assertEquals(-2.0, bottomHalfY(0.5, ACCE_WEIGHT_SCALE_MAX, -4.0))
    }

    @Test
    fun pastTheScaleStaysOnTheMiddle() {
        assertEquals(0.0, bottomHalfY(2.0, SMB_DELIVERY_SCALE_MAX, -4.0))
    }

    @Test
    fun peakIsAtLeastATenth() {
        assertEquals(0.1, iobThPeak(emptyList()))
        assertEquals(0.1, iobThPeak(listOf(0.0)))
        assertEquals(3.0, iobThPeak(listOf(-1.0, 3.0)))
    }
}
