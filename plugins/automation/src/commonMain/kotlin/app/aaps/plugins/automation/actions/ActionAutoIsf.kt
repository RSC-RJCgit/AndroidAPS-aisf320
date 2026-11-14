package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcAs
import app.aaps.core.utils.lenientDoubleOrNull
import app.aaps.core.utils.lenientIntOrNull
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// AutoISF fork: the four AutoISF actions of AutoISF 3.2.1 (AAPS 3.4.2.6). The class names and the JSON
// fields are the same as there, so automations exported from that version can be read here.

/** Limits of the AutoISF settings, the same as in the preferences and in AutoISF 3.2.1. */
internal object AutoIsfAutomationLimits {

    const val ACCE_WEIGHT_MIN = 0.0
    const val ACCE_WEIGHT_MAX = 1.0
    const val ACCE_WEIGHT_STEP = 0.05
    const val IOB_TH_MIN = 10
    const val IOB_TH_MAX = 100
    const val IOB_TH_STEP = 5
}

/** Switches the AutoISF weights on ("Enable AutoISF" in AutoISF 3.2.1). */
class ActionAutoisfEnable(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_enable
    override fun shortDescription(): String = rh.gs(AutomationStrings.autoisf_enable)
    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.INSULIN

    override suspend fun doAction(): PumpEnactResult {
        if (preferences.get(BooleanKey.ApsUseAutoIsfWeights))
            return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_already_enabled)
        preferences.put(BooleanKey.ApsUseAutoIsfWeights, true)
        return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_enabled)
    }

    override fun hasDialog(): Boolean = false
    override fun toJSON(): String = buildJsonObject { put("type", this@ActionAutoisfEnable::class.simpleName); put("data", buildJsonObject { }) }.toString()
    override fun fromJSON(data: String): Action = this
    override fun isValid(): Boolean = true
}

/** Switches the AutoISF weights off ("Disable AutoISF" in AutoISF 3.2.1). */
class ActionAutoisfDisable(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_disable
    override fun shortDescription(): String = rh.gs(AutomationStrings.autoisf_disable)
    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.INSULIN

    override suspend fun doAction(): PumpEnactResult {
        if (!preferences.get(BooleanKey.ApsUseAutoIsfWeights))
            return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_already_disabled)
        preferences.put(BooleanKey.ApsUseAutoIsfWeights, false)
        return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_disabled)
    }

    override fun hasDialog(): Boolean = false
    override fun toJSON(): String = buildJsonObject { put("type", this@ActionAutoisfDisable::class.simpleName); put("data", buildJsonObject { }) }.toString()
    override fun fromJSON(data: String): Action = this
    override fun isValid(): Boolean = true
}

/** Sets the AutoISF "BG acceleration ISF weight" (0 to 1). */
class ActionSetAcceWeight(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var weight: Double = 0.0

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_set_acce_weight
    override fun shortDescription(): String = rh.gs(AutomationStrings.autoisf_set_acce_weight_to, weight)
    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.INSULIN

    override suspend fun doAction(): PumpEnactResult {
        val newWeight = weight.coerceIn(AutoIsfAutomationLimits.ACCE_WEIGHT_MIN, AutoIsfAutomationLimits.ACCE_WEIGHT_MAX)
        if (preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight) == newWeight)
            return pumpEnactResultProvider().success(false).comment(AutomationStrings.autoisf_value_unchanged)
        preferences.put(DoubleKey.ApsAutoIsfBgAccelWeight, newWeight)
        return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_value_changed)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("weight", weight) }
        return buildJsonObject {
            put("type", this@ActionSetAcceWeight::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        weight = (jsonOf(data).lenientDoubleOrNull("weight") ?: 0.0)
            .coerceIn(AutoIsfAutomationLimits.ACCE_WEIGHT_MIN, AutoIsfAutomationLimits.ACCE_WEIGHT_MAX)
        return this
    }

    override fun isValid(): Boolean = weight in AutoIsfAutomationLimits.ACCE_WEIGHT_MIN..AutoIsfAutomationLimits.ACCE_WEIGHT_MAX
}

/** Sets the AutoISF iobTH, in percent of max IOB (10 to 100). */
class ActionSetIobTH(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val preferences: Preferences
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var percent: Int = 100

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_set_iobth
    override fun shortDescription(): String = rh.gs(AutomationStrings.autoisf_set_iobth_to, percent)
    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.INSULIN

    override suspend fun doAction(): PumpEnactResult {
        val newPercent = percent.coerceIn(AutoIsfAutomationLimits.IOB_TH_MIN, AutoIsfAutomationLimits.IOB_TH_MAX)
        if (preferences.get(IntKey.ApsAutoIsfIobThPercent) == newPercent)
            return pumpEnactResultProvider().success(false).comment(AutomationStrings.autoisf_value_unchanged)
        preferences.put(IntKey.ApsAutoIsfIobThPercent, newPercent)
        return pumpEnactResultProvider().success(true).comment(AutomationStrings.autoisf_value_changed)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = buildJsonObject { put("percentage", percent) }
        return buildJsonObject {
            put("type", this@ActionSetIobTH::class.simpleName)
            put("data", data)
        }.toString()
    }

    override fun fromJSON(data: String): Action {
        percent = (jsonOf(data).lenientIntOrNull("percentage") ?: 100)
            .coerceIn(AutoIsfAutomationLimits.IOB_TH_MIN, AutoIsfAutomationLimits.IOB_TH_MAX)
        return this
    }

    override fun isValid(): Boolean = percent in AutoIsfAutomationLimits.IOB_TH_MIN..AutoIsfAutomationLimits.IOB_TH_MAX
}
