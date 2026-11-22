package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.InputString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "State name = value", from the v3 AutoISF fork (2026-11-22, per explicit request): v4 had no such trigger, so a v3 rule using
 * it read as unknown after the in-place update and never fired. The stored form ("stateName", "stateValue") is v3's, unchanged.
 * It reads the same saved states the AutoISF plugin keeps (AutomationCurrentStates / AutomationStateValues), and is false while
 * automation states are turned off, as in v3.
 */
class TriggerAutomationState(deps: TriggerDeps) : Trigger(deps) {

    var stateName = InputString()
    var stateValue = InputString()

    private constructor(deps: TriggerDeps, other: TriggerAutomationState) : this(deps) {
        stateName.value = other.stateName.value
        stateValue.value = other.stateValue.value
    }

    override suspend fun shouldRun(): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) {
            aapsLogger.debug(LTag.AUTOMATION, "AutomationStates disabled, NOT running: " + friendlyDescription())
            return false
        }
        val name = stateName.value.trim()
        val current = currentStates()[name]
        val doRun = name.isNotEmpty() && current != null && current == stateValue.value.trim()
        aapsLogger.debug(LTag.AUTOMATION, (if (doRun) "Ready for execution: " else "NOT ready for execution: ") + friendlyDescription())
        return doRun
    }

    /** The state names the editor offers: every state with a list of values. */
    fun stateNames(): List<String> = allowedValues().keys.sorted()

    /** The values the editor offers for [name]. */
    fun stateValues(name: String): List<String> = allowedValues()[name.trim()].orEmpty()

    private fun currentStates(): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(preferences.get(StringNonKey.AutomationCurrentStates)) }.getOrElse { emptyMap() }

    private fun allowedValues(): Map<String, List<String>> =
        runCatching { Json.decodeFromString<Map<String, List<String>>>(preferences.get(StringNonKey.AutomationStateValues)) }.getOrElse { emptyMap() }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("stateName", stateName.value)
            put("stateValue", stateValue.value)
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        stateName.value = d.lenientStringOrNull("stateName") ?: ""
        stateValue.value = d.lenientStringOrNull("stateValue") ?: ""
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.check_state_name

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.check_state_description, stateName.value, stateValue.value)

    override fun duplicate(): Trigger = TriggerAutomationState(deps, this)
}
