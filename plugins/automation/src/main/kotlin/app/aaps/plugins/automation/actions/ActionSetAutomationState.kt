package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownStateMenu
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionSetAutomationState(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var automationState: AutomationStateInterface
    @Inject lateinit var preferences: Preferences

    private var stateNameDropdown: InputDropdownStateMenu = InputDropdownStateMenu(rh) { stateName ->
        updateStateValueDropdown(stateName)
    }
    private var stateValueDropdown: InputDropdownStateMenu = InputDropdownStateMenu(rh)

    private fun populateDropdowns() {
        aapsLogger.debug(LTag.AUTOMATION, "ActionSetAutomationState: populateDropdowns, states=${automationState.getAllStates()}")
        val stateNames = automationState.getAllStates().map { it.first }.distinct()
        stateNameDropdown.values = stateNames
        stateNameDropdown.updateAdapter()
        val firstName = stateNames.firstOrNull() ?: ""
        if (stateNameDropdown.value.isEmpty() && firstName.isNotEmpty()) {
            stateNameDropdown.value = firstName
        }
        updateStateValueDropdown(stateNameDropdown.value)
    }

    private fun updateStateValueDropdown(stateName: String) {
        val values = automationState.getStateValues(stateName)
        stateValueDropdown.values = values
        stateValueDropdown.updateAdapter()
        if (stateValueDropdown.value.isEmpty() && values.isNotEmpty()) {
            stateValueDropdown.value = values.first()
        }
    }

    override fun friendlyName(): Int = R.string.set_state
    override fun shortDescription(): String =
        rh.gs(R.string.set_state_description, stateNameDropdown.value, stateValueDropdown.value)

    override fun isValid(): Boolean = stateNameDropdown.value.isNotEmpty() && stateValueDropdown.value.isNotEmpty()

    override fun doAction(callback: Callback) {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) {
            aapsLogger.debug(LTag.AUTOMATION, "Automation states disabled")
            callback.result(app.aaps.core.interfaces.queue.PumpEnactResult(injector).success(false).comment(rh.gs(R.string.automation_states_disabled)))
                .run()
            return
        }
        try {
            automationState.setState(stateNameDropdown.value, stateValueDropdown.value)
            callback.result(app.aaps.core.interfaces.queue.PumpEnactResult(injector).success(true)).run()
        } catch (e: Exception) {
            callback.result(app.aaps.core.interfaces.queue.PumpEnactResult(injector).success(false).comment(e.message ?: "Error")).run()
        }
    }

    override fun generateDialog(root: LinearLayout) {
        populateDropdowns()
        stateNameDropdown.addToLayout(root)
        stateValueDropdown.addToLayout(root)
    }

    override fun toJSON(): String = JSONObject()
        .put("type", this::class.java.simpleName)
        .put("data", JSONObject()
            .put("stateName", stateNameDropdown.value)
            .put("stateValue", stateValueDropdown.value))
        .toString()

    override fun fromJSON(data: String): Action {
        val d = JSONObject(data)
        stateNameDropdown.value = d.optString("stateName", "")
        stateValueDropdown.value = d.optString("stateValue", "")
        return this
    }

    override fun hasDialog(): Boolean = true
    override fun icon(): Int = app.aaps.core.objects.R.drawable.ic_automation
}