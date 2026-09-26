package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Whether a raised IOB threshold or a raised profile percent should go back to its baseline.
 * A value below the baseline is left alone. A boost mark in the last 15 minutes blocks both restores.
 * The trigger matches the post-meal and acceleration weight restore.
 */
internal data class DoseRevertDecision(
    val restoreIobTh: Boolean,
    val restoreProfilePercent: Boolean,
    val reason: String,
)

internal fun iobProfileRevert(
    currentIobTh: Int,
    baselineIobTh: Int,
    currentProfilePercent: Int,
    baselineProfilePercent: Int,
    glucoseMgdl: Double,
    steps5: Int,
    steps30: Int,
    steps60: Int,
    noRecentHigh: Boolean,
    recentBoost: Boolean,
): DoseRevertDecision {
    val lowBg = glucoseMgdl < 153.1
    val activeMovement = steps5 > 100 || steps30 > 200 || steps60 > 300
    val trigger = lowBg || activeMovement || noRecentHigh
    val restoreIobTh = currentIobTh > baselineIobTh && trigger && !recentBoost
    val restoreProfilePercent = currentProfilePercent > baselineProfilePercent && trigger && !recentBoost
    val reason = when {
        !restoreIobTh && !restoreProfilePercent -> ""
        lowBg -> "bg"
        activeMovement -> "activity"
        else -> "noHigh"
    }
    return DoseRevertDecision(restoreIobTh, restoreProfilePercent, reason)
}
