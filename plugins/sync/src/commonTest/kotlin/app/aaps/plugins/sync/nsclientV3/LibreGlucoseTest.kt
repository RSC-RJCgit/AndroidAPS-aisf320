package app.aaps.plugins.sync.nsclientV3

import kotlin.test.Test
import kotlin.test.assertEquals

class LibreGlucoseTest {

    @Test
    fun slopeAndOffsetStayAbove40() {
        assertEquals(130.0, calibratedLibre(130.0, 1.0, 0.0, 1.0), 0.001)
        assertEquals(40.0, calibratedLibre(10.0, 1.0, 0.0, 1.0), 0.001)
        assertEquals(148.0, calibratedLibre(100.0, 1.3, 1.0, 18.0182), 0.05)
    }

    @Test
    fun firstPointIsTheCalibratedValue() {
        assertEquals(120.0, libreSpecial(120.0, lastSmooth = -1.0, elapsedMinutes = 0.0), 0.001)
    }

    @Test
    fun aClosePointMovesOnlyPartWay() {
        val next = libreSpecial(140.0, lastSmooth = 100.0, elapsedMinutes = 5.0, alpha = 0.3, maxGap = 20.0)
        assertEquals(113.27, next, 0.05)
    }
}
