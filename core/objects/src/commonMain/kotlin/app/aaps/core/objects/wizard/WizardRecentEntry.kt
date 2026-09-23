package app.aaps.core.objects.wizard

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

/**
 * Starting value for the wizard's max-bolus field.
 *
 * Below 6.0 mmol/L the field starts at the saved limit, or at 2 U when the saved limit is higher.
 * A normal bolus or carb entry in the last hour, with glucose still under 6.0 mmol/L since then,
 * takes 20% off that starting value. The user can still change the field.
 */
fun wizardMaxBolusDefault(savedMaxBolus: Double, bgMgdl: Double, recentLowEntry: Boolean, bolusStep: Double): Double {
    val lowBgMax = if (bgMgdl < WizardRecentEntry.LOW_BG_MGDL) min(savedMaxBolus, 2.0) else savedMaxBolus
    return if (recentLowEntry) WizardRecentEntry.scaledMaxBolus(lowBgMax, bolusStep) else lowBgMax
}

object WizardRecentEntry {

    const val WINDOW_MINUTES = 60L
    const val LOW_BG_MGDL = 108.1

    suspend fun lowBgRecentEntry(persistenceLayer: PersistenceLayer, now: Long, bgMgdl: Double?): Boolean {
        if (bgMgdl == null || bgMgdl <= 0.0 || bgMgdl >= LOW_BG_MGDL) return false
        val first = firstEntryTime(persistenceLayer, now) ?: return false
        return stayedLowSince(persistenceLayer, first, now)
    }

    /** [base] times 0.8, rounded down to the pump step. Never above [base], and never below one step unless [base] is smaller. */
    fun scaledMaxBolus(base: Double, bolusStep: Double): Double {
        val step = if (bolusStep > 0.0) bolusStep else 0.05
        val scaled = floor(base * 0.8 / step + 1e-6) * step
        val rounded = round(scaled * 1000.0) / 1000.0
        return min(base, rounded.coerceAtLeast(min(base, step)))
    }

    private suspend fun firstEntryTime(persistenceLayer: PersistenceLayer, now: Long): Long? {
        val from = now - T.mins(WINDOW_MINUTES).msecs()
        val bolusTimes = persistenceLayer.getBolusesFromTimeToTime(from, now, ascending = true)
            .filter { it.type == BS.Type.NORMAL && it.amount > 0.0 }
            .map { it.timestamp }
        val carbTimes = persistenceLayer.getCarbsFromTimeToTimeExpanded(from, now, ascending = true)
            .filter { it.amount > 0.0 }
            .map { it.timestamp }
        return (bolusTimes + carbTimes).minOrNull()
    }

    private suspend fun stayedLowSince(persistenceLayer: PersistenceLayer, firstEntry: Long, now: Long): Boolean {
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(firstEntry - T.mins(5).msecs(), now, ascending = true)
        return readings.isNotEmpty() && readings.all { it.value < LOW_BG_MGDL }
    }
}
