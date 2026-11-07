package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
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
            preferences.put(BooleanKey.ApsAutoIsfAutoCleanGraph, true)
        // mmol/L and the screen kept on, on every new install (2026-11-08, per explicit request)
        if (preferences.getIfExists(StringKey.GeneralUnits) == null)
            preferences.put(StringKey.GeneralUnits, UNITS_MMOL)
        if (preferences.getIfExists(BooleanKey.OverviewKeepScreenOn) == null)
            preferences.put(BooleanKey.OverviewKeepScreenOn, true)
        applyForkDefaults(preferences, config)
    }

    /** The stored value of mmol/L in [StringKey.GeneralUnits] */
    internal const val UNITS_MMOL = "mmol"

    /**
     * This fork's AutoISF settings ([forkAutoIsfDefaults], 2026-11-01, per explicit request), on clients and Virtual-pump
     * masters only - a master with real pump drivers is set up by hand. Client defaults stay on that client; they are not sent as edits to the master.
     */
    internal fun applyForkDefaults(preferences: Preferences, config: Config, defaults: List<ForkDefault> = forkAutoIsfDefaults) {
        if (!config.AAPSCLIENT && config.PUMPDRIVERS) return
        for (default in defaults) {
            if (!default.isStored(preferences)) default.write(preferences, localOnly = config.AAPSCLIENT)
        }
    }
}
