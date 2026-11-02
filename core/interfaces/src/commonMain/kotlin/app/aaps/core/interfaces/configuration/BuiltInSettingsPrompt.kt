package app.aaps.core.interfaces.configuration

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * The fork's built-in settings are offered on a client and on a master whose pump is the Virtual pump (2026-11-01, per
 * explicit request). A master with a real pump is set up by hand, as before. An empty pump is a new install, where the
 * Virtual pump is what starts.
 */
fun builtInSettingsOffered(config: Config, activePump: String): Boolean =
    config.AAPSCLIENT || activePump.isEmpty() || activePump == VIRTUAL_PUMP_PLUGIN

/**
 * True until this phone answers for the current build. An update asks again; an app restart does not.
 * The setup wizard waits for the answer too.
 */
fun builtInSettingsPromptPending(preferences: Preferences, config: Config): Boolean =
    builtInSettingsOffered(config, preferences.get(StringNonKey.ActivePluginPump)) &&
        (preferences.get(StringNonKey.BuiltInSettingsChoice).isEmpty() ||
            preferences.get(StringNonKey.BuiltInSettingsPromptBuild) != builtInSettingsBuild(config))

/** Records an explicit answer without applying any defaults. Each phone answers separately. */
fun recordBuiltInSettingsAnswer(preferences: Preferences, config: Config, useBuiltIn: Boolean) {
    preferences.put(StringNonKey.BuiltInSettingsChoice, if (useBuiltIn) BUILT_IN_SETTINGS_ON else BUILT_IN_SETTINGS_OFF)
    preferences.put(StringNonKey.BuiltInSettingsPromptBuild, builtInSettingsBuild(config))
}

private fun builtInSettingsBuild(config: Config): String = config.VERSION_NAME + "@" + config.HEAD

/** The stored answers to the question */
const val BUILT_IN_SETTINGS_ON = "on"
const val BUILT_IN_SETTINGS_OFF = "off"

/** The Virtual pump's plugin id (`PluginBase.pluginId`, the class name) */
const val VIRTUAL_PUMP_PLUGIN = "VirtualPumpPlugin"

/** Writes the fork's built-in settings, overwriting what is stored (see `BuiltInSettings` in :implementation) */
interface BuiltInSettingsApplier {

    /** @param instanceName the name a phone without one gets. @return how many settings were written */
    fun apply(instanceName: String): Int
}
