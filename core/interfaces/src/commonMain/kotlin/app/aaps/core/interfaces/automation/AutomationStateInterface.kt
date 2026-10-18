package app.aaps.core.interfaces.automation

/**
 * Named automation states (AutoISF 3.2.1), for example "Sleeping" with the values "True" and "False".
 * Automations set and test them, and the AutoISF activity monitor reads "Sleeping".
 */
interface AutomationStateInterface {

    /** True when the state [stateName] has the value [state]. */
    fun inState(stateName: String, state: String): Boolean

    /**
     * Sets the state [stateName] to [state].
     * @throws IllegalArgumentException when the state does not exist or [state] is not one of its values
     */
    fun setState(stateName: String, state: String)

    /** The current value of [stateName], or "" when it has none. */
    fun getState(stateName: String): String

    /** Every state with its current value ("" when it has none). */
    fun getAllStates(): List<Pair<String, String>>

    /** The values [stateName] can have. Empty when the state does not exist. */
    fun getStateValues(stateName: String): List<String>

    /** Creates [stateName] or changes its values. A current value that is no longer allowed is cleared. */
    fun setStateValues(stateName: String, values: List<String>)

    /** True when the state [stateName] exists. */
    fun hasStateValues(stateName: String): Boolean

    /** Deletes [stateName] and its values. */
    fun deleteState(stateName: String)
}
