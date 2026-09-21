package app.aaps.core.utils

/**
 * Registry of every coded/ported automation "key" from OpenAPSAutoISFPlugin.kt's readyToRun()/markRun()
 * calls -- lets AutomationPlugin.kt classify native Automation-tab event titles as EXACT/CLOSE/NONE
 * matches against them, so the ApsAutoIsfCustomAutomationsEnabled suppression can be scoped down from
 * "block every native automation" to "block only ones that plausibly duplicate a coded one".
 *
 * No cross-module dependency exists between plugins:aps (where the keys live) and plugins:automation
 * (where they're consumed) -- both already depend on core:utils, so the list is kept here and must be
 * updated by hand whenever OpenAPSAutoISFPlugin.kt gains/loses a readyToRun()/markRun() key (last synced 2026-09-21:
 * StuckRisingSlowly, FastRiseToggleTT, LowReboundGuardToggleTT added). Extracted via:
 *   grep -oE 'readyToRun\("[^"]+"' OpenAPSAutoISFPlugin.kt | sed -E 's/readyToRun\("//;s/"$//' | sort -u
 */
object CodedAutomationNames {

    enum class MatchType { EXACT, CLOSE, NONE }

    // CarePortal / graph markers written only when Tier 3 actually fires locally
    // (addCarePortalNote("UamBst") + addGraphAnnouncement("B")). Live uploads both to the
    // shared NS site; Virtual then shows them as if this phone had boosted. Filter those
    // two literals on VirtualPump (not Client) -- see AutoIsfHistoryExporter /
    // PrepareTreatmentsDataWorker / StoreDataForDbImpl.
    const val UAM_BOOST_NOTE = "UamBst"
    const val UAM_BOOST_GRAPH_ANNOUNCEMENT = "B"
    // Live writes these when launchAnyDeskDirect() succeeds/fails. Virtual never launches
    // (isRealLoopPhone()), so any AdOn/AdMs on Virtual are Live NS echoes -- same filter sites
    // as UamBst. Exact literals only (AIV can join several notes in a minute; the TE itself is
    // still a single "AdOn" / "AdMs" row).
    const val ANYDESK_LAUNCH_OK_NOTE = "AdOn"
    const val ANYDESK_LAUNCH_MISS_NOTE = "AdMs"

    fun isUamBoostNote(note: String?): Boolean = note?.trim() == UAM_BOOST_NOTE

    fun isUamBoostGraphAnnouncement(note: String?): Boolean = note?.trim() == UAM_BOOST_GRAPH_ANNOUNCEMENT

    fun isAnyDeskLaunchNote(note: String?): Boolean {
        val t = note?.trim() ?: return false
        return t == ANYDESK_LAUNCH_OK_NOTE || t == ANYDESK_LAUNCH_MISS_NOTE
    }

    val KEYS: List<String> = listOf(
        "50SetRecent", "50pcMakes5.7", "AcceUp0.5", "AcceWeightDownTT", "AcceWeightHighDownTT", "AcceWeightHighUpTT",
        "AcceWeightUpTT", "ActivityOff", "ActivityProf50", "ActivityTTReversal", "AlarmHypo1", "AlarmHypo2",
        "AnyDeskRestartActionTT", "AutoApkInstall", "AutoIsfMaxLowDownTT", "AutoIsfMaxLowUpTT",
        "AutoIsfMaxNormalDownTT", "AutoIsfMaxNormalUpTT", "BasalUp", "Battery1pc", "BatteryOver1pc", "Bolus2",
        "BolusGiven", "BolusGivenBg3", "BolusGivenBg3BlockNote", "BolusGivenMild", "BolusGivenMildFailsafe",
        "BoostIobMaxDownTT", "BoostIobMaxUpTT", "BoostMaxDownTT", "BoostMaxUpTT", "BoostScaleDownTT",
        "BoostScaleUpTT", "BoostToggleTT", "CarbsStopTT1", "CarbsStopTT57", "CarbsTHoff", "CleanGraphTT",
        "CloudLogsUploadTT", "ConnectPod", "DuraWeightDownTT", "DuraWeightUpTT", "EarlyDawnSlowRise",
        "EveningIobCeiling", "EveningTH", "ExerciseLimitAcce",         "ExportSettingsPodActivation", "Extra50", "FastRiseToggleTT",
        "GentleHypoRisk", "Graph2ToggleTT", "Graph5ToggleTT", "HiBrkTwilight", "High6PP", "High6PPoff", "HighDaytimeBrake",
        "HighEveNightBrake", "HighNight00AM", "HighOldPod", "HighPP130Off", "HigherIsfRangeWeightDownTT",
        "HigherIsfRangeWeightUpTT", "LibreOffsetDownTT", "LibreOffsetUpTT", "LibreOver12Backfill",
        "LibreSlopeDownTT", "LibreSlopeUpTT", "LibreSpecialShadowMetricsLog", "LibreUkf1ToggleTT",
        "LibreUkf2ToggleTT",         "LocationSmsThisPhoneTT", "LocationSmsToggleTT", "LowBgTierAReset",
        "LowBgTierAResetScan", "LowReboundGuardToggleTT", "MJ2old", "MJ3old", "MJ4", "MJ5", "MJoff", "MJrecentCurrProfAcce", "MildBoostDownTT",
        "MildBoostUpTT", "MjKotlinButtonsToggleTT", "MjRestoreActionTT", "MjStartActionTT", "MjStateActiveTT",
        "MjStateMj2TT", "MjStateMj3TT", "MjStateNoMjTT", "MoreMJ", "MorningRoleSwap", "NightAcce", "NightFrSkip",
        "NightIobCeiling", "Not50Recently", "OffHighProf", "OldPod2", "OvernightDuraRescue", "PP50Off",
        "PeakInsulinTimeDownTT", "PeakInsulinTimeUpTT", "PersistentRiseRelease", "Pod1", "Pod2",
        "PodChangeHighPP130", "PoorResponseRescueStage1", "PoorResponseRescueStage2", "PpWeightDownTT",
        "PpWeightHighDownTT", "PpWeightHighUpTT", "PpWeightRevertUnder8_5", "PpWeightUpTT", "PreSoakSensor24hrs",
        "PrepareSet50", "ProfileBatchAutoToggleTT", "ProfileBatchRevertC", "ProfileBatchRevertCToggleTT",
        "ProfileBatchRevertToggleTT", "ProfileBatchSetATT", "ProfileBatchSetBTT", "ProfileBatchSetCTT",
        "ProfileBatchStep", "ProfileLowTT", "ProfileRoleSanityCheck", "ProfileStandardTT", "RecentPod",
        "RecentPodOff", "SemiTwilightAcce", "SensorAgeAutoOff", "SensorAgeAutoOn", "SensorAgeCodeToggleTT",
        "SensorAgeToggleTT", "SensorS1hr", "SensorS2hr", "ShizukuApkInstallTT", "Shower12", "SkittlesHypoRisk",
        "SmbDeliveryDownTT", "SmbDeliveryUpTT", "SmbOffsetDownTT", "SmbOffsetUpTT", "StageAaps333NewestTT",
        "StepsSteroidsOff", "SteroidIncrease130ActionTT", "SteroidIncrease150ActionTT", "SteroidIncrease190ActionTT",
        "SteroidIncrease250ActionTT", "SteroidKotlinButtonToggleTT", "SteroidStartActionTT",
        "SteroidTurnOffActionTT", "StuckHighRescue", "StuckRisingSlowly", "Sub75HeavyDelivery", "T80Off3ok", "TT57Reversal", "Test3",
        "Tier3BoostToggleTT", "T3UnrestrictedToggleTT", "TodOffset0002DownTT", "TodOffset0002UpTT", "TodOffset0204DownTT", "TodOffset0204UpTT",
        "TodOffset0406DownTT", "TodOffset0406UpTT", "TodOffset0609DownTT", "TodOffset0609UpTT",
        "TodOffset0912DownTT", "TodOffset0912UpTT", "TodOffset1218DownTT", "TodOffset1218UpTT",
        "TodOffset1822DownTT", "TodOffset1822UpTT", "TodOffset2200DownTT", "TodOffset2200UpTT", "TodOffsetsZero",
        "TwilightTH15Acce", "UamBoostHiBrkBlockNote", "UamBst", "Ukf1DeltaMetricsLog", "Ukf1DosingToggleTT",
        "UkfSet1DeltaMetricsLog", "UnexplainedHighTierC", "Usual2forTH", "VirtualPseudoWizard", "WizardPctDownTT",
        "WizardPctUpTT", "iobTHDaytimeFloor"
    )

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Classifies [nativeTitle] (a native Automation-tab event's title) against the coded registry.
     * EXACT: normalized title equals a coded key exactly (case/punctuation-insensitive). CLOSE: one
     * normalized string contains the other (substring either direction) without being exactly equal --
     * deliberately simple (no edit-distance/fuzzy scoring) so it's easy to reason about; false positives
     * just mean one extra harmless review item in the popup, not a silent wrong suppression. NONE: no
     * relationship at all -- never touched by the coded-automations suppression.
     */
    fun classify(nativeTitle: String): MatchType {
        val normalizedTitle = normalize(nativeTitle)
        if (normalizedTitle.isEmpty()) return MatchType.NONE
        var close = false
        for (key in KEYS) {
            val normalizedKey = normalize(key)
            if (normalizedKey.isEmpty()) continue
            if (normalizedTitle == normalizedKey) return MatchType.EXACT
            if (normalizedTitle.contains(normalizedKey) || normalizedKey.contains(normalizedTitle)) close = true
        }
        return if (close) MatchType.CLOSE else MatchType.NONE
    }
}
