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

/**
 * Steroid-day IobTH floor (2026-11-27, per explicit request, same as 3426), the companion of [iobThDaytimeFloorShouldFire] (which
 * needs Steroids Off). [steroidLevel] is the running Steroid tier's own IobTH (110 75, 130 78, 150 81, 190 84, 250 88 - what the
 * steroid buttons set), null when no Steroid profile runs. Returns the IobTH to write, or null: 08:00-22:00, profile 100%, LowBG
 * clear, no TT, BG 6.5 mmol or more and not falling, and IobTH below that level.
 */
internal fun steroidIobThFloorTarget(
    ready: Boolean,
    minuteOfDay: Int,
    profilePercent: Int,
    lowBgClear: Boolean,
    tempTargetSet: Boolean,
    steroidLevel: Int?,
    iobTh: Int,
    bg: Double,
    delta: Double,
): Int? = steroidLevel?.takeIf {
    ready && timeWindowContains(minuteOfDay, 8, 0, 22, 0) && profilePercent == 100 && lowBgClear && !tempTargetSet &&
        iobTh < it && bg >= 117.1 && delta >= 0.0
}
