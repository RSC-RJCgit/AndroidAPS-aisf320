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

/**
 * Every basal column above the profile basal (2026-11-20, per explicit request), for a label as well as the hourly peaks:
 * one point per column, in the middle of the column, at its real rate. A column counts when its rate is above the profile
 * basal at its middle. Newest first.
 */
internal fun aboveProfileBasalColumns(actual: List<GraphDataPoint>, profile: List<GraphDataPoint>, now: Long): List<GraphDataPoint> {
    val ordered = actual.filter { it.timestamp <= now && it.value.isFinite() }
        .groupBy { it.timestamp }.values.map { it.last() }.sortedBy { it.timestamp }
    val columns = ordered.filterIndexed { index, point -> index == 0 || point.value != ordered[index - 1].value }
    val profileSteps = profile.filter { it.value.isFinite() }.sortedBy { it.timestamp }
    return columns.mapIndexedNotNull { index, column ->
        val end = columns.getOrNull(index + 1)?.timestamp ?: now
        if (end <= column.timestamp) return@mapIndexedNotNull null
        val middle = column.timestamp + (end - column.timestamp) / 2
        val profileRate = profileSteps.lastOrNull { it.timestamp <= middle }?.value ?: return@mapIndexedNotNull null
        if (column.value > profileRate + 0.005) GraphDataPoint(middle, column.value) else null
    }.reversed()
}

/** The hourly peaks and the columns above the profile basal together, each column once, newest first. */
internal fun basalLabelPoints(actual: List<GraphDataPoint>, profile: List<GraphDataPoint>, now: Long): List<GraphDataPoint> =
    (hourlyPeaks(actual, now, stepped = true) + aboveProfileBasalColumns(actual, profile, now))
        .distinctBy { it.timestamp }.sortedByDescending { it.timestamp }

/** One hourly peak label: [x] on the chart's x axis, [y] on the axis of its line or basal column; [color] overrides the labels' colour. */
internal class PeakMark(val x: Double, val y: Double, val label: String, val color: Color? = null)

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
    private val dots: Boolean = true,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            if (ranges.xStep == 0.0) return
            val range = ranges.getYRange(if (endAxis) Axis.Position.Vertical.End else Axis.Position.Vertical.Start)
            if (range.length <= 0.0) return
            val baseStyle = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val drawn = mutableListOf<Rect>()
            for (mark in marks) {
                val px = layerBounds.left + layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((mark.x - ranges.minX) / ranges.xStep).toFloat() - scroll
                if (px < layerBounds.left || px > layerBounds.right) continue
                if (mark.y !in range.minY..range.maxY) continue
                val py = layerBounds.bottom - layerBounds.height * ((mark.y - range.minY) / range.length).toFloat()
                val markColor = mark.color ?: color
                val layout = textMeasurer.measure(mark.label, if (mark.color == null) baseStyle else baseStyle.copy(color = markColor))
                val left = (px - layout.size.width / 2f).coerceIn(layerBounds.left, maxOf(layerBounds.left, layerBounds.right - layout.size.width))
                val top = if (pinToTop) layerBounds.top
                else (py - layout.size.height - 3f).coerceIn(layerBounds.top, maxOf(layerBounds.top, layerBounds.bottom - layout.size.height))
                val bounds = Rect(Offset(left, top), Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                if (drawn.any { it.overlaps(bounds) }) continue
                drawn += bounds
                with(mutableDrawScope) {
                    if (!pinToTop && dots) drawCircle(markColor, radius = 2f, center = Offset(px, py))
                    drawText(layout, topLeft = Offset(left, top))
                }
            }
        }
    }
}

/** Label beside the last BG reading, kept inside the graph at its right edge. */
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
            // 13 sp (2026-10-30, per explicit request): larger than the other labels, smaller than 3426's 14.4
            val style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val px = layerBounds.left + layerDimensions.startPadding +
                layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat() - scroll
            if (px < layerBounds.left || px > layerBounds.right + 40f) return
            if (y !in range.minY..range.maxY) return
            val py = layerBounds.bottom - layerBounds.height * ((y - range.minY) / range.length).toFloat()
            val layout = textMeasurer.measure(label, style)
            // Put the label before the point when it would run past the right edge.
            val preferredLeft = if (px + 4f + layout.size.width <= layerBounds.right) px + 4f
            else px - 4f - layout.size.width
            val left = preferredLeft.coerceIn(layerBounds.left, maxOf(layerBounds.left, layerBounds.right - layout.size.width))
            val top = (py - layout.size.height / 2f).coerceIn(layerBounds.top, maxOf(layerBounds.top, layerBounds.bottom - layout.size.height))
            with(mutableDrawScope) {
                drawText(layout, topLeft = Offset(left, top))
            }
        }
    }
}

/**
 * A label at the plot's own bottom left (the G1 label, 2026-10-30): placed from the plot area on each draw, so the time
 * labels below the plot are never covered, whatever their height with the system font size.
 */
internal class PlotCornerLabelDecoration(
    private val label: String,
    private val style: TextStyle,
    private val textMeasurer: TextMeasurer,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val layout = textMeasurer.measure(label, style)
            val top = layerBounds.bottom - layout.size.height - 2f
            if (top < layerBounds.top) return
            with(mutableDrawScope) {
                drawText(layout, topLeft = Offset(layerBounds.left + 4f, top))
            }
        }
    }
}
