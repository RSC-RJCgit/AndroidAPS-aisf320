package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import app.aaps.plugins.automation.elements.InputProfileName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extras branch (test3 patch 0007, "Profile check"): true when the running profile has the chosen name.
 *
 * The name is compared without the percentage or time shift of a profile switch, so "Standard" also matches while
 * "Standard" runs at 110%. In v3 a running percentage made it never match.
 */
class TriggerProfile(deps: TriggerDeps) : Trigger(deps) {

    var profileName = InputProfileName("")

    constructor(deps: TriggerDeps, other: TriggerProfile) : this(deps) {
        profileName = InputProfileName(other.profileName.value)
    }

    fun setValue(name: String): TriggerProfile {
        profileName.value = name
        return this
    }

    override suspend fun shouldRun(): Boolean {
        if (profileName.value.isNotEmpty() && profileFunction.getOriginalProfileName() == profileName.value) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JsonObject =
        buildJsonObject {
            put("profileName", profileName.value)
        }

    override fun fromJSON(data: String): Trigger {
        profileName.value = jsonOf(data).lenientString("profileName", "")
        return this
    }

    override fun friendlyName(): TextRef = AutomationStrings.profilecheck

    override fun friendlyDescription(): String = rh.gs(AutomationStrings.profilecheck_description, profileName.value)

    override fun composeIcon() = IcProfile
    override fun elementType() = ElementType.PROFILE_MANAGEMENT

    override fun duplicate(): Trigger = TriggerProfile(deps, this)
}
