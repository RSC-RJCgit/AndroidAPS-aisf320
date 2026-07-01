package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

class BolusDataPoint(
    val data: BS,
    private val rh: ResourceHelper,
    private val bolusStep: Double,
    private val preferences: Preferences,
    private val decimalFormatter: DecimalFormatter
) : DataPointWithLabelInterface {

    private var yValue = 0.0
    var colorOverride: Int = 0

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = if (data.type == BS.Type.SMB) preferences.get(UnitDoubleKey.OverviewLowMark) else yValue
    override val label
        get() = if (data.type == BS.Type.SMB)
            decimalFormatter.toPumpSupportedBolus(data.amount, bolusStep).trimStart('0')
        else
            decimalFormatter.toPumpSupportedBolus(data.amount, bolusStep)
    override val labelY: Double get() = yValue  // for SMBs: label floats at BG line, triangle stays at baseline
    override val duration = 0L
    override val size = 2f
    override val paintStyle: Paint.Style = Paint.Style.FILL // not used
    override val shape
        get() = if (data.type == BS.Type.SMB) Shape.SMB else Shape.BOLUS

    override val hasColorOverride: Boolean get() = colorOverride != 0

    override fun color(context: Context?): Int =
        if (data.type == BS.Type.SMB && colorOverride != 0) colorOverride
        else if (data.type == BS.Type.SMB) rh.gac(context, app.aaps.core.ui.R.attr.smbColor)
        else if (data.isValid) rh.gac(context, app.aaps.core.ui.R.attr.bolusDataPointColor)
        else rh.gac(context, app.aaps.core.ui.R.attr.alarmColor)

    override fun setY(y: Double) {
        yValue = y
    }
}
