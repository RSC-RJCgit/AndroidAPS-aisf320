package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.utils.Round
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min

/** Minutes between leftover parts. Same default UKF3426 used for the split interval. */
const val SPLIT_LEFTOVER_INTERVAL_MINUTES = 7

/**
 * Insulin still owed after the immediate bolus was cut to max bolus.
 * Null when the remainder is under half a pump step, or when nothing was delivered to start the series.
 */
fun splitLeftover(requested: Double, delivered: Double, bolusStep: Double): Double? {
    if (delivered <= 0.0) return null
    val residual = Round.roundTo(requested - delivered, 0.001)
    if (residual < bolusStep / 2.0) return null
    return residual
}

/** Next part: the previous part, reduced by any insulin-on-board rise, and never more than what is left. */
fun splitNextDose(
    previousPart: Double,
    remaining: Double,
    iobBaseline: Double,
    liveIob: Double,
    bolusStep: Double,
): Double {
    val rise = max(0.0, liveIob - iobBaseline)
    return Round.roundTo(min(previousPart - rise, remaining), bolusStep).coerceAtLeast(0.0)
}

enum class SplitBgCheck { Missing, Unsafe, Allowed }

/**
 * Below 6.0 mmol is unsafe. From 6.0 to 8.0 mmol, a fall of 0.05 mmol is also unsafe.
 * At 8.0 mmol or above, a fall on its own is allowed.
 */
fun splitBgCheck(glucoseMgdl: Double?, delta: Double?, shortDelta: Double?): SplitBgCheck {
    if (glucoseMgdl == null || delta == null || shortDelta == null) return SplitBgCheck.Missing
    val unsafe = glucoseMgdl < 108.1 ||
        (glucoseMgdl < 144.1 && (delta <= -0.90 || shortDelta <= -0.90))
    return if (unsafe) SplitBgCheck.Unsafe else SplitBgCheck.Allowed
}

/**
 * A new bolus replaces an older leftover series. The parts live only in this process.
 * If the app is killed, the remaining parts are not delivered.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object SplitScheduleGate {

    private val generation = AtomicLong(0)

    fun next(): Long = generation.addAndFetch(1)

    fun isCurrent(token: Long): Boolean = generation.load() == token
}
