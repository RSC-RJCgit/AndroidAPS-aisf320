package app.aaps.implementation.automation

import app.aaps.core.interfaces.automation.AutomationStateInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationStateImpl @Inject constructor() : AutomationStateInterface {

    private val states: MutableMap<String, String> = mutableMapOf()

    override fun getAllStateNames(): List<String> = states.keys.toList()

    override fun getStateValues(stateName: String): List<String> =
        states[stateName]?.let { listOf(it) } ?: emptyList()

    override fun setState(stateName: String, stateValue: String) {
        states[stateName] = stateValue
    }

    override fun inState(stateName: String, stateValue: String): Boolean =
        states[stateName] == stateValue
}
