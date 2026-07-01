package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.utils.JsonHelper
import app.aaps.core.utils.JsonHelper.safeGetString
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDuration
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional

class TriggerCarbsAgo(injector: HasAndroidInjector) : Trigger(injector) {

    var minutesAgo: InputDuration = InputDuration(30, InputDuration.TimeUnit.MINUTES)
    var comparator: Comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, other: TriggerCarbsAgo) : this(injector) {
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

    override fun shouldRun(): Boolean {
        val lastCarbs = persistenceLayer.getNewestCarbs()
        val lastCarbsTime = lastCarbs?.timestamp ?: 0L
        if (lastCarbsTime == 0L)
            return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
                aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
                true
            } else {
                aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
                false
            }
        val last = (dateUtil.now() - lastCarbsTime).toDouble() / (60 * 1000)
        aapsLogger.debug(LTag.AUTOMATION, "LastCarbs min ago: $minutesAgo")
        val doRun = comparator.value.check(last.toInt(), minutesAgo.getMinutes())
        if (doRun) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("minutesAgo", minutesAgo.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        minutesAgo.setMinutes(JsonHelper.safeGetInt(d, "minutesAgo"))
        comparator.setValue(Comparator.Compare.valueOf(safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.lastcarbslabel

    override fun friendlyDescription(): String =
        rh.gs(R.string.lastcarbscompared, comparator.value.shortSymbol, minutesAgo.getMinutes())

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_cp_bolus_carbs)

    override fun duplicate(): Trigger = TriggerCarbsAgo(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.lastcarbslabel, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.lastcarbslabel) + ": ", rh.gs(app.aaps.core.interfaces.R.string.unit_minutes), minutesAgo))
            .build(root)
    }
}
