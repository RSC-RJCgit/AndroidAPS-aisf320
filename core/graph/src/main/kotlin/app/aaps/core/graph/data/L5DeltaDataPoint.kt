package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: Libre 5-minute delta only, attached directly to the current Libre
// graph point (at its actual x/y position) rather than a fixed row, so it stands out right where the
// eye already is, in white. yValue must be the same current BG value (display units) as the actual
// plotted glucose point, or the label lands at the wrong height.
class L5DeltaDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 0L
    override val shape = Shape.L5_DELTA_POINT
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
