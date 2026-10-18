package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientInt
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AutoISF 3.2.1: sets the IOB threshold percent (iob_threshold_percent). */
class ActionSetIobTH(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences,
    private val uel: UserEntryLogger
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    /** The new IOB threshold, 10 to 100 % in steps of 5, as in 3.2.1. */
    var newIobTh = 100

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobTH_percent
    override fun shortDescription(): String = rh.gs(AutomationStrings.automate_set_iobTH_percent, newIobTh)
    override fun composeIcon() = Icons.Filled.Tune
    override fun elementType() = ElementType.LOOP

    override suspend fun doAction(): PumpEnactResult {
        if (preferences.get(IntKey.ApsAutoIsfIobThPercent) != newIobTh) {
            uel.log(
                app.aaps.core.data.ue.Action.IOB_TH_SET,
                Sources.Automation,
                title + ": " + rh.gs(AutomationStrings.automate_set_iobTH_percent, newIobTh),
                ValueWithUnit.Percent(newIobTh)
            )
            preferences.put(IntKey.ApsAutoIsfIobThPercent, newIobTh)
            return pumpEnactResultProvider().success(true).comment(AutomationStrings.weight_new)
        }
        return pumpEnactResultProvider().success(false).comment(AutomationStrings.weight_old)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("percentage", newIobTh) }
        return buildJsonObject {
            put("type", this@ActionSetIobTH::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        newIobTh = jsonOf(data).lenientInt("percentage", 100)
        return this
    }

    override fun isValid(): Boolean = true

    companion object {

        const val MIN = 10
        const val MAX = 100
        const val STEP = 5
    }
}
