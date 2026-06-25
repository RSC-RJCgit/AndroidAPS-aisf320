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
import javax.inject.Inject

class TriggerBgDuraWeight(injector: HasAndroidInjector) : Trigger(injector) {

    var duraWeight = InputDouble(0.0, 0.0, 3.0, 0.05, DecimalFormat("0.00"))
    var comparator = Comparator(rh)

    constructor(injector: HasAndroidInjector, duraWeight: Double, compare: Comparator.Compare) : this(injector) {
        this.duraWeight = InputDouble(duraWeight, 0.0, 3.0, 0.05, DecimalFormat("0.00"))
        comparator = Comparator(rh, compare)
    }

    constructor(injector: HasAndroidInjector, trigger: TriggerBgDuraWeight) : this(injector) {
        this.duraWeight = InputDouble(trigger.duraWeight.value, 0.0, 3.0, 0.05, DecimalFormat("0.00"))
        comparator = Comparator(rh, trigger.comparator.value)
    }

    fun setValue(duraWeight: Double): TriggerBgDuraWeight {
        this.duraWeight.value = duraWeight
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerBgDuraWeight {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val actualWeight = preferences.get(DoubleKey.ApsAutoIsfDuraWeight)
        if (comparator.value.check(actualWeight, duraWeight.value, 0.001)) {
            aapsLogger.debug(LTag.AUTOMATION, "dura_ISF_weight ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "dura_ISF_weight NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("dura_weight", duraWeight.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        duraWeight.value = JsonHelper.safeGetDouble(d, "dura_weight")
        comparator.value = Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!)
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_dura_isf_weight

    override fun friendlyDescription(): String =
        rh.gs(R.string.duraweightcompared, rh.gs(comparator.value.stringRes), duraWeight.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_acce_weight)

    override fun duplicate(): Trigger = TriggerBgDuraWeight(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_dura_isf_weight, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_dura_isf_weight) + ": ", "", duraWeight))
            .build(root)
    }
}
