package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class DuraIobTaperTest {

    @Test
    fun aLowIobKeepsTheFullBoost() {
        assertEquals(1.0, duraIobTaperFactor(iob = 1.5, hpMmol = 6.0), 0.001)
        assertEquals(1.0, duraIobTaperFactor(iob = 0.4, hpMmol = null), 0.001)
    }

    @Test
    fun aHighIobKeepsThirtyPercent() {
        assertEquals(0.3, duraIobTaperFactor(iob = 3.0, hpMmol = 6.0), 0.001)
        assertEquals(0.3, duraIobTaperFactor(iob = 4.0, hpMmol = null), 0.001)
    }

    @Test
    fun theMiddleIobIsHalfwayDown() {
        assertEquals(0.65, duraIobTaperFactor(iob = 2.25, hpMmol = 6.0), 0.001)
    }

    @Test
    fun aHighPredictionSkipsTheShrink() {
        assertEquals(1.0, duraIobTaperFactor(iob = 4.0, hpMmol = 7.1), 0.001)
        assertEquals(0.3, duraIobTaperFactor(iob = 4.0, hpMmol = 7.0), 0.001)
    }
}
