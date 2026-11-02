package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round

/** Allocate pump steps across the series without rounding every small part up independently. */
fun warsawPartUnits(total: Double, parts: Int, index: Int, step: Double): Double {
    if (!total.isFinite() || total <= 0 || parts <= 0 || index !in 1..parts || !step.isFinite() || step <= 0) return 0.0
    val before = Round.floorTo(total * (index - 1) / parts + step * 1e-9, step)
    val after = Round.floorTo(total * index / parts + step * 1e-9, step)
    return Round.roundTo((after - before).coerceAtLeast(0.0), step)
}
