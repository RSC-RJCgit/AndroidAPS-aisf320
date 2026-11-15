package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Null means no recent loop requirement; zero means no dose can be given now. */
fun followUpInsulinCap(candidate: Double, requirementMilliU: Long, calculatedAt: Long, now: Long, step: Double): Double? {
    if (calculatedAt <= 0L || calculatedAt > now || now - calculatedAt > 300_000L) return null
    val cap = min(max(0.0, candidate), max(0.0, requirementMilliU / 1000.0))
    val roundedCap = Round.roundTo(floor((cap + 1e-9) / step) * step, step)
    if (roundedCap <= 0.0 || roundedCap >= candidate) return roundedCap

    // For delayed, split, and scheduled FPU follow-up doses, apply only half of the reduction
    // requested by insulinReq. Keep rounding to a pump-supported step and never exceed the plan.
    val halfReductionDose = candidate - (candidate - roundedCap) / 2.0
    return Round.roundTo(halfReductionDose, step).coerceAtMost(candidate)
}

/**
 * The requirement the delayed doses are capped by, in milli-units (2026-10-18): the loop's insulinReq less the SMB the
 * loop gives in the same cycle, so the dose and that SMB together never go above insulinReq. insulinReq already counts
 * all insulin on board (SMBs, temp basals, the bolus) and COB, which is why the delayed doses no longer subtract an
 * IOB rise of their own.
 */
fun followUpRequirementMilliU(insulinReq: Double, smbThisCycle: Double): Long =
    (max(0.0, insulinReq - max(0.0, smbThisCycle)) * 1000).toLong()
