package app.aaps.implementation.notifications

/** Battery samples older than this are not used. */
internal const val BATTERY_FALL_WINDOW_MS = 30 * 60_000L

/** After an alarm, stay quiet this long. */
internal const val BATTERY_FALL_QUIET_MS = 60 * 60_000L

/**
 * Phone battery alarm (iOS, 2026-10-18): the battery is under 50% and at least 5% lower than its highest level in the last
 * 30 min. Returns how many percent it fell, or null when no alarm is due. Quiet for 60 min after an alarm.
 *
 * [samples] are (time ms, level %) in time order, newest last.
 */
internal fun batteryFallAlarmDue(samples: List<Pair<Long, Int>>, now: Long, lastAlarmAt: Long): Int? {
    val current = samples.lastOrNull()?.second ?: return null
    if (current >= 50 || now - lastAlarmAt < BATTERY_FALL_QUIET_MS) return null
    val highest = samples.filter { now - it.first <= BATTERY_FALL_WINDOW_MS }.maxOfOrNull { it.second } ?: return null
    val fall = highest - current
    return if (fall >= 5) fall else null
}
