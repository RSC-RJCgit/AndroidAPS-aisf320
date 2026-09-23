package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

/** True when two delivery ratios are within 0.001, so a stored value counts as the target. */
internal fun deliveryNear(current: Double, target: Double): Boolean =
    abs(current - target) <= 0.001

/**
 * True when SMBs in the last 5 minutes are close together.
 * The gap must be under 65 seconds and the count must be at least 4.
 */
internal fun smbIsStacking(intervalSec: Double, count5: Int): Boolean =
    intervalSec < 65.0 && count5 >= 4

/**
 * Put the delivery ratio back to [restingBaseline] when no temp target is on.
 * Leave it for 2 minutes after a rise boost. No temp target is used as that timer.
 * Leave it while SMBs are stacking and the ratio is already at the lower stacking target.
 * The caller does not add the higher-tier bump. That ladder is not in this app.
 */
internal fun delOffShouldRestore(
    currentRatio: Double,
    restingBaseline: Double,
    tempTargetSet: Boolean,
    atHardStackTarget: Boolean,
    smbStacking: Boolean,
    recentDeliveryBoost: Boolean,
): Boolean {
    if (tempTargetSet || recentDeliveryBoost) return false
    if (atHardStackTarget && smbStacking) return false
    return !deliveryNear(currentRatio, restingBaseline)
}

/**
 * Lower the delivery ratio while SMBs are stacking.
 * A boost mark from the last 3 minutes stays as it is. Carbs on board of 9 or more stay as they are.
 */
internal fun hardStackShouldReduce(
    atHardStackTarget: Boolean,
    smbStacking: Boolean,
    recentOwnBoost: Boolean,
    mealCob: Double,
): Boolean {
    if (atHardStackTarget || !smbStacking || recentOwnBoost) return false
    return mealCob < 9.0
}
