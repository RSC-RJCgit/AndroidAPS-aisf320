package app.aaps.ui.compose.overview.graphs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** How far before a bolus its calculation may lie: the calculation is saved on OK, the bolus when the pump confirms it. */
internal const val CALCULATION_BEFORE_BOLUS_MS = 10 * 60_000L

/** How far after a bolus its calculation may lie (clock rounding, records that came in from Nightscout). */
internal const val CALCULATION_AFTER_BOLUS_MS = 60_000L

/**
 * Calculations and treatments have no shared ID. The calculation is saved when OK is pressed and the bolus when the
 * pump confirms it, seconds later (more for a client, a split dose or a past time), so the two times rarely match.
 * Pick from the calculations made 10 min before to 1 min after the bolus: an exact time first, otherwise the closest
 * in time whose total is at least the bolus, otherwise the closest in time. [calculations] are (timestamp, total insulin).
 *
 * 2026-10-22: it was "closest insulin first". A dose cut by max bolus (2.50 U of a 3.75 U calculation) then matched a
 * cancelled calculation of 3.70 U made 10 min earlier. A cut dose is never more than its own calculation.
 */
internal fun matchingCalculationTimestamp(bolus: Long, bolusAmount: Double, calculations: List<Pair<Long, Double>>): Long? {
    val inWindow = calculations.filter { it.first in (bolus - CALCULATION_BEFORE_BOLUS_MS)..(bolus + CALCULATION_AFTER_BOLUS_MS) }
    inWindow.firstOrNull { it.first == bolus }?.let { return it.first }
    val nearest = compareBy<Pair<Long, Double>> { abs(it.first - bolus) }
    return (inWindow.filter { it.second >= bolusAmount - 0.01 }.minWithOrNull(nearest) ?: inWindow.minWithOrNull(nearest))?.first
}

/** [smb]: an SMB dose label, arrow or baseline triangle; a tap shows the dose (2026-10-21, per explicit request). */
internal data class GraphTapHit(val bounds: Rect, val bg: BgDataPoint? = null, val bolus: BolusGraphPoint? = null, val smb: BolusGraphPoint? = null)

/** Updated during drawing, so hit testing uses the current scroll, zoom and axis bounds. */
internal class GraphTapTargets {
    @Volatile var points: List<GraphTapHit> = emptyList()
    @Volatile var labels: List<GraphTapHit> = emptyList()
    @Volatile var smbLabels: List<GraphTapHit> = emptyList()

    fun at(position: Offset): GraphTapHit? {
        fun nearest(hits: List<GraphTapHit>) = hits.filter { position in it.bounds }
            .minByOrNull { (it.bounds.center - position).getDistanceSquared() }
        return nearest(labels + smbLabels) ?: nearest(points.filter { it.bolus != null || it.smb != null }) ?: nearest(points.filter { it.bg != null })
    }
}

internal class GraphPointTapReporter(
    private val targets: GraphTapTargets,
    private val readings: List<BgDataPoint>,
    private val bolusReadings: List<BgDataPoint>,
    private val boluses: List<BolusGraphPoint>,
    private val minTimestamp: Long,
    /** SMBs: a tap on the arrow under the BG dot, or on the baseline triangle at [lowMark], shows the dose. */
    private val smbs: List<BolusGraphPoint> = emptyList(),
    private val lowMark: Double? = null,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        targets.points = emptyList()
        with(context) {
            val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
            if (ranges.xStep == 0.0 || yRange.length == 0.0) return
            fun position(timestamp: Long, value: Double): Offset = Offset(
                layerBounds.left + layerDimensions.startPadding + layerDimensions.xSpacing *
                    ((timestampToX(timestamp, minTimestamp) - ranges.minX) / ranges.xStep).toFloat() - scroll,
                layerBounds.bottom - layerBounds.height * ((value - yRange.minY) / yRange.length).toFloat(),
            )
            val radius = with(mutableDrawScope) { 12.dp.toPx() }
            val triangleHeight = with(mutableDrawScope) { 24.dp.toPx() }
            targets.points = buildList {
                for (reading in readings) {
                    val point = position(reading.timestamp, reading.value)
                    if (point in layerBounds) add(GraphTapHit(Rect(point.x - radius, point.y - radius, point.x + radius, point.y + radius), bg = reading))
                }
                for (bolus in boluses) {
                    val reading = bolusReadings.minByOrNull { abs(it.timestamp - bolus.timestamp) } ?: continue
                    val point = position(bolus.timestamp, reading.value)
                    if (point in layerBounds) add(GraphTapHit(Rect(point.x - radius, point.y, point.x + radius, point.y + triangleHeight), bolus = bolus))
                }
                for (smb in smbs) {
                    val reading = bolusReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
                    if (reading != null) {
                        val point = position(smb.timestamp, reading.value)
                        if (point in layerBounds) add(GraphTapHit(Rect(point.x - radius, point.y, point.x + radius, point.y + triangleHeight), smb = smb))
                    }
                    if (lowMark != null) {
                        // The baseline triangle: its tip is on the low mark and its stem runs down by the dose
                        val tip = position(smb.timestamp, lowMark)
                        if (tip.x in layerBounds.left..layerBounds.right)
                            add(GraphTapHit(Rect(tip.x - radius, tip.y - radius, tip.x + radius, tip.y + triangleHeight * 2), smb = smb))
                    }
                }
            }
        }
    }
}
