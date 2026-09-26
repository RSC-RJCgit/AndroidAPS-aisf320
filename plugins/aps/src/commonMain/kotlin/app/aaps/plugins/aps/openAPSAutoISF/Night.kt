package app.aaps.plugins.aps.openAPSAutoISF

// Daytime only, 08:00 until 22:00. Raises the acceleration weight to its high value.
// Overnight back-off rules stay in force because this window does not cover them.
internal fun acceUpShouldFire(
    ready: Boolean,
    minuteOfDay: Int,
    acce: Double,
    bg: Double,
    profilePercent: Int,
    ttActive: Boolean,
    noMjRemains: Boolean,
    steroidsOn: Boolean,
): Boolean =
    ready && acce <= 0.5 && minuteInWindow(minuteOfDay, 8 * 60, 22 * 60) &&
        bg >= 108.1 && profilePercent == 100 && !ttActive && (noMjRemains || steroidsOn)

// Alert only. A fast rise with at least 1000 steps in the last hour. The caller logs it.
internal fun exerciseLimitShouldFire(ready: Boolean, bg: Double, delta: Double, steps60: Int): Boolean =
    ready && bg >= 126.1 && delta >= 7.2 && steps60 >= 1000

// Midnight until 06:00. Lowers the IOB threshold to 22%, or 35% after sustained carbs.
// The acceleration cap stays 0.35 either way. Steps of 100 or more keep this closed.
// A value already at or under the cap is left alone.
internal data class NightCeiling(val iob: Int?, val acce: Double?)

internal fun nightIobCeiling(
    ready: Boolean,
    minuteOfDay: Int,
    steps60: Int,
    iobTh: Int,
    acce: Double,
    cobSustained: Boolean,
): NightCeiling? {
    if (!ready || steps60 >= 100 || !minuteInWindow(minuteOfDay, 0, 6 * 60)) return null
    val cap = if (cobSustained) 35 else 22
    val lowerIob = if (iobTh > cap) cap else null
    val lowerAcce = if (acce > 0.35) 0.35 else null
    if (lowerIob == null && lowerAcce == null) return null
    return NightCeiling(lowerIob, lowerAcce)
}

// 06:00 until 08:00, quiet, flat or falling, IOB threshold in the 17% to 39% band.
// The caller sets the threshold to 15% and the acceleration weight to 0.50, then switches to Low.
internal fun twilightTh15ShouldFire(
    ready: Boolean,
    minuteOfDay: Int,
    steps60: Int,
    bg: Double,
    ttActive: Boolean,
    iobTh: Int,
    steroidsOff: Boolean,
    delta: Double,
): Boolean =
    ready && minuteInWindow(minuteOfDay, 6 * 60, 8 * 60) && steps60 <= 10 &&
        bg <= 117.1 && !ttActive && iobTh > 16 && iobTh <= 39 && steroidsOff && delta <= 0.0

// 01:00 until 06:00. Fires when the IOB threshold is outside the 22% band.
// Writing 22 is what stops the next loop. The caller also sets acceleration to 0.35.
internal fun nightAcceShouldFire(
    ready: Boolean,
    minuteOfDay: Int,
    iobTh: Int,
    cob: Double,
    ttActive: Boolean,
    bg: Double,
    steroidsOff: Boolean,
    acce: Double,
): Boolean =
    ready && minuteInWindow(minuteOfDay, 60, 6 * 60) && (iobTh >= 23 || iobTh <= 21) &&
        cob <= 0.0 && !ttActive && bg <= 108.1 && steroidsOff && acce >= 0.08

// Three morning branches. All of them need steroids off. The caller sets acceleration 0.50 and IOB 16%.
// Block 3's second check (under 40) is already true when the threshold is 16. It is kept so the rule matches.
internal fun semiTwilightBlock(
    ready: Boolean,
    minuteOfDay: Int,
    steps180: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    ttActive: Boolean,
    iobTh: Int,
    acce: Double,
    steroidsOff: Boolean,
): String? {
    if (!ready || !steroidsOff) return null
    val block1 = minuteInWindow(minuteOfDay, 6 * 60, 9 * 60) && steps180 >= 10 && !ttActive && iobTh <= 15
    val block2 = minuteInWindow(minuteOfDay, 2 * 60, 9 * 60) && bg >= 180.2 && acce <= 0.025 && !ttActive
    val block3 = minuteInWindow(minuteOfDay, 6 * 60, 10 * 60 + 30) && bg >= 126.1 &&
        longDelta >= 0.0 && delta >= 1.8 && shortDelta >= 0.0 && iobTh == 16 && iobTh < 40
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        else -> null
    }
}
