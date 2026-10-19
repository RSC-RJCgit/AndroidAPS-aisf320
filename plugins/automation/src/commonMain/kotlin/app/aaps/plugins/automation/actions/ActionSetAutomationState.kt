package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reorder
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AutoISF 3.2.1: sets a named automation state, for example "Sleeping" to "True". */
class ActionSetAutomationState(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val automationState: AutomationStateInterface
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var stateName = ""
    var stateValue = ""

    /** The states that exist, for the editor. */
    fun stateNames(): List<String> = automationState.getAllStates().map { it.first }.distinct()

    /** The values of [name], for the editor. */
    fun stateValues(name: String): List<String> = if (name.isEmpty()) emptyList() else automationState.getStateValues(name)

    override fun friendlyName(): TextRef = AutomationStrings.set_state
    override fun shortDescription(): String = rh.gs(AutomationStrings.set_state_description, stateName, stateValue)
    override fun composeIcon() = Icons.Filled.Reorder

    override fun isValid(): Boolean = stateName.isNotEmpty() && stateValue.isNotEmpty()

    override suspend fun doAction(): PumpEnactResult {
        if (!automationState.enabled) return pumpEnactResultProvider().success(false).comment(AutomationStrings.automation_states_off)
        // 3.2.1 threw when the state or value no longer existed. Report it as a failed action instead.
        return try {
            automationState.setState(stateName, stateValue)
            pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
        } catch (e: IllegalArgumentException) {
            pumpEnactResultProvider().success(false).comment(e.message ?: "")
        }
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject {
            put("inputStateName", stateName)
            put("inputState", stateValue)
        }
        return buildJsonObject {
            put("type", this@ActionSetAutomationState::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        val o = jsonOf(data)
        stateName = o.lenientString("inputStateName", "")
        stateValue = o.lenientString("inputState", "")
        return this
    }
}
