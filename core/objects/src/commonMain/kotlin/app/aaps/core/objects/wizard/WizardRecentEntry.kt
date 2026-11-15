package app.aaps.core.objects.wizard

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

/**
 * Low-BG "recent entry" rule, from the 3426 app (2026-09-24 there; ported 2026-11-14, per explicit request).
 *
 * It applies when ALL of these hold:
 *  - the BG is under 6.0 mmol/L;
 *  - a normal bolus or a carb entry was made in the last 60 minutes;
 *  - every stored reading from 5 minutes before the first such entry until now is under 6.0 mmol/L.
 *
 * Then:
 *  - the Bolus Wizard's COB box starts unticked (COB still holds carbs that entry's bolus already covered, so counting it
 *    again doses twice). The user can tick it again for that bolus;
 *  - a QuickWizard press gets 80% of the max bolus that applies ([scaledMaxBolus]), the lower one if the falling-BG
 *    caution also cuts it.
 *
 * "Bolus" is a normal bolus over 0 U only (wizard, QuickWizard, their later parts, manual insulin): the loop's SMBs come
 * about once a minute and would make the rule true all the time under 6.0.
 */
object WizardRecentEntry {

    const val WINDOW_MINUTES = 60L
    const val LOW_BG_MGDL = 108.1 // 6.0 mmol/L
    const val MAX_BOLUS_SCALE = 0.8

    /** The earliest normal bolus or carb entry in the last [WINDOW_MINUTES], or null when there is none. */
    suspend fun firstEntryTime(persistenceLayer: PersistenceLayer, now: Long): Long? {
        val from = now - T.mins(WINDOW_MINUTES).msecs()
        val bolusTimes = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = true)
            .filter { it.type == BS.Type.NORMAL && it.amount > 0.0 }
            .map { it.timestamp }
        val carbTimes = persistenceLayer.getCarbsFromTimeToTimeExpanded(from, now, ascending = true)
            .filter { it.amount > 0.0 }
            .map { it.timestamp }
        return (bolusTimes + carbTimes).minOrNull()
    }

    /** True when every stored reading from 5 minutes before [firstEntry] until [now] is under 6.0 (no readings: false). */
    fun stayedLow(readingsMgdl: List<Double>): Boolean = readingsMgdl.isNotEmpty() && readingsMgdl.all { it < LOW_BG_MGDL }

    /** [bgMgdl] null or 0 (no BG at all) never starts the rule. A failed database read counts as not applying. */
    suspend fun applies(persistenceLayer: PersistenceLayer, now: Long, bgMgdl: Double?): Boolean {
        if (bgMgdl == null || bgMgdl <= 0.0 || bgMgdl >= LOW_BG_MGDL) return false
        return try {
            val first = firstEntryTime(persistenceLayer, now) ?: return false
            val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(first - T.mins(5).msecs(), now, ascending = true).map { it.value }
            stayedLow(readings)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** [base] x0.8, rounded DOWN to the pump step: never under one step, never over [base]. */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * MAX_BOLUS_SCALE / step + 1e-6) * step
        val rounded = round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }
}
