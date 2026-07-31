package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.ColorInt
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

// Raw/Libre line (gv.noise), replacing the old flat-red LineGraphSeries — GraphView's line series can't
// vary color per point, so this reuses the same per-point dot rendering as the ISF-weight overlay
// (InMemoryGlucoseValueDataPoint) instead. Stays red at all other values, INCLUDING clean-graph
// (uniformGreenBg) mode — unlike the main BG dots, this line deliberately does not go green there. Only
// turns yellow (bgLow) when below the low line.
class RawBgDataPoint(
    private val timestamp: Long,
    private val valueMgdl: Double,
    private val preferences: Preferences,
    private val profileUtil: ProfileUtil,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    private fun valueToUnits(units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) valueMgdl else valueMgdl * Constants.MGDL_TO_MMOLL

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = valueToUnits(profileUtil.units)
    override fun setY(y: Double) {}
    override val label: String = ""
    override val duration = 0L
    override val shape = Shape.BUCKETED_BG
    override val size = 0.6f
    override val paintStyle: Paint.Style = Paint.Style.FILL

    @ColorInt
    override fun color(context: Context?): Int {
        val units = profileUtil.units
        val lowLine = preferences.get(UnitDoubleKey.OverviewLowMark)
        return if (valueToUnits(units) < lowLine) rh.gac(context, app.aaps.core.ui.R.attr.bgLow) else Color.RED
    }
}
