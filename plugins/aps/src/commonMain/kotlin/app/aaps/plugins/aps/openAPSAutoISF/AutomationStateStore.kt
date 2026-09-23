package app.aaps.plugins.aps.openAPSAutoISF

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Named states the loop can read and write. The current value and the allowed list are saved.
 * An unknown name or a value that is not in the list is rejected.
 */
internal class AutomationStateStore(
    currentJson: String,
    valuesJson: String,
    private val saveCurrent: (String) -> Unit,
    private val saveValues: (String) -> Unit,
) {
    private val json = Json
    private val current: MutableMap<String, String> = decodeMap(currentJson).toMutableMap()
    private val allowed: MutableMap<String, List<String>> = decodeLists(valuesJson).toMutableMap()

    fun inState(name: String, value: String): Boolean = current[name.trim()] == value.trim()

    fun getState(name: String): String = current[name.trim()].orEmpty()

    fun hasStateValues(name: String): Boolean = allowed.containsKey(name.trim())

    fun getStateValues(name: String): List<String> = allowed[name.trim()].orEmpty()

    fun getAllStates(): List<Pair<String, String>> =
        allowed.keys.map { name -> name to current[name].orEmpty() }

    fun setState(name: String, value: String) {
        val trimmedName = name.trim()
        val trimmedValue = value.trim()
        val choices = allowed[trimmedName]
        require(choices != null) { "Invalid state name: $trimmedName" }
        require(trimmedValue in choices) { "Invalid state value: $trimmedValue" }
        current[trimmedName] = trimmedValue
        saveCurrent(json.encodeToString(current))
    }

    fun setStateValues(name: String, values: List<String>) {
        val trimmedName = name.trim()
        val trimmedValues = values.map { it.trim() }
        val active = current[trimmedName]
        if (active != null && active !in trimmedValues) {
            current.remove(trimmedName)
            saveCurrent(json.encodeToString(current))
        }
        allowed[trimmedName] = trimmedValues
        saveValues(json.encodeToString(allowed))
    }

    fun deleteState(name: String) {
        val trimmedName = name.trim()
        current.remove(trimmedName)
        allowed.remove(trimmedName)
        saveCurrent(json.encodeToString(current))
        saveValues(json.encodeToString(allowed))
    }

    /** Adds any missing allowed values. Sets the default only when the state has no current value. */
    fun ensureDeclared(name: String, values: List<String>, defaultValue: String?) {
        if (!hasStateValues(name)) {
            setStateValues(name, values)
        } else {
            val existing = getStateValues(name)
            val missing = values.filter { it !in existing }
            if (missing.isNotEmpty()) setStateValues(name, existing + missing)
        }
        if (defaultValue != null && getState(name).isEmpty() && defaultValue in getStateValues(name)) {
            setState(name, defaultValue)
        }
    }

    private fun decodeMap(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrElse { emptyMap() }

    private fun decodeLists(raw: String): Map<String, List<String>> =
        runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }.getOrElse { emptyMap() }
}

internal data class RequiredAutomationState(
    val values: List<String>,
    val defaultValue: String? = null,
)

/**
 * One night FastRise skip. True only inside 00:30-04:00 when the rise is real and the safety states are off.
 * A missing raw change is passed as a large negative number so the skip stays off.
 */
internal fun nightFrSkipShouldFire(
    ready: Boolean,
    profilePercent: Int,
    tempTargetSet: Boolean,
    boostAutomationsOn: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    rawDelta5: Double,
    iob: Double,
    smbSum10: Double,
    lowBgRecent: Boolean,
    mjActive: Boolean,
    steps5: Int,
    steps30: Int,
): Boolean {
    val inWindow = minuteOfDay in 30 until 240
    return ready && profilePercent == 100 && !tempTargetSet && boostAutomationsOn && inWindow &&
        bg > 117.0 && bg <= 144.1 &&
        delta >= 4.5 && shortDelta >= 2.7 && rawDelta5 >= 4.5 &&
        iob <= 1.0 && smbSum10 < 0.6 &&
        !lowBgRecent && !mjActive &&
        steps5 <= 100 && steps30 <= 200
}

/** The names 3.2.1 reads. Defaults are the off values, so a new store does not look active. */
internal val requiredAutomationStates: Map<String, RequiredAutomationState> = mapOf(
    "MJ" to RequiredAutomationState(listOf("NOMJremains", "MJ active", "MJ2", "MJ3", "MJ4", "MJ5", "MJ6"), "NOMJremains"),
    "Steroids" to RequiredAutomationState(listOf("Steroids Off", "SteroidsON"), "Steroids Off"),
    "LowBG" to RequiredAutomationState(listOf("50recent", "NO50rec"), "NO50rec"),
    "AlarmHypo" to RequiredAutomationState(listOf("AlarmRecent", "NoAlarmRecent"), "NoAlarmRecent"),
    "Profile" to RequiredAutomationState(listOf("PP130", "C100", "AllOK", "Bolus", "HnAM"), "AllOK"),
    "Sleeping" to RequiredAutomationState(listOf("True")),
)
