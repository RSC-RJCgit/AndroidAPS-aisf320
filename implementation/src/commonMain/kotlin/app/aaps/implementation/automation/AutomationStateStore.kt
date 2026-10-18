package app.aaps.implementation.automation

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.keys.StringNonKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The automation states and their allowed values (AutoISF 3.2.1), kept as JSON in two strings.
 * [read] and [save] reach the storage, so this can be tested without the preferences.
 */
class AutomationStateStore(
    private val read: (StringNonKey) -> String,
    private val save: (StringNonKey, String) -> Unit,
) : AutomationStateInterface {

    private val statesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val valuesSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private val automationStates: HashMap<String, String> =
        try {
            HashMap(Json.decodeFromString(statesSerializer, read(StringNonKey.AutomationCurrentStates)))
        } catch (_: Exception) {
            HashMap()
        }

    private val stateValues: HashMap<String, List<String>> =
        try {
            HashMap(Json.decodeFromString(valuesSerializer, read(StringNonKey.AutomationStateValues)))
        } catch (_: Exception) {
            HashMap()
        }

    private val lock = AapsLock()

    override fun inState(stateName: String, state: String): Boolean = lock.withLock {
        automationStates[stateName.trim()]?.let { it == state.trim() } ?: false
    }

    override fun setState(stateName: String, state: String) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedState = state.trim()
        require(stateValues.containsKey(trimmedName)) { "Invalid state name: $trimmedName" }
        require(stateValues[trimmedName]!!.contains(trimmedState)) { "Invalid state value: $trimmedState" }
        automationStates[trimmedName] = trimmedState
        saveStates()
    }

    override fun getState(stateName: String): String = lock.withLock { automationStates[stateName.trim()] ?: "" }

    override fun getAllStates(): List<Pair<String, String>> = lock.withLock {
        stateValues.keys.map { stateName -> Pair(stateName, automationStates[stateName] ?: "") }
    }

    override fun getStateValues(stateName: String): List<String> = lock.withLock { stateValues[stateName.trim()] ?: emptyList() }

    override fun setStateValues(stateName: String, values: List<String>) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedValues = values.map { it.trim() }
        val currentState = automationStates[trimmedName]
        if (currentState != null && !trimmedValues.contains(currentState)) {
            automationStates.remove(trimmedName)
            saveStates()
        }
        stateValues[trimmedName] = trimmedValues
        saveValues()
    }

    override fun hasStateValues(stateName: String): Boolean = lock.withLock { stateValues.containsKey(stateName.trim()) }

    override fun deleteState(stateName: String) = lock.withLock {
        val trimmedName = stateName.trim()
        automationStates.remove(trimmedName)
        stateValues.remove(trimmedName)
        saveStates()
        saveValues()
    }

    private fun saveStates() = save(StringNonKey.AutomationCurrentStates, Json.encodeToString(statesSerializer, automationStates))
    private fun saveValues() = save(StringNonKey.AutomationStateValues, Json.encodeToString(valuesSerializer, stateValues))
}
