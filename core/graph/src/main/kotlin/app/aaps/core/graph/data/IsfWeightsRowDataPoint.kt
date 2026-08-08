package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation point: "pp= acc= du=" — current ApsAutoIsfPpWeight/BgAccelWeight/DuraWeight.
// On graph5 (Shape.PP_ACC_DU_ROW). yValue must be a FIXED value near 4.0 mmol (display units), NOT the
// live current BG -- the caller passes a constant rather than latest.value. See that shape's own comment
// for why (graph5's basal bars occupy negative Y, so this can't just track the current BG point either).
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
