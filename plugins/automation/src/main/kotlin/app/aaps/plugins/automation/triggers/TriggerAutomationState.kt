package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class TriggerAutomationState(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var automationStateService: AutomationStateInterface

    var stateName: String = ""
    var stateValue: String = ""

    private constructor(injector: HasAndroidInjector, stateName: String, stateValue: String) : this(injector) {
        this.stateName = stateName
        this.stateValue = stateValue
    }

    override suspend fun shouldRun(): Boolean {
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return false
        val result = automationStateService.inState(stateName, stateValue)
        aapsLogger.debug(LTag.AUTOMATION, "TriggerAutomationState: $stateName==$stateValue -> $result")
        return result
    }

    override fun dataJSON(): JSONObject = JSONObject()
        .put("stateName", stateName)
        .put("stateValue", stateValue)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        stateName = d.optString("stateName", "")
        stateValue = d.optString("stateValue", "")
        return this
    }

    override fun friendlyName(): Int = R.string.check_state_name

    override fun friendlyDescription(): String =
        rh.gs(R.string.check_state_description, stateName, stateValue)

    override fun duplicate(): Trigger = TriggerAutomationState(injector, stateName, stateValue)
}
