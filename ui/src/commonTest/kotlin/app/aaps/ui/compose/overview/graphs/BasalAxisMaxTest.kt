package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fixed basal scale (2026-11-19): a 15% floor (30% until later the same day) plus a log curve with knee 0.25 U/h (1 U/h until later the same day), full height at the max basal setting.
 * It replaced the scale fitted to the basal in view (2026-11-16), which changed column heights on every scroll.
 */
class BasalAxisMaxTest {

    private val hour = 3_600_000L

    private fun data(scaleTop: Double) = BasalGraphData(
        profileBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(12 * hour, 0.5)),
        actualBasal = listOf(GraphDataPoint(0L, 0.5), GraphDataPoint(6 * hour, 3.0), GraphDataPoint(7 * hour, 0.5)),
        maxBasal = 3.0,
        scaleTop = scaleTop
    )

    private fun expected(rate: Double, top: Double) =
        BASAL_SCALE_FLOOR + (1.0 - BASAL_SCALE_FLOOR) * ln(1.0 + rate / BASAL_SCALE_KNEE) / ln(1.0 + top / BASAL_SCALE_KNEE)

    @Test
    fun noBasalHasNoColumn() {
        assertEquals(0.0, basalScaleHeight(0.0, 5.0), 1e-9)
        assertEquals(0.0, basalScaleHeight(Double.NaN, 5.0), 1e-9)
    }

    @Test
    fun theExamplesPageHeightsAtAFiveUnitTop() {
        assertEquals(1.0, basalScaleHeight(5.0, 5.0), 1e-9)
        assertEquals(expected(2.5, 5.0), basalScaleHeight(2.5, 5.0), 1e-9)
        assertEquals(expected(0.5, 5.0), basalScaleHeight(0.5, 5.0), 1e-9)
        // Any rate above 0 gets at least the floor
        assertEquals(BASAL_SCALE_FLOOR, basalScaleHeight(0.0001, 5.0), 1e-3)
    }

    @Test
    fun aRateAboveTheTopIsDrawnAtFullHeight() {
        assertEquals(1.0, basalScaleHeight(8.0, 5.0), 1e-9)
    }

    @Test
    fun theTopIsTheMaxBasalSettingWhenSet() {
        assertEquals(5.0, basalScaleTop(data(scaleTop = 5.0)), 1e-9)
    }

    @Test
    fun withoutTheSettingTheTopIsTheHighestBasalLoaded() {
        assertEquals(3.0, basalScaleTop(data(scaleTop = 0.0)), 1e-9)
        assertEquals(1.0, basalScaleTop(null), 1e-9)
    }

    @Test
    fun theAxisIsFixedWhateverIsInView() {
        assertEquals(1.0 / 0.22, basalScaleAxisMax(0.22), 1e-9)
        assertEquals(1.0 / 0.5, basalScaleAxisMax(0.5), 1e-9)
    }
}
