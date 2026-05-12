package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenu
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionSetAutomationState(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var automationStateInterface: AutomationStateInterface

    var stateName = InputDropdownMenu(rh)
    var stateValue = InputDropdownMenu(rh)

    override fun friendlyName(): Int = R.string.set_state

    override fun shortDescription(): String =
        rh.gs(R.string.set_state_description, stateName.value, stateValue.value)

    @DrawableRes override fun icon(): Int = app.aaps.core.objects.R.drawable.ic_automation

    override fun doAction(callback: Callback) {
        try {
            automationStateInterface.setState(stateName.value, stateValue.value)
            aapsLogger.debug(LTag.AUTOMATION, "State set: ${stateName.value} = ${stateValue.value}")
            callback.result(pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
        } catch (e: Exception) {
            aapsLogger.error(LTag.AUTOMATION, "Error setting state: ${e.message}")
            callback.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.ui.R.string.error)).run()
        }
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("stateName", stateName.value)
            .put("stateValue", stateValue.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val d = JSONObject(data)
        stateName.value = JsonHelper.safeGetString(d, "stateName", "")
        stateValue.value = JsonHelper.safeGetString(d, "stateValue", "")
        return this
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        val stateNames = ArrayList<CharSequence>(automationStateInterface.getAllStateNames())
        val values = if (stateName.value.isNotEmpty())
            ArrayList<CharSequence>(automationStateInterface.getStateValues(stateName.value))
        else
            ArrayList()

        stateName.setList(stateNames)
        stateValue.setList(values)

        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.set_state_state_name), "", stateName))
            .add(LabelWithElement(rh, rh.gs(R.string.set_state_state_val), "", stateValue))
            .build(root)
    }

    override fun isValid(): Boolean = stateName.value.isNotEmpty() && stateValue.value.isNotEmpty()
}
