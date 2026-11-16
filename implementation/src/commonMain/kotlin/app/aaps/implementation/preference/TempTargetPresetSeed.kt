package app.aaps.implementation.preference

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TTPreset
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.tempTargets.toJson
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * The three factory temp target presets for a device that has none (Eating soon 90 mg/dL 45 min, Activity 140 mg/dL
 * 90 min, Hypo 160 mg/dL 60 min). Called at start on Android (`MainApp`), iOS and desktop.
 *
 * 2026-11-16, per explicit request: a client keeps its own settings and never takes them from the master, so it seeds
 * its own presets too. Before this a client skipped the seed (it used to adopt the master's presets, which it no longer
 * does since chk275), and iOS and desktop had no seed at all, so a client set up since then had no TT presets or buttons.
 * On a client the list is written with [Preferences.putRemote], which stores it on this device only: a plain put of a
 * synced key would send it to the master as a settings edit.
 *
 * A list that already holds presets is never touched.
 *
 * @return true when the presets were written
 */
object TempTargetPresetSeed {

    fun apply(preferences: Preferences, config: Config): Boolean {
        val existing = preferences.get(StringNonKey.TempTargetPresets)
        if (existing.isNotEmpty() && existing != "[]") return false
        val json = factoryPresets().toJson()
        if (config.AAPSCLIENT) preferences.putRemote(StringNonKey.TempTargetPresets, json, version = 0L)
        else preferences.put(StringNonKey.TempTargetPresets, json)
        return true
    }

    fun factoryPresets(): List<TTPreset> = listOf(
        TTPreset(
            id = "eatingsoon", reason = TT.Reason.EATING_SOON,
            targetValue = Constants.DEFAULT_TT_EATING_SOON_TARGET,
            duration = Constants.DEFAULT_TT_EATING_SOON_DURATION * 60L * 1000L, isDeletable = false
        ),
        TTPreset(
            id = "activity", reason = TT.Reason.ACTIVITY,
            targetValue = Constants.DEFAULT_TT_ACTIVITY_TARGET,
            duration = Constants.DEFAULT_TT_ACTIVITY_DURATION * 60L * 1000L, isDeletable = false
        ),
        TTPreset(
            id = "hypo", reason = TT.Reason.HYPOGLYCEMIA,
            targetValue = Constants.DEFAULT_TT_HYPO_TARGET,
            duration = Constants.DEFAULT_TT_HYPO_DURATION * 60L * 1000L, isDeletable = false
        )
    )
}
