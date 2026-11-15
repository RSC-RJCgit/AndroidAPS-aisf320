package app.aaps.core.objects.autoisf

import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.readTier
import app.aaps.core.keys.tierSummary

internal val standardTierRoleKeys = listOf(
    StringKey.ApsAutoIsfStandard100ProfileName,
    StringKey.ApsAutoIsfStandard105ProfileName,
    StringKey.ApsAutoIsfStandard110ProfileName,
)
internal val lowTierRoleKeys = listOf(
    StringKey.ApsAutoIsfLow70ProfileName,
    StringKey.ApsAutoIsfLow80ProfileName,
    StringKey.ApsAutoIsfLow90ProfileName,
)
internal val autoIsfProfileRoleKeys = listOf(
    StringKey.ApsAutoIsfStandardProfileName,
    StringKey.ApsAutoIsfLowProfileName,
) + standardTierRoleKeys + lowTierRoleKeys + listOf(
    StringKey.ApsAutoIsfSteroid100ProfileName,
    StringKey.ApsAutoIsfSteroid110ProfileName,
    StringKey.ApsAutoIsfSteroid130ProfileName,
    StringKey.ApsAutoIsfSteroid150ProfileName,
    StringKey.ApsAutoIsfSteroid190ProfileName,
    StringKey.ApsAutoIsfSteroid250ProfileName
)

/** List 1 row that opens the coded profile picker. */
private val CODED_PROFILES_ROW = List1Row("Re-pick coded profiles (Standard/Low/SteroidTier)", "Standard, Low, Steroid", 0.0, null, pickProfiles = true, loopValueKey = null)

/** List 2 graph switches, applied on this phone only (2026-11-16, per explicit request, as 3426's graph checkboxes). */
const val LOCAL_UKF_CALIBRATION_ON = -44.0
const val LOCAL_UKF_CALIBRATION_OFF = -45.0
const val LOCAL_GRAPH5_BGL_ONLY_ON = -46.0
const val LOCAL_GRAPH5_BGL_ONLY_OFF = -47.0

/** List 2 "Send AnyDesk restart (local test)" (2026-11-16, per explicit request, as 3426's 5.180): opens AnyDesk on the phone tapped. */
const val LOCAL_ANYDESK_THIS_PHONE = -48.0

data class List1Row(
    val label: String,
    val current: String,
    val downMmol: Double,
    val upMmol: Double?,
    val readOnly: Boolean = false,
    val pickProfiles: Boolean = false,
    val loopValueKey: String? = downMmol.takeIf { it > 0 }?.toString(),
    // One row with several codes, each its own button (2026-11-16, per explicit request: the rows v3 combines). downMmol is the first.
    val choices: List<Pair<String, Double>> = emptyList(),
)

fun numberedListRows(rows: List<List1Row>): List<List1Row> =
    rows.mapIndexed { index, row -> row.copy(label = "${index + 1}. ${row.label}") }

/** The same values are used in the local lists and in the loop phone's status report. */
class AutoIsfListSettings(private val preferences: Preferences, private val decimalFormatter: DecimalFormatter) {

    fun profilePickerRows(): List<List1Row> = numberedListRows(listOf(CODED_PROFILES_ROW))

    // 2026-11-16, per explicit request: the row names are 3426's, so the same row reads the same in v3 and v4. Codes unchanged.
    fun list1Rows(insulinPeakText: String, runningProfileName: String): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        val two: (Double) -> String = { decimalFormatter.to2Decimal(it) }
        val one: (Double) -> String = { decimalFormatter.to1Decimal(it) }
        return numberedListRows(listOf(
            List1Row("SMBdel base + mild-Bst", "base=${two(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))}, mildBst=${two(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}", 5.002, 5.004),
            List1Row("Tog Libre sens on/off", onOff(preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)), 5.006, null),
            List1Row("Tog Bst autos(all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)), 5.008, null),
            List1Row("pp ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)), 5.012, 5.014),
            List1Row("acce ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)), 5.016, 5.018),
            List1Row("dura ISF Wt", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfDuraWeight)), 5.022, 5.024),
            List1Row("Libre slope (Or)", decimalFormatter.to2Decimal(preferences.get(DoubleNonKey.ApsAutoIsfLibreSlopeOrig)), 5.026, 5.028),
            List1Row("Libre Offset (Or)", decimalFormatter.to2Decimal(preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)), 5.032, 5.034),
            List1Row("Live Libre slope/offset (view only)", libreLiveText(), 0.0, null, readOnly = true, loopValueKey = "libreLive"),
            List1Row("SMB offset", one(preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride)), 5.036, 5.038),
            List1Row("clean grph view", "no SMBs, solid green", 5.042, null, loopValueKey = null),
            List1Row("Wizard bolus %", preferences.get(IntKey.OverviewBolusPercentage).toString(), 5.046, 5.048),
            List1Row("MildBst ratio", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)), 5.052, 5.054),
            List1Row("pp ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)), 5.056, 5.058),
            List1Row("acce ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)), 5.062, 5.064),
            List1Row("higher ISF range Wt", one(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)), 5.068, 5.070),
            List1Row("peak insulin time", insulinPeakText, 5.074, 5.076),
            List1Row("autoISF max (lowBG)", one(preferences.get(DoubleKey.ApsAutoIsfMaxLow)), 5.080, 5.082),
            List1Row("autoISF max (N)", one(preferences.get(DoubleKey.ApsAutoIsfMax)), 5.086, 5.088),
            List1Row("T1 tod offset 00-02h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0002)), 5.092, 5.094),
            List1Row("T2 tod offset 02-04h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0204)), 5.098, 5.100),
            List1Row("T3 tod offset 04-06h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0406)), 5.104, 5.106),
            List1Row("T4 tod offset 06-09h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0609)), 5.110, 5.112),
            List1Row("T5 tod offset 09-12h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0912)), 5.116, 5.118),
            List1Row("T6 tod offset 12-18h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset1218)), 5.122, 5.124),
            List1Row("T7 tod offset 18-22h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset1822)), 5.128, 5.130),
            List1Row("T8 tod offset 22-00h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset2200)), 5.134, 5.136),
            List1Row("Tog Graph2 (carb model curve) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)), 5.138, null),
            List1Row("Cloud logs upload", "send now", 5.140, null, loopValueKey = null),
            List1Row("Tog Graph5 (main clone) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowGraph5)), 5.142, null),
            // 2026-11-16, per explicit request: these rows are combined and in the same order as v3, so both have the same numbers.
            List1Row(
                "MJ state (manual override)", "MJ active, MJ2, MJ3 or NOMJremains", 5.222, null, loopValueKey = null,
                choices = listOf("MJ active" to 5.222, "MJ2" to 5.224, "MJ3" to 5.146, "NOMJremains" to 5.144),
            ),
            List1Row(
                "Switch to Standard/Low profile now",
                "Standard ${preferences.get(StringKey.ApsAutoIsfStandardProfileName)}, Low ${preferences.get(StringKey.ApsAutoIsfLowProfileName)}",
                5.148, null, loopValueKey = null,
                choices = listOf(
                    "Standard (${preferences.get(StringKey.ApsAutoIsfStandardProfileName)})" to 5.148,
                    "Low (${preferences.get(StringKey.ApsAutoIsfLowProfileName)})" to 5.150,
                ),
            ),
            List1Row(
                "Set Standard+Low tier (A/B/C)", tierNowText(runningProfileName), 5.216, null, loopValueKey = "tierNow",
                choices = listOf("Tier A" to 5.216, "Tier B" to 5.218, "Tier C" to 5.220),
            ),
            CODED_PROFILES_ROW,
            List1Row("Sensor age code", onOff(preferences.get(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)), 5.156, null),
            List1Row(
                "Hypo alarm state (manual)", "AlarmRecent (set) or NoAlarmRecent (clear)", 5.240, null, loopValueKey = null,
                choices = listOf("AlarmRecent (set)" to 5.240, "NoAlarmRecent (clear)" to 5.242),
            ),
            List1Row("Tog coded automations (all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfCodedAutomationsEnabled)), 5.248, null),
            List1Row("Tog UKFset1 (Virtual live comparison) on/off", onOff(preferences.get(BooleanNonKey.ApsAutoIsfFslUseUkfSmoothing)), 5.152, null),
        ))
    }

    fun list2Rows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        return numberedListRows(listOf(
            List1Row("MJ start", "low, 0.35, 70", 5.158, null, loopValueKey = null),
            List1Row("MJ restore", "standard, 0.50, 70", 5.160, null, loopValueKey = null),
            List1Row("Steroid start", preferences.get(StringKey.ApsAutoIsfSteroid110ProfileName), 5.162, null),
            List1Row("MJ Kotlin buttons on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)), 5.164, null),
            List1Row("Steroid Kotlin button on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)), 5.166, null),
            List1Row("Steroid increase 110 to 130", preferences.get(StringKey.ApsAutoIsfSteroid130ProfileName), 5.168, null),
            List1Row("Steroid increase 130 to 150", preferences.get(StringKey.ApsAutoIsfSteroid150ProfileName), 5.170, null),
            List1Row("Steroid increase 150 to 190", preferences.get(StringKey.ApsAutoIsfSteroid190ProfileName), 5.172, null),
            List1Row("Steroid increase 190 to 250", preferences.get(StringKey.ApsAutoIsfSteroid250ProfileName), 5.174, null),
            List1Row("Steroids OFF", preferences.get(StringKey.ApsAutoIsfSteroid100ProfileName), 5.176, null),
            List1Row("Tier 3 UAM Boost on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)), 5.194, null),
            List1Row("Profile batch auto on/off (Toggle1)", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchAutoEnabled)), 5.210, null),
            List1Row("Revert profile batch to basic (Toggle2)", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertEnabled)), 5.212, null),
            List1Row("Revert profile batch to TierC (Toggle3)", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertCEnabled)), 5.214, null),
            List1Row("AutoISF calcs: UKF1 vs LibreSpecial EMA", onOff(preferences.get(BooleanKey.ApsAutoIsfUseUkf1ForDosing)), 5.196, null),
            List1Row("Location SMS + CarePortal notes on/off", onOff(preferences.get(BooleanKey.AutomationCodedLocationsEnabled)), 5.198, null),
            List1Row("Stepcount import from remote main AAPS phone on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)), 5.206, null),
            List1Row("FastRise SMB tiers on/off (test)", onOff(preferences.get(BooleanKey.ApsAutoIsfFastRiseEnabled)), 5.226, null),
            List1Row("Post-low rebound guard LoReb on/off (test)", onOff(preferences.get(BooleanKey.ApsAutoIsfLowReboundGuardEnabled)), 5.228, null),
            List1Row("T3 unrestricted compare on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled)), 5.230, null),
            List1Row("Insulin totals row on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowInsulinTotals)), 5.232, null),
            List1Row("HP1 help insulin at BG over 8.0 on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfHp1OverrideEnabled)), 5.254, null),
            List1Row("Loop interval: every 1 min / every 5 min", if (preferences.get(BooleanKey.ApsAutoIsfLoopEveryMinute)) "every 1 min" else "every 5 min", 5.234, null),
            List1Row("Location SMS from the loop phone (model)", preferences.get(StringKey.AutomationLocationSmsDeviceModel).ifBlank { onOff(false) }, 5.204, null),
            List1Row("Send AnyDesk restart", "send now", 5.178, null, loopValueKey = null),
            List1Row("Stage newest APK (keep 20)", newestApkText(), 5.202, null),
            List1Row("Install newest APK (Shizuku)", newestApkText(), 5.200, null),
            List1Row("ADB wireless: attempt Shizuku start (Virtual only)", adbPortText(), 5.208, null),
            List1Row("Boost scale (boost_scale)", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostScale)), 5.182, 5.184),
            List1Row("Boost max (boost_max, U)", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostMaxBolus)), 5.186, 5.188),
            List1Row("Boost max IOB (% of max_iob)", preferences.get(IntKey.ApsAutoIsfUamBoostMaxIobPercent).toString(), 5.190, 5.192),
            List1Row("MJ state copy from the loop phone on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual)), 5.236, null),
            List1Row(
                "Graph: UKF line uses libre slope & offset (this phone)", onOff(preferences.get(BooleanKey.Ukf1ApplyLibreCalibration)),
                LOCAL_UKF_CALIBRATION_ON, null, loopValueKey = null,
                choices = listOf("On" to LOCAL_UKF_CALIBRATION_ON, "Off" to LOCAL_UKF_CALIBRATION_OFF),
            ),
            List1Row(
                "Graph5 BGL only (this phone)", onOff(preferences.get(BooleanKey.ApsAutoIsfGraph5BglOnly)),
                LOCAL_GRAPH5_BGL_ONLY_ON, null, loopValueKey = null,
                choices = listOf("On" to LOCAL_GRAPH5_BGL_ONLY_ON, "Off" to LOCAL_GRAPH5_BGL_ONLY_OFF),
            ),
            // 2026-11-16, per explicit request: v3's row 33. Nothing goes to the loop phone. Android only: the Windows client and
            // iPhone have no AnyDesk for Android and record "AdMs" (not opened).
            List1Row(
                "Send AnyDesk restart (local test, this phone, Android only)", "opens AnyDesk on this phone",
                LOCAL_ANYDESK_THIS_PHONE, null, loopValueKey = null,
                choices = listOf("Open AnyDesk on this phone" to LOCAL_ANYDESK_THIS_PHONE),
            ),
        ))
    }

    fun snapshot(insulinPeakText: String, runningProfileName: String): Map<String, String> = buildMap {
        (list1Rows(insulinPeakText, runningProfileName) + list2Rows()).forEach { row ->
            row.loopValueKey?.let { put(it, row.current) }
        }
        // The Standard and Low names, under the codes their rows had before the combined row (2026-11-16).
        put("5.148", preferences.get(StringKey.ApsAutoIsfStandardProfileName))
        put("5.150", preferences.get(StringKey.ApsAutoIsfLowProfileName))
        // Publish only whether ADB has a port, not the port number.
        put("5.208", if (preferences.get(IntKey.ApsAutoIsfAdbConnectPort) > 0) "Port configured" else "Port not set")
        autoIsfProfileRoleKeys.forEach { key -> put("role:${key.key}", preferences.get(key)) }
        put("runningProfile", runningProfileName)
    }

    // The Standard and Low roles and the running profile, each placed on the A, B, C ladders the same way the loop reads them.
    private fun tierNowText(runningProfileName: String): String {
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
        val runningName = runningProfileName
        val reading = readTier(
            standardName = standardName,
            lowName = lowName,
            runningName = runningName,
            standardRungs = standardTierRoleKeys.map { preferences.get(it) },
            lowRungs = lowTierRoleKeys.map { preferences.get(it) },
        )
        return tierSummary(reading, standardName, lowName, runningName)
    }

    private fun newestApkText(): String {
        val newest = preferences.get(LongNonKey.ApsAutoIsfApkNewestNnn)
        return if (newest > 0L) "Newest: $newest" else "Newest: not found yet"
    }

    private fun adbPortText(): String {
        val port = preferences.get(IntKey.ApsAutoIsfAdbConnectPort)
        return if (port > 0) "Port $port" else "Port not set"
    }

    private fun libreLiveText(): String {
        val liveSlope = preferences.get(DoubleKey.FslCalSlope)
        val liveOffset = preferences.get(DoubleKey.FslCalOffset)
        val baseSlope = preferences.get(DoubleNonKey.ApsAutoIsfLibreSlopeOrig)
        val baseOffset = preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)
        val tier = preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjActive)
        return "Live slope ${decimalFormatter.to2Decimal(liveSlope)}, offset ${decimalFormatter.to2Decimal(liveOffset)}. " +
            "Baseline slope ${decimalFormatter.to2Decimal(baseSlope)}, offset ${decimalFormatter.to2Decimal(baseOffset)}. " +
            "Sensor age tier ${if (tier) "on" else "off"}."
    }
}
