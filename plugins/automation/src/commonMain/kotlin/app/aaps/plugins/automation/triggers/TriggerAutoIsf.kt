package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcAs
import app.aaps.core.utils.lenientDoubleOrNull
import app.aaps.core.utils.lenientIntOrNull
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// AutoISF fork: the two AutoISF triggers of AutoISF 3.2.1 (AAPS 3.4.2.6). The class names and the JSON
// fields are the same as there, so automations exported from that version can be read here.

/** True when the current AutoISF "BG acceleration ISF weight" compares to [acceWeight] as set. */
class TriggerBgAcceWeight(deps: TriggerDeps) : Trigger(deps) {

    var acceWeight: Double = 0.0
    var comparator: Comparator = Comparator(rh)

    private constructor(deps: TriggerDeps, other: TriggerBgAcceWeight) : this(deps) {
        acceWeight = other.acceWeight
        comparator = Comparator(rh, other.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actual = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        val ready = comparator.value.check(actual, acceWeight)
        aapsLogger.debug(LTag.AUTOMATION, (if (ready) "Ready" else "NOT ready") + " for execution: " + friendlyDescription())
        return ready
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("acce_weight", acceWeight)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        acceWeight = d.lenientDoubleOrNull("acce_weight") ?: 0.0
        d.lenientStringOrNull("comparator")?.let { name ->
            Comparator.Compare.entries.firstOrNull { it.name == name }?.let { comparator.setValue(it) }
        }
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_acce_weight_trigger

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.autoisf_acce_weight_compared, rh.gs(comparator.value.stringRes), acceWeight)

    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.SENSITIVITY

    override fun duplicate(): Trigger = TriggerBgAcceWeight(deps, this)
}

/** True when the current AutoISF iobTH (percent of max IOB) compares to [percent] as set. */
class TriggerIobTH(deps: TriggerDeps) : Trigger(deps) {

    var percent: Int = 100
    var comparator: Comparator = Comparator(rh)

    private constructor(deps: TriggerDeps, other: TriggerIobTH) : this(deps) {
        percent = other.percent
        comparator = Comparator(rh, other.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actual = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        val ready = comparator.value.check(actual, percent)
        aapsLogger.debug(LTag.AUTOMATION, (if (ready) "Ready" else "NOT ready") + " for execution: " + friendlyDescription())
        return ready
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("iobTH_percent", percent)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        percent = d.lenientIntOrNull("iobTH_percent") ?: 100
        d.lenientStringOrNull("comparator")?.let { name ->
            Comparator.Compare.entries.firstOrNull { it.name == name }?.let { comparator.setValue(it) }
        }
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobth_trigger

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.autoisf_iobth_compared, rh.gs(comparator.value.stringRes), percent)

    override fun composeIcon() = IcAs
    override fun elementType() = ElementType.SENSITIVITY

    override fun duplicate(): Trigger = TriggerIobTH(deps, this)
}
