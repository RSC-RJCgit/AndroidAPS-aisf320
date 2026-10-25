package app.aaps.implementation.preference

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Initializes switches for a new Virtual master or client once. Existing installations keep their saved switches across
 * builds; the graph text preference is restored once because a previous build reset it off.
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
     * Initializes fresh Virtual/Client installs once. On existing installs, only restores graph text once; subsequent
     * builds never overwrite saved switches. Returns the switches it changed.
     */
    fun apply(preferences: Preferences, config: Config, virtualPump: Boolean, headCommit: String): List<BooleanKey> {
        if (!config.AAPSCLIENT && !virtualPump) return emptyList()
        val build = config.VERSION_NAME + "@" + headCommit
        if (preferences.get(StringNonKey.DefaultsResetVersion) == RESET_VERSION) return emptyList()

        val isExistingInstall = preferences.get(StringNonKey.DefaultsResetBuild).isNotBlank() ||
            preferences.get(BooleanKey.GeneralSetupWizardProcessed)
        val changed = if (isExistingInstall) {
            if (preferences.get(BooleanKey.ApsAutoIsfShowGraphText)) emptyList()
            else {
                preferences.put(BooleanKey.ApsAutoIsfShowGraphText, true)
                listOf(BooleanKey.ApsAutoIsfShowGraphText)
            }
        } else {
            AGREED.filter { (key, value) ->
                if (preferences.get(key) == value) return@filter false
                preferences.put(key, value)
                true
            }.keys.toList()
        }
        preferences.put(StringNonKey.DefaultsResetBuild, build)
        preferences.put(StringNonKey.DefaultsResetVersion, RESET_VERSION)
        return changed
    }

    private const val RESET_VERSION = "2"
}
