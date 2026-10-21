package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Every new build puts the on/off display and AutoISF switches of a Virtual master or a client to an agreed set, once, on
 * its first start (2026-10-21, per explicit request: after an update it was never clear which switches such a phone had).
 * The same set as the v4 app, with this app's switch names.
 *
 * The main master looping phone (a master with a real pump) is never touched: an update must not change how it looks or
 * doses, so the code defaults stay as that phone needs them and [AGREED] is a separate list. Numbers, the Nightscout and
 * pump setup and profiles are not touched either.
 *
 * This app has no clean-graph text, coded-automations or ignore-inactivity switches. Its "custom automations" switch turns
 * on the coded copies of the automations, so it is on here, the same as "coded automations" in the v4 app.
 */
object BuildDefaultsReset {

    /** The agreed switches of a Virtual master or a client (2026-10-21). */
    val AGREED: Map<BooleanKey, Boolean> = mapOf(
        // Display
        BooleanKey.ApsAutoIsfShowInsulinTotals to true,
        BooleanKey.ApsAutoIsfShowCarbModelCurve to true,
        BooleanKey.ApsAutoIsfShowGraph5 to true,
        BooleanKey.ApsAutoIsfShowGraphText to true,
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
        BooleanKey.ApsAutoIsfCustomAutomationsEnabled to true,
        BooleanKey.AutomationStatesEnabled to true,
        BooleanKey.ApsActivityDetection to false,
        BooleanKey.ApsAutoIsfLoopEveryMinute to true,
        BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual to true,
        BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual to true,
        BooleanKey.OverviewUseBolusReminder to false,
    )

    /**
     * Sets the agreed switches when this phone is a client or a Virtual master and this build has not done it yet.
     * Returns the switches it changed.
     */
    fun apply(preferences: Preferences, config: Config, virtualPump: Boolean, headCommit: String): List<BooleanKey> {
        if (!config.AAPSCLIENT && !virtualPump) return emptyList()
        val build = config.VERSION_NAME + "@" + headCommit
        if (preferences.get(StringNonKey.DefaultsResetBuild) == build) return emptyList()
        val changed = AGREED.filter { (key, value) ->
            if (preferences.get(key) == value) return@filter false
            preferences.put(key, value)
            true
        }.keys.toList()
        preferences.put(StringNonKey.DefaultsResetBuild, build)
        return changed
    }
}
