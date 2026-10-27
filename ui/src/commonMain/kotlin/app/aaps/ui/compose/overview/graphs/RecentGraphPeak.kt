package app.aaps.ui.compose.overview.graphs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

private const val HOUR_MS = 60 * 60_000L

/** Highest historical value in the last hour; step values carry across the window boundary. */
internal fun lastHourPeak(points: List<GraphDataPoint>, now: Long, stepped: Boolean = false): GraphDataPoint? =
    hourlyPeaks(points, now, stepped, maxHours = 1).firstOrNull()

/**
 * One peak per hour, counted back from [now]: the highest value in now-1h..now, then in now-2h..now-1h, and so
 * on, back to the first point (or [maxHours] windows). Newest first. Step values carry across a window boundary,
 * and a step label is put in the middle of its column, inside its own hour.
 */
internal fun hourlyPeaks(points: List<GraphDataPoint>, now: Long, stepped: Boolean = false, maxHours: Int = Int.MAX_VALUE): List<GraphDataPoint> {
    val ordered = points.filter { it.timestamp <= now && it.value.isFinite() }
        .groupBy { it.timestamp }.values.map { it.last() }.sortedBy { it.timestamp }
    val samples = if (stepped) ordered.filterIndexed { index, point ->
        index == 0 || point.value != ordered[index - 1].value
    } else ordered
    val first = samples.firstOrNull()?.timestamp ?: return emptyList()
    val peaks = mutableListOf<GraphDataPoint>()
    var end = now
    var windows = 0
    while (end > first && windows < maxHours) {
        val start = end - HOUR_MS
        val candidates = samples.filter { it.timestamp in start..end }.toMutableList()
        if (stepped) samples.lastOrNull { it.timestamp < start }?.let {
            candidates.add(GraphDataPoint(start, it.value))
        }
        candidates.maxWithOrNull(compareBy<GraphDataPoint> { it.value }.thenBy { it.timestamp })?.let { peak ->
            peaks += if (!stepped) peak else {
                // Put a step label within the column, rather than on its vertical edge.
                val next = minOf(samples.firstOrNull { it.timestamp > peak.timestamp }?.timestamp ?: now, end)
                peak.copy(timestamp = peak.timestamp + (next - peak.timestamp) / 2)
            }
        }
        end = start
        windows++
    }
    return peaks
}

/** One hourly peak label: [x] on the chart's x axis, [y] on the axis of its line or basal column. */
internal class PeakMark(val x: Double, val y: Double, val label: String)

/**
 * Small labels at the real points, using the same axis as their line or basal column. [marks] are newest
 * first; a label that would overlap one already drawn is left out, so the newest always shows.
 * With [pinToTop] the labels sit at the top edge of the graph, in line with the IOB/COB title, and have
 * no dot. The flipped basal column hangs from the top, so this is the top of the column.
 */
internal class RecentPeakLabels(
    private val marks: List<PeakMark>,
    private val color: Color,
    private val textMeasurer: TextMeasurer,
    private val endAxis: Boolean = false,
    private val pinToTop: Boolean = false,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            if (ranges.xStep == 0.0) return
            val range = ranges.getYRange(if (endAxis) Axis.Position.Vertical.End else Axis.Position.Vertical.Start)
            if (range.length <= 0.0) return
            val style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val drawn = mutableListOf<Rect>()
            for (mark in marks) {
                val px = layerBounds.left + layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((mark.x - ranges.minX) / ranges.xStep).toFloat() - scroll
                if (px < layerBounds.left || px > layerBounds.right) continue
                if (mark.y !in range.minY..range.maxY) continue
                val py = layerBounds.bottom - layerBounds.height * ((mark.y - range.minY) / range.length).toFloat()
                val layout = textMeasurer.measure(mark.label, style)
                val left = (px - layout.size.width / 2f).coerceIn(layerBounds.left, maxOf(layerBounds.left, layerBounds.right - layout.size.width))
                val top = if (pinToTop) layerBounds.top
                else (py - layout.size.height - 3f).coerceIn(layerBounds.top, maxOf(layerBounds.top, layerBounds.bottom - layout.size.height))
                val bounds = Rect(Offset(left, top), Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                if (drawn.any { it.overlaps(bounds) }) continue
                drawn += bounds
                with(mutableDrawScope) {
                    if (!pinToTop) drawCircle(color, radius = 2f, center = Offset(px, py))
                    drawText(layout, topLeft = Offset(left, top))
                }
            }
        }
    }
}

/** Decoration for the last BG reading at the end of the BGL curve, rendered without strict boundary culling. */
internal class LastBgLabelDecoration(
    private val x: Double,
    private val y: Double,
    private val label: String,
    private val color: Color,
    private val textMeasurer: TextMeasurer,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            if (ranges.xStep == 0.0) return
            val range = ranges.getYRange(Axis.Position.Vertical.Start)
            if (range.length <= 0.0) return
            val style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val px = layerBounds.left + layerDimensions.startPadding +
                layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat() - scroll
            if (px < layerBounds.left || px > layerBounds.right + 40f) return
            if (y !in range.minY..range.maxY) return
            val py = layerBounds.bottom - layerBounds.height * ((y - range.minY) / range.length).toFloat()
            val layout = textMeasurer.measure(label, style)
            val left = px + 4f
            val top = (py - layout.size.height / 2f).coerceIn(layerBounds.top, maxOf(layerBounds.top, layerBounds.bottom - layout.size.height))
            with(mutableDrawScope) {
                drawText(layout, topLeft = Offset(left, top))
            }
        }
    }
}
