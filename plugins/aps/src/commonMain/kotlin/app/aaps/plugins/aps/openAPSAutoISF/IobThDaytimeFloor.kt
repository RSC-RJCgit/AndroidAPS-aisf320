package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Daytime rescue for an IOB threshold that has fallen to 50% or below.
 * The window is 08:00 until 22:00. 117.1 mg/dL is 6.5 mmol/L.
 * A threshold of 51 or more is left alone. The caller writes 70 only when 70 is not above the saved baseline.
 */
internal fun iobThDaytimeFloorShouldFire(
    ready: Boolean,
    minuteOfDay: Int,
    profilePercent: Int,
    lowBgClear: Boolean,
    steroidsOff: Boolean,
    tempTargetSet: Boolean,
    iobTh: Int,
    bg: Double,
    delta: Double,
): Boolean = ready &&
    timeWindowContains(minuteOfDay, 8, 0, 22, 0) &&
    profilePercent == 100 &&
    lowBgClear &&
    steroidsOff &&
    !tempTargetSet &&
    iobTh <= 50 &&
    bg >= 117.1 &&
    delta >= 0.0
