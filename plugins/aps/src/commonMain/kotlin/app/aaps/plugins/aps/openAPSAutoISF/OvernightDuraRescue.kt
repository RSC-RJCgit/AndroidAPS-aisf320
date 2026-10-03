package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Flat high on the Low profile, from 02:00 until 04:00, when the duration factor leads the others.
 * 108.1 mg/dL is 6.0 mmol/L. 1.8 mg/dL is 0.1 mmol/L.
 * The caller switches to the Standard profile for 60 minutes. It does not change the factor weights.
 */
internal fun overnightDuraRescueShouldFire(
    ready: Boolean,
    rescueActive: Boolean,
    minuteOfDay: Int,
    onLowProfile: Boolean,
    bg: Double,
    shortDelta: Double,
    longDelta: Double,
    duraIsf: Double,
    finalIsf: Double,
    acceIsf: Double,
    bgIsf: Double,
    ppIsf: Double,
    smbSum30: Double,
    lowBgRecent: Boolean,
): Boolean {
    if (!ready || rescueActive || !onLowProfile || lowBgRecent) return false
    val duraDominant = duraIsf > 2.5 && finalIsf > 2.5 && duraIsf > finalIsf &&
        duraIsf >= acceIsf && duraIsf >= bgIsf && duraIsf >= ppIsf
    return timeWindowContains(minuteOfDay, 2, 0, 4, 0) &&
        bg > 108.1 &&
        duraDominant &&
        smbSum30 <= 0.0 &&
        longDelta > -1.8 && longDelta <= 1.8 &&
        shortDelta > -1.8 && shortDelta <= 1.8
}
