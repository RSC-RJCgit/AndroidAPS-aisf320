package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Settings a brand-new install starts with that differ from their stored defaults (2026-10-19, per explicit request).
 *
 * Written once, at app start, only on a fresh install: the setup wizard has not run yet and the setting was never
 * stored. An existing install keeps what it has, even one that never touched the setting, so an update does not
 * suddenly change how a working phone behaves. A settings import afterwards brings the file's value as usual.
 */
object FreshInstallDefaults {

    fun apply(preferences: Preferences, config: Config) {
        if (preferences.get(BooleanNonKey.GeneralSetupWizardProcessed)) return
        // A new client is read-only until the user says it controls its master (the setup wizard asks)
        if (config.AAPSCLIENT && preferences.getIfExists(BooleanKey.NsClientReadOnlyClient) == null)
            preferences.put(BooleanKey.NsClientReadOnlyClient, true)
        // Show the four main-graph text rows by default.
        if (preferences.getIfExists(BooleanKey.ApsAutoIsfShowGraphText) == null)
            preferences.put(BooleanKey.ApsAutoIsfShowGraphText, true)
        if (preferences.getIfExists(BooleanKey.ApsAutoIsfAutoCleanGraph) == null)
            preferences.put(BooleanKey.ApsAutoIsfAutoCleanGraph, false)
    }
}
