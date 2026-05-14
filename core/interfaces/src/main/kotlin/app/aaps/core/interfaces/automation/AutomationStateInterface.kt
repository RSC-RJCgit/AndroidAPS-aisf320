package app.aaps.core.interfaces.automation

interface AutomationStateInterface {

    fun getAllStateNames(): List<String>

    fun getStateValues(stateName: String): List<String>

    fun setState(stateName: String, stateValue: String)

    fun inState(stateName: String, stateValue: String): Boolean
}
