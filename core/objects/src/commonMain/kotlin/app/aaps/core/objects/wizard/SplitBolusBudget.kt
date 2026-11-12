package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class SplitBolusBudget(val remaining: Double, val dose: Double)

/**
 * The next carb split part: the size of the part before it, never more than what is left, rounded down to the pump step.
 * Since 2026-10-18 (per explicit request) no IOB rise is taken off: the part is capped by the loop's insulinReq (less the
 * same-cycle SMB), which already counts all insulin on board, so taking an IOB rise off as well counted it twice.
 */
fun splitBolusBudget(remaining: Double, previousPart: Double, step: Double): SplitBolusBudget {
    val left = Round.roundTo(max(0.0, remaining), 0.001)
    val pumpCap = Round.roundTo(floor((left + 1e-9) / step) * step, step)
    val dose = Round.roundTo(max(0.0, min(previousPart, left)), step).coerceAtMost(pumpCap)
    return SplitBolusBudget(left, dose)
}
