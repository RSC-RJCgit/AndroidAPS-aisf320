package app.aaps.plugins.aps.openAPSAutoISF

// Daytime 120% for 5 minutes, and a high pp weight. Profile must already be 100%.
// A raw Libre reading over 12 mmol/L in the last 48 hours is required.
// No temp target does not count as a low target.
internal fun high6ppShouldFire(
    ready: Boolean,
    profilePercent: Int,
    minuteOfDay: Int,
    libreOver12Recent: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    ttLowMgdl: Double?,
    cob: Double,
): Boolean {
    if (!ready || profilePercent != 100 || !libreOver12Recent) return false
    if (!minuteInWindow(minuteOfDay, 9 * 60, 21 * 60)) return false
    val tt = ttLowMgdl ?: Double.MAX_VALUE
    val branch1 = bg >= 144.1 && tt <= 77.5 && delta >= 1.8 && delta <= 5.4
    val branch2 = bg >= 126.1 && tt <= 75.7 &&
        minuteInWindow(minuteOfDay, 10 * 60, 21 * 60) &&
        longDelta >= 0.0 && shortDelta >= 0.0 && shortDelta <= 5.4 &&
        delta >= 0.0 && delta <= 5.4
    val branch3 = bg >= 108.1 && cob >= 10.0 && tt <= 75.7 && delta >= 1.8 && delta <= 5.4
    return branch1 || branch2 || branch3
}

// Leaves a 120% profile. Either there is no temp target, or the evening glucose has settled.
internal fun high6ppOffShouldFire(
    ready: Boolean,
    profilePercent: Int,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    ttActive: Boolean,
): Boolean {
    if (!ready || profilePercent != 120) return false
    val noTarget = !ttActive
    val evening = minuteInWindow(minuteOfDay, 21 * 60, 0) && bg <= 144.1 && delta <= 3.6
    return noTarget || evening
}

// A fresh pod (6 hours or less) or a stale one (60 hours or more), glucose at least 10 mmol/L
// and still rising, no meal bolus for 90 minutes, and no MJ left. Profile must be 100%.
internal fun highOldPodShouldFire(
    ready: Boolean,
    profilePercent: Int,
    ttActive: Boolean,
    noMjRemains: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    minutesSinceBolus: Int,
    cannulaHours: Double?,
): Boolean {
    if (!ready || profilePercent != 100 || ttActive || !noMjRemains) return false
    if (bg < 180.2 || delta !in 1.8..5.4 || shortDelta < 1.8 || longDelta < 0.0) return false
    if (minutesSinceBolus < 90) return false
    val hours = cannulaHours ?: return false
    return hours >= 60.0 || hours <= 6.0
}
