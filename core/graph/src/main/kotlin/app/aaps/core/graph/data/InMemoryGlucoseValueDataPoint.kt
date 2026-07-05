package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

class InMemoryGlucoseValueDataPoint(
    val data: InMemoryGlucoseValue,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    // Set externally (see PrepareBucketedDataWorker.kt) to the dominant ISF-weight color, same logic/colors
    // used for the SMB arrow and GlucoseValueDataPoint overrides. 0 = no override, use default range color.
    var colorOverride: Int = 0
    override val hasColorOverride: Boolean get() = colorOverride != 0

    private fun valueToUnits(units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) data.recalculated else data.recalculated * Constants.MGDL_TO_MMOLL

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = valueToUnits(profileFunction.getUnits())
    override fun setY(y: Double) {}
    override val label: String = ""
    override val duration = 0L
    override val shape = Shape.BUCKETED_BG
    override val size = 0.6f // matches GlucoseValueDataPoint's BG dot size — was 1f, overwhelming/blurring the BG line
    override val paintStyle: Paint.Style = Paint.Style.FILL

    @ColorInt
    override fun color(context: Context?): Int {
        val units = profileFunction.getUnits()
        val lowLine = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highLine = preferences.get(UnitDoubleKey.OverviewHighMark)
        val color = when {
            PointsWithLabelGraphSeries.uniformGreenBg -> PointsWithLabelGraphSeries.uniformGreenBgColor
            colorOverride != 0              -> colorOverride
            valueToUnits(units) < lowLine  -> rh.gac(context, app.aaps.core.ui.R.attr.bgLow)
            valueToUnits(units) > highLine -> rh.gac(context, app.aaps.core.ui.R.attr.highColor)
            else                           -> rh.gac(context, app.aaps.core.ui.R.attr.bgInRange)
        }
        return if (data.filledGap) ColorUtils.setAlphaComponent(color, 128) else color
    }

}