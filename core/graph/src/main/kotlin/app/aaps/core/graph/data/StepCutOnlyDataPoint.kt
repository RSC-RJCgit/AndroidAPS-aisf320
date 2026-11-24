package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * The red steps arrow alone (2026-11-24, per explicit request), at a loop result where step counts cut the SMB to 0: its reason
 * has "StepCutOnly=<U>" (DetermineBasalAutoISF). Drawn on the baseline like the steps arrow under an SMB, same shaft scale
 * (see BolusDataPoint.stepCutShaftMultiplier). Built in PrepareTreatmentsDataWorker.
 */
class StepCutOnlyDataPoint(
    private val timestamp: Long,
    private val stepCut: Double,
    private val preferences: Preferences
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = preferences.get(UnitDoubleKey.OverviewLowMark)
    override fun setY(y: Double) {}
    override val label = ""
    override val duration = 0L
    override val shape = Shape.STEP_CUT_ONLY
    override val size = 2f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override val stepCutShaftMultiplier: Int
        get() = if (stepCut >= 0.01) 1 + (((stepCut - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0)) else 0

    override fun color(context: Context?): Int = android.graphics.Color.rgb(255, 32, 32)
}
