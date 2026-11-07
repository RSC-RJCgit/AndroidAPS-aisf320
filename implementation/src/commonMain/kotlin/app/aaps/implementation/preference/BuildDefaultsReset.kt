package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.SyncDirection

/**
 * Initializes the switches of a fresh Virtual master or client. Updates preserve saved settings;
 * built-in settings are applied later only after an explicit choice and password confirmation.
 *
 * The main master looping phone (a master with a real pump) is never touched: an update must not change how it looks or
 * doses, so the code defaults stay as that phone needs them and [AGREED] is a separate list. Numbers, the Nightscout and
 * pump setup, profiles and the read-only and pairing settings are not touched either.
 *
 * Client defaults are written locally without sending edits to the master. Broadcast settings do not
 * overwrite them. A Virtual master stores its own defaults as normal local changes.
 */
object BuildDefaultsReset {

    /** The agreed switches of a Virtual master or a client (2026-10-21). */
    val AGREED: Map<BooleanKey, Boolean> = mapOf(
        // Display
        BooleanKey.GeneralSimpleMode to false,
        BooleanKey.ApsAutoIsfShowInsulinTotals to true,
        BooleanKey.ApsAutoIsfShowCarbModelCurve to true,
        BooleanKey.ApsAutoIsfShowGraph5 to true,
        // Show text normally, and hide it while clean graph is active.
        BooleanKey.ApsAutoIsfAutoCleanGraph to true,
        BooleanKey.ApsAutoIsfShowGraphText to true,
        BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled to false,
        BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled to false,
        BooleanKey.AutomationCodedLocationsEnabled to true,
        BooleanKey.OverviewUseBolusAdvisor to false,
        BooleanKey.OverviewUseSuperBolus to false,
        // Dosing
        BooleanKey.ApsUseSmb to true,
        BooleanKey.ApsUseSmbAlways to true,
        BooleanKey.ApsUseSmbWithCob to true,
        BooleanKey.ApsUseSmbWithLowTt to true,
        BooleanKey.ApsUseSmbAfterCarbs to true,
        BooleanKey.ApsUseSmbWithHighTt to true,
        BooleanKey.ApsUseUam to true,
        BooleanKey.ApsUseAutosens to false,
        BooleanKey.ApsUseDynamicSensitivity to false,
        BooleanKey.ApsAlwaysUseShortDeltas to false,
        BooleanKey.ApsUseAutoIsfWeights to true,
        BooleanKey.ApsAutoIsfHighTtRaisesSens to true,
        BooleanKey.ApsAutoIsfLowTtLowersSens to true,
        BooleanKey.ApsAutoIsfSmbOnEvenTarget to true,
        BooleanKey.ApsAutoIsfFastRiseEnabled to true,
        BooleanKey.ApsAutoIsfLowReboundGuardEnabled to true,
        BooleanKey.ApsAutoIsfUseUkf1ForDosing to false,
        BooleanKey.ApsAutoIsfTddSensitivity to true,
        BooleanKey.ApsAutoIsfTddFactor to true,
        BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled to true,
        BooleanKey.ApsAutoIsfBoostAutomationsEnabled to true,
        BooleanKey.ApsAutoIsfUamBoostEnabled to true,
        BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled to true,
        BooleanKey.ApsAutoIsfCodedAutomationsEnabled to true,
        BooleanKey.ApsAutoIsfCustomAutomationsEnabled to true,
        BooleanKey.AutomationStatesEnabled to true,
        BooleanKey.ApsActivityDetection to true,
        BooleanKey.ApsAutoIsfStepsFromPhone to false,
        BooleanKey.ApsIgnoreInactivityOvernight to true,
        BooleanKey.ApsAutoIsfLoopEveryMinute to true,
        BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual to true,
        BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual to true,
        BooleanKey.OverviewUseBolusReminder to false,
    )

    /** The pump plugin id a Virtual master runs (`PluginBase.pluginId`, the class name). */
    private const val VIRTUAL_PUMP = "VirtualPumpPlugin"

    /**
     * Returns changed switches on a fresh install. Existing installs and real-pump masters are left alone.
     *
     * A build without pump drivers (iOS, desktop) can only run the Virtual pump, so it counts as Virtual even while
     * [activePump] is still empty, as it is on a brand-new install before the pump plugin is first chosen.
     */
    fun apply(preferences: Preferences, config: Config, activePump: String): List<BooleanKey> {
        if (!config.AAPSCLIENT && config.PUMPDRIVERS && activePump != VIRTUAL_PUMP) return emptyList()
        if (preferences.get(StringNonKey.DefaultsResetBuild).isNotEmpty() ||
            preferences.get(BooleanNonKey.GeneralSetupWizardProcessed)
        ) return emptyList()
        val build = config.VERSION_NAME + "@" + config.HEAD
        val changed = AGREED.filter { (key, value) ->
            if (preferences.get(key) == value) return@filter false
            if (config.AAPSCLIENT && key.sync?.direction == SyncDirection.Bidirectional) preferences.putRemote(key, value, 0L)
            else preferences.put(key, value)
            true
        }.keys.toList()
        preferences.put(StringNonKey.DefaultsResetBuild, build)
        return changed
    }
}
