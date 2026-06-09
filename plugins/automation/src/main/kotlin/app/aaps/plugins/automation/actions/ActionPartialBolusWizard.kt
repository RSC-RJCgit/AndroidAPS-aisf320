package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputPercent
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionPartialBolusWizard(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var preferences: Preferences

    var percentage = InputPercent(100.0)

    override fun friendlyName(): Int = R.string.partialboluswizard
    override fun shortDescription(): String = rh.gs(R.string.partialboluswizard) + ": ${percentage.value.toInt()}%"
    @DrawableRes override fun icon(): Int = app.aaps.core.ui.R.drawable.ic_running_mode

    override fun doAction(callback: Callback) {
        preferences.put(IntKey.OverviewBolusPercentage, percentage.value.toInt())
        callback.result(pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
    }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.partialboluswizard), "%", percentage))
            .build(root)
    }

    override fun hasDialog(): Boolean = true

    override fun toJSON(): String {
        val data = JSONObject().put("percentage", percentage.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        percentage.value = JsonHelper.safeGetDouble(o, "percentage", 100.0)
        return this
    }

    override fun isValid(): Boolean = percentage.value in 20.0..100.0
}
