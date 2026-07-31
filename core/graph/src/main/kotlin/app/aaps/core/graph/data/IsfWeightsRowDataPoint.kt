package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: "pp= acc= du=" — current ApsAutoIsfPpWeight/BgAccelWeight/DuraWeight,
// fixed near the bottom of graph3, same style/mechanism as NoisyBgDeltaDataPoint's "L=/A1=.../MJ" row on
// graph1 (Shape.GENERAL_WITH_DURATION_OFFSET, drawn at greenLinePy on whichever graph this series is
// added to). yValue/label are pre-formatted by the caller.
class IsfWeightsRowDataPoint(
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
    override fun color(context: Context?): Int = Color.WHITE
}
