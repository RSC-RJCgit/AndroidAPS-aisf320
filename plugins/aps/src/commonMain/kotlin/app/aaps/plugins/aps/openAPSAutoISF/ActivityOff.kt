package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Exit from ActivityProf50's Tier A Low drop (2026-11-25, per explicit request, same as 3426; it used to end any 50% profile).
 *
 * Glucose and delta are mg/dL. 171.2 is 9.5 mmol/L. 153.1 is 8.5. 108.1 is 6.0. 1.8 is 0.1 mmol/L.
 * Only while the drop is active ([dropActive]) and the profile is at 100%, so a 50% hypo protection is never cut short.
 * [activityTt]: a temp target with the Activity reason runs. [ttRunning]: any temp target runs.
 * The caller puts back the saved roles and the saved Standard profile. It does not cancel the Activity TT (ActTtHighPause
 * handles BG over 10.0) and can put the IOB threshold back to 70.
 * While a delayed bolus waits (DelayedBolus, ported from the 3426 app on 2026-11-14), the bolus that started it is not taken
 * as a sign of recovery: the caller passes delayedBolusPending from LongNonKey.WizardDelayedBolusPendingUntil.
 */
internal fun activityOffShouldFire(
    ready: Boolean,
    dropActive: Boolean,
    profilePercent: Int,
    activityTt: Boolean,
    ttRunning: Boolean,
    bg: Double,
    delta: Double,
    cannulaHours: Double,
    lastBolusMinutes: Int,
    delayedBolusPending: Boolean,
    // 2026-11-26, per explicit request (same as 3426): BG over 8.5 rising fast (see highFastRiseNow) ends the drop at once
    highFastRise: Boolean = false,
): Boolean {
    if (!ready || !dropActive || profilePercent != 100) return false
    val climbedOnActivity = activityTt && bg >= 171.2
    val targetEnded = !ttRunning &&
        bg <= 153.1 &&
        bg >= 108.1 &&
        delta >= 1.8 &&
        cannulaHours >= 3.0
    val bolusJustGiven = lastBolusMinutes <= 10 && !delayedBolusPending
    return climbedOnActivity || targetEnded || bolusJustGiven || highFastRise
}
