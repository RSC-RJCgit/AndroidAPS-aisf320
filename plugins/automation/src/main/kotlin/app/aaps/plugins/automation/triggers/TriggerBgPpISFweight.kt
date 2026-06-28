package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import java.util.Optional
import dagger.android.HasAndroidInjector
import app.aaps.plugins.automation.R
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDouble
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import org.json.JSONObject
import app.aaps.core.utils.JsonHelper
import java.text.DecimalFormat

class TriggerBgPpISFweight(injector: HasAndroidInjector) : Trigger(injector) {

    var ppWeight = InputDouble(0.0, 0.0, 0.15, 0.01, DecimalFormat("0.00"))
    var comparator = Comparator(rh)

    constructor(injector: HasAndroidInjector, ppWeight: Double, compare: Comparator.Compare) : this(injector) {
        this.ppWeight = InputDouble(ppWeight, 0.0, 0.15, 0.01, DecimalFormat("0.00"))
        comparator = Comparator(rh, compare)
    }

    constructor(injector: HasAndroidInjector, trigger: TriggerBgPpISFweight) : this(injector) {
        this.ppWeight = InputDouble(trigger.ppWeight.value, 0.0, 0.15, 0.01, DecimalFormat("0.00"))
        comparator = Comparator(rh, trigger.comparator.value)
    }

    fun setValue(ppWeight: Double): TriggerBgPpISFweight {
        this.ppWeight.value = ppWeight
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerBgPpISFweight {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val actualWeight = preferences.get(DoubleKey.ApsAutoIsfPpWeight)
        if (comparator.value.check(actualWeight, ppWeight.value, 0.001)) {
            aapsLogger.debug(LTag.AUTOMATION, "pp_ISF_weight ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "pp_ISF_weight NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("pp_weight", ppWeight.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        ppWeight.value = JsonHelper.safeGetDouble(d, "pp_weight")
        comparator.value = Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!)
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_pp_isf_weight

    override fun friendlyDescription(): String =
        rh.gs(R.string.ppweightcompared, comparator.value.shortSymbol, ppWeight.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_acce_weight)

    override fun duplicate(): Trigger = TriggerBgPpISFweight(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_pp_isf_weight, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_pp_isf_weight) + ": ", "", ppWeight))
            .build(root)
    }
}
