package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Which Usual2 block fires, or null when none do.
 * Glucose and deltas are mg/dL. 99.1 is 5.5 mmol/L. 153.1 is 8.5 mmol/L. 18.0 is 1.0 mmol/L.
 * [earlyAfterShower] is true when Shower12 ran 15 minutes to 4 hours ago.
 * Glucose over 7.0 mmol/L and a short delta over 0.10 mmol/L are still required here.
 * Block 1 wins, then block 2, then block 3.
 */
internal fun usual2Block(
    ready: Boolean,
    profilePercent: Int,
    tempTargetSet: Boolean,
    iobTh: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    steroidsOff: Boolean,
    minuteOfDay: Int,
    steps60: Int,
    steps180: Int,
    cob: Double,
    earlyAfterShower: Boolean,
): String? {
    if (!ready || profilePercent != 100 || tempTargetSet || iobTh >= 70 || bg < 99.1 || !steroidsOff) return null
    val stabilized = delta >= 0.0 && shortDelta >= 0.0
    val early = earlyAfterShower && bg > 126.1 && shortDelta > 1.8
    val daytime = timeWindowContains(minuteOfDay, 8, 0, 20, 0) ||
        (early && timeWindowContains(minuteOfDay, 5, 30, 20, 0))
    val lowOrSettled = iobTh <= 19 || (iobTh == 50 && stabilized)
    val block1 = daytime &&
        (steps180 >= 10 || lowOrSettled) &&
        (early || steps60 >= 50)
    val block2 = timeWindowContains(minuteOfDay, 9, 1, 20, 0) && lowOrSettled
    val block3 = timeWindowContains(minuteOfDay, 5, 1, 20, 0) &&
        (cob >= 10.0 || steps60 >= 100 || bg >= 153.1 || shortDelta >= 18.0)
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        else -> null
    }
}
