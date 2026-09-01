package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Translator

class TherapyEventDataPoint(
    val data: TE,
    private val rh: ResourceHelper,
    private val profileUtil: ProfileUtil,
    private val translator: Translator
) : DataPointWithLabelInterface {

    private var yValue = 0.0

    override fun getX(): Double = data.timestamp.toDouble()

    override fun getY(): Double {
        if (data.type == TE.Type.NS_MBG) return profileUtil.fromMgdlToUnits(data.glucose!!)
        if (data.glucose != null && data.glucose != 0.0) {
            val mgdl: Double = when (data.glucoseUnit) {
                GlucoseUnit.MGDL -> data.glucose!!
                GlucoseUnit.MMOL -> data.glucose!! * Constants.MMOLL_TO_MGDL
            }
            return profileUtil.fromMgdlToUnits(mgdl)
        }
        return yValue
    }

    override fun setY(y: Double) {
        yValue = y
    }

    override val label get() = if (data.note.isNullOrBlank().not()) data.note!! else translator.translate(data.type)
    override val duration get() = data.duration
    override val shape
        get() =
            when {
                data.type == TE.Type.NS_MBG                -> Shape.MBG
                data.type == TE.Type.FINGER_STICK_BG_VALUE -> Shape.BGCHECK
                data.type == TE.Type.ANNOUNCEMENT          -> Shape.ANNOUNCEMENT
                data.type == TE.Type.SETTINGS_EXPORT       -> Shape.SETTINGS_EXPORT
                data.type == TE.Type.EXERCISE              -> Shape.EXERCISE
                // All CarePortal notes use the graph4 stacked-text shape, even when duration is 0.
                // Coded-location notes (HmEnt/HmLve and the other arrival/exit tags) were inserted
                // without a duration, so they used to fall through to Shape.GENERAL, get Y-range
                // culled on graph4 (that panel isn't glucose-scaled), and vanish there while still
                // showing in Treatments. addCarePortalNote() already writes duration=1min (UamBst,
                // BMild, etc.) -- this also covers those older duration=0 rows already in the DB.
                data.type == TE.Type.NOTE                  -> Shape.GENERAL_WITH_DURATION
                duration > 0                               -> Shape.GENERAL_WITH_DURATION
                else                                       -> Shape.GENERAL
            }
    override val paintStyle: Paint.Style = Paint.Style.FILL // not used

    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    // System-change events (pump/cannula/sensor) back to the original faded grey (therapyEvent_Default,
    // #808080) — reverted from the flat-white unification below for everything else.
    override fun color(context: Context?): Int =
        when (data.type) {
            TE.Type.CANNULA_CHANGE, TE.Type.INSULIN_CHANGE, TE.Type.SENSOR_CHANGE, TE.Type.PUMP_BATTERY_CHANGE ->
                rh.gac(context, app.aaps.core.ui.R.attr.therapyEvent_Default)
            else -> Color.WHITE
        }
}