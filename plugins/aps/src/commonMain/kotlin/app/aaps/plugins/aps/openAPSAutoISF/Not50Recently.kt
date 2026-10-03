package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Clears LowBG=50recent once the profile is back at 100% and glucose is rising through 5.5 mmol/L.
 * 99.1 mg/dL is 5.5 mmol/L. 1.8 mg/dL is 0.1 mmol/L.
 * The hypo-alarm time is left as it is. Only the state flag clears.
 */
internal fun not50RecentlyShouldFire(
    ready: Boolean,
    profilePercent: Int,
    lowBgRecent: Boolean,
    delta: Double,
    bg: Double,
): Boolean = ready && profilePercent == 100 && lowBgRecent && delta >= 1.8 && bg >= 99.1
