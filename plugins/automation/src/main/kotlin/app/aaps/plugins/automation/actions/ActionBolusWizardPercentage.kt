package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcCalculator
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.compose.IconTint
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionBolusWizardPercentage(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var preferences: Preferences

    var percentage: Int = IntKey.OverviewBolusPercentage.defaultValue

    override fun friendlyName(): Int = R.string.set_bolus_wizard_percentage
    override fun shortDescription(): String = rh.gs(R.string.set_bolus_wizard_percentage_to, percentage)
    override fun composeIcon() = IcCalculator
    override fun composeIconTint() = IconTint.Insulin

    override suspend fun doAction(): PumpEnactResult {
        preferences.put(IntKey.OverviewBolusPercentage, percentage)
        return pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String =
        JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", JSONObject().put("percentage", percentage))
            .toString()

    override fun fromJSON(data: String): Action {
        percentage = JsonHelper.safeGetInt(JSONObject(data), "percentage", IntKey.OverviewBolusPercentage.defaultValue)
        return this
    }

    override fun isValid(): Boolean = percentage in IntKey.OverviewBolusPercentage.min..IntKey.OverviewBolusPercentage.max
}
