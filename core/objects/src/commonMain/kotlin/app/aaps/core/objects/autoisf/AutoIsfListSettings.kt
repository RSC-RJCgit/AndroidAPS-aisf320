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

/** List 1 row that opens the coded profile picker. */
private val CODED_PROFILES_ROW = List1Row("Re-pick coded profiles", "Standard, Low, Steroid", 0.0, null, pickProfiles = true, loopValueKey = null)

data class List1Row(
    val label: String,
    val current: String,
    val downMmol: Double,
    val upMmol: Double?,
    val readOnly: Boolean = false,
    val pickProfiles: Boolean = false,
    val loopValueKey: String? = downMmol.takeIf { it > 0 }?.toString(),
)

fun numberedListRows(rows: List<List1Row>): List<List1Row> =
    rows.mapIndexed { index, row -> row.copy(label = "${index + 1}. ${row.label}") }

/** The same values are used in the local lists and in the loop phone's status report. */
class AutoIsfListSettings(private val preferences: Preferences, private val decimalFormatter: DecimalFormatter) {

    fun profilePickerRows(): List<List1Row> = numberedListRows(listOf(CODED_PROFILES_ROW))

    fun list1Rows(insulinPeakText: String, runningProfileName: String): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        val two: (Double) -> String = { decimalFormatter.to2Decimal(it) }
        val one: (Double) -> String = { decimalFormatter.to1Decimal(it) }
        return numberedListRows(listOf(
            List1Row("SMBdel base + mild-Bst", "base=${two(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))}, mildBst=${two(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}", 5.002, 5.004),
            List1Row("Tog Libre sens on/off", onOff(preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)), 5.006, null),
            List1Row("Tog Bst autos(all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)), 5.008, null),
            List1Row("Tog coded automations (all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfCodedAutomationsEnabled)), 5.248, null),
            List1Row("pp ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)), 5.012, 5.014),
            List1Row("acce ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)), 5.016, 5.018),
            List1Row("Dura weight", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfDuraWeight)), 5.022, 5.024),
            List1Row("Libre slope", decimalFormatter.to2Decimal(preferences.get(DoubleNonKey.ApsAutoIsfLibreSlopeOrig)), 5.026, 5.028),
            List1Row("Libre offset", decimalFormatter.to2Decimal(preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)), 5.032, 5.034),
            List1Row("Live Libre slope/offset", libreLiveText(), 0.0, null, readOnly = true, loopValueKey = "libreLive"),
            List1Row("SMB offset", one(preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride)), 5.036, 5.038),
            List1Row("Clean main graph", "no SMBs, solid green", 5.042, null, loopValueKey = null),
            List1Row("Wizard bolus %", preferences.get(IntKey.OverviewBolusPercentage).toString(), 5.046, 5.048),
            List1Row("Mild boost", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)), 5.052, 5.054),
            List1Row("pp ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)), 5.056, 5.058),
            List1Row("acce ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)), 5.062, 5.064),
            List1Row("higher ISF range Wt", one(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)), 5.068, 5.070),
            List1Row("Peak insulin time", insulinPeakText, 5.074, 5.076),
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
            List1Row("MJ state: MJ active", "set this state", 5.222, null, loopValueKey = null),
            List1Row("MJ state: MJ2", "set this state", 5.224, null, loopValueKey = null),
            List1Row("MJ state: MJ3", "set this state", 5.146, null, loopValueKey = null),
            List1Row("MJ state: NOMJremains", "set this state", 5.144, null, loopValueKey = null),
            List1Row("Profile: Standard", preferences.get(StringKey.ApsAutoIsfStandardProfileName), 5.148, null),
            List1Row("Profile: Low", preferences.get(StringKey.ApsAutoIsfLowProfileName), 5.150, null),
            List1Row("Tier now", tierNowText(runningProfileName), 0.0, null, readOnly = true, loopValueKey = "tierNow"),
            List1Row("Tier set A", "Standard and Low", 5.216, null, loopValueKey = null),
            List1Row("Tier set B", "Standard and Low", 5.218, null, loopValueKey = null),
            List1Row("Tier set C", "Standard and Low", 5.220, null, loopValueKey = null),
            CODED_PROFILES_ROW,
            List1Row("Libre UKF set 1", onOff(preferences.get(BooleanNonKey.ApsAutoIsfFslUseUkfSmoothing)), 5.152, null),
            List1Row("Sensor age code", onOff(preferences.get(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)), 5.156, null),
            List1Row("Hypo alarm state: set AlarmRecent", "sets the alarm time to now", 5.240, null, loopValueKey = null),
            List1Row("Hypo alarm state: clear", "NoAlarmRecent", 5.242, null, loopValueKey = null),
        ))
    }

    fun list2Rows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        return numberedListRows(listOf(
            List1Row("MJ injection", "low, 0.35, 70", 5.158, null, loopValueKey = null),
            List1Row("MJ restore", "standard, 0.50, 70", 5.160, null, loopValueKey = null),
            List1Row("Steroids on", preferences.get(StringKey.ApsAutoIsfSteroid110ProfileName), 5.162, null),
            List1Row("MJ buttons", onOff(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)), 5.164, null),
            List1Row("Steroid buttons", onOff(preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)), 5.166, null),
            List1Row("Steroids 130", preferences.get(StringKey.ApsAutoIsfSteroid130ProfileName), 5.168, null),
            List1Row("Steroids 150", preferences.get(StringKey.ApsAutoIsfSteroid150ProfileName), 5.170, null),
            List1Row("Steroids 190", preferences.get(StringKey.ApsAutoIsfSteroid190ProfileName), 5.172, null),
            List1Row("Steroids 250", preferences.get(StringKey.ApsAutoIsfSteroid250ProfileName), 5.174, null),
            List1Row("Steroids off", preferences.get(StringKey.ApsAutoIsfSteroid100ProfileName), 5.176, null),
            List1Row("Tier 3 UAM boost", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)), 5.194, null),
            List1Row("Profile batch auto", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchAutoEnabled)), 5.210, null),
            List1Row("Profile batch hold A", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertEnabled)), 5.212, null),
            List1Row("Profile batch hold C", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertCEnabled)), 5.214, null),
            List1Row("AutoISF calcs UKF1", onOff(preferences.get(BooleanKey.ApsAutoIsfUseUkf1ForDosing)), 5.196, null),
            List1Row("Location texts", onOff(preferences.get(BooleanKey.AutomationCodedLocationsEnabled)), 5.198, null),
            List1Row("Stepcount import from remote main AAPS phone", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)), 5.206, null),
            List1Row("MJ state copy from the loop phone", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual)), 5.236, null),
            List1Row("Fast rise", onOff(preferences.get(BooleanKey.ApsAutoIsfFastRiseEnabled)), 5.226, null),
            List1Row("LoReb", onOff(preferences.get(BooleanKey.ApsAutoIsfLowReboundGuardEnabled)), 5.228, null),
            List1Row("T3 unrestricted", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled)), 5.230, null),
            List1Row("Insulin totals row", onOff(preferences.get(BooleanKey.ApsAutoIsfShowInsulinTotals)), 5.232, null),
            List1Row("Loop interval", if (preferences.get(BooleanKey.ApsAutoIsfLoopEveryMinute)) "every 1 min" else "every 5 min", 5.234, null),
            List1Row("Location text phone", preferences.get(StringKey.AutomationLocationSmsDeviceModel).ifBlank { onOff(false) }, 5.204, null),
            List1Row("Send AnyDesk restart", "send now", 5.178, null, loopValueKey = null),
            List1Row("Boost scale", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostScale)), 5.182, 5.184),
            List1Row("Boost max", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostMaxBolus)), 5.186, 5.188),
            List1Row("Boost IOB max", preferences.get(IntKey.ApsAutoIsfUamBoostMaxIobPercent).toString(), 5.190, 5.192),
            List1Row("Stage newest APK (keep 20)", newestApkText(), 5.202, null, loopValueKey = null),
            List1Row("Install newest APK (Shizuku)", newestApkText(), 5.200, null, loopValueKey = null),
            List1Row("ADB wireless: attempt Shizuku start", adbPortText(), 5.208, null, loopValueKey = null),
        ))
    }

    fun snapshot(insulinPeakText: String, runningProfileName: String): Map<String, String> = buildMap {
        (list1Rows(insulinPeakText, runningProfileName) + list2Rows()).forEach { row ->
            row.loopValueKey?.let { put(it, row.current) }
        }
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
            standardRungs = listOf(
                preferences.get(StringKey.ApsAutoIsfStandard100ProfileName),
                preferences.get(StringKey.ApsAutoIsfStandard105ProfileName),
                preferences.get(StringKey.ApsAutoIsfStandard110ProfileName),
            ),
            lowRungs = listOf(
                preferences.get(StringKey.ApsAutoIsfLow70ProfileName),
                preferences.get(StringKey.ApsAutoIsfLow80ProfileName),
                preferences.get(StringKey.ApsAutoIsfLow90ProfileName),
            ),
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
