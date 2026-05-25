package app.aaps.implementation.automation

import app.aaps.core.interfaces.automation.AutomationStateInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationStateImpl @Inject constructor() : AutomationStateInterface {

    private val states: MutableMap<String, String> = mutableMapOf()
    private val stateValues: MutableMap<String, List<String>> = mutableMapOf()

    override fun getAllStateNames(): List<String> = states.keys.toList()

    override fun getAllStates(): List<Pair<String, String>> = states.map { it.key to it.value }

    override fun getState(stateName: String): String = states[stateName] ?: ""

    override fun getStateValues(stateName: String): List<String> = stateValues[stateName] ?: emptyList()

    override fun setStateValues(stateName: String, values: List<String>) {
        stateValues[stateName] = values
    }

    override fun hasStateValues(stateName: String): Boolean = stateValues.containsKey(stateName)

    override fun setState(stateName: String, state: String) {
        states[stateName] = state
    }

    override fun inState(stateName: String, state: String): Boolean = states[stateName] == state

    override fun deleteState(stateName: String) {
        states.remove(stateName)
        stateValues.remove(stateName)
    }
}
