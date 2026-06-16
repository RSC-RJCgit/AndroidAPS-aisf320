package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownStateMenu
import app.aaps.plugins.automation.elements.InputString
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class TriggerAutomationState(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var automationStateService: AutomationStateInterface
    @Inject lateinit var preferences: Preferences

    var stateName = InputString()
    var stateValue = InputString()

    private var stateNameDropdown: InputDropdownStateMenu = InputDropdownStateMenu(rh) { stateName ->
        updateStateValueDropdown(stateName)
    }
    private var stateValueDropdown: InputDropdownStateMenu = InputDropdownStateMenu(rh)

    private constructor(injector: HasAndroidInjector, stateName: String, stateValue: String) : this(injector) {
        this.stateName.value = stateName
        this.stateValue.value = stateValue
        this.stateNameDropdown.value = stateName
        updateStateValueDropdown(stateName)
        this.stateValueDropdown.value = stateValue
    }

    private fun populateDropdowns() {
        val stateNames = automationStateService.getAllStates().map { it.first }.distinct()
        stateNameDropdown.values = stateNames
        stateNameDropdown.updateAdapter()
        if (stateNameDropdown.value.isEmpty() && stateNames.isNotEmpty()) {
            stateNameDropdown.value = stateNames.first()
        }
        updateStateValueDropdown(stateNameDropdown.value)
    }

    private fun updateStateValueDropdown(stateName: String) {
        val values = automationStateService.getStateValues(stateName)
        stateValueDropdown.values = values
        stateValueDropdown.updateAdapter()
        if (stateValueDropdown.value.isEmpty() && values.isNotEmpty()) {
            stateValueDropdown.value = values.first()
        }
    }

    override fun shouldRun(): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return false
        val result = automationStateService.inState(stateName.value, stateValue.value)
        aapsLogger.debug(LTag.AUTOMATION, "TriggerAutomationState: ${stateName.value}==${stateValue.value} -> $result")
        return result
    }

    override fun generateDialog(root: LinearLayout) {
        populateDropdowns()
        stateNameDropdown.addToLayout(root)
        stateValueDropdown.addToLayout(root)
    }

    override fun friendlyDescription(): String =
        rh.gs(R.string.check_state_description, stateName.value, stateValue.value)

    override fun icon(): Int = app.aaps.core.objects.R.drawable.ic_automation

    override fun toJSON(): String = JSONObject()
        .put("type", this::class.java.simpleName)
        .put("data", JSONObject()
            .put("stateName", stateName.value)
            .put("stateValue", stateValue.value))
        .toString()

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        stateName.value = d.optString("stateName", "")
        stateValue.value = d.optString("stateValue", "")
        stateNameDropdown.value = stateName.value
        stateValueDropdown.value = stateValue.value
        return this
    }

    override fun duplicate(): Trigger = TriggerAutomationState(injector, stateName.value, stateValue.value)
    override fun hasDialog(): Boolean = true
}