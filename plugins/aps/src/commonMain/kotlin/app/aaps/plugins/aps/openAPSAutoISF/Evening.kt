package app.aaps.plugins.aps.openAPSAutoISF

// From 22:00 until midnight, lowers the IOB threshold to 45%, or 60% after carbs have stayed
// at 5 g or more for 45 minutes. It never raises the threshold. Returns the cap, or null.
internal fun eveningIobCap(
    ready: Boolean,
    minuteOfDay: Int,
    noMjRemains: Boolean,
    iobTh: Int,
    cobSustained: Boolean,
): Int? {
    if (!ready || !noMjRemains || !minuteInWindow(minuteOfDay, 22 * 60, 24 * 60)) return null
    val cap = if (cobSustained) 60 else 45
    if (iobTh <= cap) return null
    return cap
}

// 04:30 until 08:00. All three deltas are positive and under 0.15 mmol. Glucose is over 6.5 mmol.
// A temp target that is already on keeps this closed. The caller sets 4.4 mmol for 5 minutes.
internal fun earlyDawnShouldFire(
    ready: Boolean,
    minuteOfDay: Int,
    ttActive: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
): Boolean {
    if (!ready || ttActive || !minuteInWindow(minuteOfDay, 4 * 60 + 30, 8 * 60)) return false
    val slow = delta > 0.0 && delta < 2.7 && shortDelta > 0.0 && shortDelta < 2.7 && longDelta > 0.0 && longDelta < 2.7
    return bg > 117.1 && slow
}

// Evening back-off. Returns the block name, or null.
// Block 1 and block 2 run from 20:00 until midnight. Block 3 runs from 22:00 until midnight.
// Writing 45% is what stops block 1 from firing again.
internal fun eveningThBlock(
    ready: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    iobTh: Int,
    cob: Double,
    ttActive: Boolean,
    minutesSinceBolus: Int,
    mjActive: Boolean,
    onRoleProfile: Boolean,
    profilePercent: Int,
): String? {
    if (!ready) return null
    val evening = minuteInWindow(minuteOfDay, 20 * 60, 24 * 60)
    val late = minuteInWindow(minuteOfDay, 22 * 60, 24 * 60)
    val block1 = evening && bg <= 135.1 && iobTh < 45 && cob <= 0.0 && delta <= 3.6 &&
        !ttActive && minutesSinceBolus >= 90 && !mjActive
    val block2 = iobTh >= 51 && bg <= 135.1 && cob <= 0.0 && evening && !ttActive && delta <= -1.8 &&
        onRoleProfile && minutesSinceBolus >= 90 && profilePercent == 100 && !mjActive
    val block3 = late && bg >= 122.5 && delta >= 1.8 && onRoleProfile && minutesSinceBolus >= 90 &&
        profilePercent == 100 && iobTh >= 51 && mjActive
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        else -> null
    }
}

// From 22:00 until 06:00 the low-profile switch does not need a prediction.
// Earlier in the evening it needs a hypo prediction under 5.0 mmol. A missing prediction stays closed.
internal fun eveningThShouldSwitchLow(minuteOfDay: Int, hp: Double?): Boolean =
    minuteInWindow(minuteOfDay, 22 * 60, 6 * 60) || (hp != null && hp < 5.0)
