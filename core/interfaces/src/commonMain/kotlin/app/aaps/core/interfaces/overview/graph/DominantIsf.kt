package app.aaps.core.interfaces.overview.graph

import kotlin.math.abs

/**
 * Which AutoISF factor moved the ISF the most.
 * [NONE] means every factor stayed within 0.01 of 1.0.
 */
enum class DominantIsf {
    NONE,
    ACCE,
    BG,
    PP,
    DURA
}

/** A glucose point or temp basal uses the nearest AutoISF row only when it is closer than this. */
const val DOMINANT_ISF_WINDOW_MS = 15L * 60L * 1000L

/**
 * Largest absolute distance from 1.0.
 * A tie goes to acce, then bg, then pp, then dura.
 * A largest distance of 0.01 or less is [DominantIsf.NONE].
 */
fun dominantIsf(acce: Double, bg: Double, pp: Double, dura: Double): DominantIsf {
    val acceDev = abs(acce - 1.0)
    val bgDev = abs(bg - 1.0)
    val ppDev = abs(pp - 1.0)
    val duraDev = abs(dura - 1.0)
    val maxDev = maxOf(acceDev, bgDev, ppDev, duraDev)
    if (maxDev <= 0.01) return DominantIsf.NONE
    return when {
        acceDev >= maxDev -> DominantIsf.ACCE
        bgDev >= maxDev   -> DominantIsf.BG
        ppDev >= maxDev   -> DominantIsf.PP
        else              -> DominantIsf.DURA
    }
}

/**
 * The dominant factor of the row closest to [timestamp].
 * [DominantIsf.NONE] when [rows] is empty or the closest row is [windowMs] or further away.
 */
fun <T> dominantIsfAt(
    timestamp: Long,
    rows: List<T>,
    timeOf: (T) -> Long,
    acceOf: (T) -> Double,
    bgOf: (T) -> Double,
    ppOf: (T) -> Double,
    duraOf: (T) -> Double,
    windowMs: Long = DOMINANT_ISF_WINDOW_MS
): DominantIsf {
    if (rows.isEmpty()) return DominantIsf.NONE
    val nearest = rows.minBy { abs(timeOf(it) - timestamp) }
    if (abs(timeOf(nearest) - timestamp) >= windowMs) return DominantIsf.NONE
    return dominantIsf(acceOf(nearest), bgOf(nearest), ppOf(nearest), duraOf(nearest))
}
