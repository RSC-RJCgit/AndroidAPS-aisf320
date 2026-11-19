package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs
import kotlin.math.max

/**
 * Compression-low detector, rebuilt 2026-11-19, per explicit request, from the 29 Oct - 19 Nov Live data. The old rules (30 flat
 * minutes before the drop, then 15 minutes still down and under 6.0 before any note) caught none of the four compressions on the
 * night of 19 Nov (01:29, 02:20, 06:34, 07:03): they were short V-shaped dips, often one after another, that recovered within
 * 10-15 minutes. Now the DROP is the event:
 *  - the raw series falls at least 1.0 mmol within the last 5 minutes (the UKF-smoothed series until 2026-11-20: it lags at the
 *    newest reading and spreads the fall back into the 15 minutes before, so on Live's 20 Nov 01:09-01:13 drop it never fired);
 *  - the 15 minutes before it were flat or rising: no 5-minute fall of more than 0.5 mmol in them, and no net fall of more than
 *    0.6 mmol across them (a rebound or a rise before it is fine, which is what the dips that follow one another look like);
 *  - the fall is at least 1.25 times what the insulin on board explains over those 5 minutes (the loop's BGI), which the caller
 *    passes in.
 * There is no level cap and no wait for confirmation. The caller adds the hours ([inHours], 22:30-08:00), the carbs check and the
 * 10 minute gap between triggers ([MIN_GAP_MINUTES]). A sharp rebound afterwards ([rebounded]) marks a true compression.
 * Series are (timestamp ms, mg/dL), oldest first, about one per minute: the raw series for both [suspect] and [rebounded]. The
 * caller adds a note only when [suspect] flags on two loop cycles in a row ([secondCycle]), so a single stray raw reading cannot.
 */
internal object CompressionDetector {

    /** The newest reading must be at least this far (1.0 mmol) under the reading 5 minutes earlier. */
    const val SUDDEN_DROP_MGDL = 18.0
    const val DROP_MINUTES = 5L

    /** The minutes before the drop that must be flat or rising, and enough readings in them. */
    const val PRIOR_MINUTES = 15L
    private const val MIN_PRIOR_READINGS = 10

    /** In those minutes: no 5-minute fall over 0.5 mmol, and no net fall over 0.6 mmol. */
    const val PRIOR_STEEPEST_FALL_MGDL = 9.0
    const val PRIOR_NET_FALL_MGDL = 10.8

    /** The drop must be at least this many times the fall the insulin on board explains over the same 5 minutes. */
    const val UNEXPLAINED_FACTOR = 1.25

    /** A new trigger needs at least this long since the last one, so one continuous fall gives one note. */
    const val MIN_GAP_MINUTES = 10L

    /** Minutes of readings needed: the 15 minutes before, the 5-minute drop, a margin. */
    const val LOOKBACK_MINUTES = 30L

    /** A rebound is a rise of at least this much within 10 minutes, starting near the low. */
    const val REBOUND_MGDL = 25.0
    const val REBOUND_START_NEAR_LOW_MGDL = 15.0

    /** A trigger waits this long for a rebound. */
    const val PENDING_MINUTES = 120L

    private const val MINUTE = 60_000L
    private const val TEN_MIN = 10 * MINUTE
    private const val NEAREST_TOLERANCE = 90_000L

    /** 22:30 to 08:00, when a sudden drop is most likely lying on the sensor. [minuteOfDay] is local time. */
    fun inHours(minuteOfDay: Int): Boolean = minuteOfDay >= 22 * 60 + 30 || minuteOfDay < 8 * 60

    class Suspect(
        val fromMgdl: Double,
        val toMgdl: Double,
        val steepestMgdlPerMin: Double,
        val fallMinutes: Long,
        val explainedMgdl: Double,
        /** The steepest 5-minute fall in the 15 minutes before (mg/dL, 0 when none) */
        val priorSteepestFallMgdl: Double,
        /** Last minus first reading of the 15 minutes before (mg/dL, positive = rising) */
        val priorNetChangeMgdl: Double,
    )

    /** A second flag counts when the first was at most this long before: the next cycle at a 1 minute loop, and also at a 5 minute one. */
    const val SECOND_CYCLE_WITHIN_MS = 6 * MINUTE

    /** True when this cycle's flag follows one at [firstFlagAt] (0 = none) within [SECOND_CYCLE_WITHIN_MS]. */
    fun secondCycle(firstFlagAt: Long, now: Long): Boolean =
        firstFlagAt > 0L && now > firstFlagAt && now - firstFlagAt <= SECOND_CYCLE_WITHIN_MS

    private fun valueNear(series: List<Pair<Long, Double>>, time: Long): Double? =
        series.minByOrNull { abs(it.first - time) }?.takeIf { abs(it.first - time) <= NEAREST_TOLERANCE }?.second

    /**
     * A sudden compression-type drop at the newest reading, or null. [explainedFall5Mgdl] is the fall over 5 minutes the insulin on
     * board accounts for (positive, mg/dL; the loop's BGI with the sign dropped), 0 when unknown.
     */
    fun suspect(series: List<Pair<Long, Double>>, explainedFall5Mgdl: Double = 0.0): Suspect? {
        if (series.size < MIN_PRIOR_READINGS + 3) return null
        val now = series.last().first
        val v = series.last().second
        val before = valueNear(series, now - DROP_MINUTES * MINUTE) ?: return null
        val drop = before - v
        if (drop < SUDDEN_DROP_MGDL) return null
        val explained = explainedFall5Mgdl.coerceAtLeast(0.0)
        if (drop < UNEXPLAINED_FACTOR * explained) return null
        // The 15 minutes ending where the drop began (inclusive of the reading 5 minutes ago)
        val priorEnd = now - DROP_MINUTES * MINUTE
        val priorFrom = priorEnd - PRIOR_MINUTES * MINUTE
        val prior = series.filter { it.first in priorFrom..priorEnd }
        if (prior.size < MIN_PRIOR_READINGS || prior.first().first > priorFrom + 5 * MINUTE) return null
        val priorSteepest = prior.filter { it.first >= priorFrom + DROP_MINUTES * MINUTE }
            .mapNotNull { r -> valueNear(series, r.first - DROP_MINUTES * MINUTE)?.let { it - r.second } }
            .maxOrNull()?.let { max(it, 0.0) } ?: 0.0
        val priorNet = prior.last().second - prior.first().second
        if (priorSteepest > PRIOR_STEEPEST_FALL_MGDL || priorNet < -PRIOR_NET_FALL_MGDL) return null
        val last5 = series.filter { it.first >= priorEnd }
        val steepest = last5.zipWithNext { a, b -> (b.second - a.second) / ((b.first - a.first) / MINUTE.toDouble()).coerceAtLeast(1.0) }.minOrNull() ?: 0.0
        return Suspect(before, v, steepest, DROP_MINUTES, explained, priorSteepest, priorNet)
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
