package app.aaps.plugins.automationstate.services

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.automationstate.keys.AutomationStateStringKey
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationStateService  @Inject constructor(
    private val preferences: Preferences
) : AutomationStateInterface {

    private var automationStates: HashMap<String, String> = HashMap()
    var stateValues: HashMap<String, List<String>> = HashMap()
        private set

    init {
        val string = preferences.get(AutomationStateStringKey.AutomationCurrentStates)
        try {
            automationStates = Json.decodeFromString(string)
        } catch (e: Exception) {
            automationStates = HashMap()
        }

        val valuesString = preferences.get(AutomationStateStringKey.AutomationStateValues)
        try {
            stateValues = Json.decodeFromString(valuesString)
        } catch (e: Exception) {
            stateValues = HashMap()
        }
    }

   override fun inState(stateName: String, state: String): Boolean {
        if (automationStates.containsKey(stateName.trim())) {
            return automationStates[stateName.trim()] == state.trim()
        }
        return false
    }

    override fun setState(stateName: String, state: String) {
        val trimmedName = stateName.trim()
        val trimmedState = state.trim()

        // Validate that the state value is in the allowed list
        require(stateValues.containsKey(trimmedName) ) { "Invalid state name: $trimmedName" }
        require(stateValues[trimmedName]!!.contains(trimmedState)) { "Invalid state value: $trimmedState" }

        automationStates[trimmedName] = trimmedState
        preferences.put(AutomationStateStringKey.AutomationCurrentStates, Json.encodeToString(automationStates))
    }

    override fun getState(stateName: String):String {
        val trimmedName = stateName.trim()
        // Validate that the state value is in the allowed list
        //require(stateValues.containsKey(trimmedName) ) { "Invalid state name: $trimmedName" }
        try {
            return automationStates[trimmedName]!!
        } catch (e: Exception) {
            return ""
        }
    }

    override fun getAllStates(): List<Pair<String, String>> {
        return stateValues.keys.map { stateName -> Pair(stateName, automationStates[stateName] ?: "") }
    }

    fun clearStates() {
        automationStates.clear()
        preferences.put(AutomationStateStringKey.AutomationCurrentStates, "{}")
    }

   override fun getStateValues(stateName: String): List<String> {
        return stateValues[stateName.trim()] ?: emptyList()
    }

   override fun setStateValues(stateName: String, values: List<String>) {
        val trimmedName = stateName.trim()
        val trimmedValues = values.map { it.trim() }
        
        // If there's a current state value that's not in the new values list,
        // clear the current state
        val currentState = automationStates[trimmedName]
        if (currentState != null && !trimmedValues.contains(currentState)) {
            automationStates.remove(trimmedName)
            preferences.put(AutomationStateStringKey.AutomationCurrentStates, Json.encodeToString(automationStates))
        }
        
        stateValues[trimmedName] = trimmedValues
        preferences.put(AutomationStateStringKey.AutomationStateValues, Json.encodeToString(stateValues))
    }

   override fun hasStateValues(stateName: String): Boolean {
        return stateValues.containsKey(stateName.trim())
    }

   override fun deleteState(stateName: String) {
        val trimmedName = stateName.trim()
        automationStates.remove(trimmedName)
        stateValues.remove(trimmedName)
       preferences.put(AutomationStateStringKey.AutomationCurrentStates, Json.encodeToString(automationStates))
       preferences.put(AutomationStateStringKey.AutomationStateValues, Json.encodeToString(stateValues))
    }
}
