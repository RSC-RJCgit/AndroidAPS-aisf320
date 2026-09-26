package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

/**
 * Whether a raised post-meal weight or a raised acceleration weight should go back to its baseline.
 * Acceleration is restored only when it sits above the baseline. A weight below the baseline is left
 * alone. A boost mark in the last 15 minutes blocks both restores.
 */
internal data class WeightRevertDecision(
    val restorePp: Boolean,
    val restoreAcce: Boolean,
    val reason: String,
)

internal val ppWeightBoostMarks: List<String> = listOf(
    "BolusGiven",
    "BolusGivenMild",
    "BolusGivenMildFailsafe",
    "High6PP",
    "HighOldPod",
    "PodChangeHighPP130",
    "OldPod2",
    "RecentPod",
    "HighDaytimeBrake",
    "HighEveNightBrake",
)

internal fun ppAcceWeightRevert(
    currentPp: Double,
    baselinePp: Double,
    currentAcce: Double,
    baselineAcce: Double,
    glucoseMgdl: Double,
    steps5: Int,
    steps30: Int,
    steps60: Int,
    noRecentHigh: Boolean,
    recentBoost: Boolean,
): WeightRevertDecision {
    val lowBg = glucoseMgdl < 153.1
    val activeMovement = steps5 > 100 || steps30 > 200 || steps60 > 300
    val trigger = lowBg || activeMovement || noRecentHigh
    val restorePp = !weightNear(currentPp, baselinePp) && trigger && !recentBoost
    val restoreAcce = currentAcce > baselineAcce && trigger && !recentBoost
    val reason = when {
        !restorePp && !restoreAcce -> ""
        lowBg -> "bg"
        activeMovement -> "activity"
        else -> "noHigh"
    }
    return WeightRevertDecision(restorePp, restoreAcce, reason)
}

internal fun weightNear(a: Double, b: Double): Boolean = abs(a - b) <= 0.001
