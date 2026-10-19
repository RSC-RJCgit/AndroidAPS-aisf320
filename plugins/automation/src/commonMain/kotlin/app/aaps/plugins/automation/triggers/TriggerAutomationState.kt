package app.aaps.plugins.automation.triggers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reorder
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AutoISF 3.2.1: true when a named automation state has a value, for example "Sleeping" is "True". */
class TriggerAutomationState(
    deps: TriggerDeps,
    private val automationState: AutomationStateInterface
) : Trigger(deps) {

    var stateName = ""
    var stateValue = ""

    constructor(deps: TriggerDeps, automationState: AutomationStateInterface, other: TriggerAutomationState) : this(deps, automationState) {
        stateName = other.stateName
        stateValue = other.stateValue
    }

    /** The states that exist, for the editor. */
    fun stateNames(): List<String> = automationState.getAllStates().map { it.first }.distinct()

    /** The values of [name], for the editor. */
    fun stateValues(name: String): List<String> = if (name.isEmpty()) emptyList() else automationState.getStateValues(name)

    override suspend fun shouldRun(): Boolean {
        if (!automationState.enabled) {
            aapsLogger.debug(LTag.AUTOMATION, "Automation states are off, NOT ready for execution: " + friendlyDescription())
            return false
        }
        if (automationState.inState(stateName, stateValue)) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("stateName", stateName)
            put("stateValue", stateValue)
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        stateName = d.lenientString("stateName", "")
        stateValue = d.lenientString("stateValue", "")
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.check_state_name

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.check_state_description, stateName, stateValue)

    override fun composeIcon() = Icons.Filled.Reorder

    override fun duplicate(): Trigger = TriggerAutomationState(deps, automationState, this)
}
