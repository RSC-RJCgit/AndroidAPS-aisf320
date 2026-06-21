package app.aaps.plugins.automation.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.compose.IconTint
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDouble
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.text.DecimalFormat
import javax.inject.Inject

class TriggerPhoneBattery(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var context: Context

    var batteryLevel = InputDouble(5.0, 1.0, 100.0, 1.0, DecimalFormat("0"))
    var comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, triggerPhoneBattery: TriggerPhoneBattery) : this(injector) {
        batteryLevel = InputDouble(triggerPhoneBattery.batteryLevel)
        comparator = Comparator(rh, triggerPhoneBattery.comparator.value)
    }

    override suspend fun shouldRun(): Boolean {
        val level = getPhoneBatteryLevel()
        if (level < 0) return false
        val doRun = comparator.value.check(level, batteryLevel.value)
        if (doRun) aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
        else aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return doRun
    }

    private fun getPhoneBatteryLevel(): Double {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level.toDouble() / scale.toDouble()) * 100.0
        else -1.0
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("batteryLevel", batteryLevel.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        batteryLevel.setValue(JsonHelper.safeGetDouble(d, "batteryLevel"))
        comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.trigger_phone_battery_label
    override fun friendlyDescription(): String =
        "Phone battery ${rh.gs(comparator.value.stringRes)} ${batteryLevel.value.toInt()}%"
    override fun composeIcon() = null
    override fun composeIconTint() = IconTint.Device
    override fun duplicate(): Trigger = TriggerPhoneBattery(injector, this)
}