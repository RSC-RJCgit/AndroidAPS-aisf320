package app.aaps.core.utils

/**
 * Registry of every coded/ported automation "key" from OpenAPSAutoISFPlugin.kt's readyToRun()/markRun()
 * calls -- lets AutomationPlugin.kt classify native Automation-tab event titles as EXACT/CLOSE/NONE
 * matches against them, so the ApsAutoIsfCustomAutomationsEnabled suppression can be scoped down from
 * "block every native automation" to "block only ones that plausibly duplicate a coded one".
 *
 * No cross-module dependency exists between plugins:aps (where the keys live) and plugins:automation
 * (where they're consumed) -- both already depend on core:utils, so the list is kept here and must be
 * updated by hand whenever OpenAPSAutoISFPlugin.kt gains/loses a readyToRun()/markRun() key. Extracted via:
 *   grep -oE 'readyToRun\("[^"]+"' OpenAPSAutoISFPlugin.kt | sed -E 's/readyToRun\("//;s/"$//' | sort -u
 */
object CodedAutomationNames {

    enum class MatchType { EXACT, CLOSE, NONE }

    val KEYS: List<String> = listOf(
        "50SetRecent", "50pcMakes5.7", "AcceUp0.5", "AcceWeightDownTT", "AcceWeightHighDownTT",
        "AcceWeightHighUpTT", "AcceWeightUpTT", "ActivityOff", "ActivityProf50", "ActivityTTReversal",
        "AlarmHypo1", "AlarmHypo2", "AutoIsfMaxLowDownTT", "AutoIsfMaxLowUpTT", "AutoIsfMaxNormalDownTT",
        "AutoIsfMaxNormalUpTT", "BasalUp", "Battery1pc", "BatteryOver1pc", "Bolus2", "BolusGiven",
        "BolusGivenBg3", "BolusGivenMild", "BolusGivenMildFailsafe", "BoostToggleTT", "CarbsStopTT1",
        "CarbsStopTT57", "CarbsTHoff", "CleanGraphTT", "CloudLogsUploadTT", "ConnectPod",
        "DuraWeightDownTT", "DuraWeightUpTT", "EveningTH", "ExerciseLimitAcce",
        "ExportSettingsPodActivation", "Extra50", "GentleHypoRisk", "Graph2ToggleTT", "Graph5ToggleTT",
        "High6PP", "High6PPoff", "HighNight00AM", "HighOldPod", "HighPP130Off",
        "HigherIsfRangeWeightDownTT", "HigherIsfRangeWeightUpTT", "LibreOffsetDownTT", "LibreOffsetUpTT",
        "LibreSlopeDownTT", "LibreSlopeUpTT", "MJ2old", "MJ3old", "MJ4", "MJ5", "MJoff",
        "MJrecentCurrProfAcce", "MildBoostDownTT", "MildBoostUpTT", "MoreMJ", "NightAcce",
        "Not50Recently", "OffHighProf", "OldPod2", "PP50Off", "PeakInsulinTimeDownTT",
        "PeakInsulinTimeUpTT", "Pod1", "Pod2", "PodChangeHighPP130", "PpWeightDownTT",
        "PpWeightHighDownTT", "PpWeightHighUpTT", "PpWeightRevertUnder8_5", "PpWeightUpTT",
        "PreSoakSensor24hrs", "PrepareSet50", "RecentPod", "RecentPodOff", "SemiTwilightAcce",
        "SensorAgeToggleTT", "SensorS1hr", "SensorS2hr", "Shower12", "SkittlesHypoRisk",
        "SmbDeliveryDownTT", "SmbDeliveryUpTT", "SmbOffsetDownTT", "SmbOffsetUpTT", "StepsSteroidsOff",
        "T80Off3ok", "TT57Reversal", "Test3", "TodOffset0002DownTT", "TodOffset0002UpTT",
        "TodOffset0204DownTT", "TodOffset0204UpTT", "TodOffset0406DownTT", "TodOffset0406UpTT",
        "TodOffset0609DownTT", "TodOffset0609UpTT", "TodOffset0912DownTT", "TodOffset0912UpTT",
        "TodOffset1218DownTT", "TodOffset1218UpTT", "TodOffset1822DownTT", "TodOffset1822UpTT",
        "TodOffset2200DownTT", "TodOffset2200UpTT", "TwilightTH15Acce", "Usual2forTH",
        "WizardPctDownTT", "WizardPctUpTT", "iobTHDaytimeFloor"
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
