package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Remembers the last time the phone accelerometer moved.
 * The Android listener writes the time. Other targets never move, so walking stays neutral.
 */
internal object PhoneMotion {
    private var lastMovedAtMs: Long = 0L

    fun markMoved(nowMs: Long) {
        lastMovedAtMs = nowMs
    }

    fun movedRecently(nowMs: Long): Boolean = lastMovedAtMs + 15 * 60_000L > nowMs
}

/**
 * Step activity ratio from 3.2.1. Below 1.0 means walking, so the loop gives less insulin.
 * Above 1.0 means sitting still, so the loop gives more insulin. 1.0 changes nothing.
 * Missing steps stay at 1.0. A missing watch sample is not the same as sitting still.
 */
internal fun activitySensitivityRatio(
    enabled: Boolean,
    tempTargetSet: Boolean,
    phoneMoved: Boolean,
    stepsKnown: Boolean,
    steps5: Int,
    steps10: Int,
    steps15: Int,
    steps30: Int,
    steps60: Int,
    hour: Int,
    bg: Double,
    targetBg: Double,
    shortDelta: Double,
    minutesSinceStart: Long,
    sleeping: Boolean,
    sleepStateExists: Boolean,
    ignoreInactivityOvernight: Boolean,
    idleStartHour: Int,
    idleEndHour: Int,
    activityScale: Double,
    inactivityScale: Double,
): Double {
    if (!enabled || tempTargetSet || !stepsKnown) return 1.0
    val overnight = (idleStartHour > idleEndHour && (hour >= idleStartHour || hour < idleEndHour)) ||
        (hour >= idleStartHour && hour < idleEndHour)
    if (minutesSinceStart < 60L && steps60 <= 200) return 1.0
    if (sleeping && steps60 <= 200) return 1.0
    if (overnight && steps60 <= 200 && ignoreInactivityOvernight && !sleepStateExists) return 1.0
    if (phoneMoved && (steps5 > 300 || steps10 > 300 || steps15 > 300 || steps30 > 1500 || steps60 > 2500)) {
        return 1.0 - 0.3 * activityScale
    }
    if (phoneMoved && (steps5 > 200 || steps10 > 200 || steps15 > 200 || steps30 > 500 || steps60 > 800)) {
        return 1.0 - 0.15 * activityScale
    }
    if (bg < targetBg && steps60 <= 200) return 1.0
    if (steps60 < 50 && shortDelta >= -0.02 * 18.0) return 1.0 + 0.2 * inactivityScale
    if (steps60 <= 200 && shortDelta >= -0.02 * 18.0) return 1.0 + 0.1 * inactivityScale
    return 1.0
}

/**
 * Picks the ratio that divides the profile sensitivity.
 * A temp target wins, then a real step change, then autosens, then the TDD blend when autosens is off.
 */
internal fun loopSensitivityRatio(
    tempTargetActive: Boolean,
    tempTargetRatio: Double,
    activityRatio: Double,
    autosensOn: Boolean,
    autosensRatio: Double,
    tddSensitivityOn: Boolean,
    tddRatio: Double?,
): Double {
    val stepsChanged = activityRatio < 1.0 || activityRatio > 1.0
    if (tempTargetActive || stepsChanged) {
        if (tempTargetActive) return tempTargetRatio
        return activityRatio
    }
    if (autosensOn) return autosensRatio
    if (!tddSensitivityOn) return 1.0
    return tddRatio ?: 1.0
}
