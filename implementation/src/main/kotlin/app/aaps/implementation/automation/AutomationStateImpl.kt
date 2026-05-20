package app.aaps.implementation.automation

import app.aaps.core.interfaces.automation.AutomationStateInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationStateImpl @Inject constructor() : AutomationStateInterface {

    private val states: MutableMap<String, String> = mutableMapOf()

    override fun getAllStateNames(): List<String> = states.keys.toList()

    override fun getAllStates(): List<Pair<String, String>> = states.toList()

    override fun getState(stateName: String): String = states[stateName] ?: ""

    override fun getStateValues(stateName: String): List<String> =
        states[stateName]?.let { listOf(it) } ?: emptyList()

    override fun setState(stateName: String, stateValue: String) {
        states[stateName] = stateValue
    }

    override fun setStateValues(stateName: String, values: List<String>) {
        // fallback impl: just ensure state exists with first value if not set
        if (values.isNotEmpty() && !states.containsKey(stateName))
            states[stateName] = values.first()
    }

    override fun hasStateValues(stateName: String): Boolean = states.containsKey(stateName)

    override fun deleteState(stateName: String) {
        states.remove(stateName)
    }

    override fun inState(stateName: String, stateValue: String): Boolean =
        states[stateName] == stateValue
}
