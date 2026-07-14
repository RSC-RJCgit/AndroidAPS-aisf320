package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import java.util.Optional
import dagger.android.HasAndroidInjector
import app.aaps.plugins.automation.R
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputWeight
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import org.json.JSONObject
import app.aaps.core.utils.JsonHelper

class TriggerSmbDeliveryRatio(injector: HasAndroidInjector) : Trigger(injector) {

    var smbDeliveryRatio = InputWeight()
    var comparator = Comparator(rh)

    constructor(injector: HasAndroidInjector, smbDeliveryRatio: Double, compare: Comparator.Compare) : this(injector) {
        this.smbDeliveryRatio = InputWeight(smbDeliveryRatio)
        comparator = Comparator(rh, compare)
    }

    constructor(injector: HasAndroidInjector, triggerSmbDeliveryRatio: TriggerSmbDeliveryRatio) : this(injector) {
        this.smbDeliveryRatio = InputWeight(triggerSmbDeliveryRatio.smbDeliveryRatio.value)
        comparator = Comparator(rh, triggerSmbDeliveryRatio.comparator.value)
    }

    fun setValue(smbDeliveryRatio: Double): TriggerSmbDeliveryRatio {
        this.smbDeliveryRatio.value = smbDeliveryRatio
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerSmbDeliveryRatio {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val actualRatio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        if (comparator.value.check(actualRatio, smbDeliveryRatio.value, 0.001)) {
            aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "smb_delivery_ratio NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("smb_delivery_ratio", smbDeliveryRatio.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        smbDeliveryRatio.value = JsonHelper.safeGetDouble(d, "smb_delivery_ratio")
        comparator.value = Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!)
        return this
    }

    override fun friendlyName(): Int = R.string.autoisf_smb_delivery_ratio

    override fun friendlyDescription(): String =
        rh.gs(R.string.smbdeliveryratiocompared, comparator.value.shortSymbol, smbDeliveryRatio.value)

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_iobth)

    override fun duplicate(): Trigger = TriggerSmbDeliveryRatio(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.autoisf_smb_delivery_ratio, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_smb_delivery_ratio) + ": ", "", smbDeliveryRatio))
            .build(root)
    }
}
