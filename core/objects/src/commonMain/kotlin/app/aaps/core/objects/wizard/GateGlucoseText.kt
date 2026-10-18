package app.aaps.core.objects.wizard

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import kotlin.math.abs
import kotlin.math.round

/**
 * BG and deltas for the delayed-dose notes, in the user's units (2026-10-18, per explicit request). Inputs are mg/dL.
 * mmol: BG with 1 decimal, deltas with 2. mg/dL: BG as a whole number, deltas with 1 decimal. A missing value is "--".
 */
fun gateGlucoseText(glucoseMgdl: Double?, deltaMgdl: Double?, shortDeltaMgdl: Double?, units: GlucoseUnit): String {
    val mmol = units == GlucoseUnit.MMOL
    fun bg(v: Double?) = v?.let { if (mmol) fixedDecimals(it * Constants.MGDL_TO_MMOLL, 1) else fixedDecimals(it, 0) } ?: "--"
    fun delta(v: Double?) = v?.let { if (mmol) fixedDecimals(it * Constants.MGDL_TO_MMOLL, 2) else fixedDecimals(it, 1) } ?: "--"
    val unit = if (mmol) "mmol/L" else "mg/dL"
    return "BG=${bg(glucoseMgdl)}, delta=${delta(deltaMgdl)}, short delta=${delta(shortDeltaMgdl)} $unit"
}

/** [value] rounded to [decimals] places, always shown with that many places (0.10, not 0.1). */
internal fun fixedDecimals(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = round(abs(value) * scale).toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    return "$sign${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
}
