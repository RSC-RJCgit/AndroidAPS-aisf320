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
    override val labelY: Double get() = if (data.type == BS.Type.SMB) yValue else preferences.get(UnitDoubleKey.OverviewLowMark)
    override val duration = 0L
    override val size = 2f
    override val paintStyle: Paint.Style = Paint.Style.FILL // not used
    override val shape
        get() = if (data.type == BS.Type.SMB) Shape.SMB else Shape.BOLUS

    override val hasColorOverride: Boolean get() = colorOverride != 0

    // 1 shaft length at the 0.05U baseline, +1 for every further 0.05U (0.05->1, 0.10->2, 0.20->4, ...).
    // +0.001 epsilon guards against float drift (e.g. 0.15 - 0.05 landing a hair under 0.10).
    override val shaftLengthMultiplier: Int
        get() = if (data.type == BS.Type.SMB) 1 + (((data.amount - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0))
        else 1

    override fun color(context: Context?): Int =
        if (data.type == BS.Type.SMB && colorOverride != 0) colorOverride
        else if (data.type == BS.Type.SMB) rh.gac(context, app.aaps.core.ui.R.attr.smbColor)
        else if (data.isValid) rh.gac(context, app.aaps.core.ui.R.attr.bolusDataPointColor)
        else rh.gac(context, app.aaps.core.ui.R.attr.alarmColor)

    override fun setY(y: Double) {
        yValue = y
    }
}
