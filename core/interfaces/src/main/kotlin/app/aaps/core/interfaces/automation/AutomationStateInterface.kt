package app.aaps.core.interfaces.automation

interface AutomationStateInterface {

    /** Check if a state currently has the given value */
    fun inState(stateName: String, stateValue: String): Boolean

    /** Set a state to a specific value (must be in allowed values list) */
    fun setState(stateName: String, stateValue: String)

    /** Get current value of a state; returns "" if not set */
    fun getState(stateName: String): String

    /** Get all state names that have defined values */
    fun getAllStateNames(): List<String>

    /** Get all states and their current values as name/value pairs */
    fun getAllStates(): List<Pair<String, String>>

    /** Get the list of allowed values for a state */
    fun getStateValues(stateName: String): List<String>

    /** Set the allowed values for a state */
    fun setStateValues(stateName: String, values: List<String>)

    /** Return true if the state has a defined values list */
    fun hasStateValues(stateName: String): Boolean

    /** Delete a state and its allowed values */
    fun deleteState(stateName: String)
}
