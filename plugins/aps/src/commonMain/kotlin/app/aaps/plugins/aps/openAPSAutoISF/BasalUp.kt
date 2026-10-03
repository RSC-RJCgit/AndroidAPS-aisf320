package app.aaps.plugins.aps.openAPSAutoISF

// True from [startMinute] until [endMinute]. An end that is earlier than the start wraps past midnight.
internal fun minuteInWindow(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
    return if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
    else minuteOfDay >= startMinute || minuteOfDay < endMinute
}

// BasalUp. Glucose is stable or rising, outside a fresh-pod and high-step window,
// on a Low-family profile at 100%, from 07:00 until midnight.
// A pod age of 6 hours or less, or 72 hours or more, can open it. So can MJ3, no MJ left, or 12:00-18:00.
// Steps over 30 minutes above 600 still close it. Steps over 60 minutes above 1000 close it too,
// unless the shorter windows have already dropped (5 min at 0, 15 min at 30 or less, 30 min at 200 or less).
internal fun basalUpShouldFire(
    ready: Boolean,
    bg: Double,
    delta: Double,
    profilePercent: Int,
    minuteOfDay: Int,
    steps5: Int,
    steps15: Int,
    steps60: Int,
    steps30: Int,
    podHours: Double?,
    onLowFamily: Boolean,
    mj3: Boolean,
    noMjRemains: Boolean,
): Boolean {
    if (!ready || !onLowFamily || profilePercent != 100) return false
    if (bg < 81.1 || delta < 3.6) return false
    if (basalUpStepBrake(steps5, steps15, steps30, steps60)) return false
    if (!minuteInWindow(minuteOfDay, 7 * 60, 0)) return false
    val podOk = podHours != null && (podHours >= 72.0 || podHours <= 6.0)
    val afternoon = minuteInWindow(minuteOfDay, 12 * 60, 18 * 60)
    return podOk || noMjRemains || mj3 || afternoon
}

/** True when the step count should keep BasalUp closed. A quiet recent window ignores the 60 minute count. */
internal fun basalUpStepBrake(steps5: Int, steps15: Int, steps30: Int, steps60: Int): Boolean {
    if (steps30 > 600) return true
    val recentDropped = steps5 <= 0 && steps15 <= 30 && steps30 <= 200
    if (recentDropped) return false
    return steps60 > 1000
}
