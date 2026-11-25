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
 * Activity drop to Tier A Low (2026-11-25, per explicit request, same as 3426): any temp target with the Activity reason
 * ([activityTt], any target), glucose at or under 8.5 mmol/L (153.1 mg/dL), the profile at 100%, no bolus mark, and the
 * drop not already active. No delta check any more. 8.5 stays below ActivityOff's 9.5 so the two cannot flip-flop.
 * The caller saves the roles, switches to Tier A Low at 100% with no end time, and ActivityOff puts the roles back.
 */
internal fun activityProf50ShouldFire(
    ready: Boolean,
    profilePercent: Int,
    profileIsBolus: Boolean,
    dropActive: Boolean,
    activityTt: Boolean,
    bg: Double,
): Boolean {
    if (!ready || profilePercent != 100 || profileIsBolus || dropActive || !activityTt) return false
    return bg <= 153.1
}
