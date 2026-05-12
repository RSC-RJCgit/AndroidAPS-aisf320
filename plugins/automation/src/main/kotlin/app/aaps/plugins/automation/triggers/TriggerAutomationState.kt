package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenu
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional
import javax.inject.Inject

class TriggerAutomationState(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var automationStateInterface: AutomationStateInterface

    var stateName = InputDropdownMenu(rh)
    var stateValue = InputDropdownMenu(rh)

    private constructor(injector: HasAndroidInjector, other: TriggerAutomationState) : this(injector) {
        stateName = InputDropdownMenu(rh, other.stateName.value)
        stateValue = InputDropdownMenu(rh, other.stateValue.value)
    }

    override fun shouldRun(): Boolean {
        val result = automationStateInterface.inState(stateName.value, stateValue.value)
        if (result) aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
        else aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return result
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("stateName", stateName.value)
            .put("stateValue", stateValue.value)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        stateName.value = JsonHelper.safeGetString(d, "stateName", "")
        stateValue.value = JsonHelper.safeGetString(d, "stateValue", "")
        return this
    }

    override fun friendlyName(): Int = R.string.trigger_automation_state_label

    override fun friendlyDescription(): String =
        "${rh.gs(R.string.trigger_automation_state_label)}: ${stateName.value} = ${stateValue.value}"

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_automation)

    override fun duplicate(): Trigger = TriggerAutomationState(injector, this)

    override fun generateDialog(root: LinearLayout) {
        val stateNames = ArrayList<CharSequence>(automationStateInterface.getAllStateNames())
        val values = if (stateName.value.isNotEmpty())
            ArrayList<CharSequence>(automationStateInterface.getStateValues(stateName.value))
        else
            ArrayList()

        stateName.setList(stateNames)
        stateValue.setList(values)

        LayoutBuilder()
            .add(StaticLabel(rh, R.string.trigger_automation_state_label, this))
            .add(LabelWithElement(rh, rh.gs(R.string.set_state_state_name), "", stateName))
            .add(LabelWithElement(rh, rh.gs(R.string.set_state_state_val), "", stateValue))
            .build(root)
    }
}
