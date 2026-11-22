package app.aaps.core.objects.wizard

import app.aaps.core.interfaces.pump.ScheduledDoseSupersession
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject

/**
 * The carb-split and protein/fat (FPU) parts still waiting, saved so they survive an app restart (2026-11-22, per explicit
 * request). Their timers only live in memory: on Live 22 Nov the 1574 install restarted the app after fpu1 of 8, and fpu2-fpu8
 * (about 3.4 U, due 13:01-16:01) were dropped with no dose and no note.
 *
 * BolusWizard saves a part each time it schedules its next check and removes it when that check runs; a check that reschedules
 * saves it again, so what is saved is always the next check of each live part. At app start [restoreAtStart] puts back the
 * schedule token (so "a newer entry was recorded" still works across the restart) and the super heavy activity cancel and takes
 * the saved parts, and [resumeAll] later hands them back to their scheduler. Each part then goes through all its usual checks; one whose retry
 * window ended while the app was down is cancelled with its usual note, so nothing disappears without a trace.
 */
object PendingFollowUps {

    private val lock = Any()

    fun save(preferences: Preferences, key: String, entry: JSONObject) = synchronized(lock) {
        val all = read(preferences)
        all.put(key, entry)
        preferences.put(StringNonKey.ApsAutoIsfPendingFollowUpDoses, all.toString())
    }

    fun remove(preferences: Preferences, key: String) = synchronized(lock) {
        val all = read(preferences)
        if (all.has(key)) {
            all.remove(key)
            preferences.put(StringNonKey.ApsAutoIsfPendingFollowUpDoses, all.toString())
        }
    }

    /**
     * Call once at app start, before anything can record a bolus or carbs. Tokens then keep counting up from where they were,
     * so a saved part keeps its own token and a later entry still supersedes it, and every new token is saved from now on. The
     * saved parts are taken out now, so that a part scheduled from now on is not resumed twice.
     */
    fun restoreAtStart(preferences: Preferences): List<JSONObject> {
        ScheduledDoseSupersession.restore(preferences.get(LongNonKey.ApsAutoIsfDoseScheduleToken))
        ScheduledDoseSupersession.onBump = { preferences.put(LongNonKey.ApsAutoIsfDoseScheduleToken, it) }
        val cancelled = preferences.get(LongNonKey.ApsAutoIsfSuperHeavyCancelledToken)
        if (cancelled != 0L) SuperHeavyFpuCancel.cancel(cancelled)
        return synchronized(lock) {
            val all = read(preferences)
            if (all.length() > 0) preferences.put(StringNonKey.ApsAutoIsfPendingFollowUpDoses, "")
            all.keys().asSequence().mapNotNull { all.optJSONObject(it) }.toList()
        }
    }

    /** Super heavy activity cancelled the FPU parts of [token]; kept so the cancel holds after a restart. */
    fun saveSuperHeavyCancel(preferences: Preferences, token: Long) {
        SuperHeavyFpuCancel.cancel(token)
        preferences.put(LongNonKey.ApsAutoIsfSuperHeavyCancelledToken, token)
    }

    /** Hands each part taken by [restoreAtStart] back to a [BolusWizard] (one per schedule token, for the notes' bolus time). */
    fun resumeAll(entries: List<JSONObject>, newWizard: () -> BolusWizard): Int {
        val wizards = mutableMapOf<Long, BolusWizard>()
        entries.forEach { entry ->
            val token = entry.optLong("token")
            wizards.getOrPut(token) { newWizard() }.resumePendingPart(entry)
        }
        return entries.size
    }

    private fun read(preferences: Preferences): JSONObject =
        runCatching { JSONObject(preferences.get(StringNonKey.ApsAutoIsfPendingFollowUpDoses)) }.getOrElse { JSONObject() }
}
