package app.aaps.core.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Names of the coded AutoISF automations from 3.2.1.
 *
 * A native automation title is compared with this list after letters and digits only, in lower case.
 * [MatchType.EXACT] means the names are the same. [MatchType.CLOSE] means one name contains the other.
 * [MatchType.NONE] means there is no match, and that automation is left alone.
 *
 * The list is the 3.2.1 registry. Most of these coded automations do not run in this app yet.
 */
object CodedAutomationNames {

    enum class MatchType { EXACT, CLOSE, NONE }

    val KEYS: List<String> = listOf(
        "50SetRecent", "50pcMakes5.7", "AcceUp0.5", "AcceWeightDownTT", "AcceWeightHighDownTT", "AcceWeightHighUpTT",
        "AcceWeightUpTT", "ActivityOff", "ActivityProf50", "ActivityTTReversal", "AlarmHypo1", "AlarmHypo2",
        "AnyDeskRestartActionTT", "AutoApkInstall", "AutoIsfMaxLowDownTT", "AutoIsfMaxLowUpTT",
        "AutoIsfMaxNormalDownTT", "AutoIsfMaxNormalUpTT", "BasalUp", "Battery1pc", "BatteryOver1pc", "Bolus2",
        "BolusGiven", "BolusGivenBg3", "BolusGivenBg3BlockNote", "BolusGivenMild", "BolusGivenMildFailsafe",
        "BoostIobMaxDownTT", "BoostIobMaxUpTT", "BoostMaxDownTT", "BoostMaxUpTT", "BoostScaleDownTT",
        "BoostScaleUpTT", "BoostToggleTT", "CarbsStopTT1", "CarbsStopTT57", "CarbsTHoff", "CleanGraphTT",
        "CloudLogsUploadTT", "ConnectPod", "DuraWeightDownTT", "DuraWeightUpTT", "EarlyDawnSlowRise",
        "EveningIobCeiling", "EveningTH", "ExerciseLimitAcce", "ExportSettingsPodActivation", "Extra50", "FastRiseToggleTT",
        "GentleHypoRisk", "Graph2ToggleTT", "Graph5ToggleTT", "HiBrkTwilight", "High6PP", "High6PPoff", "HighDaytimeBrake",
        "HighEveNightBrake", "HighNight00AM", "HighOldPod", "HighPP130Off", "HigherIsfRangeWeightDownTT",
        "HigherIsfRangeWeightUpTT", "LibreOffsetDownTT", "LibreOffsetUpTT", "LibreOver12Backfill",
        "LibreSlopeDownTT", "LibreSlopeUpTT", "LibreSpecialShadowMetricsLog", "LibreUkf1ToggleTT",
        "LibreUkf2ToggleTT", "LocationSmsThisPhoneTT", "LocationSmsToggleTT", "LowBgTierAReset",
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

    /**
     * Titles that still need a review. Button actions are left out. An exact name is never listed,
     * because that one stays blocked while custom automations are on. A title already stored in
     * [decisions] is left out too.
     */
    fun pendingCloseTitles(events: List<Pair<String, Boolean>>, decisions: Map<String, Boolean>): List<String> =
        events
            .filter { (_, userAction) -> !userAction }
            .map { (title, _) -> title }
            .distinct()
            .filter { title -> classify(title) == MatchType.CLOSE && title !in decisions }

    /**
     * True when this native automation must not run. [customAutomationsOn] has to be on, and AutoISF
     * has to be the running algorithm; the caller passes that combined flag. An exact name is always
     * blocked. A close name is blocked unless [decisions] stores true for that exact title.
     */
    fun nativeEventSuppressed(title: String, decisions: Map<String, Boolean>, customAutomationsOn: Boolean): Boolean {
        if (!customAutomationsOn) return false
        return when (classify(title)) {
            MatchType.EXACT -> true
            MatchType.CLOSE -> decisions[title] != true
            MatchType.NONE -> false
        }
    }

    fun encodeDecisions(decisions: Map<String, Boolean>): String =
        buildJsonObject {
            decisions.forEach { (title, accepted) -> put(title, accepted) }
        }.toString()

    fun decodeDecisions(raw: String): Map<String, Boolean> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(trimmed) as? JsonObject ?: return emptyMap()
            obj.mapNotNull { (title, value) ->
                val primitive = value as? JsonPrimitive ?: return@mapNotNull null
                val accepted = primitive.booleanOrNull ?: primitive.content.equals("true", ignoreCase = true)
                title to accepted
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
