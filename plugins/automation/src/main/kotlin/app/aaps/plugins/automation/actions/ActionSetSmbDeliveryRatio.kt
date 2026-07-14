package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import dagger.android.HasAndroidInjector
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.elements.InputWeight
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.R
import org.json.JSONObject
import javax.inject.Inject

class ActionSetSmbDeliveryRatio(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var preferences: Preferences

    var new_ratio = InputWeight()

    override fun friendlyName(): Int = R.string.autoisf_smb_delivery_ratio
    override fun shortDescription(): String = rh.gs(R.string.automate_set_smb_delivery_ratio, new_ratio.value)
    @DrawableRes override fun icon(): Int = R.drawable.ic_iobth

    override fun doAction(callback: Callback) {
        val currentRatio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        if (currentRatio != new_ratio.value) {
            uel.log(
                app.aaps.core.data.ue.Action.SMB_DELIVERY_RATIO_SET,
                Sources.Automation,
                title + ": " + rh.gs(R.string.automate_set_smb_delivery_ratio, new_ratio.value)
            )
            preferences.put(DoubleKey.ApsAutoIsfSmbDeliveryRatio, new_ratio.value)
            callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.weight_new)).run()
        } else {
            callback.result(pumpEnactResultProvider.get().success(false).comment(R.string.weight_old)).run()
        }
    }

    override fun hasDialog(): Boolean {
        return true
    }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_smb_delivery_ratio), "", new_ratio))
            .build(root)
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("ratio", new_ratio.value)
        return JSONObject()
            .put("type", this.javaClass.name)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        new_ratio.value = JsonHelper.safeGetDouble(o, "ratio")
        return this
    }

    override fun isValid(): Boolean = true
}
