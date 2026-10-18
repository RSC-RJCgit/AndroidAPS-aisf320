package app.aaps.plugins.automation.triggers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.utils.lenientInt
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AutoISF 3.2.1: compares the IOB threshold percent setting (iob_threshold_percent). */
class TriggerIobTH(deps: TriggerDeps) : Trigger(deps) {

    var iobThPercent = 100
    var comparator: Comparator = Comparator(rh)

    constructor(deps: TriggerDeps, other: TriggerIobTH) : this(deps) {
        iobThPercent = other.iobThPercent
        comparator = Comparator(rh, other.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val actualPercent = preferences.get(IntKey.ApsAutoIsfIobThPercent)
        if (comparator.value.check(actualPercent, iobThPercent)) {
            aapsLogger.debug(LTag.AUTOMATION, "set iob_threshold_percent ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "set iob_threshold_percent NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("iobTH_percent", iobThPercent)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        iobThPercent = d.lenientInt("iobTH_percent", 100)
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.autoisf_iobTH_percent

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.iobTHpercentcompared, rh.gs(comparator.value.stringRes), iobThPercent)

    override fun composeIcon() = Icons.Filled.Tune
    override fun elementType() = ElementType.LOOP

    override fun duplicate(): Trigger = TriggerIobTH(deps, this)
}
