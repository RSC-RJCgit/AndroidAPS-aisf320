package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class SplitBolusBudget(val remaining: Double, val dose: Double, val creditedIob: Double)

/** IOB credited against a pending split is removed from its budget, not delayed for later delivery. */
fun splitBolusBudget(remaining: Double, previousPart: Double, baselineIob: Double, liveIob: Double, step: Double): SplitBolusBudget {
    val rise = max(0.0, liveIob - baselineIob)
    val credit = min(remaining, rise)
    val reducedRemaining = Round.roundTo(max(0.0, remaining - credit), 0.001)
    val pumpCap = Round.roundTo(floor((reducedRemaining + 1e-9) / step) * step, step)
    val dose = Round.roundTo(max(0.0, min(previousPart - rise, reducedRemaining)), step)
        .coerceAtMost(pumpCap)
    return SplitBolusBudget(reducedRemaining, dose, credit)
}
