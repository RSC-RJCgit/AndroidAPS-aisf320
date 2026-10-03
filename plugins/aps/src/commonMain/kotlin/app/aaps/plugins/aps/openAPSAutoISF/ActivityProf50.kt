package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.math.abs

/**
 * True when [lowTargetMgdl] is the 6.8 mmol/L activity temp target.
 * 6.8 times 18.0 is the value the old app stored. This app may store 6.8 times its own factor instead.
 * The match is within 0.001 mg/dL.
 */
internal fun isActivityTempTarget(lowTargetMgdl: Double): Boolean {
    val classic = 6.8 * 18.0
    val app = 6.8 * Constants.MMOLL_TO_MGDL
    return abs(lowTargetMgdl - classic) <= 0.001 || abs(lowTargetMgdl - app) <= 0.001
}

/**
 * Activity profile cut. Glucose and delta are mg/dL. 153.1 is 8.5 mmol/L. 1.8 is 0.1 mmol/L.
 * The caller sets the current profile to 50% for 180 minutes. It does not change the acceleration weight.
 */
internal fun activityProf50ShouldFire(
    ready: Boolean,
    profilePercent: Int,
    profileIsBolus: Boolean,
    lowTargetMgdl: Double?,
    bg: Double,
    delta: Double,
): Boolean {
    if (!ready || profilePercent != 100 || profileIsBolus || lowTargetMgdl == null) return false
    return isActivityTempTarget(lowTargetMgdl) && bg <= 153.1 && delta <= 1.8
}
