package app.aaps.plugins.automation.actions

import androidx.annotation.DrawableRes
import dagger.android.HasAndroidInjector
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.automation.R
import javax.inject.Inject

class ActionEnableConnectivityAlarms(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var preferences: Preferences

    override fun friendlyName(): Int = R.string.enable_connectivity_alarms
    override fun shortDescription(): String = rh.gs(R.string.enable_connectivity_alarms)
    @DrawableRes override fun icon(): Int = R.drawable.ic_autoisf_enabled

    override fun doAction(callback: Callback) {
        preferences.put(BooleanKey.AlertPumpUnreachable, true)
        preferences.put(BooleanKey.AlertMissedBgReading, true)
        callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.connectivity_alarms_enabled)).run()
    }

    override fun isValid(): Boolean = true

    override fun hasDialog(): Boolean = false
}
