package app.aaps.core.objects.wizard

import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The protein/fat (FPU) parts, leftover carb-split checks and delayed bolus (Db) checks still waiting, saved so they survive an
 * app restart (2026-11-22, per explicit request). Their coroutines only live in this process: on Live (3426 app) 22 Nov an app
 * install restarted it after fpu1 of 8, and fpu2-fpu8 (about 3.4 U) were dropped with no dose and no note.
 *
 * Each check saves itself when it starts and removes itself when it ends normally; a check that schedules the next one saves
 * that one first. So what is saved is always the next check of each live part. [restoreAtStart], at app start before anything
 * can record a bolus, puts back the three schedule tokens and the super heavy activity cancel and takes the saved checks;
 * [resumeAll] later hands them back to [BolusWizard]. Each then runs all its usual checks; one whose window ended while the app
 * was down is cancelled with its usual note, so nothing disappears without a trace. Doses handed to Trio are not saved.
 */
@OptIn(ExperimentalAtomicApi::class)
object PendingFollowUps {

    private val mutex = Mutex()
    private val ids = AtomicLong(0)

    /** A key of its own for every check, so a check ending never removes the one it scheduled. */
    fun newKey(token: Long, kind: String): String = "$token|$kind|${ids.addAndFetch(1)}"

    suspend fun save(preferences: Preferences, key: String, entry: JsonObject) = mutex.withLock {
        write(preferences, read(preferences) + (key to entry))
    }

    suspend fun remove(preferences: Preferences, key: String) = mutex.withLock {
        val all = read(preferences)
        if (key in all) write(preferences, all - key)
    }

    /**
     * Call once at app start, before anything can record a bolus or carbs. The tokens then keep counting up from where they
     * were (a saved check keeps its own token, a later entry still supersedes it), every new token is saved from now on, and
     * the saved checks are taken out so that what is scheduled from now on is not resumed twice.
     */
    suspend fun restoreAtStart(preferences: Preferences): List<JsonObject> {
        WarsawScheduleGate.restore(preferences.get(LongNonKey.WizardWarsawScheduleToken))
        WarsawScheduleGate.onNext = { preferences.put(LongNonKey.WizardWarsawScheduleToken, it) }
        SplitScheduleGate.restore(preferences.get(LongNonKey.WizardSplitScheduleToken))
        SplitScheduleGate.onNext = { preferences.put(LongNonKey.WizardSplitScheduleToken, it) }
        DelayedScheduleGate.restore(preferences.get(LongNonKey.WizardDelayedScheduleToken))
        DelayedScheduleGate.onNext = { preferences.put(LongNonKey.WizardDelayedScheduleToken, it) }
        val cancelled = preferences.get(LongNonKey.WizardSuperHeavyCancelledToken)
        if (cancelled != 0L) SuperHeavyFpuCancel.cancel(cancelled)
        return mutex.withLock {
            val all = read(preferences)
            if (all.isNotEmpty()) preferences.put(StringNonKey.WizardPendingFollowUps, "")
            all.values.toList()
        }
    }

    /** Super heavy activity cancelled the FPU parts of [token]; kept so the cancel holds after a restart. */
    fun saveSuperHeavyCancel(preferences: Preferences, token: Long) {
        SuperHeavyFpuCancel.cancel(token)
        preferences.put(LongNonKey.WizardSuperHeavyCancelledToken, token)
    }

    /** Hands each check taken by [restoreAtStart] back to a [BolusWizard] of its own (for its bolus time in the notes). */
    fun resumeAll(entries: List<JsonObject>, newWizard: () -> BolusWizard): Int {
        entries.forEach { newWizard().resumePendingPart(it) }
        return entries.size
    }

    /** The kind of a saved check, for the log. */
    fun kindOf(entry: JsonObject): String = entry["kind"]?.jsonPrimitive?.contentOrNull ?: "?"

    /** The schedule token of a saved check. */
    fun tokenOf(entry: JsonObject): Long = entry["token"]?.jsonPrimitive?.longOrNull ?: 0L

    private fun read(preferences: Preferences): Map<String, JsonObject> =
        runCatching { Json.parseToJsonElement(preferences.get(StringNonKey.WizardPendingFollowUps)).jsonObject.mapValues { it.value.jsonObject } }
            .getOrElse { emptyMap() }

    private fun write(preferences: Preferences, all: Map<String, JsonObject>) =
        preferences.put(StringNonKey.WizardPendingFollowUps, if (all.isEmpty()) "" else JsonObject(all).toString())
}
