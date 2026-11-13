package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Exit from the 180 minute activity profile at 50%.
 *
 * Glucose and delta are mg/dL. 171.2 is 9.5 mmol/L. 153.1 is 8.5. 108.1 is 6.0. 1.8 is 0.1 mmol/L.
 * The caller switches to the standard profile at 100% and can put the IOB threshold back to 70.
 * It does not change the acceleration weight. A drop to 0.35 would stay, because the restore only raises it.
 * While a delayed bolus waits (DelayedBolus, ported from the 3426 app on 2026-11-14), the bolus that started it is not taken
 * as a sign of recovery: the caller passes delayedBolusPending from LongNonKey.WizardDelayedBolusPendingUntil.
 */
internal fun activityOffShouldFire(
    ready: Boolean,
    profilePercent: Int,
    lowTargetMgdl: Double?,
    bg: Double,
    delta: Double,
    cannulaHours: Double,
    lastBolusMinutes: Int,
    delayedBolusPending: Boolean,
): Boolean {
    if (!ready || profilePercent != 50) return false
    val climbedOnActivity = lowTargetMgdl != null && isActivityTempTarget(lowTargetMgdl) && bg >= 171.2
    val targetEnded = lowTargetMgdl == null &&
        bg <= 153.1 &&
        bg >= 108.1 &&
        delta >= 1.8 &&
        cannulaHours >= 3.0
    val bolusJustGiven = lastBolusMinutes <= 10 && !delayedBolusPending
    return climbedOnActivity || targetEnded || bolusJustGiven
}
