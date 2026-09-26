package app.aaps.plugins.aps.openAPSAutoISF

// 130% for 60 minutes around a pod change, from 10:00 to 18:00.
// The profile state must be PP130. A raw Libre reading over 12 mmol/L in 48 hours is required.
// Not for a virtual pump. Profile must already be 100%.
internal fun podChangeHighPp130ShouldFire(
    ready: Boolean,
    livePump: Boolean,
    profilePercent: Int,
    libreOver12Recent: Boolean,
    profilePp130: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    cannulaHours: Double?,
): Boolean {
    if (!ready || !livePump || profilePercent != 100 || !libreOver12Recent || !profilePp130) return false
    if (!minuteInWindow(minuteOfDay, 10 * 60, 18 * 60)) return false
    if (bg < 144.1 || delta < 3.6) return false
    val hours = cannulaHours ?: return false
    return hours <= 80.0 && (hours >= 78.0 || hours <= 2.0)
}

// Leaves a 110% or 130% profile. No temp target is enough. A falling glucose also leaves it while a target is on.
internal fun highPp130OffShouldFire(
    ready: Boolean,
    profilePercent: Int,
    bg: Double,
    delta: Double,
    ttActive: Boolean,
): Boolean {
    if (!ready) return false
    val boosted = profilePercent == 130 || profilePercent == 110
    if (!boosted) return false
    if (!ttActive) return true
    return bg <= 180.2 && delta <= -5.4
}

// 130% for 5 minutes and a 4.2 mmol target when the pod is under 6 hours or over 48 hours.
// Carbs are at least 16 g, insulin on board is at most 4 U, and the last meal bolus was at least 30 minutes ago.
internal fun recentPodShouldFire(
    ready: Boolean,
    livePump: Boolean,
    profilePercent: Int,
    ttActive: Boolean,
    libreOver12Recent: Boolean,
    bg: Double,
    delta: Double,
    cob: Double,
    iob: Double,
    minutesSinceBolus: Int,
    cannulaHours: Double?,
): Boolean {
    if (!ready || !livePump || profilePercent != 100 || ttActive || !libreOver12Recent) return false
    if (delta < 3.6 || cob < 16.0 || iob > 4.0 || bg < 108.0 || minutesSinceBolus < 30) return false
    val hours = cannulaHours ?: return false
    return hours <= 6.0 || hours >= 48.0
}
