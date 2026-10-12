package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Null means no recent loop requirement; zero means no dose can be given now. */
fun followUpInsulinCap(candidate: Double, requirementMilliU: Long, calculatedAt: Long, now: Long, step: Double): Double? {
    if (calculatedAt <= 0L || calculatedAt > now || now - calculatedAt > 300_000L) return null
    val cap = min(max(0.0, candidate), max(0.0, requirementMilliU / 1000.0))
    return Round.roundTo(floor((cap + 1e-9) / step) * step, step)
}
