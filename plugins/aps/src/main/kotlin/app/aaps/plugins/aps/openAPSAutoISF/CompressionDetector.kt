package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

/**
 * Compression-low detector (2026-10-08, notes only), rebuilt the same day on the UKF-smoothed raw series after a sensor swing at a
 * normal glucose (8 Oct 12:18) passed the old sharp-fall test. Compared on UKF values, the steepest 5-minute fall is almost the
 * same in a true compression low (-1.48 mmol, 03:24) and in that swing (-1.50 mmol, 12:03), so the rate of fall cannot separate
 * them. What does:
 *  - the fall is unbroken for 15 minutes or more (true 20+, swing 8-9 minutes) and drops at least 2.0 mmol in total (true 3.4,
 *    swing 0.9);
 *  - the signal was quiet before it (30 minutes before: swing 0.3 mmol and no 5-minute rise above +0.13 against 2.8 and +1.24);
 *  - it ends at or under 6.0 mmol.
 * Series are (timestamp ms, mg/dL), oldest first, about one per minute: the UKF-smoothed series for [suspect], the raw series for
 * [rebounded].
 */
internal object CompressionDetector {

    /** A minute counts as falling when the change over the previous 5 minutes is at or under this (-0.15 mmol). */
    const val FALLING_MGDL_PER_5MIN = -2.7

    /** The unbroken falling run must last at least this long... */
    const val MIN_FALL_MINUTES = 15L

    /** ...and drop at least this much in total (2.0 mmol), measured from where the fall began. */
    const val MIN_TOTAL_FALL_MGDL = 36.0

    /** The 30 minutes before the fall must stay within this swing (1.0 mmol), with no 5-minute rise above the next bar (0.5 mmol). */
    const val QUIET_MINUTES = 30L
    const val QUIET_SWING_MGDL = 18.0
    const val QUIET_MAX_RISE_MGDL_PER_5MIN = 9.0

    /** The level now must be at or under this (6.0 mmol). */
    const val FLOOR_MGDL = 108.1

    /** Minutes of readings needed: the quiet window, the 15-minute fall, a margin. */
    const val LOOKBACK_MINUTES = 70L

    /** A rebound is a rise of at least this much within 10 minutes, starting near the low. */
    const val REBOUND_MGDL = 25.0
    const val REBOUND_START_NEAR_LOW_MGDL = 15.0

    /** A suspected event waits this long for a rebound, and a new suspicion this soon after the last is the same event. */
    const val PENDING_MINUTES = 120L
    const val SAME_EVENT_MINUTES = 40L

    private const val FIVE_MIN = 5 * 60_000L
    private const val TEN_MIN = 10 * 60_000L
    private const val NEAREST_TOLERANCE = 90_000L
    private const val MAX_GAP = 150_000L

    class Suspect(val fromMgdl: Double, val toMgdl: Double, val steepestMgdlPerMin: Double, val fallMinutes: Long)

    private fun valueNear(series: List<Pair<Long, Double>>, time: Long): Double? =
        series.minByOrNull { abs(it.first - time) }?.takeIf { abs(it.first - time) <= NEAREST_TOLERANCE }?.second

    /** Change over the previous 5 minutes at index [i], or null when there is no reading about 5 minutes earlier. */
    private fun delta5(series: List<Pair<Long, Double>>, i: Int): Double? {
        val (t, v) = series[i]
        val earlier = valueNear(series.subList(0, i), t - FIVE_MIN) ?: return null
        return v - earlier
    }

    /** A compression-type fall that is still going at the newest reading and has run long enough, or null. */
    fun suspect(series: List<Pair<Long, Double>>): Suspect? {
        if (series.size < 20) return null
        val last = series.lastIndex
        if (series[last].second > FLOOR_MGDL) return null
        // The unbroken falling run that ends at the newest reading.
        var start = last
        var steepest5 = 0.0
        var i = last
        while (i >= 0) {
            if (i < last && series[i + 1].first - series[i].first > MAX_GAP) break
            val d = delta5(series, i) ?: break
            if (d > FALLING_MGDL_PER_5MIN) break
            steepest5 = minOf(steepest5, d)
            start = i
            i--
        }
        val fallMinutes = (series[last].first - series[start].first) / 60_000L
        if (fallMinutes < MIN_FALL_MINUTES) return null
        val fallBegan = series[start].first - FIVE_MIN
        val before = valueNear(series, fallBegan) ?: return null
        if (before - series[last].second < MIN_TOTAL_FALL_MGDL) return null
        // The 30 minutes before the fall: need data from at least 25 of them, within the swing, no 5-minute rise above the bar.
        val quietFrom = fallBegan - QUIET_MINUTES * 60_000L
        val quiet = series.withIndex().filter { it.value.first in quietFrom..fallBegan }
        if (quiet.size < 8 || quiet.first().value.first > quietFrom + 5 * 60_000L) return null
        val values = quiet.map { it.value.second }
        if (values.max() - values.min() > QUIET_SWING_MGDL) return null
        if (quiet.any { q -> (delta5(series, q.index) ?: 0.0) > QUIET_MAX_RISE_MGDL_PER_5MIN }) return null
        return Suspect(before, series[last].second, steepest5 / 5.0, fallMinutes)
    }

    /**
     * True when, after [suspectAt], the readings rise by at least [REBOUND_MGDL] within 10 minutes from near the lowest
     * value reached since. [readings] must cover the time from [suspectAt] to now.
     */
    fun rebounded(readings: List<Pair<Long, Double>>, suspectAt: Long): Boolean {
        val after = readings.filter { it.first >= suspectAt }
        if (after.size < 3) return false
        val low = after.minOf { it.second }
        for (i in after.indices) {
            if (after[i].second > low + REBOUND_START_NEAR_LOW_MGDL) continue
            for (j in i + 1 until after.size) {
                if (after[j].first - after[i].first > TEN_MIN) break
                if (after[j].second - after[i].second >= REBOUND_MGDL) return true
            }
        }
        return false
    }
}
