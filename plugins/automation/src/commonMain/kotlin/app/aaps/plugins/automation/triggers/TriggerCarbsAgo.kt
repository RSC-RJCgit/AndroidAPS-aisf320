package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcCarbs
import app.aaps.core.utils.lenientInt
import app.aaps.core.utils.lenientStringOrNull
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDuration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Last carbs ago", from the v3 AutoISF fork (2026-11-22, per explicit request): v4 had no such trigger, so a v3 rule using it
 * read as unknown after the in-place update and never fired. The stored form ("minutesAgo", "comparator") is v3's, unchanged.
 */
class TriggerCarbsAgo(deps: TriggerDeps) : Trigger(deps) {

    var minutesAgo: InputDuration = InputDuration(30, InputDuration.TimeUnit.MINUTES)
    var comparator: Comparator = Comparator(rh)

    private constructor(deps: TriggerDeps, other: TriggerCarbsAgo) : this(deps) {
        minutesAgo = InputDuration(other.minutesAgo.value, InputDuration.TimeUnit.MINUTES)
        comparator = Comparator(rh, other.comparator.value)
    }

    fun setValue(value: Int): TriggerCarbsAgo {
        minutesAgo.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerCarbsAgo {
        this.comparator.value = comparator
        return this
    }

    override suspend fun shouldRun(): Boolean {
        val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
        if (lastCarbsTime == 0L)
            return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
                aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
                true
            } else {
                aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
                false
            }
        val last = (dateUtil.now() - lastCarbsTime).toDouble() / (60 * 1000)
        aapsLogger.debug(LTag.AUTOMATION, "LastCarbs min ago: $last")
        val doRun = comparator.value.check(last.toInt(), minutesAgo.getMinutes())
        if (doRun) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("minutesAgo", minutesAgo.value)
            put("comparator", comparator.value.toString())
        }

    override fun fromJSON(data: String): Trigger {
        val d = jsonOf(data)
        minutesAgo.setMinutes(d.lenientInt("minutesAgo"))
        comparator.setValue(Comparator.Compare.valueOf(d.lenientStringOrNull("comparator")!!))
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.lastcarbslabel

    override fun friendlyDescription(): String =
        rh.gs(AutomationStrings.lastcarbscompared, rh.gs(comparator.value.stringRes), minutesAgo.getMinutes())

    override fun composeIcon() = IcCarbs
    override fun elementType() = ElementType.COB

    override fun duplicate(): Trigger = TriggerCarbsAgo(deps, this)
}
