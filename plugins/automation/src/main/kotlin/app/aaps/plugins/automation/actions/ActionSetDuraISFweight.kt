package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDouble
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.text.DecimalFormat
import javax.inject.Inject

class ActionSetDuraISFweight(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var preferences: Preferences

    override fun friendlyName(): Int = R.string.autoisf_dura_isf_weight
    override fun shortDescription(): String = rh.gs(R.string.automate_set_dura_isf_weight, new_weight.value)
    @DrawableRes override fun icon(): Int = R.drawable.ic_acce_weight

    var new_weight = InputDouble(0.0, 0.0, 3.0, 0.05, DecimalFormat("0.00"))

    override fun doAction(callback: Callback) {
        preferences.put(DoubleKey.ApsAutoIsfDuraWeight, new_weight.value)
        uel.log(
            app.aaps.core.data.ue.Action.DURA_ISF_WEIGHT_SET,
            Sources.Automation,
            title + ": " + rh.gs(R.string.automate_set_dura_isf_weight, new_weight.value)
        )
        callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.weight_new)).run()
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_dura_isf_weight), "", new_weight))
            .build(root)
    }

    override fun toJSON(): String {
        val data = JSONObject().put("weight", new_weight.value)
        return JSONObject()
            .put("type", this.javaClass.name)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        new_weight.setValue(JsonHelper.safeGetDouble(JSONObject(data), "weight"))
        return this
    }

    override fun isValid(): Boolean = true
}
