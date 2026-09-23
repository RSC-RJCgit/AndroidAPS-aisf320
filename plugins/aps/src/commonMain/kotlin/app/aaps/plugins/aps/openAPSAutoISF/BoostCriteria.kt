package app.aaps.plugins.aps.openAPSAutoISF

/**
 * True when [minuteOfDay] sits in the clock window.
 * A window that passes midnight (start later than end) covers the late hours and the early hours.
 * An end of 00:00 does not wrap: 08:30 to 00:00 means from 08:30 until the end of the day.
 */
internal fun timeWindowContains(minuteOfDay: Int, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): Boolean {
    val startMins = startHour * 60 + startMinute
    val endMins = endHour * 60 + endMinute
    return if (startMins <= endMins) minuteOfDay in startMins until endMins
    else minuteOfDay >= startMins || minuteOfDay < endMins
}

/**
 * Mild bolus-boost gate. Marks only. It does not change the delivery ratio, the IOB threshold, or the profile.
 * Raw deltas are Libre raw mg/dL. A missing raw value must be passed as -9999 so the rise gate stays closed.
 * [deliveryBaseline] is the SMB delivery baseline preference. 0.17 is the old tuning point the IOB gate was built from.
 */
internal fun mildBoostShouldFire(
    readyMild: Boolean,
    readyBg3: Boolean,
    profilePercent: Int,
    tempTargetSet: Boolean,
    boostAutomationsOn: Boolean,
    minuteOfDay: Int,
    daytimeBypass: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    rawDelta5: Double,
    rawDelta1: Double,
    iobChange5: Double,
    cob: Double,
    minutesSinceNormalBolus: Int,
    recentAlarmHypo: Boolean,
    onLowProfile: Boolean,
    mjActive: Boolean,
    steps5: Int,
    steps30: Int,
    smbIntervalSec: Double,
    deliveryBaseline: Double,
): Boolean {
    if (!(profilePercent == 100 && !tempTargetSet && boostAutomationsOn && readyMild)) return false
    val stackK = if (smbIntervalSec <= 70.0) 1.10 else 1.0
    val thresholdScale = deliveryBaseline / 0.17
    val rawDelta1FloorOk = bg < 162.1 || rawDelta1 >= 4.5 * stackK
    val mealFloor = if (recentAlarmHypo) 126.1 else 108.1
    val mealLeftover = bg >= mealFloor && (cob >= 4.0 || minutesSinceNormalBolus < 180) && shortDelta >= 2.7
    val iobRising = iobChange5 > 0.40 * stackK * thresholdScale
    val inWindow = timeWindowContains(minuteOfDay, 8, 30, 2, 0) || daytimeBypass
    return inWindow &&
        (iobRising || mealLeftover) &&
        delta >= 5.4 * stackK &&
        rawDelta5 >= 5.4 * stackK &&
        (mealLeftover || rawDelta5 < 14.4 * stackK) &&
        rawDelta1FloorOk &&
        (mealLeftover || rawDelta1 < 14.4 * stackK) &&
        !onLowProfile &&
        !mjActive &&
        readyBg3 &&
        steps5 <= 100 &&
        steps30 <= 200 &&
        !(bg < 135.1 && iobChange5 > 0.8)
}

/**
 * Strong bolus-boost gate (bg3). A temp target does not block this gate.
 * The caller must still drop the mark when [bg3BoostBlocked] is true.
 */
internal fun bg3BoostShouldFire(
    readyBolusGiven: Boolean,
    readyBg3: Boolean,
    readyMild: Boolean,
    profilePercent: Int,
    boostAutomationsOn: Boolean,
    minuteOfDay: Int,
    daytimeBypass: Boolean,
    bg: Double,
    delta: Double,
    longDelta: Double,
    rawDelta5: Double,
    rawDelta1: Double,
    iobChange5: Double,
    smbCount5: Int,
    onLowProfile: Boolean,
    mjActive: Boolean,
    steps5: Int,
    steps30: Int,
    steps60: Int,
    smbIntervalSec: Double,
    deliveryBaseline: Double,
): Boolean {
    if (!(profilePercent == 100 && boostAutomationsOn && readyBolusGiven && readyBg3)) return false
    val stackK = if (smbIntervalSec <= 70.0) 1.10 else 1.0
    val thresholdScale = deliveryBaseline / 0.17
    val rawDelta1FloorOk = bg < 162.1 || rawDelta1 >= 4.5 * stackK
    val deliverySuppressed = smbCount5 <= 1 && rawDelta5 >= 14.4 && rawDelta1 >= 14.4 && bg >= 117.1 && delta >= 0.0
    val inWindow = timeWindowContains(minuteOfDay, 8, 30, 0, 0) || daytimeBypass
    return inWindow &&
        ((iobChange5 > 0.85 * stackK * thresholdScale && delta >= 10.8 * stackK) || deliverySuppressed) &&
        rawDelta5 >= 14.4 * stackK &&
        rawDelta1FloorOk &&
        !onLowProfile &&
        !mjActive &&
        readyMild &&
        steps5 <= 100 &&
        steps30 <= 200 &&
        steps60 < 300 &&
        (deliverySuppressed || longDelta > 7.2)
}

/** True when a bg3 fire must not be marked. Matches the 60 minute re-arm and the IOB ceilings. */
internal fun bg3BoostBlocked(recentBolusGiven: Boolean, recentMild: Boolean, recentMildFailsafe: Boolean, iob: Double): Boolean {
    if (recentBolusGiven) return true
    if (iob >= 2.0) return true
    if ((recentMild || recentMildFailsafe) && iob >= 1.5) return true
    return false
}
