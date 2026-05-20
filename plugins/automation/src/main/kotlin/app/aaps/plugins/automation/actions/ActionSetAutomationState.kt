package app.aaps.plugins.automation.actions

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenu
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
        // Support both AndroidAPS1/Boost format ("stateName"/"stateValue")
        // and a320 format ("inputStateName"/"inputState")
        stateName.value = JsonHelper.safeGetString(d, "stateName", "")
            .ifEmpty { JsonHelper.safeGetString(d, "inputStateName", "") }
        stateValue.value = JsonHelper.safeGetString(d, "stateValue", "")
            .ifEmpty { JsonHelper.safeGetString(d, "inputState", "") }
        return this
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        val context = root.context
        val stateNames = automationStateInterface.getAllStateNames()

        if (stateNames.isEmpty()) {
            root.addView(TextView(context).apply { text = rh.gs(R.string.no_automation_states) })
            return
        }

        // Ensure stateName has a valid value immediately (don't rely on deferred callbacks)
        if (stateName.value.isEmpty() || !stateNames.contains(stateName.value))
            stateName.value = stateNames[0]

        val currentValues = automationStateInterface.getStateValues(stateName.value)
        if (stateValue.value.isEmpty() || !currentValues.contains(stateValue.value))
            stateValue.value = currentValues.firstOrNull() ?: ""

        val valueSpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.setMargins(0, rh.dpToPx(4), 0, rh.dpToPx(4)) }
        }

        fun refreshValueSpinner(selectedStateName: String) {
            val values = automationStateInterface.getStateValues(selectedStateName)
            // Ensure stateValue is valid for the newly selected state
            if (stateValue.value.isEmpty() || !values.contains(stateValue.value))
                stateValue.value = values.firstOrNull() ?: ""
            valueSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position < values.size) stateValue.value = values[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            valueSpinner.adapter = ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, values).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = values.indexOf(stateValue.value)
            if (idx >= 0) valueSpinner.setSelection(idx)
        }

        val nameSpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.setMargins(0, rh.dpToPx(4), 0, rh.dpToPx(4)) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val name = stateNames[position]
                    stateName.value = name
                    refreshValueSpinner(name)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            adapter = ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, stateNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = stateNames.indexOf(stateName.value)
            if (idx >= 0) setSelection(idx)
        }

        // Initialise value spinner for current state name
        refreshValueSpinner(stateName.value)

        root.addView(TextView(context).apply { text = rh.gs(R.string.set_state_state_name) })
        root.addView(nameSpinner)
        root.addView(TextView(context).apply { text = rh.gs(R.string.set_state_state_val) })
        root.addView(valueSpinner)
    }

    override fun isValid(): Boolean = stateName.value.isNotEmpty() && stateValue.value.isNotEmpty()
}
