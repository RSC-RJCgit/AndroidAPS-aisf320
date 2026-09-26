package app.aaps.plugins.aps.openAPSAutoISF

// The pump has not reported for 20 minutes, from 08:00 until 23:00, and the cannula is 80 hours old or less.
// Not for a virtual pump. The alert itself is logged by the caller.
internal fun connectPodShouldFire(
    ready: Boolean,
    livePump: Boolean,
    minutesSinceConnection: Long,
    minuteOfDay: Int,
    cannulaHours: Double?,
): Boolean {
    if (!ready || !livePump || minutesSinceConnection < 20) return false
    if (!minuteInWindow(minuteOfDay, 8 * 60, 23 * 60)) return false
    val hours = cannulaHours ?: return false
    return hours <= 80.0
}

// Drops accel to 0.02 and the IOB threshold to 50% when accel is already in the 0.03 to 0.08 band.
// Returns the block name, or null. A missing raw delta blocks the extra gate.
// Glucose is mg/dL. [hp] is the hypo prediction in mmol/L, or null when raw is missing.
internal fun gentleHypoBlock(
    ready: Boolean,
    acceWeight: Double,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    profilePercent: Int,
    ukfGlucose: Double?,
    ukfDelta1: Double?,
    ukfDelta5: Double?,
    hp: Double?,
    steps60: Int,
): String? {
    if (!ready || acceWeight <= 0.03 || acceWeight > 0.08) return null
    val ukfLow = ukfGlucose != null && ukfGlucose <= 72.1
    val ukfOr1 = (ukfGlucose != null && ukfGlucose <= 63.1) ||
        (ukfDelta1 != null && ukfDelta1 < 0.0) ||
        (ukfDelta5 != null && ukfDelta5 < 0.0)
    val block1 = bg <= 95.5 && ukfLow && ukfOr1
    val ukfOr2 = (ukfGlucose != null && ukfGlucose <= 63.1) || delta <= 0.0 || (ukfDelta5 != null && ukfDelta5 <= 0.0)
    val block2 = bg <= 99.1 && profilePercent == 50 && delta <= -5.4 && shortDelta <= -3.6 && ukfOr2
    val blockHp = hp != null && hp <= 4.1
    val block = when {
        block1 -> "1"
        block2 -> "2"
        blockHp -> "HP2"
        else -> null
    } ?: return null
    val extraLow = (ukfGlucose != null && ukfGlucose < 72.1) || (hp != null && hp <= 4.1) || steps60 > 200
    val extraD1 = ukfDelta1 != null && ukfDelta1 <= 0.0
    val extraD5 = ukfDelta5 != null && ukfDelta5 <= 0.0
    if (!extraLow || !extraD1 || !extraD5) return null
    return block
}

// Sets 5.7 mmol for 180 minutes. Returns the block letter, or null.
// Block C is the emergency floor and does not use the profile or bolus gate.
internal fun skittlesBlock(
    ready: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    iob: Double,
    cob: Double,
    profilePercent: Int,
    minutesSinceBolus: Int,
    steroidsOff: Boolean,
): String? {
    if (!ready) return null
    val gate = profilePercent >= 65 && minutesSinceBolus >= 5
    val blockA = bg <= 81.1 && delta <= -0.9 && iob >= -0.2 && shortDelta <= -0.9 && longDelta <= -0.9 && cob <= 15.0 && gate
    val blockB = bg <= 90.1 && delta <= -5.13 && iob >= 0.3 && shortDelta <= -3.6 && longDelta <= -3.6 &&
        gate && (cob <= 15.0 || minutesSinceBolus >= 60)
    val blockC = bg <= 63.1 && delta <= 0.0
    val blockD = bg <= 108.1 && delta <= -16.21 && shortDelta <= -16.21 && iob > 2.8 && gate
    val blockE = bg <= 117.1 && delta <= -9.01 && shortDelta <= -7.21 && longDelta <= -3.6 && iob >= 1.5 && cob <= 15.0 && gate
    val blockF = bg <= 162.1 && delta <= -9.01 && shortDelta <= -7.21 && longDelta <= -7.21 &&
        iob >= 2.9 && cob <= 15.0 && gate && steroidsOff
    val blockG = bg <= 171.2 && delta <= -19.82 && shortDelta <= -14.41 && longDelta <= -14.41 && iob >= 1.4 && cob <= 15.0 && gate
    return when {
        blockA -> "A"
        blockB -> "B"
        blockC -> "C"
        blockD -> "D"
        blockE -> "E"
        blockF -> "F"
        blockG -> "G"
        else -> null
    }
}

// Flags LowBG as 50recent when the profile is already 50% and the flag is clear.
// [pp50OffReady] is false for 15 minutes after PP50Off, so the two cannot flip every loop.
internal fun fiftySetRecentShouldFire(
    ready: Boolean,
    pp50OffReady: Boolean,
    profilePercent: Int,
    lowBgClear: Boolean,
): Boolean = ready && pp50OffReady && profilePercent == 50 && lowBgClear

// On a 50% profile with no temp target, glucose under 5.0 mmol/L and still falling gets 5.7 mmol for 150 minutes.
internal fun fiftyPcMakes57ShouldFire(
    ready: Boolean,
    profilePercent: Int,
    ttActive: Boolean,
    bg: Double,
    delta: Double,
): Boolean = ready && profilePercent == 50 && !ttActive && bg < 90.1 && delta <= -0.9

// Leaves the 50% prepare state. LowBG must be 50recent. Returns the block name, or null.
// A missing cannula age counts as 0 hours, so the blocks that need 3 hours stay closed.
internal fun pp50OffBlock(
    ready: Boolean,
    lowBgRecent: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    iob: Double,
    cob: Double,
    cannulaHours: Double?,
    minutesSinceBolus: Int,
    acceWeight: Double,
): String? {
    if (!ready || !lowBgRecent) return null
    val cannulaH = cannulaHours ?: 0.0
    val daytime = minuteInWindow(minuteOfDay, 1 * 60 + 1, 22 * 60)
    val overnight = minuteInWindow(minuteOfDay, 22 * 60, 1 * 60)
    val block1 = daytime && bg >= 108.1 && delta > -1.8 && shortDelta > -3.6 && longDelta > -7.21
    val block2 = daytime && bg >= 108.1 && delta > 5.4 && shortDelta > 5.4 && cannulaH >= 3.0
    val block3 = daytime && bg >= 108.1 && cob > 0.0 && minutesSinceBolus <= 30 &&
        delta > 0.9 && shortDelta > 0.9 && longDelta > 0.9 && cannulaH >= 3.0
    val block4 = daytime && bg >= 108.1 && iob <= 0.5 &&
        delta > 0.9 && shortDelta > 0.9 && longDelta > 0.9 && cannulaH >= 3.0
    val block5 = overnight && bg >= 117.1 && delta > 0.9 && shortDelta > 0.9 && longDelta > 0.9
    val block6 = acceWeight <= 0.1 && bg >= 99.1 && delta > 1.8 && shortDelta > 1.8
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        block4 -> "4"
        block5 -> "5"
        block6 -> "6"
        else -> null
    }
}

// Prepares a 50% profile for 6 hours. Profile must already be 100%. Returns the block name, or null.
internal fun prepareSet50Block(
    ready: Boolean,
    profilePercent: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    iob: Double,
    cob: Double,
    steps30: Int,
    minuteOfDay: Int,
): String? {
    if (!ready || profilePercent != 100) return null
    val block1 = bg <= 99.1 && delta <= -4.5 && shortDelta <= -4.5 && cob == 0.0
    val block2 = bg <= 99.1 && steps30 >= 300 && cob == 0.0 && iob >= 1.2 && delta <= -1.8
    val block3 = bg < 90.1 && delta <= -0.9
    val block4 = bg <= 126.1 && minuteInWindow(minuteOfDay, 21 * 60, 24 * 60) && shortDelta <= -1.8 && delta <= -3.6
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        block4 -> "4"
        else -> null
    }
}
