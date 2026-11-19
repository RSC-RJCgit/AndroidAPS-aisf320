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

    // Set externally (see PrepareTreatmentsDataWorker.kt) to "<factor*10, rounded>" when a fast-rise
    // branch fired for this SMB in DetermineBasalAutoISF; empty otherwise.
    override var fastRiseLabel: String = ""

    // Set externally (see PrepareTreatmentsDataWorker.kt) from the "StepCut=" the loop wrote for this SMB
    // (DetermineBasalAutoISF, 2026-10-29): the insulin step counts took off it. 0 when none.
    var stepCut: Double = 0.0

    // Set externally (see PrepareTreatmentsDataWorker.kt, 2026-11-19, per explicit request): this SMB is a Tier 3 UAM boost
    // dose (a "UamBst" note within 1 min before to 3 min after it). Its value is then also drawn in the manual bolus row.
    var tier3: Boolean = false

    /** The value as the manual bolus row writes it ("1.05", not the SMB's ".95" style). */
    val bolusRowLabel: String get() = decimalFormatter.toPumpSupportedBolus(data.amount, bolusStep)

    // A protein/fat dose, from the note its row carries ("Delayed fpu2 of 4 dose", "Protein/fat fpu4 (last of 4) of ...:
    // cancelled", v4's "Protein and fat"), which a Client gets through Nightscout too (2026-11-18, per explicit request).
    override val isFpuDose: Boolean get() = isFpuNote(data.notes)

    companion object {

        private val FPU_NOTE = Regex("""\bfpu\d+|Protein/fat|Protein and fat""", RegexOption.IGNORE_CASE)

        fun isFpuNote(notes: String?): Boolean = notes != null && FPU_NOTE.containsMatchIn(notes)
    }

    override fun getX(): Double = data.timestamp.toDouble()
    override fun getY(): Double = if (data.type == BS.Type.SMB) preferences.get(UnitDoubleKey.OverviewLowMark) else yValue
    // A zero bolus is just "0" on the graphs (2026-10-27, per explicit request), not "0.00" (or ".00" for an SMB)
    override val label
        get() = if (data.amount == 0.0) "0"
        else if (data.type == BS.Type.SMB)
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

    // Same scale as shaftLengthMultiplier; 0 (no red arrow) under 0.01U.
    override val stepCutShaftMultiplier: Int
        get() = if (data.type == BS.Type.SMB && stepCut >= 0.01) 1 + (((stepCut - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0))
        else 0

    override fun color(context: Context?): Int =
        if (data.type == BS.Type.SMB && colorOverride != 0) colorOverride
        else if (data.type == BS.Type.SMB) rh.gac(context, app.aaps.core.ui.R.attr.smbColor)
        else if (data.isValid) rh.gac(context, app.aaps.core.ui.R.attr.bolusDataPointColor)
        else rh.gac(context, app.aaps.core.ui.R.attr.alarmColor)

    override fun setY(y: Double) {
        yValue = y
    }
}
