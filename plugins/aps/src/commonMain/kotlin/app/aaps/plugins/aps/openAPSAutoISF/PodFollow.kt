package app.aaps.plugins.aps.openAPSAutoISF

// Puts the acceleration weight back to normal after a RecentPod or OldPod2 boost, once the temp target is gone.
// OldPod2 no longer runs. Its name is still checked so an older run can be cleared.
internal fun recentPodOffShouldFire(
    ready: Boolean,
    acceWeight: Double,
    acceHigh: Double,
    ttActive: Boolean,
    podBoostRecent: Boolean,
): Boolean {
    if (!ready || ttActive || !podBoostRecent) return false
    return weightNear(acceWeight, acceHigh)
}

// Disabled. The 130% boost was too strong. The run-mark name is kept for RecentPodOff.
internal fun oldPod2ShouldFire(): Boolean = false

// POD at 78.0 to 78.1 hours, from 08:00 until 23:59. Not for a virtual pump.
internal fun pod2ShouldFire(
    ready: Boolean,
    livePump: Boolean,
    cannulaHours: Double?,
    minuteOfDay: Int,
): Boolean {
    if (!ready || !livePump) return false
    val hours = cannulaHours ?: return false
    if (hours < 78.0 || hours > 78.1) return false
    return minuteInWindow(minuteOfDay, 8 * 60, 23 * 60 + 59)
}

// POD at 79.0 to 79.05 hours, from 07:00 until 23:59. Not for a virtual pump.
internal fun pod1ShouldFire(
    ready: Boolean,
    livePump: Boolean,
    cannulaHours: Double?,
    minuteOfDay: Int,
): Boolean {
    if (!ready || !livePump) return false
    val hours = cannulaHours ?: return false
    if (hours < 79.0 || hours > 79.05) return false
    return minuteInWindow(minuteOfDay, 7 * 60, 23 * 60 + 59)
}

// Drops the IOB threshold to 12% on a quiet morning rise, from 05:30 until 08:30.
// Steroids must be off. The last meal bolus was at least 3 hours ago. Carbs on board are zero.
internal fun shower12ShouldFire(
    ready: Boolean,
    profilePercent: Int,
    iobThPercent: Int,
    steroidsOff: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    steps60: Int,
    cob: Double,
    minutesSinceBolus: Int,
    ttLowMgdl: Double?,
): Boolean {
    if (!ready || profilePercent <= 50 || iobThPercent <= 12 || !steroidsOff) return false
    if (!minuteInWindow(minuteOfDay, 5 * 60 + 30, 8 * 60 + 30)) return false
    if (bg > 144.1 || delta < 6.3 || shortDelta < 4.5) return false
    if (steps60 >= 10 || cob != 0.0 || minutesSinceBolus < 180) return false
    return ttLowMgdl == null || ttLowMgdl <= 127.9
}

// True when a Shower12 mark is 15 minutes to 4 hours old. That opens Usual2's early morning path.
internal fun shower12OpensUsual2(minutesAgo: Int?): Boolean {
    val age = minutesAgo ?: return false
    return age in 15..240
}

// Bolus2 is off. UKF disabled it on 2026-09-06. The conditions stay here so the gate can be tested.
// A fast fall just after a bolus, or carbs with MJ active, would have set 6.8 mmol for 5 minutes.
internal fun bolus2ShouldFire(
    enabled: Boolean,
    ready: Boolean,
    ttActive: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    iob: Double,
    cob: Double,
    minutesSinceBolus: Int,
    mjActive: Boolean,
): Boolean {
    if (!enabled || !ready || ttActive) return false
    val falling = minutesSinceBolus <= 5 &&
        shortDelta <= -3.6 &&
        delta <= -5.4 &&
        bg <= 126.1 &&
        iob >= 0.5 &&
        cob >= 9.0
    val afterMeal = minutesSinceBolus <= 15 && mjActive && cob >= 4.0 && bg <= 126.1
    return falling || afterMeal
}
