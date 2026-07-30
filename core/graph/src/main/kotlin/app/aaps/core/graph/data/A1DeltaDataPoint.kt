package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: AAPS (smoothed) 1-minute delta only, attached directly to the current
// smoothed BG graph point (at its actual x/y position) rather than a fixed row, so it stands out right
// where the eye already is, in orange — same style as L1DeltaDataPoint's raw-Libre counterpart, but
// anchored to the smoothed/plotted BG value (yValue) rather than the raw noise value, since the two
// lines usually sit at slightly different heights and would otherwise overlap.
class A1DeltaDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 0L
    override val shape = Shape.A1_DELTA_POINT
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.rgb(255, 165, 0) // orange
}
