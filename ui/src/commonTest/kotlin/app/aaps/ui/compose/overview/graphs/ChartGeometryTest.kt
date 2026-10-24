package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals

/** Scroll and zoom copied between graphs of different layout keep the same time (2026-10-24). */
class ChartGeometryTest {

    // The BG graph: a 48 dp marker, so wider steps and more padding. A lower graph: a small marker.
    private val bg = ChartGeometry(minX = 0.0, xStep = 1.0, baseSpacing = 60f, baseScalableStart = 0f, unscalableStart = 24f)
    private val lower = ChartGeometry(minX = 0.0, xStep = 1.0, baseSpacing = 10f, baseScalableStart = 0f, unscalableStart = 2f)

    @Test
    fun aScrollBecomesTheSameTime() {
        val zoom = 0.5f
        val bgScroll = 3024f // 24 + 60 * 0.5 * 100: minute 100 at the left edge
        val lowerZoom = convertZoom(zoom, bg, lower)
        val lowerScroll = convertScroll(bgScroll, bg, zoom, lower, lowerZoom)
        assertEquals(100.0, bg.xAt(bgScroll, zoom), 1e-6)
        assertEquals(100.0, lower.xAt(lowerScroll, lowerZoom), 1e-3)
    }

    @Test
    fun aZoomBecomesTheSameMinutesPerWidth() {
        val lowerZoom = convertZoom(0.5f, bg, lower)
        // Pixels per minute must be equal: 60 * 0.5 on BG, 10 * lowerZoom below
        assertEquals(60f * 0.5f, 10f * lowerZoom, 1e-4f)
    }

    @Test
    fun withoutALayoutNothingChanges() {
        assertEquals(1234f, convertScroll(1234f, null, 1f, lower, 1f))
        assertEquals(0.7f, convertZoom(0.7f, bg, null))
    }
}
