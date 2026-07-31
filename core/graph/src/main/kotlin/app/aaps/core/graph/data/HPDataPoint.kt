package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: "hypoprection= <value>" row, fixed near the bottom of the MAIN graph
// (same nearBottomPy positioning as StepsStackedDataPoint/Shape.STEPS_STACKED_BOTTOM, but on the main
// graph's own viewport, near the basal-column area — see PointsWithLabelGraphSeries.draw(),
// Shape.HP_ROW_BOTTOM). yValue is a valid in-range BG value purely so the point isn't culled as
// "overdraw" before shape-specific drawing runs — the shape ignores it for on-screen placement.
class HPDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 60_000L // 1 minute; only needs to be > 0 to select a "with duration" shape
    override val shape = Shape.HP_ROW_BOTTOM
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
