package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.roundToInt

// MJ4 and MJ5 are one-shot test states. Each one returns MJ to NOMJremains.
// MJ5 also switches to Standard for 30 minutes. The caller does that.
internal fun mj4ShouldClear(ready: Boolean, mj4: Boolean): Boolean = ready && mj4

internal fun mj5ShouldClear(ready: Boolean, mj5: Boolean): Boolean = ready && mj5

// Leaves MJ3. Block 1 is 12:00 until 21:04 when glucose is at least 10.5 mmol.
// Block 2 is midnight until 01:00 with no glucose check. Returns the block name, or null.
internal fun mjOffBlock(ready: Boolean, mj3: Boolean, minuteOfDay: Int, bg: Double): String? {
    if (!ready || !mj3) return null
    val daytime = minuteInWindow(minuteOfDay, 12 * 60, 21 * 60 + 4) && bg >= 189.2
    val midnight = minuteInWindow(minuteOfDay, 0, 60)
    return when {
        daytime -> "1"
        midnight -> "2"
        else -> null
    }
}

// Advances MJ from "MJ active" to MJ2 between 02:10 and 03:10.
internal fun mj2OldShouldFire(ready: Boolean, mjActive: Boolean, minuteOfDay: Int): Boolean =
    ready && mjActive && minuteInWindow(minuteOfDay, 2 * 60 + 10, 3 * 60 + 10)

// Advances MJ from MJ2 to MJ3 between 01:05 and 02:05.
internal fun mj3OldShouldFire(ready: Boolean, mj2: Boolean, minuteOfDay: Int): Boolean =
    ready && mj2 && minuteInWindow(minuteOfDay, 1 * 60 + 5, 2 * 60 + 5)

// True when the low-profile switch should run. The accel weight and IOB threshold still change
// when this is false, as long as [mjRecentShouldTune] is true.
internal fun mjRecentShouldSwitchLow(
    tune: Boolean,
    minuteOfDay: Int,
    hp: Double?,
    rescueActive: Boolean,
): Boolean = tune && !rescueActive && (minuteInWindow(minuteOfDay, 22 * 60, 6 * 60) || (hp != null && hp < 5.0))

// Night window 00:00 until 08:00, profile 100%, no temp target, MJ cycle on, cannula under 72 hours.
internal fun mjRecentShouldTune(
    ready: Boolean,
    steroidsOff: Boolean,
    mjCycleOn: Boolean,
    ttActive: Boolean,
    profilePercent: Int,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    cannulaHours: Double?,
): Boolean {
    if (!ready || !steroidsOff || !mjCycleOn || ttActive || profilePercent != 100) return false
    if (!minuteInWindow(minuteOfDay, 0, 8 * 60)) return false
    val hours = cannulaHours ?: return false
    if (hours >= 72.0) return false
    return bg <= 144.1 || delta <= 1.8
}

// Returns "MJ2" for the hypo-alarm path, "MJ3" when there has been no raw high in 48 hours.
// The hypo path wins when both match. Closed from midnight until 06:00.
// [mjOffReady] is false for 65 minutes after MJoff.
internal fun moreMjTarget(
    ready: Boolean,
    mjOffReady: Boolean,
    minuteOfDay: Int,
    acceWeight: Double,
    steps180: Int,
    steps60: Int,
    noMjRemains: Boolean,
    bg: Double,
    profilePercent: Int,
    cob: Double,
    minutesSinceBolus: Int,
    alarmRecent: Boolean,
    iob: Double,
    noRecentHigh: Boolean,
): String? {
    if (!ready || !mjOffReady || !minuteInWindow(minuteOfDay, 6 * 60, 24 * 60)) return null
    val hypo = acceWeight <= 0.11 &&
        steps180 <= 400 &&
        steps60 <= 200 &&
        noMjRemains &&
        minuteInWindow(minuteOfDay, 10 * 60 + 30, 22 * 60) &&
        bg < 99.1 &&
        profilePercent == 50 &&
        cob <= 0.0 &&
        minutesSinceBolus > 210 &&
        alarmRecent &&
        iob >= 0.2
    if (hypo) return "MJ2"
    if (noRecentHigh && noMjRemains) return "MJ3"
    return null
}

// The next Standard/Low letter, or null when the morning step does not run.
// [hp] is null when the raw 5 minute change is missing. High needs glucose above 5.0 for 8 hours.
// Normal needs glucose between 4.5 and 7.0 for 8 hours, and an MJ cycle that is still on.
internal fun morningRoleIndex(
    ready: Boolean,
    steroidsOff: Boolean,
    minuteOfDay: Int,
    hp: Double?,
    mjKnown: Boolean,
    noMjRemains: Boolean,
    recentBgHigh: Boolean,
    recentBgNormal: Boolean,
    sourceIndex: Int,
    ladderSize: Int,
): Int? {
    if (!ready || !steroidsOff || !minuteInWindow(minuteOfDay, 3 * 60, 7 * 60)) return null
    val high = hp != null && hp > 6.0 && mjKnown && noMjRemains && recentBgHigh
    val normal = hp != null && hp < 5.0 && mjKnown && !noMjRemains && recentBgNormal
    if (!high && !normal) return null
    val index = if (sourceIndex < 0) {
        if (high) 0 else -1
    } else {
        ladderStepIndex(sourceIndex, ladderSize, high)
    }
    return index.takeIf { it >= 0 }
}

// Eight hours of glucose stay inside the bounds. A gap over 2 hours, a stale newest reading,
// or less than 60% of the minutes present keeps this closed. Bounds are exclusive.
internal fun recentBgStaysInRange(
    now: Long,
    hours: Int,
    samples: List<Pair<Long, Double>>,
    minMgdl: Double?,
    maxMgdl: Double?,
): Boolean {
    if (hours <= 0) return false
    val windowStart = now - hours * 3_600_000L
    val sorted = samples.filter { it.first in windowStart..now }.sortedBy { it.first }
    if (sorted.isEmpty()) return false
    val coveredMinutes = sorted.map { it.first / 60_000L }.distinct().count()
    if (coveredMinutes < (hours * 60 * 0.60).roundToInt()) return false
    if (sorted.last().first < now - 10 * 60_000L) return false
    if (sorted.first().first - windowStart > 120 * 60_000L) return false
    if (sorted.zipWithNext().any { (older, newer) -> newer.first - older.first > 120 * 60_000L }) return false
    return sorted.all { (_, value) ->
        (minMgdl == null || value > minMgdl) && (maxMgdl == null || value < maxMgdl)
    }
}
