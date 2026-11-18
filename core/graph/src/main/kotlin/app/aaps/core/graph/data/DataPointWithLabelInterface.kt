package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import androidx.annotation.ColorInt
import com.jjoe64.graphview.series.DataPointInterface

interface DataPointWithLabelInterface : DataPointInterface {

    override fun getX(): Double
    override fun getY(): Double
    fun setY(y: Double)

    val label: String
    val labelY: Double get() = getY()  // override to place label at a different y than the data point
    val duration: Long
    val shape: Shape
    val size: Float
    val paintStyle: Paint.Style
    @ColorInt fun color(context: Context?): Int
    val hasColorOverride: Boolean get() = false

    // Shape.SMB tail length multiplier: 1 = baseline. Only BolusDataPoint overrides this (per-0.05U-over-
    // 0.05U-baseline dose scaling); everything else keeps the default fixed length.
    val shaftLengthMultiplier: Int get() = 1

    // Shape.SMB fast-rise indicator: empty = no fast-rise branch fired for this dose. Only BolusDataPoint
    // overrides this, drawn at the bottom of the baseline arrowhead's shaft when non-empty.
    val fastRiseLabel: String get() = ""

    // Shape.SMB steps arrow (2026-10-29): 0 = none. Otherwise the shaft length, in the same units as
    // shaftLengthMultiplier, of a red arrow mirrored tip to tip on the baseline arrowhead: the insulin step
    // counts took off this SMB. Only BolusDataPoint overrides this.
    val stepCutShaftMultiplier: Int get() = 0

    // Shape.BOLUS only: true for a protein/fat (FPU) dose, given or 0 U — its triangle and value are drawn yellow, every
    // other bolus (meal, carb split part, delayed bolus) magenta (2026-11-18, per explicit request). Only BolusDataPoint
    // overrides this.
    val isFpuDose: Boolean get() = false
}