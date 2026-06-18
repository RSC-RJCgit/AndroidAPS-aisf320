package app.aaps.plugins.automationstate

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginAutomation
import app.aaps.plugins.automationstate.compose.AutomationStateComposeContent
import app.aaps.plugins.automationstate.keys.AutomationStateStringKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationStatePlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(IcPluginAutomation)
        .pluginName(R.string.automation_states)
        .shortName(R.string.automation_states_short)
        .description(R.string.description_automation_states)
        .enableByDefault(true)
        .composeContent { AutomationStateComposeContent() },
    ownPreferences = listOf(AutomationStateStringKey::class.java, BooleanKey::class.java),
    aapsLogger, rh, preferences
)
