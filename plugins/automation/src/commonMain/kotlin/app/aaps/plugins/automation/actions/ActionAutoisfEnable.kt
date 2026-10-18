package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.automation.AutomationStrings

/** AutoISF 3.2.1: turns the AutoISF weights (glucose ISF weights) on. */
class ActionAutoisfEnable(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.enableautoisf
    override fun shortDescription(): String = rh.gs(AutomationStrings.enableautoisf)
    override fun composeIcon() = Icons.Filled.CheckCircle
    override fun elementType() = ElementType.LOOP

    override suspend fun doAction(): PumpEnactResult {
        if (preferences.get(BooleanKey.ApsUseAutoIsfWeights) != true) {
            uel.log(app.aaps.core.data.ue.Action.AUTOISF_ENABLED, Sources.Automation, title)
            preferences.put(BooleanKey.ApsUseAutoIsfWeights, true)
            return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_enabled)
        }
        return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_alreadyenabled)
    }

    override fun isValid(): Boolean = true

    override fun hasDialog(): Boolean = false
}
