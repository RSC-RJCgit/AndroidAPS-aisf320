package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// "DR=/AW=/LS=" row (SMB delivery ratio, acce ISF weight, Libre cal slope), split out of the steps
// row so the steps counts (Shape.STEPS_STACKED_BOTTOM) get their own line — this one renders fixed
// one line-height above it (see PointsWithLabelGraphSeries.draw(), Shape.STEPS_EXTRA_ROW).
class StepsExtraDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 60_000L // 1 minute; only needs to be > 0 to select a "with duration" shape
    override val shape = Shape.STEPS_EXTRA_ROW
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.GREEN
}
