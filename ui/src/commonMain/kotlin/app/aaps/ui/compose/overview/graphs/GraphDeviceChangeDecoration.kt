package app.aaps.ui.compose.overview.graphs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.overview.graph.DeviceChange
import app.aaps.core.interfaces.overview.graph.TherapyEventGraphPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/*
 * Pod and sensor changes on the BG graph (2026-11-01, per explicit request): a dashed line at the change with "POD" or
 * "SEN" at the top, as the 3426 graph marks them. They used to show only as clipped text on the notes graph, and as
 * icons on the treatment belt, which this fork removed on 2026-09-26, so on the BG graph they did not show at all.
 */

/** Which mark a therapy event gets: a pod (site) change, or a sensor change or start. Null for every other event. */
internal fun deviceChangeOf(type: TE.Type): DeviceChange? = when (type) {
    TE.Type.CANNULA_CHANGE                            -> DeviceChange.POD
    TE.Type.SENSOR_CHANGE, TE.Type.SENSOR_STARTED     -> DeviceChange.SENSOR
    else                                              -> null
}

/** One mark on the BG graph. */
internal data class DeviceChangeMark(val timestamp: Long, val change: DeviceChange)

/**
 * The marks for [events], oldest first. One change is often recorded twice - a sensor change and a sensor start, or the
 * same change from both Nightscout sites - so a mark of the same kind within [MERGE_MS] of the one before is left out.
 */
internal fun deviceChangeMarks(events: List<TherapyEventGraphPoint>): List<DeviceChangeMark> {
    val marks = mutableListOf<DeviceChangeMark>()
    for (event in events.sortedBy { it.timestamp }) {
        val change = event.deviceChange ?: continue
        val previous = marks.lastOrNull { it.change == change }
        if (previous != null && event.timestamp - previous.timestamp < MERGE_MS) continue
        marks += DeviceChangeMark(event.timestamp, change)
    }
    return marks
}

private const val MERGE_MS = 30L * 60 * 1000

internal class GraphDeviceChangeDecoration(
    private val minTimestamp: Long,
    private val marks: List<DeviceChangeMark>,
    private val color: Color,
    private val textMeasurer: TextMeasurer
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            if (ranges.xStep == 0.0) return
            for (mark in marks) {
                val x = (mark.timestamp - minTimestamp) / 60_000.0
                val canvasX = layerBounds.left + layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat() - scroll
                if (canvasX < layerBounds.left || canvasX > layerBounds.right) continue
                with(mutableDrawScope) {
                    drawLine(
                        color = color,
                        start = Offset(canvasX, layerBounds.top),
                        end = Offset(canvasX, layerBounds.bottom),
                        strokeWidth = density,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * density, 3 * density))
                    )
                    val layout = textMeasurer.measure(mark.change.label, TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    // Right of the line, or left of it where the right edge is too close
                    val gap = 2 * density
                    val left = if (canvasX + gap + layout.size.width <= layerBounds.right) canvasX + gap else canvasX - gap - layout.size.width
                    drawText(layout, topLeft = Offset(left, layerBounds.top))
                }
            }
        }
    }
}

@Composable
internal fun rememberDeviceChangeDecoration(minTimestamp: Long, events: List<TherapyEventGraphPoint>): GraphDeviceChangeDecoration {
    val marks = remember(events) { deviceChangeMarks(events) }
    val color = MaterialTheme.colorScheme.tertiary
    val textMeasurer = rememberTextMeasurer()
    return remember(minTimestamp, marks, color, textMeasurer) { GraphDeviceChangeDecoration(minTimestamp, marks, color, textMeasurer) }
}
