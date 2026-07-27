package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: noisy/raw BG plus AAPS and Libre 1-minute deltas.
// yValue/label are pre-formatted (mmol, 1dp for the BG value, 2dp for deltas) by the caller.
//
// shape = GENERAL_WITH_DURATION_OFFSET: same fixed "top of graph" style as CarePortal notes with a
// duration, but drawn lower (see PointsWithLabelGraphSeries.draw()) so it doesn't overlap a CarePortal
// note rendered at the same time. Both shapes ignore the data point's Y value for on-screen text
// placement; yValue is still used for graph height/scale calculations elsewhere, so it must stay
// within the visible BG range or the point gets culled as "overdraw" before shape-specific drawing runs.
class NoisyBgDeltaDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 60_000L // 1 minute; only needs to be > 0 to select a "with duration" shape
    override val shape = Shape.GENERAL_WITH_DURATION_OFFSET
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.YELLOW
}
