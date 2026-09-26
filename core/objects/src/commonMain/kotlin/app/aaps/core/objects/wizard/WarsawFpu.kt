package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.objects.profile.ProfileSealed
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.floor
import kotlin.math.max

/**
 * Protein and fat insulin, spread as one dose per hour.
 *
 * The immediate wizard bolus does not include this insulin. Fat-protein units pick a length:
 * up to 1 gives 3 hours, up to 2 gives 4 hours, up to 3 gives 5 hours, and above 3 gives 8 hours.
 * Each dose is the total divided by that full length. On the 8 hour length, [durationHoursCap]
 * only drops the later hours. It does not make the earlier doses bigger.
 */
data class WarsawFpuPlan(
    val fpu: Double,
    val durationMinutes: Int,
    val numDoses: Int,
    val perDoseInsulin: Double,
    val totalInsulin: Double,
    val fullTierInsulin: Double,
    val capped: Boolean,
)

fun warsawFpuPlan(proteinGrams: Int, fatGrams: Int, ic: Double, durationHoursCap: Double = 5.0): WarsawFpuPlan? {
    if (ic <= 0.0) return null
    val totalRequiredInsulin = (proteinGrams * 0.4 + fatGrams * 0.9) / ic
    if (totalRequiredInsulin <= 0.0) return null
    val fpu = (9.0 * fatGrams + 4.0 * proteinGrams) / 100.0
    val tierHours = when {
        fpu <= 1.0 -> 3
        fpu <= 2.0 -> 4
        fpu <= 3.0 -> 5
        else       -> 8
    }
    val perDoseInsulin = totalRequiredInsulin / tierHours
    val numDoses = if (tierHours == 8) floor(durationHoursCap.coerceIn(0.0, 8.0)).toInt() else tierHours
    if (numDoses <= 0) return null
    return WarsawFpuPlan(
        fpu = fpu,
        durationMinutes = numDoses * 60,
        numDoses = numDoses,
        perDoseInsulin = perDoseInsulin,
        totalInsulin = perDoseInsulin * numDoses,
        fullTierInsulin = perDoseInsulin * tierHours,
        capped = numDoses < tierHours,
    )
}

/** Hour 1 is the first delay. A single dose waits the whole length. */
fun warsawDoseDelayMinutes(index: Int, numDoses: Int, durationMinutes: Int): Int {
    if (numDoses <= 1) return durationMinutes.coerceAtLeast(1)
    return (durationMinutes.toLong() * index / numDoses).toInt()
}

fun profileSwitchPercent(profile: Profile): Int =
    if (profile is ProfileSealed.EPS) profile.value.originalPercentage else profile.percentage

/** Live glucose must be at least 7.0 mmol, and neither delta may be falling by 0.05 mmol or more. */
fun warsawDoseBgAllows(glucoseMgdl: Double?, delta: Double?, shortDelta: Double?): Boolean {
    if (glucoseMgdl == null || delta == null || shortDelta == null) return false
    return glucoseMgdl >= 126.1 && delta > -0.90 && shortDelta > -0.90
}

/** Subtract any insulin-on-board rise since the immediate bolus. A result of 0 or less is not delivered. */
fun warsawDoseAfterIobRise(planned: Double, iobBaseline: Double, liveIob: Double, bolusStep: Double): Double =
    Round.roundTo(planned - max(0.0, liveIob - iobBaseline), bolusStep)

/**
 * A new wizard confirm replaces an older protein/fat series. The doses live only in this process.
 * If the app is killed, the remaining doses are not delivered.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object WarsawScheduleGate {

    private val generation = AtomicLong(0)

    fun next(): Long = generation.addAndFetch(1)

    fun isCurrent(token: Long): Boolean = generation.load() == token
}
