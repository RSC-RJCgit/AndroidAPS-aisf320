package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

class GlucoseValueDataPoint(
    val data: GV,
    private val profileUtil: ProfileUtil,
    private val rh: ResourceHelper,
    dateUtil: DateUtil,
    private val preferences: Preferences
) : DataPointWithLabelInterface {

    // Set externally (see PrepareBgDataWorker.kt) to the dominant ISF-weight color, same logic/colors
    // used for the SMB arrow override in PrepareTreatmentsDataWorker.kt. 0 = no override, use default color.
    var colorOverride: Int = 0
    override val hasColorOverride: Boolean get() = colorOverride != 0

    private fun valueToUnits(units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) data.value else data.value * Constants.MGDL_TO_MMOLL

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = valueToUnits(profileUtil.units)

    override fun setY(y: Double) {}
    override val label: String = dateUtil.timeString(data.timestamp) + " " + profileUtil.fromMgdlToStringInUnits(data.value)
    override val duration = 0L
    override val shape get() = if (isPrediction) Shape.PREDICTION else Shape.BG
    override val size = if (isPrediction) 1f else 0.6f
    // FILL (not just STROKE) when colorOverride is set, so the ISF-weight color is actually visible on
    // this small a dot instead of just tinting a thin outline. colorOverride is set externally after
    // construction (see PrepareBgDataWorker.kt), so this has to be a computed get(), not a val fixed
    // at construction time.
    override val paintStyle: Paint.Style get() = if (isPrediction || colorOverride != 0 || PointsWithLabelGraphSeries.uniformGreenBg) Paint.Style.FILL else Paint.Style.STROKE

    override fun color(context: Context?): Int {
        val units = profileUtil.units
        val lowLine = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highLine = preferences.get(UnitDoubleKey.OverviewHighMark)
        return when {
            isPrediction     -> predictionColor(context)
            PointsWithLabelGraphSeries.uniformGreenBg -> PointsWithLabelGraphSeries.uniformGreenBgColor
            // Low deliberately outranks colorOverride (unlike high, below) — hypo visibility on the graph
            // must never be masked by the ISF-weight dominant color, even while that color is active.
            valueToUnits(units) < lowLine  -> rh.gac(context, app.aaps.core.ui.R.attr.bgLow)
            colorOverride != 0 -> colorOverride
            // Restores low/high coloring dropped during an earlier core:graph refactor (it had collapsed
            // to a flat originalBgValueColor for every non-override point). High still yields to
            // colorOverride above, same as before — only genuinely unoverridden points reach this branch.
            valueToUnits(units) > highLine -> rh.gac(context, app.aaps.core.ui.R.attr.highColor)
            else             -> rh.gac(context, app.aaps.core.ui.R.attr.originalBgValueColor)
        }
    }

    private fun predictionColor(context: Context?): Int {
        return when (data.sourceSensor) {
            SourceSensor.IOB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.iobColor)
            SourceSensor.COB_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.A_COB_PREDICTION -> -0x7f000001 and rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
            SourceSensor.UAM_PREDICTION   -> rh.gac(context, app.aaps.core.ui.R.attr.uamColor)
            SourceSensor.ZT_PREDICTION    -> rh.gac(context, app.aaps.core.ui.R.attr.ztColor)
            else                          -> rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)
        }
    }

    private val isPrediction: Boolean
        get() = data.sourceSensor == SourceSensor.IOB_PREDICTION ||
            data.sourceSensor == SourceSensor.COB_PREDICTION ||
            data.sourceSensor == SourceSensor.A_COB_PREDICTION ||
            data.sourceSensor == SourceSensor.UAM_PREDICTION ||
            data.sourceSensor == SourceSensor.ZT_PREDICTION

}