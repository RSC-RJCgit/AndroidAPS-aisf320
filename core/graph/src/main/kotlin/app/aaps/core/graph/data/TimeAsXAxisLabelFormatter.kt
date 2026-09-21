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
    private val staggerHorizontal: Boolean = false
) : DefaultLabelFormatter() {

    override fun formatLabel(value: Double, isValueX: Boolean): String =
        if (isValueX) {
            val dateFormat: DateFormat = SimpleDateFormat(format, Locale.getDefault())
            val time = dateFormat.format(value.toLong())
            if (!staggerHorizontal || numHorizontalLabels < 2 || endTime <= fromTime) time
            else {
                val span = (endTime - fromTime).toDouble()
                val idx = ((value - fromTime) / span * (numHorizontalLabels - 1))
                    .roundToInt().coerceIn(0, numHorizontalLabels - 1)
                // GraphView already splits on '\n'. Even ticks on the upper row, odd on the lower,
                // so HH:mm labels do not collide on a 6h/7-tick axis.
                if (idx % 2 == 0) "$time\n " else "\n$time"
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
}
