package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

/**
 * Compression-low detector (2026-10-08, notes only). A pressure artefact on the sensor shows up in the raw readings as a
 * fall that is far too sharp for insulin: flat, then several mg/dL per minute for a few minutes, then often a sharp
 * rebound when the pressure is released. Readings are (timestamp ms, mg/dL), oldest first, about one per minute.
 */
internal object CompressionDetector {

    /** A fall of at least this much over the last 10 minutes... */
    const val DROP_MGDL = 30.0

    /** ...after a flat spell: the 10 minutes before that window changed by no more than this... */
    const val FLAT_PRIOR_MGDL = 12.0

    /** ...with at least one minute falling this fast (mg/dL per minute). */
    const val STEEPEST_MGDL_PER_MIN = -4.0

    /**
     * 2026-10-08: the low must be under 7.0 mmol/L (126.1 mg/dL). 8 Oct 12:18 a sensor swing (9.7 down to 7.8 mmol, back up within
     * 5 minutes) passed the fall test at a normal glucose.
     */
    const val FLOOR_MGDL = 126.1

    /**
     * 2026-10-08: a pressure low stays down until the pressure is released, a noise swing comes straight back. "Suspected" is only
     * declared once the fall started 15 to 20 minutes ago and every reading since has stayed at least [SUSTAINED_BELOW_PRIOR_MGDL]
     * under the level before the fall.
     */
    const val SUSTAIN_MINUTES = 15L
    const val SUSTAIN_SEARCH_MINUTES = 5L
    const val SUSTAINED_BELOW_PRIOR_MGDL = 20.0

    /** Readings needed: 20 minutes before the fall test, plus the sustained wait. */
    const val LOOKBACK_MINUTES = 45L

    /** A rebound is a rise of at least this much within 10 minutes, starting near the low. */
    const val REBOUND_MGDL = 25.0
    const val REBOUND_START_NEAR_LOW_MGDL = 15.0

    /** A suspected event waits this long for a rebound, and a new suspicion this soon after the last is the same event. */
    const val PENDING_MINUTES = 120L
    const val SAME_EVENT_MINUTES = 40L

    private const val TEN_MIN = 10 * 60_000L
    private const val NEAREST_TOLERANCE = 90_000L
    private const val MAX_GAP = 150_000L

    class Suspect(val fromMgdl: Double, val toMgdl: Double, val steepestMgdlPerMin: Double)

    private fun nearest(readings: List<Pair<Long, Double>>, time: Long): Double? =
        readings.minByOrNull { abs(it.first - time) }?.takeIf { abs(it.first - time) <= NEAREST_TOLERANCE }?.second

    /** A clean compression-type fall that has just reached the newest reading, or null. */
    fun suspect(readings: List<Pair<Long, Double>>): Suspect? {
        if (readings.size < 8) return null
        val newest = readings.last()
        if (newest.second > FLOOR_MGDL) return null
        val v10 = nearest(readings, newest.first - TEN_MIN) ?: return null
        val v20 = nearest(readings, newest.first - 2 * TEN_MIN) ?: return null
        if (newest.second - v10 > -DROP_MGDL) return null
        if (abs(v10 - v20) > FLAT_PRIOR_MGDL) return null
        var steepest = 0.0
        for (i in 1 until readings.size) {
            val (t0, a) = readings[i - 1]
            val (t1, b) = readings[i]
            if (t1 < newest.first - TEN_MIN || t1 - t0 > MAX_GAP || t1 <= t0) continue
            steepest = minOf(steepest, (b - a) / ((t1 - t0) / 60_000.0))
        }
        if (steepest > STEEPEST_MGDL_PER_MIN) return null
        return Suspect(v10, newest.second, steepest)
    }

    /**
     * A fall that [suspect] accepted [SUSTAIN_MINUTES] to [SUSTAIN_MINUTES] + [SUSTAIN_SEARCH_MINUTES] minutes before the newest
     * reading and that has stayed [SUSTAINED_BELOW_PRIOR_MGDL] or more under its pre-fall level since, or null. This is what
     * "Compression suspected" now waits for.
     */
    fun sustainedSuspect(readings: List<Pair<Long, Double>>): Suspect? {
        val newest = readings.lastOrNull() ?: return null
        val latest = newest.first - SUSTAIN_MINUTES * 60_000L
        val earliest = latest - SUSTAIN_SEARCH_MINUTES * 60_000L
        for (cut in readings.indices.reversed()) {
            val t = readings[cut].first
            if (t > latest) continue
            if (t < earliest) break
            val fall = suspect(readings.subList(0, cut + 1)) ?: continue
            val since = readings.subList(cut + 1, readings.size)
            if (since.isNotEmpty() && since.all { it.second <= fall.fromMgdl - SUSTAINED_BELOW_PRIOR_MGDL }) return fall
        }
        return null
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
