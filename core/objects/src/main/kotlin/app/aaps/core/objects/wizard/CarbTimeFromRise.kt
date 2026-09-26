package app.aaps.core.objects.wizard

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Carb-time estimate from an ongoing rise (added 2026-09-26, per explicit request), used to pre-fill the Bolus Wizard
 * dialog's carb-time box when the wizard is opened partway up a significant rise.
 *
 * The estimate is where the current rise began (looking back at the stored BG readings) plus a physiological lag of
 * [LAG_MINUTES], because BG only reacts some minutes after carbs are eaten. It is an ESTIMATE: slow (fat / protein) carbs
 * rise late so the suggestion is then too recent, and a rise from another cause (stress, a failing site or sensor) looks
 * the same. It is only offered when:
 *  - the latest reading is current (within 10 min) and BG is still rising (>= 0.2 mmol over the last 5 min, up over 15 min);
 *  - BG is at least 1.5 mmol above where the rise started, and that start was at least 15 min ago (within 90 min);
 *  - no normal bolus or carb entry exists in the last 60 min (see [WizardRecentEntry.firstEntryTime]), so an earlier meal is
 *    not mistaken for this one.
 * The result is rounded to the dialog's 5-minute step and limited to its -60 min floor.
 */
object CarbTimeFromRise {
    const val LAG_MINUTES = 10
    const val LOOKBACK_MINUTES = 90L
    const val MIN_RISE_MGDL = 27.0          // 1.5 mmol
    const val MIN_MINUTES_SINCE_ONSET = 15
    const val DIP_TOLERANCE_MGDL = 9.0      // 0.5 mmol: a bump this small is not the start of an earlier rise
    const val TROUGH_BAND_MGDL = 3.6        // 0.2 mmol: readings this close to the trough count as "still at the bottom"
    const val MIN_RECENT_DELTA5_MGDL = 3.6  // 0.2 mmol / 5 min

    data class Estimate(val offsetMinutes: Int, val minutesSinceOnset: Int, val riseMgdl: Double)

    /** Pure core, testable: [readings] are (timestamp ms, mg/dL) pairs in ascending time order. */
    fun estimateFromSeries(now: Long, readings: List<Pair<Long, Double>>): Estimate? {
        if (readings.size < 10) return null
        val last = readings.last()
        if (now - last.first > T.mins(10).msecs()) return null

        fun valueAround(ts: Long): Double? =
            readings.minByOrNull { abs(it.first - ts) }?.takeIf { abs(it.first - ts) <= T.mins(3).msecs() }?.second

        val v5 = valueAround(last.first - T.mins(5).msecs()) ?: return null
        val v15 = valueAround(last.first - T.mins(15).msecs()) ?: return null
        if (last.second - v5 < MIN_RECENT_DELTA5_MGDL || last.second <= v15) return null

        // Walk back from now while the series keeps falling going backwards (i.e. was rising), tolerating small bumps.
        var troughValue = last.second
        var troughIdx = readings.lastIndex
        var i = readings.lastIndex
        while (i >= 0) {
            val (ts, v) = readings[i]
            if (last.first - ts > T.mins(LOOKBACK_MINUTES).msecs()) break
            if (v > troughValue + DIP_TOLERANCE_MGDL) break
            if (v <= troughValue) {
                troughValue = v
                troughIdx = i
            }
            i--
        }
        // Onset = the latest reading still at the trough level (the rise leaves the bottom from here).
        var onsetIdx = troughIdx
        for (j in troughIdx..readings.lastIndex) {
            if (readings[j].second <= troughValue + TROUGH_BAND_MGDL) onsetIdx = j else break
        }
        val rise = last.second - troughValue
        if (rise < MIN_RISE_MGDL) return null
        val minutesSinceOnset = ((now - readings[onsetIdx].first) / 60_000L).toInt()
        if (minutesSinceOnset < MIN_MINUTES_SINCE_ONSET) return null

        val raw = -(minutesSinceOnset + LAG_MINUTES)
        val rounded = (raw / 5.0).roundToInt() * 5
        val offset = rounded.coerceIn(-60, 0)
        if (offset == 0) return null
        return Estimate(offset, minutesSinceOnset, rise)
    }

    fun estimate(persistenceLayer: PersistenceLayer, now: Long): Estimate? {
        if (WizardRecentEntry.firstEntryTime(persistenceLayer, now) != null) return null
        val from = now - T.mins(LOOKBACK_MINUTES + 15).msecs()
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, ascending = true)
            .map { it.timestamp to it.value }
        return estimateFromSeries(now, readings)
    }
}
