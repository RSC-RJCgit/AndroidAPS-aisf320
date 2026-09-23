package app.aaps.core.objects.wizard

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.math.floor
import kotlin.math.min

/**
 * Low-BG "recent entry" rule shared by the Bolus Wizard dialog, QuickWizard and BolusWizard.doCalc()
 * (added 2026-09-24, per explicit request; window/staying-low condition revised the same day).
 *
 * The rule applies when ALL of these hold:
 *  - the current BG is < 6.0 mmol;
 *  - the FIRST normal bolus or carb entry in the last 60 minutes exists;
 *  - BG was < 6.0 when that first entry was made and has stayed < 6.0 ever since (every stored reading
 *    from 5 min before the entry until now).
 *
 * When it applies:
 *  - COB is not used in the calculator (COB then still contains the carbs the earlier entry's bolus
 *    already covered, so counting it again double-doses). Bolus Wizard dialog and QuickWizard both show
 *    a COB box that starts UNticked; the user can re-tick it for that bolus.
 *  - the max bolus in force is cut to 80% (x0.8 of whatever applies at that moment, e.g. the dialog's
 *    BG<6 2.0U session limit: 3.0 -> 2.0 -> 1.6). Both the dialog and the QuickWizard limit box show it,
 *    and the user can change it for that bolus.
 *
 * "Bolus" is a NORMAL bolus (wizard, QuickWizard, their split parts, manual insulin) only -- loop SMBs
 * arrive about once a minute and would make the rule permanently true at BG<6.
 */
object WizardRecentEntry {
    const val WINDOW_MINUTES = 60L
    const val LOW_BG_MGDL = 108.1 // 6.0 mmol
    const val MAX_BOLUS_SCALE = 0.8

    /** Timestamp of the earliest normal bolus or carb entry in the last [WINDOW_MINUTES], or null if none. */
    fun firstEntryTime(persistenceLayer: PersistenceLayer, now: Long): Long? {
        val from = now - T.mins(WINDOW_MINUTES).msecs()
        val bolusTimes = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = true)
            .filter { it.type == BS.Type.NORMAL && it.amount > 0.0 }
            .map { it.timestamp }
        val carbTimes = persistenceLayer.getCarbsFromTimeToTimeExpanded(from, now, ascending = true)
            .filter { it.amount > 0.0 }
            .map { it.timestamp }
        return (bolusTimes + carbTimes).minOrNull()
    }

    /** True if every stored BG reading from 5 min before [firstEntry] until [now] is below 6.0 mmol (none = false). */
    fun stayedLowSince(persistenceLayer: PersistenceLayer, firstEntry: Long, now: Long): Boolean {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(firstEntry - T.mins(5).msecs(), now, ascending = true)
        return readings.isNotEmpty() && readings.all { it.value < LOW_BG_MGDL }
    }

    /** [bgMgdl] null or <= 0 (no BG at all) never triggers the rule. */
    fun lowBgRecentEntry(persistenceLayer: PersistenceLayer, now: Long, bgMgdl: Double?): Boolean {
        if (bgMgdl == null || bgMgdl <= 0.0 || bgMgdl >= LOW_BG_MGDL) return false
        val first = firstEntryTime(persistenceLayer, now) ?: return false
        return stayedLowSince(persistenceLayer, first, now)
    }

    /** [base] x0.8, rounded DOWN to the pump step (never below one step, never above [base]). */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * MAX_BOLUS_SCALE / step + 1e-6) * step
        val rounded = Math.round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }
}
