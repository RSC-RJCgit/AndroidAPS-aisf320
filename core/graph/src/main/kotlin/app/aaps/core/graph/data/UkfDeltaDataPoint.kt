package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: UKF-smoothed 5-minute delta, attached directly to the current
// UKF-smoothed graph point (at its actual x/y position) — the same curve drawn as the blue dashed
// rawBgSmoothedSeries line, so this label should always agree with which way that line is visually
// moving, unlike L1DeltaDataPoint's raw two-point slope which can briefly show the old direction near
// an inflection. yValue must be the current UKF-smoothed value (display units), not the raw/AAPS one,
// or the label lands away from the line it's meant to sit on.
class UkfDeltaDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 0L
    override val shape = Shape.UKF_DELTA_POINT
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.parseColor("#4FC3F7") // Material Light Blue 300, matches rawBgSmoothedSeries
}
