package app.aaps.core.graph.data

import com.jjoe64.graphview.DefaultLabelFormatter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Created by mike on 09.06.2016.
 */
class TimeAsXAxisLabelFormatter(
    private val format: String,
    private val fromTime: Long = 0L,
    private val endTime: Long = 0L,
    private val numHorizontalLabels: Int = 0,
    private val hideTickIndices: Set<Int> = emptySet()
) : DefaultLabelFormatter() {

    override fun formatLabel(value: Double, isValueX: Boolean): String =
        if (isValueX) {
            val dateFormat: DateFormat = SimpleDateFormat(format, Locale.getDefault())
            val time = dateFormat.format(roundToDisplayGranularity(value.toLong()))
            if (hideTickIndices.isEmpty() || numHorizontalLabels < 2 || endTime <= fromTime) time
            else {
                val span = (endTime - fromTime).toDouble()
                val idx = ((value - fromTime) / span * (numHorizontalLabels - 1))
                    .roundToInt().coerceIn(0, numHorizontalLabels - 1)
                if (idx in hideTickIndices) "" else time
            }
        } else {
            try {
                // unknown reason for crashing on this
                //                Fatal Exception: java.lang.NullPointerException
                //                Attempt to invoke virtual method 'double com.jjoe64.graphview.Viewport.getMaxY(boolean)' on a null object reference
                //                com.jjoe64.graphview.DefaultLabelFormatter.formatLabel (DefaultLabelFormatter.java:89)
                //                app.aaps.interfaces.graph.data.TimeAsXAxisLabelFormatter.formatLabel (TimeAsXAxisLabelFormatter.java:26)
                //                com.jjoe64.graphview.GridLabelRenderer.drawVerticalSteps (GridLabelRenderer.java:1057)
                //                com.jjoe64.graphview.GridLabelRenderer.draw (GridLabelRenderer.java:866)
                //                com.jjoe64.graphview.GraphView.onDraw (GraphView.java:296)
                super.formatLabel(value, false)
            } catch (ignored: Exception) {
                ""
            }
        }

    // Tick positions are evenly spaced across the window, not aligned to a clock boundary, so a
    // 6h window can land a label on e.g. 09:18. Round the displayed text (not the tick's X
    // position) to the nearest 15min on short windows, or the nearest hour on longer ones.
    private fun roundToDisplayGranularity(millis: Long): Long {
        val granularity = if (endTime > fromTime && endTime - fromTime <= SIX_HOURS_MS) FIFTEEN_MIN_MS else ONE_HOUR_MS
        return Math.round(millis / granularity.toDouble()) * granularity
    }

    companion object {

        private const val FIFTEEN_MIN_MS = 15 * 60 * 1000L
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
        private const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
    }
}
