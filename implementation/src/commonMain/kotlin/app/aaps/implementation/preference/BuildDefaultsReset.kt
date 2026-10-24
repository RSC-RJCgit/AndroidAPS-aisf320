package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.SyncDirection

/**
 * Every new build puts the on/off display and AutoISF switches of a Virtual master or a client to an agreed set, once, on
 * its first start (2026-10-21, per explicit request: after an update it was never clear which switches such a phone had).
 *
 * The main master looping phone (a master with a real pump) is never touched: an update must not change how it looks or
 * doses, so the code defaults stay as that phone needs them and [AGREED] is a separate list. Numbers, the Nightscout and
 * pump setup, profiles and the read-only and pairing settings are not touched either.
 *
 * On a client a shared switch is written the way a master's value is, so it is not sent back to the master; the master's
 * next settings document then gives the master's own value again. On a Virtual master it is written as a normal change,
 * so its clients get it.
 */
object BuildDefaultsReset {

    /** The agreed switches of a Virtual master or a client (2026-10-21). */
    val AGREED: Map<BooleanKey, Boolean> = mapOf(
        // Display
        BooleanKey.ApsAutoIsfShowInsulinTotals to true,
        BooleanKey.ApsAutoIsfShowCarbModelCurve to true,
        BooleanKey.ApsAutoIsfShowGraph5 to true,
        // Clean graph (2026-10-24, per explicit request; were false and true): no text lines, and the automatic clean also
        // hides them. The same as a new install, see FreshInstallDefaults.
        BooleanKey.ApsAutoIsfAutoCleanGraph to true,
        BooleanKey.ApsAutoIsfShowGraphText to false,
        BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled to false,
        BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled to false,
        BooleanKey.AutomationCodedLocationsEnabled to false,
        BooleanKey.OverviewUseBolusAdvisor to false,
        BooleanKey.OverviewUseSuperBolus to false,
        // Dosing
        BooleanKey.ApsUseSmb to true,
        BooleanKey.ApsUseSmbAlways to true,
        BooleanKey.ApsUseSmbWithCob to true,
        BooleanKey.ApsUseSmbWithLowTt to true,
        BooleanKey.ApsUseSmbAfterCarbs to true,
        BooleanKey.ApsUseSmbWithHighTt to false,
        BooleanKey.ApsUseUam to true,
        BooleanKey.ApsUseAutosens to false,
        BooleanKey.ApsUseDynamicSensitivity to false,
        BooleanKey.ApsAlwaysUseShortDeltas to false,
        BooleanKey.ApsUseAutoIsfWeights to true,
        BooleanKey.ApsAutoIsfHighTtRaisesSens to false,
        BooleanKey.ApsAutoIsfLowTtLowersSens to false,
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
        BooleanKey.ApsAutoIsfCustomAutomationsEnabled to false,
        BooleanKey.AutomationStatesEnabled to true,
        BooleanKey.ApsActivityDetection to false,
        BooleanKey.ApsIgnoreInactivityOvernight to true,
        BooleanKey.ApsAutoIsfLoopEveryMinute to true,
        BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual to true,
        BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual to true,
        BooleanKey.OverviewUseBolusReminder to false,
    )

    /** The pump plugin id a Virtual master runs (`PluginBase.pluginId`, the class name). */
    private const val VIRTUAL_PUMP = "VirtualPumpPlugin"

    /** Returns the switches it changed (empty on the main master looping phone, or when this build already did it). */
    fun apply(preferences: Preferences, config: Config, activePump: String): List<BooleanKey> {
        if (!config.AAPSCLIENT && activePump != VIRTUAL_PUMP) return emptyList()
        val build = config.VERSION_NAME + "@" + config.HEAD
        if (preferences.get(StringNonKey.DefaultsResetBuild) == build) return emptyList()
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
