package app.aaps.ui.compose.overview.chips

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * List 1 / List 2 "This phone" on a Client (List 1 2026-11-05, List 2 2026-11-06, per explicit request): the settings rows
 * change this phone's own preference only. No SMS, no CarePortal note (it would sync to NS and look as if the loop phone
 * changed) and no TT. Rows that switch a profile, an MJ/Steroid state, a tier, the insulin peak (a profile switch in v4), an
 * action (AnyDesk, APK, ADB) or anything else are not here and stay relayed to the loop phone.
 *
 * The change really stays on this phone: the master's broadcast settings never overwrite a client's preferences
 * (RunningConfigurationImpl.applyCold, since chk275), and since 2026-11-06 a client no longer pushes its own edits of
 * Bidirectional keys to the master either (PreferencesClientPublisher is not started; see NSClientV3Plugin). If either
 * automatic sync is ever turned back on, synced keys must come out of this list.
 *
 * KEEP IN STEP: each step and limit is copied from its branch in OpenAPSAutoISFPlugin.applyToggleAction (and its nudge
 * helpers). If a step or limit changes there, change it here too.
 */
internal object ClientLocalListCodes {

    private val codes = listOf(
        5.002, 5.004, 5.006, 5.008, 5.248, 5.012, 5.014, 5.016, 5.018, 5.022, 5.024, 5.026, 5.028, 5.032, 5.034, 5.036, 5.038,
        5.046, 5.048, 5.052, 5.054, 5.056, 5.058, 5.062, 5.064, 5.068, 5.070, 5.080, 5.082, 5.086, 5.088,
        5.092, 5.094, 5.098, 5.100, 5.104, 5.106, 5.110, 5.112, 5.116, 5.118, 5.122, 5.124, 5.128, 5.130, 5.134, 5.136,
        5.138, 5.142, 5.156,
        // List 2 settings rows
        5.164, 5.166, 5.182, 5.184, 5.186, 5.188, 5.190, 5.192, 5.194, 5.196, 5.198, 5.206, 5.210, 5.212, 5.214,
        5.226, 5.228, 5.230, 5.232, 5.234, 5.236,
    )

    fun supports(vararg mmol: Double): Boolean = mmol.all { code -> codes.any { abs(it - code) <= 0.0000001 } }

    // A step down is held at the floor only and a step up at the cap only (nudgeDown / nudgeUp), unless both = true (coerceIn).
    private fun step(value: Double, delta: Double, floor: Double, cap: Double, both: Boolean = false): Double =
        if (both) (value + delta).coerceIn(floor, cap) else if (delta < 0) (value + delta).coerceAtLeast(floor) else (value + delta).coerceAtMost(cap)

    private fun two(value: Double): String = ((value * 100).roundToLong() / 100.0).toString()

    private fun toggle(p: Preferences, key: BooleanKey): String =
        (!p.get(key)).also { p.put(key, it) }.let { "${key.key} ${if (it) "ON" else "OFF"}" }

    private fun toggle(p: Preferences, key: BooleanNonKey): String =
        (!p.get(key)).also { p.put(key, it) }.let { "${key.key} ${if (it) "ON" else "OFF"}" }

    private fun nudge(p: Preferences, key: DoubleKey, delta: Double, floor: Double, cap: Double, both: Boolean = false): String =
        step(p.get(key), delta, floor, cap, both).also { p.put(key, it) }.let { "${key.key} ${two(it)}" }

    // Baseline plus the live weight that follows it: the live one moves too when it equals the baseline.
    private fun nudgeWithLive(p: Preferences, key: DoubleKey, live: DoubleKey, delta: Double, floor: Double, cap: Double): String {
        val current = p.get(key)
        val currentLive = p.get(live)
        val next = step(current, delta, floor, cap)
        p.put(key, next)
        if (abs(currentLive - current) <= 0.001) p.put(live, step(currentLive, delta, floor, cap))
        return "${key.key} ${two(next)}"
    }

    // Libre slope / offset: the baseline is a non-preference key, the live value a preference (nudgeLibreSlope / nudgeLibreOffset).
    private fun nudgeLibre(p: Preferences, orig: DoubleNonKey, live: DoubleKey, delta: Double, floor: Double, cap: Double): String {
        val current = p.get(orig)
        val currentLive = p.get(live)
        val next = (current + delta).coerceIn(floor, cap)
        p.put(orig, next)
        if (abs(currentLive - current) <= 0.001) p.put(live, (currentLive + delta).coerceIn(floor, cap))
        return "${orig.key} ${two(next)}"
    }

    private fun wizardPct(p: Preferences, delta: Int): String {
        val current = p.get(IntKey.OverviewBolusPercentage)
        val next = if (delta < 0) (current + delta).coerceAtLeast(50) else (current + delta).coerceAtMost(100)
        p.put(IntKey.OverviewBolusPercentage, next)
        return "${IntKey.OverviewBolusPercentage.key} $next"
    }

    // nudgeBoostIob: 1 to 100 %.
    private fun boostIob(p: Preferences, delta: Int): String {
        val next = (p.get(IntKey.ApsAutoIsfUamBoostMaxIobPercent) + delta).coerceIn(1, 100)
        p.put(IntKey.ApsAutoIsfUamBoostMaxIobPercent, next)
        return "${IntKey.ApsAutoIsfUamBoostMaxIobPercent.key} $next"
    }

    /** Applies the code to this phone's own preferences. Returns a short "what changed" text, or null when not a local code. */
    fun apply(p: Preferences, mmol: Double): String? {
        if (!supports(mmol)) return null
        fun near(code: Double) = abs(mmol - code) <= 0.0000001
        return when {
            near(5.002) -> nudge(p, DoubleKey.ApsAutoIsfSmbDeliveryBaseline, -0.01, 0.1, 0.5) + ", " + nudge(p, DoubleKey.ApsAutoIsfMildBoostRatio, -0.01, 0.1, 1.0)
            near(5.004) -> nudge(p, DoubleKey.ApsAutoIsfSmbDeliveryBaseline, 0.01, 0.1, 0.5) + ", " + nudge(p, DoubleKey.ApsAutoIsfMildBoostRatio, 0.01, 0.1, 1.0)
            near(5.006) -> toggle(p, BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)
            near(5.008) -> toggle(p, BooleanKey.ApsAutoIsfBoostAutomationsEnabled)
            near(5.248) -> toggle(p, BooleanKey.ApsAutoIsfCodedAutomationsEnabled)
            near(5.012) -> nudgeWithLive(p, DoubleKey.ApsAutoIsfPpWeightNormal, DoubleKey.ApsAutoIsfPpWeight, -0.01, 0.0, 0.15)
            near(5.014) -> nudgeWithLive(p, DoubleKey.ApsAutoIsfPpWeightNormal, DoubleKey.ApsAutoIsfPpWeight, 0.01, 0.0, 0.15)
            near(5.016) -> nudgeWithLive(p, DoubleKey.ApsAutoIsfBgAccelWeightNormal, DoubleKey.ApsAutoIsfBgAccelWeight, -0.05, 0.55, 1.0)
            near(5.018) -> nudgeWithLive(p, DoubleKey.ApsAutoIsfBgAccelWeightNormal, DoubleKey.ApsAutoIsfBgAccelWeight, 0.05, 0.55, 1.0)
            near(5.022) -> nudge(p, DoubleKey.ApsAutoIsfDuraWeight, -0.1, 0.0, 3.0, both = true)
            near(5.024) -> nudge(p, DoubleKey.ApsAutoIsfDuraWeight, 0.1, 0.0, 3.0, both = true)
            near(5.026) -> nudgeLibre(p, DoubleNonKey.ApsAutoIsfLibreSlopeOrig, DoubleKey.FslCalSlope, -0.01, 0.60, 1.00)
            near(5.028) -> nudgeLibre(p, DoubleNonKey.ApsAutoIsfLibreSlopeOrig, DoubleKey.FslCalSlope, 0.01, 0.60, 1.00)
            near(5.032) -> nudgeLibre(p, DoubleNonKey.ApsAutoIsfLibreOffsetOrig, DoubleKey.FslCalOffset, -0.05, 1.20, 1.60)
            near(5.034) -> nudgeLibre(p, DoubleNonKey.ApsAutoIsfLibreOffsetOrig, DoubleKey.FslCalOffset, 0.05, 1.20, 1.60)
            near(5.036) -> nudge(p, DoubleKey.ApsAutoIsfSmbOffsetOverride, -0.1, 0.50, 1.50, both = true)
            near(5.038) -> nudge(p, DoubleKey.ApsAutoIsfSmbOffsetOverride, 0.1, 0.50, 1.50, both = true)
            near(5.046) -> wizardPct(p, -5)
            near(5.048) -> wizardPct(p, 5)
            near(5.052) -> nudge(p, DoubleKey.ApsAutoIsfMildBoostRatio, -0.25, 0.1, 1.0, both = true)
            near(5.054) -> nudge(p, DoubleKey.ApsAutoIsfMildBoostRatio, 0.25, 0.1, 1.0, both = true)
            near(5.056) -> nudge(p, DoubleKey.ApsAutoIsfPpWeightHigh, -0.01, 0.0, 0.15)
            near(5.058) -> nudge(p, DoubleKey.ApsAutoIsfPpWeightHigh, 0.01, 0.0, 0.15)
            near(5.062) -> nudge(p, DoubleKey.ApsAutoIsfBgAccelWeightHigh, -0.01, 0.0, 1.0)
            near(5.064) -> nudge(p, DoubleKey.ApsAutoIsfBgAccelWeightHigh, 0.01, 0.0, 1.0)
            near(5.068) -> nudge(p, DoubleKey.ApsAutoIsfHighBgWeight, -0.1, 0.0, 2.0)
            near(5.070) -> nudge(p, DoubleKey.ApsAutoIsfHighBgWeight, 0.1, 0.0, 2.0)
            near(5.080) -> nudge(p, DoubleKey.ApsAutoIsfMaxLow, -0.1, 1.0, 3.0)
            near(5.082) -> nudge(p, DoubleKey.ApsAutoIsfMaxLow, 0.1, 1.0, 3.0)
            near(5.086) -> nudge(p, DoubleKey.ApsAutoIsfMax, -0.1, 1.0, 3.0)
            near(5.088) -> nudge(p, DoubleKey.ApsAutoIsfMax, 0.1, 1.0, 3.0)
            near(5.092) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0002, -0.1, -2.0, 2.0)
            near(5.094) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0002, 0.1, -2.0, 2.0)
            near(5.098) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0204, -0.1, -2.0, 2.0)
            near(5.100) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0204, 0.1, -2.0, 2.0)
            near(5.104) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0406, -0.1, -2.0, 2.0)
            near(5.106) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0406, 0.1, -2.0, 2.0)
            near(5.110) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0609, -0.1, -2.0, 2.0)
            near(5.112) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0609, 0.1, -2.0, 2.0)
            near(5.116) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0912, -0.1, -2.0, 2.0)
            near(5.118) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset0912, 0.1, -2.0, 2.0)
            near(5.122) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset1218, -0.1, -2.0, 2.0)
            near(5.124) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset1218, 0.1, -2.0, 2.0)
            near(5.128) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset1822, -0.1, -2.0, 2.0)
            near(5.130) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset1822, 0.1, -2.0, 2.0)
            near(5.134) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset2200, -0.1, -2.0, 2.0)
            near(5.136) -> nudge(p, DoubleKey.ApsAutoIsfTodOffset2200, 0.1, -2.0, 2.0)
            near(5.138) -> toggle(p, BooleanKey.ApsAutoIsfShowCarbModelCurve)
            near(5.142) -> toggle(p, BooleanKey.ApsAutoIsfShowGraph5)
            near(5.156) -> toggle(p, BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled)
            // List 2
            near(5.164) -> toggle(p, BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)
            near(5.166) -> toggle(p, BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)
            near(5.182) -> nudge(p, DoubleKey.ApsAutoIsfUamBoostScale, -0.25, 0.1, 2.9, both = true)
            near(5.184) -> nudge(p, DoubleKey.ApsAutoIsfUamBoostScale, 0.25, 0.1, 2.9, both = true)
            near(5.186) -> nudge(p, DoubleKey.ApsAutoIsfUamBoostMaxBolus, -0.25, 0.1, 10.0, both = true)
            near(5.188) -> nudge(p, DoubleKey.ApsAutoIsfUamBoostMaxBolus, 0.25, 0.1, 10.0, both = true)
            near(5.190) -> boostIob(p, -5)
            near(5.192) -> boostIob(p, 5)
            near(5.194) -> toggle(p, BooleanKey.ApsAutoIsfUamBoostEnabled)
            near(5.196) -> toggle(p, BooleanKey.ApsAutoIsfUseUkf1ForDosing)
            near(5.198) -> toggle(p, BooleanKey.AutomationCodedLocationsEnabled)
            near(5.206) -> toggle(p, BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)
            near(5.210) -> toggle(p, BooleanNonKey.ApsAutoIsfProfileBatchAutoEnabled)
            near(5.212) -> toggle(p, BooleanNonKey.ApsAutoIsfProfileBatchRevertEnabled)
            near(5.214) -> toggle(p, BooleanNonKey.ApsAutoIsfProfileBatchRevertCEnabled)
            near(5.226) -> toggle(p, BooleanKey.ApsAutoIsfFastRiseEnabled)
            near(5.228) -> toggle(p, BooleanKey.ApsAutoIsfLowReboundGuardEnabled)
            near(5.230) -> toggle(p, BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled)
            near(5.232) -> toggle(p, BooleanKey.ApsAutoIsfShowInsulinTotals)
            near(5.234) -> toggle(p, BooleanKey.ApsAutoIsfLoopEveryMinute)
            near(5.236) -> toggle(p, BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual)
            else        -> null
        }
    }
}
