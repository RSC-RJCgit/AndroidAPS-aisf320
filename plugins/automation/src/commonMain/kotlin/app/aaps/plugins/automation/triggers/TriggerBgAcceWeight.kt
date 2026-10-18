package app.aaps.plugins.automation.triggers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AutoISF 3.2.1: compares the BG acceleration ISF weight setting (bgAccel_ISF_weight). */
class TriggerBgAcceWeight(deps: TriggerDeps) : Trigger(deps) {

    var acceWeight = 0.0
    var comparator: Comparator = Comparator(rh)

    constructor(deps: TriggerDeps, other: TriggerBgAcceWeight) : this(deps) {
        acceWeight = other.acceWeight
        comparator = Comparator(rh, other.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actualWeight = preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)
        if (comparator.value.check(actualWeight, acceWeight)) {
            aapsLogger.debug(LTag.AUTOMATION, "set bgAccel_ISF_weight ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "set bgAccel_ISF_weight NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("acce_weight", acceWeight)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        acceWeight = d.lenientDouble("acce_weight", 0.0)
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_acce_weight

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.acceweightcompared, rh.gs(comparator.value.stringRes), acceWeight)

    override fun composeIcon() = Icons.Filled.Speed
    override fun elementType() = ElementType.LOOP

    override fun duplicate(): Trigger = TriggerBgAcceWeight(deps, this)
}
