package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.readTier
import app.aaps.core.keys.tierSummary
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values shown in the direct settings lists. Codes are shared with the v4 client. */
internal fun autoIsfListValues(preferences: Preferences, runningProfile: String): JsonObject = buildJsonObject {
    fun onOff(value: Boolean) = if (value) "ON" else "OFF"
    fun decimal(value: Double) = String.format(Locale.US, "%.2f", value)
    fun toggle(code: Double, key: BooleanKey) = put(code.toString(), onOff(preferences.get(key)))
    fun number(code: Double, key: DoubleKey) = put(code.toString(), decimal(preferences.get(key)))
    fun profile(code: Double, key: StringKey) {
        preferences.get(key).takeIf { it.isNotBlank() }?.let { put(code.toString(), it) }
    }

    val smbBase = decimal(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))
    val mildBoost = decimal(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))
    put("5.002", "base=$smbBase, mildBst=$mildBoost")
    toggle(5.006, BooleanKey.ApsAutoIsfOldSensorAdjEnabled)
    toggle(5.008, BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
    number(5.012, DoubleKey.ApsAutoIsfPpWeightNormal)
    number(5.016, DoubleKey.ApsAutoIsfBgAccelWeightNormal)
    number(5.022, DoubleKey.ApsAutoIsfDuraWeightNormal)
    number(5.026, DoubleKey.ApsAutoIsfLibreSlopeOrig)
    number(5.032, DoubleKey.ApsAutoIsfLibreOffsetOrig)
    number(5.036, DoubleKey.ApsAutoIsfSmbOffsetOverride)
    put("5.046", "${preferences.get(IntKey.OverviewBolusPercentage)}%")
    number(5.052, DoubleKey.ApsAutoIsfMildBoostRatio)
    number(5.056, DoubleKey.ApsAutoIsfPpWeightHigh)
    number(5.062, DoubleKey.ApsAutoIsfBgAccelWeightHigh)
    number(5.068, DoubleKey.ApsAutoIsfHighBgWeight)
    put("5.074", "${preferences.get(IntKey.InsulinOrefPeak)} min")
    number(5.08, DoubleKey.ApsAutoIsfMaxLow)
    number(5.086, DoubleKey.ApsAutoIsfMax)
    number(5.092, DoubleKey.ApsAutoIsfTodOffset0002)
    number(5.098, DoubleKey.ApsAutoIsfTodOffset0204)
    number(5.104, DoubleKey.ApsAutoIsfTodOffset0406)
    number(5.11, DoubleKey.ApsAutoIsfTodOffset0609)
    number(5.116, DoubleKey.ApsAutoIsfTodOffset0912)
    number(5.122, DoubleKey.ApsAutoIsfTodOffset1218)
    number(5.128, DoubleKey.ApsAutoIsfTodOffset1822)
    number(5.134, DoubleKey.ApsAutoIsfTodOffset2200)
    toggle(5.138, BooleanKey.ApsAutoIsfShowCarbModelCurve)
    toggle(5.142, BooleanKey.ApsAutoIsfShowGraph5)
    profile(5.148, StringKey.ApsAutoIsfStandardProfileName)
    profile(5.15, StringKey.ApsAutoIsfLowProfileName)
    toggle(5.152, BooleanKey.FslUseUkfSmoothing)
    toggle(5.156, BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)

    profile(5.162, StringKey.ApsAutoIsfSteroid110ProfileName)
    toggle(5.164, BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)
    toggle(5.166, BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)
    profile(5.168, StringKey.ApsAutoIsfSteroid130ProfileName)
    profile(5.17, StringKey.ApsAutoIsfSteroid150ProfileName)
    profile(5.172, StringKey.ApsAutoIsfSteroid190ProfileName)
    profile(5.174, StringKey.ApsAutoIsfSteroid250ProfileName)
    profile(5.176, StringKey.ApsAutoIsfSteroid100ProfileName)
    number(5.182, DoubleKey.ApsAutoIsfUamBoostScale)
    val cap = DoubleKey.ApsAutoIsfUamBoostMaxBolus
    put("5.186", "${decimal(preferences.get(cap).coerceIn(cap.min, cap.max))}U")
    put("5.19", "${String.format(Locale.US, "%.1f", preferences.get(DoubleKey.ApsAutoIsfUamBoostMaxIobPercent))}%")
    toggle(5.194, BooleanKey.ApsAutoIsfUamBoostEnabled)
    toggle(5.196, BooleanKey.ApsAutoIsfUseUkf1ForDosing)
    toggle(5.198, BooleanKey.AutomationCodedLocationsEnabled)
    val newestApk = preferences.get(LongKey.ApsAutoIsfApkNewestNnn).takeIf { it > 0 }
    val apk = newestApk?.let { "Newest: $it" } ?: "Newest: unknown"
    put("5.2", apk)
    put("5.202", apk)
    put("5.204", onOff(preferences.get(StringKey.AutomationLocationSmsDeviceModel).isNotBlank()))
    toggle(5.206, BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)
    toggle(5.21, BooleanKey.ApsAutoIsfProfileBatchAutoEnabled)
    toggle(5.212, BooleanKey.ApsAutoIsfProfileBatchRevertEnabled)
    toggle(5.214, BooleanKey.ApsAutoIsfProfileBatchRevertCEnabled)
    toggle(5.226, BooleanKey.ApsAutoIsfFastRiseEnabled)
    toggle(5.228, BooleanKey.ApsAutoIsfLowReboundGuardEnabled)
    toggle(5.23, BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled)
    toggle(5.232, BooleanKey.ApsAutoIsfShowInsulinTotals)
    put("5.234", if (preferences.get(BooleanKey.ApsAutoIsfLoopEveryMinute)) "every 1 min" else "every 5 min")

    val liveSlope = decimal(preferences.get(DoubleKey.FslCalSlope))
    val liveOffset = decimal(preferences.get(DoubleKey.FslCalOffset))
    val baseSlope = decimal(preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig))
    val baseOffset = decimal(preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig))
    val tier = onOff(preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjActive))
    put("libreLive", "Live slope $liveSlope, offset $liveOffset. Baseline slope $baseSlope, offset $baseOffset. Sensor age tier $tier.")

    val standard = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
    val low = preferences.get(StringKey.ApsAutoIsfLowProfileName)
    val reading = readTier(
        standardName = standard,
        lowName = low,
        runningName = runningProfile,
        standardRungs = listOf(StringKey.ApsAutoIsfStandard100ProfileName, StringKey.ApsAutoIsfStandard105ProfileName, StringKey.ApsAutoIsfStandard110ProfileName)
            .map { preferences.get(it) },
        lowRungs = listOf(StringKey.ApsAutoIsfLow70ProfileName, StringKey.ApsAutoIsfLow80ProfileName, StringKey.ApsAutoIsfLow90ProfileName)
            .map { preferences.get(it) }
    )
    put("tierNow", tierSummary(reading, standard, low, runningProfile))
}
