package app.aaps.core.objects.wizard

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Carb time from an ongoing rise, from the 3426 app (2026-09-26 there; ported 2026-11-14, per explicit request). A
 * QuickWizard press (and the Bolus Wizard when it opens) partway up a rise dates the carbs back to where the rise began.
 *
 * The estimate is where the rise began, from the stored BG readings, plus [LAG_MINUTES] (BG only reacts some minutes after
 * eating). It is an ESTIMATE: slow (fat / protein) carbs rise late, and a rise from another cause looks the same. Only when:
 *  - the latest reading is current (within 10 min) and BG is still rising (0.2 mmol/L or more over 5 min, up over 15 min);
 *  - BG is 1.5 mmol/L or more above where the rise began, at least 15 minutes ago (within 90 minutes);
 *  - no normal bolus or carb entry in the last 60 minutes ([WizardRecentEntry.firstEntryTime]), so an earlier meal is not
 *    taken for this one.
 * Rounded to 5 minutes and limited to -60 min.
 */
object CarbTimeFromRise {

    const val LAG_MINUTES = 10
    const val LOOKBACK_MINUTES = 90L
    const val MIN_RISE_MGDL = 27.0          // 1.5 mmol/L
    const val MIN_MINUTES_SINCE_ONSET = 15
    const val DIP_TOLERANCE_MGDL = 9.0      // 0.5 mmol/L: a bump this small is not the start of an earlier rise
    const val TROUGH_BAND_MGDL = 3.6        // 0.2 mmol/L: readings this close to the bottom still count as the bottom
    const val MIN_RECENT_DELTA5_MGDL = 3.6  // 0.2 mmol/L per 5 min

    data class Estimate(val offsetMinutes: Int, val minutesSinceOnset: Int, val riseMgdl: Double)

    /** [readings] are (timestamp ms, mg/dL) pairs in ascending time order. */
    fun estimateFromSeries(now: Long, readings: List<Pair<Long, Double>>): Estimate? {
        if (readings.size < 10) return null
        val last = readings.last()
        if (now - last.first > T.mins(10).msecs()) return null

        fun valueAround(ts: Long): Double? =
            readings.minByOrNull { abs(it.first - ts) }?.takeIf { abs(it.first - ts) <= T.mins(3).msecs() }?.second

        val v5 = valueAround(last.first - T.mins(5).msecs()) ?: return null
        val v15 = valueAround(last.first - T.mins(15).msecs()) ?: return null
        if (last.second - v5 < MIN_RECENT_DELTA5_MGDL || last.second <= v15) return null

        // Back from now while the series keeps falling going backwards (it was rising), allowing small bumps.
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
        // The rise began at the latest reading still at the bottom.
        var onsetIdx = troughIdx
        for (j in troughIdx..readings.lastIndex) {
            if (readings[j].second <= troughValue + TROUGH_BAND_MGDL) onsetIdx = j else break
        }
        val rise = last.second - troughValue
        if (rise < MIN_RISE_MGDL) return null
        val minutesSinceOnset = ((now - readings[onsetIdx].first) / 60_000L).toInt()
        if (minutesSinceOnset < MIN_MINUTES_SINCE_ONSET) return null

        val raw = -(minutesSinceOnset + LAG_MINUTES)
        val offset = ((raw / 5.0).roundToInt() * 5).coerceIn(-60, 0)
        if (offset == 0) return null
        return Estimate(offset, minutesSinceOnset, rise)
    }

    /** A failed database read gives no estimate. */
    suspend fun estimate(persistenceLayer: PersistenceLayer, now: Long): Estimate? = try {
        if (WizardRecentEntry.firstEntryTime(persistenceLayer, now) != null) null
        else {
            val from = now - T.mins(LOOKBACK_MINUTES + 15).msecs()
            val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, ascending = true).map { it.timestamp to it.value }
            estimateFromSeries(now, readings)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
