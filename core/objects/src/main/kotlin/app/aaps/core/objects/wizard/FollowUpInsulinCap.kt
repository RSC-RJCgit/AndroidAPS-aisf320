package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Null means no recent loop requirement; zero means no dose can be given now.
 * [halfReduction] false (2026-11-20, per explicit request, protein/fat parts): the full insulinReq cap, not half the gap.
 */
fun followUpInsulinCap(candidate: Double, requirementMilliU: Long, calculatedAt: Long, now: Long, step: Double, halfReduction: Boolean = true): Double? {
    if (calculatedAt <= 0L || calculatedAt > now || now - calculatedAt > 300_000L) return null
    val cap = min(max(0.0, candidate), max(0.0, requirementMilliU / 1000.0))
    val roundedCap = Round.roundTo(floor((cap + 1e-9) / step) * step, step)
    if (roundedCap <= 0.0 || roundedCap >= candidate || !halfReduction) return roundedCap

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
/**
 * A protein/fat part waits while IOB rose more than this, in units, in the last 15 minutes (2026-11-20, per explicit request).
 * On 20 Nov part 7 of 8 went at 20:08 after IOB rose 0.11 -> 2.04 U in 16 min (SMBs and temp basal on an acce spike), and
 * BG fell to 4.4 later. Of ~60 earlier parts (29 Oct - 20 Nov) none went after a rise over 1.15 U.
 */
const val FPU_IOB_RISE_HOLD_U = 1.2

fun fpuIobRiseHolds(iobNow: Double, iob15MinAgo: Double): Boolean = iobNow - iob15MinAgo > FPU_IOB_RISE_HOLD_U

fun followUpRequirementMilliU(insulinReq: Double, smbThisCycle: Double): Long =
    (max(0.0, insulinReq - max(0.0, smbThisCycle)) * 1000).toLong()
