package app.aaps.core.interfaces.automation

/**
 * Interface for the AutomationStateService to allow independent compilation
 * of automation and automation-state plugins
 */
interface AutomationStateInterface {
    fun inState(stateName: String, state: String): Boolean
    fun setState(stateName: String, state: String)
    fun getState(stateName: String): String
    fun getAllStates(): List<Pair<String, String>>
    fun getStateValues(stateName: String): List<String>
    fun setStateValues(stateName: String, values: List<String>)
    fun hasStateValues(stateName: String): Boolean
    fun deleteState(stateName: String)
}
