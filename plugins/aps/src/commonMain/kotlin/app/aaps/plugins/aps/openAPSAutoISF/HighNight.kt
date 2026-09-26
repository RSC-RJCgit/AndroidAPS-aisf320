package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Overnight rise that should use the Standard profile.
 * The window is 02:00 until 07:00. 117.1 mg/dL is 6.5 mmol/L. 1.8 mg/dL is 0.1 mmol/L.
 * All three deltas must be rising. The caller switches profile for 30 minutes and does not change the IOB threshold.
 */
internal fun highNightShouldFire(
    ready: Boolean,
    tempTargetSet: Boolean,
    steroidsOff: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
): Boolean = ready &&
    !tempTargetSet &&
    steroidsOff &&
    timeWindowContains(minuteOfDay, 2, 0, 7, 0) &&
    bg > 117.1 &&
    delta >= 1.8 &&
    shortDelta >= 1.8 &&
    longDelta >= 1.8
