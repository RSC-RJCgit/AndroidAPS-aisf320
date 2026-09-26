package app.aaps.plugins.aps.openAPSAutoISF

// True when [series] (oldest first) stays under [maxMgdl] for [sustainedMinutes]
// with no gap over [maxGapMinutes], and no step sample in that stretch is over [maxSteps60].
internal fun sustainedLowEpisode(
    series: List<Pair<Long, Double>>,
    steps60At: List<Pair<Long, Int>>,
    sustainedMinutes: Int,
    maxMgdl: Double,
    maxSteps60: Int,
    maxGapMinutes: Int = 5,
): Boolean {
    if (series.size < 2) return false
    val needMs = sustainedMinutes * 60_000L
    val maxGapMs = maxGapMinutes * 60_000L
    var streakStart = -1L
    var previous = -1L
    fun qualifies(end: Long): Boolean {
        if (streakStart < 0L || end - streakStart < needMs) return false
        return steps60At.none { it.first in streakStart..end && it.second > maxSteps60 }
    }
    for ((ts, mgdl) in series) {
        if (previous >= 0L && ts - previous > maxGapMs) {
            if (qualifies(previous)) return true
            streakStart = -1L
        }
        if (mgdl < maxMgdl) {
            if (streakStart < 0L) streakStart = ts
        } else {
            if (qualifies(previous)) return true
            streakStart = -1L
        }
        previous = ts
    }
    return previous >= 0L && qualifies(previous)
}

internal enum class ProfileBatchSlot { LOW_A, LOW_B, LOW_C, STD_A, STD_B, STD_C, OTHER }

// The running profile's place on the Low then Standard ladder. A name that is not on its ladder
// uses the shared letter. Anything else is OTHER.
internal fun profileBatchSlot(
    running: String,
    lowRole: String,
    standardRole: String,
    lowIndex: Int,
    standardIndex: Int,
    sharedIndex: Int,
): ProfileBatchSlot {
    if (running == lowRole) {
        return when (if (lowIndex >= 0) lowIndex else sharedIndex) {
            0 -> ProfileBatchSlot.LOW_A
            1 -> ProfileBatchSlot.LOW_B
            else -> ProfileBatchSlot.LOW_C
        }
    }
    if (running == standardRole) {
        return when (if (standardIndex >= 0) standardIndex else sharedIndex) {
            0 -> ProfileBatchSlot.STD_A
            1 -> ProfileBatchSlot.STD_B
            else -> ProfileBatchSlot.STD_C
        }
    }
    return ProfileBatchSlot.OTHER
}

// One step. [thenLow] means switch the running profile to Low after the rung write.
// [thenStandard] means switch it to Standard, and the rung write itself must not switch.
internal data class BatchStep(val index: Int, val switchRunning: Boolean, val thenLow: Boolean, val thenStandard: Boolean)

internal fun profileBatchStep(slot: ProfileBatchSlot, up: Boolean): BatchStep? = if (up) when (slot) {
    ProfileBatchSlot.LOW_A -> BatchStep(1, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.LOW_B -> BatchStep(2, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.LOW_C -> BatchStep(0, switchRunning = false, thenLow = false, thenStandard = true)
    ProfileBatchSlot.STD_A -> BatchStep(1, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.STD_B -> BatchStep(2, switchRunning = true, thenLow = false, thenStandard = false)
    else -> null
} else when (slot) {
    ProfileBatchSlot.STD_C -> BatchStep(1, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.STD_B -> BatchStep(0, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.STD_A -> BatchStep(2, switchRunning = true, thenLow = true, thenStandard = false)
    ProfileBatchSlot.LOW_C -> BatchStep(1, switchRunning = true, thenLow = false, thenStandard = false)
    ProfileBatchSlot.LOW_B -> BatchStep(0, switchRunning = true, thenLow = false, thenStandard = false)
    else -> null
}

internal enum class BatchChoice { NONE, BOTH_HOLDS, HOLD_A, HOLD_C, STEP_DOWN, STEP_UP }

// Hold A and hold C together do nothing. A down step wins over an up step.
// A poor-response mark, 2 hours over 12.0 mmol, or 2 hours of UKF raw over 14.0 mmol steps up.
// A gentle-hypo mark, or two morning role changes, steps down.
internal fun profileBatchChoice(
    holdA: Boolean,
    holdC: Boolean,
    autoOn: Boolean,
    bgl12For2h: Boolean,
    ukf14For2h: Boolean,
    poorResponseRecent: Boolean,
    gentleHypoRecent: Boolean,
    morningStreak: Int,
): BatchChoice = when {
    holdA && holdC -> BatchChoice.BOTH_HOLDS
    holdA -> BatchChoice.HOLD_A
    holdC -> BatchChoice.HOLD_C
    !autoOn -> BatchChoice.NONE
    gentleHypoRecent || morningStreak >= 2 -> BatchChoice.STEP_DOWN
    poorResponseRecent || bgl12For2h || ukf14For2h -> BatchChoice.STEP_UP
    else -> BatchChoice.NONE
}

internal fun heldSince(now: Long, high: Boolean, previous: Long): Long =
    if (!high) 0L else if (previous == 0L) now else previous

internal fun heldForHours(now: Long, since: Long, hours: Int): Boolean =
    since != 0L && now - since >= hours * 3_600_000L

internal fun battery1ShouldFire(
    ready: Boolean,
    running: String,
    safetyName: String,
    batteryPercent: Int,
    livePump: Boolean,
): Boolean = ready && livePump && safetyName.isNotBlank() && running != safetyName && batteryPercent <= 1

internal fun missingProfileRoles(standardFound: Boolean, lowFound: Boolean, safetyFound: Boolean): List<String> =
    listOfNotNull(
        if (standardFound) null else "Standard",
        if (lowFound) null else "Low",
        if (safetyFound) null else "Safety",
    )
