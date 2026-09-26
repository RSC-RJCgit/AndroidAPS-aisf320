package app.aaps.plugins.aps.openAPSAutoISF

/**
 * AlarmHypo1. Glucose and deltas are mg/dL. 54 is 3.0 mmol/L. 77.5 is 4.3 mmol/L.
 * -0.36 mg/dL is -0.02 mmol/L. -0.9 mg/dL is -0.05 mmol/L.
 * [hp] and [hp1] are the two hypo predictions in mmol/L. Pass null when a prediction is missing.
 * The caller does not lower the acceleration weight. Quiet hours do not close this gate.
 */
internal fun alarmHypo1ShouldFire(
    ready: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    acceWeight: Double,
    minuteOfDay: Int,
    steps60: Int,
    hp: Double?,
    hp1: Double?,
    recentBolusOrCarbs: Boolean,
): Boolean {
    if (!ready) return false
    val slowDecline = delta < -0.36 && shortDelta < -0.36 && bg < 77.5 && acceWeight <= 0.08
    val emergency = bg < 54.0
    val stepsDecline = timeWindowContains(minuteOfDay, 7, 0, 23, 0) &&
        delta <= -0.9 &&
        steps60 >= 102 &&
        bg < 77.5 &&
        acceWeight <= 0.08
    val predicted = hp != null && hp <= 3.4 && hp1 != null && hp1 <= 3.8 && acceWeight <= 0.08 && !recentBolusOrCarbs
    return slowDecline || emergency || stepsDecline || predicted
}

/**
 * AlarmHypo2. Glucose and deltas are mg/dL. 77.5 is 4.3 mmol/L. 99.1 is 5.5 mmol/L.
 * [hp] and [hp1] are the two hypo predictions in mmol/L. Pass null when a prediction is missing.
 */
internal fun alarmHypo2ShouldFire(
    ready: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    acceWeight: Double,
    steps30: Int,
    hp: Double?,
    hp1: Double?,
    recentBolusOrCarbs: Boolean,
): Boolean {
    if (!ready) return false
    val low = bg <= 77.5 ||
        (bg <= 99.1 && steps30 >= 1000) ||
        (hp != null && hp <= 3.4 && hp1 != null && hp1 <= 3.8 && !recentBolusOrCarbs)
    return delta <= 0.0 && shortDelta <= 0.0 && low && acceWeight <= 0.08
}
