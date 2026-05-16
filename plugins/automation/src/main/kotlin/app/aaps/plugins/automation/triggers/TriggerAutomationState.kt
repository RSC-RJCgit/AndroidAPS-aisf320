package app.aaps.plugins.automation.triggers

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenu
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
        val context = root.context
        val stateNames = automationStateInterface.getAllStateNames()

        if (stateNames.isEmpty()) {
            StaticLabel(rh, R.string.trigger_automation_state_label, this).addToLayout(root)
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

        StaticLabel(rh, R.string.trigger_automation_state_label, this).addToLayout(root)
        root.addView(TextView(context).apply { text = rh.gs(R.string.set_state_state_name) })
        root.addView(nameSpinner)
        root.addView(TextView(context).apply { text = rh.gs(R.string.set_state_state_val) })
        root.addView(valueSpinner)
    }
}
