package app.aaps.ui.compose.overview.graphs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/** Highest historical value in the last hour; step values carry across the window boundary. */
internal fun lastHourPeak(points: List<GraphDataPoint>, now: Long, stepped: Boolean = false): GraphDataPoint? {
    val start = now - 60 * 60_000L
    val ordered = points.filter { it.timestamp <= now && it.value.isFinite() }
        .groupBy { it.timestamp }.values.map { it.last() }.sortedBy { it.timestamp }
    val samples = if (stepped) ordered.filterIndexed { index, point ->
        index == 0 || point.value != ordered[index - 1].value
    } else ordered
    val candidates = samples.filter { it.timestamp >= start }.toMutableList()
    if (stepped) samples.lastOrNull { it.timestamp < start }?.let {
        candidates.add(GraphDataPoint(start, it.value))
    }
    val peak = candidates.maxWithOrNull(compareBy<GraphDataPoint> { it.value }.thenBy { it.timestamp }) ?: return null
    if (!stepped) return peak
    // Put a step label within the column, rather than on its vertical edge.
    val end = samples.firstOrNull { it.timestamp > peak.timestamp }?.timestamp ?: now
    return peak.copy(timestamp = peak.timestamp + (end - peak.timestamp) / 2)
}

/** A small label at the real point, using the same axis as its line or basal column. */
internal class RecentPeakLabel(
    private val x: Double,
    private val y: Double,
    private val label: String,
    private val color: Color,
    private val textMeasurer: TextMeasurer,
    private val endAxis: Boolean = false,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            if (ranges.xStep == 0.0) return
            val px = layerBounds.left + layerDimensions.startPadding +
                layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat() - scroll
            if (px < layerBounds.left || px > layerBounds.right) return
            val range = ranges.getYRange(if (endAxis) Axis.Position.Vertical.End else Axis.Position.Vertical.Start)
            if (range.length <= 0.0 || y !in range.minY..range.maxY) return
            val py = layerBounds.bottom - layerBounds.height * ((y - range.minY) / range.length).toFloat()
            val layout = textMeasurer.measure(label, TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val left = (px - layout.size.width / 2f).coerceIn(layerBounds.left, maxOf(layerBounds.left, layerBounds.right - layout.size.width))
            val top = (py - layout.size.height - 3f).coerceIn(layerBounds.top, maxOf(layerBounds.top, layerBounds.bottom - layout.size.height))
            with(mutableDrawScope) {
                drawCircle(color, radius = 2f, center = Offset(px, py))
                drawText(layout, topLeft = Offset(left, top))
            }
        }
    }
}
