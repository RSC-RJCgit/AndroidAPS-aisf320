package app.aaps.core.objects.wizard

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

/**
 * Delayed bolus, from the 3426 app's DelayedBolusWorker (ported 2026-11-14, per explicit request).
 *
 * After a calculator or QuickWizard bolus on a 50% profile, or with the carb insulin halved for a recent low, the carb insulin
 * that was held back is given later, once BG rises. BG is checked every [POLL_MINUTES] minutes, [MAX_ATTEMPTS] times (5 to
 * 80 minutes). A check passes with fresh BG over 5.0 mmol/L, delta and short delta over +0.10 and long delta over 0. The dose
 * is what is still held back, times the share of the carbs still on board, cut while walking, and capped by the loop's
 * insulinReq. When insulinReq is the limit, the rest waits for the next check.
 *
 * Differences from the 3426 app, on purpose:
 *  - What was held back is the carb insulin only: the carbs at the Standard profile's carb ratio (at 100%), less the carb
 *    insulin given now, both at the calculator's percent. The 3426 app took a "full need" of (carbs / IC + bolus IOB) less
 *    the whole dose given, and that ADDED the bolus IOB (held as a positive number), about 2 x IOB too much. Here IOB, BG,
 *    trend and COB stay with the bolus given now, where the calculator already counted them.
 *  - The part over max bolus is not in it: that is the carb split's (or nobody's at 50%, as for every split in this app).
 *  - Walking cuts the dose as for the other later doses here ([delayedDoseWalkingFactor]), not the 3426 app's flat 80%.
 *  - The walking-soon and falling-BG holds are not triggers: this app already gives those later (split leftover, give rest
 *    later).
 */
object DelayedBolus {

    const val POLL_MINUTES = 5
    const val MAX_ATTEMPTS = 16
    /** How long ActivityOff treats a bolus as part of a delayed bolus wait (the 3426 app's SMB-block window). */
    const val PENDING_MINUTES = 85L

    private const val MGDL_PER_MMOL = 18.0182
    const val GATE_BG_MGDL = 5.0 * MGDL_PER_MMOL
    const val GATE_DELTA_MGDL = 0.1 * MGDL_PER_MMOL
    const val GATE_SHORT_DELTA_MGDL = 0.1 * MGDL_PER_MMOL
    const val GATE_LONG_DELTA_MGDL = 0.0
    const val GATE_MAX_AGE_MS = 5 * 60_000L

    /**
     * Carb insulin held back now, in units. [carbs] at [standardIc] less [carbInsulinNow] (the calculator's own carb insulin,
     * already on the 50% profile's carb ratio or halved for the recent low), both at [percent]. Never under 0.
     */
    fun heldUnits(carbs: Int, standardIc: Double, carbInsulinNow: Double, percent: Double): Double {
        if (carbs <= 0 || standardIc <= 0.0) return 0.0
        return max(0.0, (carbs / standardIc - carbInsulinNow) * percent / 100.0)
    }

    /** True when a check may give insulin: fresh BG over 5.0 mmol/L and all three deltas rising. */
    fun gateOk(glucoseMgdl: Double?, deltaMgdl: Double?, shortDeltaMgdl: Double?, longDeltaMgdl: Double?, ageMs: Long?): Boolean {
        if (glucoseMgdl == null || deltaMgdl == null || shortDeltaMgdl == null || longDeltaMgdl == null || ageMs == null) return false
        return ageMs in 0..GATE_MAX_AGE_MS &&
            glucoseMgdl > GATE_BG_MGDL &&
            deltaMgdl > GATE_DELTA_MGDL &&
            shortDeltaMgdl > GATE_SHORT_DELTA_MGDL &&
            longDeltaMgdl > GATE_LONG_DELTA_MGDL
    }

    /** Share of the carbs still on board, 0..1. No carbs entered gives 1. */
    fun cobFraction(cobNow: Double, carbs: Int): Double = if (carbs > 0) (cobNow / carbs).coerceIn(0.0, 1.0) else 1.0

    /** What a passing check would give before walking and the insulinReq cap: what is left, scaled by the carbs left. */
    fun checkDose(heldUnits: Double, deliveredSoFar: Double, cobFraction: Double): Double =
        max(0.0, heldUnits - deliveredSoFar) * cobFraction

    /** Minutes since the bolus for check [attempt] (1-based): the "Db" number in the care portal notes. */
    fun checkMinutes(attempt: Int): Int = attempt * POLL_MINUTES
}

/** What the calculator held back for a [DelayedBolus], carried from the confirm to the commit. */
data class DelayedBolusPlan(
    val heldBy: DelayedHeldBy,
    val heldUnits: Double,
    val carbs: Int,
    /** The carb ratio the held part was worked out with, and whether it is the Standard profile's (else the fallback). */
    val ic: Double,
    val fromStandardProfile: Boolean,
)

/** Why part of the carb insulin was held back. [label] is the 3426 app's trigger name, kept for the logs. */
enum class DelayedHeldBy(val label: String, val text: String) {
    PROFILE_50("profile=50%", "the 50% profile"),
    RECENT_LOW("recent50Triggered", "the recent low BG rule"),
}

/** A newer delayed bolus replaces the one waiting, as the 3426 app's single WorkManager job did. */
@OptIn(ExperimentalAtomicApi::class)
internal object DelayedScheduleGate {

    private val generation = AtomicLong(0)

    fun next(): Long = generation.addAndFetch(1)

    fun isCurrent(token: Long): Boolean = generation.load() == token
}
