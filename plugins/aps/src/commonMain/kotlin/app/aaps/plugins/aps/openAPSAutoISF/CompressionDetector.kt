package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

/**
 * Compression-low detector (2026-10-08, notes only), rebuilt a third time the same day, per explicit request, after a slow
 * 40-minute slide (8.2 to 5.9 mmol at about -0.15 mmol per 5 minutes, 0.1-0.35 U on board) raised a warning on 23:03. A slow
 * slide is not a trigger. A compression low starts with a SUDDEN drop out of a flat spell, and the insulin on board cannot
 * account for it:
 *  - lane A: the smoothed series falls at least 1.0 mmol within the last 5 minutes (5 loops) after 30 flat minutes (swing no
 *    more than 0.6 mmol); or lane B (added the same day after a true compression at 20:40 came on top of a slow decline, 9.3 to
 *    7.1 mmol in the half hour before it): it falls at least 1.4 mmol within 5 minutes, at least twice the steepest 5-minute
 *    fall of the 30 minutes before, which fell no more than 2.5 mmol in all;
 *  - either way the fall is at least three times what the insulin on board explains over those 5 minutes (the loop's BGI),
 *    which the caller passes in.
 * Checked on the 5-8 Oct data: of 26 drops of 1.0 mmol or more in 4 days, only 3 also had a flat 30 minutes and a small IOB: the
 * 8 Oct 03:24 compression low, the 8 Oct 21:24 fall and rebound, and a one-step shift on 5 Oct 12:01. Tonight's slow slide and
 * the two on Live (20:39, 21:31) do not qualify. There is no level cap.
 * That trigger alone raises NO note or alert. It starts a pending check ([confirm]): for 15 minutes after it the smoothed value must
 * stay at or below the level where the drop ended (plus 0.3 mmol of noise), and within 30 minutes of it the value must get to
 * 6.0 mmol or under. Only then are the note and the alert raised. A climb back, or 30 minutes without both, drops it silently.
 * The longer fall is only reported ([Suspect.fell30Mgdl]) and does not decide anything. The rebound check ([rebounded]) follows.
 * Series are (timestamp ms, mg/dL), oldest first, about one per minute: the UKF-smoothed series for [suspect], the raw series for
 * [rebounded].
 */
internal object CompressionDetector {

    /** The newest reading must be at least this far (1.0 mmol) under the reading 5 minutes earlier. */
    const val SUDDEN_DROP_MGDL = 18.0
    const val DROP_MINUTES = 5L

    /** The 30 minutes before the drop must stay within this swing (0.6 mmol) and have enough readings. */
    const val FLAT_MINUTES = 30L
    const val FLAT_SWING_MGDL = 10.8

    /** Lane B: a bigger drop (1.4 mmol) that is at least twice the steepest 5-minute fall of the 30 minutes before, which fell no more than 2.5 mmol in all. */
    const val STEEP_DROP_MGDL = 25.2
    const val STEEPEN_FACTOR = 2.0
    const val PRIOR_NET_FALL_MAX_MGDL = 45.0

    /** The drop must be at least this many times the fall the insulin on board explains over the same 5 minutes. */
    const val UNEXPLAINED_FACTOR = 3.0

    /** After the trigger the value must stay down this long (15 minutes), within this much (0.3 mmol) of the level the drop ended at. */
    const val SUSTAIN_MINUTES = 15L
    const val RECOVERY_TOLERANCE_MGDL = 5.4

    /** And within this long of the trigger (30 minutes) it must reach this level (6.0 mmol) or lower, or the trigger lapses. */
    const val CONFIRM_WINDOW_MINUTES = 30L
    const val CONFIRM_LEVEL_MGDL = 108.1
    private const val MIN_SUSTAIN_READINGS = 8

    /** Minutes of readings needed: the flat window, the 5-minute drop, a margin. */
    const val LOOKBACK_MINUTES = 45L

    enum class Pending { WAIT, CONFIRMED, CANCELLED }

    /** A rebound is a rise of at least this much within 10 minutes, starting near the low. */
    const val REBOUND_MGDL = 25.0
    const val REBOUND_START_NEAR_LOW_MGDL = 15.0

    /** A suspected event waits this long for a rebound, and a new suspicion this soon after the last is the same event. */
    const val PENDING_MINUTES = 120L
    const val SAME_EVENT_MINUTES = 40L

    private const val MINUTE = 60_000L
    private const val TEN_MIN = 10 * MINUTE
    private const val NEAREST_TOLERANCE = 90_000L
    private const val MIN_FLAT_READINGS = 20

    class Suspect(
        val fromMgdl: Double,
        val toMgdl: Double,
        val steepenedLane: Boolean,
        val steepestMgdlPerMin: Double,
        val fallMinutes: Long,
        val explainedMgdl: Double,
        val fell30Mgdl: Double,
    )

    private fun valueNear(series: List<Pair<Long, Double>>, time: Long): Double? =
        series.minByOrNull { abs(it.first - time) }?.takeIf { abs(it.first - time) <= NEAREST_TOLERANCE }?.second

    /**
     * A sudden compression-type drop at the newest reading, or null. [explainedFall5Mgdl] is the fall over 5 minutes the insulin on
     * board accounts for (positive, mg/dL; the loop's BGI with the sign dropped), 0 when unknown.
     */
    fun suspect(series: List<Pair<Long, Double>>, explainedFall5Mgdl: Double = 0.0): Suspect? {
        if (series.size < 20) return null
        val now = series.last().first
        val v = series.last().second
        val before = valueNear(series, now - DROP_MINUTES * MINUTE) ?: return null
        val drop = before - v
        if (drop < SUDDEN_DROP_MGDL) return null
        val explained = explainedFall5Mgdl.coerceAtLeast(0.0)
        if (drop < UNEXPLAINED_FACTOR * explained) return null
        // The 30 minutes ending where the drop began (inclusive of the reading 5 minutes ago).
        val flatEnd = now - DROP_MINUTES * MINUTE
        val flatFrom = flatEnd - FLAT_MINUTES * MINUTE
        val flat = series.filter { it.first in flatFrom..flatEnd }
        if (flat.size < MIN_FLAT_READINGS || flat.first().first > flatFrom + 5 * MINUTE) return null
        val values = flat.map { it.second }
        val laneA = values.max() - values.min() <= FLAT_SWING_MGDL
        var laneB = false
        if (!laneA && drop >= STEEP_DROP_MGDL) {
            // The steepest 5-minute fall inside that half hour, and how far it fell in all.
            val priorSteepest = flat.filter { it.first >= flatFrom + DROP_MINUTES * MINUTE }
                .mapNotNull { r -> valueNear(series, r.first - DROP_MINUTES * MINUTE)?.let { it - r.second } }
                .maxOrNull() ?: 0.0
            val priorNetFall = flat.first().second - flat.last().second
            laneB = drop >= STEEPEN_FACTOR * priorSteepest.coerceAtLeast(0.0) && priorNetFall <= PRIOR_NET_FALL_MAX_MGDL
        }
        if (!laneA && !laneB) return null
        val last5 = series.filter { it.first >= flatEnd }
        val steepest = last5.zipWithNext { a, b -> (b.second - a.second) / ((b.first - a.first) / MINUTE.toDouble()).coerceAtLeast(1.0) }.minOrNull() ?: 0.0
        val fell30 = (valueNear(series, now - FLAT_MINUTES * MINUTE) ?: values.first()) - v
        return Suspect(before, v, !laneA, steepest, DROP_MINUTES, explained, fell30)
    }

    /**
     * The state of a trigger seen at [triggerAt] that ended at [triggerLevelMgdl], judged on [series] up to [now] (smoothed, mg/dL).
     * CANCELLED when the value climbed back above the level plus the tolerance, or when 30 minutes passed without both conditions.
     * CONFIRMED when at least 15 minutes have passed with no climb back and the value has reached 6.0 mmol or under at some point.
     * WAIT otherwise, including when there are too few readings to judge.
     */
    fun confirm(series: List<Pair<Long, Double>>, triggerAt: Long, triggerLevelMgdl: Double, now: Long): Pending {
        val after = series.filter { it.first in triggerAt..now }
        if (after.any { it.second > triggerLevelMgdl + RECOVERY_TOLERANCE_MGDL }) return Pending.CANCELLED
        val elapsed = now - triggerAt
        val sustained = elapsed >= SUSTAIN_MINUTES * MINUTE && after.size >= MIN_SUSTAIN_READINGS
        if (sustained && after.any { it.second <= CONFIRM_LEVEL_MGDL }) return Pending.CONFIRMED
        return if (elapsed > CONFIRM_WINDOW_MINUTES * MINUTE) Pending.CANCELLED else Pending.WAIT
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
