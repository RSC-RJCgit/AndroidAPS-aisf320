package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: "pp= acc= du=" — current ApsAutoIsfPpWeight/BgAccelWeight/DuraWeight.
// Now on the main graph (was graph3), positioned relative to its own yValue (current BG, display units)
// rather than a fixed panel-height fraction (Shape.PP_ACC_DU_ROW) -- see that shape's own comment for
// why. yValue/label are pre-formatted by the caller; yValue must be the current BG in display units or
// the row lands at the wrong height.
class IsfWeightsRowDataPoint(
    private val timestamp: Long,
    private val yValue: Double,
    override val label: String,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = yValue
    override fun setY(y: Double) {}

    override val duration = 0L
    override val shape = Shape.PP_ACC_DU_ROW
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
