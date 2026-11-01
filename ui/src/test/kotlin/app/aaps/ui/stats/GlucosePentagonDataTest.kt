package app.aaps.ui.stats

import app.aaps.core.interfaces.stats.DexcomTIR
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GlucosePentagonDataTest {

    private fun readings(mean: Double, sd: Double, low: Double = 0.0, high: Double = 0.0): DexcomTIR =
        mock<DexcomTIR>().also { tir ->
            whenever(tir.mean()).thenReturn(mean)
            whenever(tir.calculateSD()).thenReturn(sd)
            whenever(tir.inRangePct()).thenReturn(100.0 - low - high)
            whenever(tir.lowPct()).thenReturn(low)
            whenever(tir.highPct()).thenReturn(high)
        }

    @Test
    fun referenceReadingsHaveTheReferenceArea() {
        val data = computeCgpData(readings(mean = 90.0, sd = 15.3))
        assertEquals(17.0, data.cvPct, 0.0001)
        assertEquals(1.0, data.pgr, 0.0001)
        data.normalizedValues.zip(data.referenceValues).forEach { (actual, reference) ->
            assertEquals(reference, actual, 0.0001)
        }
    }

    @Test
    fun rawPercentagesAndMeanUseTheDexcomNumbers() {
        val data = computeCgpData(readings(mean = 150.0, sd = 45.0, low = 5.0, high = 25.0))
        assertEquals(30.0, data.torPct)
        assertEquals(30.0, data.cvPct)
        assertEquals(5.0, data.hypoPct)
        assertEquals(25.0, data.hyperPct)
        assertEquals(150.0, data.meanGlucose)
    }

    @Test
    fun extremeReadingsRemainInsideTheChartAndHaveAFiniteAreaRatio() {
        val data = computeCgpData(readings(mean = 600.0, sd = 600.0, low = 20.0, high = 80.0))
        assertTrue(data.normalizedValues.all { it in 0.18..1.0 })
        assertTrue(data.pgr.isFinite())
        assertTrue(data.pgr > 1.0)
    }
}
