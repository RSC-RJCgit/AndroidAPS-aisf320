package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Which Extra50 block fires, or null when none do.
 * Glucose and deltas are mg/dL. 153.1 is 8.5 mmol/L. 135.1 is 7.5 mmol/L. 117.1 is 6.5 mmol/L.
 * -6.3 is -0.35 mmol/L. -4.5 is -0.25 mmol/L. -3.6 is -0.2 mmol/L. -2.7 is -0.15 mmol/L. -1.8 is -0.1 mmol/L.
 * Blocks 1 and 2 need an MJ cycle that is not the resting value. Block 3 is 01:00 until 05:00.
 * Block 1 wins, then block 2, then block 3.
 */
internal fun extra50Block(
    ready: Boolean,
    profilePercent: Int,
    lowBgClear: Boolean,
    mjNotRemaining: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
): String? {
    if (!ready || profilePercent != 100 || !lowBgClear) return null
    val block1 = delta <= -6.3 && shortDelta <= -4.5 && longDelta <= -3.6 && bg <= 153.1 && mjNotRemaining
    val block2 = bg <= 117.1 && longDelta <= -2.7 && delta <= -3.6 && shortDelta <= -1.8 && mjNotRemaining
    val block3 = timeWindowContains(minuteOfDay, 1, 0, 5, 0) && bg <= 135.1 && shortDelta < -3.6 && delta < -3.6
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        else -> null
    }
}
