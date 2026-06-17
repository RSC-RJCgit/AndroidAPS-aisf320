package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionSetAutomationState(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var automationState: AutomationStateInterface

    var stateName: String = ""
    var stateValue: String = ""

    override fun friendlyName(): Int = R.string.set_state

    override fun shortDescription(): String =
        rh.gs(R.string.set_state_description, stateName, stateValue)

    override fun hasDialog(): Boolean = true

    override fun isValid(): Boolean = stateName.isNotEmpty() && stateValue.isNotEmpty()

    override suspend fun doAction(): PumpEnactResult {
        return try {
            automationState.setState(stateName, stateValue)
            pumpEnactResultProvider.get().success(true)
        } catch (e: Exception) {
            pumpEnactResultProvider.get().success(false).comment(e.message ?: "Error")
        }
    }

    override fun toJSON(): String = JSONObject()
        .put("type", this::class.java.simpleName)
        .put("data", JSONObject()
            .put("stateName", stateName)
            .put("stateValue", stateValue))
        .toString()

    override fun fromJSON(data: String): Action {
        val d = JSONObject(data)
        stateName = d.optString("stateName", "")
        stateValue = d.optString("stateValue", "")
        return this
    }
}
