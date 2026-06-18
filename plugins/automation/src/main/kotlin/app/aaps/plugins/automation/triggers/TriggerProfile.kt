package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.compose.IconTint
import app.aaps.plugins.automation.elements.InputProfileName
import dagger.android.HasAndroidInjector
import org.json.JSONObject

class TriggerProfile(injector: HasAndroidInjector) : Trigger(injector) {

    var profileName: InputProfileName = InputProfileName("")

    constructor(injector: HasAndroidInjector, triggerProfile: TriggerProfile) : this(injector) {
        profileName = InputProfileName(triggerProfile.profileName.value)
    }

    fun setValue(value: String): TriggerProfile {
        this.profileName.value = value
        return this
    }

    override suspend fun shouldRun(): Boolean {
        val currentName = profileFunction.getProfileName()
        if (currentName == profileName.value) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("profileName", profileName.value)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        profileName.value = JsonHelper.safeGetString(d, "profileName", "")
        return this
    }

    override fun friendlyName(): Int = R.string.profilecheck
    override fun friendlyDescription(): String =
        "${rh.gs(R.string.profilecheck)}: ${profileName.value}"
    override fun composeIcon() = null
    override fun composeIconTint() = IconTint.Profile
    override fun duplicate(): Trigger = TriggerProfile(injector, this)
}