package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: current 5-min and 30-min step counts against their own rolling max
// (Step5max = max Steps5min over the last 30 min; Step30max = max Steps30min over the last 2 hours).
// label holds both lines separated by "\n" (see PointsWithLabelGraphSeries.draw(), Shape.STEPS_STACKED_BOTTOM).
//
// yValue is a valid in-range BG value purely so the point isn't culled as "overdraw" before shape-specific
// drawing runs (same reasoning as NoisyBgDeltaDataPoint) — the shape ignores it for on-screen placement.
class StepsStackedDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 60_000L // 1 minute; only needs to be > 0 to select a "with duration" shape
    override val shape = Shape.STEPS_STACKED_BOTTOM
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
