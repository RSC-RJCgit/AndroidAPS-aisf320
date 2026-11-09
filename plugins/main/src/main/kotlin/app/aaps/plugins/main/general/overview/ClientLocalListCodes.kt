package app.aaps.plugins.main.general.overview

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.abs

/**
 * List 1 and List 2 "This phone" on a Client (2026-11-05, per explicit request): the settings rows change this phone's own preference
 * only. No SMS, no CarePortal note (it would sync to NS and look as if the loop phone changed) and no TT. Rows that switch a
 * profile, an MJ/Steroid state, a tier or anything else are not here and stay relayed to the loop phone.
 *
 * KEEP IN STEP: each step and limit is copied from its handler in OpenAPSAutoISFPlugin.kt (the activeTtNear(code) block).
 * If a handler's step or limit changes there, change it here too.
 */
internal object ClientLocalListCodes {

    private sealed class Change {
        class Toggle(val key: BooleanKey) : Change()
        // live: the setting that follows the baseline. Moved too when it equals the baseline, as in the handler.
        class Nudge(val key: DoubleKey, val delta: Double, val min: Double, val max: Double, val live: DoubleKey? = null, val clampBoth: Boolean = false) : Change()
        class IntNudge(val key: IntKey, val delta: Int, val min: Int, val max: Int) : Change()
        class Both(val first: Nudge, val second: Nudge) : Change()
    }

    private val changes: Map<Double, Change> = buildMap {
        fun pair(down: Double, up: Double, key: DoubleKey, step: Double, min: Double, max: Double, live: DoubleKey? = null) {
            put(down, Change.Nudge(key, -step, min, max, live))
            put(up, Change.Nudge(key, step, min, max, live))
        }
        put(5.002, Change.Both(Change.Nudge(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, -0.01, 0.1, 0.5),
            Change.Nudge(DoubleKey.ApsAutoIsfMildBoostRatio, -0.01, 0.1, 1.0)))
        put(5.004, Change.Both(Change.Nudge(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, 0.01, 0.1, 0.5),
            Change.Nudge(DoubleKey.ApsAutoIsfMildBoostRatio, 0.01, 0.1, 1.0)))
        put(5.006, Change.Toggle(BooleanKey.ApsAutoIsfOldSensorAdjEnabled))
        put(5.008, Change.Toggle(BooleanKey.ApsAutoIsfBoostAutomationsEnabled))
        pair(5.012, 5.014, DoubleKey.ApsAutoIsfPpWeightNormal, 0.01, 0.0, 0.15, DoubleKey.ApsAutoIsfPpWeight)
        pair(5.016, 5.018, DoubleKey.ApsAutoIsfBgAccelWeightNormal, 0.05, 0.55, 1.00, DoubleKey.ApsAutoIsfBgAccelWeight)
        pair(5.022, 5.024, DoubleKey.ApsAutoIsfDuraWeightNormal, 0.1, 0.0, 3.0, DoubleKey.ApsAutoIsfDuraWeight)
        pair(5.026, 5.028, DoubleKey.ApsAutoIsfLibreSlopeOrig, 0.01, 0.60, 1.00, DoubleKey.FslCalSlope)
        pair(5.032, 5.034, DoubleKey.ApsAutoIsfLibreOffsetOrig, 0.05, 1.20, 1.60, DoubleKey.FslCalOffset)
        pair(5.036, 5.038, DoubleKey.ApsAutoIsfSmbOffsetOverride, 0.1, 0.50, 1.50)
        put(5.046, Change.IntNudge(IntKey.OverviewBolusPercentage, -5, 50, 100))
        put(5.048, Change.IntNudge(IntKey.OverviewBolusPercentage, 5, 50, 100))
        pair(5.052, 5.054, DoubleKey.ApsAutoIsfMildBoostRatio, 0.25, 0.1, 1.0)
        pair(5.056, 5.058, DoubleKey.ApsAutoIsfPpWeightHigh, 0.01, 0.0, 0.15)
        pair(5.062, 5.064, DoubleKey.ApsAutoIsfBgAccelWeightHigh, 0.01, 0.0, 1.00)
        pair(5.068, 5.070, DoubleKey.ApsAutoIsfHighBgWeight, 0.1, 0.0, 2.0)
        put(5.074, Change.IntNudge(IntKey.InsulinOrefPeak, -5, 35, 120))
        put(5.076, Change.IntNudge(IntKey.InsulinOrefPeak, 5, 35, 120))
        pair(5.080, 5.082, DoubleKey.ApsAutoIsfMaxLow, 0.1, 1.0, 3.0)
        pair(5.086, 5.088, DoubleKey.ApsAutoIsfMax, 0.1, 1.0, 3.0)
        pair(5.092, 5.094, DoubleKey.ApsAutoIsfTodOffset0002, 0.1, -2.0, 2.0)
        pair(5.098, 5.100, DoubleKey.ApsAutoIsfTodOffset0204, 0.1, -2.0, 2.0)
        pair(5.104, 5.106, DoubleKey.ApsAutoIsfTodOffset0406, 0.1, -2.0, 2.0)
        pair(5.110, 5.112, DoubleKey.ApsAutoIsfTodOffset0609, 0.1, -2.0, 2.0)
        pair(5.116, 5.118, DoubleKey.ApsAutoIsfTodOffset0912, 0.1, -2.0, 2.0)
        pair(5.122, 5.124, DoubleKey.ApsAutoIsfTodOffset1218, 0.1, -2.0, 2.0)
        pair(5.128, 5.130, DoubleKey.ApsAutoIsfTodOffset1822, 0.1, -2.0, 2.0)
        pair(5.134, 5.136, DoubleKey.ApsAutoIsfTodOffset2200, 0.1, -2.0, 2.0)
        put(5.138, Change.Toggle(BooleanKey.ApsAutoIsfShowCarbModelCurve))
        put(5.142, Change.Toggle(BooleanKey.ApsAutoIsfShowGraph5))
        put(5.156, Change.Toggle(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled))

        put(5.164, Change.Toggle(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled))
        put(5.166, Change.Toggle(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled))
        pair(5.182, 5.184, DoubleKey.ApsAutoIsfUamBoostScale, 0.25, 0.1, 2.9)
        val boostCap = DoubleKey.ApsAutoIsfUamBoostMaxBolus
        put(5.186, Change.Nudge(boostCap, -0.25, boostCap.min, boostCap.max, clampBoth = true))
        put(5.188, Change.Nudge(boostCap, 0.25, boostCap.min, boostCap.max, clampBoth = true))
        pair(5.190, 5.192, DoubleKey.ApsAutoIsfUamBoostMaxIobPercent, 5.0, 1.0, 100.0)
        put(5.194, Change.Toggle(BooleanKey.ApsAutoIsfUamBoostEnabled))
        put(5.196, Change.Toggle(BooleanKey.ApsAutoIsfUseUkf1ForDosing))
        put(5.198, Change.Toggle(BooleanKey.AutomationCodedLocationsEnabled))
        put(5.206, Change.Toggle(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual))
        put(5.210, Change.Toggle(BooleanKey.ApsAutoIsfProfileBatchAutoEnabled))
        put(5.212, Change.Toggle(BooleanKey.ApsAutoIsfProfileBatchRevertEnabled))
        put(5.214, Change.Toggle(BooleanKey.ApsAutoIsfProfileBatchRevertCEnabled))
        put(5.226, Change.Toggle(BooleanKey.ApsAutoIsfFastRiseEnabled))
        put(5.228, Change.Toggle(BooleanKey.ApsAutoIsfLowReboundGuardEnabled))
        put(5.230, Change.Toggle(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled))
        put(5.232, Change.Toggle(BooleanKey.ApsAutoIsfShowInsulinTotals))
        put(5.234, Change.Toggle(BooleanKey.ApsAutoIsfLoopEveryMinute))
    }

    private fun find(mmol: Double): Change? = changes.entries.firstOrNull { abs(it.key - mmol) <= 0.0000001 }?.value

    fun supports(vararg mmol: Double): Boolean = mmol.all { find(it) != null }

    fun toggleValue(preferences: Preferences, mmol: Double): Boolean? =
        (find(mmol) as? Change.Toggle)?.let { preferences.get(it.key) }

    /** Applies the code to this phone's own preferences. Returns a short "what changed" text, or null when not a local code. */
    fun apply(preferences: Preferences, mmol: Double): String? {
        val change = find(mmol) ?: return null
        return apply(preferences, change)
    }

    // As the handlers: a step down is held at the minimum only, a step up at the maximum only.
    private fun step(value: Double, delta: Double, min: Double, max: Double) = if (delta < 0) (value + delta).coerceAtLeast(min) else (value + delta).coerceAtMost(max)
    private fun step(value: Int, delta: Int, min: Int, max: Int) = if (delta < 0) (value + delta).coerceAtLeast(min) else (value + delta).coerceAtMost(max)

    private fun apply(preferences: Preferences, change: Change): String = when (change) {
        is Change.Toggle   -> {
            val newState = !preferences.get(change.key)
            preferences.put(change.key, newState)
            "${change.key.key} ${if (newState) "ON" else "OFF"}"
        }

        is Change.Nudge    -> {
            val current = preferences.get(change.key)
            val updated = if (change.clampBoth) (current + change.delta).coerceIn(change.min, change.max)
            else step(current, change.delta, change.min, change.max)
            preferences.put(change.key, updated)
            change.live?.let { live ->
                val currentLive = preferences.get(live)
                if (abs(currentLive - current) <= 0.001) preferences.put(live, step(currentLive, change.delta, change.min, change.max))
            }
            "${change.key.key} ${"%.2f".format(updated)}"
        }

        is Change.IntNudge -> {
            val updated = step(preferences.get(change.key), change.delta, change.min, change.max)
            preferences.put(change.key, updated)
            "${change.key.key} $updated"
        }

        is Change.Both     -> apply(preferences, change.first) + ", " + apply(preferences, change.second)
    }
}
